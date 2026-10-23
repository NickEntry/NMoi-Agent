//! 技能（skills）系统。
//!
//! 约定与主流 agent 一致：每个技能是一个目录，里面有 `SKILL.md`（大小写不敏感）。
//! 头部是可选的 front matter：
//!
//! ```markdown
//! ---
//! name: pdf-tools
//! description: 读写与合并 PDF
//! version: 1.2
//! ---
//! # 正文…
//! ```
//!
//! 三条行为约定（按用户要求）：
//!
//! 1. **开关只是"提示存在"**：插入上下文的是**已启用**技能的 `name` + `description`，
//!    不是全文。模型知道有这么个技能，需要时再读全文。
//! 2. **读取工具覆盖 skills**：技能目录本身就是一个工作区根，用同一套
//!    读取规则（三级截断 + `force_full`）读技能正文，不另造一套。
//! 3. **容器开启时映射到 `/skills`**：只读挂载，路径稳定，正文里的相对引用不用改。

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use crate::archive::truncate_chars;
use crate::workspace::{ReadOptions, ReadResult, Workspace, WorkspaceError};

/// 技能正文文件名（大小写不敏感比较）。
pub const SKILL_FILE: &str = "skill.md";
/// 发现深度：`skills/<name>/SKILL.md` 与 `skills/<category>/<name>/SKILL.md` 都要能找到。
pub const DEFAULT_SCAN_DEPTH: usize = 2;
/// 插入上下文时的描述长度上限（一行，避免把上下文吃掉）。
pub const DEFAULT_DESCRIPTION_CHARS: usize = 160;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SkillMeta {
    pub name: String,
    pub description: String,
    pub version: Option<String>,
    /// 相对技能根的路径，例如 `pdf-tools/SKILL.md`。
    pub relative_path: String,
    pub enabled: bool,
    /// front matter 没给 name / description 时用了兜底解析，UI 上值得标一下。
    pub inferred: bool,
}

/// 容器映射。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContainerMount {
    pub host_path: PathBuf,
    pub guest_path: String,
    pub read_only: bool,
}

/// 技能库。默认只发现、不启用——启用是用户的动作。
#[derive(Debug, Clone)]
pub struct SkillRegistry {
    root: PathBuf,
    skills: BTreeMap<String, SkillMeta>,
    scan_depth: usize,
}

impl SkillRegistry {
    /// 扫描技能根目录。目录不存在时返回空库（不报错：技能是可选的）。
    pub fn discover(root: impl AsRef<Path>) -> Result<Self, SkillError> {
        Self::discover_with_depth(root, DEFAULT_SCAN_DEPTH)
    }

    pub fn discover_with_depth(root: impl AsRef<Path>, depth: usize) -> Result<Self, SkillError> {
        let root = root.as_ref().to_path_buf();
        let mut skills = BTreeMap::new();
        if root.is_dir() {
            scan_dir(&root, &root, depth, &mut skills)?;
        }
        Ok(Self {
            root,
            skills,
            scan_depth: depth,
        })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn scan_depth(&self) -> usize {
        self.scan_depth
    }

    pub fn len(&self) -> usize {
        self.skills.len()
    }

    pub fn is_empty(&self) -> bool {
        self.skills.is_empty()
    }

    pub fn get(&self, name: &str) -> Option<&SkillMeta> {
        self.skills.get(name)
    }

    /// 全部技能（按名字排序）。
    pub fn all(&self) -> Vec<&SkillMeta> {
        self.skills.values().collect()
    }

    /// 已启用的技能。
    pub fn enabled(&self) -> Vec<&SkillMeta> {
        self.skills.values().filter(|skill| skill.enabled).collect()
    }

    /// 开关。返回改动前的状态。
    pub fn set_enabled(&mut self, name: &str, enabled: bool) -> Result<bool, SkillError> {
        let skill = self
            .skills
            .get_mut(name)
            .ok_or_else(|| SkillError::NotFound {
                name: name.to_string(),
            })?;
        let previous = skill.enabled;
        skill.enabled = enabled;
        Ok(previous)
    }

    pub fn enable_all(&mut self) {
        for skill in self.skills.values_mut() {
            skill.enabled = true;
        }
    }

    /// 插进上下文的技能提示块：**只有 name + description**。
    ///
    /// `container_root` 为 `Some("/skills")` 时，提示里带容器内的读取路径。
    pub fn context_block(&self, container_root: Option<&str>) -> Option<String> {
        self.context_block_with_limit(container_root, DEFAULT_DESCRIPTION_CHARS)
    }

    pub fn context_block_with_limit(
        &self,
        container_root: Option<&str>,
        description_chars: usize,
    ) -> Option<String> {
        let enabled = self.enabled();
        if enabled.is_empty() {
            return None;
        }

        let mut out = String::from("## 可用技能\n\n");
        for skill in &enabled {
            let description = truncate_chars(&skill.description, description_chars);
            out.push_str(&format!("- **{}**：{}\n", skill.name, description));
        }
        out.push_str("\n以上只是技能索引：需要用到某个技能时，先读取它的 SKILL.md 全文再动手。\n");
        match container_root {
            Some(root) => out.push_str(&format!(
                "技能目录在容器的 `{}/<技能名>/SKILL.md`（只读挂载）。\n",
                root.trim_end_matches('/')
            )),
            None => out.push_str(&format!(
                "技能目录在 `{}/<技能名>/SKILL.md`。\n",
                self.root.display()
            )),
        }
        Some(out)
    }

    /// 用同一套工作区读取规则读技能正文。
    pub fn read(&self, name: &str, options: &ReadOptions) -> Result<ReadResult, SkillError> {
        let skill = self.skills.get(name).ok_or_else(|| SkillError::NotFound {
            name: name.to_string(),
        })?;
        let workspace = Workspace::open(&self.root)?;
        Ok(workspace.read_file(&skill.relative_path, options)?)
    }

    /// 把技能根当成工作区：读工具因此天然覆盖 skills（目录、查找、搜索同样可用）。
    pub fn as_workspace(&self) -> Result<Workspace, SkillError> {
        Ok(Workspace::open(&self.root)?)
    }

    /// 容器挂载声明。只有容器能力被打开时才应该被使用（见 `core::policy`）。
    pub fn container_mount(&self, guest_path: &str) -> ContainerMount {
        ContainerMount {
            host_path: self.root.clone(),
            guest_path: guest_path.trim_end_matches('/').to_string(),
            read_only: true,
        }
    }

    /// 重新扫描（技能目录被外部改动后调用）。
    pub fn rescan(&mut self) -> Result<(), SkillError> {
        let enabled: Vec<String> = self
            .skills
            .values()
            .filter(|skill| skill.enabled)
            .map(|skill| skill.name.clone())
            .collect();
        let refreshed = Self::discover_with_depth(&self.root, self.scan_depth)?;
        self.skills = refreshed.skills;
        for name in enabled {
            if let Some(skill) = self.skills.get_mut(&name) {
                skill.enabled = true;
            }
        }
        Ok(())
    }
}

fn scan_dir(
    root: &Path,
    dir: &Path,
    depth_left: usize,
    out: &mut BTreeMap<String, SkillMeta>,
) -> Result<(), SkillError> {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Ok(()),
    };

    let mut subdirs = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let file_name = entry.file_name().to_string_lossy().to_string();
        if file_name.starts_with('.') {
            continue;
        }
        let file_type = match entry.file_type() {
            Ok(file_type) => file_type,
            Err(_) => continue,
        };
        if file_type.is_dir() {
            subdirs.push(path);
        } else if file_type.is_file() && file_name.to_lowercase() == SKILL_FILE {
            if let Some(skill) = parse_skill(root, &path)? {
                // 同名技能先到先得：外层目录优先（避免被深层同名覆盖）
                out.entry(skill.name.clone()).or_insert(skill);
            }
        }
    }

    if depth_left > 0 {
        subdirs.sort();
        for subdir in subdirs {
            scan_dir(root, &subdir, depth_left - 1, out)?;
        }
    }
    Ok(())
}

fn parse_skill(root: &Path, path: &Path) -> Result<Option<SkillMeta>, SkillError> {
    let content = fs::read_to_string(path).map_err(|err| SkillError::Io {
        path: path.display().to_string(),
        source: err,
    })?;

    let relative = path
        .strip_prefix(root)
        .unwrap_or(path)
        .to_string_lossy()
        .replace('\\', "/");
    let fallback_name = path
        .parent()
        .and_then(|parent| parent.file_name())
        .map(|name| name.to_string_lossy().to_string())
        .unwrap_or_else(|| relative.clone());

    let (front, body) = split_front_matter(&content);
    let mut name = front.get("name").cloned();
    let mut description = front.get("description").cloned();
    let version = front.get("version").cloned();

    let mut inferred = false;
    if name.is_none() {
        // 目录名比标题更可靠：它是技能的身份，也是 /skills/<name>/SKILL.md 的路径段。
        // 标题只作为最后退路（比如技能文件直接放在根目录、没有父目录名可借用）。
        let directory_name = path
            .parent()
            .filter(|parent| *parent != root)
            .map(|parent| {
                parent
                    .file_name()
                    .map(|name| name.to_string_lossy().to_string())
                    .unwrap_or_default()
            })
            .filter(|name| !name.is_empty());
        name = directory_name
            .or_else(|| first_heading(body))
            .or(Some(fallback_name));
        inferred = true;
    }
    if description.is_none() {
        description = first_paragraph(body);
        inferred = true;
    }

    let Some(name) = name.filter(|name| !name.trim().is_empty()) else {
        return Ok(None);
    };
    let description = description.unwrap_or_default();

    Ok(Some(SkillMeta {
        name: name.trim().to_string(),
        description: description.trim().replace('\n', " "),
        version,
        relative_path: relative,
        enabled: false,
        inferred,
    }))
}

/// 切出 front matter。没有 front matter（或没闭合）时 front 为空、body 就是全文。
fn split_front_matter(content: &str) -> (BTreeMap<String, String>, &str) {
    let trimmed = content.strip_prefix('\u{feff}').unwrap_or(content);
    if !trimmed.starts_with("---") {
        return (BTreeMap::new(), trimmed);
    }

    let mut offset = 0usize;
    let mut front_lines: Vec<&str> = Vec::new();
    let mut lines = trimmed.split_inclusive('\n');
    let Some(first) = lines.next() else {
        return (BTreeMap::new(), trimmed);
    };
    if first.trim() != "---" {
        return (BTreeMap::new(), trimmed);
    }
    offset += first.len();

    let mut closed = false;
    for line in lines {
        let is_end = line.trim() == "---";
        offset += line.len();
        if is_end {
            closed = true;
            break;
        }
        front_lines.push(line);
    }
    if !closed {
        // 没闭合的 front matter 不当元数据用，避免把正文吃掉
        return (BTreeMap::new(), trimmed);
    }

    let mut map = BTreeMap::new();
    for line in front_lines {
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        let key = key.trim().to_lowercase();
        let value = value.trim().trim_matches(['"', '\'']).to_string();
        if !key.is_empty() && !value.is_empty() {
            map.insert(key, value);
        }
    }
    (map, &trimmed[offset..])
}

fn first_heading(body: &str) -> Option<String> {
    body.lines()
        .find(|line| line.trim_start().starts_with('#'))
        .map(|line| line.trim_start_matches('#').trim().to_string())
        .filter(|line| !line.is_empty())
}

fn first_paragraph(body: &str) -> Option<String> {
    body.lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && !line.starts_with('#') && *line != "---")
        .map(|line| line.to_string())
}

#[derive(Debug, thiserror::Error)]
pub enum SkillError {
    #[error("技能不存在: {name}")]
    NotFound { name: String },
    #[error("读取技能失败 {path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error(transparent)]
    Workspace(#[from] WorkspaceError),
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::workspace::ReadOptions;

    fn fixture() -> (tempfile::TempDir, SkillRegistry) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("skills");
        fs::create_dir_all(root.join("pdf-tools")).unwrap();
        fs::create_dir_all(root.join("tools/web-search")).unwrap();
        fs::create_dir_all(root.join("no-meta")).unwrap();

        fs::write(
            root.join("pdf-tools/SKILL.md"),
            "---\nname: pdf-tools\ndescription: 读写与合并 PDF\nversion: 1.2\n---\n# PDF 工具\n\n用 qpdf 合并。\n",
        )
        .unwrap();
        fs::write(
            root.join("tools/web-search/skill.md"),
            "---\nname: web-search\ndescription: 联网搜索并抓正文\n---\n正文\n",
        )
        .unwrap();
        // 没有 front matter：靠标题与首段兜底
        fs::write(
            root.join("no-meta/SKILL.md"),
            "# 无元数据技能\n\n这段是描述。\n\n其余内容。\n",
        )
        .unwrap();
        // 不是技能目录，应该被忽略
        fs::write(root.join("README.md"), "不是技能\n").unwrap();

        let registry = SkillRegistry::discover(&root).unwrap();
        (dir, registry)
    }

    #[test]
    fn discovers_nested_and_top_level_skills() {
        let (_dir, registry) = fixture();
        assert_eq!(registry.len(), 3);
        let names: Vec<&str> = registry.all().iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, vec!["no-meta", "pdf-tools", "web-search"]);
    }

    #[test]
    fn name_is_case_insensitive_for_skill_file() {
        let (_dir, registry) = fixture();
        assert!(
            registry.get("web-search").is_some(),
            "小写 skill.md 也要识别"
        );
        assert_eq!(
            registry.get("web-search").unwrap().relative_path,
            "tools/web-search/skill.md"
        );
    }

    #[test]
    fn falls_back_to_heading_and_paragraph() {
        let (_dir, registry) = fixture();
        let skill = registry.get("no-meta").unwrap();
        assert_eq!(skill.name, "no-meta"); // 目录名兜底（front matter 没有 name）
        assert_eq!(skill.description, "这段是描述。");
        assert!(skill.inferred);
    }

    #[test]
    fn nothing_is_enabled_by_default() {
        let (_dir, registry) = fixture();
        assert!(registry.enabled().is_empty());
        assert!(registry.context_block(None).is_none());
    }

    #[test]
    fn context_block_has_only_name_and_description() {
        let (_dir, mut registry) = fixture();
        registry.set_enabled("pdf-tools", true).unwrap();
        registry.set_enabled("web-search", true).unwrap();

        let block = registry.context_block(None).unwrap();
        assert!(block.contains("**pdf-tools**：读写与合并 PDF"));
        assert!(block.contains("**web-search**：联网搜索并抓正文"));
        // 没启用不进上下文
        assert!(!block.contains("no-meta"));
        // 正文细节不进去
        assert!(!block.contains("qpdf"));
        assert!(block.contains("技能索引"));
    }

    #[test]
    fn context_block_mentions_container_path_when_available() {
        let (_dir, mut registry) = fixture();
        registry.set_enabled("pdf-tools", true).unwrap();
        let block = registry.context_block(Some("/skills")).unwrap();
        assert!(block.contains("/skills/<技能名>/SKILL.md"));
    }

    #[test]
    fn toggle_returns_previous_state() {
        let (_dir, mut registry) = fixture();
        assert!(!registry.set_enabled("pdf-tools", true).unwrap());
        assert!(registry.set_enabled("pdf-tools", false).unwrap());
        assert!(matches!(
            registry.set_enabled("nope", true),
            Err(SkillError::NotFound { .. })
        ));
    }

    #[test]
    fn read_uses_workspace_rules() {
        let (_dir, registry) = fixture();
        let full = registry.read("pdf-tools", &ReadOptions::full()).unwrap();
        assert!(full.content.contains("用 qpdf 合并"));

        // 同一套截断规则：max_lines=1 只给一行
        let clipped = registry
            .read(
                "pdf-tools",
                &ReadOptions {
                    max_lines: 1,
                    ..ReadOptions::default()
                },
            )
            .unwrap();
        assert_eq!(clipped.returned_lines, 1);
        assert!(clipped.truncated);
    }

    #[test]
    fn skills_root_can_be_used_as_workspace() {
        let (_dir, registry) = fixture();
        let workspace = registry.as_workspace().unwrap();
        let listing = workspace
            .list_dir("", &crate::workspace::ListOptions::default())
            .unwrap();
        assert!(listing
            .entries
            .iter()
            .any(|entry| entry.name == "pdf-tools"));
    }

    #[test]
    fn container_mount_is_read_only_and_stable() {
        let (_dir, registry) = fixture();
        let mount = registry.container_mount("/skills/");
        assert_eq!(mount.guest_path, "/skills");
        assert!(mount.read_only);
        assert!(mount.host_path.ends_with("skills"));
    }

    #[test]
    fn rescan_keeps_enabled_state() {
        let (dir, mut registry) = fixture();
        registry.set_enabled("pdf-tools", true).unwrap();
        let root = dir.path().join("skills");
        fs::create_dir_all(root.join("new-skill")).unwrap();
        fs::write(
            root.join("new-skill/SKILL.md"),
            "---\nname: new-skill\ndescription: 新技能\n---\n",
        )
        .unwrap();

        registry.rescan().unwrap();
        assert_eq!(registry.len(), 4);
        assert!(
            registry.get("pdf-tools").unwrap().enabled,
            "重新扫描不该丢掉开关"
        );
        assert!(!registry.get("new-skill").unwrap().enabled);
    }
}
