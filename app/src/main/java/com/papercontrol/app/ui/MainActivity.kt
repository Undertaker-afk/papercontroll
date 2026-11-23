package com.papercontrol.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.LabelKind
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

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        shared = sharedUris(intent)
        setContent { PaperTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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

    @Composable
    private fun Root() {
        val nav = remember { Nav(Route.Home()) }
        val context = LocalContext.current

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

        BackHandler(enabled = nav.stack.size > 1 && nav.top !is Route.Scan) { nav.pop() }

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
                    )
                    Route.Scan -> ScanFlow(
                        onClose = { nav.pop() },
                        onSaved = { id -> nav.replace(Route.Doc(id)) },
                        onImport = { uris -> nav.pop(); import(uris) },
                    )
                    is Route.Doc -> DocumentScreen(route.id, onBack = { nav.pop() }, onOpen = { nav.replace(Route.Doc(it)) })
                    Route.Import -> ImportScreen(onDone = { nav.pop() })
                    is Route.Labels -> LabelsScreen(route.kind, onBack = { nav.pop() }, onKind = { nav.replace(Route.Labels(it)) })
                    Route.Settings -> SettingsScreen(onBack = { nav.pop() })
                }
            }
        }
    }
}

/** Reads from the store off the main thread and again whenever the archive changes. */
@Composable
fun <T> query(vararg keys: Any?, initial: T, block: () -> T): T {
    val version by Store.version.collectAsState()
    var value by remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(version, *keys) { value = withContext(Dispatchers.IO) { block() } }
    return value
}
