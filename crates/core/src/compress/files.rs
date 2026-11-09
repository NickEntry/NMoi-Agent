//! 压缩时"这一段用过哪些会话文件"（D296）。
//!
//! 压掉的是一段对话，压不掉的应该是"这一段依赖过哪些文件"：模型接着干活时要能
//! 回答"刚才那个文件在哪"，所以摘要里留一份**清单**，不搬内容。
//!
//! 只认两个前缀：`/tmp/...`（这条会话自己的暂存）与 `/chat/...`（会话目录）。
//! 工作区里的文件属于项目、不属于会话——它们的路径写进摘要只会误导。

use std::path::{Path, PathBuf};

use crate::message::{AtomicUnit, Part};

/// 一条被这段历史用过的会话文件。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionFile {
    /// 展示用的路径：`/tmp/a.txt` 或 `/chat/b.md`。
    pub shown: String,
    /// 用过它的工具（按第一次出现的顺序，去重）。
    pub tools: Vec<String>,
}

/// 摘要里最多列几条、最长多少字。超了就按出现顺序截断，并写一句"还有几条没列"。
const MAX_ENTRIES: usize = 12;
const MAX_CHARS: usize = 600;

/// 从这一段里挑出落在会话 `/tmp` 与 `/chat` 里的路径。
///
/// `tmp` / `chat` 是**宿主机上的真实目录**：只有给了一个根，才认那个前缀——
/// 没有根却把 `/tmp/x` 写进摘要，等于让模型去一个这一轮根本不存在的地方找文件。
pub fn collect(units: &[AtomicUnit], tmp: Option<&Path>, chat: Option<&Path>) -> Vec<SessionFile> {
    collect_messages(
        units.iter().flat_map(|unit| unit.messages.iter()),
        tmp,
        chat,
    )
}

/// 同上，直接吃一串消息——测试和别的调用方不必凑一个 `AtomicUnit`。
pub fn collect_messages<'a>(
    messages: impl Iterator<Item = &'a crate::message::Message>,
    tmp: Option<&Path>,
    chat: Option<&Path>,
) -> Vec<SessionFile> {
    let mut found: Vec<SessionFile> = Vec::new();
    {
        for message in messages {
            for part in message.parts() {
                let Part::Tool(tool) = part else {
                    continue;
                };
                for path in paths_in(&tool.arguments) {
                    let Some((root, rest)) = split_root(&path, tmp, chat) else {
                        continue;
                    };
                    // 只留真的还在的：摘要里写一个删掉的文件，等于让下一轮去找鬼。
                    if !root.join(rest).exists() {
                        continue;
                    }
                    match found.iter_mut().find(|item| item.shown == path) {
                        Some(item) => {
                            if !item.tools.iter().any(|name| name == &tool.name) {
                                item.tools.push(tool.name.clone());
                            }
                        }
                        None => found.push(SessionFile {
                            shown: path,
                            tools: vec![tool.name.clone()],
                        }),
                    }
                }
            }
        }
    }
    found
}

/// 把清单摊成摘要里的一节。没有文件就返回 `None`——不写一句"没有"。
pub fn render(files: &[SessionFile]) -> Option<String> {
    if files.is_empty() {
        return None;
    }
    let mut lines = vec!["[这一段用到的会话文件]".to_string()];
    let mut chars = lines[0].chars().count();
    let mut listed = 0usize;
    for file in files {
        let line = format!("{}（{}）", file.shown, file.tools.join("、"));
        let cost = line.chars().count() + 1;
        if listed >= MAX_ENTRIES || chars + cost > MAX_CHARS {
            break;
        }
        chars += cost;
        listed += 1;
        lines.push(line);
    }
    if listed < files.len() {
        lines.push(format!(
            "（其余 {} 条没列出，文件还在盘上）",
            files.len() - listed
        ));
    }
    Some(lines.join("\n"))
}

/// 这个路径归哪个根、根里面是哪一段。`None` = 不归这两个根（工作区路径、`..`）。
fn split_root<'a>(
    path: &'a str,
    tmp: Option<&'a Path>,
    chat: Option<&'a Path>,
) -> Option<(&'a Path, &'a str)> {
    let (root, rest) = if let Some(rest) = path.strip_prefix("/tmp/") {
        (tmp?, rest)
    } else {
        let rest = path.strip_prefix("/chat/")?;
        (chat?, rest)
    };
    if rest.is_empty() || rest.split('/').any(|part| part == "..") {
        return None;
    }
    Some((root, rest))
}

/// 一段工具参数里出现的 `/tmp/...` 与 `/chat/...`。
///
/// 只做**字面扫描**，不解析命令行语义：参数里是 JSON，值是字符串，
/// 路径就在字符串里。宁可多认一个（后面还要过存在性），不要漏。
fn paths_in(arguments: &str) -> Vec<String> {
    let mut out = Vec::new();
    let bytes: Vec<char> = arguments.chars().collect();
    let mut index = 0usize;
    while index < bytes.len() {
        if bytes[index] != '/' {
            index += 1;
            continue;
        }
        let start = index;
        while index < bytes.len() && !is_boundary(bytes[index]) {
            index += 1;
        }
        let token: String = bytes[start..index].iter().collect();
        if token.starts_with("/tmp/") || token.starts_with("/chat/") {
            out.push(token);
        }
    }
    out
}

fn is_boundary(ch: char) -> bool {
    ch.is_whitespace()
        || matches!(
            ch,
            '"' | '\''
                | '`'
                | ','
                | ';'
                | ')'
                | '('
                | ']'
                | '['
                | '}'
                | '{'
                | ':'
                | '|'
                | '&'
                | '<'
                | '>'
        )
}

/// 摘要末尾接上这一节。已经超预算就不接（草稿正文永远优先）。
pub fn append_section(summary: &str, files: &[SessionFile], limit_tokens: u32) -> String {
    let Some(section) = render(files) else {
        return summary.to_string();
    };
    let joined = format!("{summary}\n\n{section}");
    if crate::tokens::estimate_tokens(&joined) > limit_tokens {
        return summary.to_string();
    }
    joined
}

/// 两个根的持有者：调用方（`session.rs`）从历史实现里取。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FileRoots {
    pub tmp: Option<PathBuf>,
    pub chat: Option<PathBuf>,
}

impl FileRoots {
    pub fn new(tmp: Option<PathBuf>, chat: Option<PathBuf>) -> Self {
        Self { tmp, chat }
    }

    pub fn collect(&self, units: &[AtomicUnit]) -> Vec<SessionFile> {
        collect(units, self.tmp.as_deref(), self.chat.as_deref())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::MessageId;
    use crate::message::{Message, OutgoingToolCall};

    fn tool_message(session: crate::ids::SessionId, arguments: &str) -> Message {
        Message::tool_call_message(
            MessageId::new(session, 1),
            vec![OutgoingToolCall::new("c1", "write_file", arguments)],
            1,
            0,
            LogicalClock::ZERO,
        )
    }

    #[test]
    fn picks_only_existing_files_under_the_two_roots() {
        let session = crate::ids::SessionId::new();
        let store = tempfile::tempdir().unwrap();
        let tmp = store.path().join("tmp");
        let chat = store.path().join("chat");
        std::fs::create_dir_all(&tmp).unwrap();
        std::fs::create_dir_all(&chat).unwrap();
        std::fs::write(tmp.join("a.txt"), "x").unwrap();

        let unit = tool_message(session, r#"{"path":"/tmp/a.txt","content":"x"}"#);
        let missing = tool_message(session, r#"{"path":"/tmp/gone.txt","content":"x"}"#);
        let workspace = tool_message(session, r#"{"path":"src/main.rs","content":"x"}"#);

        let files = collect_messages(
            [&unit, &missing, &workspace].into_iter(),
            Some(&tmp),
            Some(&chat),
        );
        assert_eq!(files.len(), 1, "{files:?}");
        assert_eq!(files[0].shown, "/tmp/a.txt");
        assert_eq!(files[0].tools, vec!["write_file".to_string()]);
    }

    #[test]
    fn without_the_root_the_prefix_is_not_claimed() {
        let session = crate::ids::SessionId::new();
        let store = tempfile::tempdir().unwrap();
        let tmp = store.path().join("tmp");
        std::fs::create_dir_all(&tmp).unwrap();
        std::fs::write(tmp.join("a.txt"), "x").unwrap();
        let unit = tool_message(session, r#"{"path":"/tmp/a.txt"}"#);
        // 没有 tmp 根：不认这个前缀（回落到宿主的 /tmp 就是另一种假话）。
        assert!(collect_messages([&unit].into_iter(), None, None).is_empty());
    }

    #[test]
    fn escaping_paths_are_dropped() {
        let session = crate::ids::SessionId::new();
        let store = tempfile::tempdir().unwrap();
        let tmp = store.path().join("tmp");
        std::fs::create_dir_all(tmp.join("sub")).unwrap();
        std::fs::write(store.path().join("outside.txt"), "x").unwrap();
        let unit = tool_message(session, r#"{"path":"/tmp/../outside.txt"}"#);
        assert!(collect_messages([&unit].into_iter(), Some(&tmp), None).is_empty());
    }

    #[test]
    fn render_lists_tools_and_caps_the_list() {
        let files: Vec<SessionFile> = (0..20)
            .map(|index| SessionFile {
                shown: format!("/tmp/f{index}.txt"),
                tools: vec!["read_file".to_string(), "edit_file".to_string()],
            })
            .collect();
        let text = render(&files).unwrap();
        assert!(text.starts_with("[这一段用到的会话文件]"), "{text}");
        assert!(
            text.contains("/tmp/f0.txt（read_file、edit_file）"),
            "{text}"
        );
        assert!(text.contains("没列出"), "{text}");
        assert!(render(&[]).is_none());
    }

    #[test]
    fn append_section_never_cuts_the_draft() {
        let files = vec![SessionFile {
            shown: "/tmp/a.txt".to_string(),
            tools: vec!["write_file".to_string()],
        }];
        let kept = append_section("摘要正文", &files, 1000);
        assert!(kept.contains("摘要正文"));
        assert!(kept.contains("/tmp/a.txt"));
        // 预算不够就整节不接，正文一个字都不动。
        assert_eq!(append_section("摘要正文", &files, 1), "摘要正文");
    }
}
