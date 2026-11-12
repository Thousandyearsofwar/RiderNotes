package com.thousandyearsofwar.ridenotes.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UndoableAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.thousandyearsofwar.ridenotes.editor.AnchorRenderService
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.Anchoring
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import com.thousandyearsofwar.ridenotes.ui.NotePopups
import com.thousandyearsofwar.ridenotes.util.NoteLog

/**
 * 选中代码 → 建锚点 → 立刻出现视觉 → 浮层里写第一条笔记。
 * Esc 取消时把刚建的锚点回滚掉。
 */
class CreateAnchorAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return
        val start = selectionModel.selectionStart
        val end = selectionModel.selectionEnd
        if (start == end) return
        val selection = com.intellij.openapi.util.TextRange(start, end)

        // 纯空白选区不建锚点：没有原文可锚定，之后必然定位不上
        if (editor.document.getText(selection).isBlank()) {
            NoteLog.notify(project, "选中的内容为空（只有空白），请选择实际代码")
            return
        }

        val service = NoteAnchorService.get(project)
        val render = AnchorRenderService.get(project)

        val anchor = Anchoring.createAnchor(editor.document, selection)
        service.addAnchor(file, anchor)
        render.refresh(file)
        registerUndo(project, file, "添加笔记锚点",
            undo = { service.removeAnchor(file, anchor.id); render.refresh(file) },
            redo = { service.addAnchor(file, anchor); render.refresh(file) }
        )

        NotePopups.showEditor(
            project = project,
            editor = editor,
            file = file,
            anchor = anchor,
            initial = "",
            onSave = { body ->
                if (body.isNotBlank()) service.addNote(file, anchor.id, body)
                render.refresh(file)
            },
            onCancel = {
                service.removeAnchor(file, anchor.id)
                render.refresh(file)
            }
        )
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}

/**
 * 删除光标所在的锚点。
 */
class DeleteAnchorAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val service = NoteAnchorService.get(project)
        val render = AnchorRenderService.get(project)

        val anchor = anchorAtCaret(project, editor, file) ?: return
        val removed = service.removeAnchor(file, anchor.id) ?: return
        render.refresh(file)
        registerUndo(project, file, "删除笔记锚点",
            undo = { service.addAnchor(file, removed); render.refresh(file) },
            redo = { service.removeAnchor(file, removed.id); render.refresh(file) }
        )
        NoteLog.notify(
            project,
            "已删除锚点：行 ${anchor.startLine}–${anchor.endLine}（Ctrl+Z 可撤销）"
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isVisible = true
        e.presentation.isEnabled =
            project != null && editor != null && file != null && anchorAtCaret(project, editor, file) != null
    }
}

/**
 * 把一次增删包成一条 undo 记录。
 *
 * 关键：必须声明 affectedDocuments 为本文件的 DocumentReference。
 * Ctrl+Z 走的是 UndoManager.undo(FileEditor)，只认绑定当前 document 的动作；
 * 给 null + isGlobal=true 会被直接跳过（这就是之前撤销没反应的原因）。
 */
internal fun registerUndo(
    project: Project,
    file: VirtualFile,
    name: String,
    undo: () -> Unit,
    redo: () -> Unit
) {
    val reference = runCatching {
        com.intellij.openapi.command.undo.DocumentReferenceManager.getInstance().create(file)
    }.getOrNull()

    val undoManager = UndoManager.getInstance(project)
    CommandProcessor.getInstance().executeCommand(project, {
        undoManager.undoableActionPerformed(object : UndoableAction {
            override fun undo() = undo()
            override fun redo() = redo()
            override fun getAffectedDocuments(): Array<DocumentReference>? =
                reference?.let { arrayOf(it) }

            override fun isGlobal(): Boolean = false
        })
    }, name, null)
}

/** 供别处复用：当前光标处最具体的那个锚点。 */
fun anchorAtCaret(project: Project, editor: com.intellij.openapi.editor.Editor, file: VirtualFile): Anchor? {
    val document = editor.document
    val caret = editor.caretModel.offset.coerceIn(0, document.textLength)

    val resolved = NoteAnchorService.get(project).anchorsFor(file)
        .mapNotNull { anchor -> Anchoring.locate(document, anchor)?.let { anchor to it } }
    if (resolved.isEmpty()) return null

    // ① 光标落在区间内 → 取最窄的（嵌套时就是内层那条）
    resolved.filter { caret >= it.second.startOffset && caret <= it.second.endOffset }
        .minByOrNull { it.second.length }
        ?.let { return it.first }

    val line = document.getLineNumber(caret)

    // ② 起点行 == 光标行
    resolved.filter { document.getLineNumber(it.second.startOffset.coerceIn(0, document.textLength)) == line }
        .minByOrNull { it.second.length }
        ?.let { return it.first }

    // ③ 终点行 == 光标行
    return resolved
        .filter { document.getLineNumber(it.second.endOffset.coerceIn(0, document.textLength)) == line }
        .minByOrNull { it.second.length }
        ?.first
}

/**
 * 从磁盘重新加载并重绘（调试用）。
 */
class RefreshAnchorsAction : AnAction() {



    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val relative = NoteAnchorService.get(project).relativePath(file) ?: return
        NoteAnchorService.get(project).reload(relative)
        AnchorRenderService.get(project).refresh(file)
    }
}

/** 切换"简洁显示"：编辑器里只显示标题，内容折叠。 */
class ToggleCompactAction : AnAction() {



    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val anchor = anchorAtCaret(project, editor, file) ?: return
        val service = NoteAnchorService.get(project)
        service.setAnchorCompact(anchor.id, !service.effectiveCompact(anchor))
        AnchorRenderService.get(project).refresh(file)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isVisible = true
        e.presentation.isEnabled =
            project != null && editor != null && file != null && anchorAtCaret(project, editor, file) != null
    }
}

/** 切换"锁定"：编辑器里不能编辑/删除，只能去面板。 */
class ToggleLockAction : AnAction() {



    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val anchor = anchorAtCaret(project, editor, file) ?: return
        val service = NoteAnchorService.get(project)
        service.setAnchorLocked(anchor.id, !service.effectiveLocked(anchor))
        AnchorRenderService.get(project).refresh(file)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isVisible = true
        e.presentation.isEnabled =
            project != null && editor != null && file != null && anchorAtCaret(project, editor, file) != null
    }
}
