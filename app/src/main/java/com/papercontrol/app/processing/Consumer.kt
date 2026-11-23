package com.papercontrol.app.processing

import android.content.Context
import android.graphics.Bitmap
import com.papercontrol.app.core.Extraction
import com.papercontrol.app.core.OcrResult
import com.papercontrol.app.core.Quad
import com.papercontrol.app.core.ReceiptParser
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.ExtractionJson
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Page
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.TextReader
import com.papercontrol.app.sync.Paperless
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One page, flattened, cleaned up, saved and read. */
data class PreparedPage(
    val file: File,
    val original: File?,
    val ocr: OcrResult,
    val extraction: Extraction,
    val tone: FloatArray,
    val capturedAt: Long,
)

/**
 * The consumption pipeline, like Paperless-ngx's consumer: flatten → clean up → read text
 * → read fields → sort → store.
 */
object Consumer {

    /** Flattens [photo] to [quad] (when given), cleans it up, saves both versions and reads the text. */
    suspend fun prepare(context: Context, photo: Bitmap, quad: Quad?, capturedAt: Long = System.currentTimeMillis()): PreparedPage =
        withContext(Dispatchers.Default) {
            val flat = flatten(photo, quad)
            finish(photo, flat, clean(flat), capturedAt)
        }

    fun flatten(photo: Bitmap, quad: Quad?): Bitmap = if (quad != null && quad != Quad.FULL) Images.warp(photo, quad) else photo

    fun clean(flat: Bitmap): Bitmap = if (Settings.current.enhance) Images.enhance(flat) else flat

    /** Saves the photo and the cleaned-up scan and reads the text. */
    suspend fun finish(photo: Bitmap, flat: Bitmap, clean: Bitmap, capturedAt: Long): PreparedPage =
        withContext(Dispatchers.Default) {
            val id = UUID.randomUUID().toString()
            val dir = Store.pagesDir()
            val original = Images.save(Images.scaleDown(photo, 1600), File(dir, "$id-photo.jpg"), 80)
            val file = Images.save(clean, File(dir, "$id.jpg"))
            val ocr = TextReader.read(clean)
            PreparedPage(file, original, ocr, ReceiptParser.parse(ocr), Images.paperTone(flat), capturedAt)
        }

    /** Fields of a multi-page document: header fields from the first page, totals from the last. */
    fun merge(pages: List<PreparedPage>): Extraction {
        if (pages.size == 1) return pages[0].extraction
        val ex = pages.map { it.extraction }
        val first = ex.firstOrNull { it.merchant != null } ?: ex.first()
        val withTotal = ex.lastOrNull { it.total != null }
        return first.copy(
            date = ex.firstNotNullOfOrNull { it.date },
            time = ex.firstNotNullOfOrNull { it.time },
            invoiceNumber = ex.firstNotNullOfOrNull { it.invoiceNumber },
            orderNumber = ex.firstNotNullOfOrNull { it.orderNumber },
            items = ex.flatMap { it.items }.distinct(),
            subtotal = withTotal?.subtotal ?: first.subtotal,
            tax = withTotal?.tax ?: first.tax,
            taxLabel = withTotal?.taxLabel ?: first.taxLabel,
            total = withTotal?.total,
            currency = ex.firstNotNullOfOrNull { it.currency },
            cardBrand = ex.firstNotNullOfOrNull { it.cardBrand },
            cardLast4 = ex.firstNotNullOfOrNull { it.cardLast4 },
            authCode = ex.firstNotNullOfOrNull { it.authCode },
            pageCount = pages.size,
        )
    }

    fun text(pages: List<PreparedPage>): String = pages.joinToString("\n\n") { it.ocr.text }

    suspend fun sort(context: Context, pages: List<PreparedPage>): Sorting = withContext(Dispatchers.Default) {
        val image = Images.decode(pages.first().file, 1024)?.let { Images.jpegBytes(it) }
        Sorter.sort(context, text(pages), merge(pages), image)
    }

    /** Stores the document and returns its id. */
    suspend fun commit(context: Context, pages: List<PreparedPage>, sorting: Sorting, title: String = sorting.title): Long = withContext(Dispatchers.IO) {
        val ex = merge(pages)
        val doc = Document(
            title = title,
            correspondentId = Sorter.resolve(LabelKind.CORRESPONDENT, sorting.correspondent),
            docTypeId = Sorter.resolve(LabelKind.DOCTYPE, sorting.docType),
            folderId = Sorter.resolve(LabelKind.FOLDER, sorting.folder),
            created = sorting.created ?: ex.date ?: java.time.LocalDate.now(),
            content = text(pages),
            fields = ExtractionJson.write(ex),
            amount = sorting.amount,
            currency = sorting.currency,
            pageCount = pages.size,
            thumbPath = pages.first().file.path,
            tagIds = sorting.tags.mapNotNull { Sorter.resolve(LabelKind.TAG, it) },
        )
        val id = Store.insertDocument(
            doc,
            pages.mapIndexed { i, p -> Page(docId = 0, index = i, path = p.file.path, originalPath = p.original?.path, text = p.ocr.text, capturedAt = p.capturedAt) },
        )
        if (Settings.current.paperlessAutoUpload && Paperless.configured()) Paperless.enqueueUpload(context, id)
        id
    }

    /** Removes the files of pages that were scanned but not kept. */
    fun discard(pages: List<PreparedPage>) {
        pages.forEach { it.file.delete(); it.original?.delete() }
    }
}
