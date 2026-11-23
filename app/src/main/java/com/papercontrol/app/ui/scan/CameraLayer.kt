package com.papercontrol.app.ui.scan

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.papercontrol.app.core.Quad
import com.papercontrol.app.core.QuadDetector
import com.papercontrol.app.data.Settings
import com.papercontrol.app.processing.Images
import com.papercontrol.app.ui.CircleButton
import com.papercontrol.app.ui.StatusPill
import com.papercontrol.app.ui.Thumb
import com.papercontrol.app.ui.pressable
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Type
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import kotlin.math.max

/** Live view of where the paper is, in normalized coordinates of the (rotated) camera frame. */
class Detection {
    var quad by mutableStateOf<Quad?>(null)
    var frameAspect by mutableStateOf(3f / 4f)
    var stableSince by mutableLongStateOf(0L)
}

/**
 * The camera screen from the reference: close button, title and torch on top, a
 * "Receipt detected" pill, corner brackets that snap around the paper, the shutter with the
 * gallery on its left and "Auto" on its right.
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
    val detection = remember { Detection() }
    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Settings.current.autoCapture) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var lastRun = 0L
            analysis.setAnalyzer(analysisExecutor) { image ->
                val now = System.currentTimeMillis()
                if (now - lastRun > 110) {
                    lastRun = now
                    analyze(image, detection)
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
        val live = detection.quad
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = runCatching { image.toBitmap() }.getOrNull()
                image.close()
                if (bmp == null) { capturing = false; return }
                Thread {
                    val upright = Images.scaleDown(Images.rotate(bmp, rotation), Images.MAX_SIDE)
                    previewView.post { capturing = false; onCaptured(upright, live) }
                }.start()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w("Camera", "capture failed", exception)
                capturing = false
            }
        })
    }

    // Auto capture: once the paper has been held still for a moment.
    LaunchedEffect(auto, detection.stableSince) {
        if (!auto || detection.stableSince == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        if (detection.quad != null && detection.stableSince != 0L && System.currentTimeMillis() - detection.stableSince >= 850) shoot()
    }

    Box(Modifier.fillMaxSize().background(Ink.Camera)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        // Slight darkening so white UI reads on any background, as in the reference.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))

        Brackets(detection, Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha })

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
            AnimatedContent(
                detection.quad != null,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                label = "pill",
            ) { found ->
                StatusPill(if (found) "Paper detected" else "Looking for paper…", dot = if (found) Ink.Text else Ink.Tertiary)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart).size(52.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Surface).pressable(onGallery),
                    contentAlignment = Alignment.Center,
                ) {
                    if (lastThumb != null) Thumb(lastThumb, Modifier.size(52.dp), radius = 14.dp)
                    else Icon(Icons.Outlined.PhotoLibrary, "Import photos", tint = Ink.Secondary, modifier = Modifier.size(22.dp))
                }
                Shutter(Modifier.align(Alignment.Center), busy = capturing) { shoot() }
                Text(
                    if (auto) "Auto" else "Manual",
                    style = Type.Body.copy(color = if (auto) Ink.Text else Ink.Secondary),
                    modifier = Modifier.align(Alignment.CenterEnd).clip(CircleShape).background(Ink.Surface.copy(alpha = 0.6f)).pressable({ auto = !auto }).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun Shutter(modifier: Modifier, busy: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (busy) 0.86f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "shutter")
    Box(modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(62.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(Color.White).pressable(onClick))
    }
}

/** Corner brackets: rest in a default frame, glide to the paper's bounds when it is found. */
@Composable
private fun Brackets(detection: Detection, modifier: Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val rest = Rect(w * 0.18f, h * 0.22f, w * 0.82f, h * 0.74f)
        val target = detection.quad?.let { toScreen(it, detection.frameAspect, w, h) }?.inflate(with(density) { 10.dp.toPx() }) ?: rest
        val l = remember { Animatable(rest.left) }
        val t = remember { Animatable(rest.top) }
        val r = remember { Animatable(rest.right) }
        val b = remember { Animatable(rest.bottom) }
        LaunchedEffect(target) {
            val spec = tween<Float>(260, easing = FastOutSlowInEasing)
            kotlinx.coroutines.coroutineScope {
                launch { l.animateTo(target.left, spec) }
                launch { t.animateTo(target.top, spec) }
                launch { r.animateTo(target.right, spec) }
                launch { b.animateTo(target.bottom, spec) }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val arm = 22.dp.toPx()
            val rad = 10.dp.toPx()
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val c = Color.White
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, y + sy * arm)
                    lineTo(x, y + sy * rad)
                    quadraticTo(x, y, x + sx * rad, y)
                    lineTo(x + sx * arm, y)
                }
                drawPath(path, c, style = stroke)
            }
            corner(l.value, t.value, 1f, 1f)
            corner(r.value, t.value, -1f, 1f)
            corner(r.value, b.value, -1f, -1f)
            corner(l.value, b.value, 1f, -1f)
        }
    }
}

/** Maps a normalized quad in the camera frame to the screen, as PreviewView's FILL_CENTER does. */
fun toScreen(q: Quad, frameAspect: Float, w: Float, h: Float): Rect {
    val fw: Float
    val fh: Float
    val scale = max(w / frameAspect, h)
    fw = frameAspect * scale
    fh = scale
    val ox = (w - fw) / 2f
    val oy = (h - fh) / 2f
    val xs = q.points.map { ox + it.x * fw }
    val ys = q.points.map { oy + it.y * fh }
    return Rect(xs.min(), ys.min(), xs.max(), ys.max())
}

/** Reads the luminance plane, rotates it upright and looks for paper. */
private fun analyze(image: ImageProxy, detection: Detection) {
    try {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val srcW = image.width
        val srcH = image.height
        val step = max(1, srcW / 160)
        val w = srcW / step
        val h = srcH / step
        val lum = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * step * rowStride
            for (x in 0 until w) lum[y * w + x] = buf.get(row + x * step * pixStride).toInt() and 0xFF
        }
        val rot = image.imageInfo.rotationDegrees
        val (rl, rw, rh) = rotate(lum, w, h, rot)
        detection.frameAspect = rw.toFloat() / rh
        val found = QuadDetector.detect(rl, rw, rh)?.quad
        val prev = detection.quad
        if (found == null) {
            detection.quad = null
            detection.stableSince = 0L
        } else {
            val moved = prev == null || prev.distance(found) > 0.03f
            detection.quad = if (prev == null) found else prev.lerp(found, 0.6f)
            if (moved) detection.stableSince = System.currentTimeMillis() else if (detection.stableSince == 0L) detection.stableSince = System.currentTimeMillis()
        }
    } catch (t: Throwable) {
        Log.w("Camera", "analysis failed", t)
    }
}

private fun rotate(src: IntArray, w: Int, h: Int, deg: Int): Triple<IntArray, Int, Int> = when ((deg % 360 + 360) % 360) {
    90 -> Triple(IntArray(w * h) { i -> val x = i % h; val y = i / h; src[(h - 1 - x) * w + y] }, h, w)
    180 -> Triple(IntArray(w * h) { i -> src[w * h - 1 - i] }, w, h)
    270 -> Triple(IntArray(w * h) { i -> val x = i % h; val y = i / h; src[x * w + (w - 1 - y)] }, h, w)
    else -> Triple(src, w, h)
}
