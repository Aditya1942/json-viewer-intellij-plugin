package com.jsonviewer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager

/**
 * Opens JSON Notes in the main editor and creates a new tab (same as opening the editor, then New tab).
 */
class OpenJsonNotesInEditorWithNewTabAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openJsonNotesInMainEditor(project)
        ApplicationManager.getApplication().invokeLater(
            {
                val selected = FileEditorManager.getInstance(project).selectedEditor
                if (selected is JsonNotesFileEditor) {
                    selected.viewerPanel().performNewTab()
                }
            },
            ModalityState.defaultModalityState(),
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
