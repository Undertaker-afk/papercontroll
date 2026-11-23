package com.papercontrol.app.ui.scan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.papercontrol.app.core.Box as PBox
import com.papercontrol.app.ui.theme.Ink
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** All animated values of the reveal, driven by [ScanFlow]. Ranges are 0..1 unless noted. */
class RevealAnim {
    val flash = Animatable(0f)
    /** The photo around the paper; fades to black. */
    val photo = Animatable(1f)
    /** Scan band position over the paper: -0.15 (above) .. 1.15 (below). */
    val band = Animatable(-0.2f)
    /** Glow strength of the band (it fades in at the top edge and out at the bottom). */
    val bandGlow = Animatable(0f)
    /** Paper → card. */
    val morph = Animatable(0f)
    /** The iridescent aura around the finished card. */
    val aura = Animatable(0f)
    val header = Animatable(0f)
    val footer = Animatable(0f)
    /** Where the card was laid out, in overlay coordinates. */
    var cardRect by mutableStateOf<Rect?>(null)
    var done by mutableStateOf(false)
}

val EaseBand = CubicBezierEasing(0.35f, 0f, 0.65f, 1f)
val EaseMorph = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

private val invert = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(
        -1.25f, 0f, 0f, 0f, 255f,
        0f, -1.25f, 0f, 0f, 255f,
        0f, 0f, -1.25f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
)

/** Grey, slightly flat look of the unscanned photo. */
private val unscanned = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(
        0.82f, 0f, 0f, 0f, 6f,
        0f, 0.82f, 0f, 0f, 6f,
        0f, 0f, 0.82f, 0f, 8f,
        0f, 0f, 0f, 1f, 0f,
    ))
)

/**
 * Everything drawn above the review layout while a capture turns into a card:
 * photo background, the paper with its scan band and inked highlights, the glows, the ring
 * that dissolves the paper into the card, and the white flash.
 */
@Composable
fun RevealOverlay(
    capture: Capture,
    anim: RevealAnim,
    /** Where the paper sits on screen at the start (overlay coordinates). */
    paperRect: Rect,
    /** Where the card sits on screen (overlay coordinates), once laid out. */
    cardRect: Rect?,
    /** The photo, as shown full screen. */
    photoRect: Rect,
) {
    val photo = remember(capture) { capture.photo.asImageBitmap() }
    val flat = remember(capture) { capture.flat.asImageBitmap() }
    val clean = remember(capture) { capture.clean.asImageBitmap() }
    val highlights = capture.highlights
    val t = rememberInfiniteTransition(label = "iris")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase")

    val m = anim.morph.value
    // The paper first stretches to the card's shape, then dissolves through the ring.
    val mm = shapeProgress(m)
    val hole = holeProgress(m)
    val rect = if (cardRect == null || m == 0f) paperRect else lerp(paperRect, cardRect, mm)

    Box(Modifier.fillMaxSize()) {
        // The photo around the paper, fading to black after the shutter.
        if (anim.photo.value > 0f) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = anim.photo.value }) {
                drawImage(photo, dstOffset = IntOffset(photoRect.left.roundToInt(), photoRect.top.roundToInt()), dstSize = IntSize(photoRect.width.roundToInt(), photoRect.height.roundToInt()))
            }
        }

        // Soft glow behind everything: the band's colour spill beside the paper, then the ring and aura.
        Canvas(
            Modifier.fillMaxSize()
                .blur(34.dp, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val glow = anim.bandGlow.value
            if (glow > 0f && m == 0f) {
                val y = paperRect.top + paperRect.height * anim.band.value
                val h = max(46.dp.toPx(), paperRect.height * 0.09f)
                drawRoundRect(
                    brush = iris(phase, paperRect.left - 30.dp.toPx(), paperRect.right + 30.dp.toPx()),
                    topLeft = Offset(paperRect.left - 26.dp.toPx(), y - h / 2),
                    size = Size(paperRect.width + 52.dp.toPx(), h),
                    cornerRadius = CornerRadius(h / 2),
                    alpha = 0.95f * glow,
                )
            }
            if (m > 0f && cardRect != null) {
                val c = rect.center
                val r = ringRadius(rect, hole)
                val ringAlpha = (sin(PI * min(1f, m * 1.15f)).toFloat()).coerceIn(0f, 1f)
                rotate(360f * phase, c) {
                    drawCircle(Brush.sweepGradient(Ink.Iris, c), radius = r, center = c, style = Stroke(width = 54.dp.toPx()), alpha = ringAlpha)
                }
            }
            val aura = anim.aura.value
            if (aura > 0f && cardRect != null) {
                // A glow around the card's edge, its colours slowly turning; the card itself stays clear.
                drawRoundRect(
                    sweep(cardRect.center, phase, AuraColors),
                    topLeft = cardRect.topLeft - Offset(10.dp.toPx(), 12.dp.toPx()),
                    size = Size(cardRect.width + 20.dp.toPx(), cardRect.height + 24.dp.toPx()),
                    cornerRadius = CornerRadius(36.dp.toPx()),
                    style = Stroke(width = 44.dp.toPx()),
                    alpha = 0.65f * aura,
                )
                if (m >= 1f) drawRoundRect(Color.Black, cardRect.topLeft, cardRect.size, CornerRadius(24.dp.toPx()), blendMode = BlendMode.DstOut)
            }
        }

        // The paper.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            if (m >= 1f) return@Canvas
            val corner = (3.dp.toPx() + (24.dp.toPx() - 3.dp.toPx()) * mm)
            val paperPath = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(corner))) }
            clipPath(paperPath) {
                val bandY = rect.top + rect.height * anim.band.value
                val bandH = max(64.dp.toPx(), rect.height * 0.12f)
                // Below the band: the photo as taken. Above: the cleaned-up scan.
                clipRect(rect.left, max(rect.top, bandY), rect.right, rect.bottom) {
                    drawImageIn(flat, rect, colorFilter = unscanned)
                }
                clipRect(rect.left, rect.top, rect.right, min(rect.bottom, bandY)) {
                    drawImageIn(clean, rect)
                    if (highlights != null) drawHighlights(clean, highlights, rect, bandY)
                }
                // Inside the band: the print smears vertically, as if pulled through a scanner.
                if (anim.bandGlow.value > 0f && m == 0f && bandY > rect.top - bandH && bandY < rect.bottom + bandH) {
                    val top = bandY - bandH / 2
                    val g = anim.bandGlow.value
                    // Drawn in its own layer so the whole band can fade out softly at its top and bottom.
                    drawIntoCanvas { it.saveLayer(Rect(rect.left, top, rect.right, top + bandH), Paint()) }
                    clipRect(rect.left, top, rect.right, top + bandH) {
                        val sy = clean.height / rect.height
                        val srcCenter = ((bandY - rect.top) * sy).roundToInt().coerceIn(0, clean.height - 1)
                        val srcH = max(2, (bandH * sy * 0.18f).roundToInt())
                        val srcTop = (srcCenter - srcH / 2).coerceIn(0, max(0, clean.height - srcH))
                        drawImage(
                            clean,
                            srcOffset = IntOffset(0, srcTop),
                            srcSize = IntSize(clean.width, min(srcH, clean.height - srcTop)),
                            dstOffset = IntOffset(rect.left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(rect.width.roundToInt(), bandH.roundToInt()),
                            alpha = g,
                        )
                        drawRect(iris(phase, rect.left, rect.right), Offset(rect.left, top), Size(rect.width, bandH), alpha = 0.6f * g, blendMode = BlendMode.Multiply)
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), top + bandH * 0.3f, top + bandH * 0.7f),
                            Offset(rect.left, top), Size(rect.width, bandH), alpha = g, blendMode = BlendMode.Plus,
                        )
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Black, 0.62f to Color.Black, 1f to Color.Transparent, startY = top, endY = top + bandH),
                            Offset(rect.left, top), Size(rect.width, bandH), blendMode = BlendMode.DstIn,
                        )
                    }
                    drawIntoCanvas { it.restore() }
                }
            }
            // The card shows through a widening hole in the paper.
            if (m > 0f && cardRect != null) {
                val r = ringRadius(rect, hole)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent,
                        center = rect.center, radius = max(1f, r),
                    ),
                    radius = max(1f, r), center = rect.center, blendMode = BlendMode.DstOut,
                )
            }
        }

        // Sharp iridescent rim on the ring.
        if (m > 0f && m < 1f && cardRect != null) {
            Canvas(Modifier.fillMaxSize().blur(6.dp, BlurredEdgeTreatment.Unbounded)) {
                val r = ringRadius(rect, hole)
                rotate(360f * phase, rect.center) {
                    drawCircle(Brush.sweepGradient(Ink.Iris, rect.center), r * 0.92f, rect.center, alpha = 0.75f * (1f - m), style = Stroke(10.dp.toPx()))
                }
            }
        }

        if (anim.flash.value > 0f) {
            Canvas(Modifier.fillMaxSize()) { drawRect(Color.White, alpha = anim.flash.value * 0.85f) }
        }
    }
}

/** The aura keeps to the cool end of the iris with magenta, as in the reference. */
private val AuraColors = listOf(Ink.Iris[0], Ink.Iris[1], Ink.Iris[2], Ink.Iris[3], Ink.Iris[1], Ink.Iris[0])

/** A sweep gradient whose colours are turned by [phase] (0..1) without turning the shape it fills. */
private fun sweep(center: Offset, phase: Float, colors: List<Color>): Brush {
    val n = colors.size
    val stops = colors.mapIndexed { i, c -> ((i.toFloat() / n + phase) % 1f) to c }.sortedBy { it.first }
    val first = stops.first()
    val last = stops.last()
    // Close the circle: the colour at 0 and 1 is the blend between the last and first stop.
    val edge = lerpColor(last.second, first.second, (1f - last.first) / (1f - last.first + first.first))
    return Brush.sweepGradient(*(listOf(0f to edge) + stops + listOf(1f to edge)).toTypedArray(), center = center)
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

internal fun shapeProgress(m: Float) = EaseMorph.transform((m * 1.8f).coerceAtMost(1f))

/** Lags behind the shape: slow start, then the hole opens quickly. */
internal fun holeProgress(m: Float): Float {
    val t = ((m - 0.12f) / 0.88f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun ringRadius(rect: Rect, t: Float): Float = hypot(rect.width, rect.height) * 0.62f * t

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
)

private fun iris(phase: Float, from: Float, to: Float): Brush {
    val w = to - from
    val shift = w * phase
    return Brush.horizontalGradient(Ink.Iris + Ink.Iris, startX = from - shift, endX = from - shift + 2 * w)
}

private fun DrawScope.drawImageIn(img: ImageBitmap, rect: Rect, colorFilter: ColorFilter? = null) {
    drawImage(
        img,
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
        colorFilter = colorFilter,
    )
}

/** The fields the parser found, inked in as black chips with white print once the band passed them. */
private fun DrawScope.drawHighlights(img: ImageBitmap, boxes: List<PBox>, rect: Rect, bandY: Float) {
    // Separate scales: while morphing, the paper is stretched to the card's shape.
    val kx = rect.width / img.width
    val ky = rect.height / img.height
    val pad = 3.dp.toPx()
    for (b in boxes) {
        // The chip in image pixels (padded), clamped to the image, then mapped to the screen.
        val l = (b.left - pad / kx).coerceIn(0f, img.width - 1f)
        val t = (b.top - pad / ky).coerceIn(0f, img.height - 1f)
        val rgt = (b.right + pad / kx).coerceIn(l + 1f, img.width.toFloat())
        val btm = (b.bottom + pad / ky).coerceIn(t + 1f, img.height.toFloat())
        val r = Rect(rect.left + l * kx, rect.top + t * ky, rect.left + rgt * kx, rect.top + btm * ky)
        val ink = ((bandY - r.bottom) / (18.dp.toPx())).coerceIn(0f, 1f)
        if (ink <= 0f) continue
        val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(5.dp.toPx()))) }
        clipPath(path) {
            drawRect(Color.Black, r.topLeft, r.size, alpha = ink)
            drawImage(
                img,
                srcOffset = IntOffset(l.roundToInt(), t.roundToInt()),
                srcSize = IntSize((rgt - l).roundToInt().coerceAtLeast(1), (btm - t).roundToInt().coerceAtLeast(1)),
                dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                alpha = ink,
                colorFilter = invert,
                blendMode = BlendMode.Screen,
            )
        }
    }
}
