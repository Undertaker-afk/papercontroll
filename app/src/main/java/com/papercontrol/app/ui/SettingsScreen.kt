package com.papercontrol.app.ui

import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.papercontrol.app.core.Templates
import com.papercontrol.app.data.Config
import com.papercontrol.app.data.ModelCatalog
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.Embedder
import com.papercontrol.app.ml.Llm
import com.papercontrol.app.ml.TextReader
import com.papercontrol.app.processing.Backup
import com.papercontrol.app.processing.BarcodeSheets
import com.papercontrol.app.sync.Paperless
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import com.papercontrol.app.work.BackupWorker
import com.papercontrol.app.work.DownloadWorker
import com.papercontrol.app.work.IndexWorker
import com.papercontrol.app.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding()) {
        TopBar(left = { CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
        SettingsContent()
    }
}

@Composable
fun SettingsContent(modifier: Modifier = Modifier) {
    val cfg by Settings.flow.collectAsState()
    val dl by DownloadWorker.chatProgress.collectAsState()
    val edl by DownloadWorker.embedProgress.collectAsState()
    val syncing by Paperless.syncing.collectAsState()
    val indexed by IndexWorker.indexed.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsState()
    val spec = ModelCatalog.resolve(cfg)
    val ready = remember(cfg, dl, version) { spec?.isReady(context) == true }
    val embedReady = remember(cfg, edl, version) { ModelCatalog.embedding.isReady(context) }
    val docCount = query(initial = 0) { Store.count() }
    val vectorCount = query(indexed, initial = 0) { Store.embeddings(ModelCatalog.EMBEDDING_KEY).size }
    var url by remember { mutableStateOf(cfg.paperlessUrl) }
    var token by remember { mutableStateOf(cfg.paperlessToken) }
    var custom by remember { mutableStateOf(cfg.llmCustomUrl) }
    var titleTemplate by remember { mutableStateOf(cfg.titleTemplate) }
    var folderTemplate by remember { mutableStateOf(cfg.folderTemplate) }
    var asnPrefix by remember { mutableStateOf(cfg.asnPrefix) }
    var separator by remember { mutableStateOf(cfg.separatorBarcode) }
    var labelStart by remember { mutableStateOf(Store.nextAsn().toString()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    fun set(f: (Config) -> Config) = Settings.update(f)
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    val backupTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val r = runCatching { context.contentResolver.openOutputStream(uri)?.use { Backup.write(context, it) } }
            if (r.isSuccess) { set { it.copy(lastBackup = System.currentTimeMillis()) }; toast("Backup saved") } else toast("Backup failed: ${r.exceptionOrNull()?.message}")
        }
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmRestore = uri }
    val backupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            set { it.copy(backupUri = uri.toString()) }
            BackupWorker.schedule(context)
            scope.launch { runCatching { Backup.toFolder(context, uri) }.onSuccess { toast("First backup saved") }.onFailure { toast("Backup failed: ${it.message}") } }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.Gutter).padding(bottom = 130.dp)) {
        Text("Settings", style = Type.LargeTitle, modifier = Modifier.padding(top = 12.dp))

        SectionTitle("Scanning")
        PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ToggleRow("Capture automatically", cfg.autoCapture, { v -> set { it.copy(autoCapture = v) } }, "Takes the photo once the page is held still.")
            HairLine()
            ToggleRow("Clean up scans", cfg.enhance, { v -> set { it.copy(enhance = v) } }, "Even lighting, white paper, crisp print. The photo as taken is kept too.")
        }

        SectionTitle("Reading")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("Latin script (English, German, French, Spanish, Italian, Dutch, Portuguese …) always works offline. Turn on more writing systems; Google Play services downloads each one once.", style = Type.Caption)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextReader.SCRIPTS.forEach { (id, label) ->
                    LabelChip(label, Ink.Iris[3], selected = id in cfg.ocrScripts) { set { it.copy(ocrScripts = if (id in it.ocrScripts) it.ocrScripts - id else it.ocrScripts + id) } }
                }
            }
        }

        SectionTitle("Automatic sorting")
        PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ToggleRow("Sort new documents", cfg.autoSort, { v -> set { it.copy(autoSort = v) } }, "Rules you set, plus the Auto classifier that learns from documents you have sorted.")
            HairLine()
            ToggleRow("Create senders and folders", cfg.createLabels, { v -> set { it.copy(createLabels = v) } }, "New correspondents are added from the name on the paper.")
            HairLine()
            ToggleRow("Paper mapping", cfg.paperMapping, { v -> set { it.copy(paperMapping = v) } }, "When importing many photos, pages of the same document are found, ordered and kept together.")
            HairLine()
            ToggleRow("Deadline reminders", cfg.reminders, { v -> set { it.copy(reminders = v) } }, "Payment, warranty, cancellation and expiry dates are read from documents and you are notified before they pass.")
        }

        SectionTitle("Names and folders")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("Templates fill in titles and folders for new documents. Leave empty to keep what the app reads. Placeholders: " + Templates.PLACEHOLDERS.joinToString(" ") { "{$it}" }, style = Type.Caption)
            Field("Title template", titleTemplate, { titleTemplate = it; set { c -> c.copy(titleTemplate = it) } }, placeholder = "{correspondent} · {created_year}-{created_month}")
            Field("Folder template", folderTemplate, { folderTemplate = it; set { c -> c.copy(folderTemplate = it) } }, placeholder = "{document_type}/{created_year}")
            val preview = remember(titleTemplate, folderTemplate) {
                val v = Templates.Values("Nordlit Coffee", "Nordlit Coffee", "Receipt", "Receipts", listOf("Inbox"), LocalDate.of(2026, 11, 18), LocalDate.now(), 42, 18.9, "USD")
                listOfNotNull(
                    titleTemplate.takeIf { it.isNotBlank() }?.let { "Title: " + Templates.apply(it, v) },
                    folderTemplate.takeIf { it.isNotBlank() }?.let { "Folder: " + Templates.apply(it, v) },
                ).joinToString("\n")
            }
            if (preview.isNotEmpty()) Text("Example\n$preview", style = Type.Caption.copy(color = Ink.Text))
        }

        SectionTitle("Barcodes")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("A sticker on a paper original numbers it (archive serial number). A separator sheet in a stack of pages tells an import where one document ends and the next begins.", style = Type.Caption)
            Field("Archive number starts with", asnPrefix, { asnPrefix = it; set { c -> c.copy(asnPrefix = it.ifBlank { "ASN" }) } })
            Field("Separator sheet text", separator, { separator = it; set { c -> c.copy(separatorBarcode = it.ifBlank { "PATCHT" }) } })
            Spacer(Modifier.height(6.dp))
            Row2(
                "Separator sheet" to { scope.launch { runCatching { withContext(Dispatchers.IO) { BarcodeSheets.separator(context, cfg.separatorBarcode) } }.onSuccess { shareFile(context, it, "application/pdf", "Separator sheet") }.onFailure { toast("Could not make the sheet") } } },
                "ASN labels" to { scope.launch { runCatching { withContext(Dispatchers.IO) { BarcodeSheets.labels(context, cfg.asnPrefix, labelStart.toIntOrNull() ?: 1, 27) } }.onSuccess { shareFile(context, it, "application/pdf", "ASN labels") }.onFailure { toast("Could not make the labels") } } },
            )
            Field("First label number", labelStart, { labelStart = it }, number = true)
        }

        SectionTitle("Search by meaning")
        PaperCard(padding = PaddingValues(16.dp)) {
            ToggleRow("Find documents by meaning", cfg.semanticSearch, { v -> set { it.copy(semanticSearch = v) }; if (v && embedReady) IndexWorker.start(context) }, "“Washing machine warranty” finds the right receipt even when the words differ, in 100+ languages. Also powers “Similar documents” and “Ask your documents”.")
            if (cfg.semanticSearch) {
                Spacer(Modifier.height(8.dp))
                when {
                    embedReady -> {
                        Text("Search model ready · $vectorCount of $docCount documents indexed", style = Type.Caption)
                        if (vectorCount < docCount) { Spacer(Modifier.height(8.dp)); IrisProgress(if (docCount == 0) 0f else vectorCount / docCount.toFloat()) }
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Delete search model", { scope.launch { Embedder.release(); ModelCatalog.embedding.delete(context); set { it.copy(semanticSearch = false) } } })
                    }
                    edl.running -> {
                        Text(if (edl.total > 0) "Downloading · ${edl.bytes / 1_000_000} of ${edl.total / 1_000_000} MB" else "Waiting for network…", style = Type.Caption)
                        Spacer(Modifier.height(8.dp))
                        IrisProgress(if (edl.total > 0) edl.bytes / edl.total.toFloat() else 0f)
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Cancel", { DownloadWorker.cancel(context, embedding = true) })
                    }
                    else -> {
                        edl.error?.let { Text(it, style = Type.Caption.copy(color = Ink.Danger)); Spacer(Modifier.height(8.dp)) }
                        PrimaryButton("Download search model · ${ModelCatalog.embedding.approxMb} MB", { DownloadWorker.start(context, embedding = true) })
                    }
                }
            }
        }

        SectionTitle("On-device AI model")
        PaperCard(padding = PaddingValues(16.dp)) {
            ToggleRow("Use a local AI model", cfg.llmEnabled, { v -> set { it.copy(llmEnabled = v) } }, "Writes titles, reads what the rules miss, sorts into your labels, settles close calls in paper mapping and answers questions about your documents. Runs on the phone; nothing is uploaded.")
            if (cfg.llmEnabled) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelCatalog.models.forEach { m -> LabelChip("${m.title} · ${m.approxMb / 100 / 10.0} GB", Ink.Iris[2], selected = cfg.llmModelId == m.id) { set { it.copy(llmModelId = m.id) } } }
                    LabelChip("Your own model", Ink.Iris[4], selected = cfg.llmModelId == ModelCatalog.CUSTOM_LLM_ID) { set { it.copy(llmModelId = ModelCatalog.CUSTOM_LLM_ID) } }
                }
                if (cfg.llmModelId == ModelCatalog.CUSTOM_LLM_ID) {
                    Field("Download link (.litertlm)", custom, { custom = it; set { c -> c.copy(llmCustomUrl = it) } })
                }
                Spacer(Modifier.height(8.dp))
                Text(spec?.summary ?: "Enter a link to a .litertlm model.", style = Type.Caption)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelChip("GPU", Ink.Iris[3], selected = cfg.llmBackend == Config.BACKEND_GPU) { set { it.copy(llmBackend = Config.BACKEND_GPU) } }
                    LabelChip("CPU", Ink.Iris[5], selected = cfg.llmBackend == Config.BACKEND_CPU) { set { it.copy(llmBackend = Config.BACKEND_CPU) } }
                }
                if (cfg.llmNote.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(cfg.llmNote, style = Type.Caption) }
                ToggleRow("Download over Wi-Fi only", cfg.wifiOnly, { v -> set { it.copy(wifiOnly = v) } })
                Spacer(Modifier.height(8.dp))
                when {
                    ready -> {
                        Text("Model ready.", style = Type.Body)
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Delete model file", { scope.launch { Llm.release(); spec?.delete(context) } })
                    }
                    dl.running -> {
                        Text(if (dl.total > 0) "Downloading · ${dl.bytes / 1_000_000} of ${dl.total / 1_000_000} MB" else "Waiting for network…", style = Type.Caption)
                        Spacer(Modifier.height(8.dp))
                        IrisProgress(if (dl.total > 0) dl.bytes / dl.total.toFloat() else 0f)
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Cancel", { DownloadWorker.cancel(context) })
                    }
                    else -> {
                        dl.error?.let { Text(it, style = Type.Caption.copy(color = Ink.Danger)); Spacer(Modifier.height(8.dp)) }
                        PrimaryButton("Download${spec?.approxMb?.takeIf { it > 0 }?.let { " · ${it} MB" } ?: ""}", { DownloadWorker.start(context) }, enabled = spec != null)
                    }
                }
            }
        }

        SectionTitle("Privacy")
        PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ToggleRow("Lock the app", cfg.appLock, { v -> set { it.copy(appLock = v) } }, "Ask for your fingerprint, face or screen lock when opening, and after a minute in the background. Screenshots are blocked while on.")
            HairLine()
            ToggleRow("Hide in recent apps", cfg.secureScreen, { v -> set { it.copy(secureScreen = v) } }, "Blocks screenshots and blanks the app preview.")
        }

        SectionTitle("Backup")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("Everything, pages included, in one file. Without a backup, losing the phone loses the archive.", style = Type.Caption)
            Text(if (cfg.lastBackup > 0) "Last backup " + DateUtils.getRelativeTimeSpanString(cfg.lastBackup, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else "Never backed up", style = Type.Body, modifier = Modifier.padding(vertical = 8.dp))
            Row2(
                "Back up now" to { backupTo.launch("PaperControl-${LocalDate.now()}.zip") },
                "Restore" to { restoreFrom.launch(arrayOf("application/zip", "application/octet-stream")) },
            )
            Spacer(Modifier.height(10.dp))
            if (cfg.backupUri.isBlank()) SecondaryButton("Choose a folder for weekly backups", { backupFolder.launch(null) })
            else {
                Text("Weekly into the chosen folder; the last 4 are kept.", style = Type.Caption)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Stop weekly backups", { set { it.copy(backupUri = "") }; BackupWorker.schedule(context) })
            }
        }

        SectionTitle("Paperless-ngx server")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("Optional. Keep this archive and your Paperless-ngx server in step: new documents go up with their title, date, sender, type, folder (storage path) and tags; changes and new documents there come down.", style = Type.Caption)
            Field("Server address", url, { url = it; set { c -> c.copy(paperlessUrl = it) } }, placeholder = "https://paperless.example.com")
            Field("API token", token, { token = it; set { c -> c.copy(paperlessToken = it) } }, placeholder = "Profile → API auth token")
            ToggleRow("Upload new documents", cfg.paperlessAutoUpload, { v -> set { it.copy(paperlessAutoUpload = v) } })
            HairLine()
            ToggleRow("Sync both ways", cfg.paperlessSync, { v -> set { it.copy(paperlessSync = v) }; SyncWorker.schedule(context) }, "Every few hours: uploads, sends your edits and deletions, downloads what is new on the server. If both changed, this phone wins.")
            Spacer(Modifier.height(4.dp))
            Row2(
                "Test connection" to { scope.launch { testResult = "Checking…"; testResult = Paperless.test() ?: "Connected." } },
                (if (syncing != null) "$syncing…" else "Sync now") to {
                    if (syncing == null) scope.launch {
                        testResult = "Syncing…"
                        testResult = runCatching { Paperless.sync(context) }.fold(
                            { "Done: ${it.uploaded} uploaded, ${it.downloaded} downloaded, ${it.updatedRemote + it.updatedLocal} updated, ${it.removed} removed" },
                            { "Sync failed: ${it.message}" },
                        )
                    }
                },
            )
            testResult?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Type.Caption) }
            if (cfg.lastSync > 0) Text("Last sync " + DateUtils.getRelativeTimeSpanString(cfg.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), style = Type.Caption)
        }

        SectionTitle("About")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("PaperControl keeps your documents on this phone. Text recognition, sorting, paper mapping, search and the optional AI models all run on the device.", style = Type.Caption)
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            containerColor = Ink.Surface,
            title = { Text("Replace this archive?", style = Type.Title) },
            text = { Text("Everything in the app now is replaced by the contents of the backup.", style = Type.BodySecondary) },
            confirmButton = {
                TextButton({
                    confirmRestore = null
                    scope.launch {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { Backup.restore(context, it) } }
                            .onSuccess { toast("Restored") }.onFailure { toast("Could not restore: ${it.message}") }
                    }
                }) { Text("Replace", color = Ink.Danger) }
            },
            dismissButton = { TextButton({ confirmRestore = null }) { Text("Cancel", color = Ink.Text) } },
        )
    }
}

/** Two pill buttons side by side. */
@Composable
private fun Row2(a: Pair<String, () -> Unit>, b: Pair<String, () -> Unit>) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box2(Modifier.weight(1f)) { SecondaryButton(a.first, a.second) }
        Box2(Modifier.weight(1f)) { SecondaryButton(b.first, b.second) }
    }
}

@Composable
private fun Box2(modifier: Modifier, content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box(modifier) { content() }
