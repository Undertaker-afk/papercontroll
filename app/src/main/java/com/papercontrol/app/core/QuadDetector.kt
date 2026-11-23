package com.papercontrol.app.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in normalized image coordinates (0..1). */
data class Pt(val x: Float, val y: Float)

/** The four corners of a document: top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points get() = listOf(tl, tr, br, bl)

    fun lerp(o: Quad, t: Float): Quad {
        fun l(a: Pt, b: Pt) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        return Quad(l(tl, o.tl), l(tr, o.tr), l(br, o.br), l(bl, o.bl))
    }

    fun distance(o: Quad): Float = points.zip(o.points).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    /** Output size in pixels when the quad is flattened from an image of [w] x [h]. */
    fun flatSize(w: Int, h: Int): Pair<Int, Int> {
        fun d(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val width = max(d(tl, tr), d(bl, br))
        val height = max(d(tl, bl), d(tr, br))
        return width.toInt().coerceAtLeast(1) to height.toInt().coerceAtLeast(1)
    }

    companion object {
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
    }
}

/**
 * Finds a sheet of paper in a small grayscale frame. Paper is brighter than what it lies
 * on: the frame is split at the Otsu threshold, the largest bright region is taken, and its
 * extreme corners form the quad. Works well for receipts and letters on a table; returns
 * null when no clear sheet is in view.
 */
object QuadDetector {

    data class Result(val quad: Quad, val confidence: Float)

    /** [lum]: luminance 0..255 per pixel, row by row, [w] x [h]. */
    fun detect(lum: IntArray, w: Int, h: Int): Result? {
        if (w < 16 || h < 16) return null
        val t = otsu(lum)
        // Light paper on a light table cannot be separated by brightness.
        var borderBright = 0
        var border = 0
        for (x in 0 until w) { border += 2; if (lum[x] > t) borderBright++; if (lum[(h - 1) * w + x] > t) borderBright++ }
        for (y in 0 until h) { border += 2; if (lum[y * w] > t) borderBright++; if (lum[y * w + w - 1] > t) borderBright++ }
        if (borderBright > border * 0.6) return null

        val mask = BooleanArray(w * h) { lum[it] > t }
        // Close small gaps (printed text) so the sheet is one region.
        val labels = IntArray(w * h)
        var bestLabel = 0
        var bestSize = 0
        var label = 0
        val queue = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            label++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var size = 0
            while (head < tail) {
                val p = queue[head++]
                size++
                val x = p % w
                val y = p / w
                if (x > 0 && mask[p - 1] && labels[p - 1] == 0) { labels[p - 1] = label; queue[tail++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && labels[p + 1] == 0) { labels[p + 1] = label; queue[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && labels[p - w] == 0) { labels[p - w] = label; queue[tail++] = p - w }
                if (y < h - 1 && mask[p + w] && labels[p + w] == 0) { labels[p + w] = label; queue[tail++] = p + w }
            }
            if (size > bestSize) { bestSize = size; bestLabel = label }
        }
        val area = w * h
        if (bestSize < area * 0.03 || bestSize > area * 0.97) return null

        // Extreme points along the two diagonals give the corners.
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        // Fill holes inside the region by row spans, so text does not reduce the area.
        var filled = 0
        for (y in 0 until h) {
            var first = -1
            var last = -1
            for (x in 0 until w) if (labels[y * w + x] == bestLabel) { if (first < 0) first = x; last = x }
            if (first < 0) continue
            filled += last - first + 1
            for (x in intArrayOf(first, last)) {
                val s = x + y
                val d = x - y
                val p = y * w + x
                if (s < minSum) { minSum = s; tl = p }
                if (s > maxSum) { maxSum = s; br = p }
                if (d > maxDiff) { maxDiff = d; tr = p }
                if (d < minDiff) { minDiff = d; bl = p }
            }
        }
        fun pt(p: Int) = Pt((p % w + 0.5f) / w, (p / w + 0.5f) / h)
        val quad = Quad(pt(tl), pt(tr), pt(br), pt(bl))
        val qa = polygonArea(quad, w, h)
        if (qa < area * 0.03) return null
        // How rectangular the region is: its area against the quad's area.
        val fill = (filled / qa).toFloat()
        val confidence = (1f - abs(1f - fill) * 2.5f).coerceIn(0f, 1f)
        if (confidence < 0.35f) return null
        return Result(quad, confidence)
    }

    fun polygonArea(q: Quad, w: Int, h: Int): Double {
        val p = q.points
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += (a.x * w).toDouble() * (b.y * h) - (b.x * w).toDouble() * (a.y * h)
        }
        return abs(s) / 2
    }

    fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[min(255, max(0, v))]++
        val total = lum.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = i }
        }
        return threshold
    }
}
