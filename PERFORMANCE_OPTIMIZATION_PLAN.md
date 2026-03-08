# JSON Viewer Plugin — Performance Optimization Implementation Plan

## Summary

Analysis of the JetBrains JSON Viewer plugin for IntelliJ Platform API compliance, lifecycle, threading, persistence, and performance patterns. Below: concise numbered plan, assumptions, risks, verification steps, and findings with file:line references.

---

## 1. Implementation Plan (Numbered)

1. **Fix Disposable lifecycle** — Make tool window content `Disposable`; register panel with content's `Disposable`; remove `TabStorageListener` and stop all `Timer`s in `dispose()`.
2. **Move file I/O off EDT** — Run `TabStorageService.saveToSharedFile()` and `mergeFromSharedFile()` on a background thread (e.g. `ApplicationManager.getApplication().executeOnPooledThread {}`); schedule listener notification on EDT after completion.
3. **Parse large JSON off EDT** — Run `JsonParser.parseString(text)` and `viewerContent.loadJson()` in `ProgressManager.getInstance().run()` or `executeOnPooledThread` with a progress indicator; apply result in `SwingUtilities.invokeLater`.
4. **Harden PersistentStateComponent** — Have `getState()` return a defensive copy of `JsonViewerTabsState` (and copy of `tabs` list) so the platform never holds the mutable instance.
5. **Optional: lighter-weight state** — Consider storing only tab metadata in XML (names, ids, activeId) and large `jsonText` in a separate file or `RoamingType.DISABLED` storage to reduce sync size and save time.
6. **Replace deprecated key binding** — Replace `Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx` with `InputEvent.CTRL_DOWN_MASK` / `InputEvent.META_DOWN_MASK` (or Keymap-based action) at `JsonViewerToolWindowFactory.kt:206`.
7. **Use IntelliJ clipboard** — Replace `Toolkit.getDefaultToolkit().systemClipboard` with `CopyPasteManager.getInstance()` for paste/copy in panel and tree (optional consistency improvement).
8. **Optional: AsyncTreeModel for large trees** — For very large JSON, consider building tree structure in background and using `AsyncTreeModel` so expanding nodes doesn’t block EDT.
9. **Optional: ContentFactory** — Prefer `ContentFactory.getService(project)` over `ContentFactory.getInstance()` and pass `project` into the panel for future use (e.g. `project.getMessageBus()`, project-scoped services).

---

## 2. Assumptions

- **Tool window content disposal**: When the tool window or content is removed, the platform does not automatically dispose a custom panel; the panel must implement `Disposable` and be registered so it is disposed (e.g. with `ContentManager` or by making the content the disposable parent).
- **Listener lifetime**: `TabStorageService` is application-level; tool window content is project-scoped. Listeners added by a panel must be removed when the panel is disposed to avoid leaks and callbacks after dispose.
- **EDT**: All UI updates (applyTabs, refreshTabBarState, loadJson into tree) must run on EDT; heavy work (file I/O, JSON parse) must not.
- **State size**: Current design persists full tab list with full `jsonText` in both XML and shared JSON file; acceptable for moderate use; optimization is optional.

---

## 3. Risks

- **Moving I/O off EDT**: Ensure only one writer at a time for the shared file; debounce or queue writes to avoid concurrent write conflicts.
- **Defensive copy in getState()**: Slightly higher CPU/memory on each save; acceptable unless state is very large.
- **AsyncTreeModel**: Larger refactor; only justified if users report UI freezes on big JSON.

---

## 4. Verification Steps

1. **Disposable**: Close tool window, trigger tab change from another IDE or reload; confirm no callback into disposed panel (no NPE, no updates). Run with "Disposable" debug logging if available.
2. **EDT**: Add `ApplicationManager.getApplication().assertIsDispatchThread()` in UI update paths; run with "EDT violations" checker; ensure no assertion failures.
3. **File I/O**: Add logging in `saveToSharedFile` / `mergeFromSharedFile` with thread name; confirm they run on pool thread, not "AWT-EventQueue".
4. **Large JSON**: Paste a multi-MB JSON, switch to Viewer; UI should show progress and remain responsive; no freeze.
5. **State**: Change tabs, close IDE, reopen; state should persist. Sync with second IDE; shared file should reflect latest.

---

## 5. Findings with file:line References

### 5.1 IntelliJ Platform API best practices violated

| Finding | File:Line |
|--------|-----------|
| Panel not tied to Project; no way to use project services or MessageBus | `JsonViewerToolWindowFactory.kt:46-49` |
| `ContentFactory.getInstance()` used; prefer project-scoped `ContentFactory.getService(project)` | `JsonViewerToolWindowFactory.kt:48` |
| Key binding uses deprecated `Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx` | `JsonViewerToolWindowFactory.kt:206` |
| `getState()` returns mutable `myState`; should return defensive copy | `TabStorageService.kt:142` |

### 5.2 IntelliJ APIs that could replace raw Swing

| Finding | File:Line |
|--------|-----------|
| Text editing uses `JTextArea`; could use `EditorFactory` for IDE-consistent editing and syntax highlighting | `JsonViewerToolWindowFactory.kt:411, 414-418` |
| Tree uses `DefaultTreeModel`; for large JSON, `AsyncTreeModel` would avoid blocking EDT when building/expanding | `JsonViewerToolWindowFactory.kt:437-438` |
| Clipboard via `Toolkit.getDefaultToolkit().systemClipboard`; IntelliJ uses `CopyPasteManager` | `JsonViewerToolWindowFactory.kt:364, 374`, `JsonViewerToolWindowFactory.kt:612` |

### 5.3 Disposable lifecycle

| Finding | File:Line |
|--------|-----------|
| `JsonViewerPanel` does not implement `Disposable`; no cleanup when tool window content is removed | `JsonViewerToolWindowFactory.kt:81` |
| `TabStorageService.addListener` called but never `removeListener`; listener leak when content is closed | `JsonViewerToolWindowFactory.kt:215-217` |
| `TextContentPanel` debounce `Timer` never stopped; can fire after panel is disposed | `JsonViewerToolWindowFactory.kt:413, 666` |
| `SearchPanel` debounce `Timer` never stopped | `JsonViewerToolWindowFactory.kt:1197, 1199` |
| Factory creates panel without registering it with a parent `Disposable` (e.g. content) | `JsonViewerToolWindowFactory.kt:46-49` |

### 5.4 EDT threading violations

| Finding | File:Line |
|--------|-----------|
| `saveToSharedFile()` performs file I/O; called from `onChanged()` which is invoked from UI-triggered mutations (e.g. `updateTab`) → I/O on EDT | `TabStorageService.kt:250-254, 269-305` |
| `mergeFromSharedFile()` performs file I/O; called from `loadState()` and `initializeComponent()` → can run on EDT | `TabStorageService.kt:144-147, 153-154, 308-368` |
| `JsonParser.parseString(text)` and tree build in `switchViewMode()` run on EDT; large JSON blocks UI | `JsonViewerToolWindowFactory.kt:246-249, 533-547` |

### 5.5 PersistentStateComponent usage

| Finding | File:Line |
|--------|-----------|
| `getState()` returns internal mutable `myState`; platform may serialize it; safer to return a copy | `TabStorageService.kt:142` |
| Full state (all tabs with full `jsonText`) serialized to XML; consider lighter-weight state (e.g. metadata only in XML, large payload elsewhere or `RoamingType.DISABLED`) for sync performance | `TabStorageService.kt:104-108, 52-62` |

### 5.6 Missing IntelliJ performance patterns

| Finding | File:Line |
|--------|-----------|
| No `ProgressManager` or background runnable for heavy work (JSON parse, tree build); should use `ProgressManager.getInstance().run()` or `executeOnPooledThread` + `invokeLater` for result | `JsonViewerToolWindowFactory.kt:240-264, 533-547` |
| No off-EDT execution for file I/O in `TabStorageService` | `TabStorageService.kt:269-305, 308-368` |
| ReadAction/WriteAction not applicable (no PSI/VirtualFile); no other heavy read/write actions in codebase | N/A |

---

## 6. Priority Order (Suggested)

1. **High**: Disposable lifecycle (listener + timers) — prevents leaks and use-after-dispose.
2. **High**: File I/O off EDT — prevents UI freezes on save/load.
3. **Medium**: Parse large JSON off EDT — prevents UI freeze when switching to Viewer on large documents.
4. **Medium**: `getState()` defensive copy — correctness and platform contract.
5. **Low**: Deprecated key binding, CopyPasteManager, ContentFactory — polish and future-proofing.
6. **Optional**: Lighter-weight state, AsyncTreeModel — only if needed for scale or sync size.

---

*End of plan.*
