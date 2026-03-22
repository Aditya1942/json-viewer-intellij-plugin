package com.jsonviewer

import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * Full JSON Notes UI in the main editor area (not the bottom tool window).
 */
class JsonNotesFileEditor(
    private val project: Project,
    private val file: VirtualFile
) : UserDataHolderBase(), TextEditor, DataProvider {

    private val panel = JsonViewerPanel(
        project,
        sharedEditorDocument = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        }
    )
    private val changeSupport = PropertyChangeSupport(this)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "JSON Notes"

    override fun getFile(): VirtualFile = file

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun setState(state: FileEditorState) {}

    override fun selectNotify() {}

    override fun deselectNotify() {}

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        changeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        changeSupport.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getEditor(): Editor = panel.embeddedEditor()

    override fun canNavigateTo(navigatable: Navigatable): Boolean = false

    override fun navigateTo(navigatable: Navigatable) {}

    /**
     * Main editor tab host must expose [DataProvider]; otherwise global Undo/Redo does not resolve
     * the embedded [JsonViewerPanel] editor (tool window still works via normal focus context).
     */
    override fun getData(dataId: String): Any? = panel.getData(dataId)

    /** For keyboard shortcuts and actions that need the root [JsonViewerPanel]. */
    internal fun viewerPanel(): JsonViewerPanel = panel

    override fun dispose() {
        Disposer.dispose(panel)
    }
}
