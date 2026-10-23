//! token 校准的落库（D95）。
//!
//! 只是 `settings` 表上的一层薄壳：校准的 key 自带模型、级别、内容类，
//! 所以一张表就够，不必为它加 schema。**读用 `load_setting`**，坏数据照样记
//! `settings_repair` 并按"没有校准"处理——校准坏了不该让这一轮直接失败，
//! 那是最坏情况：既算不准，又发不出去。

use nmoi_core::tokens::{build_key, parse_key, Calibration, Class, Level};

use crate::settings::{Loaded, Scope};
use crate::{Storage, StorageError};

impl Storage {
    /// 写一份校准（key 由校准自己算）。
    pub fn put_calibration(&self, calibration: &Calibration, now: i64) -> Result<(), StorageError> {
        self.put_setting(Scope::Calibration, &calibration.key(), calibration, now)
    }

    /// 读一份校准。没有、或坏掉，都是 `None`。
    pub fn get_calibration(
        &self,
        model: &str,
        level: Level,
        class: Class,
        now: i64,
    ) -> Result<Option<Calibration>, StorageError> {
        let key = build_key(model, level, class);
        match self.load_setting::<Calibration>(Scope::Calibration, &key, now)? {
            Loaded::Ready(calibration) => Ok(Some(calibration)),
            Loaded::Missing | Loaded::Reset { .. } => Ok(None),
        }
    }

    /// 一个模型的全部校准（L0 各内容类 + L1 那一个桶）。
    ///
    /// 估算器要的是一整片，不是一条——它按级别与内容类自己挑。
    pub fn calibrations(&self, model: &str, now: i64) -> Result<Vec<Calibration>, StorageError> {
        let prefix = format!("{model}|");
        let mut out = Vec::new();
        for (key, _doc) in self.list_docs(Scope::Calibration)? {
            if !key.starts_with(&prefix) {
                continue;
            }
            // key 认不出来（旧版本写的、或者手改坏的）就跳过，不猜。
            if parse_key(&key).is_none() {
                continue;
            }
            if let Some(calibration) = self.get_calibration_inner(&key, now)? {
                out.push(calibration);
            }
        }
        out.sort_by(|a, b| {
            (a.level.as_str(), a.class.as_str()).cmp(&(b.level.as_str(), b.class.as_str()))
        });
        Ok(out)
    }

    fn get_calibration_inner(
        &self,
        key: &str,
        now: i64,
    ) -> Result<Option<Calibration>, StorageError> {
        match self.load_setting::<Calibration>(Scope::Calibration, key, now)? {
            Loaded::Ready(calibration) => Ok(Some(calibration)),
            Loaded::Missing | Loaded::Reset { .. } => Ok(None),
        }
    }
}
