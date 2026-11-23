package com.papercontrol.app.data

import com.papercontrol.app.core.MatchAlgorithm
import com.papercontrol.app.core.Matchable
import java.time.LocalDate

/** The four things a document is sorted by. Same shape in Paperless-ngx. */
enum class LabelKind(val title: String, val singular: String) {
    TAG("Tags", "Tag"),
    CORRESPONDENT("Correspondents", "Correspondent"),
    DOCTYPE("Document types", "Document type"),
    FOLDER("Folders", "Folder"),
}

data class Label(
    override val id: Long = 0,
    val kind: LabelKind,
    override val name: String,
    val color: Int = 0xFF8E8E93.toInt(),
    override val match: String = "",
    override val algorithm: MatchAlgorithm = MatchAlgorithm.AUTO,
    override val caseInsensitive: Boolean = true,
    /** Tags only: new documents get this tag until someone has looked at them. */
    val isInbox: Boolean = false,
    val documentCount: Int = 0,
) : Matchable

data class Document(
    val id: Long = 0,
    val title: String,
    val correspondentId: Long? = null,
    val docTypeId: Long? = null,
    val folderId: Long? = null,
    val created: LocalDate? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    /** Archive serial number: the number written on the paper original, if you keep it. */
    val asn: Int? = null,
    val content: String = "",
    /** Extracted fields as JSON, see [ExtractionJson]. */
    val fields: String = "",
    val amount: Double? = null,
    val currency: String? = null,
    val pageCount: Int = 0,
    val thumbPath: String? = null,
    val tagIds: List<Long> = emptyList(),
    /** Id on the Paperless-ngx server after upload. */
    val remoteTask: String? = null,
)

data class Page(
    val id: Long = 0,
    val docId: Long,
    val index: Int,
    /** The flattened, cleaned-up scan. */
    val path: String,
    /** The photo as taken. */
    val originalPath: String?,
    val text: String,
    val capturedAt: Long,
)

enum class FieldType(val title: String) { TEXT("Text"), NUMBER("Number"), MONETARY("Amount"), DATE("Date"), BOOLEAN("Yes / no"), URL("Link") }

data class CustomField(val id: Long = 0, val name: String, val type: FieldType)

data class FieldValue(val field: CustomField, val value: String)

data class Note(val id: Long = 0, val docId: Long, val text: String, val createdAt: Long = System.currentTimeMillis())

enum class SortBy(val title: String) { ADDED("Added"), CREATED("Date"), TITLE("Title"), AMOUNT("Amount") }

/** What the document list shows. Saved views store one of these. */
data class Filter(
    val query: String = "",
    val tagIds: Set<Long> = emptySet(),
    val correspondentId: Long? = null,
    val docTypeId: Long? = null,
    val folderId: Long? = null,
    val inboxOnly: Boolean = false,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sort: SortBy = SortBy.ADDED,
    val descending: Boolean = true,
) {
    val isEmpty get() = query.isBlank() && tagIds.isEmpty() && correspondentId == null && docTypeId == null && folderId == null && !inboxOnly && from == null && to == null
}

data class SavedView(val id: Long = 0, val name: String, val filter: Filter, val onDashboard: Boolean = true)
