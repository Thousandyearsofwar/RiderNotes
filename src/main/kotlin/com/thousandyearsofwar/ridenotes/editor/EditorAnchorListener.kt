package com.thousandyearsofwar.ridenotes.editor

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

/**
 * 编辑器一创建就尝试挂锚点；关闭时清掉。
 */
class EditorAnchorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        AnchorRenderService.get(project).attach(editor, file)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        AnchorRenderService.get(project).detach(editor)
    }
}
