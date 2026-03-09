package com.jsonviewer.ui

import java.awt.Font

/**
 * Shared font constants for the JSON Notes plugin.
 * Avoids creating duplicate Font objects in multiple components.
 */
object PluginFonts {
    /** Monospaced font for code display — prefers JetBrains Mono, falls back to system monospaced. */
    val MONO: Font = Font("JetBrains Mono", Font.PLAIN, 13).let { f ->
        if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 13)
    }
}
