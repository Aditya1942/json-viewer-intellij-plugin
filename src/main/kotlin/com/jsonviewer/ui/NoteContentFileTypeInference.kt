package com.jsonviewer.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType

/**
 * Content-based language guess for [NoteHighlightMode.Auto] (after JSON is ruled out).
 */
object NoteContentFileTypeInference {

    fun inferNonJson(text: String): FileType? {
        if (text.isBlank()) return null
        val sample = if (text.length > 12000) text.substring(0, 12000) else text
        val firstLine = sample.lineSequence().firstOrNull { it.isNotBlank() } ?: ""

        // Shebang
        if (firstLine.startsWith("#!")) {
            when {
                firstLine.contains("python", ignoreCase = true) ->
                    return NoteHighlightFileTypeResolver.getByExtension("py")
                firstLine.contains("node", ignoreCase = true) ->
                    return NoteHighlightFileTypeResolver.getByExtension("js")
                firstLine.contains("bash", ignoreCase = true) ||
                    firstLine.contains("/sh", ignoreCase = true) ->
                    return NoteHighlightFileTypeResolver.getByExtension("sh")
            }
        }

        platformDetectFileType(sample)?.let { return it }

        // Markdown: headings, fenced code, links
        if (looksLikeMarkdown(sample)) {
            return NoteHighlightFileTypeResolver.markdownFileType()
                ?: NoteHighlightFileTypeResolver.getByExtension("md")
        }

        // XML / HTML
        val trimmedStart = sample.trimStart()
        if (trimmedStart.startsWith("<?xml", ignoreCase = true)) {
            return NoteHighlightFileTypeResolver.getByExtension("xml")
        }

        // Kotlin
        if (Regex("""\b(package|import|fun|val|var|class|object|interface)\s+""").containsMatchIn(sample)) {
            if (sample.contains("fun ") || sample.contains("val ") || sample.contains("package ")) {
                return NoteHighlightFileTypeResolver.getByExtension("kt")
            }
        }

        // Java
        if (Regex("""\b(public|private|protected|class|interface|enum)\s+""").containsMatchIn(sample)) {
            if (sample.contains("public class") || sample.contains("import java")) {
                return NoteHighlightFileTypeResolver.getByExtension("java")
            }
        }

        // Python
        if (Regex("""(^|\n)\s*(def |class |import |from )""").containsMatchIn(sample)) {
            if (sample.contains("def ") || sample.contains("import ") || Regex("""from \w+ import""").containsMatchIn(sample)) {
                return NoteHighlightFileTypeResolver.getByExtension("py")
            }
        }

        // TypeScript / TSX / JS
        if (Regex("""\b(export|import|interface|type|const|let|function)\s+""").containsMatchIn(sample)) {
            if (sample.contains("tsx") || sample.contains("jsx") || Regex("<[A-Z][a-zA-Z]*").containsMatchIn(sample)) {
                return NoteHighlightFileTypeResolver.getByExtension("tsx")
            }
            if (sample.contains(": string") || sample.contains(": number") || sample.contains("interface ")) {
                return NoteHighlightFileTypeResolver.getByExtension("ts")
            }
            return NoteHighlightFileTypeResolver.getByExtension("js")
        }

        // YAML
        if (Regex("""(?m)^[\s]*[\w.-]+:\s*(\S|$)""").containsMatchIn(sample) &&
            !sample.contains("{") && sample.count { it == ':' } >= 2
        ) {
            return NoteHighlightFileTypeResolver.getByExtension("yaml")
        }

        // Properties
        if (Regex("""(?m)^[\s]*[a-zA-Z0-9_.-]+\s*=\s*""").containsMatchIn(sample)) {
            return NoteHighlightFileTypeResolver.getByExtension("properties")
        }

        return null
    }

    private fun looksLikeMarkdown(text: String): Boolean {
        if (Regex("""(?m)^#{1,6}\s+\S+""").containsMatchIn(text)) return true
        if (text.contains("```")) return true
        if (Regex("""\[[^\]]+\]\([^)]+\)""").containsMatchIn(text)) return true
        return false
    }

    /**
     * Try IntelliJ buffer-based detection if available on the platform (best-effort).
     */
    private fun platformDetectFileType(content: String): FileType? {
        try {
            val registryClass = Class.forName("com.intellij.openapi.fileTypes.FileTypeRegistry")
            val registry = registryClass.getMethod("getInstance").invoke(null) ?: return null
            val bytes = content.toByteArray(Charsets.UTF_8)
            for (method in registry.javaClass.methods) {
                if (method.name != "detectFileTypeFromContent" && method.name != "getFileTypeByContent") continue
                try {
                    val result: FileType? = when (method.parameterTypes.size) {
                        1 -> when (method.parameterTypes[0]) {
                            ByteArray::class.java -> method.invoke(registry, bytes) as? FileType
                            CharSequence::class.java -> method.invoke(registry, content as CharSequence) as? FileType
                            String::class.java -> method.invoke(registry, content) as? FileType
                            else -> null
                        }
                        2 -> if (method.parameterTypes[0] == ByteArray::class.java &&
                            method.parameterTypes[1] == CharSequence::class.java
                        ) {
                            method.invoke(registry, bytes, content as CharSequence) as? FileType
                        } else {
                            null
                        }
                        else -> null
                    }
                    if (result != null && result !== PlainTextFileType.INSTANCE && !result.isBinary) {
                        return result
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: ClassNotFoundException) {
        } catch (_: Exception) {
        }
        return null
    }
}
