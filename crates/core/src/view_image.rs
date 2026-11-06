//! `View-Image`（0.15al / D248）：把一张图**拉进这一轮的上下文**。
//!
//! 它不"看图"，也不描述图——看是模型自己的事。它只做一件事：把盘上那张图登记进
//! [`crate::images`] 的清单，于是下一次请求会带上它。所以结果是一句登记回执，
//! 不是图片内容。
//!
//! 为什么不直接把图片塞进工具结果：OpenAI 的 `role: tool` 只收文本，Anthropic 的
//! `tool_result` 能带图、Gemini 的 `functionResponse` 又只认文本——三家不一致。
//! 走"清单 + 请求级附件"这条路，三家都是同一种形状（消息上的图），不用为每家写一套。
//!
//! 边界：
//!
//! - 只认会话自己的附件目录（`sessions/<会话>/images`），不读工作区、不读任意路径。
//! - 单张上限 5 MB：Anthropic 的 API 上限，最弱的那家。
//! - 清单满了先丢最旧的，并在结果里点名丢的是哪张——不静默。

use std::path::Path;

use serde_json::{json, Value};

use crate::images::{self, ContextImage, DEFAULT_LIMIT};
use crate::policy::Capability;
use crate::tools::{required_str, Lane, Tool, ToolContext, ToolOutcome};

/// 单张图上限：Anthropic 的 API 是 5 MB（Gemini 单张更宽，OpenAI 20 MB）。
/// 取最弱的那个——换端点不该因为一张图就 400。
const MAX_IMAGE_BYTES: u64 = 5 * 1024 * 1024;

pub struct ViewImageTool;

impl Tool for ViewImageTool {
    fn lane(&self) -> Lane {
        Lane::Read
    }

    fn name(&self) -> &str {
        "View-Image"
    }

    fn description(&self) -> &str {
        concat!(
            "把一张图片加入**这一轮的上下文**，下一次请求就会带上它。\n",
            "path 是这个会话附件目录里的文件名（`/chat/images/xxx.jpg` 或只写 `xxx.jpg`）。\n",
            "它不返回图片内容——图是给模型看的，加进来之后你就能在下一轮里看到它。\n",
            "清单有上限（默认 20 张，新的在前）；满了还能临时多留几张，那几张在下一次思考结束前有效。\n",
            "结果里会写清这次是正常放进去、临时留着，还是真挤掉了谁。",
            "单张最大 5 MB；只认 jpg / jpeg / png / webp / gif。"
        )
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "附件目录里的文件名，例如 screenshot.jpg 或 /chat/images/screenshot.jpg"
                }
            },
            "required": ["path"]
        })
    }

    fn capability(&self) -> Capability {
        // 读的是会话自己的附件目录，不是工作区；门控按"能不能读文件"这一档算。
        Capability::WorkspaceRead
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        // 门控最前面：没开读权限就别去碰盘。
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let Some(dir) = ctx.chat_images else {
            return ToolOutcome::failed(
                "这个会话没有附件目录：图得先发进来（聊天页「＋ → 图片」），或者让工具把它写到 /chat/images 下",
            );
        };
        let Some(session) = ctx.call_session else {
            return ToolOutcome::failed(
                "没有会话上下文：没法登记这张图（这是内部错误，不是你的参数问题）",
            );
        };
        let raw = match required_str(args, "path") {
            Ok(raw) => raw,
            Err(outcome) => return outcome,
        };
        let name = match file_name_of(&raw) {
            Ok(name) => name,
            Err(message) => return ToolOutcome::failed(message),
        };

        let path = dir.join(&name);
        let meta = match std::fs::metadata(&path) {
            Ok(meta) => meta,
            Err(error) => {
                return ToolOutcome::failed(format!(
                    "读不到 `{}`：{error}。先 list_dir 看一眼 /chat/images 里有什么",
                    path.display()
                ))
            }
        };
        if !meta.is_file() {
            return ToolOutcome::failed(format!("`{name}` 不是一个文件"));
        }
        if meta.len() > MAX_IMAGE_BYTES {
            return ToolOutcome::failed(format!(
                "这张图 {} MB，超过单张 5 MB 的上限（换端点最严的那家就是 5 MB）",
                meta.len() / 1024 / 1024
            ));
        }
        let Some(mime) = mime_of(&name) else {
            return ToolOutcome::failed(format!(
                "`{name}` 不是认得的图片格式：只收 jpg / jpeg / png / webp / gif"
            ));
        };
        let id = name.rsplit_once('.').map(|(stem, _)| stem).unwrap_or(&name);

        let limit = images::limit_of(ctx.image_limit);
        let room = images::room_of(ctx.image_temp_limit);
        let (at, dropped) = images::add(
            session,
            ContextImage {
                id: id.to_string(),
                file: name.clone(),
                mime: mime.to_string(),
                bytes: meta.len(),
            },
            limit,
            room,
        );
        let dropped_ids: Vec<&str> = dropped.iter().map(|image| image.id.as_str()).collect();
        let count = images::list(session).len();
        let temporary = images::temporary_count(session, limit);
        let mut value = json!({
            "file": name,
            "attachment": id,
            "in_context": at,
            "limit": limit,
            "count": count,
            "temporary": temporary,
        });
        if !dropped_ids.is_empty() {
            value["dropped"] = json!(dropped_ids);
        }
        // 三种话分开说（D272）：正常放进去了 / 临时多留了一张 / 真挤掉了谁。
        // 合成一句"已加入"会让模型以为那张图会一直在。
        value["note"] = json!(if !dropped_ids.is_empty() {
            format!(
                "已加入上下文（第 {at} 张，清单 {count} 张）；连临时额度也满了，移出了最旧的：{}",
                dropped_ids.join("、")
            )
        } else if temporary > 0 {
            format!(
                "已加入上下文（第 {at} 张，清单 {count} 张，其中 {temporary} 张是**临时**的：\n\
                 正常上限是 {limit} 张，临时额度 {room} 张——临时这几张在**下一次思考结束**时就掉出去。\n\
                 要留结论就现在写进正文或思考，别只留在工具结果里。"
            )
        } else {
            format!("已加入上下文（第 {at}/{limit} 张）。下一轮请求就会带上它。")
        });
        ToolOutcome::Ok {
            text: value.to_string(),
            truncated: false,
        }
    }
}

/// `/chat/images/a.jpg` 与 `a.jpg` 都收；带目录的、带 `..` 的一律拒。
fn file_name_of(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("path 是空的".into());
    }
    let rest = trimmed
        .strip_prefix("/chat/images/")
        .or_else(|| trimmed.strip_prefix("images/"))
        .unwrap_or(trimmed);
    if rest.is_empty() || rest.contains('/') || rest.contains('\\') || rest.contains("..") {
        return Err(format!(
            "path 只能是附件目录里的**文件名**（`xxx.jpg` 或 `/chat/images/xxx.jpg`），拿到的是 `{raw}`"
        ));
    }
    Ok(rest.to_string())
}

fn mime_of(name: &str) -> Option<&'static str> {
    let ext = Path::new(name).extension()?.to_str()?.to_ascii_lowercase();
    Some(match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        _ => return None,
    })
}

/// 给界面用的一句话：这一轮会带几张图。
pub fn summary(session: &str) -> String {
    let count = images::list(session).len();
    if count == 0 {
        "这一轮没有图片".to_string()
    } else {
        format!("这一轮带 {count} 张图")
    }
}

/// 默认上限（给"没配过"的调用方）。
pub const fn default_limit() -> usize {
    DEFAULT_LIMIT
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_bare_file_name_is_accepted() {
        assert_eq!(file_name_of("a.jpg").unwrap(), "a.jpg");
        assert_eq!(file_name_of("/chat/images/a.jpg").unwrap(), "a.jpg");
        assert_eq!(file_name_of("images/a.jpg").unwrap(), "a.jpg");
        assert!(file_name_of("../etc/passwd").is_err());
        assert!(file_name_of("sub/a.jpg").is_err());
        assert!(file_name_of("/etc/passwd").is_err());
        assert!(file_name_of("  ").is_err());
    }

    #[test]
    fn mime_comes_from_the_extension_and_unknown_ones_are_refused() {
        assert_eq!(mime_of("a.JPG"), Some("image/jpeg"));
        assert_eq!(mime_of("a.jpeg"), Some("image/jpeg"));
        assert_eq!(mime_of("a.png"), Some("image/png"));
        assert_eq!(mime_of("a.webp"), Some("image/webp"));
        assert_eq!(mime_of("a.gif"), Some("image/gif"));
        assert_eq!(mime_of("a.heic"), None);
        assert_eq!(mime_of("a"), None);
    }
}
