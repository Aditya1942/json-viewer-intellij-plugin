package com.jsonviewer

import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil

/** Action IDs and labels for the JSON Notes in-app settings (keyboard shortcuts section). */
object JsonNotesShortcutsUi {

    val ACTION_ROWS: List<Pair<String, String>> = listOf(
        "JsonViewer.Open" to "Open",
        "JsonViewer.OpenWithNewTab" to "Open with new tab",
        "JsonViewer.NewTab" to "New tab",
        "JsonViewer.NextTab" to "Next",
        "JsonViewer.PrevTab" to "Prev",
        "JsonViewer.OpenInEditor" to "Open in editor",
        "JsonViewer.OpenInEditorWithNewTab" to "Open in editor with new Tab",
    )

    fun shortcutText(actionId: String): String {
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(actionId)
        return KeymapUtil.getShortcutsText(shortcuts).ifBlank { "—" }
    }
}
