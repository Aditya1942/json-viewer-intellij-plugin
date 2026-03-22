import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.jsonviewer"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Dev / runIde: 2024.3+ parses Java 25 in GradleJvmSupportMatrix; 2023.3 throws on "25".
        intellijIdeaCommunity("2024.3")
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

intellijPlatform {
    publishing {
        token.set(
            providers.gradleProperty("intellijPlatformPublishingToken")
                .orElse(providers.provider {
                    val f = rootProject.file("publish.properties")
                    if (f.exists()) {
                        val props = Properties()
                        f.reader().use { props.load(it) }
                        props.getProperty("intellijPlatformPublishingToken") ?: ""
                    } else ""
                })
        )
    }
    pluginConfiguration {
        id = "com.jsonviewer"
        name = "JSON Notes"
        version = project.version.toString()
        description = """
            JSON Notes for all JetBrains IDEs — format, minify, and visualize JSON with an interactive tree view. Keep multiple JSON snippets in persistent, synced tabs.
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
