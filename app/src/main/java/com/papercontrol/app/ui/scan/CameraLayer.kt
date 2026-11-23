package com.papercontrol.app.ui.scan

import android.graphics.Bitmap
import android.util.Log
import android.util.Size as PxSize
import android.view.HapticFeedbackConstants
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.papercontrol.app.core.Pt
import com.papercontrol.app.core.Quad
import com.papercontrol.app.data.Settings
import com.papercontrol.app.processing.Images
import com.papercontrol.app.ui.CircleButton
import com.papercontrol.app.ui.StatusPill
import com.papercontrol.app.ui.Thumb
import com.papercontrol.app.ui.pressable
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Type
import com.papercontrol.app.vision.Cv
import com.papercontrol.app.vision.Found
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the camera sees right now: the page found in the live frame, steadied over time. */
class Detection {
    /** The page in the upright frame, as fractions of it; null when none is in view. */
    var quad by mutableStateOf<Quad?>(null)
    var how by mutableStateOf<Found?>(null)
    /** Width / height of the upright frame, for mapping onto the preview. */
    var frameAspect by mutableStateOf(3f / 4f)
    /** When the page last stopped moving; 0 while it moves or is not in view. */
    var stableSince by mutableLongStateOf(0L)
    private var misses = 0

    /** Takes in a result from the analysis thread. */
    fun update(found: com.papercontrol.app.vision.Detection?, aspect: Float) {
        frameAspect = aspect
        if (found == null) {
            // A few missed frames in a row before letting go: detection flickers.
            if (++misses >= 4) { quad = null; how = null; stableSince = 0L }
            return
        }
        misses = 0
        val prev = quad
        if (prev == null) {
            quad = found.quad; how = found.how; stableSince = System.currentTimeMillis()
            return
        }
        val moved = prev.distance(found.quad)
        if (moved > 0.12f) {
            quad = found.quad; stableSince = System.currentTimeMillis() // a different page
        } else {
            quad = prev.lerp(found.quad, 0.55f)
            if (moved > 0.025f) stableSince = System.currentTimeMillis()
        }
        how = found.how
    }
}

/**
 * The camera screen from the reference: close button, title and torch on top, a status pill,
 * corner brackets that wrap the page, the shutter with the gallery on its left and "Auto" on
 * its right. The page found in the live view is outlined as it will be cut out.
 */
@Composable
fun CameraLayer(
    title: String,
    pageCount: Int,
    lastThumb: String?,
    onClose: () -> Unit,
    onCaptured: (Bitmap, Quad?) -> Unit,
    onGallery: () -> Unit,
    uiAlpha: Float = 1f,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val detection = remember { Detection() }
    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Settings.current.autoCapture) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var stable by remember { mutableStateOf(0f) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(96)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // The surface-backed view composites the camera at its full resolution; the texture-backed one rescales it.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            fun selector(w: Int, h: Int) = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(PxSize(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector(1920, 1440)).build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var lastRun = 0L
            val mat = Mat()
            analysis.setAnalyzer(analysisExecutor) { image ->
                val now = System.currentTimeMillis()
                if (now - lastRun > 120) {
                    lastRun = now
                    analyze(image, mat, detection)
                }
                image.close()
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            } catch (t: Throwable) {
                Log.w("Camera", "bind failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    fun shoot() {
        if (capturing) return
        capturing = true
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val live = detection.quad
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = runCatching { image.toBitmap() }.getOrNull()
                image.close()
                if (bmp == null) { capturing = false; return }
                Thread {
                    val upright = Images.scaleDown(Images.rotate(bmp, rotation), Images.CAPTURE_SIDE)
                    previewView.post { capturing = false; onCaptured(upright, live) }
                }.start()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w("Camera", "capture failed", exception)
                capturing = false
            }
        })
    }

    // Hold still: the ring around the shutter fills while the page does not move, then the photo is taken.
    LaunchedEffect(detection.stableSince, auto) {
        stable = 0f
        val since = detection.stableSince
        if (since == 0L) return@LaunchedEffect
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        while (true) {
            val t = (System.currentTimeMillis() - since) / HOLD_MS.toFloat()
            stable = t.coerceIn(0f, 1f)
            if (t >= 1f) break
            delay(30)
        }
        if (auto && detection.quad != null && !capturing) shoot()
    }

    Box(Modifier.fillMaxSize().background(Ink.Camera)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        // Only enough shade at the top and bottom to read white controls on a bright desk.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.32f), 0.2f to Color.Transparent, 0.78f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.4f))))

        // Tap to focus.
        Box(
            Modifier.fillMaxSize().pointerInput(camera) {
                detectTapGestures { p ->
                    val c = camera ?: return@detectTapGestures
                    val pt = previewView.meteringPointFactory.createPoint(p.x, p.y)
                    c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build())
                    focusAt = p
                }
            }
        )
        FocusRing(focusAt) { focusAt = null }

        PageOutline(detection, stable, Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha })

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().graphicsLayer { alpha = uiAlpha }) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(56.dp)) {
                CircleButton(Icons.Default.Close, "Close", onClose, Modifier.align(Alignment.CenterStart), size = 40.dp)
                Text(if (pageCount > 0) "$title · page ${pageCount + 1}" else title, style = Type.Title, modifier = Modifier.align(Alignment.Center))
                Icon(
                    Icons.Default.Bolt, "Torch",
                    tint = if (torch) Color(0xFFFFE45C) else Ink.Text,
                    modifier = Modifier.align(Alignment.CenterEnd).size(40.dp).clip(CircleShape).pressable({ torch = !torch }).padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            val status = when {
                detection.quad == null -> "Looking for paper…"
                stable < 1f && auto -> "Hold still…"
                else -> "Paper detected"
            }
            AnimatedContent(
                status,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                label = "pill",
            ) { text -> StatusPill(text, dot = if (detection.quad != null) Ink.Text else Ink.Tertiary) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart).size(52.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Surface).pressable(onGallery),
                    contentAlignment = Alignment.Center,
                ) {
                    if (lastThumb != null) Thumb(lastThumb, Modifier.size(52.dp), radius = 14.dp)
                    else Icon(Icons.Outlined.PhotoLibrary, "Import photos", tint = Ink.Secondary, modifier = Modifier.size(22.dp))
                }
                Shutter(Modifier.align(Alignment.Center), busy = capturing, progress = if (auto) stable else 0f) { shoot() }
                Text(
                    if (auto) "Auto" else "Manual",
                    style = Type.Body.copy(color = if (auto) Ink.Text else Ink.Secondary),
                    modifier = Modifier.align(Alignment.CenterEnd).clip(CircleShape).background(Ink.Surface.copy(alpha = 0.6f)).pressable({ auto = !auto }).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private const val HOLD_MS = 900L

@Composable
private fun Shutter(modifier: Modifier, busy: Boolean, progress: Float, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (busy) 0.86f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "shutter")
    Box(modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            drawCircle(Color.White, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (progress > 0f) {
                // The filling ring: the page has stopped moving.
                drawArc(
                    Brush.sweepGradient(Ink.Iris, center), -90f, 360f * progress, false,
                    topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke + 1.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Box(Modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(Color.White).pressable(onClick))
    }
}

@Composable
private fun FocusRing(at: Offset?, onDone: () -> Unit) {
    val scale = remember { Animatable(1.4f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(at) {
        if (at == null) return@LaunchedEffect
        scale.snapTo(1.4f); alpha.snapTo(1f)
        launch { scale.animateTo(1f, tween(220)) }
        delay(700)
        alpha.animateTo(0f, tween(300))
        onDone()
    }
    if (at != null) Canvas(Modifier.fillMaxSize()) {
        val r = 34.dp.toPx() * scale.value
        drawCircle(Color.White, r, at, alpha = alpha.value, style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * The page as it will be cut out: a faint fill, a thin outline and corner brackets that run
 * along its real edges. The corners glide to each new detection.
 */
@Composable
private fun PageOutline(detection: Detection, hold: Float, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val target = detection.quad?.let { screenPoints(it, detection.frameAspect, w, h) }
        // Where the brackets rest while nothing is found: a centred frame.
        val rest = listOf(Offset(w * 0.18f, h * 0.22f), Offset(w * 0.82f, h * 0.22f), Offset(w * 0.82f, h * 0.74f), Offset(w * 0.18f, h * 0.74f))
        val dest = target ?: rest
        val xs = remember { List(4) { Animatable(rest[it].x) } }
        val ys = remember { List(4) { Animatable(rest[it].y) } }
        LaunchedEffect(dest) {
            for (i in 0 until 4) {
                launch { xs[i].animateTo(dest[i].x, tween(150)) }
                launch { ys[i].animateTo(dest[i].y, tween(150)) }
            }
        }
        val found by animateFloatAsState(if (target != null) 1f else 0f, tween(200), label = "found")
        Canvas(Modifier.fillMaxSize()) {
            val p = List(4) { Offset(xs[it].value, ys[it].value) }
            val path = Path().apply { moveTo(p[0].x, p[0].y); lineTo(p[1].x, p[1].y); lineTo(p[2].x, p[2].y); lineTo(p[3].x, p[3].y); close() }
            if (found > 0f) {
                drawPath(path, Color.White, alpha = 0.07f * found)
                drawPath(path, Color.White, alpha = (0.35f + 0.5f * hold) * found, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            drawBrackets(p, Color.White.copy(alpha = 0.6f + 0.4f * found))
        }
    }
}

/** Corner brackets that follow the quad's edges. */
private fun DrawScope.drawBrackets(p: List<Offset>, color: Color) {
    val arm = 26.dp.toPx()
    val round = 10.dp.toPx()
    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (i in 0 until 4) {
        val c = p[i]
        val a = p[(i + 3) % 4]
        val b = p[(i + 1) % 4]
        fun unit(from: Offset, to: Offset): Offset { val d = to - from; val l = hypot(d.x, d.y).coerceAtLeast(1f); return Offset(d.x / l, d.y / l) }
        val ua = unit(c, a)
        val ub = unit(c, b)
        val la = min(arm, hypot(a.x - c.x, a.y - c.y) * 0.4f)
        val lb = min(arm, hypot(b.x - c.x, b.y - c.y) * 0.4f)
        val r = min(round, min(la, lb))
        val path = Path().apply {
            moveTo(c.x + ua.x * la, c.y + ua.y * la)
            lineTo(c.x + ua.x * r, c.y + ua.y * r)
            quadraticTo(c.x, c.y, c.x + ub.x * r, c.y + ub.y * r)
            lineTo(c.x + ub.x * lb, c.y + ub.y * lb)
        }
        drawPath(path, color, style = stroke)
    }
}

/** Maps a quad in the upright camera frame onto the screen, as PreviewView's FILL_CENTER does. */
fun screenPoints(q: Quad, frameAspect: Float, w: Float, h: Float): List<Offset> {
    val scale = max(w / frameAspect, h)
    val fw = frameAspect * scale
    val fh = scale
    val ox = (w - fw) / 2f
    val oy = (h - fh) / 2f
    return q.points.map { Offset(ox + it.x * fw, oy + it.y * fh) }
}

/** The quad's bounding box on screen. */
fun toScreen(q: Quad, frameAspect: Float, w: Float, h: Float): Rect {
    val p = screenPoints(q, frameAspect, w, h)
    return Rect(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
}

/** Looks for a page in one live frame; runs on the analysis thread. */
private fun analyze(image: ImageProxy, mat: Mat, detection: Detection) {
    try {
        val bmp = image.toBitmap()
        Utils.bitmapToMat(bmp, mat)
        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val aspect = if (rot == 90 || rot == 270) bmp.height.toFloat() / bmp.width else bmp.width.toFloat() / bmp.height
        val found = Cv.detectFrame(mat, rot)
        // Publish on the main thread so the outline and the pill change together.
        android.os.Handler(android.os.Looper.getMainLooper()).post { detection.update(found, aspect) }
    } catch (t: Throwable) {
        Log.w("Camera", "analysis failed", t)
    }
}
