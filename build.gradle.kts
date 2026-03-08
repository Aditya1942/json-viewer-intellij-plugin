plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.jsonviewer"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Works with IntelliJ IDEA Community 2023.3+
        intellijIdeaCommunity("2023.3")
        instrumentationTools()
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    publishing {
        token.set(providers.gradleProperty("intellijPlatformPublishingToken"))
    }
    pluginConfiguration {
        id = "com.jsonviewer"
        name = "JSON Viewer"
        version = project.version.toString()
        description = """
            JSON Viewer and Notes for all JetBrains IDEs — format, minify, and visualize JSON with an interactive tree view. Keep multiple JSON snippets in persistent, synced tabs.
            <ul>
              <li><b>Notes-style tabs</b> — multiple document tabs; work with several JSONs side by side; names and content persist</li>
              <li><b>Cross-IDE</b> — tabs shared across IntelliJ, PyCharm, WebStorm, GoLand, Rider, and other JetBrains IDEs</li>
              <li><b>Settings Sync</b> — tabs sync across machines via JetBrains Settings Sync</li>
              <li><b>Format & minify</b> — pretty-print or remove whitespace; works on any text, preserves strings</li>
              <li><b>Tree view</b> — interactive tree with color-coded types; expand/collapse all; copy value from context menu</li>
              <li><b>Property grid</b> — inspect selected node key/value or children in a side panel</li>
              <li><b>Search</b> — Cmd+F / Ctrl+F with next/previous and highlights in both text and tree</li>
              <li><b>Paste / Copy</b> — quick clipboard actions in the toolbar</li>
            </ul>
            <p>Inspired by <a href="https://jsonviewer.stack.hu/">Online JSON Viewer (jsonviewer.stack.hu)</a>.</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "233"
            untilBuild = provider { null }
        }

        vendor {
            name = "Aditya"
        }
    }
}

// Skip buildSearchableOptions — not needed for a tool window plugin
tasks.named("buildSearchableOptions") {
    enabled = false
}
