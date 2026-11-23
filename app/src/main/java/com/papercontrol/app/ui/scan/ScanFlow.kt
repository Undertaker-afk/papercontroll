package com.papercontrol.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.papercontrol.app.ui.StatusPill
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.papercontrol.app.core.DocKind
import com.papercontrol.app.data.Palette
import com.papercontrol.app.processing.Sorting
import com.papercontrol.app.ui.CircleButton
import com.papercontrol.app.ui.LabelChip
import com.papercontrol.app.ui.PillButton
import com.papercontrol.app.ui.PrimaryButton
import com.papercontrol.app.ui.ReceiptCard
import com.papercontrol.app.ui.SecondaryButton
import com.papercontrol.app.ui.Thumb
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scan → reveal → review, as in the reference animation:
 *  1. camera with paper detection,
 *  2. shutter flash; the surroundings fade to black and the paper is straightened,
 *  3. an iridescent band sweeps down the paper, cleaning it up and inking the fields it read,
 *  4. the paper dissolves through an expanding ring into its digital card,
 *  5. the original photo is attached and the button lights up.
 */
@Composable
fun ScanFlow(onClose: () -> Unit, onSaved: (Long) -> Unit, onImport: (List<Uri>) -> Unit, onOpen: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { ScanSession(context.applicationContext, scope) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    fun close() { session.discardAll(); onClose() }
    BackHandler { if (session.stage == Stage.REVEAL) session.retake() else close() }

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        when {
            session.stage == Stage.CAMERA && granted -> CameraLayer(
                title = "Scan document",
                pageCount = session.pages.size,
                lastThumb = session.pages.lastOrNull()?.file?.path,
                onClose = ::close,
                onCaptured = { bmp, quad -> session.onCaptured(bmp, quad) },
                onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            session.stage == Stage.CAMERA -> NoCamera(onAsk = { permission.launch(Manifest.permission.CAMERA) }, onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onClose = ::close)
            else -> {
                val capture = session.capture
                if (capture != null) RevealAndReview(session, capture, onClose = ::close, onSaved = onSaved, onOpen = { id -> session.discardAll(); onOpen(id) })
                else ShotPending(session)
            }
        }
    }
}

/**
 * The moment after the shutter: the frozen photo with a flash, then the page that was found
 * lit up and everything else dimmed, while it is straightened and read.
 */
@Composable
private fun ShotPending(session: ScanSession) {
    val photo = session.pendingPhoto ?: return
    val quad = session.pendingQuad
    val img = remember(photo) { photo.asImageBitmap() }
    val flash = remember { Animatable(1f) }
    val spot = remember { Animatable(0f) }
    LaunchedEffect(photo) { flash.snapTo(1f); flash.animateTo(0f, tween(420, easing = FastOutSlowInEasing)) }
    LaunchedEffect(quad) { if (quad != null) spot.animateTo(1f, tween(260)) else spot.snapTo(0f) }
    val phase by rememberInfiniteTransition(label = "pending").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Black)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val aspect = photo.width.toFloat() / photo.height
        val scale = max(w / aspect, h)
        val photoRect = Rect(Offset((w - aspect * scale) / 2f, (h - scale) / 2f), androidx.compose.ui.geometry.Size(aspect * scale, scale))
        Canvas(Modifier.fillMaxSize()) {
            drawImage(img, dstOffset = IntOffset(photoRect.left.roundToInt(), photoRect.top.roundToInt()), dstSize = IntSize(photoRect.width.roundToInt(), photoRect.height.roundToInt()))
            if (quad != null) {
                val pts = quad.points.map { Offset(photoRect.left + it.x * photoRect.width, photoRect.top + it.y * photoRect.height) }
                drawSpotlight(photoRect, pts, 0.55f * spot.value)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts[0].x, pts[0].y); lineTo(pts[1].x, pts[1].y); lineTo(pts[2].x, pts[2].y); lineTo(pts[3].x, pts[3].y); close()
                }
                // A light running round the outline while the page is read.
                drawPath(path, Brush.sweepGradient(Ink.Iris, Offset(pts.sumOf { it.x.toDouble() }.toFloat() / 4, pts.sumOf { it.y.toDouble() }.toFloat() / 4)), alpha = 0.9f * spot.value, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawPath(path, Color.White, alpha = 0.55f * spot.value, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            }
            drawRect(Color.White, alpha = flash.value * 0.85f)
        }
        if (phase >= 0f) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp)) {
                StatusPill(if (quad == null) "Finding the page…" else "Reading…")
            }
        }
        session.error?.let { err ->
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(Metrics.Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(err, style = Type.BodySecondary)
                SecondaryButton("Retake", { session.retake() })
            }
        }
    }
}

@Composable
private fun RevealAndReview(session: ScanSession, capture: Capture, onClose: () -> Unit, onSaved: (Long) -> Unit, onOpen: (Long) -> Unit) {
    val anim = remember(capture) { RevealAnim() }

    LaunchedEffect(capture) {
        // 2. The page lifts out of the photo and straightens while the surroundings go dark.
        launch { delay(60); anim.photo.animateTo(0f, tween(380, easing = FastOutSlowInEasing)) }
        anim.peel.animateTo(1f, tween(720, easing = LinearEasing))
        // 3. The band rises at the top edge, then sweeps down inking in what was read.
        anim.band.snapTo(-0.06f)
        anim.bandGlow.animateTo(1f, tween(240))
        anim.band.animateTo(1.12f, tween(1350, easing = EaseBand))
        anim.bandGlow.animateTo(0f, tween(260))
        // Hold on the clean scan while sorting finishes.
        delay(250)
        snapshotFlow { session.ready || session.error != null }.first { it }
        if (session.error != null) return@LaunchedEffect
        snapshotFlow { anim.cardRect }.first { it != null }
        // 4. Header in, paper dissolves into the card.
        launch { anim.header.animateTo(1f, tween(320)) }
        delay(120)
        launch { delay(380); anim.aura.snapTo(1f); anim.aura.animateTo(0f, tween(1300, easing = LinearEasing)) }
        anim.morph.animateTo(1f, tween(780, easing = LinearEasing))
        // 5. Photo attached, button ready.
        delay(150)
        anim.footer.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        anim.done = true
    }

    RevealLayout(session, capture, anim, onClose, onSaved, onOpen)
}

/** The review layout with the reveal drawn over it, at whatever point [anim] stands. */
@Composable
internal fun RevealLayout(session: ScanSession, capture: Capture, anim: RevealAnim, onClose: () -> Unit, onSaved: (Long) -> Unit, onOpen: (Long) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val done = anim.done
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Black).onGloballyPositioned { origin = it.positionInRoot() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val photoAspect = capture.photo.width.toFloat() / capture.photo.height
        val photoRect = remember(capture, w, h) {
            val scale = max(w / photoAspect, h)
            Rect(Offset((w - photoAspect * scale) / 2f, (h - scale) / 2f), androidx.compose.ui.geometry.Size(photoAspect * scale, scale))
        }
        val aspect = capture.flat.width.toFloat() / capture.flat.height
        // Where the page's corners are on screen while it still lies in the photo.
        val quadScreen = remember(capture, photoRect) {
            capture.corners.map { Offset(photoRect.left + it.x * photoRect.width, photoRect.top + it.y * photoRect.height) }
        }
        // Where it comes to rest: centred, as large as fits above the button.
        val paperRect = remember(capture, w, h) {
            val margin = with(density) { 24.dp.toPx() }
            var ph = h * 0.74f
            var pw = ph * aspect
            if (pw > w - 2 * margin) { pw = w - 2 * margin; ph = pw / aspect }
            val cy = h * 0.46f
            Rect(w / 2 - pw / 2, cy - ph / 2, w / 2 + pw / 2, cy + ph / 2)
        }

        Review(
            session = session,
            anim = anim,
            interactive = done,
            onCard = { anim.cardRect = it.translate(-origin) },
            onClose = onClose,
            onRetake = { session.retake() },
            onAddPage = { session.addPage() },
            onSave = { scope.launch { session.save(null)?.let(onSaved) } },
            onOpen = onOpen,
        )

        if (!done) RevealOverlay(capture, anim, paperRect, anim.cardRect, photoRect, quadScreen)

        session.error?.let { err ->
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(Metrics.Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(err, style = Type.BodySecondary)
                SecondaryButton("Retake", { session.retake() })
            }
        }
    }
}

@Composable
private fun Review(
    session: ScanSession,
    anim: RevealAnim,
    interactive: Boolean,
    onCard: (Rect) -> Unit,
    onClose: () -> Unit,
    onRetake: () -> Unit,
    onAddPage: () -> Unit,
    onSave: () -> Unit,
    onOpen: (Long) -> Unit = {},
) {
    val ex = session.extraction
    val sorting = session.sorting
    val kind = ex.kind
    val title = when (kind) { DocKind.RECEIPT -> "New expense"; DocKind.INVOICE -> "New invoice"; else -> "New document" }
    val button = when (kind) { DocKind.RECEIPT -> "Add expense"; DocKind.INVOICE -> "Add invoice"; else -> "Add document" }
    val header = anim.header.value
    val footer = anim.footer.value
    val morph = anim.morph.value

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).height(56.dp).graphicsLayer { alpha = header }) {
            CircleButton(Icons.Default.Close, "Close", onClose, Modifier.align(Alignment.CenterStart))
            PillButton("Retake", onRetake, Modifier.align(Alignment.CenterEnd))
        }
        Text(
            title, style = Type.LargeTitle,
            modifier = Modifier.padding(start = Metrics.Gutter, top = 8.dp, bottom = 18.dp).graphicsLayer { alpha = header; translationY = (1f - header) * 8.dp.toPx() },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(), enabled = interactive).padding(horizontal = Metrics.Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReceiptCard(
                ex = ex,
                title = sorting?.title ?: "Document",
                currency = sorting?.currency ?: ex.currency,
                fallbackText = session.pages.firstOrNull()?.ocr?.text.orEmpty(),
                modifier = Modifier
                    .onGloballyPositioned { onCard(it.boundsInRoot()) }
                    .drawWithContent {
                        // Revealed through the expanding ring; fully shown once the morph is done.
                        if (morph >= 1f || interactive) { drawContent(); return@drawWithContent }
                        if (morph <= 0f) return@drawWithContent
                        val r = hypot(size.width, size.height) * 0.62f * holeProgress(morph)
                        val p = Path().apply { addOval(Rect(center, r)) }
                        clipPath(p) { this@drawWithContent.drawContent() }
                    },
            )
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = footer; translationY = (1f - footer) * 10.dp.toPx() }
                    .clip(RoundedCornerShape(Metrics.CardRadius)).background(Ink.Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(session.pages.firstOrNull()?.original?.path, Modifier.size(40.dp), radius = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (session.pages.size > 1) "Original photos · ${session.pages.size}" else "Original photo", style = Type.Body)
                    Text("Attached to this ${if (kind == DocKind.RECEIPT) "expense" else "document"}", style = Type.Caption)
                }
                PillButton("Page", onAddPage, icon = Icons.Default.Add, background = Ink.SurfaceHigh)
            }
            session.duplicates.firstOrNull()?.let { dup ->
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { alpha = footer }.clip(RoundedCornerShape(Metrics.CardRadius))
                        .background(Ink.Surface).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ContentCopy, null, tint = Ink.Danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("You may have this already", style = Type.Body)
                        Text("${dup.reason} as “${dup.title}”", style = Type.Caption, maxLines = 2)
                    }
                    PillButton("Open", { onOpen(dup.docId) }, background = Ink.SurfaceHigh)
                }
            }
            if (sorting != null) SortingSummary(sorting, Modifier.graphicsLayer { alpha = footer })
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(horizontal = Metrics.Gutter, vertical = 12.dp).graphicsLayer { alpha = header }) {
            PrimaryButton(button, onSave, enabled = interactive && session.ready && !session.saving)
        }
    }
}

/** Where the document will be filed: folder, sender, type and tags, each picked by the sorter. */
@Composable
private fun SortingSummary(s: Sorting, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.CardRadius)).background(Ink.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = Ink.Secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (s.usedAi) "Sorted by on-device AI" else "Sorted automatically", style = Type.Caption)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.folder?.let { LabelChip("Folder · ${it.name}", Color(Palette.forName(it.name))) }
            s.correspondent?.let { LabelChip(it.name + if (it.id == null) " · new" else "", Color(Palette.forName(it.name))) }
            s.docType?.let { LabelChip(it.name, Color(Palette.forName(it.name))) }
            s.tags.forEach { LabelChip(it.name, Color(Palette.forName(it.name))) }
        }
    }
}

@Composable
private fun NoCamera(onAsk: () -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Metrics.Gutter)) {
        CircleButton(Icons.Default.Close, "Close", onClose)
        Spacer(Modifier.weight(1f))
        Text("Camera access", style = Type.LargeTitle)
        Spacer(Modifier.height(8.dp))
        Text("PaperControl needs the camera to scan paper. Photos stay on this phone.", style = Type.BodySecondary)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Allow camera", onAsk)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Import photos instead", onGallery)
    }
}
