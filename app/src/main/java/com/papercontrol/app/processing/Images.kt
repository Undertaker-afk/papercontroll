package com.papercontrol.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.papercontrol.app.core.Quad
import com.papercontrol.app.core.QuadDetector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {
    /** Longest side of stored scans: sharp enough to read and print, small enough to keep many. */
    const val MAX_SIDE = 2400

    /** Longest side of a camera capture kept for finding and reading the page. */
    const val CAPTURE_SIDE = 3200

    fun decode(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        return scaleDown(rotate(bmp, rotation), maxSide)
    }

    fun decode(file: File, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        return scaleDown(rotate(bmp, rotation), maxSide)
    }

    /** When the photo was taken, from its EXIF data; 0 when unknown. */
    fun capturedAt(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val exif = ExifInterface(s)
            exif.dateTimeOriginal ?: exif.dateTime
        }
    }.getOrNull() ?: 0L

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (s >= 1f) return bmp
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /** Luminance of a small copy of [bmp], for [QuadDetector]. */
    fun luma(bmp: Bitmap, width: Int = 180): Triple<IntArray, Int, Int> {
        val w = min(width, bmp.width)
        val h = (bmp.height * w / bmp.width.toFloat()).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val lum = IntArray(w * h) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return Triple(lum, w, h)
    }

    /** Finds the page: OpenCV when available, the plain detector otherwise. */
    fun detect(bmp: Bitmap, thorough: Boolean = true): com.papercontrol.app.vision.Detection? =
        com.papercontrol.app.vision.Cv.detect(bmp, thorough) ?: if (com.papercontrol.app.vision.Cv.ready()) null
        else detectPlain(bmp)?.let { com.papercontrol.app.vision.Detection(it.quad, it.confidence, com.papercontrol.app.vision.Found.BRIGHT) }

    fun warp(src: Bitmap, quad: Quad, maxSide: Int = MAX_SIDE): Bitmap = com.papercontrol.app.vision.Cv.warp(src, quad, maxSide)

    /** Scanner-style clean-up: lighting evened out, paper white, print dark, colours kept. */
    fun enhance(src: Bitmap): Bitmap = com.papercontrol.app.vision.Cv.scanClean(src)

    /** Plain-Kotlin fallback detector, used only when OpenCV cannot load. */
    fun detectPlain(bmp: Bitmap): QuadDetector.Result? {
        val (lum, w, h) = luma(bmp)
        return QuadDetector.detect(lum, w, h)
    }

    /** Flattens the document inside [quad] into an upright rectangle. */
    fun warpPlain(src: Bitmap, quad: Quad, maxSide: Int = MAX_SIDE): Bitmap {
        val (w, h) = quad.flatSize(src.width, src.height)
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        val ow = (w * scale).roundToInt().coerceAtLeast(1)
        val oh = (h * scale).roundToInt().coerceAtLeast(1)
        val from = quad.points.flatMap { listOf(it.x * src.width, it.y * src.height) }.toFloatArray()
        val to = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(from, 0, to, 0, 4)) return src
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Average color of the paper (the brighter half of the pixels), r, g, b in 0..1. */
    fun paperTone(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, 48, max(1, 48 * bmp.height / max(1, bmp.width)), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val sorted = px.sortedBy { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) }
        val bright = sorted.subList(sorted.size / 2, sorted.size)
        val r = bright.sumOf { it shr 16 and 0xFF } / (255f * bright.size)
        val g = bright.sumOf { it shr 8 and 0xFF } / (255f * bright.size)
        val b = bright.sumOf { it and 0xFF } / (255f * bright.size)
        return floatArrayOf(r, g, b)
    }

    /**
     * Makes a photo look like a scan: the paper is lifted to white (per channel, which also
     * removes color casts) and contrast is raised so print gets crisp and dark.
     */
    fun enhancePlain(src: Bitmap): Bitmap {
        val tone = paperTone(src)
        val contrast = 1.35f
        val cm = ColorMatrix()
        val scale = ColorMatrix(floatArrayOf(
            0.97f / max(0.2f, tone[0]), 0f, 0f, 0f, 0f,
            0f, 0.97f / max(0.2f, tone[1]), 0f, 0f, 0f,
            0f, 0f, 0.97f / max(0.2f, tone[2]), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val t = (1f - contrast) * 255f
        val con = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
        cm.postConcat(scale)
        cm.postConcat(con)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** 64-bit difference hash of a page, see [com.papercontrol.app.core.Similarity.dHash]. */
    fun pageHash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        return com.papercontrol.app.core.Similarity.dHash(IntArray(72) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        })
    }

    /** Rotates an image file by [degrees] into a new file next to it (a new name, so cached thumbnails refresh). */
    fun rotateFile(path: String, degrees: Int): File? {
        val src = File(path)
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        val out = File(src.parentFile, src.nameWithoutExtension.substringBefore("~") + "~" + System.currentTimeMillis() % 100000 + ".jpg")
        save(rotate(bmp, degrees), out, 90)
        src.delete()
        return out
    }

    fun save(bmp: Bitmap, file: File, quality: Int = 88): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    fun jpegBytes(bmp: Bitmap, maxSide: Int = 1024, quality: Int = 85): ByteArray {
        val small = scaleDown(bmp, maxSide)
        return java.io.ByteArrayOutputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
    }
}
