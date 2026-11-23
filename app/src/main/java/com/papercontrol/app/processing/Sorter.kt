package com.papercontrol.app.processing

import android.content.Context
import android.util.Log
import com.papercontrol.app.core.Classifier
import com.papercontrol.app.core.DocKind
import com.papercontrol.app.core.Extraction
import com.papercontrol.app.core.MatchAlgorithm
import com.papercontrol.app.core.Matcher
import com.papercontrol.app.core.Text
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.Label
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.Llm
import java.time.LocalDate

/** Where a new document should go, with a short reason per decision for the review screen. */
data class Sorting(
    val title: String,
    val correspondent: Pick? = null,
    val docType: Pick? = null,
    val folder: Pick? = null,
    val tags: List<Pick> = emptyList(),
    val created: LocalDate? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val usedAi: Boolean = false,
)

/** An existing label ([id] set) or one to create on save ([id] null). */
data class Pick(val id: Long?, val name: String, val why: String)

/**
 * Automated sorting. In order of trust:
 *  1. rules the user wrote (any word, all words, exact, regex, fuzzy),
 *  2. the Auto classifier, learned from documents already sorted,
 *  3. what the page says (sender name, receipt or invoice),
 *  4. the optional on-device chat model, for anything still open and for the title.
 */
object Sorter {
    private const val TAG = "Sorter"
    private var trainedAt = -1
    private var models: Map<LabelKind, Any> = emptyMap()

    suspend fun sort(context: Context, text: String, ex: Extraction, image: ByteArray?): Sorting {
        val cfg = Settings.current
        val labels = LabelKind.entries.associateWith { Store.labels(it) }
        val train = trained(labels)

        fun single(kind: LabelKind): Pick? {
            val all = labels[kind].orEmpty()
            all.firstOrNull { it.algorithm != MatchAlgorithm.AUTO && Matcher.matches(it, text) }?.let { return Pick(it.id, it.name, "Matches “${it.match.take(30)}”") }
            if (!cfg.autoSort) return null
            val c = train[kind] as? Classifier ?: return null
            val id = c.predict(text) ?: return null
            if (id == Classifier.NONE) return null
            return all.firstOrNull { it.id == id }?.let { Pick(it.id, it.name, "Learned from your documents") }
        }

        var correspondent = single(LabelKind.CORRESPONDENT)
        var docType = single(LabelKind.DOCTYPE)
        var folder = single(LabelKind.FOLDER)
        val tags = mutableListOf<Pick>()
        for (t in labels[LabelKind.TAG].orEmpty()) {
            if (t.isInbox) { tags += Pick(t.id, t.name, "New documents start in the inbox"); continue }
            if (t.algorithm != MatchAlgorithm.AUTO) {
                if (Matcher.matches(t, text)) tags += Pick(t.id, t.name, "Matches “${t.match.take(30)}”")
            } else if (cfg.autoSort) {
                @Suppress("UNCHECKED_CAST")
                val c = (train[LabelKind.TAG] as? Map<Long, Classifier>)?.get(t.id) ?: continue
                if (c.predict(text, 0.75) == 1L) tags += Pick(t.id, t.name, "Learned from your documents")
            }
        }

        // What the page itself says.
        if (correspondent == null && ex.merchant != null && com.papercontrol.app.core.Heading.plausible(ex.merchant) && ex.merchant.split(' ').size <= 5) {
            val m = Text.normalize(ex.merchant)
            val existing = labels[LabelKind.CORRESPONDENT].orEmpty().maxByOrNull { Text.partialRatio(Text.normalize(it.name), m) }
            correspondent = if (existing != null && Text.partialRatio(Text.normalize(existing.name), m) >= 88) Pick(existing.id, existing.name, "Name on the document")
            else if (cfg.createLabels) Pick(null, ex.merchant, "New sender, read from the document") else null
        }
        val kindName = when (ex.kind) { DocKind.RECEIPT -> "Receipt"; DocKind.INVOICE -> "Invoice"; DocKind.LETTER -> "Letter"; DocKind.OTHER -> null }
        if (docType == null && kindName != null) {
            labels[LabelKind.DOCTYPE].orEmpty().firstOrNull { it.name.equals(kindName, true) }?.let { docType = Pick(it.id, it.name, "Looks like a ${kindName.lowercase()}") }
        }
        if (folder == null && kindName != null) {
            labels[LabelKind.FOLDER].orEmpty().firstOrNull { it.name.equals(kindName + "s", true) }?.let { folder = Pick(it.id, it.name, "Folder for ${kindName.lowercase()}s") }
        }

        var title = ex.suggestedTitle() ?: titleFallback(text, ex)
        var created = ex.date
        var amount = ex.total
        var currency = ex.currency ?: cfg.defaultCurrency.ifBlank { null }
        var usedAi = false

        if (Llm.isReady(context, cfg)) {
            try {
                val known = mapOf(
                    "tags" to labels[LabelKind.TAG].orEmpty().filter { !it.isInbox }.map { it.name },
                    "types" to labels[LabelKind.DOCTYPE].orEmpty().map { it.name },
                    "folders" to labels[LabelKind.FOLDER].orEmpty().map { it.name },
                    "correspondents" to labels[LabelKind.CORRESPONDENT].orEmpty().map { it.name },
                )
                Llm.describe(context, text, image, known)?.let { s ->
                    usedAi = true
                    s.title?.let { title = it }
                    fun resolve(kind: LabelKind, name: String?): Pick? {
                        if (name.isNullOrBlank()) return null
                        val hit = labels[kind].orEmpty().firstOrNull { it.name.equals(name, true) }
                            ?: labels[kind].orEmpty().firstOrNull { Text.partialRatio(Text.normalize(it.name), Text.normalize(name)) >= 90 }
                        return hit?.let { Pick(it.id, it.name, "AI") } ?: if (cfg.createLabels) Pick(null, name, "AI suggested a new one") else null
                    }
                    if (correspondent == null || correspondent!!.id == null) resolve(LabelKind.CORRESPONDENT, s.correspondent)?.let { correspondent = it }
                    if (docType == null) docType = resolve(LabelKind.DOCTYPE, s.documentType)
                    if (folder == null) folder = resolve(LabelKind.FOLDER, s.folder)
                    for (t in s.tags) {
                        val p = labels[LabelKind.TAG].orEmpty().firstOrNull { it.name.equals(t, true) }?.let { Pick(it.id, it.name, "AI") }
                        if (p != null && tags.none { it.id == p.id }) tags += p
                    }
                    if (created == null) created = s.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (amount == null) amount = s.total
                    if (currency == null) currency = s.currency
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AI sorting failed", t)
            }
        }

        return Sorting(title, correspondent, docType, folder, tags, created, amount, currency, usedAi)
    }

    private fun titleFallback(text: String, ex: Extraction): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { l -> l.count { it.isLetter() } >= 6 }
        if (first != null) {
            val words = first.split(Regex("\\s+"))
            return words.take(6).joinToString(" ") + if (words.size > 6) "…" else ""
        }
        val k = when (ex.kind) { DocKind.RECEIPT -> "Receipt"; DocKind.INVOICE -> "Invoice"; DocKind.LETTER -> "Letter"; DocKind.OTHER -> "Document" }
        return "$k ${ex.date ?: LocalDate.now()}"
    }

    /** Labels of [Pick]s that do not exist yet are created; returns their ids. */
    fun resolve(kind: LabelKind, p: Pick?): Long? = p?.let { it.id ?: Store.labelNamed(kind, it.name) }

    /** Trains (or reuses) the Auto classifiers. Retrained whenever the archive changed. */
    private fun trained(labels: Map<LabelKind, List<Label>>): Map<LabelKind, Any> {
        val v = Store.version.value
        if (v == trainedAt) return models
        val docs: List<Document> = Store.trainingSet()
        val out = HashMap<LabelKind, Any>()
        fun singleModel(kind: LabelKind, of: (Document) -> Long?) {
            val auto = labels[kind].orEmpty().filter { it.algorithm == MatchAlgorithm.AUTO }.map { it.id }.toSet()
            if (auto.isEmpty()) return
            val examples = docs.map { d -> d.title + "\n" + d.content to (of(d)?.takeIf { it in auto } ?: Classifier.NONE) }
            if (examples.count { it.second != Classifier.NONE } < 2) return
            out[kind] = Classifier.train(examples)
        }
        singleModel(LabelKind.CORRESPONDENT) { it.correspondentId }
        singleModel(LabelKind.DOCTYPE) { it.docTypeId }
        singleModel(LabelKind.FOLDER) { it.folderId }
        val tagModels = HashMap<Long, Classifier>()
        for (t in labels[LabelKind.TAG].orEmpty().filter { it.algorithm == MatchAlgorithm.AUTO && !it.isInbox }) {
            val pos = docs.count { t.id in it.tagIds }
            if (pos < 2 || pos == docs.size) continue
            tagModels[t.id] = Classifier.trainBinary(docs.map { d -> d.title + "\n" + d.content to (t.id in d.tagIds) })
        }
        out[LabelKind.TAG] = tagModels
        models = out
        trainedAt = v
        return out
    }
}
