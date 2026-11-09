package com.thousandyearsofwar.ridenotes.model

import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 分片存储：`<project>/.ridenotes/<相对路径转义>.notes.json`
 *
 * 一个源文件一个分片，git 冲突面小、diff 好看、打开快。
 */
object AnchorStore {

    private const val DIR_NAME = ".ridenotes"

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    /** 分片用：不缩进，序列化快一个数量级。 */
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    fun baseDir(project: Project): Path? {
        val base = project.basePath ?: return null
        val configured = runCatching {
            com.thousandyearsofwar.ridenotes.settings.NotesProjectSettings.getInstance(project).libraryPath
        }.getOrDefault(com.thousandyearsofwar.ridenotes.settings.NotesProjectSettings.DEFAULT)

        val path = Path.of(configured)
        return if (path.isAbsolute) path else Path.of(base).resolve(configured)
    }

    fun relativePath(project: Project, absolutePath: String): String? {
        val base = project.basePath ?: return null
        val basePath = Path.of(base)
        val filePath = Path.of(absolutePath)
        return runCatching { basePath.relativize(filePath).toString().replace('\\', '/') }.getOrNull()
    }

    private fun shardPath(project: Project, relativePath: String): Path? {
        val dir = baseDir(project) ?: return null
        val escaped = relativePath.replace('/', '_').replace('\\', '_')
        return dir.resolve("$escaped.notes.json")
    }

    private fun indexPath(project: Project): Path? = baseDir(project)?.resolve("_project.json")

    fun loadIndex(project: Project): ProjectIndex? {
        val path = indexPath(project) ?: return null
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            gson.fromJson(Files.readString(path, Charsets.UTF_8), ProjectIndex::class.java)
        }.getOrNull()
    }

    fun saveIndex(project: Project, index: ProjectIndex) {
        val path = indexPath(project) ?: return
        runCatching {
            Files.createDirectories(path.parent)
            val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(temp, gson.toJson(index), Charsets.UTF_8)
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // 不做同步 VFS 刷新：这些 JSON 只走 NIO 读写，进 VFS 只会白白拖慢每次写盘
        }.onFailure {
            com.thousandyearsofwar.ridenotes.util.NoteLog.warn("save index failed: $path", it)
        }
    }

    fun load(project: Project, relativePath: String): AnchorFile? {
        val path = shardPath(project, relativePath) ?: return null
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val text = Files.readString(path, Charsets.UTF_8)
            gson.fromJson(text, AnchorFile::class.java)
        }.getOrElse { null }?.also { it.file = relativePath }
    }

    fun save(project: Project, relativePath: String, anchors: List<Anchor>) {
        val path = shardPath(project, relativePath) ?: return
        val payload = AnchorFile(file = relativePath, anchors = anchors.toMutableList())
        runCatching {
            Files.createDirectories(path.parent)
            val temp = path.resolveSibling(path.fileName.toString() + ".tmp")

            val t0 = System.nanoTime()
            // 分片用紧凑 JSON：千级锚点时 prettyPrint 的序列化开销是灾难级的
            val json = compactGson.toJson(payload)
            val t1 = System.nanoTime()
            Files.writeString(temp, json, Charsets.UTF_8)
            val t2 = System.nanoTime()
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val t3 = System.nanoTime()

            if ((t3 - t0) / 1_000_000 > 50) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.info(
                    "save ${path.fileName} anchors=${anchors.size} bytes=${json.length} " +
                        "json=${(t1 - t0) / 1_000_000}ms write=${(t2 - t1) / 1_000_000}ms " +
                        "move=${(t3 - t2) / 1_000_000}ms"
                )
            }
        }.onFailure {
            com.thousandyearsofwar.ridenotes.util.NoteLog.warn("save failed: $path", it)
        }
    }

    /** 原始 JSON 读写：给撤销做快照用。 */
    fun readRaw(project: Project, relativePath: String): String? {
        val path = shardPath(project, relativePath) ?: return null
        return runCatching { if (Files.isRegularFile(path)) Files.readString(path, Charsets.UTF_8) else null }.getOrNull()
    }

    fun writeRaw(project: Project, relativePath: String, json: String?) {
        val path = shardPath(project, relativePath) ?: return
        runCatching {
            if (json == null) {
                Files.deleteIfExists(path)
            } else {
                Files.createDirectories(path.parent)
                Files.writeString(path, json, Charsets.UTF_8)
            }
            // 不做同步 VFS 刷新：这些 JSON 只走 NIO 读写，进 VFS 只会白白拖慢每次写盘
        }
    }

    fun readRawIndex(project: Project): String? {
        val path = indexPath(project) ?: return null
        return runCatching { if (Files.isRegularFile(path)) Files.readString(path, Charsets.UTF_8) else null }.getOrNull()
    }

    fun writeRawIndex(project: Project, json: String?) {
        val path = indexPath(project) ?: return
        runCatching {
            if (json == null) {
                Files.deleteIfExists(path)
            } else {
                Files.createDirectories(path.parent)
                Files.writeString(path, json, Charsets.UTF_8)
            }
            // 不做同步 VFS 刷新：这些 JSON 只走 NIO 读写，进 VFS 只会白白拖慢每次写盘
        }
    }

    /** 列出全项目的分片（管理面板用）。 */
    fun listAll(project: Project): List<AnchorFile> {
        val dir = baseDir(project) ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            val files = Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".notes.json") }.toList()
            }
            files.mapNotNull { path ->
                runCatching {
                    gson.fromJson(Files.readString(path, Charsets.UTF_8), AnchorFile::class.java)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun delete(project: Project, relativePath: String) {
        val path = shardPath(project, relativePath) ?: return
        runCatching {
            Files.deleteIfExists(path)
            // 不做同步 VFS 刷新：这些 JSON 只走 NIO 读写，进 VFS 只会白白拖慢每次写盘
        }
    }
}
