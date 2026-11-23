package com.papercontrol.app.data

/**
 * Search box syntax, close to Paperless-ngx: free words search titles and text; these
 * prefixes filter: tag:, correspondent: (or from:), type:, folder:, asn:, year:.
 * Values with spaces go in quotes: correspondent:"Nordlit Coffee".
 */
object QueryParser {
    data class Parsed(
        val text: String,
        val tagIds: Set<Long> = emptySet(),
        val correspondentId: Long? = null,
        val docTypeId: Long? = null,
        val folderId: Long? = null,
        val asn: Int? = null,
        val year: Int? = null,
    )

    private val token = Regex("""(\w+):(?:"([^"]*)"|(\S+))|"([^"]*)"|(\S+)""")

    fun parse(query: String, store: Store): Parsed {
        if (!query.contains(':')) return Parsed(query.trim())
        val words = mutableListOf<String>()
        val tags = mutableSetOf<Long>()
        var corr: Long? = null
        var type: Long? = null
        var folder: Long? = null
        var asn: Int? = null
        var year: Int? = null
        fun find(kind: LabelKind, name: String): Long? =
            store.labels(kind).let { all -> all.firstOrNull { it.name.equals(name, true) } ?: all.firstOrNull { it.name.startsWith(name, true) } }?.id
        for (m in token.findAll(query)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            when {
                key.isEmpty() -> words += m.groupValues[4].ifEmpty { m.groupValues[5] }
                key == "tag" -> find(LabelKind.TAG, value)?.let { tags += it } ?: run { tags += -1 }
                key == "correspondent" || key == "from" -> corr = find(LabelKind.CORRESPONDENT, value) ?: -1
                key == "type" -> type = find(LabelKind.DOCTYPE, value) ?: -1
                key == "folder" || key == "path" -> folder = find(LabelKind.FOLDER, value) ?: -1
                key == "asn" -> asn = value.toIntOrNull() ?: -1
                key == "year" || key == "created" -> year = value.take(4).toIntOrNull()
                else -> words += m.value
            }
        }
        return Parsed(words.joinToString(" "), tags, corr, type, folder, asn, year)
    }
}
