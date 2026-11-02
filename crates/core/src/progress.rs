//! 一轮生成过程中的"正在说的话"（0.15n）。
//!
//! 为什么是**线程局部**而不是参数：`turn_run` 是一次阻塞调用，**一次调用 = 一个线程**，
//! 所以"这一层现在在给谁吐字"用线程局部表达是准确的；而加参数要动
//! `run_session_turn` 的 24 个调用点（D195）。
//!
//! 用法只有两处：
//! - 装钩子：`progress::scope(sink, || ...)`（FFI 那一层装，作用域结束自动还原）；
//!   要跟着"换轮"清缓冲就用 `progress::scope_with_round(sink, round, || ...)`；
//! - 吐字：`progress::emit(text, reasoning)`（`agent::run_turn` 的流式分支调）；
//!   每轮开头 `progress::round_start()` 让听的人把上一轮那截清掉。
//!
//! 没装钩子时 `emit` 是空操作——测试与"还没接流式的地方"不用改一行。

use std::cell::RefCell;
use std::rc::Rc;

/// 增量回调：`(正文增量, 思考增量)`，两个都是**片段**，可能有一个是空的。
pub type Sink = Rc<dyn Fn(&str, &str)>;

/// 换轮回调：工具往返之后模型会**重新开口**，上一轮那段话不属于这一轮。
///
/// 界面要是把两轮拼在一起，收尾落库时正文会突然缩掉一半（库里只存最后一轮），
/// 看起来就是"气泡跳了一下"。所以每一轮开头先叫一声，让听的人把手里的缓冲清掉。
pub type RoundHook = Rc<dyn Fn()>;

thread_local! {
    static SINK: RefCell<Option<Sink>> = const { RefCell::new(None) };
    static ROUND: RefCell<Option<RoundHook>> = const { RefCell::new(None) };
}

/// 在这个作用域里装一个增量钩子；**离开作用域自动还原**（panic 也还原）。
///
/// 不叠层：scope 里再 scope 会临时替换，退出时恢复外层的那个——
/// 嵌套多半意味着"两个调用同时想往一个线程里吐字"，那本来就不该发生。
pub fn scope<R>(sink: Sink, body: impl FnOnce() -> R) -> R {
    scope_with_round(sink, Rc::new(|| {}), body)
}

/// 同 [`scope`]，多一个"换轮了"的钩子（界面要清掉上一轮那截）。
pub fn scope_with_round<R>(sink: Sink, round: RoundHook, body: impl FnOnce() -> R) -> R {
    struct Restore(Option<Sink>, Option<RoundHook>);
    impl Drop for Restore {
        fn drop(&mut self) {
            let previous = self.0.take();
            SINK.with(|slot| *slot.borrow_mut() = previous);
            let previous = self.1.take();
            ROUND.with(|slot| *slot.borrow_mut() = previous);
        }
    }

    let previous_sink = SINK.with(|slot| slot.replace(Some(sink)));
    let previous_round = ROUND.with(|slot| slot.replace(Some(round)));
    let _guard = Restore(previous_sink, previous_round);
    body()
}

/// 一轮模型请求开始（工具往返之后会再来一次）。没装钩子就是空操作。
///
/// 只在 [`scope`] 的作用域里有意义；回调里不要重入 `emit` / `round_start`。
pub fn round_start() {
    ROUND.with(|slot| {
        let borrowed = slot.borrow();
        if let Some(hook) = borrowed.as_ref() {
            hook();
        }
    });
}

/// 吐一段增量。没装钩子就是空操作。
///
/// 回调里**不要再调 emit**：SINK 正被借着，重入会 panic。钩子只该做"记下来"这一件事。
pub fn emit(text: &str, reasoning: &str) {
    SINK.with(|slot| {
        let borrowed = slot.borrow();
        if let Some(sink) = borrowed.as_ref() {
            sink(text, reasoning);
        }
    });
}

/// 现在有没有装钩子（给"要不要走流式"这类判断用；不装也可以走，只是没人听得见）。
pub fn installed() -> bool {
    SINK.with(|slot| slot.borrow().is_some())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    #[test]
    fn scope_installs_and_restores_the_sink() {
        assert!(!installed(), "没装的时候应当是空的");
        let seen = Rc::new(RefCell::new(Vec::new()));
        let sink = {
            let seen = seen.clone();
            Rc::new(move |text: &str, reasoning: &str| {
                seen.borrow_mut().push(format!("{text}|{reasoning}"));
            }) as Sink
        };
        scope(sink, || {
            assert!(installed());
            emit("你", "");
            emit("", "想一想");
        });
        assert!(!installed(), "离开作用域要还原");
        assert_eq!(*seen.borrow(), vec!["你|", "|想一想"]);
        // 钩子撤了之后 emit 不该 panic，也不该有人收到。
        emit("谁", "都不该听到");
        assert_eq!(seen.borrow().len(), 2);
    }

    #[test]
    fn emit_without_a_sink_is_a_no_op() {
        let hits = Cell::new(0);
        let _ = &hits;
        emit("没装", "钩子");
        assert!(!installed());
    }

    #[test]
    fn round_start_reaches_the_hook_and_is_restored() {
        let rounds = Rc::new(RefCell::new(0));
        let hook = {
            let rounds = rounds.clone();
            Rc::new(move || *rounds.borrow_mut() += 1) as RoundHook
        };
        let sink: Sink = Rc::new(|_: &str, _: &str| {});
        scope_with_round(sink, hook, || {
            round_start();
            round_start();
        });
        assert_eq!(*rounds.borrow(), 2);
        // 作用域外再叫一声不该有人听见，也不该 panic。
        round_start();
        assert_eq!(*rounds.borrow(), 2);
    }
}
