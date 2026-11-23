package com.papercontrol.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.papercontrol.app.core.DeadlineKind
import com.papercontrol.app.core.Expense
import com.papercontrol.app.core.MatchAlgorithm
import com.papercontrol.app.core.QuerySyntax
import com.papercontrol.app.core.Similarity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

/**
 * The local document archive: SQLite with a full-text index over titles and recognized text.
 * Every write bumps [version], which screens observe to reload.
 */
object Store {
    @Volatile private var helper: Helper? = null
    private val db: SQLiteDatabase get() = helper!!.writableDatabase
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

    private const val DOC_COLS = "d.id, d.title, d.correspondent_id, d.doctype_id, d.folder_id, d.created, d.added_at, d.modified_at, d.asn, d.content, d.fields, d.amount, d.currency, d.page_count, d.thumb, d.remote_task, (SELECT GROUP_CONCAT(tag_id) FROM doc_tags WHERE doc_id = d.id), d.remote_id, d.deleted_at, d.phash, d.duplicate_of"

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
        remoteId = c.longOrNull(17),
        deletedAt = c.longOrNull(18),
        pageHash = c.longOrNull(19),
        duplicateOf = c.longOrNull(20),
    )

    fun document(id: Long): Document? =
        db.rawQuery("SELECT $DOC_COLS FROM documents d WHERE d.id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) doc(c) else null }

    /** Documents matching [f]; [ids] restricts to those ids (search by meaning), in that order when sorting by relevance. */
    fun documents(f: Filter, limit: Int = 500, ids: List<Long>? = null): List<Document> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        val q = QuerySyntax.parse(f.query)
        where += if (f.trash) "d.deleted_at IS NOT NULL" else "d.deleted_at IS NULL"
        fun named(kind: LabelKind, name: String): Long =
            labels(kind).let { all -> all.firstOrNull { it.name.equals(name, true) } ?: all.firstOrNull { it.name.startsWith(name, true) } }?.id ?: -1L
        val tagIds = f.tagIds + q.tags.map { named(LabelKind.TAG, it) }
        for (t in tagIds) { where += "EXISTS (SELECT 1 FROM doc_tags WHERE doc_id = d.id AND tag_id = ?)"; args += t.toString() }
        for (t in q.notTags.map { named(LabelKind.TAG, it) }) { where += "NOT EXISTS (SELECT 1 FROM doc_tags WHERE doc_id = d.id AND tag_id = ?)"; args += t.toString() }
        (f.correspondentId ?: q.correspondent?.let { named(LabelKind.CORRESPONDENT, it) })?.let { where += "d.correspondent_id = ?"; args += it.toString() }
        (f.docTypeId ?: q.type?.let { named(LabelKind.DOCTYPE, it) })?.let { where += "d.doctype_id = ?"; args += it.toString() }
        (f.folderId ?: q.folder?.let { named(LabelKind.FOLDER, it) })?.let { where += "d.folder_id = ?"; args += it.toString() }
        q.asn?.let { where += "d.asn = ?"; args += it.toString() }
        (q.createdFrom ?: f.from)?.let { where += "d.created >= ?"; args += it.toString() }
        (q.createdTo ?: f.to)?.let { where += "d.created <= ?"; args += it.toString() }
        q.addedWithinDays?.let { where += "d.added_at >= ?"; args += (System.currentTimeMillis() - it * 86_400_000L).toString() }
        q.amountMin?.let { where += "d.amount >= ?"; args += it.toString() }
        q.amountMax?.let { where += "d.amount <= ?"; args += it.toString() }
        val inbox = "EXISTS (SELECT 1 FROM doc_tags dt JOIN labels l ON l.id = dt.tag_id WHERE dt.doc_id = d.id AND l.inbox = 1)"
        if (f.inboxOnly) where += inbox
        for (flag in q.flags) when (flag.substringAfter(':')) {
            "inbox" -> where += inbox
            "due", "reminder", "deadline" -> where += "EXISTS (SELECT 1 FROM reminders r WHERE r.doc_id = d.id AND r.done = 0)"
            "duplicate" -> where += "d.duplicate_of IS NOT NULL"
            "payment" -> where += "d.fields LIKE '%\"payment\"%'"
            "notes" -> where += "EXISTS (SELECT 1 FROM notes n WHERE n.doc_id = d.id)"
            "asn" -> where += "d.asn IS NOT NULL"
            "synced" -> where += "d.remote_id IS NOT NULL"
        }
        for ((name, value) in q.fields) {
            where += "EXISTS (SELECT 1 FROM field_values v JOIN custom_fields cf ON cf.id = v.field_id WHERE v.doc_id = d.id AND cf.name = ? COLLATE NOCASE AND v.value LIKE ?)"
            args += name; args += "%$value%"
        }
        if (q.text.isNotBlank() && ids == null) {
            val match = q.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                .joinToString(" ") { w -> "\"" + w.replace("\"", "") + "\"*" }
            where += "d.id IN (SELECT docid FROM docs_fts WHERE docs_fts MATCH ?)"
            args += match
        }
        if (ids != null) where += if (ids.isEmpty()) "0" else "d.id IN (${ids.joinToString(",")})"
        val order = when (f.sort) {
            SortBy.ADDED -> if (f.trash) "d.deleted_at" else "d.added_at"
            SortBy.CREATED -> "COALESCE(d.created, '0000')"
            SortBy.TITLE -> "d.title COLLATE NOCASE"
            SortBy.AMOUNT -> "COALESCE(d.amount, 0)"
        } + if (f.descending) " DESC" else " ASC"
        val sql = "SELECT $DOC_COLS FROM documents d WHERE " + where.joinToString(" AND ") + " ORDER BY $order, d.id DESC LIMIT $limit"
        return try {
            db.rawQuery(sql, args.toTypedArray()).use { c -> c.map { doc(it) } }
        } catch (e: Exception) {
            emptyList() // an incomplete search expression while typing
        }
    }

    fun count(inboxOnly: Boolean = false): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM documents d WHERE d.deleted_at IS NULL" +
                if (inboxOnly) " AND EXISTS (SELECT 1 FROM doc_tags dt JOIN labels l ON l.id = dt.tag_id WHERE dt.doc_id = d.id AND l.inbox = 1)" else "",
            null,
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    fun trashCount(): Int = db.rawQuery("SELECT COUNT(*) FROM documents WHERE deleted_at IS NOT NULL", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Content of every document for training the Auto classifier, with its current sorting. */
    fun trainingSet(): List<Document> =
        db.rawQuery("SELECT $DOC_COLS FROM documents d WHERE d.deleted_at IS NULL ORDER BY d.id DESC LIMIT 3000", null).use { c -> c.map { doc(it) } }

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

    /** Moves documents to the trash. They stay restorable for [TRASH_DAYS] days. */
    fun trash(ids: Collection<Long>) {
        val now = System.currentTimeMillis()
        db.transaction { for (id in ids) execSQL("UPDATE documents SET deleted_at = ? WHERE id = ?", arrayOf<Any>(now, id)) }
        changed()
    }

    fun restore(ids: Collection<Long>) {
        db.transaction { for (id in ids) execSQL("UPDATE documents SET deleted_at = NULL WHERE id = ?", arrayOf<Any>(id)) }
        changed()
    }

    const val TRASH_DAYS = 30

    /** Deletes documents in the trash for longer than [TRASH_DAYS] days, or all of them. */
    fun emptyTrash(all: Boolean = false) {
        val cutoff = if (all) Long.MAX_VALUE else System.currentTimeMillis() - TRASH_DAYS * 86_400_000L
        val ids = db.rawQuery("SELECT id FROM documents WHERE deleted_at IS NOT NULL AND deleted_at <= ?", arrayOf(cutoff.toString())).use { c -> c.map { it.getLong(0) } }
        ids.forEach { purgeDocument(it) }
    }

    /** Deletes a document and its files for good. */
    fun purgeDocument(id: Long) {
        pages(id).forEach { p -> File(p.path).delete(); p.originalPath?.let { File(it).delete() } }
        document(id)?.thumbPath?.let { File(it).delete() }
        db.transaction {
            for (t in listOf("pages", "doc_tags", "field_values", "notes", "reminders", "embeddings")) delete(t, "doc_id = ?", arrayOf(id.toString()))
            delete("docs_fts", "docid = ?", arrayOf(id.toString()))
            delete("documents", "id = ?", arrayOf(id.toString()))
        }
        changed()
    }

    // ---------------------------------------------------------------- bulk edits

    fun bulkTag(ids: Collection<Long>, tagId: Long, add: Boolean) {
        db.transaction {
            for (id in ids) {
                if (add) insertWithOnConflict("doc_tags", null, ContentValues().apply { put("doc_id", id); put("tag_id", tagId) }, SQLiteDatabase.CONFLICT_IGNORE)
                else delete("doc_tags", "doc_id = ? AND tag_id = ?", arrayOf(id.toString(), tagId.toString()))
                execSQL("UPDATE documents SET modified_at = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
            }
        }
        changed()
    }

    fun bulkSet(ids: Collection<Long>, kind: LabelKind, labelId: Long?) {
        val col = when (kind) {
            LabelKind.CORRESPONDENT -> "correspondent_id"
            LabelKind.DOCTYPE -> "doctype_id"
            LabelKind.FOLDER -> "folder_id"
            LabelKind.TAG -> return
        }
        db.transaction { for (id in ids) execSQL("UPDATE documents SET $col = ?, modified_at = ? WHERE id = ?", arrayOf<Any?>(labelId, System.currentTimeMillis(), id)) }
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
        put("remote_id", d.remoteId); put("deleted_at", d.deletedAt); put("phash", d.pageHash); put("duplicate_of", d.duplicateOf)
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
        db.rawQuery("SELECT id, doc_id, idx, path, original, text, captured_at, ocr FROM pages WHERE doc_id = ? ORDER BY idx", arrayOf(docId.toString())).use { c ->
            c.map { Page(it.getLong(0), it.getLong(1), it.getInt(2), it.getString(3), it.getString(4), it.getString(5) ?: "", it.getLong(6), it.getString(7) ?: "") }
        }

    /** Removes one page (not the last one) and its files. */
    fun deletePage(page: Page): Boolean {
        val rest = pages(page.docId).filter { it.id != page.id }
        if (rest.isEmpty()) return false
        File(page.path).delete(); page.originalPath?.let { File(it).delete() }
        db.delete("pages", "id = ?", arrayOf(page.id.toString()))
        reorderPages(page.docId, rest.map { it.id })
        document(page.docId)?.let { d ->
            updateDocument(d.copy(pageCount = rest.size, content = rest.joinToString("\n\n") { it.text }, thumbPath = rest.first().path))
        }
        return true
    }

    /** Replaces a page's image and what was read from it (after rotating), and refreshes the document's text. */
    fun replacePage(page: Page, newPath: String, text: String, ocr: String) {
        db.execSQL("UPDATE pages SET path = ?, text = ?, ocr = ? WHERE id = ?", arrayOf<Any>(newPath, text, ocr, page.id))
        db.execSQL("UPDATE documents SET thumb = ? WHERE id = ? AND thumb = ?", arrayOf<Any>(newPath, page.docId, page.path))
        document(page.docId)?.let { d -> updateDocument(d.copy(content = pages(page.docId).joinToString("\n\n") { it.text }, thumbPath = if (d.thumbPath == page.path) newPath else d.thumbPath)) }
    }

    /** Points a page at a new image file (after rotating), and the thumbnail too if it showed that page. */
    fun replacePageImage(page: Page, newPath: String) {
        db.execSQL("UPDATE pages SET path = ? WHERE id = ?", arrayOf<Any>(newPath, page.id))
        db.execSQL("UPDATE documents SET thumb = ? WHERE id = ? AND thumb = ?", arrayOf<Any>(newPath, page.docId, page.path))
        changed()
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
        put("doc_id", p.docId); put("idx", p.index); put("path", p.path); put("original", p.originalPath); put("text", p.text); put("captured_at", p.capturedAt); put("ocr", p.ocr)
    }

    // ---------------------------------------------------------------- custom fields, notes, views

    fun customFields(): List<CustomField> =
        db.rawQuery("SELECT id, name, type, options FROM custom_fields ORDER BY name COLLATE NOCASE", null).use { c ->
            c.map {
                CustomField(
                    it.getLong(0), it.getString(1), runCatching { FieldType.valueOf(it.getString(2)) }.getOrDefault(FieldType.TEXT),
                    it.getString(3)?.split('\n')?.filter { o -> o.isNotBlank() }.orEmpty(),
                )
            }
        }

    fun saveCustomField(f: CustomField): Long {
        val v = ContentValues().apply { put("name", f.name.trim()); put("type", f.type.name); put("options", f.options.joinToString("\n")) }
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

    // ---------------------------------------------------------------- reminders

    fun reminders(docId: Long? = null, openOnly: Boolean = false, until: LocalDate? = null): List<Reminder> {
        val where = mutableListOf("d.deleted_at IS NULL")
        val args = mutableListOf<String>()
        docId?.let { where += "r.doc_id = ?"; args += it.toString() }
        if (openOnly) where += "r.done = 0"
        until?.let { where += "r.date <= ?"; args += it.toString() }
        return db.rawQuery(
            "SELECT r.id, r.doc_id, r.date, r.kind, r.note, r.done, r.notified, d.title FROM reminders r JOIN documents d ON d.id = r.doc_id WHERE ${where.joinToString(" AND ")} ORDER BY r.date",
            args.toTypedArray(),
        ).use { c ->
            c.map {
                Reminder(
                    it.getLong(0), it.getLong(1), LocalDate.parse(it.getString(2)),
                    runCatching { DeadlineKind.valueOf(it.getString(3)) }.getOrDefault(DeadlineKind.PAYMENT),
                    it.getString(4) ?: "", it.getInt(5) != 0,
                    it.getString(6)?.split(',')?.mapNotNull { n -> n.toIntOrNull() }?.toSet().orEmpty(),
                    it.getString(7) ?: "",
                )
            }
        }
    }

    fun saveReminder(r: Reminder): Long {
        val v = ContentValues().apply {
            put("doc_id", r.docId); put("date", r.date.toString()); put("kind", r.kind.name); put("note", r.note)
            put("done", if (r.done) 1 else 0); put("notified", r.notified.joinToString(","))
        }
        val id = if (r.id == 0L) db.insert("reminders", null, v) else { db.update("reminders", v, "id = ?", arrayOf(r.id.toString())); r.id }
        changed()
        return id
    }

    fun deleteReminder(id: Long) { db.delete("reminders", "id = ?", arrayOf(id.toString())); changed() }

    // ---------------------------------------------------------------- search vectors

    fun embedding(docId: Long, model: String): FloatArray? =
        db.rawQuery("SELECT vec FROM embeddings WHERE doc_id = ? AND model = ?", arrayOf(docId.toString(), model)).use { c ->
            if (c.moveToFirst()) floats(c.getBlob(0)) else null
        }

    fun embeddings(model: String): Map<Long, FloatArray> =
        db.rawQuery("SELECT e.doc_id, e.vec FROM embeddings e JOIN documents d ON d.id = e.doc_id WHERE e.model = ? AND d.deleted_at IS NULL", arrayOf(model)).use { c ->
            val out = HashMap<Long, FloatArray>()
            while (c.moveToNext()) out[c.getLong(0)] = floats(c.getBlob(1))
            out
        }

    fun saveEmbedding(docId: Long, model: String, v: FloatArray) {
        val buf = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        db.insertWithOnConflict("embeddings", null, ContentValues().apply { put("doc_id", docId); put("model", model); put("vec", buf.array()); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Documents without a vector for [model] yet (or changed since). */
    fun unembedded(model: String, limit: Int = 50): List<Document> =
        db.rawQuery(
            "SELECT $DOC_COLS FROM documents d WHERE d.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.doc_id = d.id AND e.model = ? AND e.at >= d.modified_at) ORDER BY d.id DESC LIMIT $limit",
            arrayOf(model),
        ).use { c -> c.map { doc(it) } }

    private fun floats(b: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }

    // ---------------------------------------------------------------- duplicates, stats, sync

    fun fingerprints(): List<Similarity.Fingerprint> {
        val names = labels(LabelKind.CORRESPONDENT).associate { it.id to it.name }
        return db.rawQuery("SELECT id, phash, amount, created, correspondent_id, substr(content, 1, 4000) FROM documents WHERE deleted_at IS NULL", null).use { c ->
            c.map {
                Similarity.Fingerprint(
                    it.getLong(0), it.longOrNull(1), if (it.isNull(2)) null else it.getDouble(2),
                    it.getString(3)?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() },
                    it.longOrNull(4)?.let { id -> names[id] }, it.getString(5) ?: "",
                )
            }
        }
    }

    /** Every dated document with an amount, for the expense overview. */
    fun expenses(): List<Pair<Document, Expense>> {
        val all = allLabels()
        return documents(Filter(), Int.MAX_VALUE).mapNotNull { d ->
            val date = d.created ?: return@mapNotNull null
            val amount = d.amount ?: return@mapNotNull null
            d to Expense(date, amount, d.currency, d.docTypeId?.let { all[it]?.name }, d.correspondentId?.let { all[it]?.name })
        }
    }

    fun byRemoteId(remoteId: Long): Document? =
        db.rawQuery("SELECT $DOC_COLS FROM documents d WHERE d.remote_id = ?", arrayOf(remoteId.toString())).use { c -> if (c.moveToFirst()) doc(c) else null }

    /** Sets server-side ids without touching the modification time (so it does not count as a local edit). */
    fun setRemote(docId: Long, remoteId: Long?, task: String?) {
        db.execSQL("UPDATE documents SET remote_id = ?, remote_task = ? WHERE id = ?", arrayOf<Any?>(remoteId, task, docId))
        changed()
    }

    fun setModified(docId: Long, at: Long) {
        db.execSQL("UPDATE documents SET modified_at = ? WHERE id = ?", arrayOf<Any>(at, docId))
    }

    fun setPageHash(docId: Long, hash: Long?, duplicateOf: Long?) {
        db.execSQL("UPDATE documents SET phash = ?, duplicate_of = ? WHERE id = ?", arrayOf<Any?>(hash, duplicateOf, docId))
        changed()
    }

    /** The database file, for backups. Call [close] first when replacing it. */
    fun databaseFile(): File = appContext.getDatabasePath("papercontrol.db")

    fun close() { helper?.close() }

    /** Hands [read] a consistent copy of the database file: the log is folded in and writers wait meanwhile. */
    fun snapshot(read: (File) -> Unit) {
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        db.beginTransaction()
        try { read(databaseFile()) } finally { db.endTransaction() }
    }

    /** Rewrites stored file paths after a restore from another app folder. */
    fun relocate(from: String, to: String) {
        db.execSQL("UPDATE pages SET path = replace(path, ?, ?), original = replace(original, ?, ?)", arrayOf<Any>(from, to, from, to))
        db.execSQL("UPDATE documents SET thumb = replace(thumb, ?, ?)", arrayOf<Any>(from, to))
        changed()
    }

    /** Reopens after a restore. */
    fun reopen() { helper = Helper(appContext); changed() }

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

    private class Helper(context: Context) : SQLiteOpenHelper(context, "papercontrol.db", null, 2) {
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
            upgradeTo2(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) upgradeTo2(db)
        }

        /** Trash, duplicates, sync ids, page text positions, field choices, reminders, search vectors. */
        private fun upgradeTo2(db: SQLiteDatabase) {
            for (col in listOf("remote_id INTEGER", "deleted_at INTEGER", "phash INTEGER", "duplicate_of INTEGER")) db.execSQL("ALTER TABLE documents ADD COLUMN $col")
            db.execSQL("ALTER TABLE pages ADD COLUMN ocr TEXT")
            db.execSQL("ALTER TABLE custom_fields ADD COLUMN options TEXT")
            db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_id INTEGER NOT NULL, date TEXT NOT NULL, kind TEXT NOT NULL, note TEXT, done INTEGER NOT NULL DEFAULT 0, notified TEXT)")
            db.execSQL("CREATE INDEX reminders_date ON reminders (done, date)")
            db.execSQL("CREATE TABLE embeddings (doc_id INTEGER NOT NULL, model TEXT NOT NULL, vec BLOB NOT NULL, at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (doc_id, model))")
            db.execSQL("CREATE INDEX documents_remote ON documents (remote_id)")
        }
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
