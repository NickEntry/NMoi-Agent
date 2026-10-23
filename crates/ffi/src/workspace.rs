//! 工作区面的 C ABI：读 / 写 / 改 / 列目录 / stat。
//!
//! # 这一层**不**过能力门控
//!
//! `AssistantPolicy` 管的是"模型能做什么"。用户自己点"打开这个文件夹"之后再读写文件，
//! 是用户本人的动作，不需要模型那套开关——把用户自己的手也锁上，只会得到一个
//! 需要反复解锁的 UI。模型只能走工具层（`nmoi_tool_invoke_json`），那里有门控。
//!
//! # 截断信息原样摊平
//!
//! `read` 会带上 `truncated` / `truncation.reason` / `remaining_lines` / `long_lines`，
//! 因为"这段内容不全"必须让 UI 说出来。悄悄给半截文件，是这类工具最常见的骗人方式（D14）。

use std::ffi::c_char;

use nmoi_core::workspace::{ListOptions, ReadOptions, TruncationReason, Workspace};
use serde_json::{json, Value};

use crate::handles::{insert_workspace, remove_workspace, with_workspace};
use crate::json::{read_str, run_json, FfiError};

fn workspace_error(error: impl std::fmt::Display) -> FfiError {
    FfiError::new("workspace", error.to_string())
}

fn truncation_reason(reason: TruncationReason) -> &'static str {
    match reason {
        TruncationReason::LineLimit => "line_limit",
        TruncationReason::ByteLimit => "byte_limit",
        TruncationReason::LongLine => "long_line",
        TruncationReason::FileTooLarge => "file_too_large",
    }
}

/// 绑定一个工作区根目录。目录必须已经存在（这里不替用户建目录——绑错路径要当场知道）。
#[no_mangle]
pub extern "C" fn nmoi_workspace_open_json(root: *const c_char) -> *mut c_char {
    run_json(|| {
        let root = read_str(root, "root")?;
        let workspace = Workspace::open(root).map_err(workspace_error)?;
        let path = workspace.root().display().to_string();
        let handle = insert_workspace(workspace);
        Ok(json!({ "handle": handle, "root": path }))
    })
}

/// 关掉工作区句柄。幂等。
#[no_mangle]
pub extern "C" fn nmoi_workspace_close_json(handle: u64) -> *mut c_char {
    run_json(|| Ok(json!({ "closed": remove_workspace(handle) })))
}

/// 读一个文件。
///
/// `start_line` 是 1-based，传 0 用默认（1）；`max_lines` 传 0 用默认上限；
/// `force_full != 0` 忽略行数/字节/单行/二进制上限（D14 的最高优先级开关）。
#[no_mangle]
pub extern "C" fn nmoi_workspace_read_json(
    handle: u64,
    rel: *const c_char,
    start_line: u64,
    max_lines: u64,
    force_full: i32,
) -> *mut c_char {
    run_json(|| {
        let rel = read_str(rel, "path")?;
        let mut options = ReadOptions::default();
        if start_line > 0 {
            options.start_line = start_line as usize;
        }
        if max_lines > 0 {
            options.max_lines = max_lines as usize;
        }
        options.force_full = force_full != 0;
        with_workspace(handle, |workspace| {
            let result = workspace
                .read_file(rel, &options)
                .map_err(workspace_error)?;
            let long_lines: Vec<Value> = result
                .long_lines
                .iter()
                .map(|line| {
                    json!({
                        "line": line.line,
                        "bytes": line.bytes,
                        "kept_bytes": line.kept_bytes,
                    })
                })
                .collect();
            let truncation = result.truncation.as_ref().map(|truncation| {
                json!({
                    "reason": truncation_reason(truncation.reason),
                    "remaining_lines": truncation.remaining_lines,
                    "remaining_bytes": truncation.remaining_bytes,
                })
            });
            Ok(json!({
                "path": result.path,
                "content": result.content,
                "total_lines": result.total_lines,
                "total_bytes": result.total_bytes,
                "returned_lines": result.returned_lines,
                "truncated": result.truncated,
                "truncation": truncation,
                "long_lines": long_lines,
                "lossy": result.lossy,
                "hint": result.hint,
            }))
        })
    })
}

/// 整篇写入（覆盖）。父目录会被创建。
#[no_mangle]
pub extern "C" fn nmoi_workspace_write_json(
    handle: u64,
    rel: *const c_char,
    content: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let rel = read_str(rel, "path")?;
        let content = read_str(content, "content")?;
        with_workspace(handle, |workspace| {
            let result = workspace
                .write_file(rel, content)
                .map_err(workspace_error)?;
            Ok(json!({ "path": result.path, "bytes": result.bytes }))
        })
    })
}

/// 精确串替换（`replace_all != 0` 时全换）。找不到 / 匹配不唯一都会失败，且**不动文件**。
#[no_mangle]
pub extern "C" fn nmoi_workspace_edit_json(
    handle: u64,
    rel: *const c_char,
    old: *const c_char,
    new: *const c_char,
    replace_all: i32,
) -> *mut c_char {
    run_json(|| {
        let rel = read_str(rel, "path")?;
        let old = read_str(old, "old")?;
        let new = read_str(new, "new")?;
        with_workspace(handle, |workspace| {
            let result = workspace
                .edit_file(rel, old, new, replace_all != 0)
                .map_err(workspace_error)?;
            Ok(json!({
                "path": result.path,
                "replacements": result.replacements,
                "bytes_before": result.bytes_before,
                "bytes_after": result.bytes_after,
            }))
        })
    })
}

/// 列目录。`depth` / `max_entries` 传 0 用默认；`include_hidden != 0` 带上隐藏项。
#[no_mangle]
pub extern "C" fn nmoi_workspace_list_json(
    handle: u64,
    rel: *const c_char,
    depth: u64,
    max_entries: u64,
    include_hidden: i32,
) -> *mut c_char {
    run_json(|| {
        let rel = read_str(rel, "path")?;
        let mut options = ListOptions::default();
        if depth > 0 {
            options.depth = depth as usize;
        }
        if max_entries > 0 {
            options.max_entries = max_entries as usize;
        }
        options.include_hidden = include_hidden != 0;
        with_workspace(handle, |workspace| {
            let result = workspace.list_dir(rel, &options).map_err(workspace_error)?;
            let entries: Vec<Value> = result
                .entries
                .iter()
                .map(|entry| {
                    json!({
                        "name": entry.name,
                        "path": entry.path,
                        "is_dir": entry.is_dir,
                        "is_symlink": entry.is_symlink,
                        "size_bytes": entry.size_bytes,
                    })
                })
                .collect();
            Ok(json!({
                "path": result.path,
                "entries": entries,
                "count": result.entries.len(),
            }))
        })
    })
}

/// 单个路径的元信息。
#[no_mangle]
pub extern "C" fn nmoi_workspace_stat_json(handle: u64, rel: *const c_char) -> *mut c_char {
    run_json(|| {
        let rel = read_str(rel, "path")?;
        with_workspace(handle, |workspace| {
            let stat = workspace.stat(rel).map_err(workspace_error)?;
            Ok(json!({
                "path": stat.path,
                "is_dir": stat.is_dir,
                "is_file": stat.is_file,
                "size_bytes": stat.size_bytes,
                "modified_ms": stat.modified_ms,
            }))
        })
    })
}
