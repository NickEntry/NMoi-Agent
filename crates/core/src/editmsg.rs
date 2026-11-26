use serde_json::{json, Value};

use crate::edit::MAX_BLOCK_BYTES;
use crate::message::Message;
use crate::policy::Capability;
use crate::tools::{required_str, resolve_edit_target, Tool, ToolContext, ToolOutcome};

// ---------------------------------------------------------------------------
// 模型侧：整条重写一条消息（D431）
//
// 与 `edit_unit` 的分工：
// - `edit_message`：**整条重写**。模型看得见消息全文，它要的就是"照我看到的这样写一遍"。
//   参数里没有 `part` / `block` / `index`——那三样是库里的形状，不该让它背。
// - `edit_unit`：只动某一块时用（少写点字）。
//
// 落盘那一条（用户 2026-11-26）：工具**立刻**回一句 `note`（模型自己写的）+ 报告路径，
// 详细的 before/after 在落库那一步写进 `/chat/outputs/edit-<seq>.md`。
// 这样上下文里只留一句它自己写的摘要，要核对时用 `read_outputs` 去读。
// ---------------------------------------------------------------------------

/// `edit_message` 的入参：整条重写。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessageEdit {
    pub target: String,
    /// `None` = 不动正文。
    pub text: Option<String>,
    /// `None` = 不动思考；`Some("")` = 删掉这一块。
    pub thinking: Option<String>,
    /// `None` = 不动工具清单；`Some(vec)` = **整份替换**（可以增删改名）。
    pub tools: Option<Vec<crate::edit::ToolSpec>>,
    /// 留给自己的话：它会进上下文，所以必须短。
    pub note: String,
}

/// `note` 的上限。比"一句话"宽松一点，比"一小段"紧得多。
pub const MAX_NOTE_CHARS: usize = 60;

/// 解析 `edit_message` 的参数。**不猜**：缺什么、多长，都点名。
pub fn parse_message_edit(args: &Value) -> Result<MessageEdit, String> {
    let target = args
        .get("message_id")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .ok_or_else(|| {
            "缺少字符串参数 `message_id`（具体 id，或 last_assistant / last_user / last）"
                .to_string()
        })?
        .to_string();

    let note = args
        .get("note")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .ok_or_else(|| {
            format!("缺少 `note`：留一句你改了什么（不超过 {MAX_NOTE_CHARS} 字），以后你只看到它")
        })?
        .to_string();
    let note_chars = note.chars().count();
    if note_chars > MAX_NOTE_CHARS {
        return Err(format!(
            "`note` 有 {note_chars} 字，上限 {MAX_NOTE_CHARS}：它要一直躺在上下文里"
        ));
    }

    let text = match args.get("text").and_then(Value::as_str) {
        None => None,
        Some(content) => {
            if content.trim().is_empty() {
                return Err("`text` 是空的：不想改正文就别给这一栏（给了空串等于要清空）".into());
            }
            if content.len() > MAX_BLOCK_BYTES {
                return Err(format!(
                    "`text` 太长（{} 字节，上限 {MAX_BLOCK_BYTES}）",
                    content.len()
                ));
            }
            Some(content.to_string())
        }
    };

    let thinking = args
        .get("thinking")
        .and_then(Value::as_str)
        .map(str::to_string);

    let tools = match args.get("tools") {
        None => None,
        Some(Value::Array(items)) => {
            let mut out = Vec::with_capacity(items.len());
            for (position, item) in items.iter().enumerate() {
                let which = position + 1;
                let name = item
                    .get("name")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|name| !name.is_empty())
                    .ok_or_else(|| format!("第 {which} 个工具缺少 `name`（工具块不能没有名字）"))?;
                let arguments = item
                    .get("arguments")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                if !arguments.trim().is_empty() {
                    let parsed = serde_json::from_str::<Value>(arguments)
                        .ok()
                        .is_some_and(|value| value.is_object());
                    if !parsed {
                        return Err(format!(
                            "第 {which} 个工具（{name}）的参数不是 JSON 对象：它会原样发给上游"
                        ));
                    }
                }
                let output = item
                    .get("output")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                out.push(crate::edit::ToolSpec {
                    name: name.to_string(),
                    arguments: arguments.to_string(),
                    output: output.to_string(),
                });
            }
            Some(out)
        }
        Some(_) => return Err("`tools` 必须是数组（整份替换；空数组 = 全删）".into()),
    };

    if text.is_none() && thinking.is_none() && tools.is_none() {
        return Err("三块都没给：要改就指明 `text` / `thinking` / `tools` 里的至少一块".into());
    }

    Ok(MessageEdit {
        target,
        text,
        thinking,
        tools,
        note,
    })
}

impl MessageEdit {
    /// 落库时用的那份：块级动作 + 整份工具清单。
    ///
    /// 正文走 `set`；思考走 `set`（空串 = 删，这是 core 的既有语义）；工具走整份替换。
    pub fn to_turn_edit(&self, message: crate::ids::MessageId) -> crate::edit::TurnEdit {
        let mut ops = Vec::new();
        if let Some(text) = &self.text {
            ops.push(crate::edit::BlockOp::Set {
                block: crate::edit::BlockSelector::Output,
                content: text.clone(),
            });
        }
        if let Some(thinking) = &self.thinking {
            ops.push(crate::edit::BlockOp::Set {
                block: crate::edit::BlockSelector::Thinking,
                content: thinking.clone(),
            });
        }
        crate::edit::TurnEdit {
            message,
            ops,
            tools: self.tools.clone(),
        }
    }

    /// 报告落在哪：`/chat/outputs/edit-<seq>.md`。
    ///
    /// 用 seq 不用 uuid：模型待会儿要 `read_outputs` 它，路径短一点、能念出来一点。
    /// 同一条消息被改多次就往同一个文件里**追加**——"这条消息经历过什么"本来就是一整份。
    pub fn report_path(seq: u64) -> String {
        format!("/chat/outputs/edit-{seq}.md")
    }
}

pub struct EditMessageTool;

impl Tool for EditMessageTool {
    fn name(&self) -> &str {
        "edit_message"
    }

    fn description(&self) -> &str {
        "改一条已经存在的消息：**整条重写**，不是打补丁。这一轮结束时生效。\
         先找消息：`find_messages` 按关键词或正文开头找，拿 id；也可以给 \
         last_assistant / last_user。改哪一块就给哪一块，没给的不动：`text` 是整段新正文，\
         `thinking` 是整段新思考（空串 = 删掉），`tools` 是**整份**工具清单（可以多一条、\
         少一条、改名；不给就不动工具块）。\
         `note` 必填，一句话写清你改了什么，不超过 60 字——它会留在上下文里，\
         以后你只看到它和报告路径；详细的前后对比在 /chat/outputs/，需要核对时用 \
         `read_outputs` 去读，不要凭记忆复述。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "message_id": {
                    "type": "string",
                    "description": "要改的消息：find_messages 给的 id，或 last_assistant / last_user / last"
                },
                "text": {
                    "type": "string",
                    "description": "整段新正文（可省；省了就不动正文）"
                },
                "thinking": {
                    "type": "string",
                    "description": "整段新思考（可省；空串 = 删掉这一块）"
                },
                "tools": {
                    "type": "array",
                    "description": "工具清单：整份替换，可增删改名；不给就不动",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": { "type": "string" },
                            "arguments": {
                                "type": "string",
                                "description": "参数原文，必须是 JSON 对象的字符串"
                            },
                            "output": { "type": "string", "description": "结果原文（可空）" }
                        },
                        "required": ["name", "arguments"]
                    }
                },
                "note": {
                    "type": "string",
                    "description": "一句话：你改了什么（不超过 60 字）。它会留在上下文里。"
                }
            },
            "required": ["message_id", "note"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::WorkspaceWrite
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let call = match parse_message_edit(args) {
            Ok(call) => call,
            Err(message) => return ToolOutcome::failed(message),
        };
        let Some(history) = ctx.history else {
            return ToolOutcome::failed("这一轮没拿到会话历史，改不了消息");
        };
        let message_id = match resolve_edit_target(&call.target, history) {
            Ok(id) => id,
            Err(message) => return ToolOutcome::failed(message),
        };
        let Some(message) = history.iter().find(|message| message.id == message_id) else {
            return ToolOutcome::failed(format!("这次会话里没有消息 {message_id}"));
        };
        if message.locked {
            return ToolOutcome::failed(format!(
                "消息 {message_id} 被用户锁定，内容不能改（压缩不受影响）"
            ));
        }
        if let Some(tools) = &call.tools {
            for (position, tool) in tools.iter().enumerate() {
                if tool.name.trim().is_empty() {
                    return ToolOutcome::failed(format!("第 {} 个工具没有名字", position + 1));
                }
            }
        }
        // 回给模型的就是**它自己写的那句 + 一条路径**：详细报告在落库时写进文件，
        // 不进上下文（用户 2026-11-26：不希望落盘太长）。
        ToolOutcome::ok(format!(
            "{}\n详细结果：{}",
            call.note,
            MessageEdit::report_path(message_id.seq())
        ))
    }
}

pub struct FindMessagesTool;

impl Tool for FindMessagesTool {
    fn name(&self) -> &str {
        "find_messages"
    }

    fn description(&self) -> &str {
        "按关键词或正文开头找这一轮看得见的历史消息，返回 id 与开头几十字。\
         改消息之前先用它拿 id（id 不会随请求发给你）。\
         只找**压缩线以下**、还能改的那些。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "query": { "type": "string", "description": "关键词，或正文的开头几个字" },
                "mode": {
                    "type": "string",
                    "enum": ["keyword", "body_prefix"],
                    "description": "keyword=全文包含（默认）；body_prefix=正文开头逐字匹配"
                },
                "limit": { "type": "integer", "description": "最多几条（默认 5，上限 10）" }
            },
            "required": ["query"]
        })
    }

    /// 与 `edit_message` 同一道门：找 id 是为了改，不能改的话找了也用不上。
    /// 而且它读的是**这一轮的历史**，不是工作区里的文件——用 `WorkspaceRead`
    /// 会在没绑工作区时把"找消息"也一起拒掉，那是错的。
    fn capability(&self) -> Capability {
        Capability::WorkspaceWrite
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let query = match required_str(args, "query") {
            Ok(query) => query.trim().to_string(),
            Err(outcome) => return outcome,
        };
        if query.is_empty() {
            return ToolOutcome::failed("`query` 是空的：给个关键词，或正文开头几个字");
        }
        let mode = args
            .get("mode")
            .and_then(Value::as_str)
            .unwrap_or("keyword");
        if mode != "keyword" && mode != "body_prefix" {
            return ToolOutcome::failed(format!(
                "`mode` 只认 keyword / body_prefix，收到 `{mode}`"
            ));
        }
        let limit = args
            .get("limit")
            .and_then(Value::as_u64)
            .map(|value| (value as usize).clamp(1, 10))
            .unwrap_or(5);
        let Some(history) = ctx.history else {
            return ToolOutcome::failed("这一轮没拿到会话历史，找不了消息");
        };

        let mut hits: Vec<&Message> = Vec::new();
        for message in history {
            let body = message.content();
            let matched = if mode == "body_prefix" {
                body.trim_start().starts_with(&query)
            } else {
                body.contains(&query)
                    || message
                        .reasoning()
                        .is_some_and(|reasoning| reasoning.contains(&query))
            };
            if matched {
                hits.push(message);
            }
        }
        if hits.is_empty() {
            return ToolOutcome::ok(format!("没有找到含「{query}」的消息"));
        }
        // 最近的在前面：模型通常要找的是"刚才那条"。
        hits.reverse();
        hits.truncate(limit);
        let mut text = String::new();
        for message in hits {
            let head: String = message
                .content()
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ")
                .chars()
                .take(40)
                .collect();
            text.push_str(&format!(
                "{} · {} · {} tok · 「{}」\n",
                message.id,
                message.role.as_str(),
                message.tokens,
                if head.is_empty() {
                    "（没有正文）"
                } else {
                    &head
                }
            ));
        }
        ToolOutcome::ok(text.trim_end().to_string())
    }
}

pub struct ReadOutputsTool;

impl Tool for ReadOutputsTool {
    fn name(&self) -> &str {
        "read_outputs"
    }

    fn description(&self) -> &str {
        "读一份报告（改消息的前后对比都在 /chat/outputs/）。\
         核对「改过什么」时用它——不要凭记忆复述。"
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "报告路径，形如 /chat/outputs/edit-97.md"
                }
            },
            "required": ["path"]
        })
    }

    /// 同上：读的是会话自己的 `/chat/outputs`，不是工作区。
    fn capability(&self) -> Capability {
        Capability::WorkspaceWrite
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        if let Err(reason) = ctx.policy.check(self.capability()) {
            return ToolOutcome::denied(self.capability(), reason);
        }
        let path = match required_str(args, "path") {
            Ok(path) => path.trim().to_string(),
            Err(outcome) => return outcome,
        };
        let Some(chat) = ctx.session_chat else {
            return ToolOutcome::failed("这一轮没有会话目录，读不了报告");
        };
        // 只认 `/chat/` 底下那一片：`..` 一律拒（这是"能读整台机器"与"只能读自己这份"的分界）。
        let relative = path
            .strip_prefix("/chat/")
            .or_else(|| path.strip_prefix("chat/"))
            .unwrap_or(path.as_str());
        if relative.is_empty() || relative.contains("..") {
            return ToolOutcome::failed(format!("路径只认 /chat/ 底下那一层，收到 `{path}`"));
        }
        let full = chat.join(relative);
        match std::fs::read_to_string(full) {
            Ok(text) => {
                // 报告可能很长：截一下，但把"截了"说出来。
                const CAP: usize = 8000;
                if text.chars().count() > CAP {
                    let head: String = text.chars().take(CAP).collect();
                    ToolOutcome::ok(format!("{head}\n…（只显示前 {CAP} 字）"))
                } else {
                    ToolOutcome::ok(text)
                }
            }
            Err(_) => ToolOutcome::failed(format!("读不到 `{path}`（文件不存在或读不了）")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::{MessageId, SessionId};
    use crate::message::{Message, OutgoingToolCall, Role};
    use crate::source::SourceTag;

    fn history() -> Vec<Message> {
        let session = SessionId::new();
        let user = Message::text(
            MessageId::new(session, 1),
            Role::User,
            SourceTag::History,
            "跑一下再告诉我",
            1,
            0,
            LogicalClock::new(1),
        );
        let mut call = Message::tool_call_message(
            MessageId::new(session, 2),
            vec![OutgoingToolCall::new("call_a", "System-Shell", "{}")],
            4,
            0,
            LogicalClock::new(2),
        );
        call.set_tool_output("call_a", "done", crate::message::ToolStatus::Ok);
        let text = Message::text(
            MessageId::new(session, 3),
            Role::Assistant,
            SourceTag::History,
            "这次改成一条等完再发下一条",
            2,
            0,
            LogicalClock::new(3),
        );
        vec![user, call, text]
    }

    /// 一句 `note` 是必填的：没有它，下一轮的自己就不知道改了什么。
    #[test]
    fn a_note_is_required() {
        let error = parse_message_edit(&json!({ "message_id": "last_assistant", "text": "x" }))
            .unwrap_err();
        assert!(error.contains("note"), "{error}");
    }

    #[test]
    fn a_too_long_note_is_refused() {
        let long = "很".repeat(MAX_NOTE_CHARS + 1);
        let error = parse_message_edit(&json!({
            "message_id": "last_assistant",
            "text": "x",
            "note": long
        }))
        .unwrap_err();
        assert!(error.contains("上限"), "{error}");
    }

    /// 三块都不给：没什么可改的，别开一条空事务。
    #[test]
    fn nothing_to_change_is_refused() {
        let error = parse_message_edit(&json!({
            "message_id": "last_assistant",
            "note": "什么都没动"
        }))
        .unwrap_err();
        assert!(error.contains("至少一块"), "{error}");
    }

    /// 工具清单可以整份给（多一条少一条都行），参数必须是 JSON 对象。
    #[test]
    fn a_tool_list_is_parsed_and_validated() {
        let ok = parse_message_edit(&json!({
            "message_id": "last_assistant",
            "note": "加一条调用",
            "tools": [
                { "name": "System-Shell", "arguments": "{\"command\":\"ls\"}", "output": "a" },
                { "name": "Read-File", "arguments": "{}" }
            ]
        }))
        .unwrap();
        assert_eq!(ok.tools.as_ref().unwrap().len(), 2);

        let bad = parse_message_edit(&json!({
            "message_id": "last_assistant",
            "note": "x",
            "tools": [{ "name": "System-Shell", "arguments": "不是 json" }]
        }))
        .unwrap_err();
        assert!(bad.contains("不是 JSON 对象"), "{bad}");
    }

    /// 报告路径按 seq 走：同一个文件，多次改就追加。
    #[test]
    fn the_report_path_is_short_and_stable() {
        assert_eq!(MessageEdit::report_path(97), "/chat/outputs/edit-97.md");
    }

    /// `to_turn_edit`：给什么写什么；没给的块一条动作都不产生。
    #[test]
    fn only_the_given_blocks_become_ops() {
        let call = parse_message_edit(&json!({
            "message_id": "last_assistant",
            "text": "新的正文",
            "note": "改正文"
        }))
        .unwrap();
        let turn = call.to_turn_edit(crate::ids::MessageId::new(SessionId::new(), 3));
        assert_eq!(turn.ops.len(), 1, "只该有一条（正文）");
        assert!(turn.tools.is_none(), "没给工具清单就不动工具块");
    }

    /// 绑一个只读工作区：这两个工具与 `edit_message` 同一道门（`WorkspaceWrite`），
    /// 没绑工作区时策略会直接拒——那不是它们的 bug，是"这个助手什么都不许写"。
    fn bound() -> (
        tempfile::TempDir,
        crate::workspace::Workspace,
        crate::policy::AssistantPolicy,
    ) {
        let dir = tempfile::tempdir().unwrap();
        let workspace = crate::workspace::Workspace::open(dir.path()).unwrap();
        let policy = crate::policy::AssistantPolicy::locked_down()
            .with_binding(crate::policy::WorkspaceBinding::read_write("ws-1"))
            .enable_network(false);
        (dir, workspace, policy)
    }

    /// 找消息：关键词命中全文，`body_prefix` 只认开头；找不到时说人话。
    #[test]
    fn find_messages_matches_body_and_prefix() {
        let history = history();
        let (_dir, workspace, policy) = bound();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_history(&history);

        let found = FindMessagesTool.invoke(&json!({ "query": "这次改成" }), &ctx);
        assert!(found.is_ok(), "{}", found.text());
        assert!(found.text().contains("assistant"), "{}", found.text());
        assert!(found.text().contains("这次改成"), "{}", found.text());

        // 前缀：整句开头能吃上，"等在中间"的吃不上的那种。
        let prefix =
            FindMessagesTool.invoke(&json!({ "query": "这次改成", "mode": "body_prefix" }), &ctx);
        assert!(prefix.is_ok(), "{}", prefix.text());
        let miss =
            FindMessagesTool.invoke(&json!({ "query": "等完再发", "mode": "body_prefix" }), &ctx);
        assert!(miss.is_ok());
        assert!(miss.text().contains("没有找到"), "{}", miss.text());

        let empty = FindMessagesTool.invoke(&json!({ "query": "  " }), &ctx);
        assert!(!empty.is_ok(), "空 query 该拒");
    }

    /// 报告读得回来，但只能在 `/chat/` 底下转；`..` 一律拒。
    #[test]
    fn read_outputs_stays_inside_the_chat_dir() {
        let dir = tempfile::tempdir().unwrap();
        let chat = dir.path().join("chat");
        std::fs::create_dir_all(chat.join("outputs")).unwrap();
        std::fs::write(chat.join("outputs/edit-3.md"), "改了一句措辞").unwrap();

        let (_ws_dir, workspace, policy) = bound();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_session_chat(Some(&chat));

        let ok = ReadOutputsTool.invoke(&json!({ "path": "/chat/outputs/edit-3.md" }), &ctx);
        assert!(ok.is_ok(), "{}", ok.text());
        assert!(ok.text().contains("改了一句措辞"));

        let escape = ReadOutputsTool.invoke(&json!({ "path": "/chat/../etc/passwd" }), &ctx);
        assert!(!escape.is_ok(), "`..` 必须拒：{}", escape.text());
    }
}
