package com.thousandyearsofwar.ridenotes.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.thousandyearsofwar.ridenotes.editor.AnchorRenderService
import com.thousandyearsofwar.ridenotes.model.Anchoring
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import com.thousandyearsofwar.ridenotes.settings.NotesProjectSettings
import com.thousandyearsofwar.ridenotes.util.NoteLog
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 本地自动化接口：绑定 127.0.0.1，供脚本批量建书签 / 压测用。
 *
 * ```
 * GET  /ping
 * GET  /status
 * GET  /anchors?file=<relative>
 * POST /anchors        {file,startLine,endLine,title,body}
 * POST /anchors/delete {id}
 * POST /clear          {file}    // file 省略 = 清空整个库
 * ```
 */
@Service(Service.Level.PROJECT)
class NotesHttpServer(private val project: Project) {

    private var server: HttpServer? = null
    private val gson = GsonBuilder().create()

    /** 批处理期间抑制逐条重绘，结束时统一刷新。 */
    private var suppressRefresh = false
    private val batchTouched = linkedSetOf<VirtualFile>()
    private var batchAll = false

    private fun refreshEditor(file: VirtualFile?) {
        if (suppressRefresh) {
            if (file != null) batchTouched.add(file) else batchAll = true
            return
        }
        if (file != null) AnchorRenderService.get(project).refresh(file)
        else AnchorRenderService.get(project).refreshAll()
    }

    /** 实际监听的端口（配置端口被占用时会向后顺延）。 */
    var actualPort: Int = 0
        private set

    companion object {
        const val PORT = 47800
        private const val PORT_ATTEMPTS = 10
        private const val TOKEN_HEADER = "X-Sidecar-Token"

        fun getInstance(project: Project): NotesHttpServer = project.service()
    }

    fun start(configuredPort: Int = -1) {
        if (server != null) return
        val settings = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get()
        if (!settings.apiEnabled) {
            NoteLog.info("http api disabled in settings")
            return
        }
        val wanted = if (configuredPort > 0) configuredPort else settings.apiPort

        var instance: HttpServer? = null
        var boundPort = 0
        for (offset in 0 until PORT_ATTEMPTS) {
            val candidate = wanted + offset
            val created = runCatching {
                HttpServer.create(InetSocketAddress("127.0.0.1", candidate), 0).apply {
                    executor = Executors.newFixedThreadPool(2)
                    createContext("/") { exchange -> runCatching { route(exchange) }.onFailure {
                        NoteLog.warn("http api error: ${it.message}")
                        reply(exchange, 500, mapOf("ok" to false, "error" to (it.message ?: "error")))
                    } }
                    start()
                }
            }.onFailure { NoteLog.warn("port $candidate unavailable: ${it.message}") }.getOrNull()

            if (created != null) {
                instance = created
                boundPort = candidate
                break
            }
        }

        server = instance
        actualPort = boundPort
        if (instance != null) {
            NoteLog.info("http api listening on 127.0.0.1:$boundPort (auth=${if (settings.apiToken.isBlank()) "off" else "token"})")
            writeCapabilityFile(boundPort, settings.apiToken)
        } else {
            NoteLog.warn("http api failed to bind ports $wanted..${wanted + PORT_ATTEMPTS - 1}")
        }
    }

    /**
     * 写「能力文件」给脚本 / AI 发现：`.ridenotes/api.json`（含端口和令牌）。
     * 同时在同目录写一个 `.gitignore` 把 api.json 排除，避免令牌被提交。
     */
    private fun writeCapabilityFile(port: Int, token: String) {
        runCatching {
            val dir = com.thousandyearsofwar.ridenotes.model.AnchorStore.baseDir(project) ?: return@runCatching
            java.nio.file.Files.createDirectories(dir)
            val version = com.thousandyearsofwar.ridenotes.util.PluginInfo.version
            val payload = gson.toJson(
                mapOf("port" to port, "token" to token, "version" to version, "project" to project.name)
            )
            java.nio.file.Files.writeString(
                dir.resolve("api.json"), payload, Charsets.UTF_8
            )
            val gitignore = dir.resolve(".gitignore")
            if (!java.nio.file.Files.exists(gitignore)) {
                java.nio.file.Files.writeString(gitignore, "api.json\n", Charsets.UTF_8)
            }
        }.onFailure { NoteLog.warn("write api.json failed: ${it.message}") }
    }

    private fun removeCapabilityFile() {
        runCatching {
            val dir = com.thousandyearsofwar.ridenotes.model.AnchorStore.baseDir(project) ?: return@runCatching
            java.nio.file.Files.deleteIfExists(dir.resolve("api.json"))
        }
    }

    fun stop() {
        runCatching { server?.stop(0) }
        server = null
        actualPort = 0
        removeCapabilityFile()
    }

    // ------------------------------------------------------------------

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

        // 鉴权：/ping 允许匿名探活，其余接口在配置了 token 时必须带 X-Sidecar-Token
        val token = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get().apiToken
        if (path != "/ping" && token.isNotBlank() &&
            exchange.requestHeaders.getFirst(TOKEN_HEADER) != token
        ) {
            reply(exchange, 401, mapOf("ok" to false, "error" to "unauthorized: send $TOKEN_HEADER header"))
            return
        }

        // 调用方标识（审计）：X-Sidecar-Client，缺省记为 api
        val client = exchange.requestHeaders.getFirst("X-Sidecar-Client")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "api"

        when {
            path == "/ping" -> reply(exchange, 200, mapOf("ok" to true))

            path == "/status" -> {
                val status = onEdt {

                    mapOf(
                        "ok" to true,
                        "project" to project.name,
                        "basePath" to (project.basePath ?: ""),
                        "libraryPath" to NotesProjectSettings.getInstance(project).libraryPath,
                        "version" to com.thousandyearsofwar.ridenotes.util.PluginInfo.version,
                        "port" to actualPort,
                        "anchors" to com.thousandyearsofwar.ridenotes.model.AnchorStore.listAll(project)
                            .sumOf { it.anchors.size },
                        "notes" to com.thousandyearsofwar.ridenotes.model.AnchorStore.listAll(project)
                            .sumOf { shard -> shard.anchors.sumOf { it.notes.size } }
                    )
                }
                reply(exchange, 200, status)
            }

            path == "/anchors" && exchange.requestMethod == "GET" -> {
                val filter = queryParam(query, "file")
                val text = queryParam(query, "q")?.lowercase()
                val list = onEdt {
                    com.thousandyearsofwar.ridenotes.model.AnchorStore.listAll(project)
                        .filter { filter == null || it.file == filter }
                        .flatMap { shard ->
                            shard.anchors.map { anchor ->
                                mapOf(
                                    "id" to anchor.id,
                                    "file" to shard.file,
                                    "startLine" to anchor.startLine,
                                    "endLine" to anchor.endLine,
                                    "title" to anchor.title,
                                    "color" to anchor.color,
                                    "createdBy" to anchor.createdBy,
                                    "notes" to anchor.notes.size,
                                    "tags" to anchor.notes.flatMap { it.tags }.distinct(),
                                    "text" to anchor.notes.joinToString("\n") { it.body }
                                )
                            }
                        }
                        .filter { row ->
                            if (text.isNullOrBlank()) return@filter true
                            val hay = buildString {
                                append(row["title"]).append(' ').append(row["file"]).append(' ')
                                append(row["text"]).append(' ').append(row["tags"])
                            }.lowercase()
                            hay.contains(text)
                        }
                        .map { row -> row.filterKeys { it != "text" } }
                }
                reply(exchange, 200, mapOf("ok" to true, "anchors" to list))
            }

            path == "/notes" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                val created = onEdt { appendNote(json, client) }
                reply(exchange, if (created == null) 400 else 200, created ?: mapOf("ok" to false, "error" to "bad request"))
            }

            path == "/notes/update" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { updateNote(json) })
            }

            path == "/notes/delete" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { deleteNote(json) })
            }

            path == "/anchors/update" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { updateAnchor(json) })
            }

            path == "/anchors" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                val created = onEdt { createAnchor(json, client) }
                reply(exchange, if (created == null) 400 else 200, created ?: mapOf("ok" to false, "error" to "bad request"))
            }

            path == "/anchors/batch" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                val created = onEdt { createBatch(json) }
                reply(exchange, 200, mapOf("ok" to true, "created" to created))
            }

            path == "/groups" && exchange.requestMethod == "GET" -> reply(exchange, 200, onEdt { listGroups() })
            path == "/groups" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { createGroup(json) })
            }
            path == "/groups/update" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { updateGroup(json) })
            }
            path == "/groups/delete" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { deleteGroup(json) })
            }
            path == "/anchors/move" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { moveAnchor(json) })
            }
            path == "/tags" && exchange.requestMethod == "GET" -> reply(exchange, 200, onEdt { listTags() })
            path == "/tags" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { createTag(json) })
            }
            path == "/tags/update" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { updateTag(json) })
            }
            path == "/tags/delete" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { deleteTag(json) })
            }
            path == "/changes" && exchange.requestMethod == "GET" -> {
                val since = queryParam(query, "since")?.toLongOrNull() ?: 0L
                reply(exchange, 200, onEdt { NoteAnchorService.get(project).changesSince(since) })
            }
            path == "/batch" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                reply(exchange, 200, onEdt { runBatch(json, client) })
            }

            path == "/export" && exchange.requestMethod == "GET" -> {
                val format = queryParam(query, "format") ?: "md"
                val file = queryParam(query, "file")
                val text = onEdt {
                    if (format == "pr") NotesExporter.pullRequest(project, file)
                    else NotesExporter.markdown(project, file)
                }
                replyText(exchange, 200, text)
            }

            path == "/anchors/delete" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                val removed = onEdt { deleteAnchor(json) }
                reply(exchange, 200, mapOf("ok" to true, "removed" to removed))
            }

            path == "/clear" && exchange.requestMethod == "POST" -> {
                val json = JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
                val cleared = onEdt { clear(json) }
                reply(exchange, 200, mapOf("ok" to true, "cleared" to cleared))
            }

            else -> reply(exchange, 404, mapOf("ok" to false, "error" to "not found: $path"))
        }
    }

    private fun createAnchor(json: JsonObject, client: String): Map<String, Any?>? {
        val relative = json.str("file") ?: return null
        val startLine = json.int("startLine") ?: return null
        val endLine = json.int("endLine") ?: startLine
        val title = json.str("title").orEmpty()
        val noteBody = json.str("body").orEmpty()

        val file = resolveFile(relative) ?: return mapOf("ok" to false, "error" to "file not found: $relative")
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return mapOf("ok" to false, "error" to "no document")
        if (document.lineCount <= 0) {
            return mapOf("ok" to false, "error" to "file is empty: $relative")
        }

        val lastLine = document.lineCount - 1
        val from = (startLine - 1).coerceIn(0, lastLine)
        val to = (endLine - 1).coerceIn(from, lastLine)
        val range = TextRange(document.getLineStartOffset(from), document.getLineEndOffset(to))

        val anchor = Anchoring.createAnchor(document, range)
        anchor.title = title
        anchor.createdBy = client
        val service = NoteAnchorService.get(project)
        val t0 = System.nanoTime()
        service.addAnchor(file, anchor)
        val t1 = System.nanoTime()
        if (noteBody.isNotBlank()) service.addNote(file, anchor.id, noteBody, client)
        val t2 = System.nanoTime()
        refreshEditor(file)
        val t3 = System.nanoTime()
        NoteLog.info(
            "api create id=${anchor.id} anchors=${service.anchorsFor(file).size} " +
                "add=${(t1 - t0) / 1_000_000}ms note=${(t2 - t1) / 1_000_000}ms refresh=${(t3 - t2) / 1_000_000}ms"
        )

        return mapOf(
            "ok" to true,
            "id" to anchor.id,
            "startLine" to anchor.startLine,
            "endLine" to anchor.endLine
        )
    }

    private fun createBatch(json: JsonObject): Int {
        val relative = json.str("file") ?: return 0
        val startLine = json.int("startLine") ?: 1
        val step = json.int("step") ?: 10
        val count = json.int("count") ?: 10
        val titlePrefix = json.str("titlePrefix") ?: "batch"

        val file = resolveFile(relative) ?: return 0
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return 0
        val lastLine = document.lineCount - 1
        val author = System.getProperty("user.name").orEmpty()

        val fresh = ArrayList<com.thousandyearsofwar.ridenotes.model.Anchor>(count)
        for (i in 0 until count) {
            val line = (startLine - 1 + i * step).coerceIn(0, lastLine)
            val range = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
            val anchor = Anchoring.createAnchor(document, range)
            anchor.title = "$titlePrefix $i"
            anchor.notes.add(com.thousandyearsofwar.ridenotes.model.Note(body = "auto note #$i", author = author))
            fresh.add(anchor)
        }

        NoteAnchorService.get(project).addAnchors(file, fresh)
        refreshEditor(file)
        return fresh.size
    }

    /** 给已存在的锚点追加一条笔记：{anchorId, body} */
    private fun appendNote(json: JsonObject, client: String = "api"): Map<String, Any?>? {
        val anchorId = json.str("anchorId") ?: return null
        val body = json.str("body") ?: return null
        if (body.isBlank()) return mapOf("ok" to false, "error" to "empty body")

        val (relative, file) = findAnchorFile(anchorId)
            ?: return mapOf("ok" to false, "error" to "anchor not found: $anchorId")
        val note = NoteAnchorService.get(project).addNote(file, anchorId, body, client)
            ?: return mapOf("ok" to false, "error" to "failed to add note")
        refreshEditor(file)
        return mapOf("ok" to true, "noteId" to note.id, "file" to relative)
    }

    /** 按 anchorId 找它所属的分片与文件（走内存缓存，批量模式下也能找到刚建的锚点）。 */
    private fun findAnchorFile(anchorId: String): Pair<String, VirtualFile>? {
        val (relative, _) = NoteAnchorService.get(project).findAnchor(anchorId) ?: return null
        val file = resolveFile(relative) ?: return null
        return relative to file
    }

    /** 修改笔记正文 / 标签：{anchorId, noteId, body?, tags?[]} */
    private fun updateNote(json: JsonObject): Map<String, Any?> {
        val anchorId = json.str("anchorId") ?: return mapOf("ok" to false, "error" to "anchorId required")
        val noteId = json.str("noteId") ?: return mapOf("ok" to false, "error" to "noteId required")
        val body = json.str("body")
        val tags = json.get("tags")?.takeIf { it.isJsonArray }
            ?.asJsonArray?.map { it.asString }

        val (relative, file) = findAnchorFile(anchorId)
            ?: return mapOf("ok" to false, "error" to "anchor not found: $anchorId")
        NoteAnchorService.get(project).updateNoteContent(file, anchorId, noteId, body, tags)
        refreshEditor(file)
        return mapOf("ok" to true, "file" to relative, "noteId" to noteId)
    }

    private fun deleteNote(json: JsonObject): Map<String, Any?> {
        val anchorId = json.str("anchorId") ?: return mapOf("ok" to false, "error" to "anchorId required")
        val noteId = json.str("noteId") ?: return mapOf("ok" to false, "error" to "noteId required")
        val (relative, file) = findAnchorFile(anchorId)
            ?: return mapOf("ok" to false, "error" to "anchor not found: $anchorId")
        NoteAnchorService.get(project).deleteNote(file, anchorId, noteId)
        refreshEditor(file)
        return mapOf("ok" to true, "file" to relative, "noteId" to noteId)
    }

    /** 修改锚点属性：{id, title?, color?, visible?, locked?, compact?} */
    private fun updateAnchor(json: JsonObject): Map<String, Any?> {
        val id = json.str("id") ?: return mapOf("ok" to false, "error" to "id required")
        val (relative, file) = findAnchorFile(id)
            ?: return mapOf("ok" to false, "error" to "anchor not found: $id")
        val service = NoteAnchorService.get(project)

        json.str("title")?.let { service.updateAnchorTitle(relative, id, it) }
        json.int("color")?.let { service.setAnchorColorOverride(id, it) }
        json.bool("visible")?.let { service.setAnchorVisible(id, it) }
        json.bool("locked")?.let { service.setAnchorLocked(id, it) }
        json.bool("compact")?.let { service.setAnchorCompact(id, it) }

        refreshEditor(file)
        return mapOf("ok" to true, "file" to relative, "id" to id)
    }

    private fun err(message: String): Map<String, Any?> = mapOf("ok" to false, "error" to message)

    // JSON 安全取值：字段缺失或显式 null 都返回 null，不会抛 JsonNull
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString
    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { !it.isJsonNull }?.asInt
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { !it.isJsonNull }?.asBoolean

    // ---------- 分组 ----------

    private fun listGroups(): Map<String, Any?> {
        val service = NoteAnchorService.get(project)
        val index = service.index()
        return mapOf(
            "ok" to true,
            "groups" to index.groups.map { g ->
                mapOf(
                    "id" to g.id, "title" to g.title, "color" to g.color,
                    "visible" to g.visible, "locked" to g.locked, "compact" to g.compact,
                    "parentId" to g.parentId, "order" to g.order, "tags" to g.tags
                )
            },
            // schema 2：归属存在锚点里，这里从锚点派生，保持旧响应结构
            "placements" to service.anchorsSnapshot().map { a ->
                mapOf(
                    "anchorId" to a.id, "groupId" to a.groupId, "order" to a.orderValue.toInt(),
                    "visible" to a.visible, "locked" to a.locked, "compact" to a.compact,
                    "colorOverride" to a.colorOverride
                )
            }
        )
    }

    private fun createGroup(json: JsonObject): Map<String, Any?> {
        val name = json.str("name") ?: return err("name required")
        val parentId = json.str("parentId")
        val group = NoteAnchorService.get(project).createGroup(parentId, name)
        return mapOf("ok" to true, "id" to group.id)
    }

    private fun updateGroup(json: JsonObject): Map<String, Any?> {
        val id = json.str("id") ?: return err("id required")
        val service = NoteAnchorService.get(project)
        json.str("title")?.let { service.renameGroup(id, it) }
        json.int("color")?.let { service.setGroupColor(id, it) }
        json.bool("visible")?.let { service.setGroupVisible(id, it) }
        json.bool("locked")?.let { service.setGroupLocked(id, it) }
        json.bool("compact")?.let { service.setGroupCompact(id, it) }
        refreshEditor(null)
        return mapOf("ok" to true, "id" to id)
    }

    private fun deleteGroup(json: JsonObject): Map<String, Any?> {
        val id = json.str("id") ?: return err("id required")
        NoteAnchorService.get(project).deleteGroup(id)
        refreshEditor(null)
        return mapOf("ok" to true, "id" to id)
    }

    private fun moveAnchor(json: JsonObject): Map<String, Any?> {
        val id = json.str("id") ?: return err("id required")
        val groupId = json.str("groupId")
        val service = NoteAnchorService.get(project)
        val order = json.int("order")
            ?: service.anchorsSnapshot().count { it.groupId == groupId }
        service.placeAnchor(id, groupId, order)
        return mapOf("ok" to true, "id" to id, "groupId" to groupId)
    }

    // ---------- 标签库 ----------

    private fun listTags(): Map<String, Any?> {
        val index = NoteAnchorService.get(project).index()
        return mapOf(
            "ok" to true,
            "tagGroups" to index.tagGroups.sortedBy { it.order }
                .map { mapOf("id" to it.id, "name" to it.name, "order" to it.order) },
            "tags" to index.tags.map { t ->
                mapOf("id" to t.id, "name" to t.name, "color" to t.color, "groupId" to t.groupId)
            }
        )
    }

    private fun createTag(json: JsonObject): Map<String, Any?> {
        val name = json.str("name") ?: return err("name required")
        val groupId = json.str("groupId")
        val tag = NoteAnchorService.get(project).addTag(name, groupId) ?: return err("failed to create tag")
        return mapOf("ok" to true, "id" to tag.id, "name" to tag.name, "color" to tag.color)
    }

    private fun updateTag(json: JsonObject): Map<String, Any?> {
        val id = json.str("id") ?: return err("id required")
        val service = NoteAnchorService.get(project)
        json.str("name")?.let { service.renameTag(id, it) }
        json.int("color")?.let { service.setTagColor(id, it) }
        if (json.has("groupId")) {
            service.setTagGroup(id, json.str("groupId"))
        }
        return mapOf("ok" to true, "id" to id)
    }

    private fun deleteTag(json: JsonObject): Map<String, Any?> {
        val id = json.str("id") ?: return err("id required")
        NoteAnchorService.get(project).removeTag(id)
        refreshEditor(null)
        return mapOf("ok" to true, "id" to id)
    }

    // ---------- 混合批处理 ----------

    /**
     * 一次请求执行多条操作：**每个文件只重绘一次**。
     * 返回每个 op 的结果，单个 op 失败不影响其他。
     */
    private fun runBatch(json: JsonObject, client: String = "api"): Map<String, Any?> {
        val ops = json.get("ops")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return err("ops array required")
        val service = NoteAnchorService.get(project)
        val results = mutableListOf<Map<String, Any?>>()

        suppressRefresh = true
        try {
            service.inBulk {
                ops.forEach { element ->
            val op = element.asJsonObject
            val kind = op.str("op")
            if (kind == null) {
                results.add(err("op required"))
                return@forEach
            }
            val result = runCatching {
                when (kind) {
                    "anchor.create" -> {
                        val file = op.str("file")?.let { resolveFile(it) } ?: return@runCatching err("file not found")
                        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runCatching err("no document")
                        if (document.lineCount <= 0) return@runCatching err("file is empty")
                        val start = (op.int("startLine") ?: 1) - 1
                        val end = (op.int("endLine") ?: (start + 1)) - 1
                        val last = document.lineCount - 1
                        val from = start.coerceIn(0, last)
                        val to = end.coerceIn(from, last)
                        val anchor = Anchoring.createAnchor(document, TextRange(document.getLineStartOffset(from), document.getLineEndOffset(to)))
                        anchor.title = op.str("title").orEmpty()
                        anchor.createdBy = client
                        service.addAnchor(file, anchor)
                        op.str("body")?.takeIf { it.isNotBlank() }
                            ?.let { service.addNote(file, anchor.id, it, client) }
                        mapOf("ok" to true, "id" to anchor.id)
                    }
                    "anchor.update" -> {
                        val id = op.str("id") ?: return@runCatching err("id required")
                        val (relative, file) = findAnchorFile(id) ?: return@runCatching err("anchor not found: $id")
                        op.str("title")?.let { service.updateAnchorTitle(relative, id, it) }
                        op.int("color")?.let { service.setAnchorColorOverride(id, it) }
                        op.bool("visible")?.let { service.setAnchorVisible(id, it) }
                        op.bool("locked")?.let { service.setAnchorLocked(id, it) }
                        op.bool("compact")?.let { service.setAnchorCompact(id, it) }
                        mapOf("ok" to true, "id" to id)
                    }
                    "anchor.delete" -> {
                        val id = op.str("id") ?: return@runCatching err("id required")
                        val (_, file) = findAnchorFile(id) ?: return@runCatching err("anchor not found: $id")
                        service.removeAnchor(file, id)
                        mapOf("ok" to true, "id" to id)
                    }
                    "anchor.move" -> moveAnchor(op)
                    "note.add" -> appendNote(op, client)
                    "note.update" -> updateNote(op)
                    "note.delete" -> deleteNote(op)
                    else -> err("unknown op: $kind")
                }
            }.getOrElse { err(it.message ?: "failed") } ?: err("failed")
                results.add(result)
                }
            }
        } finally {
            suppressRefresh = false
        }

        if (batchAll) AnchorRenderService.get(project).refreshAll()
        else batchTouched.forEach { AnchorRenderService.get(project).refresh(it) }
        batchTouched.clear()
        batchAll = false

        return mapOf("ok" to true, "results" to results)
    }

    private fun deleteAnchor(json: JsonObject): Int {
        val id = json.str("id") ?: return 0
        val service = NoteAnchorService.get(project)
        com.thousandyearsofwar.ridenotes.model.AnchorStore.listAll(project).forEach { shard ->
            if (shard.anchors.none { it.id == id }) return@forEach
            val file = resolveFile(shard.file) ?: return@forEach
            service.removeAnchor(file, id)
            refreshEditor(file)
            return 1
        }
        return 0
    }

    private fun clear(json: JsonObject): Int {
        val filter = json.str("file")
        var removed = 0
        val service = NoteAnchorService.get(project)
        com.thousandyearsofwar.ridenotes.model.AnchorStore.listAll(project).forEach { shard ->
            if (filter != null && shard.file != filter) return@forEach
            val file = resolveFile(shard.file) ?: return@forEach
            shard.anchors.map { it.id }.forEach { id ->
                if (service.removeAnchor(file, id) != null) removed++
            }
            refreshEditor(file)
        }
        return removed
    }

    private fun resolveFile(relative: String): VirtualFile? {
        val base = project.basePath ?: return null
        val absolute = if (java.io.File(relative).isAbsolute) relative else "$base/$relative"
        return LocalFileSystem.getInstance().findFileByPath(absolute.replace('\\', '/'))
    }

    private fun <T> onEdt(block: () -> T): T {
        val queuedAt = System.nanoTime()
        var enteredAt = 0L
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait {
            enteredAt = System.nanoTime()
            result = block()
        }
        val doneAt = System.nanoTime()
        val waitMs = (enteredAt - queuedAt) / 1_000_000
        val workMs = (doneAt - enteredAt) / 1_000_000
        if (waitMs > 200 || workMs > 200) {
            NoteLog.info("api edt wait=${waitMs}ms work=${workMs}ms")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun reply(exchange: HttpExchange, code: Int, payload: Any) {
        val bytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 纯文本响应（导出用）。 */
    private fun replyText(exchange: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/markdown; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
