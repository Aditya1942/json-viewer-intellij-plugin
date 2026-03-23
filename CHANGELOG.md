# Changelog

All notable changes to **JSON Notes** are documented here. The version in `build.gradle.kts` and the `<change-notes>` in `plugin.xml` should stay aligned with Marketplace releases.

## [1.0.4] — 2026-03-23

### Added
- **Keyboard shortcuts** — Default keybindings (where assigned in `plugin.xml`): Open JSON Notes with New Tab (**Ctrl+Shift+Alt+N**), New JSON Notes Tab (**Ctrl+Shift+Alt+T**), Open JSON Notes in Editor (**Ctrl+Shift+Alt+J**), Open in Editor with New Tab on macOS (**⌥⌘J**). Next/Previous tab actions have no default binding; assign under **Settings → Keymap** (group **JSON Notes**).
- **Actions** — `JsonNotesOpenWithNewTabAction`, `JsonNotesNewTabAction`, `JsonNotesNextTabAction`, `JsonNotesPrevTabAction`, `OpenJsonNotesInEditorWithNewTabAction`; `JsonNotesShortcutActions.kt` centralizes tab/navigation behavior with tool-window fallback.
- **Settings UI** — “Keyboard shortcuts” table in JSON Notes settings (`JsonNotesShortcutsUi`, `JsonViewerToolWindowFactory`) showing action labels and live shortcut text from the active keymap.

### Changed
- **README** and **plugin.xml** — Refined 1.0.3 marketplace wording (commit after tag `v1.0.3`).
- **Plugin description** — New bullet for keyboard shortcuts and settings reference (aligned in `build.gradle.kts` and `plugin.xml`).

### Development
- **`JsonNotesFileEditor`** — Minor integration hook for shortcuts/actions.
- **`OpenJsonViewerAction`** — Doc/comment alignment with shortcut story.

## [1.0.3] — 2026-03-22

### Fixed
- **Main editor** — virtual file is writable so **Undo / Redo** works when JSON Notes is opened in the editor area.
- **Editor integration** — `JsonNotesFileEditor` implements `TextEditor` and `DataProvider` for correct IDE behavior.
- **Tool window ↔ editor** — shared document between the bottom tool window and the main editor where applicable.
- **Text updates** — safer threading and caret handling in `TextContentPanel` when content changes.

## [1.0.2] — 2026-03-22

### Added
- Open JSON Notes in the **main editor** area (`JsonNotesFileEditor`, virtual file per project), with action **Tools → Open JSON Notes in Editor** and shortcut **Ctrl+Shift+Alt+J** (⌘⇧⌥J on macOS).
- **JsonViewerUiSettings** — application-level font family/size for the JSON Notes editor.
- Settings to **hide selected toolbar actions** for a cleaner tool window.
- **Search panel** improvements with debounced search; **IconButtonHover** and visual separator tweaks.

### Changed
- Tab bar: **delete confirmation** and improved header layout (new / previous / next).
- **Kotlin 2.0.21**, IntelliJ Platform **2025.1** for development builds; `sinceBuild` remains **241** for broad IDE compatibility.
- Viewer layout: one-pixel splitter; toolbar and settings integration refactors.

### Fixed / stability
- **ReadAction** / read locks for document and tree validation to avoid EDT/threading issues.
- Kotlin compile: **all warnings as errors** enabled in Gradle.

### Development / repo
- Dev-only **AllIcons explorer** when running with `-Djson.notes.dev.icons=true` (see `runIde` in `build.gradle.kts`).
- README: JetBrains Marketplace link and documentation updates.

## [1.0.1] — earlier

- Initial public feature set: format, minify, tree view, tabs, Settings Sync, search, property grid, and related tooling.

[1.0.4]: https://github.com/Aditya1942/json-viewer-intellij-plugin/releases
[1.0.3]: https://github.com/Aditya1942/json-viewer-intellij-plugin/releases
[1.0.2]: https://github.com/Aditya1942/json-viewer-intellij-plugin/releases
