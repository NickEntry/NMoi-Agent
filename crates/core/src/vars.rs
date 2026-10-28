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

/// 一条名单里的问题。**每条都要能点名**：界面上的红字必须说清是哪一条、哪里不对。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Problem {
    /// 调用 ID 的形状不对（只允许小写字母、数字、下划线；1–32 个字符）。
    BadId(String),
    /// 两个助手填了同一个调用 ID。`first` 是它的 id，`second` 是撞上它的那个。
    Duplicate {
        id: String,
        first: String,
        second: String,
    },
    /// 描述是空的。模型要靠这句话决定"什么时候用它"，空描述等于没登记。
    EmptyDescription(String),
}

impl Problem {
    /// 给界面那句人话。
    pub fn describe(&self) -> String {
        match self {
            Problem::BadId(id) => {
                format!("调用 ID「{id}」不合法：只能用小写字母、数字和下划线，1–32 个字符")
            }
            Problem::Duplicate { id, first, second } => {
                format!("调用 ID「{id}」重复了：助手 {first} 与 {second} 都用了它")
            }
            Problem::EmptyDescription(id) => {
                format!("子助手「{id}」还没写描述：主 AGENT 靠它决定什么时候调用")
            }
        }
    }
}

/// 校验一份名单。**空 = 全部合格**。
///
/// 为什么查重放在这里而不是各端各写一遍：`id` 是主 AGENT 手里唯一的钥匙，
/// 重名就意味着"调用随机一个"。客户端漏一次，故障表现是"它有时叫错人"，最难查的那一类。
///
/// `owners` 与 `entries` 一一对应（助手自己的 id），用来把"和谁撞了"说出来。
pub fn validate(entries: &[(String, SubAgentEntry)]) -> Vec<Problem> {
    let mut problems = Vec::new();
    let mut seen: std::collections::BTreeMap<&str, &str> = std::collections::BTreeMap::new();
    for (owner, entry) in entries {
        if !is_valid_id(&entry.id) {
            problems.push(Problem::BadId(entry.id.clone()));
        }
        if let Some(first) = seen.get(entry.id.as_str()) {
            problems.push(Problem::Duplicate {
                id: entry.id.clone(),
                first: (*first).to_string(),
                second: owner.clone(),
            });
        } else {
            seen.insert(entry.id.as_str(), owner.as_str());
        }
        if entry.description.trim().is_empty() {
            problems.push(Problem::EmptyDescription(entry.id.clone()));
        }
    }
    problems
}

/// 调用 ID 的形状：小写字母 / 数字 / 下划线，1–32 个字符。
///
/// 只收小写：模型抄写 ID 的频率不低，`Test_Agent` 与 `test_agent` 在它眼里是同一个东西，
/// 允许大写就等于允许"看起来一样、其实不是一条"的故障。
pub fn is_valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 32
        && id
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
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

    fn owned(owner: &str, id: &str, description: &str) -> (String, SubAgentEntry) {
        (owner.to_string(), entry(id, description))
    }

    #[test]
    fn a_clean_list_has_no_problems() {
        let list = vec![
            owned("a1", "test_agent", "test"),
            owned("a2", "test_agent2", "test"),
        ];
        assert!(validate(&list).is_empty());
    }

    /// 撞名要**说清和谁撞了**：只说"id 重复"的话，用户得自己一个个翻。
    #[test]
    fn a_duplicate_names_both_owners() {
        let list = vec![
            owned("assistant-a", "writer", "改文案"),
            owned("assistant-b", "writer", "也是改文案"),
        ];
        let problems = validate(&list);
        assert_eq!(problems.len(), 1, "{problems:?}");
        let text = problems[0].describe();
        assert!(text.contains("writer"), "{text}");
        assert!(
            text.contains("assistant-a") && text.contains("assistant-b"),
            "{text}"
        );
    }

    #[test]
    fn bad_shapes_and_empty_descriptions_are_named() {
        let list = vec![
            owned("a1", "Test_Agent", "大写不行"),
            owned("a2", "带空格 的", "不行"),
            owned("a3", "", "空的 id 不行"),
            owned("a4", &"x".repeat(33), "太长"),
            owned("a5", "ok_id", "   "),
        ];
        let problems = validate(&list);
        // 四个形状问题 + 一条空描述
        assert_eq!(problems.len(), 5, "{problems:?}");
        assert!(problems.iter().any(|p| p.describe().contains("还没写描述")));
    }

    /// 校验与渲染是两件事：不合法的照样能被渲染（界面要能"先展示、再报错"），
    /// 但校验必须拦住它。这条测试是为了防止以后有人把校验塞进渲染里。
    #[test]
    fn validation_does_not_change_rendering() {
        let table = VarTable::new(vec![entry("BAD ID", "x")]);
        assert!(table.sub_agent_info().contains("id:BAD ID"));
        let problems = validate(&[owned("a", "BAD ID", "x")]);
        assert!(!problems.is_empty());
    }

    #[test]
    fn id_pattern_edges() {
        assert!(is_valid_id("a"));
        assert!(is_valid_id("test_agent_2"));
        assert!(is_valid_id(&"y".repeat(32)));
        assert!(!is_valid_id(&"y".repeat(33)));
        assert!(!is_valid_id("Agent"));
        assert!(!is_valid_id("a-b"));
        assert!(!is_valid_id("a b"));
        assert!(!is_valid_id(""));
    }
}
