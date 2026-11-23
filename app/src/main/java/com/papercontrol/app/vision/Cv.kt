package com.papercontrol.app.vision

import android.graphics.Bitmap
import android.util.Log
import com.papercontrol.app.core.Quad
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** OpenCV on the phone: finding the page, straightening it and cleaning it up like a scanner. */
object Cv {
    private const val TAG = "Cv"
    @Volatile private var state = 0 // 0 unknown, 1 ready, -1 unavailable

    /** False when the native library cannot load on this device; callers then use the plain fallbacks. */
    fun ready(): Boolean {
        if (state == 0) {
            state = try { if (OpenCVLoader.initLocal()) 1 else -1 } catch (t: Throwable) { Log.w(TAG, "OpenCV unavailable", t); -1 }
        }
        return state == 1
    }

    private fun toMat(bmp: Bitmap): Mat = Mat().also { Utils.bitmapToMat(bmp, it) }

    private fun toBitmap(m: Mat): Bitmap = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(m, it) }

    /** Finds the page in [bmp]. [thorough] for a final photo, off for live frames. */
    fun detect(bmp: Bitmap, thorough: Boolean): Detection? {
        if (!ready()) return null
        val m = toMat(bmp)
        return try { DocDetector.detect(m, thorough) } catch (t: Throwable) { Log.w(TAG, "detect failed", t); null } finally { m.release() }
    }

    /** As [detect] on a frame given as RGBA [m], turned upright by [rotation] degrees clockwise (0, 90, 180, 270). */
    fun detectFrame(m: Mat, rotation: Int): Detection? {
        if (!ready()) return null
        val up = Mat()
        when (((rotation % 360) + 360) % 360) {
            90 -> Core.rotate(m, up, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(m, up, Core.ROTATE_180)
            270 -> Core.rotate(m, up, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> m.copyTo(up)
        }
        return try { DocDetector.detect(up, thorough = false) } catch (t: Throwable) { Log.w(TAG, "detect failed", t); null } finally { up.release() }
    }

    // ------------------------------------------------------------------ straightening

    /** Size in pixels of the flattened page for [quad] in an image of [w] x [h]. */
    fun flatSize(quad: Quad, w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val p = quad.points.map { Point(it.x * w.toDouble(), it.y * h.toDouble()) }
        val top = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val bottom = hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val left = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val right = hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        var ow = max(top, bottom)
        var oh = max(left, right)
        val s = min(1.0, maxSide / max(ow, oh))
        ow *= s; oh *= s
        return ow.roundToInt().coerceAtLeast(2) to oh.roundToInt().coerceAtLeast(2)
    }

    /** The page inside [quad] as an upright rectangle (bicubic). */
    fun warp(bmp: Bitmap, quad: Quad, maxSide: Int): Bitmap {
        if (!ready()) return com.papercontrol.app.processing.Images.warpPlain(bmp, quad, maxSide)
        val src = toMat(bmp)
        val out = warp(src, quad, maxSide)
        try { return toBitmap(out) } finally { src.release(); out.release() }
    }

    /** As above on a Mat; the result is a new Mat. */
    fun warp(src: Mat, quad: Quad, maxSide: Int): Mat {
        val (ow, oh) = flatSize(quad, src.cols(), src.rows(), maxSide)
        val out = Mat()
        val from = MatOfPoint2f(*quad.points.map { Point(it.x * src.cols().toDouble(), it.y * src.rows().toDouble()) }.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(ow - 1.0, 0.0), Point(ow - 1.0, oh - 1.0), Point(0.0, oh - 1.0))
        val h = Imgproc.getPerspectiveTransform(from, to)
        Imgproc.warpPerspective(src, out, h, Size(ow.toDouble(), oh.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        h.release(); from.release(); to.release()
        return out
    }

    // ------------------------------------------------------------------ clean-up

    /**
     * Makes a photo of a page look scanned, keeping colour: the lighting is estimated (print
     * removed by a large closing, then smoothed) and divided out, so shadows and uneven
     * light vanish and paper turns white; levels are stretched so print is dark; a light
     * unsharp mask crisps the text.
     */
    fun scanClean(bmp: Bitmap): Bitmap {
        if (!ready()) return com.papercontrol.app.processing.Images.enhancePlain(bmp)
        val src = toMat(bmp)
        try {
            return toBitmap(scanClean(src))
        } finally {
            src.release()
        }
    }

    /** [rgba]: RGBA or BGR Mat. Returns a new Mat of the same layout. */
    fun scanClean(rgba: Mat): Mat {
        val bgr = Mat()
        val alpha = rgba.channels() == 4
        if (alpha) Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR) else rgba.copyTo(bgr)

        val gray = Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2GRAY) }
        // Work at a smaller size for the lighting estimate; it is smooth anyway.
        val long = max(gray.cols(), gray.rows())
        val s = min(1.0, 360.0 / long)
        val small = Mat().also { Imgproc.resize(gray, it, Size(), s, s, Imgproc.INTER_AREA) }
        val k = max(9, (0.05 * max(small.cols(), small.rows())).toInt() or 1)
        val bg = Mat().also { Imgproc.morphologyEx(small, it, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))) }
        Imgproc.GaussianBlur(bg, bg, Size(), k / 2.0, k / 2.0)
        val bgFull = Mat().also { Imgproc.resize(bg, it, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }

        // Per-pixel gain: lift the local paper level to white.
        val bgF = Mat().also { bgFull.convertTo(it, CvType.CV_32F, 1.0 / 255.0) }
        Core.max(bgF, Scalar(0.25), bgF)
        val gain = Mat()
        Core.divide(0.97, bgF, gain)
        val bgrF = Mat().also { bgr.convertTo(it, CvType.CV_32FC3, 1.0 / 255.0) }
        val ch = ArrayList<Mat>(); Core.split(bgrF, ch)
        ch.forEach { Core.multiply(it, gain, it) }
        Core.merge(ch, bgrF)

        // Levels: black point from the darkest print, white at 1.
        val lum = Mat().also { Imgproc.cvtColor(bgrF, it, Imgproc.COLOR_BGR2GRAY) }
        val lum8 = Mat().also { lum.convertTo(it, CvType.CV_8U, 255.0) }
        val black = (percentile(lum8, 0.01) - 6).coerceIn(0.0, 90.0)
        val outF = Mat()
        val scale = 255.0 / max(60.0, 250.0 - black)
        bgrF.convertTo(outF, CvType.CV_32FC3, 255.0 * scale, -black * scale)
        val out8 = Mat().also { outF.convertTo(it, CvType.CV_8UC3) }

        // Unsharp mask.
        val blur = Mat().also { Imgproc.GaussianBlur(out8, it, Size(), 1.2) }
        val sharp = Mat().also { Core.addWeighted(out8, 1.6, blur, -0.6, 0.0, it) }

        val result = Mat()
        if (alpha) Imgproc.cvtColor(sharp, result, Imgproc.COLOR_BGR2RGBA) else sharp.copyTo(result)
        listOf(bgr, gray, small, bg, bgFull, bgF, gain, bgrF, lum, lum8, outF, out8, blur, sharp).forEach { it.release() }
        ch.forEach { it.release() }
        return result
    }

    private fun percentile(m: Mat, p: Double): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(m), org.opencv.core.MatOfInt(0), Mat(), hist, org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = m.rows().toDouble() * m.cols()
        var acc = 0.0
        val v = FloatArray(1)
        for (i in 0 until 256) { hist.get(i, 0, v); acc += v[0]; if (acc / total >= p) { hist.release(); return i.toDouble() } }
        hist.release()
        return 255.0
    }
}
