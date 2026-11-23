package com.papercontrol.app.data

import com.papercontrol.app.core.DocKind
import com.papercontrol.app.core.Extraction
import com.papercontrol.app.core.LineItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Stores the fields read from a document with it, so the detail card can show them later. */
object ExtractionJson {
    fun write(e: Extraction): String = JSONObject().apply {
        put("kind", e.kind.name)
        e.merchant?.let { put("merchant", it) }
        e.address?.let { put("address", it) }
        e.phone?.let { put("phone", it) }
        e.date?.let { put("date", it.toString()) }
        e.time?.let { put("time", it.toString()) }
        e.orderNumber?.let { put("order", it) }
        e.table?.let { put("table", it) }
        e.invoiceNumber?.let { put("invoice", it) }
        if (e.items.isNotEmpty()) put("items", JSONArray().apply {
            e.items.forEach { put(JSONObject().put("name", it.name).put("amount", it.amount).put("qty", it.quantity)) }
        })
        e.subtotal?.let { put("subtotal", it) }
        e.taxLabel?.let { put("taxLabel", it) }
        e.tax?.let { put("tax", it) }
        e.total?.let { put("total", it) }
        e.currency?.let { put("currency", it) }
        e.cardBrand?.let { put("cardBrand", it) }
        e.cardLast4?.let { put("cardLast4", it) }
        e.authCode?.let { put("auth", it) }
        e.pageCount?.let { put("pageCount", it) }
    }.toString()

    fun read(s: String): Extraction {
        if (s.isBlank()) return Extraction()
        return try {
            val o = JSONObject(s)
            fun str(k: String) = if (o.has(k)) o.getString(k) else null
            fun num(k: String) = if (o.has(k)) o.getDouble(k) else null
            val items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).map { i ->
                    val it = a.getJSONObject(i)
                    LineItem(it.getString("name"), it.getDouble("amount"), it.optInt("qty", 1))
                }
            }.orEmpty()
            Extraction(
                kind = runCatching { DocKind.valueOf(o.optString("kind", "OTHER")) }.getOrDefault(DocKind.OTHER),
                merchant = str("merchant"),
                address = str("address"),
                phone = str("phone"),
                date = str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                time = str("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                orderNumber = str("order"),
                table = str("table"),
                invoiceNumber = str("invoice"),
                items = items,
                subtotal = num("subtotal"),
                taxLabel = str("taxLabel"),
                tax = num("tax"),
                total = num("total"),
                currency = str("currency"),
                cardBrand = str("cardBrand"),
                cardLast4 = str("cardLast4"),
                authCode = str("auth"),
                pageCount = if (o.has("pageCount")) o.getInt("pageCount") else null,
            )
        } catch (_: Exception) {
            Extraction()
        }
    }

    fun writeFilter(f: Filter): String = JSONObject().apply {
        put("q", f.query)
        put("tags", JSONArray(f.tagIds.toList()))
        f.correspondentId?.let { put("c", it) }
        f.docTypeId?.let { put("t", it) }
        f.folderId?.let { put("f", it) }
        put("inbox", f.inboxOnly)
        f.from?.let { put("from", it.toString()) }
        f.to?.let { put("to", it.toString()) }
        put("sort", f.sort.name)
        put("desc", f.descending)
    }.toString()

    fun readFilter(s: String): Filter = try {
        val o = JSONObject(s)
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }.orEmpty()
        Filter(
            query = o.optString("q"),
            tagIds = tags,
            correspondentId = if (o.has("c")) o.getLong("c") else null,
            docTypeId = if (o.has("t")) o.getLong("t") else null,
            folderId = if (o.has("f")) o.getLong("f") else null,
            inboxOnly = o.optBoolean("inbox"),
            from = if (o.has("from")) LocalDate.parse(o.getString("from")) else null,
            to = if (o.has("to")) LocalDate.parse(o.getString("to")) else null,
            sort = runCatching { SortBy.valueOf(o.optString("sort")) }.getOrDefault(SortBy.ADDED),
            descending = o.optBoolean("desc", true),
        )
    } catch (_: Exception) {
        Filter()
    }
}
