package com.papercontrol.app.processing

import android.content.Context
import android.graphics.Bitmap
import com.papercontrol.app.core.Barcodes
import com.papercontrol.app.core.Deadlines
import com.papercontrol.app.core.Extraction
import com.papercontrol.app.core.OcrResult
import com.papercontrol.app.core.PageCodes
import com.papercontrol.app.core.Quad
import com.papercontrol.app.core.ReceiptParser
import com.papercontrol.app.core.Similarity
import com.papercontrol.app.core.Templates
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.ExtractionJson
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Page
import com.papercontrol.app.data.Reminder
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.BarcodeReader
import com.papercontrol.app.ml.TextReader
import com.papercontrol.app.sync.Paperless
import com.papercontrol.app.work.IndexWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** One page, flattened, cleaned up, saved and read. */
data class PreparedPage(
    val file: File,
    val original: File?,
    val ocr: OcrResult,
    val extraction: Extraction,
    val tone: FloatArray,
    val capturedAt: Long,
    /** ASN sticker, separator sheet, payment QR code. */
    val codes: PageCodes = PageCodes(),
    /** Difference hash of the page, for duplicate detection. */
    val hash: Long? = null,
)

/** A document already in the archive that a new one looks like. */
data class Duplicate(val docId: Long, val title: String, val reason: String)

/**
 * The consumption pipeline, like Paperless-ngx's consumer: flatten → clean up → read text
 * and barcodes → read fields → sort → apply templates → store → reminders, search index,
 * upload.
 */
object Consumer {

    /** A page ready to show: where it was in the photo and how it ended up. */
    class Processed(
        val page: PreparedPage,
        /** The straightened page as photographed, turned upright. */
        val flat: Bitmap,
        /** The scanner-style version, turned upright. */
        val clean: Bitmap,
        /** Where the page is in the photo (normalized). */
        val quad: Quad,
        /** Clockwise quarter turns applied to the straightened page to make its text upright. */
        val turns: Int,
        val how: com.papercontrol.app.vision.Found?,
    )

    /** [photo] → find the page (unless [quad] is given) → straighten → clean → read → turn upright. */
    suspend fun process(photo: Bitmap, quad: Quad? = null, how: com.papercontrol.app.vision.Found? = null, capturedAt: Long = System.currentTimeMillis()): Processed =
        withContext(Dispatchers.Default) {
            val cfg = Settings.current
            var q = quad
            var found = how
            if (q == null) {
                val d = Images.detect(photo, thorough = true)
                q = d?.quad ?: Quad.FULL
                found = d?.how
            }
            var flat = Images.warp(photo, q, Images.MAX_SIDE)
            var clean = if (cfg.enhance) Images.enhance(flat) else flat
            var ocr = TextReader.read(clean)

            // Text that runs sideways or upside down: turn the page and read again.
            var turns = ocr.uprightTurns()
            if (turns == 0 && ocr.chars < 24) {
                val r = Images.rotate(clean, 180)
                val o = TextReader.read(r)
                if (o.chars > ocr.chars * 1.5 + 8) {
                    flat = Images.rotate(flat, 180); clean = r; ocr = o; turns = 2
                }
            } else if (turns != 0) {
                val f2 = Images.rotate(flat, turns * 90)
                val c2 = Images.rotate(clean, turns * 90)
                val o2 = TextReader.read(c2)
                if (o2.chars >= ocr.chars * 0.8) { flat = f2; clean = c2; ocr = o2 } else turns = 0
            }

            val id = UUID.randomUUID().toString()
            val dir = Store.pagesDir()
            val original = Images.save(Images.scaleDown(photo, 1600), File(dir, "$id-photo.jpg"), 80)
            val file = Images.save(clean, File(dir, "$id.jpg"))
            // Barcodes read best on the straightened photo before contrast was raised.
            val codes = Barcodes.interpret(BarcodeReader.read(flat), cfg.asnPrefix, cfg.separatorBarcode)
            val ex = ReceiptParser.parse(ocr).let { e -> if (codes.payment != null) e.copy(payment = codes.payment, total = e.total ?: codes.payment.amount) else e }
            val page = PreparedPage(file, original, ocr, ex, Images.paperTone(flat), capturedAt, codes, Images.pageHash(clean))
            Processed(page, flat, clean, q, turns, found)
        }

    /** [process] without the pictures, for batch imports. */
    suspend fun prepare(context: Context, photo: Bitmap, quad: Quad?, capturedAt: Long = System.currentTimeMillis()): PreparedPage =
        process(photo, quad, null, capturedAt).page

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
            payment = ex.firstNotNullOfOrNull { it.payment },
            pageCount = pages.size,
        )
    }

    fun text(pages: List<PreparedPage>): String = pages.joinToString("\n\n") { it.ocr.text }

    suspend fun sort(context: Context, pages: List<PreparedPage>): Sorting = withContext(Dispatchers.Default) {
        val image = Images.decode(pages.first().file, 1024)?.let { Images.jpegBytes(it) }
        Sorter.sort(context, text(pages), merge(pages), image)
    }

    /** Documents in the archive these pages look like a copy of. */
    suspend fun duplicates(pages: List<PreparedPage>, sorting: Sorting?): List<Duplicate> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext emptyList()
        val ex = merge(pages)
        val me = Similarity.Fingerprint(0, pages.first().hash, sorting?.amount ?: ex.total, sorting?.created ?: ex.date, sorting?.correspondent?.name ?: ex.merchant, text(pages).take(4000))
        val titles = HashMap<Long, String>()
        Store.fingerprints().mapNotNull { f ->
            Similarity.duplicateReason(me, f)?.let { r -> Duplicate(f.id, titles.getOrPut(f.id) { Store.document(f.id)?.title ?: "" }, r) }
        }.take(3)
    }

    /** Stores the document and returns its id. */
    suspend fun commit(context: Context, pages: List<PreparedPage>, sorting: Sorting, title: String = sorting.title): Long = withContext(Dispatchers.IO) {
        val cfg = Settings.current
        val ex = merge(pages)
        val created = sorting.created ?: ex.date ?: LocalDate.now()
        val correspondentId = Sorter.resolve(LabelKind.CORRESPONDENT, sorting.correspondent)
        val docTypeId = Sorter.resolve(LabelKind.DOCTYPE, sorting.docType)
        val tagIds = sorting.tags.mapNotNull { Sorter.resolve(LabelKind.TAG, it) }
        val asn = pages.firstNotNullOfOrNull { it.codes.asn }
        val labels = Store.allLabels()
        val values = Templates.Values(
            title = title,
            correspondent = correspondentId?.let { labels[it]?.name } ?: sorting.correspondent?.name,
            documentType = docTypeId?.let { labels[it]?.name } ?: sorting.docType?.name,
            folder = sorting.folder?.name,
            tags = tagIds.mapNotNull { labels[it] }.filter { !it.isInbox }.map { it.name },
            created = created,
            added = LocalDate.now(),
            asn = asn,
            amount = sorting.amount,
            currency = sorting.currency,
        )
        val finalTitle = cfg.titleTemplate.takeIf { it.isNotBlank() }?.let { Templates.apply(it, values) }?.ifBlank { null } ?: title
        val folderId = cfg.folderTemplate.takeIf { it.isNotBlank() }?.let { Templates.apply(it, values) }?.ifBlank { null }
            ?.let { Store.labelNamed(LabelKind.FOLDER, it) }
            ?: Sorter.resolve(LabelKind.FOLDER, sorting.folder)
        val dup = duplicates(pages, sorting).firstOrNull()

        val doc = Document(
            title = finalTitle,
            correspondentId = correspondentId,
            docTypeId = docTypeId,
            folderId = folderId,
            created = created,
            asn = asn,
            content = text(pages),
            fields = ExtractionJson.write(ex),
            amount = sorting.amount ?: ex.payment?.amount,
            currency = sorting.currency ?: ex.payment?.currency,
            pageCount = pages.size,
            thumbPath = pages.first().file.path,
            tagIds = tagIds,
            pageHash = pages.first().hash,
            duplicateOf = dup?.docId,
        )
        val id = Store.insertDocument(
            doc,
            pages.mapIndexed { i, p ->
                Page(docId = 0, index = i, path = p.file.path, originalPath = p.original?.path, text = p.ocr.text, capturedAt = p.capturedAt, ocr = TextReader.toJson(p.ocr))
            },
        )
        // Deadlines become reminders.
        for (d in Deadlines.find(doc.content, created, ex.currency == "USD")) {
            Store.saveReminder(Reminder(docId = id, date = d.date, kind = d.kind, note = d.note))
        }
        if (cfg.paperlessAutoUpload && Paperless.configured()) Paperless.enqueueUpload(context, id)
        if (cfg.semanticSearch) IndexWorker.start(context)
        id
    }

    /** Turns a stored page by [degrees] clockwise, reads it again and updates the document. */
    suspend fun rotatePage(page: Page, degrees: Int) = withContext(Dispatchers.IO) {
        val rotated = Images.rotateFile(page.path, degrees) ?: return@withContext
        val bmp = Images.decode(rotated, Images.MAX_SIDE) ?: return@withContext
        val ocr = TextReader.read(bmp)
        Store.replacePage(page, rotated.path, ocr.text, TextReader.toJson(ocr))
    }

    /** Removes the files of pages that were scanned but not kept. */
    fun discard(pages: List<PreparedPage>) {
        pages.forEach { it.file.delete(); it.original?.delete() }
    }
}
