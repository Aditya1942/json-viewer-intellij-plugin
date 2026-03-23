package com.jsonviewer.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType

/**
 * Resolves [FileType] instances for syntax highlighting (popular entries, JSON, Markdown, by name).
 */
object NoteHighlightFileTypeResolver {

    val popularExtensions: List<String> = listOf(
        "json",
        "md",
        "java",
        "kt",
        "kts",
        "py",
        "ts",
        "tsx",
        "js",
        "jsx",
        "xml",
        "yaml",
        "yml",
        "properties",
        "sh",
        "bash",
        "sql",
        "gradle",
        "go",
        "rs",
        "c",
        "h",
        "cpp",
        "cs",
        "rb",
        "php",
        "swift",
    )

    fun findByTypeName(name: String): FileType? {
        val n = name.trim()
        if (n.isEmpty()) return null
        for (ft in FileTypeManager.getInstance().registeredFileTypes) {
            if (ft.name == n) return ft
        }
        return null
    }

    fun getByExtension(ext: String): FileType? {
        val e = ext.removePrefix(".").trim().lowercase()
        if (e.isEmpty()) return null
        return FileTypeManager.getInstance().getFileTypeByFileName("snippet.$e")
    }

    fun jsonFileType(): FileType? = try {
        Class.forName("com.intellij.json.JsonFileType")
            .getDeclaredField("INSTANCE").get(null) as FileType
    } catch (_: Exception) {
        null
    }

    /** JSON highlighting for explicit "JSON" mode when the JSON plugin is absent. */
    fun jsonLikeFileType(): FileType =
        jsonFileType() ?: getByExtension("json") ?: PlainTextFileType.INSTANCE

    fun markdownFileType(): FileType? {
        try {
            val cl = Class.forName("org.intellij.plugins.markdown.lang.MarkdownFileType")
            try {
                val inst = cl.getField("INSTANCE").get(null) as? FileType
                if (inst != null) return inst
            } catch (_: Exception) {
            }
            val m = cl.methods.find { it.name == "getInstance" && it.parameterCount == 0 }
            if (m != null) {
                return m.invoke(null) as? FileType
            }
        } catch (_: Exception) {
        }
        return getByExtension("md")
    }

    fun plainTextFileType(): FileType = PlainTextFileType.INSTANCE

    /**
     * File types for the "All types" picker (non-binary, excluding plain).
     */
    fun searchableEditorFileTypes(): List<FileType> {
        return FileTypeManager.getInstance().registeredFileTypes
            .asSequence()
            .filter { !it.isBinary }
            .filter { it !== PlainTextFileType.INSTANCE }
            .sortedBy { it.name.lowercase() }
            .toList()
    }
}
