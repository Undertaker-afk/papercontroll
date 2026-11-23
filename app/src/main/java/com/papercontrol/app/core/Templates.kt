package com.papercontrol.app.core

import java.time.LocalDate

/**
 * Title and folder templates, like Paperless-ngx storage paths:
 * "{correspondent} {created_year}-{created_month}" or "{document_type}s/{correspondent}".
 * Missing values drop out, together with the separators around them.
 */
object Templates {
    val PLACEHOLDERS = listOf(
        "title", "correspondent", "document_type", "folder", "tags", "tag_list", "created", "created_year",
        "created_month", "created_month_name", "created_day", "added", "asn", "amount", "currency",
    )

    data class Values(
        val title: String? = null,
        val correspondent: String? = null,
        val documentType: String? = null,
        val folder: String? = null,
        val tags: List<String> = emptyList(),
        val created: LocalDate? = null,
        val added: LocalDate? = null,
        val asn: Int? = null,
        val amount: Double? = null,
        val currency: String? = null,
    )

    private val token = Regex("""\{([a-z_]+)}""")

    fun apply(template: String, v: Values): String {
        val map = mapOf(
            "title" to v.title,
            "correspondent" to v.correspondent,
            "document_type" to v.documentType,
            "folder" to v.folder,
            "tags" to v.tags.firstOrNull(),
            "tag_list" to v.tags.joinToString(", ").ifEmpty { null },
            "created" to v.created?.toString(),
            "created_year" to v.created?.year?.toString(),
            "created_month" to v.created?.monthValue?.toString()?.padStart(2, '0'),
            "created_month_name" to v.created?.month?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()),
            "created_day" to v.created?.dayOfMonth?.toString()?.padStart(2, '0'),
            "added" to v.added?.toString(),
            "asn" to v.asn?.toString(),
            "amount" to v.amount?.let { String.format(java.util.Locale.ROOT, "%.2f", it) },
            "currency" to v.currency,
        )
        // Each "/"-separated part is filled on its own; empty parts disappear.
        return template.split('/').map { part ->
            token.replace(part) { m -> map[m.groupValues[1]]?.replace("/", "-") ?: "\u0000" }
                .replace(Regex("""[\s\-–·,_]*\u0000[\s\-–·,_]*""")) { r ->
                    // Keep one separator if text remains on both sides.
                    val sep = r.value.replace("\u0000", "").trim().firstOrNull()
                    if (sep != null) " $sep " else " "
                }
                .replace("\u0000", "")
                .trim(' ', '-', '–', '·', ',', '_')
                .replace(Regex("""\s{2,}"""), " ")
        }.filter { it.isNotBlank() }.joinToString("/")
    }
}
