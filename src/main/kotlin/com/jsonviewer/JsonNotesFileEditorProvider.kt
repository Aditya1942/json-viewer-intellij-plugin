package com.jsonviewer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class JsonNotesFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        JsonNotesVirtualFile.isJsonNotesFile(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        JsonNotesFileEditor(project, file)

    override fun getEditorTypeId(): String = "json-notes-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
