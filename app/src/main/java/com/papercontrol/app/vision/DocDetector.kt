package com.papercontrol.app.vision

import com.papercontrol.app.core.Pt
import com.papercontrol.app.core.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/** How a page was found. */
enum class Found(val label: String) {
    /** The outline of the sheet itself was seen. */
    OUTLINE("Sheet"),
    /** A bright shape on a darker surface (receipts on a dark table). */
    BRIGHT("Bright shape"),
    /** Only the printed content was found; the box is the content plus a margin. */
    CONTENT("Content"),
}

data class Detection(val quad: Quad, val confidence: Float, val how: Found)

/**
 * Finds the page in a camera frame or photo. Three independent cues, combined:
 *
 *  1. **Outline.** The frame is cleaned of print (grayscale closing), contrast is raised
 *     locally, and Canny edges are traced at several sensitivities. Every region enclosed by
 *     edges is a candidate quadrilateral, scored by straight sides that really lie on edges,
 *     right angles, paper-like proportions and being brighter than its surroundings. This is
 *     what finds a white sheet on a white desk, where the only trace is a faint shadow line.
 *  2. **Content.** Print (thin dark or coloured structures on a lighter ground) is clustered
 *     and boxed. Outline candidates must enclose most of it; without a trustworthy outline
 *     the content box, widened by a margin, is the page.
 *  3. **Bright shape.** Otsu on the cleaned frame; for a pale receipt on a dark surface.
 *
 * Works on a small copy ([WORK_SIZE]); results are fractions of the frame.
 */
object DocDetector {
    const val WORK_SIZE = 480

    /** [rgba]: an RGBA or BGR(A) Mat of any size. Not modified. */
    /** [thorough]: more sensitivities and print-removal strengths, for the final capture. */
    fun detect(src: Mat, thorough: Boolean = true): Detection? {
        if (src.empty()) return null
        val scale = WORK_SIZE.toDouble() / max(src.cols(), src.rows())
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA) else src.copyTo(small)
        val bgr = Mat()
        when (small.channels()) {
            4 -> Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
            1 -> Imgproc.cvtColor(small, bgr, Imgproc.COLOR_GRAY2BGR)
            else -> small.copyTo(bgr)
        }
        small.release()
        return try { detectSmall(bgr, thorough) } finally { bgr.release() }
    }

    private fun detectSmall(bgr: Mat, thorough: Boolean): Detection? {
        val w = bgr.cols()
        val h = bgr.rows()
        val area = w.toDouble() * h
        val gray = Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2GRAY) }

        val content = contentBox(bgr, gray)
        val contentQuad = content?.let { rotatedRectQuad(it.rect) }

        var best: Scored? = null
        // Print removal strengths: a thin shadow line survives the weaker ones, thick print the stronger.
        val closings = if (thorough) listOf(0.02, 0.027, 0.04) else listOf(0.02, 0.027)
        val thresholds = if (thorough) listOf(5.0 to 15.0, 8.0 to 24.0, 12.0 to 36.0, 18.0 to 54.0, 28.0 to 84.0)
        else listOf(5.0 to 15.0, 8.0 to 24.0, 12.0 to 36.0)
        var firstClosed: Mat? = null
        for (frac in closings) {
            val k = max(7, (frac * max(w, h)).toInt() or 1)
            val closed = Mat().also { Imgproc.morphologyEx(gray, it, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))) }
            val eq = Mat().also { Imgproc.createCLAHE(3.0, Size(6.0, 6.0)).apply(closed, it) }
            val blur = Mat().also { Imgproc.GaussianBlur(eq, it, Size(5.0, 5.0), 0.0) }
            for ((lo, hi) in thresholds) {
                val edges = Mat().also { Imgproc.Canny(blur, it, lo, hi) }
                val thick = Mat().also { Imgproc.dilate(edges, it, Mat.ones(3, 3, CvType.CV_8U)) }
                for (q in outlineCandidates(thick, area)) {
                    val sc = score(q, edges, closed, w, h, contentQuad, Found.OUTLINE)
                    if (sc != null && (best == null || sc.score > best!!.score)) best = sc
                }
                edges.release(); thick.release()
            }
            eq.release(); blur.release()
            if (firstClosed == null) firstClosed = closed else closed.release()
        }
        firstClosed?.let { closed ->
            brightCandidate(closed, area)?.let { q ->
                val sc = score(q, null, closed, w, h, contentQuad, Found.BRIGHT)
                if (sc != null && (best == null || sc.score > best!!.score)) best = sc
            }
            closed.release()
        }
        gray.release()

        val b = best
        if (b != null && b.score >= ACCEPT) {
            return Detection(norm(b.quad, w, h), (b.score / 4.0).toFloat().coerceIn(0f, 1f), b.how)
        }
        if (content != null && contentQuad != null) {
            // The page is somewhat larger than its print.
            val grown = grow(contentQuad, 0.045, w, h)
            return Detection(norm(grown, w, h), 0.45f, Found.CONTENT)
        }
        return null
    }

    // ------------------------------------------------------------------ content

    private class Content(val rect: RotatedRect, val ink: Int)

    private fun contentBox(bgr: Mat, gray: Mat): Content? {
        val w = gray.cols()
        val h = gray.rows()
        val k = max(9, (0.03 * max(w, h)).toInt() or 1)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        // Thin dark structures on a lighter ground: text, table lines, print.
        val bh = Mat().also { Imgproc.morphologyEx(gray, it, Imgproc.MORPH_BLACKHAT, kernel) }
        // Coloured print against the paper's own colour.
        val hsv = Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2HSV) }
        val ch = ArrayList<Mat>(); Core.split(hsv, ch)
        val sat = Mat().also { Imgproc.GaussianBlur(ch[1], it, Size(5.0, 5.0), 0.0) }
        val k2 = k * 2 + 1
        val satBg = Mat().also { Imgproc.morphologyEx(sat, it, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k2.toDouble(), k2.toDouble()))) }
        val colour = Mat().also { Core.subtract(sat, satBg, it) }

        val thr = max(12.0, percentile(bh, 0.90) * 0.6)
        val m1 = Mat().also { Imgproc.threshold(bh, it, thr, 255.0, Imgproc.THRESH_BINARY) }
        val m2 = Mat().also { Imgproc.threshold(colour, it, 40.0, 255.0, Imgproc.THRESH_BINARY) }
        val mask = Mat().also { Core.bitwise_or(m1, m2, it) }
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Mat.ones(2, 2, CvType.CV_8U))
        val merge = max(7, (0.03 * max(w, h)).toInt() or 1)
        val blob = Mat().also { Imgproc.morphologyEx(mask, it, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(merge.toDouble(), merge.toDouble()))) }

        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(blob, labels, stats, cents, 8)
        var bestI = -1
        var bestScore = 0.0
        val statI = IntArray(stats.cols())
        for (i in 1 until n) {
            stats.get(i, 0, statI)
            val a = statI[Imgproc.CC_STAT_AREA]
            if (a < 0.015 * w * h) continue
            val cx = cents.get(i, 0)[0]
            val cy = cents.get(i, 1)[0]
            val compMask = Mat().also { Core.compare(labels, Scalar(i.toDouble()), it, Core.CMP_EQ) }
            val ink = Core.countNonZero(mask.let { m -> Mat().also { r -> Core.bitwise_and(m, compMask, r) } })
            compMask.release()
            val centre = 1.0 - 1.2 * hypot((cx - w / 2) / w, (cy - h / 2) / h)
            val s = ink * centre
            if (s > bestScore) { bestScore = s; bestI = i }
        }
        var result: Content? = null
        if (bestI > 0) {
            val sel = Mat().also { Core.compare(labels, Scalar(bestI.toDouble()), it, Core.CMP_EQ) }
            val pts = MatOfPoint()
            Core.findNonZero(sel, pts)
            val p2 = MatOfPoint2f(*pts.toArray().map { Point(it.x, it.y) }.toTypedArray())
            result = Content(Imgproc.minAreaRect(p2), bestScore.toInt())
            sel.release(); pts.release(); p2.release()
        }
        listOf(bh, hsv, sat, satBg, colour, m1, m2, mask, blob, labels, stats, cents).forEach { it.release() }
        ch.forEach { it.release() }
        return result
    }

    // ------------------------------------------------------------------ outlines

    /** Regions enclosed by edges, as quadrilaterals. [thick]: dilated edge map. */
    private fun outlineCandidates(thick: Mat, area: Double): List<List<Point>> {
        val nonEdge = Mat().also { Core.bitwise_not(thick, it) }
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(nonEdge, labels, stats, cents, 4)
        val out = ArrayList<List<Point>>()
        val statI = IntArray(stats.cols())
        for (i in 1 until n) {
            stats.get(i, 0, statI)
            val a = statI[Imgproc.CC_STAT_AREA]
            if (a < 0.05 * area || a > 0.97 * area) continue
            val mask = Mat().also { Core.compare(labels, Scalar(i.toDouble()), it, Core.CMP_EQ) }
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            mask.release()
            val c = contours.maxByOrNull { Imgproc.contourArea(it) } ?: continue
            val hull = convexHull(c)
            val ha = Imgproc.contourArea(hull)
            if (ha < 0.06 * area) continue
            if (Imgproc.contourArea(c) / max(ha, 1.0) < 0.8) continue
            quadFromHull(hull)?.let { out += it }
        }
        listOf(nonEdge, labels, stats, cents).forEach { it.release() }
        return out
    }

    private fun brightCandidate(closed: Mat, area: Double): List<Point>? {
        val bin = Mat()
        Imgproc.threshold(closed, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        // Nothing to separate when most of the border is bright too.
        val border = max(2, min(bin.cols(), bin.rows()) / 40)
        val inner = bin.submat(border, bin.rows() - border, border, bin.cols() - border)
        val brightShare = Core.countNonZero(bin).toDouble() / area
        val innerShare = Core.countNonZero(inner).toDouble() / (inner.rows().toDouble() * inner.cols())
        if (brightShare > 0.7 || innerShare > 0.7) { bin.release(); return null }
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        bin.release()
        val c = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(c) < 0.05 * area) return null
        return quadFromHull(convexHull(c))
    }

    private fun convexHull(c: MatOfPoint): MatOfPoint {
        val idx = MatOfInt()
        Imgproc.convexHull(c, idx)
        val pts = c.toArray()
        return MatOfPoint(*idx.toArray().map { pts[it] }.toTypedArray())
    }

    private fun quadFromHull(hull: MatOfPoint): List<Point>? {
        val h2 = MatOfPoint2f(*hull.toArray())
        val peri = Imgproc.arcLength(h2, true)
        for (eps in doubleArrayOf(0.012, 0.02, 0.03, 0.045, 0.06, 0.08)) {
            val ap = MatOfPoint2f()
            Imgproc.approxPolyDP(h2, ap, eps * peri, true)
            if (ap.rows() == 4) return order(ap.toArray().toList())
        }
        val r = Imgproc.minAreaRect(h2)
        val box = rotatedRectQuad(r)
        val ha = Imgproc.contourArea(hull)
        val ba = Imgproc.contourArea(MatOfPoint(*box.toTypedArray()))
        return if (ha / max(ba, 1.0) > 0.9) box else null
    }

    // ------------------------------------------------------------------ scoring

    private class Scored(val quad: List<Point>, val score: Double, val how: Found)

    private const val ACCEPT = 2.6

    private fun score(q: List<Point>, thin: Mat?, closed: Mat, w: Int, h: Int, content: List<Point>?, how: Found): Scored? {
        val area = polyArea(q)
        val f = area / (w.toDouble() * h)
        if (f < 0.06 || f > 0.97) return null
        val ang = angles(q)
        if (ang.min() < 55 || ang.max() > 125) return null
        val sides = List(4) { dist(q[it], q[(it + 1) % 4]) }
        val opp = (min(sides[0], sides[2]) / max(sides[0], sides[2]) + min(sides[1], sides[3]) / max(sides[1], sides[3])) / 2
        val aspect = max(sides[0], sides[1]) / max(1.0, min(sides[0], sides[1]))
        val paperLike = max(max(g(aspect, 1.414, 0.25), g(aspect, 1.29, 0.2)), if (aspect > 2.0) 0.35 else 0.0)

        var s = 0.5 * opp + 0.6 * paperLike + 0.5 * sqrt(f) - 0.4 * max(0.0, (ang.max() - 100) / 30)

        // Sides must lie on real edges.
        if (thin != null) {
            val sup = sideSupport(thin, q)
            if (sup.min() < 0.35) return null
            s += 1.2 * sup.average() + 0.8 * sup.min()
        } else {
            s += 1.5 // a thresholded region is its own evidence
        }

        // Brighter than its surroundings.
        val mask = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.fillConvexPoly(mask, MatOfPoint(*q.toTypedArray()), Scalar(255.0))
        val ring = Mat()
        val kd = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(25.0, 25.0))
        Imgproc.dilate(mask, ring, kd)
        Core.subtract(ring, mask, ring)
        val ins = Core.mean(closed, mask).`val`[0]
        val outs = if (Core.countNonZero(ring) > 0) Core.mean(closed, ring).`val`[0] else ins
        s += 0.6 * tanh((ins - outs) / 12.0)

        // The print must be inside, as a page's is.
        if (content != null) {
            val cm = Mat.zeros(h, w, CvType.CV_8U)
            Imgproc.fillConvexPoly(cm, MatOfPoint(*content.toTypedArray()), Scalar(255.0))
            val both = Mat()
            Core.bitwise_and(cm, mask, both)
            val inside = Core.countNonZero(both).toDouble() / max(1, Core.countNonZero(cm))
            val ratio = area / max(1.0, polyArea(content))
            cm.release(); both.release()
            if (inside < 0.75) { mask.release(); ring.release(); return null }
            // A sheet is somewhat larger than its print, not several times.
            if (ratio > 4.0) { mask.release(); ring.release(); return null }
            s += 0.7 * (inside - 0.75) / 0.25 + (if (ratio in 1.0..2.4) 0.4 else 0.0)
        }
        mask.release(); ring.release()
        return Scored(q, s, how)
    }

    private fun sideSupport(edges: Mat, q: List<Point>, tol: Int = 4): List<Double> {
        val inv = Mat().also { Core.bitwise_not(edges, it) }
        val dt = Mat().also { Imgproc.distanceTransform(inv, it, Imgproc.DIST_L2, 3) }
        inv.release()
        val buf = FloatArray(1)
        val res = List(4) { i ->
            val a = q[i]; val b = q[(i + 1) % 4]
            val n = max(10, max(abs(b.x - a.x), abs(b.y - a.y)).toInt() + 1)
            var ok = 0
            for (t in 0 until n) {
                val x = (a.x + (b.x - a.x) * t / (n - 1)).toInt()
                val y = (a.y + (b.y - a.y) * t / (n - 1)).toInt()
                if (x !in 0 until edges.cols() || y !in 0 until edges.rows()) { ok++; continue } // cut by the frame
                dt.get(y, x, buf)
                if (buf[0] <= tol) ok++
            }
            ok.toDouble() / n
        }
        dt.release()
        return res
    }

    // ------------------------------------------------------------------ geometry

    private fun g(x: Double, mu: Double, sigma: Double) = exp(-((x - mu) / sigma).let { it * it })
    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun polyArea(p: List<Point>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    private fun angles(q: List<Point>): List<Double> = List(4) { i ->
        val p0 = q[(i + 3) % 4]; val p1 = q[i]; val p2 = q[(i + 1) % 4]
        val v1x = p0.x - p1.x; val v1y = p0.y - p1.y; val v2x = p2.x - p1.x; val v2y = p2.y - p1.y
        val c = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-9)
        Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /** tl, tr, br, bl. */
    fun order(p: List<Point>): List<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.maxBy { it.x - it.y }
        val bl = p.minBy { it.x - it.y }
        return listOf(tl, tr, br, bl)
    }

    private fun rotatedRectQuad(r: RotatedRect): List<Point> {
        val pts = arrayOfNulls<Point>(4)
        r.points(pts)
        return order(pts.map { it!! })
    }

    /** Pushes every corner away from the centre by [frac] of the quad's size, within the frame. */
    private fun grow(q: List<Point>, frac: Double, w: Int, h: Int): List<Point> {
        val cx = q.sumOf { it.x } / 4; val cy = q.sumOf { it.y } / 4
        return q.map { p -> Point((cx + (p.x - cx) * (1 + 2 * frac)).coerceIn(0.0, w - 1.0), (cy + (p.y - cy) * (1 + 2 * frac)).coerceIn(0.0, h - 1.0)) }
    }

    private fun norm(q: List<Point>, w: Int, h: Int) =
        Quad(Pt((q[0].x / w).toFloat(), (q[0].y / h).toFloat()), Pt((q[1].x / w).toFloat(), (q[1].y / h).toFloat()), Pt((q[2].x / w).toFloat(), (q[2].y / h).toFloat()), Pt((q[3].x / w).toFloat(), (q[3].y / h).toFloat()))

    private fun percentile(m: Mat, p: Double): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(m), MatOfInt(0), Mat(), hist, MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = m.rows().toDouble() * m.cols()
        var acc = 0.0
        val v = FloatArray(1)
        for (i in 0 until 256) { hist.get(i, 0, v); acc += v[0]; if (acc / total >= p) { hist.release(); return i.toDouble() } }
        hist.release()
        return 255.0
    }
}
