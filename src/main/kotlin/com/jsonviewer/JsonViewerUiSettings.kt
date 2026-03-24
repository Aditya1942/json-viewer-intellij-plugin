package com.jsonviewer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import com.jsonviewer.ui.PluginFonts

/**
 * Application-level UI preferences for JSON Notes (font, future sections).
 * Persisted to XML and eligible for Settings Sync.
 */
data class JsonViewerUiState(
    @Tag("fontFamily") var fontFamily: String = "",
    @Tag("fontSize") var fontSize: Int = 13,
    @Tag("hideCopy") var hideCopy: Boolean = false,
    @Tag("hidePaste") var hidePaste: Boolean = false,
    @Tag("hideFormat") var hideFormat: Boolean = false,
    @Tag("hideMinify") var hideMinify: Boolean = false,
    @Tag("hideViewer") var hideViewer: Boolean = false,
    @Tag("hideOpenInMainEditor") var hideOpenInMainEditor: Boolean = false,
    @Tag("showSideToolbar") var showSideToolbar: Boolean = true,
) {
    constructor() : this("", 13, false, false, false, false, false, false, true)
}

@State(
    name = "JsonViewerUi",
    storages = [Storage("jsonViewerUi.xml", roamingType = RoamingType.DEFAULT)]
)
@Service(Service.Level.APP)
class JsonViewerUiSettings : PersistentStateComponent<JsonViewerUiState> {

    private var myState = JsonViewerUiState()

    override fun getState(): JsonViewerUiState = myState

    override fun loadState(state: JsonViewerUiState) {
        XmlSerializerUtil.copyBean(state, myState)
        normalizeState()
    }

    fun fontFamily(): String =
        myState.fontFamily.ifBlank { PluginFonts.defaultFamilyName() }

    fun fontSize(): Int = myState.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun updateFont(family: String, size: Int) {
        myState.fontFamily = family.trim()
        myState.fontSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    fun hideCopy(): Boolean = myState.hideCopy

    fun hidePaste(): Boolean = myState.hidePaste

    fun hideFormat(): Boolean = myState.hideFormat

    fun hideMinify(): Boolean = myState.hideMinify

    fun hideViewer(): Boolean = myState.hideViewer

    fun hideOpenInMainEditor(): Boolean = myState.hideOpenInMainEditor

    fun showSideToolbar(): Boolean = myState.showSideToolbar

    fun updateToolbarVisibility(
        hideCopy: Boolean,
        hidePaste: Boolean,
        hideFormat: Boolean,
        hideMinify: Boolean,
        hideViewer: Boolean,
        hideOpenInMainEditor: Boolean,
        showSideToolbar: Boolean,
    ) {
        myState.hideCopy = hideCopy
        myState.hidePaste = hidePaste
        myState.hideFormat = hideFormat
        myState.hideMinify = hideMinify
        myState.hideViewer = hideViewer
        myState.hideOpenInMainEditor = hideOpenInMainEditor
        myState.showSideToolbar = showSideToolbar
    }

    private fun normalizeState() {
        if (myState.fontFamily.isBlank()) {
            myState.fontFamily = PluginFonts.defaultFamilyName()
        }
        myState.fontSize = myState.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    companion object {
        const val MIN_FONT_SIZE: Int = 8
        const val MAX_FONT_SIZE: Int = 48

        fun getInstance(): JsonViewerUiSettings =
            ApplicationManager.getApplication().getService(JsonViewerUiSettings::class.java)
    }
}

