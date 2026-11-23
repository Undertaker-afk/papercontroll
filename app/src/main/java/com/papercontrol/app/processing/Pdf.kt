package com.papercontrol.app.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Store
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object Pdf {
    /** Writes the pages of [doc] as one PDF, each page sized to its scan at A4 width. */
    fun write(doc: Document, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            for ((i, p) in Store.pages(doc.id).withIndex()) {
                val bmp = Images.decode(File(p.path), 2000) ?: continue
                val w = 595
                val h = (w * bmp.height / bmp.width.toFloat()).toInt().coerceAtLeast(1)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                com.papercontrol.app.ml.TextReader.fromJson(p.ocr)?.let { drawTextLayer(page.canvas, it, w) }
                pdf.finishPage(page)
                bmp.recycle()
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    /**
     * The recognized text, drawn invisibly over each line, so the PDF can be searched and its
     * text selected and copied in any PDF viewer (like Paperless-ngx's archive version).
     */
    private fun drawTextLayer(canvas: android.graphics.Canvas, ocr: com.papercontrol.app.core.OcrResult, pageWidth: Int) {
        val s = pageWidth / ocr.width.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(1, 0, 0, 0) }
        for (l in ocr.lines) {
            val text = l.text.trim().ifEmpty { continue }
            val bh = l.box.height * s
            val bw = l.box.width * s
            if (bh < 2f || bw < 2f) continue
            paint.textSize = bh * 0.82f
            paint.textScaleX = 1f
            val measured = paint.measureText(text).coerceAtLeast(1f)
            paint.textScaleX = (bw / measured).coerceIn(0.2f, 4f)
            canvas.drawText(text, l.box.left * s, l.box.bottom * s - bh * 0.18f, paint)
        }
    }

    /** A PDF of [doc] in the cache, for sharing. */
    fun toCache(context: Context, doc: Document): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, safeName(doc.title) + ".pdf")
        FileOutputStream(f).use { write(doc, it) }
        return f
    }

    /** Renders every page of a PDF for reading and storing. */
    fun render(context: Context, uri: Uri, maxSide: Int = 2000): List<Bitmap> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return render(pfd, maxSide)
    }

    fun render(pfd: ParcelFileDescriptor, maxSide: Int): List<Bitmap> = pfd.use {
        PdfRenderer(it).use { r ->
            (0 until minOf(r.pageCount, 60)).map { i ->
                r.openPage(i).use { page ->
                    val scale = maxSide.toFloat() / maxOf(page.width, page.height)
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    /**
     * Saves every document as a PDF into Downloads/PaperControl/<folder>/, so the folders
     * the AI sorted into also exist as real folders on the phone. Returns how many were written.
     */
    fun exportAll(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val folders = Store.labels(LabelKind.FOLDER).associate { it.id to it.name }
        var n = 0
        for (d in Store.documents(com.papercontrol.app.data.Filter(), Int.MAX_VALUE)) {
            val folder = d.folderId?.let { folders[it] } ?: "Unsorted"
            val path = folder.split('/').joinToString("/") { safeName(it) }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName(d.title) + ".pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PaperControl/" + path)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            context.contentResolver.openOutputStream(uri)?.use { write(d, it); n++ }
        }
        return n
    }

    fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "Document" }
}
