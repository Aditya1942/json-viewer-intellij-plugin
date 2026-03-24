# Publish JSON Notes **1.0.5** (release tag `v1.0.5`)

Use this guide for the **1.0.5** Marketplace update. General steps: [JETBRAINS_MARKETPLACE_PUBLISH_STEPS.md](JETBRAINS_MARKETPLACE_PUBLISH_STEPS.md) and [PUBLISH_CHECKLIST.md](PUBLISH_CHECKLIST.md).

---

## Release metadata

| Item | Value |
|------|--------|
| **Version** | `1.0.5` (`build.gradle.kts` → `version`) |
| **Git tag** | `v1.0.5` (annotated recommended) |
| **Baseline tag** | `v1.0.4` — change catalog below is `v1.0.4..HEAD` |
| **Release date (notes)** | 2026-03-25 |

---

## Change catalog (`git log v1.0.4..HEAD`)

Commits included after tag **`v1.0.4`** through **latest commit** at publish time:

| Commit | Message |
|--------|---------|
| `49358aa` | Enhance JSON Viewer UI with new layout and settings options |
| `63724d1` | Add plain text keyword highlighting feature to JSON Viewer |
| `d97d321` | Refactor JSON Notes tab actions and enhance keyboard focus handling |
| `90dee29` | Implement word occurrence highlighting in TextContentPanel |

### Files touched (`git diff v1.0.4..HEAD --stat`)

| Area | Files |
|------|--------|
| Product | `JsonViewerToolWindowFactory.kt`, `JsonViewerUiSettings.kt`, `JsonNotesShortcutActions.kt`, `ui/JsonViewerChrome.kt`, `ui/PlainTextKeywordsSyntaxHighlighter.kt`, `ui/TextContentPanel.kt`, `ui/SearchPanel.kt`, `ui/ViewerContentPanel.kt` |
| Metadata | `plugin.xml` (`<change-notes>`), `build.gradle.kts` (version, description bullet) |
| Docs | `CHANGELOG.md`, `README.md`, `docs/VERSION_1.0.5_PUBLISH_GUIDE.md` |

---

## Copy-and-paste: Marketplace “What’s new”

Use the **same** HTML as `<change-notes>` for **1.0.5** in `plugin.xml`, or paste this block in the Marketplace upload form:

```html
<p><b>1.0.5</b> (2026-03-25)</p>
<ul>
  <li><b>Plain text</b> — optional <strong>keyword highlighting</strong> for common programming keywords (colors per language family); toggle in JSON Notes settings.</li>
  <li><b>Text editor</b> — <strong>word occurrence highlighting</strong> for the word at the caret or selection (debounced updates).</li>
  <li><b>Layout</b> — refined toolbar and viewer spacing; optional <strong>side toolbar</strong>; consistent chrome and borders.</li>
  <li><b>Tabs &amp; focus</b> — tab shortcuts avoid stealing focus from editable text and the notes search field; improved next/previous tab behavior.</li>
</ul>
```

---

## Build and publish checklist

1. **Verify version** — `build.gradle.kts` shows `version = "1.0.5"`; `plugin.xml` `<change-notes>` lead with **1.0.5**.
2. **Build** — `./gradlew buildPlugin` → `build/distributions/json-viewer-jetbrains-1.0.5.zip`.
3. **Smoke test** — Install from disk; test plain text keyword toggle, word highlights, side toolbar, tab shortcuts with caret in editor/search.
4. **Publish (CLI)** — Ensure `publish.properties` (see `.gitignore`) or `-PintellijPlatformPublishingToken=...` is set, then `./gradlew publishPlugin`.
5. **Upload** (if not using CLI) — Marketplace → plugin admin → new version → upload ZIP; paste **What’s new** (above or from `plugin.xml`).
6. **Tag after merge** (when `main` is ready to release):

```bash
git tag -a v1.0.5 -m "Release 1.0.5 — plain text keywords, word highlights, layout, tab focus"
git push origin v1.0.5
```

7. **GitHub Release** (optional) — Create release from tag `v1.0.5`; attach `json-viewer-jetbrains-1.0.5.zip` from `build/distributions/`.

---

## References

- [Publishing and listing your plugin](https://plugins.jetbrains.com/docs/marketplace/publishing-and-listing-your-plugin.html)
- Plugin page: [JSON Notes on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30568-json-notes)
