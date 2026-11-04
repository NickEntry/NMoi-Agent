//! 上下文图片清单（0.15al / D248）。
//!
//! 一件事：**这一轮要发出去的图是哪几张**。
//!
//! 为什么要有这张表：用户发的图跟着消息走（`attachments` 表按消息 id 索引，D208），
//! 但"查看图像"这个工具把图**拉进上下文**时，那张图不属于任何一条消息——它可能来自
//! `/chat/images` 下工具自己产出的文件，也可能是上一轮留下的。所以它需要一个
//! 独立于消息的清单。
//!
//! 三条规矩：
//!
//! 1. **新的在前**。用户 2026-11-04 定的：后加进来的图排在前面，满了先丢最旧的。
//! 2. **上限按最弱的端点算**。OpenAI 500 张/请求、Gemini 3 600 张、Anthropic 100 张
//!    ——取 100 当硬上限，默认 12（用户定的）。见 [`MAX_LIMIT`]。
//! 3. **这张表是进程内的**。重启之后清单为空：图还在盘上、消息里也还在，
//!    只是"这一轮要发它"这件事得重新说一次。持久化它等于给"看不见的上下文"开口子。
//!
//! 表按会话分：多会话并发时两条会话的清单互不相干。

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// 用户定的默认值。
pub const DEFAULT_LIMIT: usize = 12;

/// 硬上限：三家端点里最弱的那个（Anthropic 100 张/请求；OpenAI 500、Gemini 3 600）。
/// 设得比它大，换一家端点就会 400。
pub const MAX_LIMIT: usize = 100;

/// 清单里的一张图。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContextImage {
    /// 附件 id（`attachments.id`，也就是盘上的文件名去掉扩展名）。
    pub id: String,
    pub mime: String,
    pub bytes: u64,
}

fn table() -> &'static Mutex<HashMap<String, Vec<ContextImage>>> {
    static TABLE: OnceLock<Mutex<HashMap<String, Vec<ContextImage>>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 把配置里的数折成合法上限：`0` = 没设 = 默认 12，超过硬上限就压到硬上限。
pub fn limit_of(raw: u32) -> usize {
    match raw as usize {
        0 => DEFAULT_LIMIT,
        n => n.min(MAX_LIMIT),
    }
}

/// 加一张图（新的在前）。返回它在清单里的位置（1 起）与被挤掉的那些。
///
/// 同一张图重复加：**挪到最前面**，不产生第二份——重复发同一张图既费 token 又占位置。
pub fn add(session: &str, image: ContextImage, limit: usize) -> (usize, Vec<ContextImage>) {
    let limit = limit.max(1);
    let mut table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    let list = table.entry(session.to_string()).or_default();
    list.retain(|existing| existing.id != image.id);
    list.insert(0, image);
    let mut dropped = Vec::new();
    while list.len() > limit {
        if let Some(old) = list.pop() {
            dropped.push(old);
        }
    }
    (1, dropped)
}

/// 这一轮要发的图，新的在前。
pub fn list(session: &str) -> Vec<ContextImage> {
    let table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    table.get(session).cloned().unwrap_or_default()
}

/// 移出一张。返回是否真的移掉了（不在清单里就是 `false`，不是错误）。
pub fn remove(session: &str, id: &str) -> bool {
    let mut table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    let Some(list) = table.get_mut(session) else {
        return false;
    };
    let before = list.len();
    list.retain(|image| image.id != id);
    before != list.len()
}

/// 清空这个会话的清单。返回清掉几张。
pub fn clear(session: &str) -> usize {
    let mut table = table().lock().unwrap_or_else(|poison| poison.into_inner());
    table.remove(session).map(|list| list.len()).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn image(id: &str) -> ContextImage {
        ContextImage {
            id: id.to_string(),
            mime: "image/jpeg".into(),
            bytes: 1024,
        }
    }

    #[test]
    fn newest_comes_first_and_the_oldest_falls_off() {
        let session = "s-images-fifo";
        add(session, image("a"), 3);
        add(session, image("b"), 3);
        add(session, image("c"), 3);
        // 新的在前。
        let ids: Vec<String> = list(session).into_iter().map(|i| i.id).collect();
        assert_eq!(ids, ["c", "b", "a"]);

        // 第 4 张进来：最旧的 a 被挤掉，而且是**返回给调用方**的（界面要说清挤掉了谁）。
        let (at, dropped) = add(session, image("d"), 3);
        assert_eq!(at, 1);
        assert_eq!(dropped.len(), 1);
        assert_eq!(dropped[0].id, "a");
        let ids: Vec<String> = list(session).into_iter().map(|i| i.id).collect();
        assert_eq!(ids, ["d", "c", "b"]);
        clear(session);
    }

    #[test]
    fn adding_the_same_image_again_moves_it_up_instead_of_duplicating() {
        let session = "s-images-dup";
        add(session, image("a"), 4);
        add(session, image("b"), 4);
        add(session, image("a"), 4);
        let ids: Vec<String> = list(session).into_iter().map(|i| i.id).collect();
        assert_eq!(ids, ["a", "b"], "重复的那张挪到最前，不产生第二份");
        clear(session);
    }

    #[test]
    fn removing_and_clearing_are_not_errors_when_there_is_nothing() {
        let session = "s-images-empty";
        assert!(!remove(session, "nope"));
        assert_eq!(clear(session), 0);
        let _ = add(session, image("a"), 2);
        assert!(remove(session, "a"));
        assert!(list(session).is_empty());
    }

    #[test]
    fn limits_fold_to_the_weakest_endpoint() {
        assert_eq!(limit_of(0), DEFAULT_LIMIT, "0 = 没设 = 默认 12");
        assert_eq!(limit_of(8), 8);
        assert_eq!(limit_of(500), MAX_LIMIT, "比 Anthropic 的 100 还大就会 400");
    }
}
