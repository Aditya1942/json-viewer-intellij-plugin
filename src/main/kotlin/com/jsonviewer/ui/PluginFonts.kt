package com.jsonviewer.ui

import java.awt.Font

/**
 * Shared font constants for the JSON Notes plugin.
 * Avoids creating duplicate Font objects in multiple components.
 */
object PluginFonts {
    /** Monospaced font for code display — prefers JetBrains Mono, falls back to system monospaced. */
    val MONO: Font = Font(defaultFamilyName(), Font.PLAIN, 13)

    /** Logical family name for persisted UI settings (JetBrains Mono when installed, else [Font.MONOSPACED]). */
    fun defaultFamilyName(): String {
        val probe = Font("JetBrains Mono", Font.PLAIN, 12)
        return if (probe.family == "JetBrains Mono") "JetBrains Mono" else Font.MONOSPACED
    }
}
