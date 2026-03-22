package com.jsonviewer

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * One virtual file per project so reopening focuses the same editor tab instead of duplicating.
 */
class JsonNotesEditorVirtualFileService(@Suppress("UNUSED_PARAMETER") project: Project) {

    private var file: JsonNotesVirtualFile? = null

    fun getOrCreateFile(): JsonNotesVirtualFile {
        file?.let { return it }
        val created = JsonNotesVirtualFile()
        file = created
        return created
    }

    companion object {
        fun getInstance(project: Project): JsonNotesEditorVirtualFileService = project.service()
    }
}
