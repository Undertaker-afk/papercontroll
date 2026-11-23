package com.papercontrol.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Config(
    /** Capture on its own once a document has been held still in the frame. */
    val autoCapture: Boolean = true,
    /** Flatten and clean up scans (contrast, white paper). The photo is kept as well. */
    val enhance: Boolean = true,
    /** Ask the AI to sort new documents into tags, correspondents, types and folders. */
    val autoSort: Boolean = true,
    /** Create correspondents and folders for new senders automatically. */
    val createLabels: Boolean = true,
    /** Batch imports are split into documents by paper mapping. */
    val paperMapping: Boolean = true,
    val llmEnabled: Boolean = false,
    val llmModelId: String = ModelCatalog.DEFAULT_LLM_ID,
    val llmCustomUrl: String = "",
    val llmBackend: String = BACKEND_GPU,
    val llmNote: String = "",
    val wifiOnly: Boolean = true,
    val paperlessUrl: String = "",
    val paperlessToken: String = "",
    val paperlessAutoUpload: Boolean = false,
    val defaultCurrency: String = "",
    /** Paperless-style title template; empty keeps the title read from the document. */
    val titleTemplate: String = "",
    /** Folder template, e.g. "{document_type}s/{correspondent}"; empty keeps the sorted folder. */
    val folderTemplate: String = "",
    val asnPrefix: String = com.papercontrol.app.core.Barcodes.DEFAULT_ASN_PREFIX,
    val separatorBarcode: String = com.papercontrol.app.core.Barcodes.DEFAULT_SEPARATOR,
    /** Extra writing systems for text recognition, besides Latin: chinese, devanagari, japanese, korean. */
    val ocrScripts: Set<String> = emptySet(),
    /** Search by meaning with the on-device embedding model. */
    val semanticSearch: Boolean = false,
    val appLock: Boolean = false,
    /** Hide the app content in the recent-apps view and block screenshots. */
    val secureScreen: Boolean = false,
    val reminders: Boolean = true,
    /** Folder (document tree URI) for automatic weekly backups; empty when off. */
    val backupUri: String = "",
    val lastBackup: Long = 0,
    /** Two-way sync with the Paperless-ngx server (download as well as upload). */
    val paperlessSync: Boolean = false,
    val lastSync: Long = 0,
) {
    companion object {
        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"
    }
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(Config())
    val flow: StateFlow<Config> = _flow
    val current: Config get() = _flow.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _flow.value = read()
    }

    private fun read(): Config {
        val d = Config()
        return Config(
            autoCapture = prefs.getBoolean("autoCapture", d.autoCapture),
            enhance = prefs.getBoolean("enhance", d.enhance),
            autoSort = prefs.getBoolean("autoSort", d.autoSort),
            createLabels = prefs.getBoolean("createLabels", d.createLabels),
            paperMapping = prefs.getBoolean("paperMapping", d.paperMapping),
            llmEnabled = prefs.getBoolean("llmEnabled", d.llmEnabled),
            llmModelId = prefs.getString("llmModelId", d.llmModelId)!!,
            llmCustomUrl = prefs.getString("llmCustomUrl", d.llmCustomUrl)!!,
            llmBackend = prefs.getString("llmBackend", d.llmBackend)!!,
            llmNote = prefs.getString("llmNote", d.llmNote)!!,
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            paperlessUrl = prefs.getString("paperlessUrl", d.paperlessUrl)!!,
            paperlessToken = prefs.getString("paperlessToken", d.paperlessToken)!!,
            paperlessAutoUpload = prefs.getBoolean("paperlessAutoUpload", d.paperlessAutoUpload),
            defaultCurrency = prefs.getString("defaultCurrency", d.defaultCurrency)!!,
            titleTemplate = prefs.getString("titleTemplate", d.titleTemplate)!!,
            folderTemplate = prefs.getString("folderTemplate", d.folderTemplate)!!,
            asnPrefix = prefs.getString("asnPrefix", d.asnPrefix)!!,
            separatorBarcode = prefs.getString("separatorBarcode", d.separatorBarcode)!!,
            ocrScripts = prefs.getStringSet("ocrScripts", d.ocrScripts)!!.toSet(),
            semanticSearch = prefs.getBoolean("semanticSearch", d.semanticSearch),
            appLock = prefs.getBoolean("appLock", d.appLock),
            secureScreen = prefs.getBoolean("secureScreen", d.secureScreen),
            reminders = prefs.getBoolean("reminders", d.reminders),
            backupUri = prefs.getString("backupUri", d.backupUri)!!,
            lastBackup = prefs.getLong("lastBackup", d.lastBackup),
            paperlessSync = prefs.getBoolean("paperlessSync", d.paperlessSync),
            lastSync = prefs.getLong("lastSync", d.lastSync),
        )
    }

    fun update(f: (Config) -> Config) {
        val c = f(current)
        prefs.edit()
            .putBoolean("autoCapture", c.autoCapture)
            .putBoolean("enhance", c.enhance)
            .putBoolean("autoSort", c.autoSort)
            .putBoolean("createLabels", c.createLabels)
            .putBoolean("paperMapping", c.paperMapping)
            .putBoolean("llmEnabled", c.llmEnabled)
            .putString("llmModelId", c.llmModelId)
            .putString("llmCustomUrl", c.llmCustomUrl)
            .putString("llmBackend", c.llmBackend)
            .putString("llmNote", c.llmNote)
            .putBoolean("wifiOnly", c.wifiOnly)
            .putString("paperlessUrl", c.paperlessUrl.trim().trimEnd('/'))
            .putString("paperlessToken", c.paperlessToken.trim())
            .putBoolean("paperlessAutoUpload", c.paperlessAutoUpload)
            .putString("defaultCurrency", c.defaultCurrency)
            .putString("titleTemplate", c.titleTemplate)
            .putString("folderTemplate", c.folderTemplate)
            .putString("asnPrefix", c.asnPrefix)
            .putString("separatorBarcode", c.separatorBarcode)
            .putStringSet("ocrScripts", c.ocrScripts)
            .putBoolean("semanticSearch", c.semanticSearch)
            .putBoolean("appLock", c.appLock)
            .putBoolean("secureScreen", c.secureScreen)
            .putBoolean("reminders", c.reminders)
            .putString("backupUri", c.backupUri)
            .putLong("lastBackup", c.lastBackup)
            .putBoolean("paperlessSync", c.paperlessSync)
            .putLong("lastSync", c.lastSync)
            .apply()
        _flow.value = c.copy(paperlessUrl = c.paperlessUrl.trim().trimEnd('/'), paperlessToken = c.paperlessToken.trim())
    }

    fun llmFallBackToCpu(note: String) = update { it.copy(llmBackend = Config.BACKEND_CPU, llmNote = note) }
    fun clearLlmNote() { if (current.llmNote.isNotEmpty()) update { it.copy(llmNote = "") } }
}
