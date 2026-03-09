# JetBrains Marketplace — Publish Checklist

Use this checklist before uploading the JSON Notes plugin. All items are required for approval unless marked optional.

---

## Before you upload

### 1. Vendor (required for approval)

- [ ] **Set real vendor URL and email** in `src/main/resources/META-INF/plugin.xml`:
  - Replace `url="https://github.com"` with your real website or GitHub profile (e.g. `https://github.com/yourusername`).
  - Replace `email="your-email@example.com"` with a valid, working email.
- Marketplace approval requires: *"The Vendor's website and email address are provided, valid, and functional."*

### 2. Plugin icon

- [x] **Plugin logo added**: `src/main/resources/META-INF/pluginIcon.svg` (40×40 px, SVG).
- Must not be the [default IntelliJ Platform Plugin Template logo](https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/src/main/resources/META-INF/pluginIcon.svg).
- Must not resemble any JetBrains product logos.

### 3. Plugin name & description

- [x] **Name**: "JSON Notes" — short, title case, no "Plugin"/"JetBrains", &lt; 30 chars.
- [x] **Description**: English, clear value proposition; first ~40 characters work as card summary. No marketing fluff. Bullet list present.

### 4. Change notes

- [x] **Change notes** in `plugin.xml`: present and meaningful (no "Add change notes here" or "most HTML tags may be used").

### 5. Legal & license

- [ ] **Choose a license** and be ready to provide it at upload (e.g. MIT, Apache 2.0). License is mandatory.
- [ ] If open-source: have the **source code URL** ready; Marketplace will ask for it.
- [ ] Accept the [JetBrains Marketplace Developer Agreement](https://plugins.jetbrains.com/legal/developer-agreement) when prompted (first upload).

### 6. Compatibility

- [ ] **Run Plugin Verifier** (recommended): [Verifying compatibility](https://plugins.jetbrains.com/docs/intellij/api-changes-list.html#verifying-compatibility). Ensures the plugin works on declared target products/versions.
- [x] **Compatibility declared** in `build.gradle.kts`: `sinceBuild = "233"` (2023.3+).

### 7. Build & test

- [ ] Run `./gradlew buildPlugin` and confirm the ZIP is under `build/distributions/`.
- [ ] Install from disk in a clean IDE (e.g. **File → Settings → Plugins → ⚙️ → Install Plugin from Disk...**) and smoke-test.

---

## At upload time (Marketplace form)

- [ ] **Vendor profile**: Create/select vendor; accept Developer Agreement if first plugin.
- [ ] **License**: Select or paste your license text; add source code URL if open-source.
- [ ] **Tags**: Pick at least one; e.g. `json`, `formatter`, `tools`, `editor`. Correct tags improve discoverability.
- [ ] **Channels**: Leave default for public release; use a custom channel only for alpha/beta.
- [ ] **File**: Upload the signed or unsigned ZIP from `build/distributions/` (max 400 MB). Signing is recommended; see [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

---

## After upload (on plugin admin page)

- [ ] **Media**: Add at least one screenshot (recommended min 1200×760). Shows the tool window / tree view in the IDE.
- [ ] **Getting started** (optional): Short steps to open the tool (e.g. Tools → Open JSON Notes or shortcut).
- [ ] **Links**: Add issue tracker / source code / documentation if you have them. All links must be valid and reachable.

---

## Approval criteria (summary)

| Area | Requirement |
|------|-------------|
| **Logo** | 40×40 px SVG; not default template; not JetBrains-like. |
| **Name** | Original, Latin, ≤30 chars, no "Plugin"/"IntelliJ"/"JetBrains". |
| **Vendor** | Valid, functional website and email. |
| **Description** | English, correct grammar, no broken media. |
| **Change notes** | Real content; no placeholder text. |
| **Links** | All external links valid and related to plugin/author. |
| **Functionality** | Compatible with at least one JetBrains product; no internal API misuse; no major performance/security issues. |
| **Legal** | Developer Agreement accepted; EULA/license provided; source link if open-source. |

Review typically takes 3–4 working days. If you don’t hear back, contact [marketplace@jetbrains.com](mailto:marketplace@jetbrains.com).

---

## References

- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [JetBrains Marketplace Approval Guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)
- [Best practices for listing](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
- [Plugin User Experience (UX)](https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html)
