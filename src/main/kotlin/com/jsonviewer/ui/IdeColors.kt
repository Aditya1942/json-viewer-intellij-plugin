package com.jsonviewer.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Same as `com.intellij.util.ui.JBUI.Separator.color()` (`Separator.separatorColor` with platform
 * fallbacks). Use for toolbar / header divider strokes — dark theme fallback is mid-gray, not black.
 */
fun ideSeparatorColor(): Color =
    JBColor.namedColor("Separator.separatorColor", JBColor(Color(0xCD, 0xCD, 0xCD), Color(0x51, 0x51, 0x51)))
