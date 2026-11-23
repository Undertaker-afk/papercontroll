package com.papercontrol.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.papercontrol.app.core.MatchAlgorithm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

/**
 * The local document archive: SQLite with a full-text index over titles and recognized text.
 * Every write bumps [version], which screens observe to reload.
 */
object Store {
    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private lateinit var appContext: Context

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(context: Context) {
        appContext = context.applicationContext
        helper = Helper(appContext)
    }

    private fun changed() { _version.value++ }

    fun pagesDir(): File = File(appContext.filesDir, "pages").apply { mkdirs() }

    // ---------------------------------------------------------------- labels

    fun labels(kind: LabelKind): List<Label> {
        val countSql = when (kind) {
            LabelKind.TAG -> "(SELECT COUNT(*) FROM doc_tags dt WHERE dt.tag_id = l.id)"
            LabelKind.CORRESPONDENT -> "(SELECT COUNT(*) FROM documents d WHERE d.correspondent_id = l.id)"
            LabelKind.DOCTYPE -> "(SELECT COUNT(*) FROM documents d WHERE d.doctype_id = l.id)"
            LabelKind.FOLDER -> "(SELECT COUNT(*) FROM documents d WHERE d.folder_id = l.id)"
        }
        return db.rawQuery(
            "SELECT l.id, l.kind, l.name, l.color, l.match, l.algorithm, l.ci, l.inbox, $countSql FROM labels l WHERE l.kind = ? ORDER BY l.name COLLATE NOCASE",
            arrayOf(kind.name),
        ).use { c -> c.map { label(it) } }
    }

    fun allLabels(): Map<Long, Label> =
        db.rawQuery("SELECT id, kind, name, color, match, algorithm, ci, inbox, 0 FROM labels", null).use { c -> c.map { label(it) } }.associateBy { it.id }

    private fun label(c: Cursor) = Label(
        id = c.getLong(0),
        kind = LabelKind.valueOf(c.getString(1)),
        name = c.getString(2),
        color = c.getInt(3),
        match = c.getString(4),
        algorithm = runCatching { MatchAlgorithm.valueOf(c.getString(5)) }.getOrDefault(MatchAlgorithm.NONE),
        caseInsensitive = c.getInt(6) != 0,
        isInbox = c.getInt(7) != 0,
        documentCount = c.getInt(8),
    )

    fun saveLabel(l: Label): Long {
        val v = ContentValues().apply {
            put("kind", l.kind.name); put("name", l.name.trim()); put("color", l.color); put("match", l.match)
            put("algorithm", l.algorithm.name); put("ci", if (l.caseInsensitive) 1 else 0); put("inbox", if (l.isInbox) 1 else 0)
        }
        val id = if (l.id == 0L) db.insert("labels", null, v) else { db.update("labels", v, "id = ?", arrayOf(l.id.toString())); l.id }
        changed()
        return id
    }

    /** The label of [kind] called [name], created with Auto matching when missing. */
    fun labelNamed(kind: LabelKind, name: String, color: Int? = null): Long {
        val clean = name.trim()
        db.rawQuery("SELECT id FROM labels WHERE kind = ? AND name = ? COLLATE NOCASE", arrayOf(kind.name, clean)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return saveLabel(Label(kind = kind, name = clean, color = color ?: Palette.forName(clean), algorithm = MatchAlgorithm.AUTO))
    }

    fun deleteLabel(l: Label) {
        db.transaction {
            when (l.kind) {
                LabelKind.TAG -> delete("doc_tags", "tag_id = ?", arrayOf(l.id.toString()))
                LabelKind.CORRESPONDENT -> execSQL("UPDATE documents SET correspondent_id = NULL WHERE correspondent_id = ?", arrayOf(l.id))
                LabelKind.DOCTYPE -> execSQL("UPDATE documents SET doctype_id = NULL WHERE doctype_id = ?", arrayOf(l.id))
                LabelKind.FOLDER -> execSQL("UPDATE documents SET folder_id = NULL WHERE folder_id = ?", arrayOf(l.id))
            }
            delete("labels", "id = ?", arrayOf(l.id.toString()))
        }
        changed()
    }

    fun inboxTagIds(): List<Long> = db.rawQuery("SELECT id FROM labels WHERE kind = 'TAG' AND inbox = 1", null).use { c -> c.map { it.getLong(0) } }

    // ---------------------------------------------------------------- documents

    private const val DOC_COLS = "d.id, d.title, d.correspondent_id, d.doctype_id, d.folder_id, d.created, d.added_at, d.modified_at, d.asn, d.content, d.fields, d.amount, d.currency, d.page_count, d.thumb, d.remote_task, (SELECT GROUP_CONCAT(tag_id) FROM doc_tags WHERE doc_id = d.id)"

    private fun doc(c: Cursor) = Document(
        id = c.getLong(0),
        title = c.getString(1),
        correspondentId = c.longOrNull(2),
        docTypeId = c.longOrNull(3),
        folderId = c.longOrNull(4),
        created = c.getString(5)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        addedAt = c.getLong(6),
        modifiedAt = c.getLong(7),
        asn = if (c.isNull(8)) null else c.getInt(8),
        content = c.getString(9) ?: "",
        fields = c.getString(10) ?: "",
        amount = if (c.isNull(11)) null else c.getDouble(11),
        currency = c.getString(12),
        pageCount = c.getInt(13),
        thumbPath = c.getString(14),
        remoteTask = c.getString(15),
        tagIds = c.getString(16)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty(),
    )

    fun document(id: Long): Document? =
        db.rawQuery("SELECT $DOC_COLS FROM documents d WHERE d.id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) doc(c) else null }

    fun documents(f: Filter, limit: Int = 500): List<Document> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        val q = QueryParser.parse(f.query, this)
        val tagIds = f.tagIds + q.tagIds
        for (t in tagIds) { where += "EXISTS (SELECT 1 FROM doc_tags WHERE doc_id = d.id AND tag_id = ?)"; args += t.toString() }
        (f.correspondentId ?: q.correspondentId)?.let { where += "d.correspondent_id = ?"; args += it.toString() }
        (f.docTypeId ?: q.docTypeId)?.let { where += "d.doctype_id = ?"; args += it.toString() }
        (f.folderId ?: q.folderId)?.let { where += "d.folder_id = ?"; args += it.toString() }
        q.asn?.let { where += "d.asn = ?"; args += it.toString() }
        q.year?.let { where += "substr(d.created, 1, 4) = ?"; args += it.toString() }
        if (f.inboxOnly) where += "EXISTS (SELECT 1 FROM doc_tags dt JOIN labels l ON l.id = dt.tag_id WHERE dt.doc_id = d.id AND l.inbox = 1)"
        f.from?.let { where += "d.created >= ?"; args += it.toString() }
        f.to?.let { where += "d.created <= ?"; args += it.toString() }
        if (q.text.isNotBlank()) {
            val match = q.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                .joinToString(" ") { w -> "\"" + w.replace("\"", "") + "\"*" }
            where += "d.id IN (SELECT docid FROM docs_fts WHERE docs_fts MATCH ?)"
            args += match
        }
        val order = when (f.sort) {
            SortBy.ADDED -> "d.added_at"
            SortBy.CREATED -> "COALESCE(d.created, '0000')"
            SortBy.TITLE -> "d.title COLLATE NOCASE"
            SortBy.AMOUNT -> "COALESCE(d.amount, 0)"
        } + if (f.descending) " DESC" else " ASC"
        val sql = "SELECT $DOC_COLS FROM documents d" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY $order, d.id DESC LIMIT $limit"
        return try {
            db.rawQuery(sql, args.toTypedArray()).use { c -> c.map { doc(it) } }
        } catch (e: Exception) {
            emptyList() // an incomplete search expression while typing
        }
    }

    fun count(inboxOnly: Boolean = false): Int = documents(Filter(inboxOnly = inboxOnly), Int.MAX_VALUE).size

    /** Content of every document for training the Auto classifier, with its current sorting. */
    fun trainingSet(): List<Document> =
        db.rawQuery("SELECT $DOC_COLS FROM documents d ORDER BY d.id DESC LIMIT 3000", null).use { c -> c.map { doc(it) } }

    fun insertDocument(d: Document, pages: List<Page>): Long {
        var id = 0L
        db.transaction {
            id = insert("documents", null, docValues(d))
            pages.forEachIndexed { i, p -> insert("pages", null, pageValues(p.copy(docId = id, index = i))) }
            writeTags(this, id, d.tagIds)
            writeFts(this, id, d)
        }
        changed()
        return id
    }

    fun updateDocument(d: Document) {
        db.transaction {
            update("documents", docValues(d.copy(modifiedAt = System.currentTimeMillis())), "id = ?", arrayOf(d.id.toString()))
            writeTags(this, d.id, d.tagIds)
            writeFts(this, d.id, d)
        }
        changed()
    }

    fun deleteDocument(id: Long) {
        pages(id).forEach { p -> File(p.path).delete(); p.originalPath?.let { File(it).delete() } }
        document(id)?.thumbPath?.let { File(it).delete() }
        db.transaction {
            delete("pages", "doc_id = ?", arrayOf(id.toString()))
            delete("doc_tags", "doc_id = ?", arrayOf(id.toString()))
            delete("field_values", "doc_id = ?", arrayOf(id.toString()))
            delete("notes", "doc_id = ?", arrayOf(id.toString()))
            delete("docs_fts", "docid = ?", arrayOf(id.toString()))
            delete("documents", "id = ?", arrayOf(id.toString()))
        }
        changed()
    }

    /** Moves the pages of [otherIds] behind the pages of [targetId] and removes the others. */
    fun mergeDocuments(targetId: Long, otherIds: List<Long>) {
        val target = document(targetId) ?: return
        var idx = pages(targetId).size
        val texts = StringBuilder(target.content)
        db.transaction {
            for (o in otherIds) {
                for (p in pages(o)) {
                    execSQL("UPDATE pages SET doc_id = ?, idx = ? WHERE id = ?", arrayOf<Any>(targetId, idx++, p.id))
                    texts.append("\n\n").append(p.text)
                }
                delete("doc_tags", "doc_id = ?", arrayOf(o.toString()))
                delete("field_values", "doc_id = ?", arrayOf(o.toString()))
                execSQL("UPDATE notes SET doc_id = ? WHERE doc_id = ?", arrayOf(targetId, o))
                delete("docs_fts", "docid = ?", arrayOf(o.toString()))
                delete("documents", "id = ?", arrayOf(o.toString()))
            }
        }
        updateDocument(target.copy(pageCount = idx, content = texts.toString()))
    }

    private fun docValues(d: Document) = ContentValues().apply {
        put("title", d.title); put("correspondent_id", d.correspondentId); put("doctype_id", d.docTypeId); put("folder_id", d.folderId)
        put("created", d.created?.toString()); put("added_at", d.addedAt); put("modified_at", d.modifiedAt); put("asn", d.asn)
        put("content", d.content); put("fields", d.fields); put("amount", d.amount); put("currency", d.currency)
        put("page_count", d.pageCount); put("thumb", d.thumbPath); put("remote_task", d.remoteTask)
    }

    private fun writeTags(w: SQLiteDatabase, id: Long, tags: List<Long>) {
        w.delete("doc_tags", "doc_id = ?", arrayOf(id.toString()))
        for (t in tags.distinct()) w.insert("doc_tags", null, ContentValues().apply { put("doc_id", id); put("tag_id", t) })
    }

    private fun writeFts(w: SQLiteDatabase, id: Long, d: Document) {
        w.delete("docs_fts", "docid = ?", arrayOf(id.toString()))
        w.insert("docs_fts", null, ContentValues().apply { put("docid", id); put("title", d.title); put("content", d.content) })
    }

    fun nextAsn(): Int = db.rawQuery("SELECT COALESCE(MAX(asn), 0) + 1 FROM documents", null).use { c -> c.moveToFirst(); c.getInt(0) }

    // ---------------------------------------------------------------- pages

    fun pages(docId: Long): List<Page> =
        db.rawQuery("SELECT id, doc_id, idx, path, original, text, captured_at FROM pages WHERE doc_id = ? ORDER BY idx", arrayOf(docId.toString())).use { c ->
            c.map { Page(it.getLong(0), it.getLong(1), it.getInt(2), it.getString(3), it.getString(4), it.getString(5) ?: "", it.getLong(6)) }
        }

    fun reorderPages(docId: Long, pageIds: List<Long>) {
        db.transaction { pageIds.forEachIndexed { i, id -> execSQL("UPDATE pages SET idx = ? WHERE id = ? AND doc_id = ?", arrayOf<Any>(i, id, docId)) } }
        changed()
    }

    /** Moves one page out into a document of its own and returns the new document's id. */
    fun splitPage(page: Page): Long? {
        val src = document(page.docId) ?: return null
        val rest = pages(page.docId).filter { it.id != page.id }
        if (rest.isEmpty()) return null
        var newId = 0L
        db.transaction {
            newId = insert("documents", null, docValues(src.copy(id = 0, title = src.title + " (page)", content = page.text, pageCount = 1, thumbPath = page.path, asn = null, remoteTask = null)))
            execSQL("UPDATE pages SET doc_id = ?, idx = 0 WHERE id = ?", arrayOf(newId, page.id))
            rest.forEachIndexed { i, p -> execSQL("UPDATE pages SET idx = ? WHERE id = ?", arrayOf<Any>(i, p.id)) }
            writeTags(this, newId, src.tagIds)
        }
        updateDocument(src.copy(pageCount = rest.size, content = rest.joinToString("\n\n") { it.text }, thumbPath = rest.first().path))
        document(newId)?.let { updateDocument(it) }
        return newId
    }

    private fun pageValues(p: Page) = ContentValues().apply {
        put("doc_id", p.docId); put("idx", p.index); put("path", p.path); put("original", p.originalPath); put("text", p.text); put("captured_at", p.capturedAt)
    }

    // ---------------------------------------------------------------- custom fields, notes, views

    fun customFields(): List<CustomField> =
        db.rawQuery("SELECT id, name, type FROM custom_fields ORDER BY name COLLATE NOCASE", null).use { c ->
            c.map { CustomField(it.getLong(0), it.getString(1), runCatching { FieldType.valueOf(it.getString(2)) }.getOrDefault(FieldType.TEXT)) }
        }

    fun saveCustomField(f: CustomField): Long {
        val v = ContentValues().apply { put("name", f.name.trim()); put("type", f.type.name) }
        val id = if (f.id == 0L) db.insert("custom_fields", null, v) else { db.update("custom_fields", v, "id = ?", arrayOf(f.id.toString())); f.id }
        changed()
        return id
    }

    fun deleteCustomField(f: CustomField) {
        db.delete("field_values", "field_id = ?", arrayOf(f.id.toString()))
        db.delete("custom_fields", "id = ?", arrayOf(f.id.toString()))
        changed()
    }

    fun fieldValues(docId: Long): List<FieldValue> {
        val fields = customFields().associateBy { it.id }
        return db.rawQuery("SELECT field_id, value FROM field_values WHERE doc_id = ?", arrayOf(docId.toString())).use { c ->
            c.mapNotNull { fields[it.getLong(0)]?.let { f -> FieldValue(f, it.getString(1) ?: "") } }
        }
    }

    fun setFieldValue(docId: Long, fieldId: Long, value: String?) {
        if (value == null) db.delete("field_values", "doc_id = ? AND field_id = ?", arrayOf(docId.toString(), fieldId.toString()))
        else db.insertWithOnConflict("field_values", null, ContentValues().apply { put("doc_id", docId); put("field_id", fieldId); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun notes(docId: Long): List<Note> =
        db.rawQuery("SELECT id, doc_id, text, created_at FROM notes WHERE doc_id = ? ORDER BY created_at DESC", arrayOf(docId.toString())).use { c ->
            c.map { Note(it.getLong(0), it.getLong(1), it.getString(2), it.getLong(3)) }
        }

    fun addNote(docId: Long, text: String) {
        db.insert("notes", null, ContentValues().apply { put("doc_id", docId); put("text", text); put("created_at", System.currentTimeMillis()) })
        changed()
    }

    fun deleteNote(id: Long) { db.delete("notes", "id = ?", arrayOf(id.toString())); changed() }

    fun savedViews(): List<SavedView> =
        db.rawQuery("SELECT id, name, filter, dashboard FROM saved_views ORDER BY id", null).use { c ->
            c.map { SavedView(it.getLong(0), it.getString(1), ExtractionJson.readFilter(it.getString(2)), it.getInt(3) != 0) }
        }

    fun saveView(v: SavedView): Long {
        val cv = ContentValues().apply { put("name", v.name); put("filter", ExtractionJson.writeFilter(v.filter)); put("dashboard", if (v.onDashboard) 1 else 0) }
        val id = if (v.id == 0L) db.insert("saved_views", null, cv) else { db.update("saved_views", cv, "id = ?", arrayOf(v.id.toString())); v.id }
        changed()
        return id
    }

    fun deleteView(id: Long) { db.delete("saved_views", "id = ?", arrayOf(id.toString())); changed() }

    // ---------------------------------------------------------------- helpers

    private inline fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out += f(this)
        return out
    }

    private inline fun <T : Any> Cursor.mapNotNull(f: (Cursor) -> T?): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) f(this)?.let { out += it }
        return out
    }

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "papercontrol.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE labels (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL, match TEXT NOT NULL DEFAULT '', algorithm TEXT NOT NULL, ci INTEGER NOT NULL DEFAULT 1, inbox INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE documents (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, correspondent_id INTEGER, doctype_id INTEGER, folder_id INTEGER, created TEXT, added_at INTEGER NOT NULL, modified_at INTEGER NOT NULL, asn INTEGER, content TEXT, fields TEXT, amount REAL, currency TEXT, page_count INTEGER NOT NULL DEFAULT 0, thumb TEXT, remote_task TEXT)")
            db.execSQL("CREATE TABLE doc_tags (doc_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY (doc_id, tag_id))")
            db.execSQL("CREATE TABLE pages (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_id INTEGER NOT NULL, idx INTEGER NOT NULL, path TEXT NOT NULL, original TEXT, text TEXT, captured_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX pages_doc ON pages (doc_id, idx)")
            db.execSQL("CREATE TABLE custom_fields (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT NOT NULL)")
            db.execSQL("CREATE TABLE field_values (doc_id INTEGER NOT NULL, field_id INTEGER NOT NULL, value TEXT, PRIMARY KEY (doc_id, field_id))")
            db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_id INTEGER NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE saved_views (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, filter TEXT NOT NULL, dashboard INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE VIRTUAL TABLE docs_fts USING fts4 (title, content, tokenize=unicode61)")

            // A starting set, like a fresh Paperless-ngx install plus the AI sorting defaults.
            fun label(kind: LabelKind, name: String, color: Int, inbox: Boolean = false, algorithm: MatchAlgorithm = MatchAlgorithm.AUTO, match: String = "") =
                db.insert("labels", null, ContentValues().apply {
                    put("kind", kind.name); put("name", name); put("color", color); put("match", match)
                    put("algorithm", algorithm.name); put("ci", 1); put("inbox", if (inbox) 1 else 0)
                })
            label(LabelKind.TAG, "Inbox", Palette.colors[0], inbox = true, algorithm = MatchAlgorithm.NONE)
            label(LabelKind.TAG, "Tax relevant", Palette.colors[3], algorithm = MatchAlgorithm.ANY, match = "steuer tax finanzamt irs \"tax return\" steuererklärung")
            label(LabelKind.TAG, "Warranty", Palette.colors[5], algorithm = MatchAlgorithm.ANY, match = "warranty garantie gewährleistung")
            label(LabelKind.DOCTYPE, "Receipt", Palette.colors[1], algorithm = MatchAlgorithm.NONE)
            label(LabelKind.DOCTYPE, "Invoice", Palette.colors[2], algorithm = MatchAlgorithm.NONE)
            label(LabelKind.DOCTYPE, "Letter", Palette.colors[4], algorithm = MatchAlgorithm.NONE)
            label(LabelKind.DOCTYPE, "Contract", Palette.colors[6], algorithm = MatchAlgorithm.ANY, match = "contract vertrag agreement vereinbarung")
            label(LabelKind.FOLDER, "Receipts", Palette.colors[1], algorithm = MatchAlgorithm.NONE)
            label(LabelKind.FOLDER, "Invoices", Palette.colors[2], algorithm = MatchAlgorithm.NONE)
            label(LabelKind.FOLDER, "Letters", Palette.colors[4], algorithm = MatchAlgorithm.NONE)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}

/** Label colors: soft tones that read on black. */
object Palette {
    val colors = listOf(
        0xFF6A7CFF.toInt(), 0xFF5CD6A8.toInt(), 0xFFFFB35C.toInt(), 0xFFFF6B8B.toInt(),
        0xFF9C8CFF.toInt(), 0xFF5CC8FF.toInt(), 0xFFE6D65C.toInt(), 0xFFFF8F5C.toInt(),
        0xFFB0B0B8.toInt(), 0xFFD27CFF.toInt(),
    )

    fun forName(name: String): Int = colors[Math.floorMod(name.lowercase().hashCode(), colors.size)]
}
