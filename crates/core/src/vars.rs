//! 注入文本里的变量占位符（D176）。
//!
//! 用户 2026-10-28 的口径：子 AGENT 的名单不写死在任何一处，而是在**会被注入的文本**里
//! 放一段特殊字符串，组装请求前替换掉：
//!
//! ```text
//! 你可以调用这些子助手（每条一句话说明什么时候用它）：
//! <var><sub_agent_info></var>
//! ```
//!
//! 为什么用占位符而不是"在系统提示词末尾追加一段"：
//!
//! 1. 用户要把它放在**指定位置**（比如世界书里"内部工具用法"那一段的中间），
//!    追加到末尾就做不到；
//! 2. 位置随用户写，改写权归他；我们只负责把那一段换成当下的真名单。
//!
//! 语法只有一条：`<var><名字></var>`。**认不出的名字原样留着**——这里的文本是用户写的，
//! 猜着替换等于改他的稿子。

/// 一个占位符的名字。加变量就在这里加一条，别在调用处直接拼字符串。
pub const SUB_AGENT_INFO: &str = "sub_agent_info";

/// 名单里的一条：调用 ID + 描述。
///
/// `id` 是主 AGENT 唯一的钥匙（跨助手唯一，见 D176），`description` 是"什么时候该用它"。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubAgentEntry {
    pub id: String,
    pub description: String,
}

/// 一条替换用的名单。空 = 没有可调用的子助手。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct VarTable {
    pub sub_agents: Vec<SubAgentEntry>,
}

impl VarTable {
    pub fn new(sub_agents: Vec<SubAgentEntry>) -> Self {
        Self { sub_agents }
    }

    /// `<var><sub_agent_info></var>` 替换成的那一块。
    ///
    /// 形状照用户给的样子抄（每一段用 `---` 起止，整块裹在 `{}` 里）：
    ///
    /// ```text
    /// {
    /// ---
    /// id:test_agent
    /// description:test
    /// ---
    /// }
    /// ```
    ///
    /// **一条都没有时给一句人话**，不给空壳 `{ --- }`：模型看见空壳会去猜格式，
    /// 而"现在没有子助手"本身就是它该知道的信息。
    pub fn sub_agent_info(&self) -> String {
        if self.sub_agents.is_empty() {
            return "（没有可调用的子助手）".to_string();
        }
        let mut out = String::from("{\n");
        for entry in &self.sub_agents {
            out.push_str("---\n");
            out.push_str("id:");
            out.push_str(&entry.id);
            out.push('\n');
            out.push_str("description:");
            out.push_str(&entry.description);
            out.push('\n');
        }
        out.push_str("---\n}");
        out
    }

    /// 名字 → 值。加变量时在这里登记一处，`replace` 不用改。
    fn value(&self, name: &str) -> Option<String> {
        match name {
            SUB_AGENT_INFO => Some(self.sub_agent_info()),
            _ => None,
        }
    }
}

/// 把 `text` 里所有 `<var><名字></var>` 换成对应的值。
///
/// 规则只有三条，都好测：
/// - 认得的名字 → 换掉；
/// - **认不出的名字原样保留**（用户写的文本不该被我们改）；
/// - 同一个名字出现几次就换几次（同一份名单可能被用在两处）。
pub fn replace(text: &str, table: &VarTable) -> String {
    const OPEN: &str = "<var><";
    const CLOSE: &str = "></var>";

    // 快路径：绝大多数文本里没有占位符，别为它分配一堆小字符串。
    if !text.contains(OPEN) {
        return text.to_string();
    }

    let mut out = String::with_capacity(text.len() + 64);
    let mut rest = text;
    while let Some(start) = rest.find(OPEN) {
        out.push_str(&rest[..start]);
        let after_open = &rest[start + OPEN.len()..];
        match after_open.find(CLOSE) {
            Some(end) => {
                let name = &after_open[..end];
                match table.value(name) {
                    Some(value) => out.push_str(&value),
                    // 认不出来：连标记一起原样写回去。
                    None => {
                        out.push_str(OPEN);
                        out.push_str(name);
                        out.push_str(CLOSE);
                    }
                }
                rest = &after_open[end + CLOSE.len()..];
            }
            None => {
                // 只有开标记没有闭标记：剩下的全是正文，别再找了。
                out.push_str(&rest[start..]);
                return out;
            }
        }
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(id: &str, description: &str) -> SubAgentEntry {
        SubAgentEntry {
            id: id.to_string(),
            description: description.to_string(),
        }
    }

    /// 用户给的形状要一字不差（这是给模型看的协议，多一个空行都会让某些模型把 `---` 当分隔线）。
    #[test]
    fn two_sub_agents_are_rendered_in_the_users_shape() {
        let table = VarTable::new(vec![
            entry("test_agent", "test"),
            entry("test_agent2", "test"),
        ]);
        assert_eq!(
            table.sub_agent_info(),
            "{\n---\nid:test_agent\ndescription:test\n---\nid:test_agent2\ndescription:test\n---\n}"
        );
    }

    #[test]
    fn one_sub_agent_has_no_double_separator() {
        let table = VarTable::new(vec![entry("writer", "改文案时用它")]);
        assert_eq!(
            table.sub_agent_info(),
            "{\n---\nid:writer\ndescription:改文案时用它\n---\n}"
        );
    }

    /// 没有子助手时给一句人话：空壳 `{ --- }` 会让模型去猜。
    #[test]
    fn an_empty_table_says_there_are_none() {
        assert_eq!(
            VarTable::default().sub_agent_info(),
            "（没有可调用的子助手）"
        );
    }

    /// 占位符在句子中间也要能换，前后的字一个不能少。
    #[test]
    fn the_placeholder_is_replaced_inside_a_sentence() {
        let table = VarTable::new(vec![entry("test_agent", "test")]);
        let text = "你可以调用这些子助手：\n<var><sub_agent_info></var>\n用不上的时候别提它。";
        let out = replace(text, &table);
        assert!(out.starts_with("你可以调用这些子助手：\n{\n---\nid:test_agent\n"));
        assert!(out.ends_with("---\n}\n用不上的时候别提它。"), "{out}");
    }

    /// 出现两次就换两次（同一份名单可能被用在两处）。
    #[test]
    fn every_occurrence_is_replaced() {
        let table = VarTable::new(vec![entry("a", "A")]);
        let out = replace(
            "<var><sub_agent_info></var> 和 <var><sub_agent_info></var>",
            &table,
        );
        assert_eq!(out.matches("id:a").count(), 2, "{out}");
    }

    /// **认不出的名字原样留着**：那是用户写的稿子，我们只换自己认得的那几个。
    #[test]
    fn unknown_placeholders_are_left_untouched() {
        let table = VarTable::new(vec![entry("a", "A")]);
        let text = "前<var><not_a_thing></var>后";
        assert_eq!(replace(text, &table), text);
    }

    /// 只有开标记、没有闭标记：剩下的当正文，不吞字、不重复。
    #[test]
    fn an_unclosed_placeholder_is_left_as_text() {
        let table = VarTable::new(vec![entry("a", "A")]);
        let text = "开始 <var><sub_agent_info";
        assert_eq!(replace(text, &table), text);
    }

    /// 没有占位符的文本原样返回（这是绝大多数情况）。
    #[test]
    fn plain_text_is_returned_unchanged() {
        let table = VarTable::new(vec![entry("a", "A")]);
        let text = "就是一段普通提示词，没有任何标记。";
        assert_eq!(replace(text, &table), text);
    }
}
