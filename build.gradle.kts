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
    pluginConfiguration {
        id = "com.jsonviewer"
        name = "JSON Viewer"
        version = project.version.toString()
        description = """
            JSON Viewer and Formatter — Format, minify, and visualize JSON with an interactive tree view.
            <ul>
              <li>Multiple document tabs — work with several JSONs side by side</li>
              <li>Cross-IDE tab persistence — tabs are shared across all JetBrains IDEs</li>
              <li>JetBrains Cloud sync — tabs sync across machines via Settings Sync</li>
              <li>Format any text (not just valid JSON) with smart indentation</li>
              <li>Remove whitespace while preserving string content</li>
              <li>Interactive tree view with color-coded types</li>
              <li>Search with Cmd+F / Ctrl+F</li>
              <li>Property grid for selected nodes</li>
            </ul>
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
