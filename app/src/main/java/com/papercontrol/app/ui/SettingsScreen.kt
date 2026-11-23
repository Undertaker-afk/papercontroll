package com.papercontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
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
import com.papercontrol.app.data.Config
import com.papercontrol.app.data.ModelCatalog
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.Llm
import com.papercontrol.app.sync.Paperless
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import com.papercontrol.app.work.DownloadWorker
import kotlinx.coroutines.launch

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
    val dl by DownloadWorker.progress.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsState()
    val spec = ModelCatalog.resolve(cfg)
    val ready = remember(cfg, dl, version) { spec?.isReady(context) == true }
    var url by remember { mutableStateOf(cfg.paperlessUrl) }
    var token by remember { mutableStateOf(cfg.paperlessToken) }
    var custom by remember { mutableStateOf(cfg.llmCustomUrl) }
    var testResult by remember { mutableStateOf<String?>(null) }
    fun set(f: (Config) -> Config) = Settings.update(f)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.Gutter).padding(bottom = 130.dp)) {
        Text("Settings", style = Type.LargeTitle, modifier = Modifier.padding(top = 12.dp))

        SectionTitle("Scanning")
        PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ToggleRow("Capture automatically", cfg.autoCapture, { v -> set { it.copy(autoCapture = v) } }, "Takes the photo once the paper is held still.")
            HairLine()
            ToggleRow("Clean up scans", cfg.enhance, { v -> set { it.copy(enhance = v) } }, "White paper, crisp print. The photo as taken is kept too.")
        }

        SectionTitle("Automatic sorting")
        PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ToggleRow("Sort new documents", cfg.autoSort, { v -> set { it.copy(autoSort = v) } }, "Rules you set, plus the Auto classifier that learns from documents you have sorted.")
            HairLine()
            ToggleRow("Create senders and folders", cfg.createLabels, { v -> set { it.copy(createLabels = v) } }, "New correspondents are added from the name on the paper.")
            HairLine()
            ToggleRow("Paper mapping", cfg.paperMapping, { v -> set { it.copy(paperMapping = v) } }, "When importing many photos, pages of the same document are found, ordered and kept together.")
        }

        SectionTitle("On-device AI model")
        PaperCard(padding = PaddingValues(16.dp)) {
            ToggleRow("Use a local AI model", cfg.llmEnabled, { v -> set { it.copy(llmEnabled = v) } }, "Writes titles, reads what the rules miss, sorts into your labels and settles close calls in paper mapping. Runs on the phone; nothing is uploaded.")
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

        SectionTitle("Paperless-ngx server")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("Optional. Upload documents to your own Paperless-ngx with their title, date, sender, type, folder (storage path) and tags.", style = Type.Caption)
            Field("Server address", url, { url = it; set { c -> c.copy(paperlessUrl = it) } }, placeholder = "https://paperless.example.com")
            Field("API token", token, { token = it; set { c -> c.copy(paperlessToken = it) } }, placeholder = "Profile → API auth token")
            ToggleRow("Upload new documents", cfg.paperlessAutoUpload, { v -> set { it.copy(paperlessAutoUpload = v) } })
            Spacer(Modifier.height(4.dp))
            SecondaryButton("Test connection", { scope.launch { testResult = "Checking…"; testResult = Paperless.test() ?: "Connected." } })
            testResult?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Type.Caption) }
        }

        SectionTitle("About")
        PaperCard(padding = PaddingValues(16.dp)) {
            Text("PaperControl keeps your documents on this phone. Text recognition, sorting, paper mapping and the optional AI model all run on the device.", style = Type.Caption)
        }
    }
}
