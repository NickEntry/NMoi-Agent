/*
 * C 侧验收：直接链接 libnmoi_ffi，按绑定层的方式调一遍。
 *
 * 为什么要有这个文件：Rust 单测只能证明"Rust 调这些函数能过"，
 * 证明不了"从 C 调也是这个 ABI"——符号名、返回宽度、谁释放内存、
 * 句柄失效时的行为，只有真编一次 C 才看得见。
 *
 *     scripts/ffi_smoke.sh
 *
 * 断言都用字符串包含，不引 JSON 库：这个程序要能在只有 cc 的机器上跑。
 * 但会检查括号配平——半截 JSON 是这类边界最典型的错法。
 */

/* mkdtemp 是 POSIX 2008 的，严格的 -std=c99 下不声明。
 * 本地 gcc 只当警告放过，CI 的 gcc 14 直接当错误——所以显式打开。 */
#define _POSIX_C_SOURCE 200809L

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "nmoi.h"

static int checks = 0;
static int failures = 0;

static void check(int condition, const char *label, const char *detail) {
    checks++;
    if (condition) {
        return;
    }
    failures++;
    printf("  FAIL  %s", label);
    if (detail != NULL && detail[0] != '\0') {
        printf("  →  %s", detail);
    }
    printf("\n");
}

static int contains(const char *haystack, const char *needle) {
    if (haystack == NULL || needle == NULL) {
        return 0;
    }
    return strstr(haystack, needle) != NULL;
}

/* 在一个"行对象"里找一个数值字段。
 *
 * 为什么不用 JSON 库：这个程序要能在只有 cc 的机器上跑。所以按 `label` 定位到行，
 * 再在 `{` 与 `}` 之间找 `"field":value`，并要求数字后面紧跟 , 或 }——
 * 否则 `"tokens":10` 会匹配上 `"tokens":100`，测试就变成了自欺。 */
static int row_has_field(const char *json, const char *label, const char *field, long value) {
    if (json == NULL || label == NULL || field == NULL) {
        return 0;
    }
    char label_needle[192];
    snprintf(label_needle, sizeof(label_needle), "\"label\":\"%s\"", label);
    const char *hit = strstr(json, label_needle);
    if (hit == NULL) {
        return 0;
    }
    const char *open = hit;
    while (open > json && *open != '{') {
        open--;
    }
    const char *close = strchr(hit, '}');
    if (close == NULL || open > close) {
        return 0;
    }

    char needle[96];
    const int len = snprintf(needle, sizeof(needle), "\"%s\":%ld", field, value);
    if (len <= 0) {
        return 0;
    }
    for (const char *p = open; p + len <= close + 1; p++) {
        if (strncmp(p, needle, (size_t)len) != 0) {
            continue;
        }
        const char after = p[len];
        if (after == ',' || after == '}') {
            return 1;
        }
    }
    return 0;
}

static int count_of(const char *haystack, const char *needle) {
    if (haystack == NULL || needle == NULL || needle[0] == '\0') {
        return 0;
    }
    int count = 0;
    const size_t len = strlen(needle);
    for (const char *p = haystack; (p = strstr(p, needle)) != NULL; p += len) {
        count++;
    }
    return count;
}

/* {"ok":true 是成功信封的唯一前缀（D55：ok 永远在第一个键）。 */
static int is_ok(const char *json) {
    return json != NULL && strncmp(json, "{\"ok\":true", 10) == 0;
}

static int is_error(const char *json) {
    return json != NULL && strncmp(json, "{\"ok\":false", 11) == 0;
}

/* 括号配平（跳过字符串内容），用来抓"JSON 被截断"这类错。 */
static int balanced(const char *text) {
    int depth = 0;
    int in_string = 0;
    int escaped = 0;
    const char *p = text;
    for (; p != NULL && *p != '\0'; p++) {
        const char c = *p;
        if (in_string) {
            if (escaped) {
                escaped = 0;
            } else if (c == '\\') {
                escaped = 1;
            } else if (c == '"') {
                in_string = 0;
            }
            continue;
        }
        if (c == '"') {
            in_string = 1;
        } else if (c == '{' || c == '[') {
            depth++;
        } else if (c == '}' || c == ']') {
            depth--;
            if (depth < 0) {
                return 0;
            }
        }
    }
    return depth == 0 && !in_string;
}

/*
 * 从 JSON 里抠出一个字符串值。给 session id 这种"后面还要当参数用"的字段用。
 * `marker` 形如 `"session":"`（含冒号和开头的引号）。
 */
static int extract_string(const char *json, const char *marker, char *out, size_t out_len) {
    const char *start = strstr(json, marker);
    if (start == NULL) {
        return 0;
    }
    start += strlen(marker);
    const char *end = strchr(start, '"');
    if (end == NULL) {
        return 0;
    }
    const size_t length = (size_t)(end - start);
    if (length + 1 > out_len) {
        return 0;
    }
    memcpy(out, start, length);
    out[length] = '\0';
    return 1;
}

/* 取一个数字字段（handle 这类）。找不到返回 0。 */
static uint64_t extract_u64(const char *json, const char *marker) {
    const char *at = strstr(json, marker);
    if (at == NULL) {
        return 0;
    }
    return (uint64_t)strtoull(at + strlen(marker), NULL, 10);
}

/* 期望失败的调用：断言信封是 error，仍然检查括号配平。 */
static char *take_error(char *json, const char *label) {
    check(balanced(json), label, "JSON 括号不配平（可能被截断）");
    check(is_error(json), label, json != NULL ? json : "NULL");
    return json;
}

/* 取回一个 JSON 字符串并顺手做两条通用断言；释放仍由调用方负责。 */
static char *take(char *json, const char *label) {
    check(balanced(json), label, "JSON 括号不配平（可能被截断）");
    check(is_ok(json), label, json != NULL ? json : "NULL");
    return json;
}

static void join_path(char *out, size_t out_len, const char *a, const char *b) {
    snprintf(out, out_len, "%s/%s", a, b);
}

int main(void) {
    printf("== NMoi-Agent C ABI smoke ==\n");

    /* 临时目录留着不删：跑挂了可以进去看库和文件到底长什么样。 */
    char work_dir[256] = "/tmp/nmoi-ffi-XXXXXX";
    /* schema 版本从 capabilities 里读，别写死：每升一版都来改这个数字，
     * 改到最后就没人看它了——那才是真正的失守。 */
    long schema_version = 0;
    if (mkdtemp(work_dir) == NULL) {
        printf("mkdtemp 失败，无法继续\n");
        return 2;
    }

    /* ---- 1. 元信息与所有权 ---- */
    printf("[meta]\n");
    {
        check(nmoi_abi_version() == 1, "abi_version == 1", NULL);

        char small[2] = {0};
        const int32_t needed = nmoi_version(small, sizeof(small));
        check(needed > 2, "缓冲区不足时返回所需长度", NULL);

        char version[64] = {0};
        const int32_t written = nmoi_version(version, sizeof(version));
        check(written > 0 && strlen(version) == (size_t)written, "返回值等于写入字节数", version);

        char product[64] = {0};
        check(nmoi_product_name(product, sizeof(product)) > 0, "产品名可读", product);

        check(nmoi_selftest() == 0, "selftest == 0", NULL);
        check(nmoi_panic_probe() == -3, "panic 被挡在边界内（返回 -3）", NULL);

        char *caps = take(nmoi_capabilities_json(), "capabilities");
        check(contains(caps, "\"abi_version\":1"), "capabilities 带 abi_version", caps);
        check(contains(caps, "\"turn\":true"), "capabilities 说 turn 已接（F2）", NULL);
        check(contains(caps, "\"cancel\":true"), "capabilities 说取消已接", NULL);
        check(contains(caps, "\"https\":false"), "capabilities 如实说 https 不支持", NULL);
        schema_version = (long)extract_u64(caps, "\"schema_version\":");
        check(schema_version >= 3, "capabilities 带 schema_version（且不低于 v3）", caps);
        nmoi_string_free(caps);

        char *null_arg = take_error(nmoi_estimate_tokens_json(NULL), "null 参数");
        check(contains(null_arg, "\"kind\":\"null_argument\""), "空指针给出 null_argument",
              null_arg);
        nmoi_string_free(null_arg);

        nmoi_string_free(NULL); /* 必须是空操作，不能崩 */
        check(1, "string_free(NULL) 不崩", NULL);
    }

    /* ---- 2. 文本面 ---- */
    printf("[text]\n");
    {
        char *tokens = take(nmoi_estimate_tokens_json("hello world"), "estimate_tokens");
        check(contains(tokens, "\"tokens\":"), "token 估算有 tokens 字段", tokens);
        nmoi_string_free(tokens);

        /* 视觉覆盖：两套默认提示词必须是两份不同的东西（D63）。 */
        char *vision = take(nmoi_vision_defaults_json(), "vision_defaults");
        check(contains(vision, "Image Analysis Assistant"), "图像默认提示词在那儿", NULL);
        check(contains(vision, "时间轴"), "视频默认提示词是另写的一份", NULL);
        check(contains(vision, "\"identical\":false"), "两份不一样（D63）", NULL);
        check(contains(vision, "\"media\":\"video\""), "标了各自对应哪个模态", NULL);
        nmoi_string_free(vision);

        const char *secret = "key sk-abcdefghijklmnopqrstuvwxyz end";
        char *redacted = take(nmoi_redact_json(secret), "redact");
        check(contains(redacted, "\"kind\":\"api_key\""), "认出 api_key", redacted);
        check(contains(redacted, "\"clean\":false"), "clean 为 false", NULL);
        check(!contains(redacted, "sk-abcdefghijklmnopqrstuvwxyz"), "命中记录不回显原文",
              redacted);
        nmoi_string_free(redacted);
    }

    /* ---- 3. 存储面 ---- */
    printf("[storage]\n");
    uint64_t storage_handle = 0;
    char session[64] = {0};
    {
        char db_path[320];
        join_path(db_path, sizeof(db_path), work_dir, "db.sqlite3");

        char *opened = take(nmoi_storage_open_json(db_path), "storage_open");
        check(contains(opened, "\"state\":\"ready\""), "新库打开后 state = ready", opened);
        storage_handle = extract_u64(opened, "\"handle\":");
        check(storage_handle > 0, "open 返回正整数 handle", opened);
        nmoi_string_free(opened);

        char *created =
            take(nmoi_storage_create_conversation_json(storage_handle, "C smoke", "default"),
                 "create_conversation");
        check(extract_string(created, "\"session\":\"", session, sizeof(session)),
              "返回 session id", created);
        nmoi_string_free(created);

        if (session[0] != '\0') {
            char *first = take(nmoi_storage_append_text_json(storage_handle, session, "user",
                                                            "first line", 1700000000000LL),
                               "append_text#1");
            check(contains(first, "\"seq\":1"), "第一条 seq = 1", first);
            nmoi_string_free(first);

            char *second = take(nmoi_storage_append_text_json(
                                    storage_handle, session, "assistant", "second line",
                                    1700000001000LL),
                                "append_text#2");
            check(contains(second, "\"seq\":2"), "第二条 seq = 2", second);
            nmoi_string_free(second);

            char *units =
                take(nmoi_storage_load_units_json(storage_handle, session, 0), "load_units");
            check(contains(units, "\"units_total\":2"), "库里两个单元", units);
            check(contains(units, "first line"), "第一条内容读得回来", NULL);
            check(contains(units, "\"role\":\"assistant\""), "角色保住了", NULL);
            check(contains(units, "\"closed\":true"), "单元是闭合的", NULL);
            nmoi_string_free(units);

            char *limited = take(nmoi_storage_load_units_json(storage_handle, session, 1),
                                 "load_units(limit=1)");
            check(contains(limited, "\"returned\":1") && contains(limited, "\"truncated\":true"),
                  "limit 生效且说实话", limited);
            nmoi_string_free(limited);

            char *bad_session =
                take_error(nmoi_storage_load_units_json(storage_handle, "not-a-uuid", 0),
                     "load_units(坏 id)");
            check(is_error(bad_session) && contains(bad_session, "\"kind\":\"id\""),
                  "坏 session 给 id 错误", bad_session);
            nmoi_string_free(bad_session);
        }

        char *health = take(nmoi_storage_health_json(storage_handle), "health");
        check(contains(health, "\"integrity_ok\":true"), "整库完整性通过", NULL);
        {
            char needle[64];
            snprintf(needle, sizeof(needle), "\"schema_version\":%ld", schema_version);
            check(contains(health, needle), "health 与 capabilities 报同一个 schema 版本", health);
        }
        nmoi_string_free(health);

        /* 以 assistant 结尾 = 干净：收口不该凭空报"没等到回复"。 */
        char *clean = take(nmoi_storage_reconcile_json(storage_handle), "reconcile(干净)");
        check(contains(clean, "\"clean\":true"), "以 assistant 结尾的会话算干净", clean);
        nmoi_string_free(clean);

        /* 再追加一条 user 而不给回复：收口必须把它报出来（D47：只报告，不猜）。 */
        if (session[0] != '\0') {
            char *third = take(nmoi_storage_append_text_json(
                                   storage_handle, session, "user", "third line", 1700000002000LL),
                               "append_text#3");
            nmoi_string_free(third);

            char *pending = take(nmoi_storage_reconcile_json(storage_handle), "reconcile(挂起)");
            check(contains(pending, "\"needs_attention\":true"), "没等到回复时报警", pending);
            check(contains(pending, "\"last_role\":\"user\""), "说清挂在哪个角色上", NULL);
            nmoi_string_free(pending);
        }

        /* 会话记住"最近用哪个模型"（D101）：面板靠它选词表算 L1。
         * 先设成空串——那是调用方的 bug，必须报错而不是写进一个空模型名。 */
        {
            char *blank = take_error(
                nmoi_storage_set_conversation_model_json(storage_handle, session, "   "),
                "set_model(空串)");
            check(is_error(blank) && contains(blank, "\"kind\":\"bad_argument\""),
                  "空模型名被拒绝", blank);
            nmoi_string_free(blank);

            char *set = take(
                nmoi_storage_set_conversation_model_json(storage_handle, session,
                                                         "c-smoke-model"),
                "set_model");
            check(contains(set, "\"model\":\"c-smoke-model\""), "设模型返回回显", set);
            nmoi_string_free(set);

            /* 会话不存在：报错，不能顺手建一个。用一个格式合法但不存在的 uuid。 */
            char *missing = take_error(nmoi_storage_set_conversation_model_json(
                                           storage_handle,
                                           "018f0000-0000-7000-8000-000000000000", "m"),
                                       "set_model(不存在的会话)");
            check(is_error(missing), "给不存在的会话设模型是错误", missing);
            nmoi_string_free(missing);
        }

        /* 会话列表（P6.2）：UI 第一屏要的东西必须从这一层拿得到。 */
        {
            char *list =
                take(nmoi_storage_list_conversations_json(storage_handle, 0, 0, "default"),
                     "list(默认)");
            check(contains(list, "\"has_more\":false"), "只有一个会话，没有下一页", list);
            check(contains(list, "\"messages\":3"), "条数对得上（三条消息）", NULL);
            check(contains(list, "third line"), "预览是最后一条消息", NULL);
            check(contains(list, "\"last_text_truncated\":false"), "短预览不标截断", NULL);
            check(contains(list, "\"incomplete\":0"), "incomplete 计数在这儿", NULL);
            check(contains(list, "C smoke"), "标题也在", NULL);
            check(contains(list, "\"model\":\"c-smoke-model\""), "列表带上模型名", list);
            nmoi_string_free(list);

            /* limit 被夹到合法区间，offset 负数当 0：UI 传错不该把手机拖死。 */
            char *tiny = take(nmoi_storage_list_conversations_json(storage_handle, -5, -3, NULL),
                              "list(limit=-5, offset=-3)");
            check(contains(tiny, "\"limit\":50"), "limit <= 0 用默认 50", tiny);
            check(contains(tiny, "\"offset\":0"), "offset 负数当 0", NULL);
            nmoi_string_free(tiny);
        }

        /* 会话按助手归属（D174）：移动是**改归属，不复制**——同一个 session id 换一边出现。 */
        {
            char *moved =
                take(nmoi_storage_move_conversation_json(storage_handle, session, "assistant-b"),
                     "move_conversation(b)");
            check(contains(moved, "\"assistant\":\"assistant-b\""), "移动回显新归属", moved);
            nmoi_string_free(moved);

            char *in_b =
                take(nmoi_storage_list_conversations_json(storage_handle, 0, 0, "assistant-b"),
                     "list(assistant-b)");
            check(contains(in_b, session), "移到 b 之后 b 的列表里有它", NULL);
            check(contains(in_b, "\"returned\":1"), "b 只有这一条", in_b);
            nmoi_string_free(in_b);

            char *in_a =
                take(nmoi_storage_list_conversations_json(storage_handle, 0, 0, "default"),
                     "list(default)");
            check(contains(in_a, "\"returned\":0"), "default 的列表里没了", in_a);
            nmoi_string_free(in_a);

            char *counted = take(
                nmoi_storage_count_conversations_of_json(storage_handle, "assistant-b"),
                "count(assistant-b)");
            check(contains(counted, "\"count\":1"), "b 底下 1 个会话", counted);
            nmoi_string_free(counted);

            /* 挪回去：后面的用例还按 default 找这条会话。挪回去也不许动 updated_at。 */
            char *back = take(
                nmoi_storage_move_conversation_json(storage_handle, session, "default"),
                "move(回 default)");
            nmoi_string_free(back);

            char *blank = take_error(
                nmoi_storage_move_conversation_json(storage_handle, session, "  "),
                "move(空 id)");
            check(is_error(blank), "移到空 id 是错误，不静默成功", blank);
            nmoi_string_free(blank);
        }

        /* 上下文窗口面板的分段用量（P6.4）：八行恒在，0 行也给——
         * 图例位置不该随数据跳动，UI 不该自己维护中文词表。 */
        {
            char *breakdown =
                take(nmoi_context_breakdown_json(storage_handle, session, NULL), "breakdown");
            check(is_ok(breakdown) && balanced(breakdown), "breakdown 是配平的 JSON", breakdown);
            check(count_of(breakdown, "\"key\":") == 8, "恒为 8 行（含 0 行）", breakdown);
            check(contains(breakdown, "\"label\":\"历史消息\""), "第一行是历史消息", NULL);
            check(contains(breakdown, "\"label\":\"草稿\""), "最后一行是草稿", NULL);
            check(row_has_field(breakdown, "历史消息", "items", 3), "三条消息都进了历史那一行",
                  breakdown);
            check(contains(breakdown, "\"estimated\":true"), "估算是估算，得标出来", NULL);
            check(contains(breakdown, "\"model\":\"c-smoke-model\""),
                  "breakdown 说清按哪个模型算的", breakdown);
            check(contains(breakdown, "\"level\":\"l1\""),
                  "有模型名就走 L1（默认编译带 bpe）", breakdown);
            check(contains(breakdown, "\"total\":"), "给了总量", NULL);
            nmoi_string_free(breakdown);

            /* extra：库里没有的那几行由调用方说。放进 0 也算一条——
             * "注入了但是 0"和"没注入"在面板上不是一回事。 */
            char *extra =
                take(nmoi_context_breakdown_json(
                         storage_handle, session,
                         "{\"system_prompt\":100,\"skills\":20,\"draft\":7,"
                         "\"tool_definitions\":[{\"name\":\"read_file\",\"tokens\":30},"
                         "{\"name\":\"mcp__fs__read\",\"tokens\":40}]}"),
                     "breakdown(extra)");
            check(row_has_field(extra, "系统提示词", "tokens", 100), "系统提示词按调用方给的数算",
                  extra);
            check(row_has_field(extra, "技能", "tokens", 20), "技能行", NULL);
            check(row_has_field(extra, "草稿", "tokens", 7), "草稿行", NULL);
            check(row_has_field(extra, "内置工具", "tokens", 30), "普通工具名进内置工具", NULL);
            check(row_has_field(extra, "MCP 工具", "tokens", 40), "mcp__ 开头的进 MCP 行", NULL);
            nmoi_string_free(extra);

            /* 自己也算 history 就是双倍。宁可报错，也不要默默多一行。 */
            char *bad_extra = take_error(
                nmoi_context_breakdown_json(storage_handle, session, "{\"history\":1}"),
                "breakdown(重复算 history)");
            check(is_error(bad_extra) && contains(bad_extra, "\"kind\":\"bad_argument\""),
                  "未知字段被拒绝而不是静默忽略", bad_extra);
            nmoi_string_free(bad_extra);
        }

        char backup_path[320];
        join_path(backup_path, sizeof(backup_path), work_dir, "backup.sqlite3");
        char *backup = take(nmoi_storage_backup_json(storage_handle, backup_path), "backup");
        check(contains(backup, "\"verified\":true"), "备份验证通过", backup);
        nmoi_string_free(backup);

        /* 设置文档（schema v3 / D86）：四类文档共用五个动词。 */
        {
            char *put = take(nmoi_settings_put_json(storage_handle, "assistant", "as_1",
                                                    "{\"name\":\"写代码\",\"chat\":"
                                                    "{\"system_prompt\":\"你是写代码的\"}}",
                                                    1700000000000),
                             "settings_put(assistant)");
            check(is_ok(put) && balanced(put), "put 是配平的成功信封", put);
            check(contains(put, "\"doc_version\":1"), "put 报出文档版本", NULL);
            nmoi_string_free(put);

            char *got = take(nmoi_settings_get_json(storage_handle, "assistant", "as_1",
                                                    1700000001000),
                             "settings_get(assistant)");
            check(contains(got, "\"state\":\"ready\""), "读回来是 ready", got);
            check(contains(got, "写代码"), "doc 原样回来了", NULL);
            nmoi_string_free(got);

            char *missing =
                take(nmoi_settings_get_json(storage_handle, "assistant", "as_none", 1700000001000),
                     "settings_get(不存在)");
            check(contains(missing, "\"state\":\"missing\""),
                  "不存在是 missing 而不是错误", missing);
            nmoi_string_free(missing);

            char *list =
                take(nmoi_settings_list_json(storage_handle, "assistant", 0), "settings_list");
            check(contains(list, "\"returned\":1"), "列表里有一份", list);
            check(contains(list, "\"readable\":true"), "读得出来", NULL);
            check(contains(list, "\"truncated\":false"), "没截断", NULL);
            nmoi_string_free(list);

            /* 不是对象就该被挡在写入口，别让它进库 */
            char *not_object =
                take_error(nmoi_settings_put_json(storage_handle, "assistant", "as_2", "[1,2,3]",
                                                  1700000002000),
                           "settings_put(数组)");
            check(contains(not_object, "\"kind\":\"bad_argument\""), "非对象被拒绝", not_object);
            nmoi_string_free(not_object);

            char *empty_key =
                take_error(nmoi_settings_put_json(storage_handle, "assistant", "", "{}",
                                                  1700000002000),
                           "settings_put(空 key)");
            check(contains(empty_key, "\"kind\":\"bad_argument\""), "空 key 被拒绝", empty_key);
            nmoi_string_free(empty_key);

            char *bad_scope =
                take_error(nmoi_settings_get_json(storage_handle, "assistants", "as_1", 0),
                           "settings_get(错的 scope)");
            check(contains(bad_scope, "\"kind\":\"bad_argument\""), "scope 拼错给 bad_argument",
                  bad_scope);
            check(contains(bad_scope, "conversation"), "错误里列出合法 scope", NULL);
            nmoi_string_free(bad_scope);

            /* ---- 预设（0.14t / D178）---------------------------------- */
            {
                const char *assets =
                    "{\"presets\":[{\"id\":\"tool-usage\",\"version\":1,"
                    "\"title\":\"工具用法\",\"summary\":\"\","
                    "\"applies_to\":[\"read_file\"],"
                    "\"entries\":[{\"id\":\"read-first\",\"title\":\"先读再改\","
                    "\"content\":\"改文件前先读一遍\"}]}]}";

                char *list = take(nmoi_presets_json(storage_handle, assets), "presets");
                check(contains(list, "\"total\":1"), "预设列表有一条", list);
                check(contains(list, "\"state\":\"follow\""), "没改过是 follow", NULL);
                check(contains(list, "\"upstream_hash\":"), "给出上游摘要（存档用）", NULL);
                check(contains(list, "\"summary_line\":"), "带一句汇总", NULL);
                nmoi_string_free(list);

                /* 用户改过一条，但 `base_hash` 写了个对不上的值。
                 * 按 D178 的判据（`preset::state`）：base_hash 对不上 → **上游也算变了**，
                 * 于是一边"本地改过"、一边"上游变了" = 冲突。这不是 bug，是"绝不自动覆盖"那条：
                 * 记不清自己是从哪一版改的，就只能请人来定。 */
                char *doc =
                    "{\"base_id\":\"tool-usage/read-first\",\"base_version\":1,"
                    "\"base_hash\":\"deadbeef\",\"content\":\"我自己的写法\","
                    "\"disabled\":false}";
                char *put = take(nmoi_settings_put_json(storage_handle, "preset",
                                                        "tool-usage/read-first", doc,
                                                        1700000003000),
                                 "settings_put(preset)");
                check(is_ok(put), "预设文档能写进 settings", put);
                nmoi_string_free(put);

                char *edited = take(nmoi_presets_json(storage_handle, assets), "presets(改过)");
                check(contains(edited, "\"state\":\"conflict\""),
                      "base 对不上 → 冲突（不替用户决定）", edited);
                check(contains(edited, "我自己的写法"), "生效正文是本地那份", NULL);
                nmoi_string_free(edited);

                char *inject_declared = take(
                    nmoi_presets_inject_json(storage_handle, assets, "[\"read_file\"]"),
                    "presets_inject(声明了 read_file)");
                check(contains(inject_declared, "\"chars\":"), "注入段给出字数", NULL);
                check(contains(inject_declared, "我自己的写法"), "注入的是本地那份", NULL);
                check(contains(inject_declared, "\"preset\":\"tool-usage\""), "报出注入了哪一份",
                      NULL);
                nmoi_string_free(inject_declared);

                char *inject_none = take(nmoi_presets_inject_json(storage_handle, assets, "[]"),
                                         "presets_inject(一个工具都没开)");
                check(contains(inject_none, "\"chars\":0"), "工具没开就一个字都不注入",
                      inject_none);
                nmoi_string_free(inject_none);

                char *bad_assets =
                    take_error(nmoi_presets_json(storage_handle, "{\"presets\":{}}"),
                               "presets(assets 形状错)");
                check(contains(bad_assets, "\"kind\":\"bad_argument\"")
                          && contains(bad_assets, "presets"),
                      "assets 形状错给 bad_argument 并点名", bad_assets);
                nmoi_string_free(bad_assets);

                (void)take(nmoi_settings_delete_json(storage_handle, "preset",
                                                     "tool-usage/read-first"),
                           "settings_delete(preset)");
            }

            char *deleted =
                take(nmoi_settings_delete_json(storage_handle, "assistant", "as_1"),
                     "settings_delete");
            check(contains(deleted, "\"deleted\":true"), "删掉了", deleted);
            nmoi_string_free(deleted);

            char *again_del =
                take(nmoi_settings_delete_json(storage_handle, "assistant", "as_1"),
                     "settings_delete(重复)");
            check(contains(again_del, "\"deleted\":false"), "重复删是幂等的", again_del);
            nmoi_string_free(again_del);

            char *after =
                take(nmoi_settings_get_json(storage_handle, "assistant", "as_1", 1700000003000),
                     "settings_get(删后)");
            check(contains(after, "\"state\":\"missing\""), "删完就是 missing", after);
            nmoi_string_free(after);

            /* 一直没出过坏数据，所以修复记录应该是空的 */
            char *repairs = take(nmoi_settings_repairs_json(storage_handle, 0), "settings_repairs");
            check(contains(repairs, "\"returned\":0"), "没有坏数据时修复记录为空", repairs);
            nmoi_string_free(repairs);
        }

        char *closed = take(nmoi_storage_close_json(storage_handle), "storage_close");
        check(contains(closed, "\"closed\":true"), "关闭成功", closed);
        nmoi_string_free(closed);

        char *again = take(nmoi_storage_close_json(storage_handle), "storage_close(重复)");
        check(contains(again, "\"closed\":false"), "重复关闭是幂等的", again);
        nmoi_string_free(again);

        char *stale = take_error(nmoi_storage_health_json(storage_handle), "health(已关闭)");
        check(is_error(stale) && contains(stale, "\"kind\":\"invalid_handle\""),
              "旧句柄得到 invalid_handle 而不是崩", stale);
        nmoi_string_free(stale);
    }

    /* ---- 4. 工作区面 ---- */
    printf("[workspace]\n");
    uint64_t workspace_handle = 0;
    {
        char *opened = take(nmoi_workspace_open_json(work_dir), "workspace_open");
        workspace_handle = extract_u64(opened, "\"handle\":");
        check(workspace_handle > 0, "workspace handle 是正整数", opened);
        nmoi_string_free(opened);

        char *wrote = take(nmoi_workspace_write_json(workspace_handle, "note.txt",
                                                    "alpha\nbeta\ngamma\n"),
                           "workspace_write");
        check(contains(wrote, "\"bytes\":17"), "写入字节数对得上", wrote);
        nmoi_string_free(wrote);

        char *read = take(nmoi_workspace_read_json(workspace_handle, "note.txt", 0, 0, 0),
                          "workspace_read");
        check(contains(read, "beta"), "读回来有刚写的行", read);
        check(contains(read, "\"truncated\":false"), "小文件不截断", NULL);
        check(contains(read, "\"total_lines\":3"), "行数对得上", NULL);
        nmoi_string_free(read);

        char *edited =
            take(nmoi_workspace_edit_json(workspace_handle, "note.txt", "beta", "BETA", 0),
                 "workspace_edit");
        check(contains(edited, "\"replacements\":1"), "替换一处", edited);
        nmoi_string_free(edited);

        char *reread = take(nmoi_workspace_read_json(workspace_handle, "note.txt", 0, 0, 0),
                            "workspace_read(改后)");
        check(contains(reread, "BETA"), "改完立刻读得到", reread);
        nmoi_string_free(reread);

        char *stat = take(nmoi_workspace_stat_json(workspace_handle, "note.txt"), "workspace_stat");
        check(contains(stat, "\"is_file\":true"), "stat 认得是文件", stat);
        nmoi_string_free(stat);

        char *list = take(nmoi_workspace_list_json(workspace_handle, "", 0, 0, 0), "workspace_list");
        check(contains(list, "note.txt"), "列出 note.txt", list);
        /* 备份的临时库会留下 `.tmp-wal` / `.tmp-shm`（rename 只搬主文件）。
         * 这一条是跑这个程序时抓到的真 bug，修在 crates/storage/src/backup.rs。 */
        check(!contains(list, ".tmp"), "备份没在目录里留下 .tmp 残骸", list);
        nmoi_string_free(list);

        char *missing = take_error(nmoi_workspace_read_json(workspace_handle, "nope.txt", 0, 0, 0),
                             "workspace_read(不存在)");
        check(is_error(missing) && contains(missing, "\"kind\":\"workspace\""),
              "缺文件给 workspace 错误", missing);
        nmoi_string_free(missing);

        char *escape = take_error(nmoi_workspace_read_json(workspace_handle, "../etc/passwd", 0, 0, 0),
                            "workspace_read(越界)");
        check(is_error(escape), "越界路径被拒", escape);
        nmoi_string_free(escape);
    }

    /* ---- 5. 工具面 ---- */
    printf("[tools]\n");
    {
        char *names = take(nmoi_tool_names_json(), "tool_names");
        check(contains(names, "read_file") && contains(names, "write_file"),
              "默认工具有 read_file / write_file", names);
        nmoi_string_free(names);

        char *specs = take(nmoi_tool_specs_json(workspace_handle, 0), "tool_specs(读写)");
        check(contains(specs, "\"workspace_bound\":true"), "报告工作区已绑", NULL);
        check(contains(specs, "\"name\":\"write_file\""), "读写绑定下会写声明", NULL);
        nmoi_string_free(specs);

        char *readonly = take(nmoi_tool_specs_json(workspace_handle, 1), "tool_specs(只读)");
        check(!contains(readonly, "\"name\":\"write_file\""), "只读绑定下不写声明", readonly);
        check(contains(readonly, "\"name\":\"read_file\""), "只读绑定下仍给读", NULL);
        nmoi_string_free(readonly);

        char *unbound = take(nmoi_tool_specs_json(0, 0), "tool_specs(未绑)");
        check(contains(unbound, "\"workspace_bound\":false"), "报告没绑工作区", unbound);
        check(contains(unbound, "\"count\":0"), "没绑工作区时声明表是空的（门控在前）", NULL);
        nmoi_string_free(unbound);

        char *ok = take(nmoi_tool_invoke_json(workspace_handle, "read_file",
                                              "{\"path\":\"note.txt\"}"),
                        "tool_invoke(read_file)");
        check(contains(ok, "\"status\":\"ok\""), "工具调用成功", ok);
        check(contains(ok, "BETA"), "工具读到的是刚才改过的内容", NULL);
        nmoi_string_free(ok);

        char *denied = take(nmoi_tool_invoke_json(0, "read_file", "{\"path\":\"note.txt\"}"),
                            "tool_invoke(未绑)");
        check(contains(denied, "\"status\":\"denied\""), "未绑工作区被门控挡住", denied);
        check(contains(denied, "\"capability\":\"workspace_read\""), "拒绝原因说清是哪项能力",
              NULL);
        nmoi_string_free(denied);

        char *unknown = take(nmoi_tool_invoke_json(workspace_handle, "no_such_tool", "{}"),
                             "tool_invoke(未知)");
        check(contains(unknown, "\"status\":\"failed\""), "未知工具是 failed 不是崩溃", unknown);
        nmoi_string_free(unknown);

        char *bad_json = take(nmoi_tool_invoke_json(workspace_handle, "read_file", "{not json"),
                              "tool_invoke(坏 JSON)");
        check(contains(bad_json, "\"status\":\"failed\""), "坏参数也是 failed 结果", bad_json);
        nmoi_string_free(bad_json);
    }

    /* ---- 6. turn 面（F2）---- */
    printf("[turn]\n");
    {
        char db_path[320];
        join_path(db_path, sizeof(db_path), work_dir, "turn.sqlite3");
        char *opened = take(nmoi_storage_open_json(db_path), "storage_open(turn)");
        const uint64_t sh = extract_u64(opened, "\"handle\":");
        nmoi_string_free(opened);
        check(sh > 0, "turn 用的库打开了", NULL);

        char turn_session[64] = {0};
        char *created =
            take(nmoi_storage_create_conversation_json(sh, "turn smoke", "default"),
                 "create(turn)");
        check(extract_string(created, "\"session\":\"", turn_session, sizeof(turn_session)),
              "turn 用的会话建好了", created);
        nmoi_string_free(created);

        /* 6.1 没在跑的时候取消是空操作，不能"预存"一个吃掉后面某一轮 */
        char *noop = take(nmoi_turn_cancel_json(turn_session), "cancel(空闲)");
        check(contains(noop, "\"running\":false"), "空闲时取消是空操作", noop);
        check(contains(noop, "\"cancelled\":false"), "空闲时 cancelled 为 false", NULL);
        nmoi_string_free(noop);

        /* 6.2 配置缺字段：错要说清是哪个字段 */
        char config[768];
        snprintf(config, sizeof(config),
                 "{\"storage\":%llu,\"session\":\"%s\","
                 "\"provider\":{\"kind\":\"openai_compatible\","
                 "\"base_url\":\"http://127.0.0.1:1/v1\"},\"user_text\":\"hi\"}",
                 (unsigned long long)sh, turn_session);
        char *broken = take_error(nmoi_turn_run_json(config), "turn(缺 model)");
        check(contains(broken, "`model`"), "缺字段时指明字段名", broken);
        nmoi_string_free(broken);

        /* 6.3 连不上：干净的失败，而且句柄必须还回来（D60 的 guard 真的跑到） */
        snprintf(config, sizeof(config),
                 "{\"storage\":%llu,\"session\":\"%s\","
                 "\"provider\":{\"kind\":\"openai_compatible\","
                 "\"base_url\":\"http://127.0.0.1:1/v1\"},"
                 "\"model\":\"m\",\"user_text\":\"hi\"}",
                 (unsigned long long)sh, turn_session);
        char *failed = take_error(nmoi_turn_run_json(config), "turn(连不上)");
        check(contains(failed, "\"kind\":\"turn\""), "模型调用失败报 turn", failed);
        nmoi_string_free(failed);

        char *health = take(nmoi_storage_health_json(sh), "health(turn 之后)");
        check(contains(health, "\"integrity_ok\":true"), "失败之后句柄没卡在 busy", health);
        nmoi_string_free(health);

        char *units =
            take(nmoi_storage_load_units_json(sh, turn_session, 0), "load_units(turn 之后)");
        check(contains(units, "\"units_total\":1"), "失败的轮次只落了用户那条", units);
        nmoi_string_free(units);

        /* 6.4 真端点：给了 NMOI_SMOKE_BASE / NMOI_SMOKE_MODEL 才跑（CI 里没有）
         *
         * 这一段是这一层唯一的端到端证据：从 C 进去，模型真的读了文件，
         * 落库的单元、工具记录、正文都是真的。没给端点就明说跳过，不假装验过。 */
        const char *base = getenv("NMOI_SMOKE_BASE");
        const char *model = getenv("NMOI_SMOKE_MODEL");
        if (base != NULL && model != NULL && base[0] != '\0' && model[0] != '\0') {
            printf("  (真端点：%s %s)\n", base, model);
            snprintf(config, sizeof(config),
                     "{\"storage\":%llu,\"session\":\"%s\",\"workspace\":%llu,"
                     "\"provider\":{\"id\":\"c-smoke\",\"kind\":\"openai_compatible\","
                     "\"base_url\":\"%s\"},"
                     "\"model\":\"%s\",\"user_text\":\"先读 note.txt，确认第二行的内容，"
                     "然后用一句话回答第二行是什么。\","
                     "\"now_ms\":1700000003000}",
                     (unsigned long long)sh, turn_session,
                     (unsigned long long)workspace_handle, base, model);

            char *live = take(nmoi_turn_run_json(config), "turn(真端点)");
            check(contains(live, "\"finished\":true"), "这一轮正常收尾", live);
            check(contains(live, "\"cancelled\":false"), "没有被取消", NULL);
            check(contains(live, "\"name\":\"read_file\""), "模型真的读了文件", NULL);
            check(contains(live, "\"assistant\":{"), "有一句正文落库", NULL);
            nmoi_string_free(live);

            char *after =
                take(nmoi_storage_load_units_json(sh, turn_session, 0), "load_units(真端点之后)");
            check(extract_u64(after, "\"units_total\":") >= 3,
                  "用户 + 工具对 + 助手，至少三条在库里", after);
            check(contains(after, "\"kind\":\"tool_pair\""), "工具单元成对落库", NULL);
            check(contains(after, "\"role\":\"tool\""), "工具结果也落了", NULL);
            nmoi_string_free(after);
        } else {
            printf("  (跳过真端点轮次：没给 NMOI_SMOKE_BASE / NMOI_SMOKE_MODEL)\n");
        }

        char *closed = take(nmoi_storage_close_json(sh), "storage_close(turn)");
        nmoi_string_free(closed);
    }

    /* ---- 7. 句柄失效 ---- */
    /* 图标面（D105）：包在磁盘上，规则由调用方传进来。用仓库里那份原版包验。 */
    {
        printf("[icons]\n");
        char cwd[512];
        if (getcwd(cwd, sizeof(cwd)) == NULL) {
            check(0, "getcwd 失败", NULL);
        } else {
            char packs_root[640];
            join_path(packs_root, sizeof(packs_root), cwd, "data/icon-packs");

            char *opened = take(nmoi_icons_open_json(packs_root), "icons_open");
            check(contains(opened, "\"origin\":\"original\""), "原版包被认出来是 original", opened);
            uint64_t icons_handle = extract_u64(opened, "\"handle\":");
            check(icons_handle > 0, "icons_open 返回正整数 handle", NULL);
            nmoi_string_free(opened);

            char *list = take(nmoi_icons_list_json(icons_handle), "icons_list");
            check(contains(list, "\"pack_id\":\"nmoi-original\""), "列表里有原版包", list);
            check(contains(list, "\"removable\":false"), "原版不可删（removable=false）", NULL);
            nmoi_string_free(list);

            /* 没有规则时走"模型名首段"：deepseek-chat → deepseek */
            char *by_prefix = take(
                nmoi_icons_resolve_json(icons_handle, NULL,
                                        "{\"model\":\"deepseek-chat\"}"),
                "icons_resolve(首段)");
            check(contains(by_prefix, "\"key\":\"deepseek\""), "按模型名首段命中 deepseek",
                  by_prefix);
            check(contains(by_prefix, "\"via\":\"model_prefix\""), "说了是哪一级命中的", NULL);
            nmoi_string_free(by_prefix);

            /* 谁都不认 → 首字母方块，界面永远有话可说 */
            char *by_mono = take(
                nmoi_icons_resolve_json(icons_handle, NULL, "{\"model\":\"zzz-unknown-9\"}"),
                "icons_resolve(退化)");
            check(contains(by_mono, "\"icon\":null"), "没命中时 icon 为 null", by_mono);
            check(contains(by_mono, "\"via\":\"monogram\""), "退化到首字母方块", NULL);
            check(contains(by_mono, "\"letter\":\"Z\""), "首字母取到了 Z", NULL);
            nmoi_string_free(by_mono);

            /* 原版不可删：返回 refused，而不是"删了但没删掉" */
            char *refused =
                take_error(nmoi_icons_delete_json(icons_handle, "nmoi-original"), "icons_delete(原版)");
            check(is_error(refused) && contains(refused, "\"kind\":\"refused\""),
                  "删除原版被拒绝", refused);
            nmoi_string_free(refused);

            char *closed = take(nmoi_icons_close_json(icons_handle), "icons_close");
            check(contains(closed, "\"closed\":true"), "图标句柄能关", NULL);
            nmoi_string_free(closed);
        }
    }

    printf("[handles]\n");
    {
        char *closed = take(nmoi_workspace_close_json(workspace_handle), "workspace_close");
        check(contains(closed, "\"closed\":true"), "工作区关闭", closed);
        nmoi_string_free(closed);

        char *stale = take_error(nmoi_tool_invoke_json(workspace_handle, "read_file",
                                                       "{\"path\":\"note.txt\"}"),
                           "tool_invoke(已关闭)");
        check(is_error(stale) && contains(stale, "\"kind\":\"invalid_handle\""),
              "旧工作区句柄给 invalid_handle", stale);
        nmoi_string_free(stale);

        /* 类型串用：拿工作区的 id 当存储句柄用，也必须是一条干净的错。 */
        char *wrong_kind = take_error(nmoi_storage_health_json(1), "health(错误类型的句柄)");
        check(is_error(wrong_kind), "串用句柄得到错误而不是 UB", wrong_kind);
        nmoi_string_free(wrong_kind);
    }

    printf("== 检查 %d 项，失败 %d 项（临时目录 %s）==\n", checks, failures, work_dir);
    return failures == 0 ? 0 : 1;
}
