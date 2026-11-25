//! 7z 打包的 C ABI（D425）。
//!
//! 界面上的"导出"只有一个动作：把选中的那些文件打成一个包。格式定成 7z、压缩等级定成
//! 标准档（`-mx5` 的等价参数），这两条都在 `nmoi_core::sevenz` 里，这一层只负责
//! **把参数变成路径、把路径变成读者**。
//!
//! # 为什么不让 Kotlin 自己压
//!
//! 压缩器要跟解压器对得上：`nmoi_core::sevenz` 是拿 `7z` 命令行逐字节验过的
//! （`scripts/check_sevenz.sh`）。Kotlin 侧再写一份，两份判据迟早分叉——
//! 那时用户拿到的包"有时能开、有时不能开"，而那种故障最难查。

use std::ffi::c_char;
use std::io::Write;

use serde_json::{json, Value};

use crate::json::{read_str, run_json, FfiError};

fn pack_error(error: impl std::fmt::Display) -> FfiError {
    FfiError::new("sevenz", error.to_string())
}

/// 把一组文件打成一个 7z。
///
/// 入参：`{ "dest": "/…/out.7z", "entries": [ {"name": "a/b.txt", "path": "/…/b.txt"} ] }`
///
/// - `name` 是**包内路径**，由调用方从盘上路径算出来（去掉那个共同前缀）。
///   它同时是安全检查的对象：`..`、绝对路径、控制字符一律拒绝。
/// - `path` 是盘上的绝对路径。读不出来就整包失败——**不跳过**：
///   用户勾了五个文件、包里只有四个，那种事他事后才发现。
///
/// 返回：`{ dest, entries, bytes_in, bytes_out }`。`bytes_out` 是包的大小。
///
/// 写的时候先写 `<dest>.tmp`，成功才 rename：中途被杀只会留下一个 `.tmp`，
/// 目标位置那份旧包一点没动（与备份同一条规矩）。
#[no_mangle]
pub extern "C" fn nmoi_sevenz_write_json(spec: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(spec, "spec")?;
        let value: Value = serde_json::from_str(text)
            .map_err(|error| FfiError::new("bad_argument", format!("spec 不是 JSON：{error}")))?;
        let dest = value
            .get("dest")
            .and_then(Value::as_str)
            .ok_or_else(|| FfiError::new("bad_argument", "缺 dest"))?;
        let list = value
            .get("entries")
            .and_then(Value::as_array)
            .ok_or_else(|| FfiError::new("bad_argument", "缺 entries"))?;
        if list.is_empty() {
            return Err(FfiError::new("bad_argument", "没有要打包的文件"));
        }

        let dest = std::path::PathBuf::from(dest);
        if let Some(parent) = dest.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(pack_error)?;
            }
        }
        let tmp = nmoi_storage::atomic::tmp_path(&dest);
        let _ = std::fs::remove_file(&tmp);

        // 先把读者全建起来：有一条打不开就整包失败，别写到一半才发现。
        let mut readers: Vec<Box<dyn std::io::Read>> = Vec::with_capacity(list.len());
        let mut names: Vec<String> = Vec::with_capacity(list.len());
        for item in list {
            let name = item
                .get("name")
                .and_then(Value::as_str)
                .ok_or_else(|| FfiError::new("bad_argument", "entries 里有一条缺 name"))?;
            let path = item
                .get("path")
                .and_then(Value::as_str)
                .ok_or_else(|| FfiError::new("bad_argument", "entries 里有一条缺 path"))?;
            let file = std::fs::File::open(path)
                .map_err(|error| pack_error(format!("打不开 {path}：{error}")))?;
            names.push(name.to_string());
            readers.push(Box::new(std::io::BufReader::new(file)));
        }

        let written = (|| -> Result<(u64, u64), FfiError> {
            let file = std::fs::File::create(&tmp).map_err(pack_error)?;
            let mut out = std::io::BufWriter::new(file);
            let mut plan: Vec<nmoi_core::sevenz::StreamEntry<'_>> = Vec::with_capacity(names.len());
            for (index, name) in names.iter().enumerate() {
                // 一条条搬：`Box<dyn Read>` 不能同时借两次，而 `StreamEntry` 要持有它。
                // 这里换个形状——把 reader 挪进 plan 之后，原来的槽位填一个空读者。
                plan.push(nmoi_core::sevenz::StreamEntry {
                    name: name.clone(),
                    reader: std::mem::replace(&mut readers[index], Box::new(std::io::empty())),
                });
            }
            let report = nmoi_core::sevenz::write_to(&mut out, &mut plan, &mut |_| {})
                .map_err(pack_error)?;
            let bytes_in: u64 = report.iter().map(|entry| entry.size).sum();
            out.flush().map_err(pack_error)?;
            Ok((bytes_in, report.len() as u64))
        })();

        let (bytes_in, entries) = match written {
            Ok(values) => values,
            Err(error) => {
                let _ = std::fs::remove_file(&tmp);
                return Err(error);
            }
        };
        // 包多大要现读：`write_to` 写的是"几条、每条多大"，不回头量文件。
        let bytes_out = std::fs::metadata(&tmp).map(|meta| meta.len()).unwrap_or(0);
        nmoi_storage::atomic::publish(&tmp, &dest).map_err(pack_error)?;

        Ok(json!({
            "dest": dest.display().to_string(),
            "entries": entries,
            "bytes_in": bytes_in,
            "bytes_out": bytes_out,
        }))
    })
}
