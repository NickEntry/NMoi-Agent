//! 压缩摘录（D143 / D145）：压缩后仍然**原样**留在上下文里的那几条原文。
//!
//! 为什么不让模型"输出最多 3 条助手消息"：模型会改写。实测改写版把
//! "你叫我笨蛋也行" 改成了 "你可叫我笨蛋"，语义反了。摘录的意义就是"这是我自己的原话"，
//! 改写过的就不是原话。
//!
//! 为什么只从**窗口**里挑、不从摘要里挑：窗口是库里的真实消息序列，所以二次压缩天然是
//! **替换**——上一次的摘录只活在摘要正文里，这一次不会把它再抄一遍，不会越压越多。

use crate::message::{AtomicUnit, Message, Role};
use crate::tokens::estimate_tokens;

/// 一条摘录最多几条消息。
///
/// 实测（两个上游各 10 采/条件，口癖率）：0 条 = 0.00、1 条 = 1.56、3 条 = 2.30、5 条 = 2.83，
/// 到 3 条就接近满值，再加没有额外收益。所以 3 条是饱和点，不是随便定的。
pub const EXCERPT_MAX_MESSAGES: usize = 3;

/// 摘录的 token 上限（D144 硬上限：占用要可预测，不能靠模型自律）。
pub const EXCERPT_MAX_TOKENS: u32 = 512;

/// 摘要的 token 上限（D144 硬上限）。
pub const SUMMARY_MAX_TOKENS: u32 = 2048;

/// 从窗口里挑"最近的三条原话"，形态尽量凑成 `assistant → user → assistant`。
///
/// 规则：
///
/// - 只取整条消息，**不切片、不改写**；
/// - 跳过工具消息和带 `tool_calls` 的助手消息——它们离开伙伴就不合法，喂进上下文只会制造鬼话；
/// - 从最近往回找，凑不出想要的槽位就少给（宁缺勿造）；
/// - 超过 token 上限的那条直接不要，不做截断（截断就不是原话了）。
pub fn pick(units: &[AtomicUnit]) -> Vec<Message> {
    let candidates: Vec<&Message> = units
        .iter()
        .flat_map(|unit| unit.messages.iter())
        .filter(|message| !message.content.trim().is_empty())
        .filter(|message| message.role != Role::Tool && message.tool_calls.is_empty())
        .collect();
    if candidates.is_empty() {
        return Vec::new();
    }

    let mut picked: Vec<&Message> = Vec::new();
    let mut cursor = candidates.len();
    let mut budget = EXCERPT_MAX_TOKENS;
    for want in [Role::Assistant, Role::User, Role::Assistant] {
        if picked.len() >= EXCERPT_MAX_MESSAGES {
            break;
        }
        let mut found = None;
        for index in (0..cursor).rev() {
            let message = candidates[index];
            if message.role != want {
                continue;
            }
            let cost = estimate_tokens(&message.content);
            if cost <= budget {
                found = Some((index, cost));
            }
            // 最近的同角色消息就是候选；它超预算就放弃这个槽位，不再往更早找。
            break;
        }
        match found {
            Some((index, cost)) => {
                budget -= cost;
                cursor = index;
                picked.push(candidates[index]);
            }
            None => break,
        }
    }
    picked.reverse();
    picked.into_iter().cloned().collect()
}

/// 渲染成注入用的一段文字。没有内容返回 `None`——不产生只有一个标题的空块。
pub fn render(messages: &[Message]) -> Option<String> {
    let mut out = String::from(
        "[原文节选｜以下是这个会话里已经说过的原话，用来接上语气，不要把它当成新的一轮]\n",
    );
    let mut lines = 0usize;
    for message in messages {
        let who = match message.role {
            Role::Assistant => "我",
            Role::User => "用户",
            // 工具与系统消息不会从 `pick` 里出来；真出来了也不渲染。
            Role::Tool | Role::System => continue,
        };
        out.push_str(who);
        out.push('：');
        out.push_str(message.content.trim());
        out.push('\n');
        lines += 1;
    }
    if lines == 0 {
        return None;
    }
    Some(out.trim_end().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::LogicalClock;
    use crate::ids::{MessageId, SessionId};
    use crate::message::OutgoingToolCall;
    use crate::source::SourceTag;

    struct Builder {
        session: SessionId,
        seq: u64,
        units: Vec<AtomicUnit>,
    }

    impl Builder {
        fn new() -> Self {
            Self {
                session: SessionId::new(),
                seq: 0,
                units: Vec::new(),
            }
        }

        fn next(&mut self) -> u64 {
            self.seq += 1;
            self.seq
        }

        fn text(&mut self, role: Role, body: &str, tokens: u32) {
            let seq = self.next();
            let message = Message::text(
                MessageId::new(self.session, seq),
                role,
                SourceTag::History,
                body,
                tokens,
                0,
                LogicalClock::new(seq),
            );
            self.units.push(AtomicUnit::turn(message).unwrap());
        }

        fn user(&mut self, body: &str) {
            self.text(Role::User, body, 1);
        }

        fn assistant(&mut self, body: &str) {
            self.text(Role::Assistant, body, 1);
        }

        /// 一个完整工具轮：assistant(tool_calls) + tool_result。
        fn tool_round(&mut self) {
            let call_seq = self.next();
            let head = Message::tool_call_message(
                MessageId::new(self.session, call_seq),
                vec![OutgoingToolCall::new("c1", "read", "{}")],
                1,
                0,
                LogicalClock::new(call_seq),
            );
            let mut unit = AtomicUnit::tool_pair(head).unwrap();
            let result_seq = self.next();
            unit.add_result(
                Message::tool_result(
                    MessageId::new(self.session, result_seq),
                    "c1",
                    "工具结果不该出现在摘录里",
                    1,
                    0,
                    LogicalClock::new(result_seq),
                )
                .with_unit(unit.id.clone()),
            )
            .unwrap();
            self.units.push(unit);
        }
    }

    fn plain() -> Builder {
        let mut b = Builder::new();
        b.user("我回来了。");
        b.assistant("回来啦。");
        b.user("今天好累。");
        b.assistant("那就先坐着。");
        b.user("周六有空吗？");
        b.assistant("有。");
        b
    }

    #[test]
    fn picks_three_in_chronological_order() {
        let b = plain();
        let picked = pick(&b.units);
        assert_eq!(picked.len(), 3);
        // 期望：倒数第三条 assistant、最后一条 user、最后一条 assistant
        assert_eq!(picked[0].content, "那就先坐着。");
        assert_eq!(picked[1].content, "周六有空吗？");
        assert_eq!(picked[2].content, "有。");
        assert!(picked[0].id.seq() < picked[1].id.seq());
        assert!(picked[1].id.seq() < picked[2].id.seq());
    }

    #[test]
    fn content_is_verbatim() {
        let b = plain();
        for message in pick(&b.units) {
            let source = b
                .units
                .iter()
                .flat_map(|u| u.messages.iter())
                .find(|m| m.id == message.id)
                .expect("摘录必须来自窗口里的同一条消息");
            assert_eq!(message.content, source.content);
        }
    }

    #[test]
    fn skips_tool_messages_and_tool_calls() {
        let mut b = Builder::new();
        b.user("查一下文件。");
        b.tool_round();
        b.assistant("查完了。");
        let picked = pick(&b.units);
        assert!(picked.iter().all(|m| m.role != Role::Tool));
        assert!(picked.iter().all(|m| m.tool_calls.is_empty()));
        assert_eq!(picked.last().unwrap().content, "查完了。");
    }

    #[test]
    fn respects_token_cap_without_truncating() {
        let mut b = Builder::new();
        b.user("很短。");
        // 一条超长的助手消息：超过 EXCERPT_MAX_TOKENS，应整条放弃而不是切一半。
        let long = "长".repeat(EXCERPT_MAX_TOKENS as usize * 2);
        b.assistant(&long);
        let picked = pick(&b.units);
        assert!(picked.is_empty(), "超预算的原话不该被截断后塞进来");
    }

    #[test]
    fn empty_window_yields_nothing_and_no_empty_header() {
        let b = Builder::new();
        assert!(pick(&b.units).is_empty());
        assert!(render(&[]).is_none());
    }

    #[test]
    fn render_labels_roles_and_has_header() {
        let b = plain();
        let text = render(&pick(&b.units)).expect("有原话就该有渲染结果");
        assert!(text.starts_with("[原文节选"));
        assert!(text.contains("我：有。"));
        assert!(text.contains("用户：周六有空吗？"));
    }

    /// 二次压缩必须是替换：摘录只从窗口取，窗口里没有旧摘要，就抄不进来。
    #[test]
    fn second_pass_does_not_accumulate_old_excerpt() {
        let b = plain();
        let first = pick(&b.units);
        // 模拟"第一次压缩之后"：窗口只剩最后两条原文，前面换成摘要（摘要不在 units 里）。
        let mut after = Builder::new();
        after.user("周六有空吗？");
        after.assistant("有。");
        let second = pick(&after.units);
        assert_eq!(second.len(), 2);
        let old: Vec<String> = first.iter().map(|m| m.content.clone()).collect();
        let new: Vec<String> = second.iter().map(|m| m.content.clone()).collect();
        assert!(!new.contains(&"那就先坐着。".to_string()));
        assert!(new.iter().all(|t| old.contains(t) || t == "有。"));
    }
}
