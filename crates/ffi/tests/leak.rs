//! 内存与资源回归（0.16c / D319）。
//!
//! 两件事，各一条判据：
//!
//! 1. **每条 `*_json` 都必须被 `nmoi_string_free` 归还。** 这里装一个计数型分配器，
//!    量"还活着多少字节"。正常往返 N 次之后这个数该回到原处；漏 free 就会台阶式上涨。
//! 2. **打开 / 关闭要成对。** 句柄表是 `Mutex<BTreeMap>`，漏一格不会崩，只会"用着变慢"。
//!    判据是表长回到原处——这个数能直接断言，比盯着内存曲线猜靠谱。
//!
//! 为什么还要一条"故意漏"的用例：**没红过的绿，只说明它跑通了**。那一条先证明
//! 这个探针看得见泄漏（不还的指针确实让数字涨上去），上面那两条绿才有意义。

use std::alloc::{GlobalAlloc, Layout, System};
use std::ffi::{c_char, CStr, CString};
use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::{Mutex, MutexGuard};

struct Counting;

static LIVE: AtomicIsize = AtomicIsize::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = System.alloc(layout);
        if !pointer.is_null() {
            LIVE.fetch_add(layout.size() as isize, Ordering::Relaxed);
        }
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        System.dealloc(pointer, layout);
        LIVE.fetch_sub(layout.size() as isize, Ordering::Relaxed);
    }
}

#[global_allocator]
static ALLOCATOR: Counting = Counting;

fn live() -> isize {
    LIVE.load(Ordering::Relaxed)
}

/// 这四条共用一份计数器，必须一条一条跑。
///
/// 判据是"跑之前 / 跑之后"的差，两条同时跑就会把对方的分配算进来——那种红是假的，
/// 而假红最贵：下次真红的时候没人信。
fn serial() -> MutexGuard<'static, ()> {
    static GATE: Mutex<()> = Mutex::new(());
    GATE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 调一次 `nmoi_estimate_tokens_json`；`free` 决定还不还——控制组靠它。
fn call_once(text: &CStr, free: bool) -> *mut c_char {
    let pointer = nmoi_ffi::text::nmoi_estimate_tokens_json(text.as_ptr());
    assert!(!pointer.is_null(), "返回了空指针");
    if free {
        unsafe { nmoi_ffi::nmoi_string_free(pointer) };
        std::ptr::null_mut()
    } else {
        pointer
    }
}

#[test]
fn json_round_trip_returns_to_the_same_footprint() {
    let _serial = serial();
    let text = CString::new("一段用来量 token 的中文，长度中等，跑两千次。").unwrap();
    // 先跑一轮热起来：分配器第一次会拿页，那一截不该算在任何人头上。
    for _ in 0..200 {
        call_once(&text, true);
    }
    let before = live();
    for _ in 0..2_000 {
        call_once(&text, true);
    }
    let grew = live() - before;
    assert!(
        grew < 64 * 1024,
        "往返两千次之后还多着 {grew} 字节：有东西没被 nmoi_string_free 归还"
    );
}

/// 控制组：不归还的那一条**必须**让数字涨上去。它绿了，上面那条绿才说明问题。
#[test]
fn the_probe_sees_a_leak() {
    let _serial = serial();
    let text = CString::new("故意漏掉的这一条").unwrap();
    for _ in 0..200 {
        call_once(&text, true);
    }
    let before = live();
    let mut leaked = Vec::new();
    for _ in 0..500 {
        leaked.push(call_once(&text, false));
    }
    let grew = live() - before;
    // 每次回的信封至少几十字节，五百次至少几十 KB。门槛按"看得见"来，不按精确值。
    assert!(
        grew > 8 * 1024,
        "漏了五百条却只涨 {grew} 字节：探针看不见泄漏，后面那几条绿不算证据"
    );
    for pointer in leaked {
        unsafe { nmoi_ffi::nmoi_string_free(pointer) };
    }
}

#[test]
fn storage_handles_are_opened_and_closed_in_pairs() {
    let _serial = serial();
    let dir = tempfile::tempdir().unwrap();
    let database = dir.path().join("leak.db");
    let path = CString::new(database.to_str().unwrap()).unwrap();
    let baseline = nmoi_ffi::handle_count();

    for _ in 0..20 {
        let opened = nmoi_ffi::storage::nmoi_storage_open_json(path.as_ptr());
        assert!(!opened.is_null());
        let text = unsafe { CStr::from_ptr(opened) }
            .to_string_lossy()
            .into_owned();
        unsafe { nmoi_ffi::nmoi_string_free(opened) };
        let envelope: serde_json::Value = serde_json::from_str(&text).unwrap();
        let handle = envelope
            .get("handle")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or_else(|| panic!("打开失败：{text}"));

        let closed = nmoi_ffi::storage::nmoi_storage_close_json(handle);
        unsafe { nmoi_ffi::nmoi_string_free(closed) };
    }

    assert_eq!(
        nmoi_ffi::handle_count(),
        baseline,
        "开了二十次关了二十次，句柄表却没回到原处：有一格没被拿走"
    );
}

/// 关一个不存在的句柄是**幂等**的（D54 定的）：返回体说 `closed:false`，不报错、不动表。
///
/// 这条是给上面那条兜底：如果 close 对未知 id 也"成功"，那"表长回到原处"就可能是
/// 别的东西补上了窟窿，而不是真的配对。
#[test]
fn closing_an_unknown_handle_changes_nothing() {
    let _serial = serial();
    let baseline = nmoi_ffi::handle_count();
    let closed = nmoi_ffi::storage::nmoi_storage_close_json(u64::MAX);
    let text = unsafe { CStr::from_ptr(closed) }
        .to_string_lossy()
        .into_owned();
    unsafe { nmoi_ffi::nmoi_string_free(closed) };
    let envelope: serde_json::Value = serde_json::from_str(&text).unwrap();
    assert_eq!(
        envelope.get("ok").and_then(serde_json::Value::as_bool),
        Some(true),
        "{text}"
    );
    assert_eq!(
        envelope.get("closed").and_then(serde_json::Value::as_bool),
        Some(false),
        "关一个不存在的句柄，回执要如实说没关：{text}"
    );
    assert_eq!(nmoi_ffi::handle_count(), baseline, "不该动句柄表");
}
