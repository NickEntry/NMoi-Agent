//! 内容落盘时的**索引摘要**（D322）。
//!
//! 一段工具输出被降级（F2 尾注 / F3 占位 / F4 指针）之后，模型手里只剩一行标记。
//! 那一行要能回答"这段里有没有我要的东西、跟旁边那段有什么区别"——否则它只能
//! 挨个把原文读回来，等于降级白做。
//!
//! 三条规矩：
//!
//! 1. **一次看几段，不是一段一次**。索引的价值在比较：只有把几段摆在一起，
//!    模型才写得出"第 2 段是失败那次、第 3 段是成功那次，差别在 Host 头"。
//!    一段一次写出来的永远是"一段工具输出"。
//! 2. **只喂头尾样本**。摘要是索引，不是复述；把 8.2k token 的原文整段塞进
//!    摘要请求里，等于为了省 8k 又花掉 8k。
//! 3. **失败就留空**。留空的那段标记照样能读、原文照样在 `/archive`；
//!    下一轮再补（`pending`）。宁可暂时没有索引，也不要编一句没读过的。

use crate::archive::ArchiveEntry;
use crate::ids::ArchiveRef;
use crate::message::Role;
use crate::provider::{ProviderConfig, ThinkingSetting};
use crate::runtime::{ApiKey, ChatClient, ChatRequest};
use crate::transport::Transport;

/// 一次请求最多几段。超过就分几次——但一次不超过这个数，
/// 是为了让"第几段"这种互相指认还有效。
pub const BATCH: usize = 4;

/// 一段喂给模型多少字：头 1200 / 尾 400。
///
/// 头尾各留一段是有理由的：工具输出里"命令是什么"在前，"结果如何"在后，
/// 中间那几万行重复日志对判断"要不要取回"没有帮助。
const HEAD_CHARS: usize = 1_200;
const TAIL_CHARS: usize = 400;

/// 一行索引最多几个字。长了就不是索引了，是第二份摘要。
pub const LINE_MAX_CHARS: usize = 48;

/// 这个会话用哪个模型写索引。`None` = 这一轮不做摘要（标记就只是标记）。
#[derive(Debug, Clone)]
pub struct SummaryTarget {
    pub provider: ProviderConfig,
    pub model: String,
    pub api_key: Option<ApiKey>,
    /// 思考强度（D313 那套档位）。默认 `disabled` = 一个字段都不注。
    pub thinking: ThinkingSetting,
    /// 全局系统提示词（D318）那一层。`None` = 只有脚手架。
    ///
    /// 它排在脚手架之后：脚手架说"你在写索引、几段一行"，那一段说"这台设备上怎么说话"。
    pub system: Option<String>,
}

/// 摘要请求的脚手架。**不是**用户写的那份——它是内部调用，写死在代码里。
///
/// 界面上能改的那一层是「摘要」这个角色的系统提示词（D318），它排在这一段之后；
/// 这里只说清任务形状（几段、每段一行、多少字），不规定语气。
pub const SCAFFOLD: &str = "\
你在给几段**已经移出上下文**的工具输出写索引。读到这份索引的人手里只有索引，
要据此决定哪一段值得把原文取回来看。

规则：
1. 一段一行，行首写段号，形如 `1. …`。
2. 每行不超过 48 个字。写「里面有什么、跟别的段有什么区别」，不要复述内容。
3. 尽量带上能当判据的硬东西：命令名、文件名、错误码、数字。
4. 看不清或没把握就写「看不清」，不要猜。";

/// 一段要写索引的条目。`None` 的字段说明这一段本身没内容可看。
pub fn prompt_for(entries: &[&ArchiveEntry]) -> String {
    let mut out = String::from("段数：");
    out.push_str(&entries.len().to_string());
    out.push_str("\n\n");
    for (index, entry) in entries.iter().enumerate() {
        out.push_str(&format!(
            "【{}】{} tok · {} · {}\n",
            index + 1,
            entry.tokens,
            entry.source,
            entry.descriptor
        ));
        out.push_str(&sample(&entry.content));
        out.push_str("\n\n");
    }
    out.push_str("按上面的段号，一段一行写索引。");
    out
}

/// 头尾样本。短内容原样给——截断没有意义，还多付一次拼接。
fn sample(content: &str) -> String {
    let chars: Vec<char> = content.chars().collect();
    let limit = HEAD_CHARS + TAIL_CHARS;
    if chars.len() <= limit {
        return content.to_string();
    }
    let head: String = chars[..HEAD_CHARS].iter().collect();
    let tail: String = chars[chars.len() - TAIL_CHARS..].iter().collect();
    format!(
        "{}\n…（中间略去 {} 字）…\n{}",
        head.trim_end(),
        chars.len() - limit,
        tail.trim_start()
    )
}

/// 从模型回的那段话里挑出索引行：`1. xxx` / `1) xxx` / `【1】xxx`。
///
/// 返回 `(段号, 文本)`，段号是 1-based。**不猜**：超过 [`LINE_MAX_CHARS`] 的、
/// 认不出段号的，一律丢掉——丢掉的那一段保持"待补"，比塞一句垃圾进上下文好。
pub fn parse_lines(text: &str) -> Vec<(usize, String)> {
    let mut out = Vec::new();
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        let (number, rest) = match split_number(line) {
            Some(pair) => pair,
            None => continue,
        };
        let cleaned = rest
            .trim_start_matches(['.', '、', ')', '：', ':', ' ', '】'])
            .trim();
        if cleaned.is_empty() || cleaned.chars().count() > LINE_MAX_CHARS {
            continue;
        }
        out.push((number, cleaned.to_string()));
    }
    out
}

fn split_number(line: &str) -> Option<(usize, &str)> {
    let line = line.strip_prefix('【').unwrap_or(line);
    let digits: String = line.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        return None;
    }
    let number: usize = digits.parse().ok()?;
    let rest = &line[digits.len()..];
    Some((number, rest))
}

/// 把解析结果对回条目：段号越界、重复的丢。
pub fn map_lines(
    entries: &[ArchiveEntry],
    lines: Vec<(usize, String)>,
) -> Vec<(ArchiveRef, String)> {
    let mut out: Vec<(ArchiveRef, String)> = Vec::new();
    for (number, text) in lines {
        if number == 0 || number > entries.len() {
            continue;
        }
        let entry = &entries[number - 1];
        if out.iter().any(|(known, _)| known == &entry.rf) {
            continue;
        }
        out.push((entry.rf.clone(), text));
    }
    out
}

/// 真的跑一次。`Ok(vec![])` = 模型没给出能用的行（调用方保持待补）；
/// `Err` = 连请求都没成（同样保持待补，只是原因不同）。
pub fn run<T: Transport>(
    client: &ChatClient<T>,
    target: &SummaryTarget,
    entries: &[ArchiveEntry],
) -> Result<Vec<(ArchiveRef, String)>, String> {
    if entries.is_empty() {
        return Ok(Vec::new());
    }
    let picked: Vec<&ArchiveEntry> = entries.iter().take(BATCH).collect();
    let prompt = prompt_for(&picked);
    let system = target
        .system
        .as_deref()
        .map(str::trim)
        .filter(|text| !text.is_empty());
    let mut turns: Vec<(Role, &str)> = vec![(Role::System, SCAFFOLD)];
    if let Some(system) = system {
        turns.push((Role::System, system));
    }
    turns.push((Role::User, prompt.as_str()));
    let mut request =
        ChatRequest::from_turns(target.provider.clone(), target.model.clone(), &turns)
            .without_stream();
    if let Some(key) = &target.api_key {
        request = request.api_key(key.as_str().to_string());
    }
    if target.thinking.effort.is_some()
        || target.thinking.field != crate::provider::ThinkingField::None
    {
        request = request.thinking(target.thinking.clone());
    }
    let done = client
        .complete(&request)
        .map_err(|error| format!("摘要请求失败：{error}"))?;
    Ok(map_lines(
        &picked.iter().map(|e| (*e).clone()).collect::<Vec<_>>(),
        parse_lines(&done.text),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::archive::{ArchiveEntry, Origin};
    use crate::ids::{MessageId, SessionId};
    use crate::source::SourceTag;

    fn entry(content: &str, tokens: u32, descriptor: &str) -> ArchiveEntry {
        let session = SessionId::new();
        ArchiveEntry::new_redacted(
            content.to_string(),
            SourceTag::ToolResult,
            Origin::new(session, MessageId::new(session, 1), 0),
            tokens,
            0,
        )
        .with_descriptor(descriptor)
    }

    #[test]
    fn prompt_numbers_every_block_and_keeps_both_ends() {
        let long = format!("HEAD{}TAIL", "x".repeat(4_000));
        let one = entry(&long, 8_200, "构建失败");
        let two = entry("短内容", 3, "一小段");
        let prompt = prompt_for(&[&one, &two]);
        assert!(prompt.contains("【1】8200 tok"), "{prompt}");
        assert!(prompt.contains("HEAD"), "头要留着：{prompt}");
        assert!(prompt.contains("TAIL"), "尾要留着：{prompt}");
        assert!(prompt.contains("中间略去"), "长内容要标出省略：{prompt}");
        assert!(prompt.contains("短内容"), "短内容原样给：{prompt}");
    }

    #[test]
    fn lines_are_parsed_by_number_and_overlong_is_dropped() {
        let text = "1. 失败那次的构建日志\n2) 成功那次，差别在 Host 头\n\
                    3、这句刚好在长度以内\n4. "
            .to_string()
            + &"超".repeat(LINE_MAX_CHARS + 1);
        let lines = parse_lines(&text);
        assert_eq!(
            lines,
            vec![
                (1, "失败那次的构建日志".to_string()),
                (2, "成功那次，差别在 Host 头".to_string()),
                (3, "这句刚好在长度以内".to_string()),
            ],
            "超长的那一句该被丢掉，不是截断"
        );
    }

    #[test]
    fn lines_map_back_to_refs_and_out_of_range_is_dropped() {
        let one = entry("a", 10, "一");
        let two = entry("b", 10, "二");
        let entries = vec![one.clone(), two.clone()];
        let mapped = map_lines(
            &entries,
            vec![
                (2, "第二段".to_string()),
                (9, "越界".to_string()),
                (2, "重复".to_string()),
            ],
        );
        assert_eq!(mapped, vec![(two.rf.clone(), "第二段".to_string())]);
    }

    #[test]
    fn summary_only_shows_up_when_there_is_one() {
        let bare = entry("x", 8_200, "一");
        let with = bare.clone().with_note("失败那次的构建日志");
        assert!(
            !crate::form::truncation_marker(bare.tokens, &bare.rf, bare.summary()).contains('|')
        );
        let line = crate::form::truncation_marker(with.tokens, &with.rf, with.summary());
        assert!(line.contains("失败那次的构建日志"), "{line}");
        assert!(line.ends_with(']'), "{line}");
    }
}
