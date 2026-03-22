package com.jsonviewer.dev

import com.intellij.openapi.application.ApplicationManager

/**
 * Gates development-only tools (e.g. AllIcons explorer).
 *
 * Enabled when:
 * - `-Djson.notes.dev.icons=true` (set automatically for `./gradlew runIde` in [build.gradle.kts]), or
 * - the IDE is running in [internal mode](https://plugins.jetbrains.com/docs/intellij/enabling-internal.html).
 */
object DevMode {
    private const val PROP = "json.notes.dev.icons"

    fun isDevIconsExplorerEnabled(): Boolean =
        java.lang.Boolean.getBoolean(PROP) ||
            ApplicationManager.getApplication().isInternal
}
