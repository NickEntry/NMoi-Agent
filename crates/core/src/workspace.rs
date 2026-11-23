//! 工作区文件工具（用户需求：读写、查找、看目录、搜索、编辑；不依赖容器）。
//!
//! 设计要点：
//!
//! 1. **路径只能落在根目录内**。绝对路径、`..` 逃逸、指向外部的符号链接一律拒绝。
//! 2. **读取默认按预算截断**，且截断必须"说出来"：返回 `truncated` 与剩余行数，
//!    模型才知道该继续读还是换工具。
//! 3. **超长单行单独处理**：不静默丢，保留头部 + 标记原始字节数，并记进 `long_lines`，
//!    调用方可以按行跳读，或者显式全量读。
//! 4. **`force_full` 优先级最高**：忽略行数、字节、单行、二进制与总大小限制。
//!    这是用户明确要的逃生口——代价由调用方承担（可能把整个大文件塞进上下文）。

use std::collections::HashMap;
use std::fs;
use std::path::{Component, Path, PathBuf};

/// 默认最多返回 2000 行。
pub const DEFAULT_MAX_LINES: usize = 2_000;
/// 单行超过 4 KiB 就截断并在结果里标记。
pub const DEFAULT_MAX_LINE_BYTES: usize = 4_000;
/// 单次读取默认最多 256 KiB。
pub const DEFAULT_MAX_BYTES: usize = 256 * 1024;
/// 非全量读取时的文件大小上限（64 MiB）。超过就拒绝，避免一次读爆内存。
pub const HARD_FILE_CAP: u64 = 64 * 1024 * 1024;

/// 一次调用最多吐多少字节（D343）。
///
/// **`force_full` 也绕不过它**：那个参数是"别按默认的行数/单行上限截断"，
/// 不是"把整个文件塞进上下文"。4.5 MB 的 txt 一次吐 10 万字，六次就把窗口撑爆——
/// 而撑爆之后要撤回的正是这一段，代价比一开始就分页大得多。
///
/// 48 KB ≈ 中文 1.6 万字、英文 4.8 万字符；一页的量级。
pub const MAX_BURST_BYTES: usize = 48 * 1024;
/// 递归查找的最大深度。
pub const MAX_WALK_DEPTH: usize = 20;

/// 默认跳过的目录：版本库、依赖、构建产物。
pub const DEFAULT_SKIP_DIRS: &[&str] = &[
    ".git",
    ".hg",
    ".svn",
    "node_modules",
    "target",
    ".venv",
    "venv",
    "__pycache__",
    "dist",
    "build",
];

#[derive(Debug, Clone)]
pub struct Workspace {
    root: PathBuf,
}

impl Workspace {
    /// 打开（必要时创建）工作区根目录。根目录会被 canonicalize。
    pub fn open(root: impl AsRef<Path>) -> Result<Self, WorkspaceError> {
        let root = root.as_ref();
        fs::create_dir_all(root)?;
        let canonical = fs::canonicalize(root)?;
        Ok(Self { root: canonical })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// 把相对路径解析成绝对路径，并保证不越界。
    pub fn resolve(&self, relative: &str) -> Result<PathBuf, WorkspaceError> {
        if relative.trim().is_empty() {
            return Err(WorkspaceError::EmptyPath);
        }
        let path = Path::new(relative);
        if path.is_absolute() {
            return Err(WorkspaceError::OutsideRoot {
                path: relative.to_string(),
            });
        }
        let mut out = self.root.clone();
        for component in path.components() {
            match component {
                Component::Normal(part) => out.push(part),
                Component::CurDir => {}
                Component::ParentDir => {
                    if !out.pop() || !out.starts_with(&self.root) {
                        return Err(WorkspaceError::OutsideRoot {
                            path: relative.to_string(),
                        });
                    }
                }
                Component::RootDir | Component::Prefix(_) => {
                    return Err(WorkspaceError::OutsideRoot {
                        path: relative.to_string(),
                    })
                }
            }
        }
        Ok(out)
    }

    /// 已存在路径的越界检查（符号链接可能指向根目录外）。
    fn ensure_inside(&self, path: &Path) -> Result<(), WorkspaceError> {
        let canonical = fs::canonicalize(path)?;
        if !canonical.starts_with(&self.root) {
            return Err(WorkspaceError::OutsideRoot {
                path: path.display().to_string(),
            });
        }
        Ok(())
    }

    pub fn read_file(
        &self,
        relative: &str,
        options: &ReadOptions,
    ) -> Result<ReadResult, WorkspaceError> {
        let path = self.resolve(relative)?;
        let meta = fs::metadata(&path).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => WorkspaceError::NotFound {
                path: relative.to_string(),
            },
            _ => WorkspaceError::Io(e),
        })?;
        if meta.is_dir() {
            return Err(WorkspaceError::NotAFile {
                path: relative.to_string(),
            });
        }
        self.ensure_inside(&path)?;

        if !options.force_full && meta.len() > HARD_FILE_CAP {
            return Err(WorkspaceError::FileTooLarge {
                path: relative.to_string(),
                bytes: meta.len(),
                cap: HARD_FILE_CAP,
            });
        }

        let bytes = fs::read(&path)?;
        let total_bytes = bytes.len() as u64;
        let binary = bytes.contains(&0);
        if binary && !options.force_full {
            return Err(WorkspaceError::BinaryFile {
                path: relative.to_string(),
            });
        }
        let lossy = std::str::from_utf8(&bytes).is_err();
        let text = String::from_utf8_lossy(&bytes).into_owned();

        let total_lines = text.lines().count();
        // `force_full` 不再把起始行重置成 1：它现在的意思是"别按默认上限截断"，
        // 而分页读到第二页时必须能接着往下（D343）。
        let start_line = options.start_line.max(1);
        let mut out = String::new();
        let mut returned_lines = 0usize;
        let mut returned_bytes = 0usize;
        let mut long_lines = Vec::new();
        let mut truncation: Option<Truncation> = None;

        for (index, raw_line) in text.split_inclusive('\n').enumerate() {
            let line_no = index + 1;
            if line_no < start_line {
                continue;
            }
            if !options.force_full && returned_lines >= options.max_lines {
                truncation = Some(Truncation {
                    reason: TruncationReason::LineLimit,
                    remaining_lines: total_lines.saturating_sub(returned_lines + start_line - 1),
                    remaining_bytes: total_bytes.saturating_sub(returned_bytes as u64),
                });
                break;
            }

            let mut piece = raw_line.to_string();
            if !options.force_full && raw_line.len() > options.max_line_bytes {
                let kept = truncate_utf8(raw_line, options.max_line_bytes);
                let kept_bytes = kept.len();
                piece = format!(
                    "{kept}… [单行过长：{} 字节，已截断到 {} 字节；需要全文用 force_full=true]",
                    raw_line.len(),
                    kept_bytes
                );
                long_lines.push(LongLine {
                    line: line_no,
                    bytes: raw_line.len(),
                    kept_bytes,
                });
            }

            if !options.force_full
                && returned_lines > 0
                && returned_bytes + piece.len() > options.max_bytes
            {
                truncation = Some(Truncation {
                    reason: TruncationReason::ByteLimit,
                    remaining_lines: total_lines.saturating_sub(returned_lines + start_line - 1),
                    remaining_bytes: total_bytes.saturating_sub(returned_bytes as u64),
                });
                break;
            }

            // 硬上限，`force_full` 也绕不过（D343）：一条工具结果大到能撑爆上下文时，
            // 撤回它的代价比一开始就分页大得多。
            if returned_lines > 0 && returned_bytes + piece.len() > MAX_BURST_BYTES {
                truncation = Some(Truncation {
                    reason: TruncationReason::BurstLimit,
                    remaining_lines: total_lines.saturating_sub(returned_lines + start_line - 1),
                    remaining_bytes: total_bytes.saturating_sub(returned_bytes as u64),
                });
                break;
            }

            if options.numbered {
                out.push_str(&format!("{:>6}| ", line_no));
            }
            out.push_str(&piece);
            returned_lines += 1;
            returned_bytes += piece.len();
        }

        let hint = build_hint(
            options,
            total_lines,
            start_line,
            returned_lines,
            &long_lines,
            truncation.as_ref(),
        );

        Ok(ReadResult {
            path: relative.to_string(),
            content: out,
            total_lines,
            total_bytes,
            returned_lines,
            truncated: truncation.is_some(),
            truncation,
            long_lines,
            lossy,
            hint,
        })
    }

    /// 建目录（父目录一起建）。目录**已经存在**时返回 `AlreadyExists`，不是成功——
    /// 界面上"新建文件夹"点了没反应，比报一句"已经有了"更让人困惑。
    pub fn make_dir(&self, relative: &str) -> Result<WriteResult, WorkspaceError> {
        let path = self.resolve(relative)?;
        if path.exists() {
            return Err(WorkspaceError::AlreadyExists {
                path: relative.to_string(),
            });
        }
        fs::create_dir_all(&path)?;
        self.ensure_inside(&path)?;
        Ok(WriteResult {
            path: relative.to_string(),
            bytes: 0,
        })
    }

    /// 改名 / 移动。**只在根内**：`to` 越界与 `relative` 越界同样拒绝。
    ///
    /// 目标已存在时拒绝，不覆盖：改名撞上一个同名文件，覆盖掉的那一份没人能找回来。
    pub fn rename_entry(&self, relative: &str, to: &str) -> Result<RenameResult, WorkspaceError> {
        let from = self.resolve(relative)?;
        if !from.exists() {
            return Err(WorkspaceError::NotFound {
                path: relative.to_string(),
            });
        }
        self.ensure_inside(&from)?;
        let target = self.resolve(to)?;
        if target.exists() {
            return Err(WorkspaceError::AlreadyExists {
                path: to.to_string(),
            });
        }
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
            self.ensure_inside(parent)?;
        }
        fs::rename(&from, &target)?;
        Ok(RenameResult {
            from: relative.to_string(),
            to: to.to_string(),
        })
    }

    /// 删一个文件或一整棵目录。
    ///
    /// **不跟随符号链接**：目录里指向外面的链接删掉的是链接本身，不是它指向的东西。
    /// 返回删掉的条目数，界面用它说"删了 N 项"——只说"已删除"，用户不知道有没有删干净。
    pub fn remove_entry(&self, relative: &str) -> Result<RemoveResult, WorkspaceError> {
        if relative.trim().is_empty() {
            return Err(WorkspaceError::EmptyPath);
        }
        let path = self.resolve(relative)?;
        let meta = fs::symlink_metadata(&path).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => WorkspaceError::NotFound {
                path: relative.to_string(),
            },
            _ => WorkspaceError::Io(e),
        })?;
        // 判的是**父目录**在不在根里，不是条目自己：条目是符号链接时，`canonicalize` 会跟到
        // 目标上——指向外面就被拒，而"删掉这个链接"恰恰是安全的（删的是链接，不是它指的东西）。
        // 父目录这一层挡住的是另一种：路径中间有一段是通到外面的链接。
        let parent = path.parent().map(Path::to_path_buf);
        match parent {
            Some(parent) if parent.starts_with(&self.root) && parent != self.root => {
                self.ensure_inside(&parent)?;
            }
            _ => {}
        }
        let is_dir = meta.is_dir();
        let mut removed = 1usize;
        if is_dir {
            removed = count_tree(&path).max(1);
            fs::remove_dir_all(&path)?;
        } else {
            fs::remove_file(&path)?;
        }
        Ok(RemoveResult {
            path: relative.to_string(),
            is_dir,
            removed,
        })
    }

    pub fn write_file(&self, relative: &str, content: &str) -> Result<WriteResult, WorkspaceError> {
        let path = self.resolve(relative)?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
            self.ensure_inside(parent)?;
        }
        fs::write(&path, content.as_bytes())?;
        Ok(WriteResult {
            path: relative.to_string(),
            bytes: content.len(),
        })
    }

    /// 精确文本替换。默认要求唯一匹配，避免"改错地方"。
    pub fn edit_file(
        &self,
        relative: &str,
        old: &str,
        new: &str,
        replace_all: bool,
    ) -> Result<EditFileResult, WorkspaceError> {
        if old.is_empty() {
            return Err(WorkspaceError::EmptyPattern);
        }
        let path = self.resolve(relative)?;
        self.ensure_inside(&path)?;
        let content = fs::read_to_string(&path).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => WorkspaceError::NotFound {
                path: relative.to_string(),
            },
            std::io::ErrorKind::InvalidData => WorkspaceError::BinaryFile {
                path: relative.to_string(),
            },
            _ => WorkspaceError::Io(e),
        })?;

        let matches = content.matches(old).count();
        if matches == 0 {
            return Err(WorkspaceError::NoMatch {
                path: relative.to_string(),
            });
        }
        if matches > 1 && !replace_all {
            return Err(WorkspaceError::AmbiguousMatch {
                path: relative.to_string(),
                matches,
            });
        }
        let updated = if replace_all {
            content.replace(old, new)
        } else {
            content.replacen(old, new, 1)
        };
        let before_bytes = content.len();
        fs::write(&path, updated.as_bytes())?;
        Ok(EditFileResult {
            path: relative.to_string(),
            replacements: if replace_all { matches } else { 1 },
            bytes_before: before_bytes,
            bytes_after: updated.len(),
        })
    }

    pub fn list_dir(
        &self,
        relative: &str,
        options: &ListOptions,
    ) -> Result<ListResult, WorkspaceError> {
        let path = if relative.trim().is_empty() {
            self.root.clone()
        } else {
            self.resolve(relative)?
        };
        if !path.is_dir() {
            return Err(WorkspaceError::NotADirectory {
                path: relative.to_string(),
            });
        }
        self.ensure_inside(&path)?;

        let mut entries = Vec::new();
        collect_entries(&path, &self.root, 1, options, &mut entries)?;
        entries.truncate(options.max_entries);
        Ok(ListResult {
            path: relative.to_string(),
            entries,
        })
    }

    /// 递归找文件名。`pattern` 支持 `*` / `?` / `**`，匹配的是相对路径。
    pub fn find(&self, pattern: &str, options: &FindOptions) -> Result<FindResult, WorkspaceError> {
        if pattern.trim().is_empty() {
            return Err(WorkspaceError::EmptyPattern);
        }
        let base = if options.path.trim().is_empty() {
            self.root.clone()
        } else {
            self.resolve(&options.path)?
        };
        if !base.exists() {
            return Err(WorkspaceError::NotFound {
                path: options.path.clone(),
            });
        }
        self.ensure_inside(&base)?;

        let mut matches = Vec::new();
        walk(
            &base,
            &self.root,
            1,
            options.max_depth,
            options.include_hidden,
            &mut |path| {
                let relative = path
                    .strip_prefix(&self.root)
                    .unwrap_or(path)
                    .to_string_lossy()
                    .replace('\\', "/");
                if glob_match(pattern, &relative) {
                    matches.push(relative);
                }
                matches.len() < options.max_results * 4
            },
        )?;
        matches.sort();
        matches.truncate(options.max_results);
        Ok(FindResult {
            pattern: pattern.to_string(),
            matches,
        })
    }

    /// 文本搜索，返回命中行。跳过二进制、超大文件与默认忽略目录。
    pub fn search(
        &self,
        query: &str,
        options: &SearchOptions,
    ) -> Result<SearchResult, WorkspaceError> {
        if query.is_empty() {
            return Err(WorkspaceError::EmptyPattern);
        }
        let base = if options.path.trim().is_empty() {
            self.root.clone()
        } else {
            self.resolve(&options.path)?
        };
        if !base.exists() {
            return Err(WorkspaceError::NotFound {
                path: options.path.clone(),
            });
        }
        self.ensure_inside(&base)?;

        let needle = if options.ignore_case {
            query.to_lowercase()
        } else {
            query.to_string()
        };
        let mut hits = Vec::new();
        walk(
            &base,
            &self.root,
            1,
            options.max_depth,
            options.include_hidden,
            &mut |path| {
                let meta = match fs::metadata(path) {
                    Ok(meta) => meta,
                    Err(_) => return true,
                };
                if !meta.is_file() || meta.len() > options.max_file_bytes {
                    return true;
                }
                let bytes = match fs::read(path) {
                    Ok(bytes) => bytes,
                    Err(_) => return true,
                };
                if bytes.contains(&0) {
                    return true;
                }
                let text = String::from_utf8_lossy(&bytes);
                for (index, line) in text.lines().enumerate() {
                    let haystack = if options.ignore_case {
                        line.to_lowercase()
                    } else {
                        line.to_string()
                    };
                    if haystack.contains(&needle) {
                        let relative = path
                            .strip_prefix(&self.root)
                            .unwrap_or(path)
                            .to_string_lossy()
                            .replace('\\', "/");
                        hits.push(SearchHit {
                            path: relative,
                            line: index + 1,
                            text: crate::archive::truncate_chars(
                                line.trim(),
                                options.max_line_chars,
                            ),
                        });
                        if hits.len() >= options.max_results {
                            return false;
                        }
                    }
                }
                true
            },
        )?;

        Ok(SearchResult {
            query: query.to_string(),
            hits,
        })
    }

    /// 文件元信息（大小、修改时间、是否目录）。
    pub fn stat(&self, relative: &str) -> Result<FileStat, WorkspaceError> {
        let path = self.resolve(relative)?;
        let meta = fs::metadata(&path)?;
        self.ensure_inside(&path)?;
        let modified_ms = modified_ms(&meta);
        Ok(FileStat {
            path: relative.to_string(),
            is_dir: meta.is_dir(),
            is_file: meta.is_file(),
            size_bytes: meta.len(),
            modified_ms,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadOptions {
    /// 1-based 起始行。
    pub start_line: usize,
    pub max_lines: usize,
    pub max_line_bytes: usize,
    pub max_bytes: usize,
    /// 最高优先级：忽略行数 / 字节 / 单行 / 二进制 / 大小上限。
    pub force_full: bool,
    pub numbered: bool,
}

impl Default for ReadOptions {
    fn default() -> Self {
        Self {
            start_line: 1,
            max_lines: DEFAULT_MAX_LINES,
            max_line_bytes: DEFAULT_MAX_LINE_BYTES,
            max_bytes: DEFAULT_MAX_BYTES,
            force_full: false,
            numbered: false,
        }
    }
}

impl ReadOptions {
    pub fn full() -> Self {
        Self {
            force_full: true,
            ..Self::default()
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadResult {
    pub path: String,
    pub content: String,
    pub total_lines: usize,
    pub total_bytes: u64,
    pub returned_lines: usize,
    pub truncated: bool,
    pub truncation: Option<Truncation>,
    /// 被截断的超长行（行号、原始字节数、保留字节数）。
    pub long_lines: Vec<LongLine>,
    /// 是否用有损 UTF-8 解码（二进制 / 非法编码）。
    pub lossy: bool,
    pub hint: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Truncation {
    pub reason: TruncationReason,
    pub remaining_lines: usize,
    pub remaining_bytes: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TruncationReason {
    LineLimit,
    ByteLimit,
    LongLine,
    FileTooLarge,
    /// 单次返回的硬上限（[`MAX_BURST_BYTES`]）。`force_full` 也绕不过。
    BurstLimit,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LongLine {
    pub line: usize,
    pub bytes: usize,
    pub kept_bytes: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WriteResult {
    pub path: String,
    pub bytes: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditFileResult {
    pub path: String,
    pub replacements: usize,
    pub bytes_before: usize,
    pub bytes_after: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RenameResult {
    pub from: String,
    pub to: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoveResult {
    pub path: String,
    pub is_dir: bool,
    /// 删掉的条目数（文件算 1，目录算它底下的全部条目，含子目录本身）。
    pub removed: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListOptions {
    pub max_entries: usize,
    pub depth: usize,
    pub include_hidden: bool,
}

impl Default for ListOptions {
    fn default() -> Self {
        Self {
            max_entries: 500,
            depth: 1,
            include_hidden: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub size_bytes: u64,
    /// 修改时间（毫秒，0 = 拿不到）。界面的「按修改时间排」用它。
    pub modified_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListResult {
    pub path: String,
    pub entries: Vec<DirEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FindOptions {
    pub path: String,
    pub max_results: usize,
    pub max_depth: usize,
    pub include_hidden: bool,
}

impl Default for FindOptions {
    fn default() -> Self {
        Self {
            path: String::new(),
            max_results: 200,
            max_depth: 8,
            include_hidden: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FindResult {
    pub pattern: String,
    pub matches: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchOptions {
    pub path: String,
    pub max_results: usize,
    pub max_depth: usize,
    pub max_file_bytes: u64,
    pub max_line_chars: usize,
    pub ignore_case: bool,
    pub include_hidden: bool,
}

impl Default for SearchOptions {
    fn default() -> Self {
        Self {
            path: String::new(),
            max_results: 100,
            max_depth: 8,
            max_file_bytes: 2 * 1024 * 1024,
            max_line_chars: 200,
            ignore_case: false,
            include_hidden: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchHit {
    pub path: String,
    pub line: usize,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResult {
    pub query: String,
    pub hits: Vec<SearchHit>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileStat {
    pub path: String,
    pub is_dir: bool,
    pub is_file: bool,
    pub size_bytes: u64,
    pub modified_ms: i64,
}

#[derive(Debug, thiserror::Error)]
pub enum WorkspaceError {
    #[error("路径为空")]
    EmptyPath,
    #[error("路径越界（只能访问工作区内）: {path}")]
    OutsideRoot { path: String },
    #[error("文件不存在: {path}")]
    NotFound { path: String },
    #[error("已经存在: {path}")]
    AlreadyExists { path: String },
    #[error("不是文件: {path}")]
    NotAFile { path: String },
    #[error("不是目录: {path}")]
    NotADirectory { path: String },
    #[error("二进制文件，拒绝按文本读取: {path}")]
    BinaryFile { path: String },
    #[error("文件过大（{bytes} 字节 > {cap} 字节），需要显式 force_full 才能读: {path}")]
    FileTooLarge { path: String, bytes: u64, cap: u64 },
    #[error("没有找到匹配内容: {path}")]
    NoMatch { path: String },
    #[error("匹配到 {matches} 处，需要 replace_all 或更精确的锚点: {path}")]
    AmbiguousMatch { path: String, matches: usize },
    #[error("搜索 / 替换模式为空")]
    EmptyPattern,
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
}

/// 修改时间（毫秒，拿不到给 0）。`stat` 与目录列表共用这一份：两处各算一次迟早对不上。
fn modified_ms(meta: &fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 数一棵树里有几个条目（含子目录本身），**不跟随符号链接**。
fn count_tree(root: &Path) -> usize {
    let mut total = 0usize;
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let entries = match fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            total += 1;
            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                stack.push(entry.path());
            }
        }
    }
    total
}

fn truncate_utf8(text: &str, max_bytes: usize) -> &str {
    if text.len() <= max_bytes {
        return text;
    }
    let mut end = max_bytes;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

fn build_hint(
    options: &ReadOptions,
    total_lines: usize,
    start_line: usize,
    returned_lines: usize,
    long_lines: &[LongLine],
    truncation: Option<&Truncation>,
) -> Option<String> {
    let mut parts = Vec::new();
    // 一次最多 48 KB，`force_full` 也一样（D343）。说清"这一页是哪几行、下一页从哪开始"——
    // 模型只有拿到这句才接得上；以前 force_full 时这里写的是"已全量读取"，它以为读完了。
    if let Some(truncation) = truncation {
        if truncation.reason == TruncationReason::BurstLimit {
            let last = start_line + returned_lines.saturating_sub(1);
            parts.push(format!(
                "一次最多返回 {} KB：这一页是第 {start_line}–{last} 行，共 {total_lines} 行；还剩 {} 行。",
                MAX_BURST_BYTES / 1024,
                truncation.remaining_lines
            ));
            parts.push(format!(
                "接着读：start_line={}（force_full 也可以给，它不会从第 1 行重来）；\n\
                 只想看某一段就直接给 start_line / max_lines，别整篇吞。",
                last + 1
            ));
            return Some(parts.join(" "));
        }
    }
    if options.force_full {
        return Some(format!(
            "已全量读取（force_full=true）：{total_lines} 行，忽略行数与单行上限"
        ));
    }
    if let Some(truncation) = truncation {
        let last = start_line + returned_lines.saturating_sub(1);
        parts.push(format!(
            "已返回第 {start_line}–{last} 行，共 {total_lines} 行；还剩 {} 行。",
            truncation.remaining_lines
        ));
        parts.push(format!(
            "继续读：start_line={}；force_full=true 只是不按行数/单行上限截断，仍然一次一页。",
            last + 1
        ));
    }
    if !long_lines.is_empty() {
        let lines: Vec<String> = long_lines
            .iter()
            .take(5)
            .map(|line| format!("#{}（{} 字节）", line.line, line.bytes))
            .collect();
        parts.push(format!("超长单行已截断：{}。", lines.join("、")));
    }
    if parts.is_empty() {
        None
    } else {
        Some(parts.join(" "))
    }
}

fn collect_entries(
    dir: &Path,
    root: &Path,
    depth: usize,
    options: &ListOptions,
    out: &mut Vec<DirEntry>,
) -> Result<(), WorkspaceError> {
    let mut entries: Vec<_> = fs::read_dir(dir)?.collect::<Result<_, _>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        if out.len() >= options.max_entries * 4 {
            break;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        if !options.include_hidden && name.starts_with('.') {
            continue;
        }
        let path = entry.path();
        let meta = match entry.metadata() {
            Ok(meta) => meta,
            Err(_) => continue,
        };
        let is_symlink = entry.file_type().map(|t| t.is_symlink()).unwrap_or(false);
        let relative = path
            .strip_prefix(root)
            .unwrap_or(&path)
            .to_string_lossy()
            .replace('\\', "/");
        out.push(DirEntry {
            name: name.clone(),
            path: relative,
            is_dir: meta.is_dir(),
            is_symlink,
            size_bytes: meta.len(),
            modified_ms: modified_ms(&meta),
        });
        // 目录排在文件后面统一排序；深度递归不跟随符号链接，避免环。
        if meta.is_dir() && depth < options.depth && !is_symlink {
            collect_entries(&path, root, depth + 1, options, out)?;
        }
    }
    out.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then(a.path.cmp(&b.path)));
    Ok(())
}

fn walk<F>(
    dir: &Path,
    root: &Path,
    depth: usize,
    max_depth: usize,
    include_hidden: bool,
    visit: &mut F,
) -> Result<(), WorkspaceError>
where
    F: FnMut(&Path) -> bool,
{
    if depth > max_depth {
        return Ok(());
    }
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Ok(()),
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !include_hidden && name.starts_with('.') {
            continue;
        }
        let path = entry.path();
        let file_type = match entry.file_type() {
            Ok(file_type) => file_type,
            Err(_) => continue,
        };
        if file_type.is_symlink() {
            continue;
        }
        if file_type.is_dir() {
            if DEFAULT_SKIP_DIRS.contains(&name.as_str()) {
                continue;
            }
            if path != root {
                walk(&path, root, depth + 1, max_depth, include_hidden, visit)?;
            }
        } else if file_type.is_file() && !visit(&path) {
            return Ok(());
        }
    }
    Ok(())
}

/// 通配符匹配：`*` 不跨 `/`，`**` 跨目录，`?` 单个非 `/` 字符。
pub fn glob_match(pattern: &str, text: &str) -> bool {
    let pattern: Vec<char> = pattern.chars().collect();
    let text: Vec<char> = text.chars().collect();
    let mut memo = HashMap::new();
    match_inner(&pattern, &text, 0, 0, &mut memo)
}

fn match_inner(
    pattern: &[char],
    text: &[char],
    i: usize,
    j: usize,
    memo: &mut HashMap<(usize, usize), bool>,
) -> bool {
    if let Some(cached) = memo.get(&(i, j)) {
        return *cached;
    }
    let result = if i == pattern.len() {
        j == text.len()
    } else {
        match pattern[i] {
            '*' if i + 1 < pattern.len() && pattern[i + 1] == '*' => {
                // 两种直接成立的写法合成一个条件，避免两个分支内容相同：
                //   · 模式以 `**` 结尾 → 吃掉剩余全部（含 '/'）。少了这一支，"**" 永远匹配不了
                //   · `**/` 允许匹配零层目录
                let terminal = i + 2 >= pattern.len();
                let zero_dir = !terminal
                    && pattern[i + 2] == '/'
                    && match_inner(pattern, text, i + 3, j, memo);
                terminal
                    || zero_dir
                    || (j < text.len() && match_inner(pattern, text, i, j + 1, memo))
            }
            '*' => {
                match_inner(pattern, text, i + 1, j, memo)
                    || (j < text.len()
                        && text[j] != '/'
                        && match_inner(pattern, text, i, j + 1, memo))
            }
            '?' => {
                j < text.len() && text[j] != '/' && match_inner(pattern, text, i + 1, j + 1, memo)
            }
            ch => j < text.len() && text[j] == ch && match_inner(pattern, text, i + 1, j + 1, memo),
        }
    };
    memo.insert((i, j), result);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> (tempfile::TempDir, Workspace) {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        workspace
            .write_file("src/main.rs", "fn main() {\n    println!(\"hi\");\n}\n")
            .unwrap();
        workspace
            .write_file(
                "src/lib.rs",
                "pub fn add(a: i32, b: i32) -> i32 {\n    a + b\n}\n",
            )
            .unwrap();
        workspace
            .write_file("README.md", "# demo\nhello world\n")
            .unwrap();
        (dir, workspace)
    }

    #[test]
    fn rejects_path_escape() {
        let (_dir, workspace) = fixture();
        assert!(workspace
            .read_file("../etc/passwd", &ReadOptions::default())
            .is_err());
        assert!(workspace
            .read_file("/etc/passwd", &ReadOptions::default())
            .is_err());
        assert!(matches!(
            workspace.resolve("a/../../b"),
            Err(WorkspaceError::OutsideRoot { .. })
        ));
    }

    #[test]
    fn reads_with_line_limit_and_hint() {
        let (_dir, workspace) = fixture();
        let content = (1..=50)
            .map(|i| format!("line {i}\n"))
            .collect::<Vec<_>>()
            .join("");
        workspace.write_file("big.txt", &content).unwrap();

        let options = ReadOptions {
            max_lines: 10,
            ..ReadOptions::default()
        };
        let result = workspace.read_file("big.txt", &options).unwrap();
        assert_eq!(result.returned_lines, 10);
        assert!(result.truncated);
        assert_eq!(result.total_lines, 50);
        assert!(result.hint.unwrap().contains("start_line=11"));
    }

    #[test]
    fn reads_from_offset() {
        let (_dir, workspace) = fixture();
        let content = (1..=10)
            .map(|i| format!("line {i}\n"))
            .collect::<Vec<_>>()
            .join("");
        workspace.write_file("lines.txt", &content).unwrap();
        let options = ReadOptions {
            start_line: 5,
            max_lines: 2,
            ..ReadOptions::default()
        };
        let result = workspace.read_file("lines.txt", &options).unwrap();
        assert_eq!(result.content, "line 5\nline 6\n");
    }

    #[test]
    fn truncates_overlong_single_line_and_reports_it() {
        let (_dir, workspace) = fixture();
        let long = format!("head-{}-tail", "x".repeat(20_000));
        workspace.write_file("min.js", &long).unwrap();

        let options = ReadOptions {
            max_line_bytes: 100,
            ..ReadOptions::default()
        };
        let result = workspace.read_file("min.js", &options).unwrap();
        assert_eq!(result.long_lines.len(), 1);
        assert_eq!(result.long_lines[0].bytes, long.len());
        assert!(result.content.contains("单行过长"));
        assert!(result.content.len() < 500);
    }

    /// `force_full` 也绕不过单次上限（D343）：一页 48 KB，下一页用 start_line 接。
    #[test]
    fn force_full_still_stops_at_one_page() {
        let (_dir, workspace) = fixture();
        // 每行 100 字节 × 2000 行 = 200 KB，远超一页。
        let content = (1..=2000)
            .map(|i| format!("{i:05} {}\n", "x".repeat(94)))
            .collect::<Vec<_>>()
            .join("");
        workspace.write_file("huge.txt", &content).unwrap();

        let first = workspace
            .read_file("huge.txt", &ReadOptions::full())
            .unwrap();
        assert!(first.truncated, "200 KB 不该一次全读出来");
        assert_eq!(
            first.truncation.as_ref().map(|t| t.reason),
            Some(TruncationReason::BurstLimit)
        );
        assert!(
            first.content.len() <= MAX_BURST_BYTES + 128,
            "一页应当卡在 48 KB 附近，实际 {}",
            first.content.len()
        );
        let hint = first.hint.expect("要说清怎么接着读");
        assert!(hint.contains("48 KB"), "{hint}");
        assert!(hint.contains("start_line="), "{hint}");

        // 第二页从上一页最后一行 + 1 开始，且 force_full 不会把它重置成第 1 行。
        let next = first.returned_lines + 1;
        let second = workspace
            .read_file(
                "huge.txt",
                &ReadOptions {
                    start_line: next,
                    force_full: true,
                    numbered: true,
                    ..ReadOptions::default()
                },
            )
            .unwrap();
        assert!(
            second
                .content
                .starts_with(&format!("{:>6}| {next:05}", next)),
            "第二页应当从第 {next} 行开始：{}",
            &second.content[..40.min(second.content.len())]
        );
    }

    #[test]
    fn force_full_ignores_every_limit() {
        let (_dir, workspace) = fixture();
        let content = (1..=500)
            .map(|i| format!("line {i}\n"))
            .collect::<Vec<_>>()
            .join("");
        workspace.write_file("big.txt", &content).unwrap();

        let options = ReadOptions {
            max_lines: 5,
            max_bytes: 10,
            max_line_bytes: 5,
            force_full: true,
            ..ReadOptions::default()
        };
        let result = workspace.read_file("big.txt", &options).unwrap();
        assert_eq!(result.returned_lines, 500);
        assert!(!result.truncated);
        assert!(result.long_lines.is_empty());
    }

    #[test]
    fn binary_is_rejected_unless_forced() {
        let (_dir, workspace) = fixture();
        // 0x00 触发二进制判定，0xFF 让 UTF-8 解码失败（两者都要覆盖到）。
        let mut bytes = vec![0u8, 0xFF, 0xFE, 0x01];
        bytes.extend_from_slice(b"text");
        fs::write(workspace.root().join("bin.dat"), &bytes).unwrap();

        assert!(matches!(
            workspace.read_file("bin.dat", &ReadOptions::default()),
            Err(WorkspaceError::BinaryFile { .. })
        ));
        let result = workspace
            .read_file("bin.dat", &ReadOptions::full())
            .unwrap();
        assert!(result.lossy);
    }

    #[test]
    fn write_and_edit_roundtrip() {
        let (_dir, workspace) = fixture();
        workspace
            .write_file("notes/a.txt", "alpha beta gamma")
            .unwrap();
        let result = workspace
            .edit_file("notes/a.txt", "beta", "BETA", false)
            .unwrap();
        assert_eq!(result.replacements, 1);
        let read = workspace
            .read_file("notes/a.txt", &ReadOptions::default())
            .unwrap();
        assert_eq!(read.content, "alpha BETA gamma");
    }

    #[test]
    fn ambiguous_edit_requires_replace_all() {
        let (_dir, workspace) = fixture();
        workspace.write_file("dup.txt", "x x x").unwrap();
        assert!(matches!(
            workspace.edit_file("dup.txt", "x", "y", false),
            Err(WorkspaceError::AmbiguousMatch { matches: 3, .. })
        ));
        let result = workspace.edit_file("dup.txt", "x", "y", true).unwrap();
        assert_eq!(result.replacements, 3);
    }

    #[test]
    fn make_dir_creates_parents_and_refuses_existing() {
        let (_dir, workspace) = fixture();
        let made = workspace.make_dir("a/b/c").unwrap();
        assert_eq!(made.path, "a/b/c");
        assert!(workspace.root().join("a/b/c").is_dir());

        // 第二次要拒绝：界面上"新建文件夹"点了没反应比报一句"已经有了"更让人困惑。
        assert!(matches!(
            workspace.make_dir("a/b/c"),
            Err(WorkspaceError::AlreadyExists { .. })
        ));
        // 越界同样拒绝。
        assert!(workspace.make_dir("../outside").is_err());
    }

    #[test]
    fn rename_moves_and_refuses_to_overwrite() {
        let (_dir, workspace) = fixture();
        let moved = workspace
            .rename_entry("README.md", "docs/README.md")
            .unwrap();
        assert_eq!(moved.to, "docs/README.md");
        assert!(workspace.root().join("docs/README.md").is_file());
        assert!(!workspace.root().join("README.md").exists());

        // 撞上已有的文件不覆盖：覆盖掉的那一份没人能找回来。
        workspace.write_file("a.txt", "a").unwrap();
        workspace.write_file("b.txt", "b").unwrap();
        assert!(matches!(
            workspace.rename_entry("a.txt", "b.txt"),
            Err(WorkspaceError::AlreadyExists { .. })
        ));
        let kept = workspace
            .read_file("b.txt", &ReadOptions::default())
            .unwrap();
        assert_eq!(kept.content, "b");

        assert!(workspace.rename_entry("nope.txt", "x.txt").is_err());
        assert!(workspace.rename_entry("a.txt", "../x.txt").is_err());
    }

    #[test]
    fn remove_file_and_tree_counts_entries() {
        let (_dir, workspace) = fixture();
        let one = workspace.remove_entry("README.md").unwrap();
        assert!(!one.is_dir);
        assert_eq!(one.removed, 1);
        assert!(!workspace.root().join("README.md").exists());

        // `src/` 里两个文件：数出来是 2，目录本身不计。
        let tree = workspace.remove_entry("src").unwrap();
        assert!(tree.is_dir);
        assert_eq!(tree.removed, 2);
        assert!(!workspace.root().join("src").exists());

        // 空路径不许删（那等于删整棵树）。
        assert!(matches!(
            workspace.remove_entry(""),
            Err(WorkspaceError::EmptyPath)
        ));
        assert!(workspace.remove_entry("nope").is_err());
    }

    #[test]
    fn remove_does_not_follow_symlinks() {
        let (dir, workspace) = fixture();
        // 根外面放一个"重要的东西"，工作区里给一个指向它的链接。
        let outside = dir.path().parent().unwrap().join("nmoi-outside.txt");
        std::fs::write(&outside, "keep me").unwrap();
        let link = workspace.root().join("link.txt");
        #[cfg(unix)]
        std::os::unix::fs::symlink(&outside, &link).unwrap();
        #[cfg(not(unix))]
        return;

        workspace.remove_entry("link.txt").unwrap();
        assert!(!link.exists());
        assert_eq!(std::fs::read_to_string(&outside).unwrap(), "keep me");
        let _ = std::fs::remove_file(&outside);
    }

    #[test]
    fn list_reports_modified_time() {
        let (_dir, workspace) = fixture();
        let result = workspace.list_dir("", &ListOptions::default()).unwrap();
        let readme = result
            .entries
            .iter()
            .find(|entry| entry.name == "README.md")
            .expect("README.md 应当在列表里");
        assert!(readme.modified_ms > 0, "改过时间要给出来：{readme:?}");
    }

    #[test]
    fn lists_directory_with_dirs_first() {
        let (_dir, workspace) = fixture();
        let result = workspace.list_dir("", &ListOptions::default()).unwrap();
        assert!(result
            .entries
            .iter()
            .any(|entry| entry.name == "src" && entry.is_dir));
        assert_eq!(result.entries[0].name, "src");
    }

    #[test]
    fn finds_files_with_glob() {
        let (_dir, workspace) = fixture();
        let result = workspace.find("**/*.rs", &FindOptions::default()).unwrap();
        assert_eq!(result.matches, vec!["src/lib.rs", "src/main.rs"]);

        let single = workspace.find("src/*.rs", &FindOptions::default()).unwrap();
        assert_eq!(single.matches.len(), 2);
    }

    #[test]
    fn searches_text() {
        let (_dir, workspace) = fixture();
        let result = workspace
            .search("hello", &SearchOptions::default())
            .unwrap();
        assert_eq!(result.hits.len(), 1);
        assert_eq!(result.hits[0].path, "README.md");
        assert_eq!(result.hits[0].line, 2);
    }

    #[test]
    fn glob_semantics() {
        assert!(glob_match("*.rs", "main.rs"));
        assert!(!glob_match("*.rs", "src/main.rs"));
        assert!(glob_match("src/**/*.rs", "src/a/b/main.rs"));
        assert!(glob_match("src/**/*.rs", "src/main.rs"));
        assert!(glob_match("a?c", "abc"));
        assert!(!glob_match("a?c", "a/c"));
        assert!(glob_match("**", "any/deep/path"));
        assert!(glob_match("**", "single"));
        assert!(glob_match("src/**", "src/a/b/c.rs"));
        assert!(!glob_match("src/**", "lib/a.rs"));
    }
}
