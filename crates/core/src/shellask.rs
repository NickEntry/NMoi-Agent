use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::shellguard::Hit;

pub const ASK_TIMEOUT: Duration = Duration::from_secs(60);

const COMMAND_CAP: usize = 2_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Answer {
    AllowOnce,
    AllowRule,
    Deny,
}

impl Answer {
    pub fn parse(text: &str) -> Option<Answer> {
        match text.trim().to_ascii_lowercase().as_str() {
            "allow" | "allow_once" | "once" => Some(Answer::AllowOnce),
            "allow_rule" | "rule" => Some(Answer::AllowRule),
            "deny" | "reject" => Some(Answer::Deny),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Answer::AllowOnce => "allow_once",
            Answer::AllowRule => "allow_rule",
            Answer::Deny => "deny",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AskOutcome {
    Answered(Answer),
    TimedOut,
    Cancelled,
    Gone,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingAsk {
    pub call_id: String,
    pub command: String,
    pub label: String,
    pub group: String,
    pub seconds_left: u64,
}

struct Waiter {
    command: String,
    label: String,
    group: String,
    started: Instant,
    slot: Arc<(Mutex<Option<Answer>>, Condvar)>,
}

type Table = HashMap<String, HashMap<String, Waiter>>;

fn table() -> &'static Mutex<Table> {
    static TABLE: OnceLock<Mutex<Table>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn allowed() -> &'static Mutex<HashMap<String, HashSet<String>>> {
    static ALLOWED: OnceLock<Mutex<HashMap<String, HashSet<String>>>> = OnceLock::new();
    ALLOWED.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lock<T>(mutex: &'static Mutex<T>) -> std::sync::MutexGuard<'static, T> {
    mutex.lock().unwrap_or_else(|poison| poison.into_inner())
}

pub fn pending(session: &str) -> Vec<PendingAsk> {
    let table = lock(table());
    let Some(calls) = table.get(session) else {
        return Vec::new();
    };
    let mut out: Vec<PendingAsk> = calls
        .iter()
        .map(|(call_id, waiter)| PendingAsk {
            call_id: call_id.clone(),
            command: waiter.command.clone(),
            label: waiter.label.clone(),
            group: waiter.group.clone(),
            seconds_left: ASK_TIMEOUT
                .saturating_sub(waiter.started.elapsed())
                .as_secs(),
        })
        .collect();
    out.sort_by(|left, right| left.call_id.cmp(&right.call_id));
    out
}

pub fn answer(session: &str, call_id: &str, verdict: Answer) -> bool {
    let table = lock(table());
    let Some(waiter) = table.get(session).and_then(|calls| calls.get(call_id)) else {
        return false;
    };
    let (slot, signal) = &*waiter.slot;
    *slot.lock().unwrap_or_else(|poison| poison.into_inner()) = Some(verdict);
    signal.notify_all();
    true
}

pub fn allows_rule(session: &str, rule: &str) -> bool {
    let allowed = lock(allowed());
    allowed
        .get(session)
        .map(|rules| rules.contains(rule))
        .unwrap_or(false)
}

pub fn allow_rule(session: &str, rule: &str) {
    let mut allowed = lock(allowed());
    allowed
        .entry(session.to_string())
        .or_default()
        .insert(rule.to_string());
}

pub fn clear_session(session: &str) {
    lock(table()).remove(session);
    lock(allowed()).remove(session);
}

pub fn ask<F>(
    session: &str,
    call_id: &str,
    command: &str,
    hit: &Hit,
    cancelled: F,
    timeout: Duration,
) -> AskOutcome
where
    F: Fn() -> bool,
{
    let slot = Arc::new((Mutex::new(None::<Answer>), Condvar::new()));
    let waiter = Waiter {
        command: clip(command),
        label: hit.label.clone(),
        group: hit.group_name.clone(),
        started: Instant::now(),
        slot: Arc::clone(&slot),
    };

    {
        let mut table = lock(table());
        let calls = table.entry(session.to_string()).or_default();
        if let Some(old) = calls.insert(call_id.to_string(), waiter) {
            let (old_slot, old_signal) = &*old.slot;
            *old_slot.lock().unwrap_or_else(|poison| poison.into_inner()) = Some(Answer::Deny);
            old_signal.notify_all();
        }
    }

    let deadline = Instant::now() + timeout;
    let outcome = loop {
        if cancelled() {
            break AskOutcome::Cancelled;
        }
        let (mutex, signal) = &*slot;
        let guard = mutex.lock().unwrap_or_else(|poison| poison.into_inner());
        if let Some(verdict) = *guard {
            break AskOutcome::Answered(verdict);
        }
        let now = Instant::now();
        if now >= deadline {
            break AskOutcome::TimedOut;
        }
        let wait = (deadline - now).min(Duration::from_millis(100));
        let _ = signal
            .wait_timeout(guard, wait)
            .unwrap_or_else(|poison| poison.into_inner());
    };

    let mut table = lock(table());
    let mine = table
        .get(session)
        .and_then(|calls| calls.get(call_id))
        .map(|waiter| Arc::ptr_eq(&waiter.slot, &slot))
        .unwrap_or(false);
    let mut empty = false;
    if let Some(calls) = table.get_mut(session) {
        if mine {
            calls.remove(call_id);
        }
        empty = calls.is_empty();
    }
    if empty {
        table.remove(session);
    }
    outcome
}

fn clip(command: &str) -> String {
    if command.chars().count() <= COMMAND_CAP {
        return command.trim().to_string();
    }
    let mut out: String = command.chars().take(COMMAND_CAP).collect();
    out.push('…');
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    fn hit() -> Hit {
        Hit {
            id: "delete.rm".into(),
            group: "delete".into(),
            group_name: "文件删除".into(),
            label: "rm -r".into(),
            deny: false,
        }
    }

    #[test]
    fn an_answered_ask_returns_the_verdict() {
        let session = "s-ask-yes";
        let asker = thread::spawn(move || {
            ask(
                session,
                "call_1",
                "rm -rf build",
                &hit(),
                || false,
                ASK_TIMEOUT,
            )
        });
        for _ in 0..100 {
            if !pending("s-ask-yes").is_empty() {
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }
        assert!(answer("s-ask-yes", "call_1", Answer::AllowOnce));
        assert_eq!(
            asker.join().unwrap(),
            AskOutcome::Answered(Answer::AllowOnce)
        );
        assert!(pending("s-ask-yes").is_empty());
    }

    #[test]
    fn a_timeout_is_a_denial() {
        let outcome = ask(
            "s-ask-timeout",
            "call_1",
            "rm -rf build",
            &hit(),
            || false,
            Duration::from_millis(80),
        );
        assert_eq!(outcome, AskOutcome::TimedOut);
    }

    #[test]
    fn a_cancelled_turn_stops_waiting() {
        let outcome = ask(
            "s-ask-cancel",
            "call_1",
            "rm -rf build",
            &hit(),
            || true,
            ASK_TIMEOUT,
        );
        assert_eq!(outcome, AskOutcome::Cancelled);
    }

    #[test]
    fn answering_an_unknown_call_is_false_not_an_error() {
        assert!(!answer("s-nobody", "call_9", Answer::Deny));
    }

    #[test]
    fn session_rules_are_remembered_and_cleared() {
        assert!(!allows_rule("s-rule", "delete.rm"));
        allow_rule("s-rule", "delete.rm");
        assert!(allows_rule("s-rule", "delete.rm"));
        assert!(!allows_rule("s-other", "delete.rm"));
        clear_session("s-rule");
        assert!(!allows_rule("s-rule", "delete.rm"));
    }
}
