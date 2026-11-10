package com.nmoi.agent.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书（D281 第 1 批）：**册 → 条目**两级。
 *
 * 为什么是两级：一册 = 一组设定（主 AGENT / 子 AGENT / 界面）。册行上要能一眼看出
 * 这一册多少条、几条常驻、是不是停用了；点开才是条目，条目上只有"开关"和"位置徽标"。
 *
 * 这一批**不改匹配行为**：位置、角色、关键词、正则、扫描深度、常驻都是 core 已经支持的字段，
 * 这里只是让它们看得见、能改。时序（sticky / cooldown / delay）还没做，所以面板里没有那几格——
 * 摆一个拨了不生效的开关，比不摆更坏。
 */
@Composable
internal fun LorebookScreen(context: Context, databasePath: String, onBack: () -> Unit) {
    var root by remember { mutableStateOf(LorebookStore.library(context)) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var deleting by remember { mutableStateOf<DeleteTarget?>(null) }
    var creating by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<JSONObject>?>(null) }
    var exportTarget by remember { mutableStateOf<JSONObject?>(null) }
    // 内置册的条目来自预设包（§10.1）：不复制数据，这一册是那份词条的视图。
    var presetReload by remember { mutableStateOf(0) }
    val presets by produceState(initialValue = emptyList<PresetView>(), presetReload) {
        value = withContext(Dispatchers.IO) {
            PresetRepository.list(context, databasePath).getOrNull()?.items.orEmpty()
        }
    }
    var presetSheet by remember { mutableStateOf<PresetEntryView?>(null) }
    var bookPicked by remember { mutableStateOf<Set<String>?>(null) }
    var entryPicked by remember { mutableStateOf<EntryPick?>(null) }
    var bulkDelete by remember { mutableStateOf<BulkDelete?>(null) }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    val scope = rememberCoroutineScope()

    // 每次改动都从**当前树**拷一份再改：`JSONObject` 是可变对象，原地改不会触发重组，
    // 界面上就会出现"点了没反应"。拷一份还顺手拿到了要落盘的那份文本。
    fun commit(block: (JSONObject) -> JSONObject) {
        val next = runCatching { block(JSONObject(root.toString())) }.getOrElse { error ->
            notice = "改不动：${error.message ?: error.javaClass.simpleName}"
            return
        }
        root = next
        LorebookStore.save(context, next).onFailure { notice = "存盘失败：${it.message}" }
    }

    fun book(id: String): JSONObject? = LorebookStore.book(root, id)

    fun exitPick() {
        bookPicked = null
        entryPicked = null
    }

    fun enterBookPick(bookId: String) {
        entryPicked = null
        bookPicked = setOf(bookId)
        expanded = emptySet()
    }

    fun enterEntryPick(bookId: String) {
        bookPicked = null
        entryPicked = EntryPick(bookId, emptySet())
        expanded = setOf(bookId)
    }

    val picking = bookPicked != null || entryPicked != null
    val pickedCount = bookPicked?.size ?: entryPicked?.ids?.size ?: 0

    BackHandler(enabled = picking) { exitPick() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            notice = "已取消导入"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                notice = "读不出这个文件"
                return@launch
            }
            LorebookStore.importBooks(text).fold(
                onSuccess = { books -> preview = books },
                onFailure = { notice = "导入失败：${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) {
            notice = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(target.toString(2).toByteArray(Charsets.UTF_8))
                    } ?: error("打不开目标文件")
                }
            }
            notice = result.fold(
                onSuccess = { "已导出 ${target.toString(2).length} 字符" },
                onFailure = { "导出失败：${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    val books = LorebookStore.books(root)

    fun exportBooks(ids: Set<String>) {
        val rows = ArrayList<JSONObject>()
        for (index in 0 until books.length()) {
            val book = books.optJSONObject(index) ?: continue
            if (book.optString("id") in ids) rows.add(JSONObject(book.toString()))
        }
        if (rows.isEmpty()) {
            notice = "勾的那几册不在了"
            return
        }
        exportTarget = if (rows.size == 1) {
            singleBookDocument(rows[0])
        } else {
            JSONObject()
                .put("version", 1)
                .put("type", "lorebook")
                .put("books", JSONArray().also { array -> rows.forEach { array.put(it) } })
        }
        val name = if (rows.size == 1) {
            "${rows[0].optString("name").ifBlank { "lorebook" }}.json"
        } else {
            "世界书-${rows.size}册.json"
        }
        exportLauncher.launch(name)
    }

    val pickableBooks = (0 until books.length()).mapNotNull { index ->
        books.optJSONObject(index)?.optString("id")
    }.filter { it.isNotBlank() && !LorebookStore.isBuiltin(it) }

    fun pickableEntries(rows: JSONArray?): List<String> {
        if (rows == null) return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("id")
        }.filter { it.isNotBlank() }
    }

    fun otherBooks(bookId: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (index in 0 until books.length()) {
            val row = books.optJSONObject(index) ?: continue
            val id = row.optString("id")
            if (id.isBlank() || id == bookId || LorebookStore.isBuiltin(id)) continue
            out.add(id to row.optString("name").ifBlank { "未命名" })
        }
        return out
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack = onBack)
            Text(
                text = "世界书",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
    if (picking) {
        SelectionBar(
            count = pickedCount,
            unit = if (bookPicked != null) "册" else "条",
            allSelected = run {
                val pickedBooks = bookPicked
                val pickedEntries = entryPicked
                when {
                    pickedBooks != null -> pickableBooks.isNotEmpty() && pickableBooks.all { it in pickedBooks }
                    pickedEntries != null -> {
                        val ids = pickableEntries(book(pickedEntries.bookId)?.let { LorebookStore.entries(it) })
                        ids.isNotEmpty() && ids.all { it in pickedEntries.ids }
                    }
                    else -> false
                }
            },
            onToggleAll = {
                val picked = bookPicked
                val rows = entryPicked
                when {
                    picked != null -> bookPicked = if (pickableBooks.all { it in picked }) {
                        emptySet()
                    } else {
                        pickableBooks.toSet()
                    }
                    rows != null -> {
                        val ids = pickableEntries(book(rows.bookId)?.let { LorebookStore.entries(it) })
                        entryPicked = rows.copy(ids = if (ids.all { it in rows.ids }) emptySet() else ids.toSet())
                    }
                }
            },
            onClose = { exitPick() },
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!picking) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChipButton("导入 JSON", primary = false) { pickFile.launch("application/json") }
                    ChipButton("新建一册", primary = true) { creating = true }
                }
            }

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            if (books.length() == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (LorebookStore.text(context).isNotBlank()) {
                            "这份文件读不出来（不是合法的 JSON）：先导一份新的，或者清掉它"
                        } else {
                            "还没有世界书。导入一份 kelivo / RikkaHub 导出的 JSON，或者先建一册手写条目"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    ChipButton("导入 JSON", primary = true) { pickFile.launch("application/json") }
                }
            }

            BuiltinCard(
                presets = presets,
                manual = LorebookStore.manualEntries(root),
                expanded = expanded.contains(LorebookStore.BUILTIN_ID),
                onToggleExpand = {
                    expanded = if (expanded.contains(LorebookStore.BUILTIN_ID)) {
                        expanded - LorebookStore.BUILTIN_ID
                    } else {
                        expanded + LorebookStore.BUILTIN_ID
                    }
                },
                onOpenPreset = { presetSheet = it },
                onAddManual = {
                    commit { LorebookStore.addManualEntry(it) }
                    expanded = expanded + LorebookStore.BUILTIN_ID
                },
                onEditManual = { entryId -> editing = EditTarget(LorebookStore.BUILTIN_ID, entryId) },
                onToggleManual = { entryId, on ->
                    commit { LorebookStore.setEntryEnabled(it, LorebookStore.BUILTIN_ID, entryId, on) }
                },
                onDeleteManual = { entryId ->
                    deleting = DeleteTarget(LorebookStore.BUILTIN_ID, entryId)
                },
                locked = picking,
                onReset = {
                    scope.launch {
                        var reset = 0
                        presets.forEach { preset ->
                            preset.entries.filter { it.state != "follow" }.forEach { entry ->
                                PresetRepository.follow(databasePath, preset.id, entry.id)
                                    .onSuccess { reset += 1 }
                            }
                        }
                        presetReload += 1
                        notice = if (reset == 0) {
                            "内置条目本来就是默认的"
                        } else {
                            "已把 $reset 条内置条目恢复成默认"
                        }
                    }
                },
            )

            for (index in 0 until books.length()) {
                val book = books.optJSONObject(index) ?: continue
                val bookId = book.optString("id")
                // 内置册是虚拟的一册（条目来自预设包），不在真册循环里画。
                if (LorebookStore.isBuiltin(bookId)) continue
                BookCard(
                    book = book,
                    expanded = expanded.contains(bookId),
                    onToggleExpand = {
                        expanded = if (expanded.contains(bookId)) {
                            expanded - bookId
                        } else {
                            expanded + bookId
                        }
                    },
                    onToggleEnabled = { on ->
                        commit { LorebookStore.setBookEnabled(it, bookId, on) }
                    },
                    onAddEntry = {
                        commit { LorebookStore.addEntry(it, bookId) }
                        expanded = expanded + bookId
                    },
                    onExport = {
                        exportTarget = singleBookDocument(book)
                        exportLauncher.launch("${book.optString("name").ifBlank { "lorebook" }}.json")
                    },
                    onDelete = { deleting = DeleteTarget(bookId, null) },
                    onEditEntry = { entryId -> editing = EditTarget(bookId, entryId) },
                    onToggleEntry = { entryId, on ->
                        commit { LorebookStore.setEntryEnabled(it, bookId, entryId, on) }
                    },
                    onDeleteEntry = { entryId ->
                        deleting = DeleteTarget(bookId, entryId)
                    },
                    bookSelecting = bookPicked != null,
                    bookSelected = bookPicked?.contains(bookId) == true,
                    onToggleBookPick = {
                        val current = bookPicked ?: emptySet<String>()
                        bookPicked = if (bookId in current) current - bookId else current + bookId
                    },
                    onHold = {
                        if (bookPicked == null && entryPicked == null) enterBookPick(bookId)
                    },
                    entrySelecting = entryPicked?.bookId == bookId,
                    pickedEntries = if (entryPicked?.bookId == bookId) entryPicked?.ids ?: emptySet() else emptySet(),
                    onToggleEntryPick = { entryId ->
                        val current = entryPicked
                        if (current != null && current.bookId == bookId) {
                            entryPicked = current.copy(
                                ids = if (entryId in current.ids) current.ids - entryId else current.ids + entryId,
                            )
                        }
                    },
                    onPickEntries = { enterEntryPick(bookId) },
                    locked = entryPicked != null && entryPicked?.bookId != bookId,
                )
            }

            Text(
                text = "注入规则：常驻条目每轮都进上下文；其余按关键词命中（看最近 N 条消息）。" +
                    "位置决定它插在系统提示前 / 后、对话顶、倒数第几条前、对话底。" +
                    "世界书属于设定，档位再高也不会被截断。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            Spacer(Modifier.height(if (picking) 76.dp else 24.dp))
        }

        if (picking) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                BulkBar(
                    count = pickedCount,
                    emptyLabel = if (bookPicked != null) "还没勾：长按一册开始" else "还没勾：点一条开始",
                ) {
                    if (bookPicked != null) {
                        BulkText(label = "导出", onClick = { exportBooks(bookPicked ?: emptySet<String>()) })
                        Spacer(Modifier.weight(1f))
                        BulkText(
                            label = "删除",
                            danger = true,
                            onClick = { bulkDelete = BulkDelete(bookIds = bookPicked ?: emptySet<String>()) },
                        )
                    } else {
                        val picked = entryPicked
                        BulkText(
                            label = "复制到…",
                            onClick = { picked?.let { moveTarget = MoveTarget(it, move = false) } },
                        )
                        Spacer(Modifier.width(4.dp))
                        BulkText(
                            label = "移动…",
                            onClick = { picked?.let { moveTarget = MoveTarget(it, move = true) } },
                        )
                        Spacer(Modifier.weight(1f))
                        BulkText(
                            label = "删除",
                            danger = true,
                            onClick = {
                                picked?.let {
                                    bulkDelete = BulkDelete(entryBookId = it.bookId, entryIds = it.ids)
                                }
                            },
                        )
                    }
                }
            }
        }

        val target = editing
        if (target != null) {
            val currentBook = book(target.bookId)
            val entry = currentBook?.let { LorebookStore.entry(it, target.entryId) }
            if (currentBook != null && entry != null) {
                EntrySheet(
                    entry = entry,
                    onApply = { fields ->
                        commit { next ->
                            LorebookStore.updateEntry(next, target.bookId, target.entryId) { targetEntry ->
                                val keys = fields.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    targetEntry.put(key, fields.get(key))
                                }
                            }
                        }
                    },
                    onDelete = {
                        editing = null
                        deleting = DeleteTarget(target.bookId, target.entryId)
                    },
                    onDismiss = { editing = null },
                )
            } else {
                LaunchedEffect(target) { editing = null }
            }
        }

        presetSheet?.let { entry ->
            PresetEntrySheet(entry = entry, onDismiss = { presetSheet = null })
        }

        if (creating) {
            NewBookSheet(
                onCreate = { name, note ->
                    commit { LorebookStore.addBook(it, name, note) }
                    creating = false
                },
                onDismiss = { creating = false },
            )
        }

        preview?.let { incoming ->
            ImportPreviewSheet(
                books = incoming,
                existing = existingIds(root),
                onImport = {
                    scope.launch {
                        // 内置册里那些"和预设同 id"的条目：覆盖的是预设正文（§10.2 的补缺），
                        // 不是往世界书里塞一条新的。要写 settings，所以先做。
                        val presetOwner = presets
                            .flatMap { preset -> preset.entries.map { entry -> entry.id to preset.id } }
                            .toMap()
                        val builtin = incoming.firstOrNull {
                            LorebookStore.isBuiltin(it.optString("id"))
                        }
                        var overridden = 0
                        if (builtin != null) {
                            val rows = LorebookStore.entries(builtin)
                            for (index in 0 until rows.length()) {
                                val row = rows.optJSONObject(index) ?: continue
                                val entryId = row.optString("id")
                                val presetId = presetOwner[entryId] ?: continue
                                val view = presets.firstOrNull { it.id == presetId }
                                    ?.entries?.firstOrNull { it.id == entryId } ?: continue
                                PresetRepository.saveEntry(
                                    databasePath = databasePath,
                                    presetId = presetId,
                                    entry = view,
                                    content = row.optString("content"),
                                    disabled = false,
                                ).onSuccess { overridden += 1 }
                            }
                            presetReload += 1
                        }
                        var replaced = 0
                        var added = 0
                        commit { next ->
                            var out = next
                            incoming.forEach { book ->
                                val id = book.optString("id")
                                if (LorebookStore.isBuiltin(id)) {
                                    out = LorebookStore.mergeIntoBuiltin(out, book)
                                } else {
                                    if (LorebookStore.book(out, id) != null) replaced++ else added++
                                    out = LorebookStore.putBookObject(out, book)
                                }
                            }
                            out
                        }
                        preview = null
                        notice = buildString {
                            if (added > 0) append("新增 $added 册")
                            if (replaced > 0) {
                                if (isNotEmpty()) append("，")
                                append("覆盖 $replaced 册")
                            }
                            if (overridden > 0) {
                                if (isNotEmpty()) append("，")
                                append("内置条目覆盖 $overridden 条")
                            }
                            if (isEmpty()) append("这份文件里没有可导入的东西")
                        }
                    }
                },
                onDismiss = { preview = null },
            )
        }

        deleting?.let { target ->
            val targetBook = book(target.bookId)
            val entryName = target.entryId?.let { id ->
                targetBook?.let { LorebookStore.entry(it, id)?.optString("name") }
            }
            ConfirmDialog(
                title = if (target.entryId == null) "删除这一册？" else "删除这一条？",
                detail = if (target.entryId == null) {
                    "「${targetBook?.optString("name").orEmpty()}」里的 " +
                        "${targetBook?.let { LorebookStore.entries(it).length() } ?: 0} 条会一起没掉。" +
                        "删完这一册不再参与注入。"
                } else {
                    "「${entryName.orEmpty().ifBlank { "这一条" }}」会从册里没掉，删完不再参与注入。"
                },
                confirmLabel = "删除",
                onCancel = { deleting = null },
                onConfirm = {
                    commit { next ->
                        if (target.entryId == null) {
                            LorebookStore.deleteBook(next, target.bookId)
                        } else {
                            LorebookStore.deleteEntry(next, target.bookId, target.entryId)
                        }
                    }
                    deleting = null
                },
            )
        }

        bulkDelete?.let { target ->
            val names = target.bookIds.mapNotNull { id ->
                book(id)?.optString("name")?.ifBlank { "未命名" }
            }
            val entryCount = target.bookIds.sumOf { id ->
                book(id)?.let { LorebookStore.entries(it).length() } ?: 0
            }
            val entryBook = target.entryBookId?.let { book(it) }
            ConfirmDialog(
                title = if (target.entryBookId == null) {
                    "删除这 ${target.bookIds.size} 册？"
                } else {
                    "删除这 ${target.entryIds.size} 条？"
                },
                detail = if (target.entryBookId == null) {
                    "「${names.joinToString("」「")}」里的 $entryCount 条会一起没掉。删完这些册不再参与注入。"
                } else {
                    "「${entryBook?.optString("name").orEmpty().ifBlank { "这一册" }}」里选中的" +
                        " ${target.entryIds.size} 条会没掉，删完不再参与注入。"
                },
                confirmLabel = "删除",
                onCancel = { bulkDelete = null },
                onConfirm = {
                    val bookIds = target.bookIds
                    val entryBookId = target.entryBookId
                    val entryIds = target.entryIds
                    commit { next ->
                        var out = next
                        bookIds.forEach { id -> out = LorebookStore.deleteBook(out, id) }
                        if (entryBookId != null) {
                            entryIds.forEach { id -> out = LorebookStore.deleteEntry(out, entryBookId, id) }
                        }
                        out
                    }
                    bulkDelete = null
                    exitPick()
                    notice = if (bookIds.isNotEmpty()) "删了 ${bookIds.size} 册" else "删了 ${entryIds.size} 条"
                },
            )
        }

        moveTarget?.let { target ->
            val from = target.pick
            val present = from.ids.filter { id ->
                book(from.bookId)?.let { LorebookStore.entry(it, id) } != null
            }
            val options = otherBooks(from.bookId)
            MenuSheet(
                title = if (target.move) "移到哪一册" else "复制到哪一册",
                items = options.map { (id, name) ->
                    SheetItem(
                        label = name,
                        value = "${present.size} 条",
                        onClick = {
                            commit { next ->
                                if (target.move) {
                                    LorebookStore.moveEntries(next, from.bookId, id, present)
                                } else {
                                    LorebookStore.copyEntries(next, from.bookId, id, present)
                                }
                                next
                            }
                            notice = if (target.move) {
                                "移了 ${present.size} 条到「$name」"
                            } else {
                                "复制了 ${present.size} 条到「$name」"
                            }
                            moveTarget = null
                            exitPick()
                        },
                    )
                },
                onHint = { notice = it },
                onDismiss = { moveTarget = null },
            )
        }
    }
    }
    }
}

private data class EditTarget(val bookId: String, val entryId: String)

private data class DeleteTarget(val bookId: String, val entryId: String?)

private data class EntryPick(val bookId: String, val ids: Set<String>)

private data class BulkDelete(
    val bookIds: Set<String> = emptySet(),
    val entryBookId: String? = null,
    val entryIds: Set<String> = emptySet(),
)

private data class MoveTarget(val pick: EntryPick, val move: Boolean)

/** 一册导出去用 kelivo 的单册形状：导回 kelivo 不会变样。 */
private fun singleBookDocument(book: JSONObject): JSONObject = JSONObject()
    .put("version", 1)
    .put("type", "lorebook")
    .put("data", JSONObject(book.toString()))

private fun existingIds(root: JSONObject): Set<String> {
    val books = LorebookStore.books(root)
    val ids = LinkedHashSet<String>()
    for (index in 0 until books.length()) {
        books.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
    }
    return ids
}

/**
 * 内置提示词册（§10.1）：条目来自 `assets/presets`，**不复制数据**。
 *
 * 三条规矩：不可删、不可减项（内置条目没有删除与停用）、能增项（自己加的存
 * `files/lorebook.json` 的 `builtin` 册里，可删可停用）。恢复默认只重置内置那几条。
 */
@Composable
private fun BuiltinCard(
    presets: List<PresetView>,
    manual: JSONArray,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenPreset: (PresetEntryView) -> Unit,
    onAddManual: () -> Unit,
    onEditManual: (String) -> Unit,
    onToggleManual: (String, Boolean) -> Unit,
    onDeleteManual: (String) -> Unit,
    locked: Boolean = false,
    onReset: () -> Unit,
) {
    val builtinCount = presets.sumOf { it.entries.size }
    SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !locked, onClick = onToggleExpand)
                .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) ActionIcons.ChevronDown else ActionIcons.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = LorebookStore.BUILTIN_NAME,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Badge("内置")
                }
                Text(
                    text = "$builtinCount 条内置 · ${manual.length()} 条自制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (expanded) {
            RowDivider(leading = 36.dp)
            presets.forEach { preset ->
                preset.entries.forEach { entry ->
                    PresetEntryRow(entry = entry, onClick = { onOpenPreset(entry) })
                }
            }
            for (index in 0 until manual.length()) {
                val entry = manual.optJSONObject(index) ?: continue
                val entryId = entry.optString("id")
                EntryRow(
                    entry = entry,
                    onClick = { onEditManual(entryId) },
                    onToggle = { on -> onToggleManual(entryId, on) },
                    onDelete = { onDeleteManual(entryId) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipButton("新建条目", primary = false, onClick = onAddManual)
                Spacer(Modifier.weight(1f))
                ChipButton("恢复默认", primary = false, onClick = onReset)
            }
        }
    }
}

/** 内置条目那一行：只读，没有开关也没有删除（不可减项）。 */
@Composable
private fun PresetEntryRow(entry: PresetEntryView, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 36.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { "（未命名）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge("预设")
                if (entry.state != "follow") {
                    Spacer(Modifier.width(6.dp))
                    Badge(entry.label)
                }
            }
        }
        Icon(
            imageVector = ActionIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun BookCard(
    book: JSONObject,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onAddEntry: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onEditEntry: (String) -> Unit,
    onToggleEntry: (String, Boolean) -> Unit,
    onDeleteEntry: (String) -> Unit,
    bookSelecting: Boolean = false,
    bookSelected: Boolean = false,
    onToggleBookPick: () -> Unit = {},
    onHold: () -> Unit = {},
    entrySelecting: Boolean = false,
    pickedEntries: Set<String> = emptySet(),
    onToggleEntryPick: (String) -> Unit = { },
    onPickEntries: () -> Unit = {},
    locked: Boolean = false,
) {
    val enabled = book.optBoolean("enabled", true)
    val entries = LorebookStore.entries(book)
    SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onHold(key = book.optString("id")) {
                    if (!locked && !entrySelecting) onHold()
                }
                .clickable {
                    when {
                        locked -> Unit
                        bookSelecting -> onToggleBookPick()
                        else -> onToggleExpand()
                    }
                }
                .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bookSelecting) {
                SelectBox(checked = bookSelected)
            } else {
                Icon(
                    imageVector = if (expanded) ActionIcons.ChevronDown else ActionIcons.ChevronRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.optString("name").ifBlank { "未命名" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!enabled) {
                        Spacer(Modifier.width(6.dp))
                        Badge("已停用")
                    }
                }
                val note = book.optString("note")
                Text(
                    text = listOf(note, LorebookStore.summary(book))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!bookSelecting) {
                Text(
                    text = "选择",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = !entrySelecting) { onPickEntries() }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            }
        }

        if (expanded) {
            RowDivider(leading = 36.dp)
            if (entries.length() == 0) {
                Text(
                    text = "这一册还没有条目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val entryId = entry.optString("id")
                EntryRow(
                    entry = entry,
                    onClick = { onEditEntry(entryId) },
                    onToggle = { on -> onToggleEntry(entryId, on) },
                    onDelete = { onDeleteEntry(entryId) },
                    selecting = entrySelecting,
                    selected = entryId in pickedEntries,
                    onToggleSelect = { onToggleEntryPick(entryId) },
                )
            }
            if (!entrySelecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChipButton("新建条目", primary = false, onClick = onAddEntry)
                    Spacer(Modifier.weight(1f))
                    IconActionButton(ActionIcons.Share2, "导出这一册", onExport, compact = true)
                    IconActionButton(ActionIcons.Trash2, "删除这一册", onDelete, compact = true)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: JSONObject,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val enabled = entry.optBoolean("enabled", true)
    val constant = entry.optBoolean("constantActive", false)
    val keywords = entry.optJSONArray("keywords") ?: JSONArray()
    SwipeDeleteRow(
        resetKey = entry.optString("id"),
        rowColor = MaterialTheme.colorScheme.surfaceContainerLow,
        swipeEnabled = !selecting,
        deleteDescription = "删除这一条",
        onClick = { if (selecting) onToggleSelect() else onClick() },
        onDelete = onDelete,
        modifier = Modifier.padding(start = 36.dp),
        contentPadding = PaddingValues(end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (selecting) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                SelectBox(checked = selected)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.optString("name").ifBlank { "（未命名）" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                maxLines = 1,
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(LorebookStore.positionLabel(entry.optString("position", "AFTER_SYSTEM_PROMPT")))
                if (constant) {
                    Spacer(Modifier.width(6.dp))
                    Badge("常驻")
                } else if (keywords.length() > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "关键词 ${keywords.length()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!enabled) {
                    Spacer(Modifier.width(6.dp))
                    Badge("已停用")
                }
            }
        }
        if (!selecting) {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

/**
 * 条目编辑面板（底部面板，D281 第 1 批）。
 *
 * 停手 100 ms 就写回（跟应用设置页同一条规矩）：面板上没有"保存"按钮，
 * 遮罩点掉也不会丢改动——丢改动才是那种"我明明改了"的来源。
 */
@Composable
private fun EntrySheet(
    entry: JSONObject,
    onApply: (JSONObject) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(entry.optString("name")) }
    var content by remember { mutableStateOf(entry.optString("content")) }
    var position by remember {
        mutableStateOf(entry.optString("position", "AFTER_SYSTEM_PROMPT").trim().uppercase())
    }
    var role by remember { mutableStateOf(entry.optString("role", "USER").trim().uppercase()) }
    var keywords by remember { mutableStateOf(LorebookStore.joinKeywords(entry)) }
    var secondary by remember { mutableStateOf(LorebookStore.joinKeywords(entry, "keySecondary")) }
    var logic by remember { mutableStateOf(entry.optString("selectiveLogic", "and_any")) }
    var wholeWords by remember { mutableStateOf(entry.optBoolean("matchWholeWords", false)) }
    var useRegex by remember { mutableStateOf(entry.optBoolean("useRegex", false)) }
    var caseSensitive by remember { mutableStateOf(entry.optBoolean("caseSensitive", false)) }
    var constant by remember { mutableStateOf(entry.optBoolean("constantActive", false)) }
    var injectDepth by remember { mutableStateOf(entry.optInt("injectDepth", 4).toString()) }
    var scanDepth by remember { mutableStateOf(entry.optInt("scanDepth", 4).toString()) }
    var priority by remember { mutableStateOf(entry.optInt("priority", 0).toString()) }
    var sticky by remember { mutableStateOf(entry.optInt("sticky", 0).toString()) }
    var cooldown by remember { mutableStateOf(entry.optInt("cooldown", 0).toString()) }
    var delay by remember { mutableStateOf(entry.optInt("delay", 0).toString()) }

    val key = listOf(
        name, content, position, role, keywords, secondary, logic, wholeWords,
        useRegex, caseSensitive, constant, injectDepth, scanDepth, priority,
        sticky, cooldown, delay,
    )
    LaunchedEffect(key) {
        delay(100)
        onApply(
            JSONObject()
                .put("name", name.trim())
                .put("content", content)
                .put("position", position)
                .put("role", role)
                .put("keywords", LorebookStore.splitKeywords(keywords))
                .put("keySecondary", LorebookStore.splitKeywords(secondary))
                .put("selectiveLogic", logic)
                .put("matchWholeWords", wholeWords)
                .put("useRegex", useRegex)
                .put("caseSensitive", caseSensitive)
                .put("constantActive", constant)
                .put("injectDepth", injectDepth.trim().toIntOrNull()?.coerceIn(0, 999) ?: 4)
                .put("scanDepth", scanDepth.trim().toIntOrNull()?.coerceIn(0, 999) ?: 4)
                .put("priority", priority.trim().toIntOrNull()?.coerceIn(0, 9999) ?: 0)
                .put("sticky", sticky.trim().toIntOrNull()?.coerceIn(0, 99) ?: 0)
                .put("cooldown", cooldown.trim().toIntOrNull()?.coerceIn(0, 99) ?: 0)
                .put("delay", delay.trim().toIntOrNull()?.coerceIn(0, 99) ?: 0),
        )
    }

    SheetFrame(title = "条目", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            LoreField(name, { name = it }, "名称")
            Spacer(Modifier.height(8.dp))
            LoreField(
                value = content,
                onChange = { content = it },
                placeholder = "内容：注入进上下文的就是这一段",
                singleLine = false,
                minHeight = 96.dp,
            )

            SheetLabel("位置")
            ChipRows(LorebookStore.POSITIONS, position) { position = it }
            SheetLabel("角色")
            ChipRows(LorebookStore.ROLES, role) { role = it }

            Spacer(Modifier.height(8.dp))
            LoreField(keywords, { keywords = it }, "关键词（逗号分隔；空 = 只有常驻时生效）")
            Spacer(Modifier.height(8.dp))
            LoreField(secondary, { secondary = it }, "次级关键词（逗号分隔；空 = 不看这一项）")
            SheetLabel("次级关键词怎么配合主关键词")
            ChipRows(LorebookStore.SELECTIVE_LOGICS, logic) { logic = it }

            if (position == "AT_DEPTH") {
                Spacer(Modifier.height(8.dp))
                LoreField(
                    value = injectDepth,
                    onChange = { injectDepth = it },
                    placeholder = "倒数第几条消息之前",
                    number = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            LoreField(
                value = scanDepth,
                onChange = { scanDepth = it },
                placeholder = "扫描最近几条消息",
                number = true,
            )
            Spacer(Modifier.height(8.dp))
            LoreField(
                value = priority,
                onChange = { priority = it },
                placeholder = "同一位置内的先后（小的先注入）",
                number = true,
            )

            Spacer(Modifier.height(8.dp))
            LoreField(
                value = sticky,
                onChange = { sticky = it },
                placeholder = "粘性：命中之后再多带几条（0 = 关）",
                number = true,
            )
            Spacer(Modifier.height(8.dp))
            LoreField(
                value = cooldown,
                onChange = { cooldown = it },
                placeholder = "冷却：最近几条里提到过就先不注入（0 = 关）",
                number = true,
            )
            Spacer(Modifier.height(8.dp))
            LoreField(
                value = delay,
                onChange = { delay = it },
                placeholder = "延迟：会话前几条里不激活（0 = 关）",
                number = true,
            )

            ToggleLine(
                "整词匹配",
                "关键词要整词命中；中文没有词边界，勾了对中文等于匹配不到",
                wholeWords,
            ) { wholeWords = it }
            ToggleLine("正则", "关键词按正则解释", useRegex) { useRegex = it }
            ToggleLine("区分大小写", "关着 = 不区分", caseSensitive) { caseSensitive = it }
            ToggleLine("常驻", "不看关键词，每一轮都注入", constant) { constant = it }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipButton("删除这一条", primary = false, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                ChipButton("完成", primary = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun NewBookSheet(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    SheetFrame(title = "新建一册", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LoreField(name, { name = it }, "名称（例如：主 AGENT）")
            Spacer(Modifier.height(8.dp))
            LoreField(note, { note = it }, "副标题（可选，例如：槽位 200 起）")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipButton("取消", primary = false, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                ChipButton(
                    label = "建这一册",
                    primary = true,
                    enabled = name.isNotBlank(),
                    onClick = { onCreate(name, note) },
                )
            }
        }
    }
}

/** 导入预览：先看再落库。冲突按 **id** 判（§10.2），名字相同不算同一册。 */
@Composable
private fun ImportPreviewSheet(
    books: List<JSONObject>,
    existing: Set<String>,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clashes = books.filter { existing.contains(it.optString("id")) }
    SheetFrame(title = "导入预览", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            books.forEach { book ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.optString("name").ifBlank { "未命名" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = LorebookStore.summary(book),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (LorebookStore.isBuiltin(book.optString("id"))) {
                        Badge("内置册")
                    } else if (existing.contains(book.optString("id"))) {
                        Badge("同 id")
                    }
                }
            }
            if (clashes.isNotEmpty()) {
                Text(
                    text = "同 id 的 ${clashes.size} 册会被整册替换；内置册按 id 补缺，一条都不少。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipButton("取消", primary = false, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                ChipButton("导入", primary = true, onClick = onImport)
            }
        }
    }
}

/** 内置条目：只读。要改内容去「预设」页——一个动作一个入口。 */
@Composable
private fun PresetEntrySheet(entry: PresetEntryView, onDismiss: () -> Unit) {
    SheetFrame(title = entry.title.ifBlank { "内置条目" }, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = entry.content.ifBlank { "（这一条是空的）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                ChipButton("关闭", primary = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** 互斥选项：两三个全摆出来（不做下拉），一行放不下就换行。 */
@Composable
private fun ChipRows(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val rows = options.chunked(3)
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { (value, label) ->
                    SettingChip(
                        label = label,
                        selected = value.equals(selected, ignoreCase = true),
                        onClick = { onSelect(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** 一行输入。自绘：系统 `TextField` 的内边距与圆角跟这套页面里其它控件对不上。 */
@Composable
private fun LoreField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    number: Boolean = false,
    minHeight: Dp = 44.dp,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/** 「＋」里那个数：**生效 / 启用**（用户 2026-11-08：2/13 = 生效 2、启用 13）。 */
internal fun lorebookCount(books: List<BookRow>, active: List<String>): String =
    "${active.count { id -> books.any { it.id == id } }}/${books.size}"

/** 一册占多高：三册刚好一屏（用户 2026-11-11 要的正是这个数）。 */
private val BOOK_ROW_HEIGHT = 64.dp

/**
 * 世界书清单（D313）：「＋ → 世界书」与角色提示词两处共用同一份形状。
 *
 * 高度是 **3 行**，再多就滚。行高是**固定**的，不由内容撑开——名字长短不一时，
 * 让内容撑就会变成"2.5 册"，那正是这次要避免的。
 */
@Composable
internal fun LorebookBookList(
    rows: List<BookRow>,
    active: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Text(
            text = "还没有世界书：设置 → 世界书 里导入一份",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 16.dp),
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = BOOK_ROW_HEIGHT * 3)
            .verticalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowDivider(leading = 12.dp)
            BookListRow(row = row, selected = row.id in active) {
                onToggle(row.id, row.id !in active)
            }
        }
    }
}

@Composable
private fun BookListRow(row: BookRow, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BOOK_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ActionIcons.Database,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bookLine(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = ActionIcons.Check,
                contentDescription = "这一册生效",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 第二行：「已启用 5/5 · 简介」；没写简介就只留数字。 */
private fun bookLine(row: BookRow): String {
    val head = "已启用 ${row.enabledEntries}/${row.totalEntries}"
    return if (row.note.isBlank()) head else "$head · ${row.note}"
}

/**
 * 「＋ → 世界书」：勾哪几册**真正生效**。
 *
 * 这里只列册级开关打开的那些（收起来的不出现在清单里）；勾选结果存在助手身上，
 * 跟助手设置里那份是同一份数据。
 *
 * **没有「完成」按钮**（用户 2026-11-11）：勾一下就已经存进去了，再摆一颗"完成"
 * 等于告诉用户"你刚才点的那些都没算数"。收起靠点遮罩或返回。
 */
@Composable
internal fun LorebookPickerSheet(
    books: List<BookRow>,
    active: List<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val on = active.count { id -> books.any { it.id == id } }
    SheetFrame(title = "世界书（$on/${books.size}）", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            LorebookBookList(rows = books, active = active.toSet(), onToggle = onToggle)
        }
    }
}
