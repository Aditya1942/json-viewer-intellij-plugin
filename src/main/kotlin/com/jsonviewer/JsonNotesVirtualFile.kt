package com.jsonviewer

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * Virtual document shown as a main-editor tab; same capabilities as the JSON Notes tool window.
 *
 * Uses [LightVirtualFile] from the test-framework module (shipped in the IDE) because
 * `com.intellij.openapi.vfs.LightVirtualFile` is not exposed on the plugin compile classpath for IC 2023.3.
 */
class JsonNotesVirtualFile : LightVirtualFile(
    "JSON Notes",
    PlainTextFileType.INSTANCE,
    ""
) {
    init {
        // Tab JSON lives in [TabStorageService]; the VFS buffer is still updated via the file [Document].
        // Writable + file-backed document is required for main-editor Undo/Redo (see JsonNotesFileEditor).
        isWritable = true
    }

    companion object {
        fun isJsonNotesFile(file: VirtualFile): Boolean = file is JsonNotesVirtualFile
    }
}
