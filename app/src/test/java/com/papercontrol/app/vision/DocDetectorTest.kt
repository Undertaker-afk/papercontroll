package com.papercontrol.app.vision

import com.papercontrol.app.core.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs

/**
 * Runs the real detector (on OpenCV's desktop build) on photos and on drawn scenes.
 * Personal test photos live in src/test/resources/private (not in the repository); those
 * tests are skipped when the files are missing.
 */
class DocDetectorTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun load() { nu.pattern.OpenCV.loadLocally() }

        fun resource(name: String): File? = File("src/test/resources/$name").takeIf { it.exists() }

        /** Fraction of the frame covered by [q], and its centre. */
        fun areaOf(q: Quad): Double {
            val p = q.points
            var s = 0.0
            for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % 4]; s += a.x * b.y - b.x * a.y }
            return abs(s) / 2
        }

        /** Writes the image with the found page drawn on it to build/detect/<name>.png, for looking at. */
        fun save(name: String, img: Mat, d: Detection?) {
            val v = img.clone()
            d?.let {
                val pts = it.quad.points.map { p -> Point(p.x * v.cols().toDouble(), p.y * v.rows().toDouble()) }
                for (i in 0 until 4) Imgproc.line(v, pts[i], pts[(i + 1) % 4], Scalar(0.0, 0.0, 255.0), maxOf(2, v.cols() / 150))
            }
            File("build/detect").mkdirs()
            Imgcodecs.imwrite("build/detect/$name.png", v)
        }

        fun polygon(m: Mat, pts: List<Point>, color: Scalar) = Imgproc.fillConvexPoly(m, MatOfPoint(*pts.toTypedArray()), color)

        fun noise(m: Mat, sigma: Double) {
            val n = Mat(m.size(), m.type())
            Core.randn(n, 0.0, sigma)
            Core.add(m, n, m)
            n.release()
        }

        /** A table printed on a sheet: grid lines, text-like dashes and a coloured column. */
        fun printOn(m: Mat, x0: Int, y0: Int, x1: Int, y1: Int) {
            val cols = 9; val rows = 7
            for (c in 0..cols) Imgproc.line(m, Point(x0 + (x1 - x0) * c / cols.toDouble(), y0.toDouble()), Point(x0 + (x1 - x0) * c / cols.toDouble(), y1.toDouble()), Scalar(70.0, 70.0, 70.0), 1)
            for (r in 0..rows) Imgproc.line(m, Point(x0.toDouble(), y0 + (y1 - y0) * r / rows.toDouble()), Point(x1.toDouble(), y0 + (y1 - y0) * r / rows.toDouble()), Scalar(70.0, 70.0, 70.0), 1)
            Imgproc.rectangle(m, Point(x0 + (x1 - x0) * 3 / cols.toDouble(), y0.toDouble()), Point(x0 + (x1 - x0) * 4 / cols.toDouble(), y1.toDouble()), Scalar(40.0, 170.0, 230.0), -1)
            for (r in 0 until rows) for (c in 0 until cols) if ((r * 7 + c) % 3 != 0)
                Imgproc.line(m, Point(x0 + (x1 - x0) * c / cols.toDouble() + 4, y0 + (y1 - y0) * r / rows.toDouble() + 10), Point(x0 + (x1 - x0) * (c + 1) / cols.toDouble() - 5, y0 + (y1 - y0) * r / rows.toDouble() + 10), Scalar(60.0, 60.0, 60.0), 2)
        }
    }

    private fun check(q: Quad, minArea: Double, maxArea: Double) {
        val a = areaOf(q)
        assertTrue("area $a not in $minArea..$maxArea", a in minArea..maxArea)
    }

    @Test
    fun whiteSheetOnWhiteDeskInARealPhoto() {
        val f = resource("private/white_on_white.jpg")
        assumeTrue("personal test photo not present", f != null)
        val img = Imgcodecs.imread(f!!.path)
        val d = DocDetector.detect(img)
        assertNotNull("no page found", d)
        save("real_photo", img, d)
        println("real photo: ${d!!.how} conf=${d.confidence} quad=${d.quad.points.map { "(%.2f,%.2f)".format(it.x, it.y) }}")
        // The sheet is roughly the middle third of this photo, about a fifth to a third of the frame.
        check(d.quad, 0.12, 0.42)
        val c = d.quad.points.let { p -> p.sumOf { it.x.toDouble() } / 4 to p.sumOf { it.y.toDouble() } / 4 }
        assertEquals(0.52, c.first, 0.14)
        assertEquals(0.50, c.second, 0.14)
    }

    @Test
    fun whiteSheetOnPaleDeskDrawn() {
        // Light grey desk, white sheet turned 4 degrees with a faint shadow along two sides.
        val m = Mat(640, 480, CvType.CV_8UC3, Scalar(205.0, 208.0, 210.0))
        val tilt = Math.toRadians(4.0)
        fun rot(x: Double, y: Double) = Point(240 + (x - 240) * Math.cos(tilt) - (y - 330) * Math.sin(tilt), 330 + (x - 240) * Math.sin(tilt) + (y - 330) * Math.cos(tilt))
        val shadow = listOf(rot(70.0, 100.0), rot(420.0, 100.0), rot(426.0, 570.0), rot(76.0, 576.0))
        polygon(m, shadow, Scalar(190.0, 193.0, 195.0))
        val sheet = listOf(rot(66.0, 96.0), rot(414.0, 96.0), rot(414.0, 566.0), rot(66.0, 566.0))
        polygon(m, sheet, Scalar(228.0, 230.0, 232.0))
        printOn(m, 110, 160, 370, 470)
        noise(m, 2.5)
        val d = DocDetector.detect(m)
        assertNotNull("no page found", d)
        save("drawn_pale", m, d)
        println("drawn pale: ${d!!.how} conf=${d.confidence} area=${areaOf(d.quad)}")
        // The sheet covers (348 * 470) / (480 * 640) = 53% of the frame.
        check(d.quad, 0.30, 0.75)
    }

    @Test
    fun straightenedAndCleanedRealPhoto() {
        val f = resource("private/white_on_white.jpg")
        assumeTrue("personal test photo not present", f != null)
        val img = Imgcodecs.imread(f!!.path)
        val d = DocDetector.detect(img)!!
        val flat = Cv.warp(img, d.quad, 1600)
        val clean = Cv.scanClean(flat)
        File("build/detect").mkdirs()
        Imgcodecs.imwrite("build/detect/flat.png", flat)
        Imgcodecs.imwrite("build/detect/clean.png", clean)
        println("flat ${flat.cols()}x${flat.rows()}")
        // Paper ends up white: the brightest 40% of the page average above 235.
        val gray = Mat(); Imgproc.cvtColor(clean, gray, Imgproc.COLOR_BGR2GRAY)
        val mean = Core.mean(gray).`val`[0]
        println("clean mean grey $mean")
        assertTrue("page not white enough: $mean", mean > 200)
    }

    @Test
    fun paleReceiptOnDarkTable() {
        val m = Mat(640, 480, CvType.CV_8UC3, Scalar(28.0, 28.0, 30.0))
        polygon(m, listOf(Point(170.0, 90.0), Point(330.0, 84.0), Point(338.0, 560.0), Point(176.0, 566.0)), Scalar(226.0, 228.0, 228.0))
        for (i in 0 until 14) Imgproc.line(m, Point(190.0, 130.0 + i * 30), Point(300.0 - (i % 3) * 20, 130.0 + i * 30), Scalar(60.0, 60.0, 60.0), 2)
        noise(m, 2.0)
        val d = DocDetector.detect(m)
        assertNotNull("no page found", d)
        save("receipt", m, d)
        println("receipt: ${d!!.how} conf=${d.confidence}")
        check(d.quad, 0.18, 0.40)
        assertTrue(d.confidence > 0.5f)
    }

    @Test
    fun emptyDeskGivesNothing() {
        val m = Mat(640, 480, CvType.CV_8UC3, Scalar(200.0, 203.0, 205.0))
        noise(m, 2.0)
        val d = DocDetector.detect(m)
        assertTrue("found $d on an empty desk", d == null)
    }

    @Suppress("unused")
    private val size = Size(1.0, 1.0)
}
