/*
 * NMoi-Agent C ABI（stage F1）
 * ===========================
 *
 * 这个头文件是**契约**，不是"当前实现的快照"：`docs/PLAN.md` §3.11 与
 * `docs/decisions.md` D53–D57 规定了下面每一条规矩的来历。
 *
 * 三种调用形状
 * ------------
 *   1. 数据 / 句柄：`char *nmoi_xxx_json(...)`
 *      - **永不返回 NULL**。失败也是 JSON：`{"ok":false,"error":{"kind":"…","message":"…"}}`
 *      - `ok` 永远是第一个键，所以可以只检查前缀 `{"ok":true`
 *      - 返回值必须用 `nmoi_string_free()` 归还；不还只是泄漏，不会崩
 *   2. 标量：`int32_t` / `uint32_t`，负数表示 ABI 级问题（见 FfiStatus）
 *   3. 输入字符串：`const char *`，UTF-8、NUL 结尾；**只读**，调用期间必须有效
 *
 * 句柄
 * ----
 * 句柄是整数 id（`uint64_t`），不是指针。**id 永不复用**：关掉之后再用同一个 id
 * 只会得到 `{"ok":false,"error":{"kind":"invalid_handle"}}`，不会崩进程。
 * 关闭是幂等的，清理路径可以放心重复调用。
 *
 * 线程
 * ----
 * 所有函数都是**同步阻塞**的，且内部只有一把全局锁：同一进程内的 FFI 调用是串行的。
 * 不要在 UI 主线程调用 storage / workspace（会锁库、会读盘）。
 * FFI 自己不建线程、不回调。
 *
 * panic
 * -----
 * 边界内 panic 不会穿出来：JSON 函数变成 `{"kind":"panic"}`，标量函数返回 -3。
 */

#ifndef NMOI_H
#define NMOI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- 版本与自检 ---------------------------------------------------------- */

/* ABI 版本。加函数不递增；改参数含义 / 返回语义必须递增。加载后先比对这个值。 */
uint32_t nmoi_abi_version(void);

/* 产品名写入调用方缓冲区。成功返回写入字节数（不含 NUL）；缓冲区不足返回所需长度（含 NUL）。 */
int32_t nmoi_product_name(char *buf, size_t len);

/* 版本串，语义同 nmoi_product_name。 */
int32_t nmoi_version(char *buf, size_t len);

/* 纯函数自检（不碰盘不碰网）。返回 0 表示绑定与 ABI 对齐。 */
int32_t nmoi_selftest(void);

/* 故意 panic 的探针：返回 -3（FfiStatus::Panic）才说明 catch_unwind 真的在工作。 */
int32_t nmoi_panic_probe(void);

/* 归还 nmoi_*_json 的返回值。传 NULL 是合法的空操作。 */
void nmoi_string_free(char *text);

/* 这一层给了什么：abi_version / schema_version / features / tools / https 支持情况。 */
char *nmoi_capabilities_json(void);

/* ---- 存储 ---------------------------------------------------------------- */

/*
 * 打开（必要时新建并迁移）库。
 * 返回 `state` 三种之一：
 *   "ready"   给了句柄
 *   "blocked" L3 语义损坏，**不给句柄**，带 issues / report；恢复方式由用户选，不自动修
 *   失败       {"ok":false,"error":…}
 */
char *nmoi_storage_open_json(const char *path);

/* 关句柄（幂等）。返回 {"ok":true,"closed":true|false} */
char *nmoi_storage_close_json(uint64_t handle);

/* 重新体检：integrity_ok / schema_version / semantic / quarantine_entries … */
char *nmoi_storage_health_json(uint64_t handle);

/* 启动收口：只报告，不猜。pending_turns / open_units / incomplete_messages。 */
char *nmoi_storage_reconcile_json(uint64_t handle);

/* 新建会话，返回 {"ok":true,"session":"<uuid>"} */
char *nmoi_storage_create_conversation_json(uint64_t handle, const char *title);

/*
 * 记下"这个会话现在用哪个模型"（D101）。返回 {"ok":true,"session":"…","model":"…"}。
 *
 * 为什么单独一条：用户常常先建会话、再挑模型。空串会被当成 bad_argument 拒绝——
 * NULL/不调 才代表"还不知道"，空串是调用方写错了。
 * `nmoi_context_breakdown_json` 靠这个值选词表：没设过就只能给 L0 的粗估。
 */
char *nmoi_storage_set_conversation_model_json(uint64_t handle,
                                              const char *session,
                                              const char *model);

/*
 * 追加一条纯文本消息（user / assistant / system）。不经过模型。
 *
 * UI 的"发送按钮长按 → 仅保存为助手消息 / 仅保存为用户消息"就是它：
 * 同一个 text、不同 role，存成最新一条消息但不发给模型。
 * 存成 assistant 会让历史变成"以助手结尾"——那是用户要的语义，别在 UI 层"顺手修正"。
 */
char *nmoi_storage_append_text_json(uint64_t handle,
                                    const char *session,
                                    const char *role,
                                    const char *text,
                                    int64_t now_ms);

/* 读会话的原子单元。limit > 0 只取最后 N 个，<= 0 全给。 */
char *nmoi_storage_load_units_json(uint64_t handle, const char *session, int64_t limit);

/*
 * 会话列表（UI 第一屏）。排序 updated_at DESC, id DESC——第二键保证同一毫秒建的两个
 * 会话也有确定顺序，不然列表会自己乱跳。
 *
 * 分页给 has_more，**不给总数**：UI 只需要知道"还能不能往下翻"。
 * limit <= 0 用默认 50，硬上限 200；offset < 0 当 0。
 *
 * 每条的 last_text 是最后一条消息的预览，**截断到 200 字符**；
 * last_text_truncated 说明它不完整——别把半句话当全文显示。
 */
char *nmoi_storage_list_conversations_json(uint64_t handle, int64_t limit, int64_t offset);

/*
 * 上下文窗口面板的八行用量（P6.4）。行数与顺序固定，0 行也给。
 *
 * 库只能算它自己存了什么（历史、工具调用与结果）。系统提示词 / 技能 / 世界书 /
 * 工作区 / 草稿是编排层拼进去的，库看不见——所以 extra 由调用方给：
 *
 *   {"system_prompt":1200,"skills":3400,"world_book":0,"workspace":500,"draft":0,
 *    "tool_definitions":[{"name":"read_file","tokens":90},
 *                        {"name":"mcp__fs__read","tokens":120}]}
 *
 * extra 传 NULL 或 "" 表示"只算库里的"。**不许**在 extra 里给 history /
 * builtin_tools / mcp_tools：那是库算的，两边都算会变成双份，所以直接报错。
 *
 * tokens 是 estimate_tokens 的估算，不是端点的真实 tokenizer，`estimated:true`
 * 就是这个意思——面板上那个数不该被当成账单。
 */
char *nmoi_context_breakdown_json(uint64_t handle, const char *session, const char *extra);

/* 备份到 dest：.tmp → 验证 → 发布。返回 {"verified":true,...} 才算成。 */
char *nmoi_storage_backup_json(uint64_t handle, const char *dest);

/* 只读诊断一个库文件（不动主文件）。 */
char *nmoi_storage_diagnose_json(const char *path);

/* ---- 设置 ---------------------------------------------------------------- */

/*
 * 设置是"按 (scope, key) 取的文档"（D86）。五个动词，四类文档共用：
 *
 *   scope = model_overrides | assistant | conversation | roles
 *   key   = provider/model | 助手 id | 会话 id | "global"
 *
 * 写进去的 doc 必须是 JSON 对象。不是对象就被挡在写入口——让它写进去，
 * 就等于把"读不出来的数据"交给用户自己承担。
 *
 * get 有三种结局，**都写在 JSON 里**：
 *   {"ok":true,"state":"ready","doc":{…}}
 *   {"ok":true,"state":"missing"}
 *   {"ok":true,"state":"reset","reason":"…"}      ← 不是错误：数据在那儿但没法用，
 *                                                    已按默认处理并记了一笔（D79）
 *
 * list 里读不出来的条目给 "readable":false 且**不带 doc 字段**（给 null 会让人
 * 以为"有个空文档"）。list 不记修复记录：它会被反复刷，会把真正的那条淹掉。
 */
char *nmoi_settings_put_json(uint64_t handle, const char *scope, const char *key,
                             const char *doc, int64_t now_ms);
char *nmoi_settings_get_json(uint64_t handle, const char *scope, const char *key,
                             int64_t now_ms);
char *nmoi_settings_list_json(uint64_t handle, const char *scope, int64_t limit);
char *nmoi_settings_delete_json(uint64_t handle, const char *scope, const char *key);

/* 设置被重置的记录。digest 是原值哈希的前 16 位，不是原值（可能夹着密钥）。 */
char *nmoi_settings_repairs_json(uint64_t handle, int64_t limit);
char *nmoi_settings_trim_repairs_json(uint64_t handle, int64_t keep);

/* ---- 工作区 -------------------------------------------------------------- */

/*
 * 绑定一个已存在的目录。这一层**不过能力门控**：那是模型（工具层）的开关，
 * 用户自己读写文件不该被自己锁住。
 */
char *nmoi_workspace_open_json(const char *root);

/* 关句柄（幂等）。 */
char *nmoi_workspace_close_json(uint64_t handle);

/* 读文件。start_line 1-based（0 = 默认 1）；max_lines 0 = 默认上限；force_full != 0 关掉截断。 */
char *nmoi_workspace_read_json(uint64_t handle,
                               const char *path,
                               uint64_t start_line,
                               uint64_t max_lines,
                               int32_t force_full);

/* 整篇写入（覆盖），父目录会被创建。 */
char *nmoi_workspace_write_json(uint64_t handle, const char *path, const char *content);

/* 精确串替换。找不到 / 匹配不唯一都失败且不动文件；replace_all != 0 全换。 */
char *nmoi_workspace_edit_json(uint64_t handle,
                               const char *path,
                               const char *old_text,
                               const char *new_text,
                               int32_t replace_all);

/* 列目录。depth / max_entries 传 0 用默认。 */
char *nmoi_workspace_list_json(uint64_t handle,
                               const char *path,
                               uint64_t depth,
                               uint64_t max_entries,
                               int32_t include_hidden);

/* 单路径元信息。 */
char *nmoi_workspace_stat_json(uint64_t handle, const char *path);

/* ---- 工具 ---------------------------------------------------------------- */

/* 全部工具名（不看策略）。 */
char *nmoi_tool_names_json(void);

/* 按策略裁过的声明表——发给模型的就是这一份。workspace_handle = 0 表示没绑工作区。 */
char *nmoi_tool_specs_json(uint64_t workspace_handle, int32_t read_only);

/*
 * 直接调一个工具。`arguments` 是工具自己的 JSON 对象字符串。
 * 返回体里 `status` 是 "ok" / "denied" / "failed"——**失败也是结果**，
 * 所以这几种都是 ok:true；`error` 只留给 ABI 级问题。
 */
char *nmoi_tool_invoke_json(uint64_t workspace_handle, const char *name, const char *arguments);

/* ---- 跑一轮（F2）-------------------------------------------------------- */

/*
 * 跑一轮会话：用户消息落库 → 模型（可能要调几轮工具）→ 工具对成对落库 →
 * 正文落库 → 算压缩判据。**同步阻塞**，调用方自己在工作线程上调。
 *
 * `config_json` 是一个 JSON 对象（形状见 docs/PLAN.md §3.11 的 F2 小节）：
 *
 *   {
 *     "storage": 1, "session": "018f…", "workspace": 2, "read_only_workspace": false,
 *     "provider": {"id": "local", "kind": "openai_compatible",
 *                  "base_url": "https://api.example.com/v1", "api_key": null},
 *     "model": "…", "user_text": "…", "now_ms": 1700000000000,
 *     "max_rounds": 12, "max_tokens": 16384, "temperature": null,
 *     "budget": {"window": 128000, "reserve_output": 8192}, "avg_per_turn": null
 *   }
 *
 * 出参里 `report.finished` / `report.cancelled` / `report.truncated` 三件事分开：
 * 前者 false 是"轮数用尽，模型还没收尾"，`cancelled` 是"用户点了停止"，
 * `truncated` 是"撞了绝对线，工具循环就地停了"（D90）。UI 该说的话不一样。
 *
 * 上下文越过绝对线时**这一轮不会发出去**，返回 `kind="compaction_required"`：
 * 先压缩再重试，重试不会产生重复的用户消息（判定在落库之前）。
 * `compression.action` 是这一处该做什么：proceed / proceed_then_compact /
 * compact_then_proceed / truncate_then_compact / compact_after_persist。
 *
 * 这条边界上**不注入系统提示词**（先自己 append_text(role="system") 落库）、
 * **不发思考档位**（等角色配置持久化）、**不回显 api_key**。
 *
 * 跑的时候 storage 句柄被取出去，期间对它的其他调用得到 kind="busy"（不会卡住）。
 */
char *nmoi_turn_run_json(const char *config_json);

/*
 * 取消正在跑的那一轮。**只对正在跑的轮次生效**：没在跑就是空操作
 * `{"cancelled":false,"running":false}`——不做"预存取消请求等下一轮"。
 *
 * 粒度是"轮次边界"：正在飞的那次往返不会被打断（最坏等一次往返），
 * 已经执行完的工具照常落库，这一轮标 cancelled + 那条消息带 incomplete。
 */
char *nmoi_turn_cancel_json(const char *session);

/* ---- 视觉覆盖（P3.3）----------------------------------------------------- */

/*
 * 两套默认提示词：图像一份、视频一份（D63）。两份内容不同，别把视频那份接到图片上。
 * 出参：{"image":{"prompt","chars","media","note"},"video":{…},"identical":false}
 */
char *nmoi_vision_defaults_json(void);

/* ---- 图标（D105）--------------------------------------------------------- */

/*
 * 打开一个图标包根目录：<root>/original/ 与 <root>/imported/<pack_id>/。
 * 出参：{"handle":n,"root":"…","packs":[…]}
 *
 * 这一层**不碰 settings 表**："当前用哪个包""用户规则"属于设置，存在 settings 里。
 * 句柄表是一把全局锁，图标句柄里再嵌存储句柄会自己等自己，所以规则由调用方传进来。
 */
char *nmoi_icons_open_json(const char *root);
char *nmoi_icons_close_json(uint64_t handle);           /* 幂等 */
char *nmoi_icons_list_json(uint64_t handle);            /* 原版永远在第一行 */

/* 导入一个包 zip。校验不过整包拒绝，磁盘上不留半个包。 */
char *nmoi_icons_import_json(uint64_t handle, const char *zip_path);

/* 导出到 dest（原版也能导出——"把当前图标带走"是正当需求）。 */
char *nmoi_icons_export_json(uint64_t handle, const char *pack_id, const char *dest);

/* 删除导入的包。**原版永远拒绝**（error kind = refused）：它是回滚的那一档。 */
char *nmoi_icons_delete_json(uint64_t handle, const char *pack_id);

/* 取某个包（不给就是原版）的预设规则，首次启动时用来给设置打种子。 */
char *nmoi_icons_rules_json(uint64_t handle, const char *pack_id);

/*
 * 解析"这个模型该用哪张图"。
 *   rules_json：规则数组，或 {"rules":[…]}；NULL/空串 = 没有规则
 *   subject   ：{"model":"anthropic/claude-opus-5","provider":{"kind":"openai",
 *                "host":"api.x.com","name":"我的端点"},"pack_id":"nmoi-original"}
 * 出参：{"pack_id","icon":{kind,key,file,tint,backing}|null,"via","matched_rule",
 *        "monogram":{letter,hue},"problems":[…]}
 *
 * via 说明是哪一级命中的：rule / provider_kind / model_prefix / monogram。
 * 没命中任何图标时 icon 为 null 但 monogram 永远有值——界面永远有话可说。
 */
char *nmoi_icons_resolve_json(uint64_t handle, const char *rules_json, const char *subject);

/* ---- 文本 ---------------------------------------------------------------- */

/* 脱敏。命中记录只给 kind 与 matched_len，**不回显原文**。 */
char *nmoi_redact_json(const char *text);

/* 粗估 token（不联网），附带 chars / bytes。 */
char *nmoi_estimate_tokens_json(const char *text);

#ifdef __cplusplus
}
#endif

#endif /* NMOI_H */
