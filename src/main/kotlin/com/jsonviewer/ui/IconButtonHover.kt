package com.jsonviewer.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton

/**
 * Hover fill derived from current LaF [JBColor.background] / [JBColor.foreground],
 * so light and dark themes stay consistent with the IDE.
 */
private fun iconButtonHoverBackground(): Color {
    val bg = JBColor.background()
    val fg = JBColor.foreground()
    return mixColors(bg, fg, 0.1)
}

private fun mixColors(c1: Color, c2: Color, ratio: Double): Color {
    val r = (c1.red * (1 - ratio) + c2.red * ratio).toInt().coerceIn(0, 255)
    val g = (c1.green * (1 - ratio) + c2.green * ratio).toInt().coerceIn(0, 255)
    val b = (c1.blue * (1 - ratio) + c2.blue * ratio).toInt().coerceIn(0, 255)
    val a = (c1.alpha * (1 - ratio) + c2.alpha * ratio).toInt().coerceIn(0, 255)
    return Color(r, g, b, a)
}

/**
 * Flat icon [JButton]s: show background on hover. Use [onExitRestore] when exit must
 * re-apply another style (e.g. Text/Viewer mode toggles).
 */
fun JButton.installIconButtonHover(onExitRestore: (() -> Unit)? = null) {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            if (!isEnabled) return
            isOpaque = true
            background = iconButtonHoverBackground()
            repaint()
        }

        override fun mouseExited(e: MouseEvent) {
            if (onExitRestore != null) {
                onExitRestore()
            } else {
                isOpaque = false
                background = null
                repaint()
            }
        }
    })
}
