package com.jsonviewer.ui

import com.intellij.openapi.fileTypes.FileType

/**
 * How syntax highlighting is chosen for a note tab. Serialized to [SavedTab.highlightMode].
 */
sealed class NoteHighlightMode {

    /** Plain text; JSON highlighting + folding when content looks like JSON (legacy behavior). */
    data object Plain : NoteHighlightMode()

    /** Infer JSON first, then content heuristics, then plain. */
    data object Auto : NoteHighlightMode()

    /** Fixed IDE [FileType] highlighting. */
    data class Explicit(val fileType: FileType) : NoteHighlightMode()

    companion object {
        const val SERIAL_PLAIN = "plain"
        const val SERIAL_AUTO = "auto"
        private const val PREFIX_EXPLICIT = "explicit:"

        fun default(): NoteHighlightMode = Plain

        fun fromSerialized(raw: String?): NoteHighlightMode {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s == SERIAL_PLAIN) return Plain
            if (s == SERIAL_AUTO) return Auto
            if (s.startsWith(PREFIX_EXPLICIT)) {
                val name = s.removePrefix(PREFIX_EXPLICIT)
                val ft = NoteHighlightFileTypeResolver.findByTypeName(name)
                if (ft != null) return Explicit(ft)
            }
            return Plain
        }

        fun toSerialized(mode: NoteHighlightMode): String = when (mode) {
            is Plain -> SERIAL_PLAIN
            is Auto -> SERIAL_AUTO
            is Explicit -> PREFIX_EXPLICIT + mode.fileType.name
        }
    }
}
