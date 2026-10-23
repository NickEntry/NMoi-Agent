//! 设置文档：助手、会话、模型覆盖的持久化形状（D83 / D86）。
//!
//! 这一层只定"存什么"，不定"怎么存"。存储侧是 `(scope, key) → 一份 JSON 文档`，
//! 因为设置永远是整份读、整份写：没有人会去查"所有把 thinking 设成 max 的模型"。
//! 用文档而不是列，是为了以后加一个开关不必再迁移一次 schema——
//! 这类"每次 UI 加一行就要改表"的迁移，做一个丢一个。
//!
//! 三条规矩，与 D79 同源：
//!
//! 1. **字段只增不减**，`#[serde(default)]` 全部打开：旧文档读进新代码要能跑。
//! 2. **不存推导值**：能力、档位这些是模型事实，存在快照里，这里只存"用户选了什么"。
//! 3. **工作区工具没有开关**（用户 2026-10-21 更正）：它们是"工作区绑定了"的一部分，
//!    绑了就在，不绑就没有。给它一个开关就会出现"绑了工作区但工具是关的"这种摆不明白的状态。

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// 文档格式版本。**读的时候比它新就当作坏数据**：新版本写的字段我们看不懂，
/// 硬读会把不认识的字段丢掉，再写回去就等于把用户的设置静默删了。
pub const DOC_VERSION: i64 = 1;

/// 助手 / 会话共用的那一份聊天设置。
///
/// 会话创建时从助手**复制**一份（D83），之后各改各的。复制而不是引用，
/// 是因为用户会"同一个助手开三个会话试不同设置"，改助手把另外两个一起改掉是灾难。
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct ChatSettings {
    /// 会话/助手自己那一份系统提示词。
    pub system_prompt: String,
    /// 绑定的对话模型（D77）。子 AGENT 从这里取。
    pub model: Option<AssistantModel>,
    pub skills: SkillSelection,
    pub tools: ToolSwitches,
    /// 世界书默认勾选（D81 的第二级门；第一级在全局设置里）。
    pub world_books: Vec<String>,
    /// 视觉覆盖（原 OCR）是否开启。
    pub vision_override: bool,
}

/// 一个模型绑定。`provider` 是供应商条目 id，不是类型——同名模型挂在不同网关上是两回事。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssistantModel {
    pub provider: String,
    pub model: String,
}

/// 技能的启用方式：全部 / 点名。
///
/// 注意这里的"启用"只影响**注入**（name + description），不影响文件能不能读（D80）。
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct SkillSelection {
    /// 使用全部已发现的技能。
    pub use_all: bool,
    /// `use_all` 为假时，点名启用的技能。
    pub names: Vec<String>,
}

/// 工具开关。三层各有各的位置，别混（D85 / 用户 2026-10-21 的更正）。
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct ToolSwitches {
    /// 本地工具：**在聊天面板上开**。关掉只是不给模型声明，不影响别的。
    pub local: bool,
    /// MCP：两级门——这里启用了才会去连接；**连不上就不进工具表**。
    /// 键是服务器配置 id，值是启用与否。
    pub mcp: BTreeMap<String, bool>,
}

impl ToolSwitches {
    /// 这一份设置里，实际应该去连接的 MCP 服务器。
    pub fn mcp_to_connect(&self) -> Vec<&str> {
        self.mcp
            .iter()
            .filter(|(_, enabled)| **enabled)
            .map(|(id, _)| id.as_str())
            .collect()
    }
}

/// 一个助手：名字 + 一份聊天设置。
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct AssistantSettings {
    pub name: String,
    pub chat: ChatSettings,
}

/// 一个会话的设置：来源助手 + 自己那一份。
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct ConversationSettings {
    /// 从哪个助手复制来的。助手被删了也留着这个 id——那是历史，不是外键。
    pub assistant_id: Option<String>,
    pub chat: ChatSettings,
}

impl ConversationSettings {
    /// 新建会话：从助手复制一份，之后互不影响。
    pub fn from_assistant(assistant_id: impl Into<String>, assistant: &AssistantSettings) -> Self {
        Self {
            assistant_id: Some(assistant_id.into()),
            chat: assistant.chat.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn old_documents_without_new_fields_still_load() {
        // 只写一个字段，其余靠 default 补齐：这就是"字段只增不减"的验收方式。
        let doc: ChatSettings = serde_json::from_str(r#"{"system_prompt":"你是助手"}"#).unwrap();
        assert_eq!(doc.system_prompt, "你是助手");
        assert!(!doc.vision_override);
        assert!(!doc.tools.local);
        assert!(doc.skills.names.is_empty());
    }

    #[test]
    fn conversation_copies_the_assistant_and_does_not_share() {
        let mut assistant = AssistantSettings {
            name: "写代码".into(),
            ..AssistantSettings::default()
        };
        assistant.chat.system_prompt = "你是写代码的".into();
        assistant.chat.tools.local = true;

        let mut conversation = ConversationSettings::from_assistant("as_1", &assistant);
        assert_eq!(conversation.assistant_id.as_deref(), Some("as_1"));
        assert_eq!(conversation.chat.system_prompt, "你是写代码的");

        // 会话里改，助手不动（复制语义）
        conversation.chat.system_prompt = "这次只写测试".into();
        assistant.chat.tools.local = false;
        assert_eq!(conversation.chat.system_prompt, "这次只写测试");
        assert!(conversation.chat.tools.local, "复制之后各走各的");
    }

    #[test]
    fn workspace_tools_have_no_switch() {
        // 结构上就不给开关：序列化出来的字段里不该出现 workspace 之类的键。
        let json = serde_json::to_string(&ToolSwitches::default()).unwrap();
        assert!(!json.contains("workspace"), "{json}");
        assert!(json.contains("\"local\""));
    }

    #[test]
    fn only_enabled_mcp_servers_are_connected() {
        let mut tools = ToolSwitches::default();
        tools.mcp.insert("srv_a".into(), true);
        tools.mcp.insert("srv_b".into(), false);
        assert_eq!(tools.mcp_to_connect(), vec!["srv_a"]);
    }
}
