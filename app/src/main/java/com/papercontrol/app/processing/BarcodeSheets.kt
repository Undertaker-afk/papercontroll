package com.papercontrol.app.processing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

/** Sheets to print: a separator sheet for splitting a stack, and archive-number sticker labels. */
object BarcodeSheets {
    private const val W = 595 // A4 in points
    private const val H = 842

    private fun canvasQr(canvas: android.graphics.Canvas, text: String, x: Float, y: Float, size: Float) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val cell = size / m.width
        val p = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        for (cy in 0 until m.height) for (cx in 0 until m.width) if (m[cx, cy]) canvas.drawRect(x + cx * cell, y + cy * cell, x + (cx + 1) * cell + 0.4f, y + (cy + 1) * cell + 0.4f, p)
    }

    /** One page: a big code that tells the importer "a new document starts after this sheet". */
    fun separator(context: Context, text: String): File {
        val f = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, "Separator-sheet.pdf") }
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 15f; textAlign = Paint.Align.CENTER }
        c.drawText("Separator sheet", W / 2f, 130f, big)
        canvasQr(c, text, (W - 340) / 2f, 190f, 340f)
        c.drawText("Put this sheet between two documents in a stack,", W / 2f, 590f, small)
        c.drawText("then import all the photos at once. A new document", W / 2f, 612f, small)
        c.drawText("starts after it; the sheet itself is left out.", W / 2f, 634f, small)
        pdf.finishPage(page)
        FileOutputStream(f).use { pdf.writeTo(it) }
        pdf.close()
        return f
    }

    /** Sticker labels "ASN00042", 3 x 9 to a page, for numbering paper originals. */
    fun labels(context: Context, prefix: String, start: Int, count: Int): File {
        val f = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, "ASN-labels-$start.pdf") }
        val pdf = PdfDocument()
        val cols = 3; val rows = 9
        val lw = W / cols.toFloat(); val lh = H / rows.toFloat()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f; typeface = Typeface.MONOSPACE }
        var n = 0
        var pageNo = 1
        while (n < count) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo++).create())
            page.canvas.drawColor(Color.WHITE)
            loop@ for (r in 0 until rows) for (col in 0 until cols) {
                if (n >= count) break@loop
                val label = prefix + (start + n).toString().padStart(5, '0')
                val x = col * lw; val y = r * lh
                canvasQr(page.canvas, label, x + 14f, y + (lh - 52f) / 2, 52f)
                page.canvas.drawText(label, x + 76f, y + lh / 2 + 5f, text)
                n++
            }
            pdf.finishPage(page)
        }
        FileOutputStream(f).use { pdf.writeTo(it) }
        pdf.close()
        return f
    }
}
