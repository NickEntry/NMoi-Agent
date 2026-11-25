//! 备份与备份校验（设计文档 §4.6）。
//!
//! "没人验证的备份是希望，不是备份"。所以备份写入后立刻在**备份文件上**跑
//! `integrity_check` 与 `foreign_key_check`——前者不检查外键，两个都要跑。
//!
//! 顺序是硬的（D48）：写 `.tmp` → 在 `.tmp` 上验证 → 过了才 rename 发布。
//! 验证不过就**不发布**，旧备份原样留着——一个验证不过的备份比没有备份更坏。

use std::path::{Path, PathBuf};
use std::time::Duration;

use rusqlite::{Connection, OpenFlags};

use crate::repo::StorageError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupReport {
    pub path: PathBuf,
    pub verified: bool,
    pub problems: Vec<String>,
}

/// 每一步搬多少页（D424）。一页默认 4 KiB，8192 页 = 32 MiB。
///
/// 原来是 64 页 + 每步睡 10 ms：那是照着 SQLite 文档里"不要长时间占着源库的读锁"
/// 的示例抄的。代价在真机上很直白——246 MB 的库要 **14.6 s**，其中绝大部分是睡掉的。
/// 实测同一份库、同一步骤改成 8192 页且不睡：**1.5 s**（9.5 倍）。
///
/// 为什么不再睡：源库是同一个进程里的连接，写它的人就是这一轮的用户动作；
/// 备份期间占着读锁最多几十毫秒（一步 32 MiB 的拷贝），比"用户盯着转圈 15 秒"划算。
/// 真出现"备份时界面卡住"，往回调这个数就行，它是一个常数。
const STEP_PAGES: i32 = 8192;

/// 用 SQLite Online Backup API 复制活动库到 `dest`，验证通过后才发布。
///
/// 目标文件已存在时会被**整体替换**，但替换发生在验证之后：中途被杀只会留下一个
/// `.tmp`，旧备份一点没动。
pub fn backup_to(conn: &Connection, dest: &Path) -> Result<BackupReport, StorageError> {
    if let Some(parent) = dest.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let tmp = crate::atomic::tmp_path(dest);
    // 上次写到一半的残骸先清掉，不续写（连旁路文件一起：一个孤零零的 `.tmp-wal`
    // 会在下一次备份时被当成"有未合并 WAL"，也会让用户在工作区目录里看不懂）
    crate::atomic::remove_with_sidecars(&tmp);

    let written = (|| -> Result<(), StorageError> {
        let mut target = Connection::open(&tmp)?;
        // 副本是临时文件：它自己的 WAL 与 fsync 在这里纯属浪费——发布动作是 rename，
        // 发布之前还会重新打开它读一遍做验证。实测这一步又省掉一半时间。
        target.pragma_update(None, "journal_mode", "OFF")?;
        target.pragma_update(None, "synchronous", "OFF")?;
        let backup = rusqlite::backup::Backup::new(conn, &mut target)?;
        backup.run_to_completion(STEP_PAGES, Duration::ZERO, None)?;
        Ok(())
    })();
    if let Err(error) = written {
        crate::atomic::remove_with_sidecars(&tmp);
        return Err(error);
    }

    let problems = match verify(&tmp) {
        Ok(problems) => problems,
        Err(error) => {
            crate::atomic::remove_with_sidecars(&tmp);
            return Err(error);
        }
    };
    if !problems.is_empty() {
        // 验证不过：不发布。旧备份还在，用户至少没被"看起来有备份"骗到。
        crate::atomic::remove_with_sidecars(&tmp);
        return Ok(BackupReport {
            path: dest.to_path_buf(),
            verified: false,
            problems,
        });
    }

    crate::atomic::publish(&tmp, dest)?;
    // 发布之后两类旁路文件都要清：
    //   `dest-wal` —— 旧备份遗留的，留着会让新库被当成"有未合并 WAL"；
    //   `dest.tmp-wal` —— 临时文件自己的，rename 只搬主文件，它会顶着一个
    //   已经不存在的主文件名留在原地（FFI 的 C 侧验收在列目录时抓到的）。
    crate::atomic::remove_sidecars(dest);
    crate::atomic::remove_sidecars(&tmp);

    Ok(BackupReport {
        path: dest.to_path_buf(),
        verified: true,
        problems: Vec::new(),
    })
}

/// 在副本上跑完整性与外键检查。
pub fn verify(path: &Path) -> Result<Vec<String>, StorageError> {
    let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut problems = Vec::new();

    let mut stmt = conn.prepare("PRAGMA integrity_check")?;
    let results: Vec<String> = stmt
        .query_map([], |row| row.get(0))?
        .collect::<Result<_, _>>()?;
    if results.len() != 1 || results[0] != "ok" {
        problems.extend(results.into_iter().take(20));
    }

    let mut stmt = conn.prepare("PRAGMA foreign_key_check")?;
    let fk: Vec<(String, i64, String, i64)> = stmt
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })?
        .collect::<Result<_, _>>()?;
    for (table, rowid, parent, _) in fk {
        problems.push(format!(
            "外键损坏：{table} rowid={rowid} 指向不存在的 {parent}"
        ));
    }

    Ok(problems)
}
