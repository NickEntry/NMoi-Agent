//! 原子写文件（P10 / D45）：先写 `.tmp` → fsync → rename → fsync 目录。
//!
//! 为什么不能"先删旧文件再写新的"：进程可能死在中间，那时旧文件没了、新文件是半截。
//! 备份是最不能出这种事的地方——用户以为有备份，实际是一个 0 字节文件。
//!
//! 三条规矩：
//!
//! 1. **原地不覆盖**。所有写入都落在 `<name>.tmp`，只有全部写完并验证通过才 rename。
//! 2. **rename 是发布动作**。同一文件系统上 rename 是原子的：要么旧文件、要么新文件，
//!    不存在"半个"的中间态。
//! 3. **残留 `.tmp` 一律当垃圾**。上一次写到一半被杀留下的临时文件，写之前先删掉；
//!    它从来不是数据，删它不会丢任何东西。

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::repo::StorageError;

/// 临时文件后缀。`.tmp` 结尾的东西**永远不是数据**。
pub const TMP_SUFFIX: &str = ".tmp";

/// `<path>.tmp`。
pub fn tmp_path(path: &Path) -> PathBuf {
    let mut os = path.as_os_str().to_os_string();
    os.push(TMP_SUFFIX);
    PathBuf::from(os)
}

/// 原子写：`write` 只负责把内容写进临时文件，剩下的交给这里。
///
/// 任何一步失败都会删掉临时文件并把错误原样返回；**目标文件保持原样**。
pub fn write_atomic<F>(path: &Path, write: F) -> Result<(), StorageError>
where
    F: FnOnce(&Path) -> Result<(), StorageError>,
{
    let tmp = tmp_path(path);
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    // 上一次写到一半留下的残骸：删掉再写，不用它续写
    let _ = std::fs::remove_file(&tmp);

    let result = write(&tmp)
        .and_then(|()| sync_file(&tmp))
        .and_then(|()| std::fs::rename(&tmp, path).map_err(StorageError::Io))
        .and_then(|()| sync_dir(path));
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

/// 把一个已经写好的文件原子地"发布"到 `path`（内容由调用方自己写进 `.tmp`）。
///
/// 与 [`write_atomic`] 的区别：这个给"内容已经落在临时文件里"的场合用，
/// 比如 SQLite 备份 API 直接写临时文件、验证完之后再发布。
pub fn publish(tmp: &Path, path: &Path) -> Result<(), StorageError> {
    sync_file(tmp)?;
    std::fs::rename(tmp, path).map_err(StorageError::Io)?;
    sync_dir(path)
}

/// fsync 一个文件。数据必须真的落到磁盘上，rename 才有意义。
///
/// 用**可写**句柄打开：Windows 的 `FlushFileBuffers` 要求句柄带写权限，
/// 拿只读句柄 fsync 会直接回 `ERROR_ACCESS_DENIED`（实测 code 5，CI 的 windows job 抓到的）。
/// Unix 上只读 fd 也能 fsync，所以这个坑在本地一点看不出来。
pub fn sync_file(path: &Path) -> Result<(), StorageError> {
    let file = OpenOptions::new().write(true).open(path)?;
    file.sync_all()?;
    Ok(())
}

/// fsync 目录：让 rename 这个目录项变更本身也落盘。
///
/// 失败**不算错**：Windows 打不开目录句柄，某些文件系统不支持对目录 fsync。
/// 这种情况下 rename 仍然是原子的，只是"rename 之后立刻断电"的那一瞬不保证。
fn sync_dir(path: &Path) -> Result<(), StorageError> {
    let Some(parent) = path.parent() else {
        return Ok(());
    };
    let dir = if parent.as_os_str().is_empty() {
        Path::new(".")
    } else {
        parent
    };
    match File::open(dir) {
        Ok(handle) => {
            let _ = handle.sync_all();
            Ok(())
        }
        Err(_) => Ok(()),
    }
}

/// 小文件的便捷写入：一次写完 + fsync + rename。给导出（Markdown / 分享）用。
pub fn write_bytes(path: &Path, bytes: &[u8]) -> Result<(), StorageError> {
    write_atomic(path, |tmp| {
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(tmp)?;
        file.write_all(bytes)?;
        file.flush()?;
        Ok(())
    })
}

/// 清掉某个位置残留的 `.tmp`（启动时可调，也用于"用户看到脏文件"之后的手动清理）。
pub fn sweep_tmp(path: &Path) -> bool {
    remove_with_sidecars(&tmp_path(path))
}

/// 删掉 `path` 的 SQLite 旁路文件（`-wal` / `-shm` / `-journal`），主文件不动。
///
/// 为什么需要单独一个：备份时 SQLite 会在临时文件旁边留下 `<name>.tmp-wal` 这种文件，
/// rename 只搬主文件，旁路文件会顶着一个已经不存在的主文件名留在原地。
/// 对用户来说那是解释不清的垃圾；对下次备份来说它是"上一个残骸"。
pub fn remove_sidecars(path: &Path) {
    for suffix in ["-wal", "-shm", "-journal"] {
        let mut side = path.as_os_str().to_os_string();
        side.push(suffix);
        let _ = std::fs::remove_file(PathBuf::from(side));
    }
}

/// 删主文件 + 它的旁路文件。返回主文件是否真的被删掉。
pub fn remove_with_sidecars(path: &Path) -> bool {
    let removed = std::fs::remove_file(path).is_ok();
    remove_sidecars(path);
    removed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_bytes_and_leaves_no_tmp() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.md");
        write_bytes(&path, "第一版".as_bytes()).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "第一版");
        assert!(!tmp_path(&path).exists(), "临时文件不该留下");
    }

    #[test]
    fn replaces_existing_file_only_at_rename() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.md");
        std::fs::write(&path, "旧内容").unwrap();

        // 写失败：目标文件必须还是旧的
        let err = write_atomic(&path, |_tmp| {
            Err(StorageError::Inconsistent("模拟写到一半失败".into()))
        });
        assert!(err.is_err());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "旧内容");
        assert!(!tmp_path(&path).exists());

        // 写成功：内容被整体替换
        write_bytes(&path, "新内容".as_bytes()).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "新内容");
    }

    #[test]
    fn stale_tmp_from_a_crash_is_discarded() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.md");
        let tmp = tmp_path(&path);
        std::fs::write(&tmp, "上次杀进程留下的半截").unwrap();

        write_bytes(&path, "完整内容".as_bytes()).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "完整内容");
        assert!(!tmp.exists(), "残骸该被覆盖掉");
    }

    #[test]
    fn publish_moves_the_staged_file_into_place() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("app.db");
        let tmp = tmp_path(&path);
        std::fs::write(&tmp, "内容".as_bytes()).unwrap();
        std::fs::write(&path, "旧".as_bytes()).unwrap();

        publish(&tmp, &path).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "内容");
        assert!(!tmp.exists());
    }

    #[test]
    fn sweep_reports_whether_something_was_removed() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("x.txt");
        assert!(!sweep_tmp(&path));
        std::fs::write(tmp_path(&path), "x".as_bytes()).unwrap();
        assert!(sweep_tmp(&path));
        assert!(!tmp_path(&path).exists());
    }

    /// 旁路文件必须跟着主文件一起清。留一个孤零零的 `-wal`，下次备份会把它当成
    /// "有未合并 WAL"，用户在工作区目录里也看不懂那是什么。
    #[test]
    fn remove_with_sidecars_takes_the_wal_and_shm_with_it() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("app.db");
        let tmp = tmp_path(&path);
        for candidate in [&path, &tmp] {
            std::fs::write(candidate, "主文件").unwrap();
            for suffix in ["-wal", "-shm", "-journal"] {
                std::fs::write(format!("{}{suffix}", candidate.display()), "旁路").unwrap();
            }
        }

        assert!(remove_with_sidecars(&tmp));
        assert!(!tmp.exists());
        for suffix in ["-wal", "-shm", "-journal"] {
            let side = format!("{}{suffix}", tmp.display());
            assert!(!Path::new(&side).exists(), "{side} 应该被清掉");
        }

        // 主文件自己的旁路清理不能把主文件干掉：备份发布之后要用它。
        remove_sidecars(&path);
        assert!(path.exists(), "remove_sidecars 只清旁路，不动主文件");
        assert!(!Path::new(&format!("{}-wal", path.display())).exists());
    }
}
