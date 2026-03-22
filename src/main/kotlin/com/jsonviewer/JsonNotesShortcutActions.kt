package com.jsonviewer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Adds a new JSON Notes tab. Resolves the focused or selected [JsonViewerPanel]; if none exists,
 * opens the tool window and creates the tab there.
 */
class JsonNotesNewTabAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        withToolWindowPanelOrShow(project) { it.performNewTab() }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * Shows the JSON Notes tool window and adds a new tab in that window (not the main-editor host).
 */
class JsonNotesOpenWithNewTabAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(JsonViewerPanel.TOOL_WINDOW_ID) ?: return
        tw.show()
        ApplicationManager.getApplication().invokeLater(
            {
                JsonViewerPanel.findJsonViewerPanelFromToolWindow(project)?.performNewTab()
            },
            ModalityState.defaultModalityState(),
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class JsonNotesNextTabAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JsonViewerPanel.findJsonViewerPanel(project)?.performNavigateTab(1)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class JsonNotesPrevTabAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JsonViewerPanel.findJsonViewerPanel(project)?.performNavigateTab(-1)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

private fun withToolWindowPanelOrShow(project: Project, block: (JsonViewerPanel) -> Unit) {
    val panel = JsonViewerPanel.findJsonViewerPanel(project)
    if (panel != null) {
        block(panel)
        return
    }
    val tw = ToolWindowManager.getInstance(project).getToolWindow(JsonViewerPanel.TOOL_WINDOW_ID) ?: return
    tw.show()
    ApplicationManager.getApplication().invokeLater(
        {
            JsonViewerPanel.findJsonViewerPanelFromToolWindow(project)?.let(block)
        },
        ModalityState.defaultModalityState(),
    )
}
