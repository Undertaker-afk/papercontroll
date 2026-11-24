package com.papercontrol.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Importer
import com.papercontrol.app.ui.scan.ScanFlow
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.PaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data class Home(val filter: Filter = Filter(), val tab: Tab = Tab.DOCUMENTS) : Route
    data object Scan : Route
    data class Doc(val id: Long) : Route
    data object Import : Route
    data class Labels(val kind: LabelKind) : Route
    data object Settings : Route
    data object Trash : Route
    data object Insights : Route
    data object Ask : Route
}

enum class Tab { DOCUMENTS, FOLDERS, LABELS, SETTINGS }

/** A tiny back stack. */
class Nav(start: Route) {
    val stack = mutableStateListOf(start)
    val top: Route get() = stack.last()
    fun push(r: Route) { stack += r }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun replace(r: Route) { stack[stack.lastIndex] = r }
}

class MainActivity : FragmentActivity() {
    private var shared by mutableStateOf<List<Uri>>(emptyList())
    private var openDoc by mutableStateOf<Long?>(null)
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        locked = Settings.current.appLock
        handle(intent)
        setContent { PaperTheme { Root() } }
        if (Build.VERSION.SDK_INT >= 33 && Settings.current.reminders &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Lock again after a minute in the background.
        if (Settings.current.appLock && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 60_000) locked = true
        applySecure()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    fun applySecure() {
        if (Settings.current.secureScreen || Settings.current.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun handle(intent: Intent?) {
        intent?.getLongExtra(EXTRA_DOC, -1L)?.takeIf { it > 0 }?.let { openDoc = it }
        shared = sharedUris(intent)
    }

    private fun sharedUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty()
        else -> emptyList()
    }

    /** Fingerprint, face or the phone's screen lock. */
    fun unlock() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock set up on this phone: nothing to check against.
            locked = false
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock PaperControl")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    @Composable
    private fun Root() {
        val nav = remember { Nav(Route.Home()) }
        val context = LocalContext.current
        val cfg by Settings.flow.collectAsState()

        fun import(uris: List<Uri>) {
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch { Importer.run(context.applicationContext, uris) }
            nav.push(Route.Import)
        }

        LaunchedEffect(shared) {
            if (shared.isNotEmpty()) {
                val uris = shared
                shared = emptyList()
                import(uris)
            }
        }
        LaunchedEffect(openDoc) {
            openDoc?.let { id -> if (withContext(Dispatchers.IO) { Store.document(id) } != null) nav.push(Route.Doc(id)); openDoc = null }
        }
        LaunchedEffect(cfg.appLock, cfg.secureScreen) { applySecure() }
        LaunchedEffect(locked) { if (locked) unlock() }

        BackHandler(enabled = nav.stack.size > 1 && nav.top !is Route.Scan) { nav.pop() }
        var report by remember { mutableStateOf(com.papercontrol.app.CrashLog.pending()) }

        Box(Modifier.fillMaxSize().background(Ink.Black)) {
            AnimatedContent(
                targetState = nav.top,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.01f))
                },
                contentKey = { it::class },
                label = "nav",
            ) { route ->
                when (route) {
                    is Route.Home -> HomeScreen(
                        route = route,
                        onRoute = { nav.replace(it) },
                        onOpen = { nav.push(Route.Doc(it)) },
                        onScan = { nav.push(Route.Scan) },
                        onImport = ::import,
                        onLabels = { nav.push(Route.Labels(it)) },
                        onGo = { nav.push(it) },
                    )
                    Route.Scan -> ScanFlow(
                        onClose = { nav.pop() },
                        onSaved = { id -> nav.replace(Route.Doc(id)) },
                        onImport = { uris -> nav.pop(); import(uris) },
                        onOpen = { id -> nav.replace(Route.Doc(id)) },
                    )
                    is Route.Doc -> DocumentScreen(route.id, onBack = { nav.pop() }, onOpen = { nav.push(Route.Doc(it)) })
                    Route.Import -> ImportScreen(onDone = { nav.pop() })
                    is Route.Labels -> LabelsScreen(route.kind, onBack = { nav.pop() }, onKind = { nav.replace(Route.Labels(it)) })
                    Route.Settings -> SettingsScreen(onBack = { nav.pop() })
                    Route.Trash -> TrashScreen(onBack = { nav.pop() }, onOpen = { nav.push(Route.Doc(it)) })
                    Route.Insights -> InsightsScreen(onBack = { nav.pop() }, onOpen = { nav.push(Route.Doc(it)) }, onFilter = { nav.replace(Route.Home(it)) })
                    Route.Ask -> AskScreen(onBack = { nav.pop() }, onOpen = { nav.push(Route.Doc(it)) })
                }
            }
            AnimatedVisibility(locked, enter = fadeIn(), exit = fadeOut()) { LockScreen(onUnlock = ::unlock) }
            report?.let { text -> CrashDialog(text, onDismiss = { com.papercontrol.app.CrashLog.clear(); report = null }) }
        }
    }

    companion object {
        const val EXTRA_DOC = "doc"
    }
}

/** Reads from the store off the main thread and again whenever the archive changes. */
@Composable
fun <T> query(vararg keys: Any?, initial: T, block: suspend () -> T): T {
    val version by Store.version.collectAsState()
    var value by remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(version, *keys) {
        // A failed read keeps what is on screen instead of closing the app.
        value = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            com.papercontrol.app.CrashLog.note("reading the archive", t)
            value
        }
    }
    return value
}

/** Shares a file from the app's cache or files through the system share sheet. */
fun shareFile(context: android.content.Context, file: java.io.File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), title))
}

/** What went wrong last time, with the exact error, ready to share. */
@Composable
private fun CrashDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Surface,
        title = { androidx.compose.material3.Text("PaperControl closed unexpectedly", style = com.papercontrol.app.ui.theme.Type.Title) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("This is what went wrong. Share it so it can be fixed.", style = com.papercontrol.app.ui.theme.Type.Caption)
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text(
                    text.take(4000),
                    style = com.papercontrol.app.ui.theme.Type.Caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share crash report"))
            }) { androidx.compose.material3.Text("Share", color = Ink.Text) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { androidx.compose.material3.Text("Dismiss", color = Ink.Secondary) } },
    )
}
