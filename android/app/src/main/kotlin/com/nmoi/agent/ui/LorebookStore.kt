package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 世界书的落盘（D280 / D281 第 1 批）：一个文件，**多册**。
 *
 * 为什么多册：一册 = 一组设定（主 AGENT / 子 AGENT / 界面）。单册装不下这个形状，
 * 换一组就得整份重导。老的单册文件（`{"type":"lorebook","data":{…}}`）读进来当一册，
 * 不丢数据——上一版存的就是那个形状。
 *
 * 为什么还留在文件里、不进库：它是**用户导入的一份配置**，换设备时重新导一份，
 * 而不是指望备份带着它。
 *
 * 这一层只做"树上的增删改"，不做匹配语义：位置/角色认不认得、要不要注入，
 * 判定在 core（`lorebook::Library` / `plan`），那边会点名报错。
 */
internal object LorebookStore {
    private const val FILE = "lorebook.json"

    const val VERSION = 2
    const val KIND = "lorebooks"

    /** 位置：值与 core 的大写蛇形一致，第二个是界面上的短标签。 */
    val POSITIONS = listOf(
        "BEFORE_SYSTEM_PROMPT" to "系统提示前",
        "AFTER_SYSTEM_PROMPT" to "系统提示后",
        "TOP_OF_CHAT" to "对话顶部",
        "AT_DEPTH" to "倒数第 N 条前",
        "BOTTOM_OF_CHAT" to "对话底部",
    )

    val ROLES = listOf(
        "SYSTEM" to "系统",
        "USER" to "用户",
        "ASSISTANT" to "助手",
    )

    fun file(context: Context): File = File(context.filesDir, FILE)

    /** 读出来；没有或读不出来就是空串（界面按"还没导入"处理）。 */
    fun text(context: Context): String =
        runCatching { file(context).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")

    fun library(context: Context): JSONObject = parse(text(context))

    fun save(context: Context, root: JSONObject): Result<Unit> = runCatching {
        file(context).writeText(root.toString(2))
    }

    /** 空库：还没有文件、或者文件读不出来时用。 */
    fun empty(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("type", KIND)
        .put("books", JSONArray())

    /**
     * 读一份文本：有 `books` 就当多册，有 `data` 就当老的单册包成一册。
     *
     * 判据是**形状**，不是 `type` 字段——用户手改过 `type` 的文件也该读得出来。
     * 读不出来给空库，让界面说"读不出来"，而不是静默当成"没有世界书"。
     */
    fun parse(text: String): JSONObject {
        if (text.isBlank()) return empty()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return empty()
        val books = root.optJSONArray("books")
        if (books != null) {
            for (index in 0 until books.length()) {
                books.optJSONObject(index)?.let { fillBook(it, index) }
            }
            root.put("version", VERSION)
            root.put("type", KIND)
            return root
        }
        val data = root.optJSONObject("data") ?: return empty()
        fillBook(data, 0)
        return empty().put("books", JSONArray().put(data))
    }

    /** 结构上缺什么补什么（id / 名字 / 启用 / 条目数组）；不碰位置与角色的取值。 */
    private fun fillBook(book: JSONObject, index: Int) {
        if (book.optString("id").isBlank()) book.put("id", "b${index + 1}")
        if (book.optString("name").isBlank()) book.put("name", "第 ${index + 1} 册")
        if (!book.has("enabled")) book.put("enabled", true)
        if (book.optJSONArray("entries") == null) book.put("entries", JSONArray())
        val entries = book.optJSONArray("entries") ?: return
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("id").isBlank()) entry.put("id", "e${i + 1}")
            if (!entry.has("enabled")) entry.put("enabled", true)
            if (!entry.has("position")) entry.put("position", "AFTER_SYSTEM_PROMPT")
            if (!entry.has("role")) entry.put("role", "USER")
            if (entry.optJSONArray("keywords") == null) entry.put("keywords", JSONArray())
        }
    }

    /** 内置册的 id 固定：导入按 id 覆盖、恢复默认都认它。 */
    const val BUILTIN_ID = "builtin"
    const val BUILTIN_NAME = "内置提示词"

    fun isBuiltin(bookId: String): Boolean = bookId == BUILTIN_ID

    fun books(root: JSONObject): JSONArray = root.optJSONArray("books") ?: JSONArray()

    fun book(root: JSONObject, bookId: String): JSONObject? {
        val books = books(root)
        for (index in 0 until books.length()) {
            val book = books.optJSONObject(index) ?: continue
            if (book.optString("id") == bookId) return book
        }
        return null
    }

    fun entries(book: JSONObject): JSONArray = book.optJSONArray("entries") ?: JSONArray()

    fun entry(book: JSONObject, entryId: String): JSONObject? {
        val entries = entries(book)
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("id") == entryId) return entry
        }
        return null
    }

    fun setBookEnabled(root: JSONObject, bookId: String, on: Boolean): JSONObject {
        book(root, bookId)?.put("enabled", on)
        return root
    }

    fun setEntryEnabled(root: JSONObject, bookId: String, entryId: String, on: Boolean): JSONObject {
        book(root, bookId)?.let { entry(it, entryId)?.put("enabled", on) }
        return root
    }

    fun addBook(root: JSONObject, name: String, note: String): JSONObject {
        val books = books(root)
        books.put(
            JSONObject()
                .put("id", nextId(books, "b"))
                .put("name", name.trim().ifBlank { "新世界书" })
                .put("note", note.trim())
                .put("enabled", true)
                .put("entries", JSONArray()),
        )
        return root.put("books", books)
    }

    /** 内置册在文件里只存"自己加的那几条"；内置条目来自预设包，不落盘。 */
    fun manualEntries(root: JSONObject): JSONArray =
        book(root, BUILTIN_ID)?.let { entries(it) } ?: JSONArray()

    /** 内置册不存在就建一个空壳（id 固定，名字固定）。 */
    fun ensureBuiltinBook(root: JSONObject): JSONObject {
        if (book(root, BUILTIN_ID) != null) return root
        val books = books(root)
        books.put(
            JSONObject()
                .put("id", BUILTIN_ID)
                .put("name", BUILTIN_NAME)
                .put("note", "")
                .put("enabled", true)
                .put("entries", JSONArray()),
        )
        return root.put("books", books)
    }

    /** 往内置册里加一条自制条目（内置条目不能加，那是预设包的东西）。 */
    fun addManualEntry(root: JSONObject): JSONObject =
        addEntry(ensureBuiltinBook(root), BUILTIN_ID)

    /**
     * 内置册按 id 补缺（§10.2）：文件里没有的保留、同 id 的覆盖正文、自制条目原样留着。
     *
     * 它跟别的册不一样：别的册是"整册替换"，内置册**不能减法**——那是应用自带的那几条，
     * 少一条就等于悄悄改了行为。
     */
    fun mergeIntoBuiltin(root: JSONObject, incoming: JSONObject): JSONObject {
        val target = ensureBuiltinBook(root)
        val targetBook = book(target, BUILTIN_ID) ?: return target
        val mine = entries(targetBook)
        val theirs = entries(incoming)
        for (index in 0 until theirs.length()) {
            val entry = theirs.optJSONObject(index) ?: continue
            val id = entry.optString("id")
            var replaced = false
            for (slot in 0 until mine.length()) {
                val old = mine.optJSONObject(slot) ?: continue
                if (old.optString("id") == id) {
                    mine.put(slot, entry)
                    replaced = true
                    break
                }
            }
            if (!replaced) mine.put(entry)
        }
        targetBook.put("entries", mine)
        return target
    }

    /**
     * 这一轮真正参与注入的册：`ids == null` 表示没设置过（全部启用册都算）。
     *
     * 册级 `enabled` 只管"要不要出现在「＋」的清单里"，真正生效的是助手身上这一份
     * （用户 2026-11-08 定的三层语义）。
     */
    fun filterBy(root: JSONObject, ids: List<String>?): JSONObject {
        if (ids == null) return root
        val keep = ids.toSet()
        val books = books(root)
        val kept = JSONArray()
        for (index in 0 until books.length()) {
            val book = books.optJSONObject(index) ?: continue
            if (book.optString("id") in keep) kept.put(book)
        }
        return JSONObject(root.toString()).put("books", kept)
    }

    /** 「＋」里那份可选清单：只列册级 `enabled` 的（收起的不出现在这里）。 */
    fun selectableBooks(root: JSONObject): List<Pair<String, String>> {
        val books = books(root)
        val out = ArrayList<Pair<String, String>>()
        for (index in 0 until books.length()) {
            val book = books.optJSONObject(index) ?: continue
            if (!book.optBoolean("enabled", true)) continue
            out.add(book.optString("id") to book.optString("name").ifBlank { "未命名" })
        }
        return out
    }

    /** 按 id 放一册进去：同 id 就整册换掉（§10.2），没有就追加。 */
    fun putBookObject(root: JSONObject, book: JSONObject): JSONObject {
        val id = book.optString("id").ifBlank { nextId(books(root), "b") }
        book.put("id", id)
        val books = books(root)
        var replaced = false
        for (index in 0 until books.length()) {
            val old = books.optJSONObject(index) ?: continue
            if (old.optString("id") == id) {
                books.put(index, book)
                replaced = true
                break
            }
        }
        if (!replaced) books.put(book)
        return root.put("books", books)
    }

    /** 导入进来的一册：编号重新发一个，免得和现有的撞上。 */
    fun addBookObject(root: JSONObject, book: JSONObject): JSONObject {
        val books = books(root)
        book.put("id", nextId(books, "b"))
        books.put(book)
        return root.put("books", books)
    }

    fun deleteBook(root: JSONObject, bookId: String): JSONObject {        val books = books(root)
        val kept = JSONArray()
        for (index in 0 until books.length()) {
            val book = books.optJSONObject(index) ?: continue
            if (book.optString("id") != bookId) kept.put(book)
        }
        return root.put("books", kept)
    }

    fun addEntry(root: JSONObject, bookId: String): JSONObject {
        val book = book(root, bookId) ?: return root
        val entries = entries(book)
        entries.put(
            JSONObject()
                .put("id", nextId(entries, "e"))
                .put("name", "新条目")
                .put("enabled", true)
                .put("priority", entries.length())
                .put("position", "AFTER_SYSTEM_PROMPT")
                .put("role", "USER")
                .put("content", "")
                .put("injectDepth", 4)
                .put("scanDepth", 4)
                .put("keywords", JSONArray())
                .put("useRegex", false)
                .put("caseSensitive", false)
                .put("constantActive", false),
        )
        return root
    }

    fun deleteEntry(root: JSONObject, bookId: String, entryId: String): JSONObject {
        val book = book(root, bookId) ?: return root
        val entries = entries(book)
        val kept = JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("id") != entryId) kept.put(entry)
        }
        book.put("entries", kept)
        return root
    }

    /** 条目上的一处改动：`apply` 里改什么就存什么（面板每次停手调一次）。 */
    fun updateEntry(
        root: JSONObject,
        bookId: String,
        entryId: String,
        apply: (JSONObject) -> Unit,
    ): JSONObject {
        book(root, bookId)?.let { entry(it, entryId)?.let(apply) }
        return root
    }

    /** 册行副标题：`6 条 · 3 常驻 · 3 关键词`（停用的另起一句）。 */
    fun summary(book: JSONObject): String {
        val entries = entries(book)
        var constant = 0
        var keyword = 0
        var off = 0
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (!entry.optBoolean("enabled", true)) off++
            if (entry.optBoolean("constantActive", false)) constant++
            else if (entry.optJSONArray("keywords")?.length() ?: 0 > 0) keyword++
        }
        val parts = ArrayList<String>()
        parts.add("${entries.length()} 条")
        if (constant > 0) parts.add("$constant 常驻")
        if (keyword > 0) parts.add("$keyword 关键词")
        if (off > 0) parts.add("$off 已停用")
        return parts.joinToString(" · ")
    }

    fun positionLabel(value: String): String =
        POSITIONS.firstOrNull { it.first.equals(value.trim(), ignoreCase = true) }?.second
            ?: "未知（$value）"

    fun roleLabel(value: String): String =
        ROLES.firstOrNull { it.first.equals(value.trim(), ignoreCase = true) }?.second
            ?: "未知（$value）"

    /** 关键词输入框：逗号（中英文都认）、换行、空格都当分隔。 */
    fun splitKeywords(text: String): JSONArray {
        val array = JSONArray()
        text.split(',', '，', '\n', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { array.put(it) }
        return array
    }

    fun joinKeywords(entry: JSONObject): String {
        val keywords = entry.optJSONArray("keywords") ?: return ""
        val parts = ArrayList<String>()
        for (index in 0 until keywords.length()) {
            keywords.optString(index).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        return parts.joinToString(", ")
    }

    /**
     * 导入前逐条校验：位置/角色认不认得。返回 null = 都能读。
     *
     * 与 core 的 `ImportError` 同一套判据（大写蛇形、大小写不敏感）。这里先拦一道，
     * 是因为"导进去之后下一轮请求才报错"对用户来说等于没导进去。
     */
    fun validate(book: JSONObject): String? {
        val bookName = book.optString("name").ifBlank { "这一册" }
        val entries = entries(book)
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val name = entry.optString("name").ifBlank { "第 ${index + 1} 条" }
            val position = entry.optString("position", "AFTER_SYSTEM_PROMPT")
            if (POSITIONS.none { it.first.equals(position.trim(), ignoreCase = true) }) {
                return "「$bookName」第 ${index + 1} 条（$name）的位置是「$position」：" +
                    "认不出；只收 " + POSITIONS.joinToString(" / ") { it.first }
            }
            val role = entry.optString("role", "USER")
            if (ROLES.none { it.first.equals(role.trim(), ignoreCase = true) }) {
                return "「$bookName」第 ${index + 1} 条（$name）的角色是「$role」：" +
                    "认不出；只收 " + ROLES.joinToString(" / ") { it.first }
            }
        }
        return null
    }

    /** 把一份文本读成若干册（单册文件 = 一册）。位置/角色统一成大写，其余原样保留。 */
    fun importBooks(text: String): Result<List<JSONObject>> = runCatching {
        val root = JSONObject(text)
        val raw = root.optJSONArray("books")
        val books = ArrayList<JSONObject>()
        if (raw != null) {
            for (index in 0 until raw.length()) {
                raw.optJSONObject(index)?.let { books.add(it) }
            }
        } else {
            books.add(root.optJSONObject("data") ?: error("这不是世界书文件：找不到 data.entries"))
        }
        if (books.isEmpty()) error("这份文件里一册都没有")
        books.forEachIndexed { index, book ->
            fillBook(book, index)
            validate(book)?.let { error(it) }
            val entries = entries(book)
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                entry.put("position", entry.optString("position").trim().uppercase())
                entry.put("role", entry.optString("role").trim().uppercase())
            }
        }
        books
    }

    /** `b1` / `e7` 这种：取现有的最大编号 +1，删掉中间一册也不会撞号。 */
    private fun nextId(items: JSONArray, prefix: String): String {
        var max = 0
        for (index in 0 until items.length()) {
            val id = items.optJSONObject(index)?.optString("id").orEmpty()
            if (id.startsWith(prefix)) {
                id.removePrefix(prefix).toIntOrNull()?.let { if (it > max) max = it }
            }
        }
        return "$prefix${max + 1}"
    }
}
