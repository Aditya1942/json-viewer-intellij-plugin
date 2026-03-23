# Publish JSON Notes **1.0.4** (release tag `v1.0.4`)

Use this guide for the **1.0.4** Marketplace update. General steps: [JETBRAINS_MARKETPLACE_PUBLISH_STEPS.md](JETBRAINS_MARKETPLACE_PUBLISH_STEPS.md) and [PUBLISH_CHECKLIST.md](PUBLISH_CHECKLIST.md).

---

## Release metadata

| Item | Value |
|------|--------|
| **Version** | `1.0.4` (`build.gradle.kts` → `version`) |
| **Git tag** | `v1.0.4` (annotated recommended) |
| **Baseline tag** | `v1.0.3` — change catalog below is `v1.0.3..HEAD` |
| **Release date (notes)** | 2026-03-23 |

---

## Change catalog (`git log v1.0.3..HEAD`)

Commits included after tag **`v1.0.3`** through **latest commit** at publish time:

| Commit | Message |
|--------|---------|
| `1d75917` | Release 1.0.3 — editor undo/redo and TextEditor integration |
| `cd459ea` | Implement keyboard shortcuts for JSON Notes and enhance UI settings |

**Note:** `1d75917` only adjusts **README** and **plugin.xml** wording for the 1.0.3 listing; the functional delta for **1.0.4** is **`cd459ea`** (shortcuts, actions, settings UI). Version **1.0.4** bundles everything since `v1.0.3` so Marketplace “What’s new” and the zip match the repo state.

### Files touched (`git diff v1.0.3..HEAD --stat`)

| Area | Files |
|------|--------|
| Product | `JsonNotesShortcutActions.kt` (new), `JsonNotesShortcutsUi.kt` (new), `OpenJsonNotesInEditorWithNewTabAction.kt` (new), `JsonViewerToolWindowFactory.kt`, `JsonNotesFileEditor.kt`, `OpenJsonViewerAction.kt` |
| Metadata | `plugin.xml` (actions, shortcuts, keymap group, change notes, description) |
| Docs | `README.md` |

---

## Copy-and-paste: Marketplace “What’s new”

Use the **same** HTML as `<change-notes>` for **1.0.4** in `plugin.xml`, or paste this block in the Marketplace upload form if you maintain text outside the repo:

```html
<p><b>1.0.4</b> (2026-03-23)</p>
<ul>
  <li><b>Keyboard shortcuts</b> — default bindings where applicable: Open JSON Notes with New Tab (Ctrl+Shift+Alt+N), New JSON Notes Tab (Ctrl+Shift+Alt+T), Open JSON Notes in Editor (Ctrl+Shift+Alt+J), Open in Editor with New Tab on macOS (⌥⌘J). Next / Previous tab actions are in Keymap (search "JSON Notes") with no default shortcut.</li>
  <li><b>Actions</b> — new tab, next/previous tab, open with new tab, and open editor with new tab work from the active JSON Notes panel (tool window or main editor).</li>
  <li><b>Settings</b> — "Keyboard shortcuts" section lists JSON Notes actions and shows the current shortcuts from your active keymap; link text points to Settings → Keymap.</li>
  <li><b>Keymap</b> — all JSON Notes actions are grouped under JSON Notes for search and rebinding.</li>
</ul>
```

---

## Build and publish checklist

1. **Verify version** — `build.gradle.kts` shows `version = "1.0.4"`; `plugin.xml` `<change-notes>` lead with **1.0.4**.
2. **Build** — `./gradlew buildPlugin` → `build/distributions/json-viewer-jetbrains-1.0.4.zip`.
3. **Smoke test** — Install from disk; test new shortcuts and Keymap group **JSON Notes**; open the shortcuts table in JSON Notes settings.
4. **Upload** — Marketplace → plugin admin → new version → upload ZIP; paste **What’s new** (above or from `plugin.xml`).
5. **Tag after merge** (when `main` is ready to release):

```bash
git tag -a v1.0.4 -m "Release 1.0.4 — keyboard shortcuts, tab actions, settings shortcut reference"
git push origin v1.0.4
```

6. **GitHub Release** (optional) — Create release from tag `v1.0.4`; attach `json-viewer-jetbrains-1.0.4.zip` from `build/distributions/`.

---

## References

- [Publishing and listing your plugin](https://plugins.jetbrains.com/docs/marketplace/publishing-and-listing-your-plugin.html)
- Plugin page: [JSON Notes on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30568-json-notes)
