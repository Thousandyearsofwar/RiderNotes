package com.thousandyearsofwar.ridenotes.model

/**
 * 一条笔记。一个锚点下可以有任意多条（最新在前）。
 */
class Note(
    var id: String = newId("n"),
    var author: String = "",
    var createdAt: String = now(),
    var updatedAt: String = now(),
    var body: String = "",
    var tags: List<String> = emptyList()
)

/**
 * 锚点：一段代码区间 + 一组笔记。
 *
 * 存储里的 startOffset/endOffset 只是"最后一次已知位置"，真正的身份由
 * anchorText（规范化原文）+ anchorSha1 + 上下文行 决定，重开文件时靠它们重定位。
 */
class Anchor(
    var id: String = newId("a"),
    var startOffset: Int = 0,
    var endOffset: Int = 0,
    var startLine: Int = 1,
    var endLine: Int = 1,
    var anchorText: String = "",
    var anchorSha1: String = "",
    var contextBefore: List<String> = emptyList(),
    var contextAfter: List<String> = emptyList(),
    var title: String = "",
    var color: Int = 0,
    /** 创建者：git 用户名、`api`，或自定义客户端名（X-Sidecar-Client）。 */
    var createdBy: String = "",
    // ---- 以下字段原属 ProjectIndex.placements，schema 2 起并入锚点（单一真相源）----
    /** 所属分组；null 表示根级。 */
    var groupId: String? = null,
    /** 组内排序键，用浮点中点插入，避免重排其他项。 */
    var orderValue: Double = 0.0,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var compact: Boolean = false,
    /** 单条颜色覆盖；null 表示跟随分组。 */
    var colorOverride: Int? = null,
    /** 克隆锚点：指向源锚点 id，笔记内容与源保持一致（只读镜像）。 */
    var mirrorOf: String? = null,
    var createdAt: String = now(),
    var updatedAt: String = now(),
    var notes: MutableList<Note> = mutableListOf()
) {
    /** 显示名：用户没起标题时，用内容首行当前缀，最后才回落到行号。 */
    val displayTitle: String
        get() {
            if (title.isNotBlank()) return title
            val head = notes.firstOrNull()?.body
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            if (head.isNotEmpty()) return head.take(30)
            return "行 $startLine–$endLine"
        }

    val isEmpty: Boolean get() = notes.isEmpty()
}

/**
 * 一个源文件对应一个分片文件。
 */
class AnchorFile(
    var schema: Int = SCHEMA,
    var file: String = "",
    var anchors: MutableList<Anchor> = mutableListOf()
) {
    companion object {
        const val SCHEMA = 1
    }
}

private fun newId(prefix: String): String =
    prefix + "_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

private fun now(): String = java.time.Instant.now().toString()
