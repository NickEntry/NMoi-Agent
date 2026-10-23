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
