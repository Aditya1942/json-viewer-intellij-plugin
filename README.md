# JSON Notes

A **JSON viewer and notes** plugin for **all JetBrains IDEs** (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, PhpStorm, RubyMine, etc.). View, format, minify, and organize JSON in a dedicated tool window with persistent tabs that sync across IDEs and machines.

---

## Features

### Viewing & editing
- **Text mode** — Edit raw JSON in a syntax-friendly editor (2-space indent).
- **Tree (Viewer) mode** — Interactive tree view with color-coded types (object, array, string, number, boolean, null).
- **Property grid** — Select any node in the tree to see its key and value (or child summary) in a side panel.
- **Expand / collapse** — Expand all or collapse all from the toolbar or via right-click context menu on any node.
- **Copy value** — Right-click a tree node → “Copy value” to copy that node’s value to the clipboard.

### Formatting
- **Format** — Pretty-print JSON with consistent indentation (works on any text; preserves content inside strings).
- **Minify** — Remove whitespace while preserving string content.

### Tabs & notes
- **Multiple tabs** — Work with several JSON documents side by side; each tab has a name and stores JSON text.
- **Persistent tabs** — Tabs are saved automatically and restored when you reopen the IDE.
- **Cross-IDE tabs** — Tabs are shared across all JetBrains IDEs on the same machine via a shared file (`~/.config/json-viewer/tabs.json` on Linux/macOS, `%APPDATA%\json-viewer\tabs.json` on Windows).
- **JetBrains Settings Sync** — When you enable [Settings Sync](https://www.jetbrains.com/help/idea/settings-sync.html), your JSON Notes tabs sync across machines.
- **Tree expansion state** — Which nodes you expanded in the tree is remembered per tab and restored when you switch back.

### Search
- **Find** — **Ctrl+F** / **Cmd+F** opens a search bar; works in both Text and Viewer mode.
- **Next / previous** — Jump between matches; match count is shown.
- **Highlights** — Matching text is highlighted (yellow); in the tree, matching nodes are highlighted and parents expanded to reveal them.

### Clipboard & actions
- **Paste** — Paste from clipboard into the current tab (e.g. API responses).
- **Copy** — Copy the full JSON from the current tab to the clipboard.

### Navigation
- **Tab bar** — Previous / Next / New tab / Delete tab; tab title shows `[current/total] Tab name`.
- **Shortcut** — **Ctrl+Shift+J** (Windows/Linux) or **Cmd+Shift+J** (macOS) opens the JSON Notes tool window. Also available under **Tools → Open JSON Notes**.

---

## Installation

1. In your JetBrains IDE: **Settings/Preferences → Plugins → Marketplace**.
2. Search for **JSON Notes**.
3. Install and restart the IDE.

Or install from disk: **Settings → Plugins → ⚙️ → Install Plugin from Disk** and select the built `*.zip` from the project’s `build/distributions/` folder.

---

## How to use

1. Open the tool window: **Tools → Open JSON Notes** or **Ctrl+Shift+J** / **Cmd+Shift+J**.
2. Paste or type JSON in the text area (Text mode).
3. Use **Format** / **Minify** as needed.
4. Switch to **Viewer** to see the tree; select nodes to inspect them in the property grid.
5. Use multiple tabs to keep several JSON snippets or API responses; they persist and sync as described above.

---

## Build from source

```bash
./gradlew buildPlugin
```

The plugin archive is produced in `build/distributions/`. Use **Install Plugin from Disk** and select that zip.

Run a sandbox IDE with the plugin loaded:

```bash
./gradlew runIde
```

---

## Credit

The idea for this plugin was inspired by the [Online JSON Viewer](https://jsonviewer.stack.hu/) by [jsonviewer.stack.hu](https://jsonviewer.stack.hu/). That project has been helping users format and visualize JSON since 2008. This JetBrains plugin brings a similar experience into the IDE with extra features like persistent notes-style tabs and cross-IDE sync.

---

---

## JetBrains Marketplace

When you publish or update the plugin on [JetBrains Marketplace](https://plugins.jetbrains.com/), use the description from `plugin.xml` (or the one in `build.gradle.kts` if you use the Gradle plugin to generate metadata). The **plugin description** is already set in this repo and includes credit to [jsonviewer.stack.hu](https://jsonviewer.stack.hu/).

### Suggested tags (for searchability)

Select these in the **Plugin Upload** form or under **General Information** in the plugin’s admin panel:

- **JSON**
- **Formatter**
- **Tools**
- **Data**
- **Viewer**
- **Developer tools**

Pick the tags that best match the marketplace’s available list; the exact tag names may vary. Tags improve discoverability when users search for “JSON”, “formatter”, “viewer”, etc.

---

## License

See the repository for license information.
