//! 来源标签（设计文档 §5.3）。
//!
//! 这一层是整套架构的地基：没有它，模式 2 的规则无法"只压工具输出、不动用户输入"，
//! 模式 3 的语义判断也没有依据。

use std::str::FromStr;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceTag {
    /// 系统提示词本体。
    System,
    /// 运行时注入的指令（压缩脚手架、任务锚点等）。
    InstructionInjection,
    /// 对话历史（用户与助手的普通发言）。
    History,
    /// 助手发起的工具调用。
    ToolCall,
    /// 工具返回值。
    ToolResult,
    /// 工具定义（schema）。路由规则 1 的保护对象之一。
    ToolDefinition,
    /// 长期记忆。
    Memory,
    /// 世界书条目。
    WorldBook,
    /// 技能包内容。
    Skills,
    /// 工作区文件。
    Workspace,
    /// 搜索结果。
    Search,
    /// 模型思考内容。
    ModelThinking,
    /// 断块修复产出的文字块（P4.1 / D66）。
    ///
    /// 它不是模型说过的话，也不是用户打的字：是"这一段当时断了"的翻译结果。
    /// 单独一类，压缩与路由才能区别对待——比如它可以被压缩，但描述里要保住声明行。
    BrokenBlock,
    /// 视觉覆盖产出的文字描述（D22 / D62）。
    ///
    /// 它不是用户打的字，也不是模型自己想说的：是"附件被人替你看过了"的产物。
    /// 单独一类的好处是路由与压缩能区别对待——比如压缩时它跟着它描述的那条消息走，
    /// 而不是被当成用户输入保护起来。
    VisionBridge,
}

impl SourceTag {
    pub const ALL: [SourceTag; 14] = [
        SourceTag::System,
        SourceTag::InstructionInjection,
        SourceTag::History,
        SourceTag::ToolCall,
        SourceTag::ToolResult,
        SourceTag::ToolDefinition,
        SourceTag::Memory,
        SourceTag::WorldBook,
        SourceTag::Skills,
        SourceTag::Workspace,
        SourceTag::Search,
        SourceTag::ModelThinking,
        SourceTag::BrokenBlock,
        SourceTag::VisionBridge,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            SourceTag::System => "system",
            SourceTag::InstructionInjection => "instruction_injection",
            SourceTag::History => "history",
            SourceTag::ToolCall => "tool_call",
            SourceTag::ToolResult => "tool_result",
            SourceTag::ToolDefinition => "tool_definition",
            SourceTag::Memory => "memory",
            SourceTag::WorldBook => "world_book",
            SourceTag::Skills => "skills",
            SourceTag::Workspace => "workspace",
            SourceTag::Search => "search",
            SourceTag::ModelThinking => "model_thinking",
            SourceTag::BrokenBlock => "broken_block",
            SourceTag::VisionBridge => "vision_bridge",
        }
    }

    /// 这类来源是否**永不参与自动路由**（§2.3 规则 1）。
    pub fn never_routed(self) -> bool {
        matches!(
            self,
            SourceTag::System | SourceTag::ToolDefinition | SourceTag::InstructionInjection
        )
    }

    /// 是否属于"模型产出的废料"，可被 `discard_candidate` 处理（§3.4.2）。
    pub fn is_model_waste(self) -> bool {
        matches!(self, SourceTag::ModelThinking | SourceTag::ToolCall)
    }
}

impl FromStr for SourceTag {
    type Err = UnknownSourceTag;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        for tag in Self::ALL {
            if tag.as_str() == value {
                return Ok(tag);
            }
        }
        Err(UnknownSourceTag(value.to_string()))
    }
}

impl std::fmt::Display for SourceTag {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("未知来源标签: {0}")]
pub struct UnknownSourceTag(pub String);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_all_tags() {
        for tag in SourceTag::ALL {
            assert_eq!(tag.as_str().parse::<SourceTag>().unwrap(), tag);
        }
    }

    #[test]
    fn protected_sources_are_never_routed() {
        assert!(SourceTag::System.never_routed());
        assert!(SourceTag::ToolDefinition.never_routed());
        assert!(!SourceTag::ToolResult.never_routed());
    }
}
