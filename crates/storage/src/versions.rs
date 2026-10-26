//! 消息版本（D135 的规则、D137 的施工图）。
//!
//! 一句话：**每条消息可以有多版回复**，每版存两组正文——模型原始输出（`raw_text`）
//! 与工具/编辑改过的那一份（`edited_text`）；`active_view` 记"上次看的是哪一组"。
//!
//! 三条设计上的取舍：
//!
//! 1. **`messages.content` 仍然是"当前显示的那一组"**。这样 `load_units` 的读路径一行都不用改，
//!    界面照旧渲染 `content`；切版本只是把选中的那一组写回 `content`。
//!    代价是同一份文本存两处，所以**所有写入都必须走本模块的方法**——别处直接 UPDATE
//!    `messages.content` 会让两处分叉，而分叉之后没人知道哪份是对的。
//! 2. `version_no` **永远是 1..=version_count 的连续整数**。删除版本后重新编号，
//!    这样 UI 上的 `n/n` 不需要再处理空洞。
//! 3. 每消息上限 [`MAX_VERSIONS`] 版，超了丢**最旧**的一版（丢的是旧，不是当前）。

use std::collections::HashMap;

use nmoi_core::ids::{MessageId, SessionId};
use rusqlite::{params, Transaction};

use crate::repo::StorageError;
use crate::Storage;

/// 每消息最多保留多少版。超了丢最旧的那一版（当前显示的那一版永不丢）。
pub const MAX_VERSIONS: i64 = 20;

/// 这一版现在显示的是哪一组正文。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VersionView {
    Raw,
    Edited,
}

impl VersionView {
    pub fn as_str(self) -> &'static str {
        match self {
            VersionView::Raw => "raw",
            VersionView::Edited => "edited",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "raw" => Some(VersionView::Raw),
            "edited" => Some(VersionView::Edited),
            _ => None,
        }
    }
}

/// 一版的元信息（不含正文：列表页只需要知道"几版、哪版、什么颜色"）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessageVersion {
    pub version_no: i64,
    pub active: bool,
    /// 被**工具/AI** 改过。决定 UI 上那个数字的颜色（白 = 没有，蓝/红 = 有）。
    ///
    /// 注意：**用户编辑不会把它置 1**（D148）。用户改的是"他看到的那一份"，
    /// 存在 `user_text` 里，颜色不受影响。
    pub edited_by_tool: bool,
    /// 用户改过这一版（`user_text` 非空）。不参与三色，只是另一个标记。
    pub user_edited: bool,
    pub view: VersionView,
    pub created_at: i64,
}

/// 列表结果：总数 + 当前版号 + 每版元信息。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionList {
    pub count: i64,
    pub active: i64,
    pub versions: Vec<MessageVersion>,
    /// **当前版、当前视图下应当显示的正文**（`shown_text` 算出来的那一份）。
    ///
    /// 给界面「编辑」用：预填的东西必须与屏幕上显示的是同一份，
    /// 否则就会出现"看着改后、编辑框里是原文，存完正文又变成他打的那份"（2026-10-26 报）。
    /// 让界面自己按 raw/edited 去拼，等于在 Kotlin 里再实现一遍显示规则——两份规则迟早分叉。
    pub shown: String,
}

impl Storage {
    /// 记一版**新生成的**回复（重新生成时调用，前提是"消息版本"开关是开的）。
    ///
    /// - 第一次调用会建第 1 版，并把 `content` 设成它；
    /// - 之后再调用就追加，成为新的当前版；
    /// - 超过 [`MAX_VERSIONS`] 时丢最旧的一版——但**不丢当前版**。
    ///
    /// 返回 `(新的版号, 当前总版数)`。
    pub fn save_generated_version(
        &mut self,
        message_id: &MessageId,
        raw_text: &str,
    ) -> Result<(i64, i64), StorageError> {
        let tx = self.conn.transaction()?;
        let id = message_id.to_string();
        let exists: bool = tx
            .query_row("SELECT 1 FROM messages WHERE id = ?1", [&id], |row| {
                row.get::<_, i64>(0)
            })
            .is_ok();
        if !exists {
            return Err(StorageError::Inconsistent(format!(
                "消息 {message_id} 不存在，不能记版本"
            )));
        }

        let current: i64 = tx.query_row(
            "SELECT version_count FROM messages WHERE id = ?1",
            [&id],
            |row| row.get(0),
        )?;
        let next = current + 1;
        tx.execute(
            "INSERT INTO message_versions(message_id, version_no, raw_text, edited_text,
                                          edited_by_tool, active_view, created_at)
             VALUES (?1, ?2, ?3, NULL, 0, 'raw', ?4)",
            params![id, next, raw_text, crate::validate::now_ms()],
        )?;

        // 超上限：丢最旧的一版（当前这一版刚插入，永远不会是被丢的那个）。
        let mut count = next;
        if count > MAX_VERSIONS {
            tx.execute(
                "DELETE FROM message_versions
                 WHERE message_id = ?1 AND version_no = (
                     SELECT MIN(version_no) FROM message_versions WHERE message_id = ?1
                 )",
                [&id],
            )?;
            renumber(&tx, &id)?;
            count = MAX_VERSIONS;
        }

        // `next` 可能因为重编号变了：新插入的那一版现在在最后。
        let active: i64 = tx.query_row(
            "SELECT MAX(version_no) FROM message_versions WHERE message_id = ?1",
            [&id],
            |row| row.get(0),
        )?;
        tx.execute(
            "UPDATE messages SET version_count = ?1, version_active = ?2, content = ?3 WHERE id = ?4",
            params![count, active, raw_text, id],
        )?;
        tx.commit()?;
        Ok((active, count))
    }

    /// 把某一版标成"被工具编辑过"，并写下编辑后的正文。
    ///
    /// 这一条消息还没记过版本时，**先把它现在的正文落成第 1 版**再标
    /// （真机上"工具编辑"对绝大多数消息都是可点的，而绝大多数消息没有版本行）。
    ///
    /// 只有 `active_view = 'edited'` 的那一版才需要同步 `messages.content`——
    /// 别的版本改内容不该影响屏幕上正显示的东西。
    pub fn mark_version_edited(
        &mut self,
        message_id: &MessageId,
        version_no: i64,
        edited_text: &str,
    ) -> Result<(), StorageError> {
        let tx = self.conn.transaction()?;
        let id = message_id.to_string();
        seed_first_version(&tx, &id)?;
        let changed = tx.execute(
            "UPDATE message_versions
             SET edited_text = ?1, edited_by_tool = 1
             WHERE message_id = ?2 AND version_no = ?3",
            params![edited_text, id, version_no],
        )?;
        if changed == 0 {
            return Err(StorageError::Inconsistent(format!(
                "消息 {message_id} 没有第 {version_no} 版"
            )));
        }
        tx.execute(
            "UPDATE messages SET content = ?1
             WHERE id = ?2 AND version_active = ?3
               AND EXISTS (
                   SELECT 1 FROM message_versions
                   WHERE message_id = ?2 AND version_no = ?3 AND active_view = 'edited'
               )",
            params![edited_text, id, version_no],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// 列出每一版的元信息。
    pub fn list_message_versions(
        &self,
        message_id: &MessageId,
    ) -> Result<VersionList, StorageError> {
        let id = message_id.to_string();
        let (count, active): (i64, i64) = self.conn.query_row(
            "SELECT version_count, version_active FROM messages WHERE id = ?1",
            [&id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;

        let mut stmt = self.conn.prepare(
            "SELECT version_no, edited_by_tool, active_view, created_at,
                    (user_text IS NOT NULL)
             FROM message_versions WHERE message_id = ?1 ORDER BY version_no",
        )?;
        let rows = stmt.query_map([&id], |row| {
            let version_no: i64 = row.get(0)?;
            let edited: i64 = row.get(1)?;
            let view: String = row.get(2)?;
            Ok(MessageVersion {
                version_no,
                active: version_no == active,
                edited_by_tool: edited != 0,
                user_edited: row.get::<_, i64>(4)? != 0,
                view: VersionView::parse(&view).unwrap_or(VersionView::Raw),
                created_at: row.get(3)?,
            })
        })?;
        let versions = rows.collect::<Result<Vec<_>, _>>()?;
        // 当前版当前视图该显示的正文：与 `switch_message_version` 用同一个函数算，
        // 不在这里另写一份"看 edited 就取 edited_text"。
        let shown = match versions.iter().find(|version| version.active) {
            None => String::new(),
            Some(current) => {
                let (raw, edited, user_text, user_edit_of) = self.conn.query_row(
                    "SELECT raw_text, edited_text, user_text, user_edit_of
                     FROM message_versions WHERE message_id = ?1 AND version_no = ?2",
                    params![id, current.version_no],
                    |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, Option<String>>(1)?,
                            row.get::<_, Option<String>>(2)?,
                            row.get::<_, Option<String>>(3)?,
                        ))
                    },
                )?;
                shown_text(
                    &raw,
                    edited.as_deref(),
                    user_text.as_deref(),
                    user_edit_of.as_deref(),
                    current.view,
                )
            }
        };
        Ok(VersionList {
            count,
            active,
            versions,
            shown,
        })
    }

    /// 切到某一版的某一组正文，返回**切换后应该显示的正文**。
    ///
    /// 没被编辑过的版本只有一组正文，传 `Edited` 会被降级成 `Raw`（不报错：
    /// 界面上的"点数字切换"本来就只在蓝/红两态下有效）。
    pub fn switch_message_version(
        &mut self,
        message_id: &MessageId,
        version_no: i64,
        view: VersionView,
    ) -> Result<String, StorageError> {
        let tx = self.conn.transaction()?;
        let id = message_id.to_string();
        let (raw, edited, edited_by_tool, user_text, user_edit_of) = tx
            .query_row(
                "SELECT raw_text, edited_text, edited_by_tool, user_text, user_edit_of
                 FROM message_versions WHERE message_id = ?1 AND version_no = ?2",
                params![id, version_no],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, Option<String>>(3)?,
                        row.get::<_, Option<String>>(4)?,
                    ))
                },
            )
            .map_err(|_| {
                StorageError::Inconsistent(format!("消息 {message_id} 没有第 {version_no} 版"))
            })?;

        let effective = if edited_by_tool == 0 || edited.is_none() {
            VersionView::Raw
        } else {
            view
        };
        let text = shown_text(
            &raw,
            edited.as_deref(),
            user_text.as_deref(),
            user_edit_of.as_deref(),
            effective,
        );
        tx.execute(
            "UPDATE message_versions SET active_view = ?1 WHERE message_id = ?2 AND version_no = ?3",
            params![effective.as_str(), id, version_no],
        )?;
        tx.execute(
            "UPDATE messages SET version_active = ?1, content = ?2 WHERE id = ?3",
            params![version_no, text, id],
        )?;
        tx.commit()?;
        Ok(text)
    }

    /// **用户**改这一版的正文（D148）。
    ///
    /// 跟 [`Self::mark_version_edited`] 的区别（这条区别是整套颜色语义的根）：
    ///
    /// - 用户编辑只写 `user_text`，**不碰 `edited_by_tool`**——用户改字不该让这一版变成"AI 改过"；
    /// - 记下 `user_edit_of`＝改的时候正在看哪一组（`raw` / `edited`），
    ///   因为同一个版本下原文和 AI 改后是两份内容，用户可以分别改；
    /// - 这一版正在显示时，同步 `messages.content`（`content` 永远代表"当前显示的那一份"）。
    ///
    /// 还没记过版本的消息先落第 1 版，理由同 [`Self::mark_version_edited`]。
    ///
    /// 返回改完之后应当显示的正文。
    pub fn user_edit_version(
        &mut self,
        message_id: &MessageId,
        version_no: i64,
        text: &str,
    ) -> Result<String, StorageError> {
        let tx = self.conn.transaction()?;
        let id = message_id.to_string();
        seed_first_version(&tx, &id)?;
        let (edited_by_tool, active_view): (i64, String) = tx
            .query_row(
                "SELECT edited_by_tool, active_view FROM message_versions
                 WHERE message_id = ?1 AND version_no = ?2",
                params![id, version_no],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|_| {
                StorageError::Inconsistent(format!("消息 {message_id} 没有第 {version_no} 版"))
            })?;
        // 没被工具改过的版本只有一组正文，用户改的就是那一组。
        let target = if edited_by_tool == 0 {
            VersionView::Raw
        } else {
            VersionView::parse(&active_view).unwrap_or(VersionView::Raw)
        };
        tx.execute(
            "UPDATE message_versions SET user_text = ?1, user_edit_of = ?2
             WHERE message_id = ?3 AND version_no = ?4",
            params![text, target.as_str(), id, version_no],
        )?;
        tx.execute(
            "UPDATE messages SET content = ?1 WHERE id = ?2 AND version_active = ?3",
            params![text, id, version_no],
        )?;
        tx.commit()?;
        Ok(text.to_string())
    }

    /// 切到某一版，**视图沿用这一版自己记住的那个**（`active_view`）。
    ///
    /// UI 上按 `<` / `>` 翻版本时用这个：把当前这一版的"原文/编辑后"状态硬套到邻居上，
    /// 会让用户看到"我明明点的是上一版，它却跳到了编辑态"。
    pub fn switch_message_version_remembered(
        &mut self,
        message_id: &MessageId,
        version_no: i64,
    ) -> Result<String, StorageError> {
        let view: String = self.conn.query_row(
            "SELECT active_view FROM message_versions WHERE message_id = ?1 AND version_no = ?2",
            params![message_id.to_string(), version_no],
            |row| row.get(0),
        )?;
        let view = VersionView::parse(&view).unwrap_or(VersionView::Raw);
        self.switch_message_version(message_id, version_no, view)
    }

    /// 删一版（`version_no = 0` 表示"删除所有版本、只留当前显示的那一版"）。
    ///
    /// 两种语义，**不是**同一种：
    ///
    /// - `version_no > 0`：删这一版。只剩一版时拒绝（删到零版等于删消息，那组动词属于 D130）；
    /// - `version_no == 0`：**清空整条消息的版本记录**（连当前版一起），正文保持"现在显示的那一份"。
    ///   这是 D151 改的：原来是"留当前版"，用户 2026-10-26 说不对——他要的是把版本这件事撤销掉，
    ///   不是把两版合成一版。清空之后 `n/n` 与颜色一起消失，正文一个字节不动。
    ///
    /// 返回 `(剩余版数, 当前版号, 当前应显示的正文)`；清空后前两个都是 0。
    pub fn delete_message_version(
        &mut self,
        message_id: &MessageId,
        version_no: i64,
    ) -> Result<(i64, i64, String), StorageError> {
        let tx = self.conn.transaction()?;
        let id = message_id.to_string();
        let (count, active): (i64, i64) = tx
            .query_row(
                "SELECT version_count, version_active FROM messages WHERE id = ?1",
                [&id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|_| StorageError::Inconsistent(format!("消息 {message_id} 不存在")))?;

        if version_no == 0 {
            let content: String =
                tx.query_row("SELECT content FROM messages WHERE id = ?1", [&id], |row| {
                    row.get(0)
                })?;
            tx.execute("DELETE FROM message_versions WHERE message_id = ?1", [&id])?;
            tx.execute(
                "UPDATE messages SET version_count = 0, version_active = 0 WHERE id = ?1",
                [&id],
            )?;
            tx.commit()?;
            return Ok((0, 0, content));
        }

        if count <= 1 {
            return Err(StorageError::Inconsistent(format!(
                "消息 {message_id} 只剩一版，删了就等于删消息（那要走删除消息的流程）"
            )));
        }

        {
            let changed = tx.execute(
                "DELETE FROM message_versions WHERE message_id = ?1 AND version_no = ?2",
                params![id, version_no],
            )?;
            if changed == 0 {
                return Err(StorageError::Inconsistent(format!(
                    "消息 {message_id} 没有第 {version_no} 版"
                )));
            }
        }

        renumber(&tx, &id)?;
        let new_count: i64 = tx.query_row(
            "SELECT COUNT(*) FROM message_versions WHERE message_id = ?1",
            [&id],
            |row| row.get(0),
        )?;
        // 当前版落在哪个新编号上：删的是前面的版本就往前挪，否则原样。
        // `version_no == 0`（清空）在上面就返回了，走不到这里。
        let new_active = if version_no < active {
            (active - 1).max(1)
        } else {
            active.min(new_count)
        };

        let (raw, edited, user_text, user_edit_of, view): (
            String,
            Option<String>,
            Option<String>,
            Option<String>,
            String,
        ) = tx.query_row(
            "SELECT raw_text, edited_text, user_text, user_edit_of, active_view
             FROM message_versions WHERE message_id = ?1 AND version_no = ?2",
            params![id, new_active],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                ))
            },
        )?;
        // 显示哪一份**必须走 `shown_text`**：这里曾经自己拼了一遍"看 edited 就取 edited_text"，
        // 于是用户改过的那一份被丢掉——删完版本，正文跳回 AI 那一份（2026-10-26 报）。
        let displayed = shown_text(
            &raw,
            edited.as_deref(),
            user_text.as_deref(),
            user_edit_of.as_deref(),
            VersionView::parse(&view).unwrap_or(VersionView::Raw),
        );

        tx.execute(
            "UPDATE messages SET version_count = ?1, version_active = ?2, content = ?3 WHERE id = ?4",
            params![new_count, new_active, displayed, id],
        )?;
        tx.commit()?;
        Ok((new_count, new_active, displayed))
    }
}

/// 一条消息的版本概要（列表渲染用）：几版、当前第几版、当前看哪一组、有没有被编辑过。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionBrief {
    pub count: i64,
    pub active: i64,
    pub view: VersionView,
    pub edited_by_tool: bool,
    /// 当前这一版被用户改过（不参与三色，只是另一个标记）。
    pub user_edited: bool,
}

impl Storage {
    /// 整个会话的版本概要，按消息 id 索引。
    ///
    /// 为什么整会话一次查完、而不是每条消息查一次：`load_units` 一次给几十上百条消息，
    /// 逐条查会变成 N+1 次 SQL——真机上就是滚动一顿一顿的来源。
    ///
    /// 只返回 `version_count > 0` 的消息：没版本的消息（绝大多数）不该出现在结果里，
    /// 调用方拿不到就等于"没有版本"，与 `count = 0` 同义。
    pub fn version_index(
        &self,
        session: SessionId,
    ) -> Result<HashMap<String, VersionBrief>, StorageError> {
        let mut stmt = self.conn.prepare(
            "SELECT m.id, m.version_count, m.version_active,
                    COALESCE(v.edited_by_tool, 0), COALESCE(v.active_view, 'raw'),
                    COALESCE(v.user_text, '') <> ''
             FROM messages m
             LEFT JOIN message_versions v
                    ON v.message_id = m.id AND v.version_no = m.version_active
             WHERE m.conv_id = ?1 AND m.version_count > 0",
        )?;
        let rows = stmt.query_map([session.to_string()], |row| {
            let id: String = row.get(0)?;
            let count: i64 = row.get(1)?;
            let active: i64 = row.get(2)?;
            let edited: i64 = row.get(3)?;
            let view: String = row.get(4)?;
            let user_edited: i64 = row.get(5)?;
            Ok((
                id,
                VersionBrief {
                    count,
                    active,
                    view: VersionView::parse(&view).unwrap_or(VersionView::Raw),
                    edited_by_tool: edited != 0,
                    user_edited: user_edited != 0,
                },
            ))
        })?;
        let mut out = HashMap::new();
        for row in rows {
            let (id, brief) = row?;
            out.insert(id, brief);
        }
        Ok(out)
    }
}

/// 某一版在某个视图下应当显示的正文（D148）。
///
/// 优先级：**用户改过的那一组** > `edited_text`（仅当要看 edited）> `raw_text`。
/// `user_edit_of` 记的是"用户改的是哪一组"，所以改了原文之后切到"编辑后"看到的
/// 仍然是 AI 那一份，不会把用户的改写串到另一组上去。
fn shown_text(
    raw: &str,
    edited: Option<&str>,
    user_text: Option<&str>,
    user_edit_of: Option<&str>,
    view: VersionView,
) -> String {
    if let (Some(text), Some(of)) = (user_text, user_edit_of) {
        if VersionView::parse(of).unwrap_or(VersionView::Raw) == view {
            return text.to_string();
        }
    }
    match view {
        VersionView::Raw => raw.to_string(),
        VersionView::Edited => edited.unwrap_or(raw).to_string(),
    }
}

/// 一条消息还没记过版本时，把它现在的正文落成第 1 版。
///
/// 为什么放在写入路径里、而不是要求调用方先 seed：真机上「编辑」「工具编辑」对**绝大多数
/// 消息**都是可点的，而绝大多数消息没有任何版本行——不自动补，用户看到的就是
/// `工具编辑失败：… 消息 xxx 没有第 1 版`（2026-10-26 报，截图里就是这个）。
///
/// `raw_text` 取 `messages.content`（**当前显示的那一份**），所以调用方传进来的是"改后"，
/// 原文也不会被它顶掉。返回是否真的补了一版（测试用）。
fn seed_first_version(tx: &Transaction<'_>, message_id: &str) -> Result<bool, StorageError> {
    let (count, content): (i64, String) = tx
        .query_row(
            "SELECT version_count, content FROM messages WHERE id = ?1",
            [message_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|_| StorageError::Inconsistent(format!("消息 {message_id} 不存在")))?;
    if count > 0 {
        return Ok(false);
    }
    tx.execute(
        "INSERT INTO message_versions(message_id, version_no, raw_text, edited_text,
                                      edited_by_tool, active_view, created_at)
         VALUES (?1, 1, ?2, NULL, 0, 'raw', ?3)",
        params![message_id, content, crate::validate::now_ms()],
    )?;
    tx.execute(
        "UPDATE messages SET version_count = 1, version_active = 1 WHERE id = ?1",
        [message_id],
    )?;
    Ok(true)
}

/// 把版本号重新压成 1..=n（删过之后会留空洞，而 UI 的 `n/n` 不该看到空洞）。
///
/// 先挪到 `1_000_000 + idx` 再挪回 `idx + 1`：`UNIQUE(message_id, version_no)` 会在
/// 直接改写时撞车（1→2 时 2 还在），两步走能绕开。
fn renumber(tx: &Transaction<'_>, message_id: &str) -> Result<(), rusqlite::Error> {
    let ids: Vec<i64> = {
        let mut stmt = tx
            .prepare("SELECT id FROM message_versions WHERE message_id = ?1 ORDER BY version_no")?;
        let rows = stmt.query_map([message_id], |row| row.get(0))?;
        rows.collect::<Result<Vec<_>, _>>()?
    };
    for (idx, row_id) in ids.iter().enumerate() {
        tx.execute(
            "UPDATE message_versions SET version_no = ?1 WHERE id = ?2",
            params![1_000_000 + idx as i64, row_id],
        )?;
    }
    for (idx, row_id) in ids.iter().enumerate() {
        tx.execute(
            "UPDATE message_versions SET version_no = ?1 WHERE id = ?2",
            params![idx as i64 + 1, row_id],
        )?;
    }
    Ok(())
}
