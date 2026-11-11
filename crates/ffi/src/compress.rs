//! 上下文压缩的 C ABI（D305）。
//!
//! 两步，故意的：
//!
//! - `nmoi_compress_plan_json`：只读库、不调模型。告诉界面"会移走哪一段、留多少"。
//! - `nmoi_compress_run_json`：真的跑（同步阻塞几十秒）。压完把摘要与归档数交回去。
//!
//! 分成两步是因为压缩**永久移走一段历史**：让用户在"开始"之前看见切点与规模，比给他一个
//! 点下去就消失的按钮诚实。界面那边在 plan 之后弹一层确认，run 在 IO 线程上跑。

use std::ffi::c_char;
use std::str::FromStr;

use nmoi_core::compress::runner::{drive, LoopConfig};
use nmoi_core::compress::{CompressionJob, CompressionLimits};
use nmoi_core::engine::now_ms;
use nmoi_core::ids::SessionId;
use nmoi_core::message::AtomicUnit;
use nmoi_core::provider::ProviderConfig;
use nmoi_core::runtime::ChatRequest;
use nmoi_storage::Storage;
use serde_json::{json, Value};

use crate::handles::storage_path;
use crate::json::{read_str, run_json, FfiError};

/// 窗口里默认留多少 token 的原文不动。
const DEFAULT_KEEP_RECENT: u32 = 8_192;

fn root_of(text: &str) -> Result<Value, FfiError> {
    serde_json::from_str(text)
        .map_err(|error| FfiError::new("bad_argument", format!("配置不是 JSON：{error}")))
}

fn session_of(root: &Value) -> Result<SessionId, FfiError> {
    let text = root.get("session").and_then(Value::as_str).unwrap_or("");
    SessionId::from_str(text)
        .map_err(|error| FfiError::new("bad_argument", format!("`session` 不是会话 id：{error}")))
}

fn storage_of(root: &Value) -> Result<Storage, FfiError> {
    let id = root
        .get("storage")
        .and_then(Value::as_u64)
        .ok_or_else(|| FfiError::new("bad_argument", "缺少 `storage` 句柄"))?;
    Storage::attach(storage_path(id)?).map_err(|error| FfiError::new("storage", error.to_string()))
}

fn keep_recent(root: &Value) -> u32 {
    root.get("keep_recent")
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .unwrap_or(DEFAULT_KEEP_RECENT as u64) as u32
}

fn provider_of(root: &Value) -> Result<ProviderConfig, FfiError> {
    let row = root
        .get("provider")
        .ok_or_else(|| FfiError::new("bad_argument", "缺少 `provider`"))?;
    let id = row.get("id").and_then(Value::as_str).unwrap_or("compress");
    let kind_text = row
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("openai_compatible");
    let kind = crate::turn::parse_provider_kind(kind_text)?;
    let base = row.get("base_url").and_then(Value::as_str).unwrap_or("");
    if base.trim().is_empty() {
        return Err(FfiError::new(
            "bad_argument",
            "`provider.base_url` 是空的：压缩要真的调一次模型",
        ));
    }
    Ok(ProviderConfig::new(id, kind, base))
}

fn build_job(
    root: &Value,
    units: &[AtomicUnit],
    session: SessionId,
) -> Result<CompressionJob, FfiError> {
    CompressionJob::start(
        session,
        units,
        keep_recent(root),
        now_ms(),
        CompressionLimits::default(),
    )
    .map_err(|error| FfiError::new("compress", error.to_string()))
}

/// 只算切点：调它不会动任何数据。
#[no_mangle]
pub extern "C" fn nmoi_compress_plan_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let root = root_of(text)?;
        let session = session_of(&root)?;
        let storage = storage_of(&root)?;
        let units = storage
            .load_units(session)
            .map_err(|error| FfiError::new("storage", error.to_string()))?;
        if units.is_empty() {
            return Err(FfiError::new("compress", "这一条会话还没有内容可压"));
        }
        let job = build_job(&root, &units, session)?;
        Ok(json!({
            "session": session.to_string(),
            "cut_after_seq": job.cut_after_seq(),
            "above_units": job.above().len(),
            "total_units": units.len(),
            "keep_recent": keep_recent(&root),
            "max_rounds": job.max_rounds(),
        }))
    })
}

/// 真的压一轮：模型写草稿 → 校验 → 归档 + 分界线 + 摘要，一个事务写进去。
///
/// 同步阻塞。模型没交（往返用尽 / 草稿不合格）时按 `fallback` 决定要不要走确定性兜底：
/// 默认 **开**（用户点的是"压缩"，不是"试着压缩"），返回体里 `forced` 说清是哪条路。
#[no_mangle]
pub extern "C" fn nmoi_compress_run_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let root = root_of(text)?;
        let session = session_of(&root)?;
        let model = root.get("model").and_then(Value::as_str).unwrap_or("");
        if model.trim().is_empty() {
            return Err(FfiError::new(
                "bad_argument",
                "缺少 `model`：压缩要指定用哪个模型",
            ));
        }
        let provider = provider_of(&root)?;
        let mut request = ChatRequest::new(provider, model.trim());
        if let Some(key) = root.get("api_key").and_then(Value::as_str) {
            if !key.trim().is_empty() {
                request = request.api_key(key);
            }
        }

        let mut storage = storage_of(&root)?;
        let units = storage
            .load_units(session)
            .map_err(|error| FfiError::new("storage", error.to_string()))?;
        if units.is_empty() {
            return Err(FfiError::new("compress", "这一条会话还没有内容可压"));
        }
        let mut job = build_job(&root, &units, session)?;
        let cut_after_seq = job.cut_after_seq();
        let above_units = job.above().len();

        let client = crate::proxy::client();
        let loop_config = LoopConfig {
            max_exchanges: root
                .get("max_exchanges")
                .and_then(Value::as_u64)
                .filter(|value| *value > 0)
                .unwrap_or(LoopConfig::default().max_exchanges as u64)
                as u32,
            system: system_of(&root),
            ..LoopConfig::default()
        };
        let started = now_ms();
        let driven = drive(
            &client,
            &request,
            &mut job,
            &mut storage,
            &loop_config,
            started,
        );
        let (report, drive_error) = match driven {
            Ok(report) => (Some(report), None),
            Err(error) => (None, Some(error.to_string())),
        };

        let mut outcome = report.as_ref().and_then(|report| report.outcome.clone());
        let mut forced_by_fallback = false;
        let allow_fallback = root
            .get("fallback")
            .and_then(Value::as_bool)
            .unwrap_or(true);
        if outcome.is_none() && allow_fallback {
            match job.commit_fallback(&mut storage, now_ms()) {
                Ok(fallback) => {
                    forced_by_fallback = true;
                    outcome = Some(fallback);
                }
                Err(error) => {
                    return Err(FfiError::new(
                        "compress",
                        format!(
                            "模型没交出可用摘要（{}），兜底也没成：{error}",
                            drive_error.clone().unwrap_or_else(|| "往返用尽".into())
                        ),
                    ));
                }
            }
        }
        let outcome = outcome.ok_or_else(|| {
            FfiError::new(
                "compress",
                format!(
                    "模型没交出可用摘要，且这一轮不准兜底（fallback=false）：{}",
                    drive_error.clone().unwrap_or_else(|| "往返用尽".into())
                ),
            )
        })?;

        Ok(json!({
            "session": session.to_string(),
            "cut_after_seq": cut_after_seq,
            "above_units": above_units,
            "keep_recent": keep_recent(&root),
            "summary": outcome.injected,
            // 摘要正文之外的摘录块也算在 `injected` 里：界面显示的就是将来进上下文的那一份。
            "summary_chars": outcome.summary.chars().count(),
            "archived": outcome.archived.len(),
            "archived_tokens": outcome.archived_tokens,
            "draft_tokens": outcome.trace.draft_tokens,
            "forced": outcome.forced || forced_by_fallback,
            "coverage": outcome.coverage.summary(),
            "exchanges": report.as_ref().map(|report| report.exchanges).unwrap_or(0),
            "tool_calls": report.as_ref().map(|report| report.tool_calls).unwrap_or(0),
            "rejections": report.as_ref().map(|report| report.rejections).unwrap_or(0),
            "errors": report
                .as_ref()
                .map(|report| report.errors.clone())
                .unwrap_or_else(|| drive_error.into_iter().collect()),
            "state": format!("{:?}", job.state()),
            "elapsed_ms": now_ms().saturating_sub(started),
        }))
    })
}

/// 这个角色自己的提示词（D318）：拼在核心脚手架之后。空串 / 全空白当没有。
///
/// 单独一个函数是为了能单独断言：这一行是"界面上写了字"到"模型真的看见"之间唯一的
/// 那道门，而它错了不会有任何报错——请求照样成功，只是那段话没进去。
fn system_of(root: &Value) -> Option<String> {
    root.get("system")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn role_system_is_read_and_blank_is_dropped() {
        let with = json!({ "system": "  第一人称写摘要。 " });
        assert_eq!(system_of(&with).as_deref(), Some("第一人称写摘要。"));
        for without in [
            json!({}),
            json!({ "system": "" }),
            json!({ "system": "   " }),
            json!({ "system": 3 }),
        ] {
            assert_eq!(
                system_of(&without),
                None,
                "不该造出一个空 system：{without}"
            );
        }
    }
}
