# Publish JSON Notes to JetBrains Marketplace — All Steps

Step-by-step guide to publish this plugin to the [JetBrains Marketplace](https://plugins.jetbrains.com/). Use together with [PUBLISH_CHECKLIST.md](PUBLISH_CHECKLIST.md) for the full checklist.

---

## Phase 1: Before you build

### 1. Vendor & contact (required for approval)

- **plugin.xml**: Set real vendor URL and email in `src/main/resources/META-INF/plugin.xml`.
  - Replace `url="https://github.com"` with your site or GitHub profile (e.g. `https://github.com/yourusername`).
  - Replace `email="your-email@example.com"` with a valid email.
- Marketplace requires: *"The Vendor's website and email address are provided, valid, and functional."*

### 2. Legal & license

- **Choose a license** (e.g. MIT, Apache 2.0). You will paste or select it at upload.
- If **open-source**: have the **source code URL** ready (e.g. GitHub repo); Marketplace will ask for it.
- Read and be ready to accept the [JetBrains Marketplace Developer Agreement](https://plugins.jetbrains.com/legal/developer-agreement) (you accept it at first upload).

### 3. (Optional) Plugin Verifier

- Run [Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/api-changes-list.html#verifying-compatibility) to check compatibility with declared IDE versions.
- Your plugin already declares `sinceBuild = "233"` (2023.3+) in `build.gradle.kts`.

---

## Phase 2: Build the plugin

### 4. Build the distribution ZIP

```bash
./gradlew buildPlugin
```

- Output: **`build/distributions/json-viewer-jetbrains-<version>.zip`** (e.g. `json-viewer-jetbrains-1.0.4.zip`; version comes from `build.gradle.kts`; archive base name from `settings.gradle.kts` root project name).
- Max size allowed: **400 MB**.
- (Optional) [Sign the plugin](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html); unsigned is also accepted.

### 5. Test locally

- In a clean IDE: **File → Settings → Plugins → ⚙️ → Install Plugin from Disk...**
- Select the ZIP from `build/distributions/`.
- Restart and smoke-test (open JSON Notes, format/minify, tree view, tabs).

---

## Phase 3: Upload to JetBrains Marketplace

### 6. Log in and start upload

1. Go to [JetBrains Marketplace](https://plugins.jetbrains.com/) and **log in**.
2. Open your **user account menu** → **Upload plugin**.

### 7. Vendor profile (first-time only)

- If this is your first plugin:
  - **Accept** the [JetBrains Marketplace Developer Agreement](https://plugins.jetbrains.com/legal/developer-agreement).
  - **Create** your [Vendor profile](https://plugins.jetbrains.com/docs/marketplace/organizations.html) (name, URL, email, etc.).
- Select the **Vendor profile** under which you’re publishing.

### 8. Submit plugin details

Fill in the upload form:

| Field | What to provide |
|-------|------------------|
| **Plugin file** | The ZIP from `build/distributions/` (e.g. `json-viewer-jetbrains-1.0.4.zip`). |
| **License / EULA** | Your chosen license text (or EULA). **Required.** |
| **Source code URL** | **Required** if the plugin is open-source (e.g. GitHub repo URL). |
| **Tags** | At least one; e.g. `json`, `formatter`, `tools`, `editor`. Correct tags improve discoverability. |
| **Channels** | Default = public release. Use a [custom channel](https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html) only for Alpha/Beta/EAP. |
| **Hidden** | Optional: keep plugin hidden after approval until you’re ready to list it. |

Submit the form. The plugin will enter **manual review**.

---

## Phase 4: After upload (plugin admin page)

### 9. Complete the plugin page

On the plugin’s admin/overview page:

- **Media**: Add **at least one screenshot** (recommended min **1200×760**), e.g. the JSON Notes tool window with tree view.
- **Getting started** (optional): Short steps (e.g. *Tools → Open JSON Notes* or *Ctrl+Shift+J*).
- **Links**: Add **issue tracker**, **source code**, **documentation** if you have them. All links must be **valid and reachable**.

### 10. (Paid plugins only)

- If you later make the plugin **paid**: use the **Sales Info** tab, **Add to Marketplace**, fill pricing and [license scheme](https://plugins.jetbrains.com/docs/marketplace/license-types-and-schemes.html), optional trial (default 30 days).  
- Ignore this step for a **free** plugin.

---

## Phase 5: Review and release

### 11. Manual review

- The **JetBrains Marketplace team** will **manually review** your plugin.
- **Typical review time**: a few working days (often 3–4).
- They may ask for changes (vendor info, description, media, links, functionality). Reply and fix as requested.
- If you don’t hear back or have issues: **marketplace@jetbrains.com**.

### 12. Approval and listing

- Once **approved**, the plugin becomes **listed** (unless you left it **hidden**).
- Users can install it via **File → Settings → Plugins** or from the [Marketplace](https://plugins.jetbrains.com/) plugin page.

---

## Quick reference

| Step | Action |
|------|--------|
| 1 | Set vendor URL + email in `plugin.xml` |
| 2 | Choose license; have source URL if open-source |
| 3 | (Optional) Run Plugin Verifier |
| 4 | `./gradlew buildPlugin` → ZIP in `build/distributions/` |
| 5 | Install from disk and smoke-test |
| 6 | Marketplace → Log in → Upload plugin |
| 7 | Accept agreement + create/select Vendor (first time) |
| 8 | Upload ZIP; set license, source URL, tags; submit |
| 9 | Add screenshot(s) and links on plugin admin page |
| 10 | (Paid only) Sales Info, pricing, trial |
| 11 | Wait for review; respond to any feedback |
| 12 | Plugin goes live after approval |

---

## References

- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
- [Publishing and listing your plugin](https://plugins.jetbrains.com/docs/marketplace/publishing-and-listing-your-plugin.html)
- [JetBrains Marketplace Approval Guidelines](https://plugins.jetbrains.com/legal/approval-guidelines)
- [Best practices for listing](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
- [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- **Support**: marketplace@jetbrains.com
