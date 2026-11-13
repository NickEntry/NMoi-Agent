//! 工具面的 C ABI：声明表 + 直接调用。
//!
//! 两件事分开：`nmoi_tool_specs_json` 发请求前要裁多少（D50 的三层里最外面那层），
//! `nmoi_tool_invoke_json` 是**真的执行**——所以后者要工作区句柄 + 策略。
//!
//! # 失败也是结果
//!
//! 未知工具、坏 JSON、越界、匹配不唯一，全部是 `ok:true` + `status:"failed"`。
//! 这不是"我们宽容"，是工具层本来的语义：模型看到失败信息才知道换个办法，
//! 而 UI 看到 `status` 才知道该显示红字还是正常输出。`error` 只留给 ABI 级问题。

use std::ffi::c_char;

use nmoi_core::policy::{AssistantPolicy, WorkspaceBinding};
use nmoi_core::tools::{ToolContext, ToolOutcome, ToolRegistry};
use serde_json::{json, Value};

use crate::handles::with_workspace;
use crate::json::{read_str, run_json};

/// 策略只看两件事：有没有绑工作区、是不是只读。其余四类能力（容器 / 原生命令 /
/// 网络 / 凭据代理）在这一层**故意不给开关**：F1 里没有实现，给了开关就是骗人。
fn policy_for(workspace_handle: u64, read_only: i32) -> AssistantPolicy {
    if workspace_handle == 0 {
        return AssistantPolicy::locked_down();
    }
    let binding = if read_only != 0 {
        WorkspaceBinding::read_only("ffi-workspace")
    } else {
        WorkspaceBinding::read_write("ffi-workspace")
    };
    AssistantPolicy::locked_down().with_binding(binding)
}

fn outcome_json(outcome: ToolOutcome) -> Value {
    match outcome {
        ToolOutcome::Ok { text, truncated } => json!({
            "status": "ok",
            "text": text,
            "truncated": truncated,
            "bytes": text.len(),
        }),
        ToolOutcome::Denied { capability, reason } => json!({
            "status": "denied",
            "capability": capability.as_str(),
            "reason": reason.as_str(),
            "explain": reason.explain(),
        }),
        ToolOutcome::Failed { message } => json!({
            "status": "failed",
            "message": message,
        }),
    }
}

/// 全部工具名（不看策略）。
#[no_mangle]
pub extern "C" fn nmoi_tool_names_json() -> *mut c_char {
    run_json(|| {
        let registry = ToolRegistry::workspace_defaults();
        Ok(json!({ "names": registry.names(), "count": registry.len() }))
    })
}

/// 按策略裁过的工具声明表。发给模型的就是这一份。
///
/// `workspace_handle` 传 0 表示没绑工作区（这时候所有要盘的工具都不会出现）；
/// `read_only != 0` 只发读类工具。
#[no_mangle]
pub extern "C" fn nmoi_tool_specs_json(workspace_handle: u64, read_only: i32) -> *mut c_char {
    run_json(|| {
        let policy = policy_for(workspace_handle, read_only);
        let registry = ToolRegistry::workspace_defaults();
        let specs: Vec<Value> = registry
            .specs_for(&policy)
            .into_iter()
            .map(|spec| {
                json!({
                    "name": spec.name,
                    "description": spec.description,
                    "parameters": spec.parameters,
                })
            })
            .collect();
        let allowed: Vec<&str> = policy.allowed().iter().map(|cap| cap.as_str()).collect();
        Ok(json!({
            "specs": specs,
            "count": specs.len(),
            "allowed_capabilities": allowed,
            "workspace_bound": workspace_handle != 0,
        }))
    })
}

/// 关掉一条正在跑的工具调用（0.15ak / D246）。
///
/// 入参 `{"session": "...", "call_id": "..."}`，回 `{"cancelled": bool, "found": bool}`。
///
/// - `found:false` = 那条调用已经结束了。**不是错误**：用户点晚了一拍，
///   界面上该写"已经结束了"，不该弹一条红字。
/// - 只置标志，杀进程由等待循环那一侧做（超时与关闭在同一处，才分得清是谁停的）。
/// - 行数/条数这类闸不在这里加：一次能关几条由调用方决定。
#[no_mangle]
pub extern "C" fn nmoi_tool_cancel_json(input: *const c_char) -> *mut c_char {
    run_json(|| {
        let raw = read_str(input, "input")?;
        let value: Value = serde_json::from_str(raw)
            .map_err(|error| crate::json::FfiError::new("bad_json", error.to_string()))?;
        let session = value
            .get("session")
            .and_then(Value::as_str)
            .ok_or_else(|| crate::json::FfiError::new("bad_argument", "缺 session"))?;
        let call_id = value
            .get("call_id")
            .and_then(Value::as_str)
            .ok_or_else(|| crate::json::FfiError::new("bad_argument", "缺 call_id"))?;
        let found = nmoi_core::kills::cancel(session, call_id);
        Ok(json!({ "cancelled": found, "found": found }))
    })
}

/// 回答一条"命令要不要跑"的询问（D329）。
///
/// 入参 `{"session":"...","call_id":"...","verdict":"allow_once|allow_rule|deny"}`，
/// 回 `{"answered":bool,"verdict":"..."}`。
///
/// `answered:false` = 那条询问已经不在等了（超时了、被停了、或者用户点晚了一拍）。
/// **不是错误**：界面据此写"这条已经结束了"，不该弹红字。
#[no_mangle]
pub extern "C" fn nmoi_tool_answer_json(input: *const c_char) -> *mut c_char {
    run_json(|| {
        let raw = read_str(input, "input")?;
        let value: Value = serde_json::from_str(raw)
            .map_err(|error| crate::json::FfiError::new("bad_json", error.to_string()))?;
        let session = value
            .get("session")
            .and_then(Value::as_str)
            .ok_or_else(|| crate::json::FfiError::new("bad_argument", "缺 session"))?;
        let call_id = value
            .get("call_id")
            .and_then(Value::as_str)
            .ok_or_else(|| crate::json::FfiError::new("bad_argument", "缺 call_id"))?;
        let raw_verdict = value
            .get("verdict")
            .and_then(Value::as_str)
            .ok_or_else(|| crate::json::FfiError::new("bad_argument", "缺 verdict"))?;
        let verdict = nmoi_core::shellask::Answer::parse(raw_verdict).ok_or_else(|| {
            crate::json::FfiError::new(
                "bad_argument",
                format!("verdict 只认 allow_once / allow_rule / deny，拿到的是 {raw_verdict}"),
            )
        })?;
        let answered = nmoi_core::shellask::answer(session, call_id, verdict);
        Ok(json!({
            "answered": answered,
            "verdict": verdict.as_str(),
        }))
    })
}

/// 内置命令规则的清单：十组、每组的项、哪几项不能关（`deny`）。
///
/// 设置页拿它渲染分组开关；**拒绝项也在里面**——闸一旦隐形，
/// 撞上时用户看到的是"事情做了一半就停了"，而他什么都没做错。
#[no_mangle]
pub extern "C" fn nmoi_shell_rules_json() -> *mut c_char {
    run_json(|| Ok(nmoi_core::shellguard::Rules::builtin().catalog()))
}

/// 直接调一个工具。
///
/// `arguments` 是**工具自己的 JSON**（比如 `{"path":"a.txt"}`），原样交给工具层。
/// `workspace_handle` 传 0 时工具层会因为没有工作区而被门控挡住——那正是我们要的答案，
/// 不需要在 FFI 里提前判断。
#[no_mangle]
pub extern "C" fn nmoi_tool_invoke_json(
    workspace_handle: u64,
    name: *const c_char,
    arguments: *const c_char,
) -> *mut c_char {
    run_json(|| {
        let name = read_str(name, "name")?;
        let arguments = read_str(arguments, "arguments")?;
        let policy = policy_for(workspace_handle, 0);
        let registry = ToolRegistry::workspace_defaults();
        let outcome = if workspace_handle == 0 {
            let context = ToolContext::new(&policy, None);
            outcome_json(registry.invoke(name, arguments, &context))
        } else {
            with_workspace(workspace_handle, |workspace| {
                let context = ToolContext::new(&policy, Some(workspace));
                Ok(outcome_json(registry.invoke(name, arguments, &context)))
            })?
        };
        Ok(outcome)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::{CStr, CString};

    fn read(pointer: *mut c_char) -> Value {
        assert!(!pointer.is_null(), "返回数据的函数永不返回 null");
        // SAFETY: 这些指针来自 `run_json` 的 `CString::into_raw`，读一次再还回去。
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .into_owned();
        unsafe { drop(CString::from_raw(pointer)) };
        serde_json::from_str(&text).expect("必须是合法 JSON")
    }

    /// 关一条正在跑的调用：`found:true` + `cancelled:true`，且标志真的置上了。
    #[test]
    fn cancelling_a_running_call_sets_its_flag() {
        let handle = nmoi_core::kills::begin("s-ffi-cancel", "call_1", "System-Shell", "");
        let config = CString::new(r#"{"session":"s-ffi-cancel","call_id":"call_1"}"#).unwrap();
        let value = read(nmoi_tool_cancel_json(config.as_ptr()));
        assert_eq!(value["ok"], json!(true), "{value}");
        assert_eq!(value["found"], json!(true), "{value}");
        assert_eq!(value["cancelled"], json!(true), "{value}");
        assert!(handle.cancelled(), "标志要真的置上，不能只回一句 true");
    }

    /// 没在跑：`found:false`，**仍然是 `ok:true`**。用户点晚了一拍不是错误。
    #[test]
    fn cancelling_something_that_already_finished_is_not_an_error() {
        let config = CString::new(r#"{"session":"s-ffi-nobody","call_id":"call_9"}"#).unwrap();
        let value = read(nmoi_tool_cancel_json(config.as_ptr()));
        assert_eq!(value["ok"], json!(true), "{value}");
        assert_eq!(value["found"], json!(false), "{value}");
        assert_eq!(value["cancelled"], json!(false), "{value}");
    }

    #[test]
    fn a_missing_call_id_is_named() {
        let config = CString::new(r#"{"session":"s-ffi-nobody"}"#).unwrap();
        let value = read(nmoi_tool_cancel_json(config.as_ptr()));
        assert_eq!(value["ok"], json!(false), "{value}");
        assert!(
            value["error"]["message"]
                .as_str()
                .unwrap()
                .contains("call_id"),
            "报错要点名缺的是哪一格：{value}"
        );
    }
}
