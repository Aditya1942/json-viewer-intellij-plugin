package com.jsonviewer

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// ──────────────────────────────────────────────────────
// Data models
// ──────────────────────────────────────────────────────

/**
 * Represents a single saved JSON document tab.
 * Uses @Tag for XML serialization (JetBrains Settings Sync / Cloud).
 */
@Tag("tab")
data class SavedTab(
    @Tag("id") var id: String = UUID.randomUUID().toString(),
    @Tag("name") var name: String = "Untitled",
    @Tag("jsonText") var jsonText: String = "",
    @Tag("createdAt") var createdAt: Long = System.currentTimeMillis(),
    @Tag("updatedAt") var updatedAt: Long = System.currentTimeMillis(),
    @Tag("expandedPathKeys") var expandedPathKeys: String = "",
) {
    // No-arg constructor required for XML deserialization
    constructor() : this(
        id = UUID.randomUUID().toString(),
        name = "Untitled",
        jsonText = "",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        expandedPathKeys = ""
    )
}

/**
 * Persistent state holding all tabs.
 * Serialized to XML by JetBrains platform for Settings Sync / Cloud.
 */
data class JsonViewerTabsState(
    @XCollection(style = XCollection.Style.v2)
    var tabs: MutableList<SavedTab> = mutableListOf(),
    @Tag("activeTabId")
    var activeTabId: String? = null,
    @Tag("lastModified")
    var lastModified: Long = 0L,
) {
    constructor() : this(mutableListOf(), null, 0L)
}

// ──────────────────────────────────────────────────────
// Cross-IDE shared file format (JSON)
// ──────────────────────────────────────────────────────

data class SharedTabsFile(
    val version: Int = 1,
    val lastModified: Long = 0L,
    val lastModifiedBy: String = "",
    val tabs: List<SharedTabEntry> = emptyList(),
    val activeTabId: String? = null,
)

data class SharedTabEntry(
    val id: String = "",
    val name: String = "",
    val jsonText: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

// ──────────────────────────────────────────────────────
// Change listener
// ──────────────────────────────────────────────────────

fun interface TabStorageListener {
    fun onTabsChanged(tabs: List<SavedTab>, activeTabId: String?)
}

// ──────────────────────────────────────────────────────
// TabStorageService — Application-level persistent service
//
// Storage strategy:
// 1. JetBrains XML state (jsonViewerTabs.xml) with RoamingType.DEFAULT
//    → Automatically synced via JetBrains Settings Sync / Cloud
// 2. Cross-IDE shared file (~/.config/json-viewer/tabs.json)
//    → Readable by any JetBrains IDE with this plugin installed
// 3. On load: merge both sources (latest-wins per tab by updatedAt)
// 4. On save: write to both locations
//
// Performance:
// - File I/O runs on pooled background thread (never blocks EDT)
// - Saves are coalesced via AtomicBoolean flag (no redundant writes)
// - setActiveTab() does NOT trigger file save (deferred to next content change)
// - Atomic file writes via temp+rename to prevent cross-IDE corruption
// ──────────────────────────────────────────────────────

@State(
    name = "JsonViewerTabs",
    storages = [Storage("jsonViewerTabs.xml", roamingType = RoamingType.DEFAULT)]
)
@Service(Service.Level.APP)
class TabStorageService : PersistentStateComponent<JsonViewerTabsState> {

    companion object {
        private val LOG = Logger.getInstance(TabStorageService::class.java)
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        /** Cached shared storage directory — computed once. */
        private val SHARED_DIR: Path by lazy {
            val configHome = System.getenv("XDG_CONFIG_HOME")
                ?: when {
                    System.getProperty("os.name").lowercase().contains("win") ->
                        System.getenv("APPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Roaming"
                    else ->
                        "${System.getProperty("user.home")}/.config"
                }
            Path.of(configHome, "json-viewer")
        }

        private val SHARED_FILE_PATH: Path by lazy { SHARED_DIR.resolve("tabs.json") }

        fun getInstance(): TabStorageService =
            ApplicationManager.getApplication().getService(TabStorageService::class.java)
    }

    private var myState = JsonViewerTabsState()
    private val listeners = CopyOnWriteArrayList<TabStorageListener>()

    /** Flag to coalesce multiple rapid saves into one background write. */
    private val savePending = AtomicBoolean(false)

    /** Whether the shared directory has been created (avoid repeated createDirectories calls). */
    private var sharedDirEnsured = false

    // ── PersistentStateComponent ──

    override fun getState(): JsonViewerTabsState = myState

    override fun loadState(state: JsonViewerTabsState) {
        XmlSerializerUtil.copyBean(state, myState)
        // After loading IDE state, merge with shared file
        mergeFromSharedFile()
    }

    override fun initializeComponent() {
        // If no tabs exist yet, create a default one
        if (myState.tabs.isEmpty()) {
            mergeFromSharedFile()
            if (myState.tabs.isEmpty()) {
                val defaultTab = SavedTab(name = defaultTabName())
                myState.tabs.add(defaultTab)
                myState.activeTabId = defaultTab.id
                scheduleSave()
            }
        }
    }

    // ── Public API ──

    fun getTabs(): List<SavedTab> = myState.tabs.toList()

    fun getActiveTabId(): String? = myState.activeTabId

    fun addTab(name: String = "Untitled", jsonText: String = ""): SavedTab {
        val tab = SavedTab(name = name, jsonText = jsonText)
        myState.tabs.add(tab)
        myState.activeTabId = tab.id
        onChanged()
        return tab
    }

    fun removeTab(id: String) {
        myState.tabs.removeIf { it.id == id }
        if (myState.activeTabId == id) {
            myState.activeTabId = myState.tabs.lastOrNull()?.id
        }
        // Don't allow empty — always keep at least one tab
        if (myState.tabs.isEmpty()) {
            val defaultTab = SavedTab(name = defaultTabName())
            myState.tabs.add(defaultTab)
            myState.activeTabId = defaultTab.id
        }
        onChanged()
    }

    private fun defaultTabName(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy h:mm a", Locale.US))

    fun updateTab(id: String, name: String? = null, jsonText: String? = null, expandedPathKeys: String? = null) {
        val tab = myState.tabs.find { it.id == id } ?: return
        if (name != null) tab.name = name
        if (jsonText != null) tab.jsonText = jsonText
        if (expandedPathKeys != null) tab.expandedPathKeys = expandedPathKeys
        tab.updatedAt = System.currentTimeMillis()
        onChanged()
    }

    /**
     * Set the active tab. Does NOT trigger a file save — the active tab ID
     * will be persisted with the next content change to avoid unnecessary I/O
     * on rapid tab switching.
     */
    fun setActiveTab(id: String) {
        if (myState.tabs.any { it.id == id }) {
            myState.activeTabId = id
            myState.lastModified = System.currentTimeMillis()
            // Only notify listeners (for UI update), but skip file save
            notifyListeners()
        }
    }

    fun duplicateTab(id: String): SavedTab? {
        val source = myState.tabs.find { it.id == id } ?: return null
        val copy = SavedTab(
            name = "${source.name} (copy)",
            jsonText = source.jsonText,
        )
        val index = myState.tabs.indexOfFirst { it.id == id }
        myState.tabs.add(index + 1, copy)
        myState.activeTabId = copy.id
        onChanged()
        return copy
    }

    fun reorderTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || fromIndex >= myState.tabs.size) return
        if (toIndex < 0 || toIndex >= myState.tabs.size) return
        val tab = myState.tabs.removeAt(fromIndex)
        myState.tabs.add(toIndex, tab)
        onChanged()
    }

    fun addListener(listener: TabStorageListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TabStorageListener) {
        listeners.remove(listener)
    }

    /**
     * Force a reload from the shared cross-IDE file.
     * Useful when the user switches from another IDE.
     */
    fun reloadFromSharedFile() {
        mergeFromSharedFile()
        notifyListeners()
    }

    // ── Internals ──

    private fun onChanged() {
        myState.lastModified = System.currentTimeMillis()
        scheduleSave()
        notifyListeners()
    }

    /**
     * Schedule an async save to the shared file.
     * Uses AtomicBoolean to coalesce multiple rapid changes into one write.
     * File I/O happens on a pooled background thread — never blocks EDT.
     */
    private fun scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // Small delay to coalesce rapid changes
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@executeOnPooledThread
                }
                savePending.set(false)
                saveToSharedFile()
            }
        }
    }

    private fun notifyListeners() {
        val tabs = myState.tabs.toList()
        val activeId = myState.activeTabId
        for (listener in listeners) {
            try {
                listener.onTabsChanged(tabs, activeId)
            } catch (e: Exception) {
                LOG.warn("TabStorageListener error", e)
            }
        }
    }

    // ── Cross-IDE shared file I/O ──

    /**
     * Write tabs to the shared cross-IDE file.
     * Uses atomic write (temp file + rename) to prevent corruption.
     * Runs on background thread — never call from EDT.
     */
    private fun saveToSharedFile() {
        try {
            if (!sharedDirEnsured) {
                Files.createDirectories(SHARED_DIR)
                sharedDirEnsured = true
            }

            val appName = try {
                ApplicationManager.getApplication()?.let {
                    com.intellij.openapi.application.ApplicationNamesInfo.getInstance().fullProductName
                } ?: "JetBrains IDE"
            } catch (_: Exception) {
                "JetBrains IDE"
            }

            val sharedFile = SharedTabsFile(
                version = 1,
                lastModified = myState.lastModified,
                lastModifiedBy = appName,
                tabs = myState.tabs.map { tab ->
                    SharedTabEntry(
                        id = tab.id,
                        name = tab.name,
                        jsonText = tab.jsonText,
                        createdAt = tab.createdAt,
                        updatedAt = tab.updatedAt,
                    )
                },
                activeTabId = myState.activeTabId,
            )

            val json = GSON.toJson(sharedFile)

            // Atomic write: write to temp file, then rename
            val tempFile = SHARED_DIR.resolve("tabs.json.tmp")
            Files.writeString(
                tempFile, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            )
            try {
                Files.move(tempFile, SHARED_FILE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // ATOMIC_MOVE may not be supported on all filesystems; fall back to regular move
                Files.move(tempFile, SHARED_FILE_PATH, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to save cross-IDE tabs file", e)
        }
    }

    private fun mergeFromSharedFile(): Boolean {
        try {
            val path = SHARED_FILE_PATH
            if (!Files.exists(path)) return false

            val json = Files.readString(path)
            val sharedFile = GSON.fromJson(json, SharedTabsFile::class.java) ?: return false

            if (sharedFile.version != 1) {
                LOG.warn("Unknown shared tabs file version: ${sharedFile.version}")
                return false
            }

            // If shared file is newer, use its data
            if (sharedFile.lastModified > myState.lastModified) {
                myState.tabs.clear()
                myState.tabs.addAll(sharedFile.tabs.map { entry ->
                    SavedTab(
                        id = entry.id,
                        name = entry.name,
                        jsonText = entry.jsonText,
                        createdAt = entry.createdAt,
                        updatedAt = entry.updatedAt,
                    )
                })
                myState.activeTabId = sharedFile.activeTabId
                myState.lastModified = sharedFile.lastModified
                return true
            }

            // Otherwise merge: add tabs from shared file that don't exist locally,
            // and update local tabs if the shared version is newer
            val localTabMap = myState.tabs.associateBy { it.id }.toMutableMap()
            var changed = false

            for (entry in sharedFile.tabs) {
                val local = localTabMap[entry.id]
                if (local == null) {
                    // New tab from shared file
                    myState.tabs.add(SavedTab(
                        id = entry.id,
                        name = entry.name,
                        jsonText = entry.jsonText,
                        createdAt = entry.createdAt,
                        updatedAt = entry.updatedAt,
                    ))
                    changed = true
                } else if (entry.updatedAt > local.updatedAt) {
                    // Shared version is newer
                    local.name = entry.name
                    local.jsonText = entry.jsonText
                    local.updatedAt = entry.updatedAt
                    changed = true
                }
            }

            return changed
        } catch (e: Exception) {
            LOG.warn("Failed to read cross-IDE tabs file", e)
            return false
        }
    }
}
