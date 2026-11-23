package com.papercontrol.app.processing

import android.content.Context
import com.papercontrol.app.core.QuerySyntax
import com.papercontrol.app.core.Similarity
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.ModelCatalog
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search that combines words (full-text index) with meaning (EmbeddingGemma vectors), and
 * "more like this". Without the search model it falls back to words only.
 */
object Search {

    /** Document ids by closeness in meaning to [text], best first; null when the model is not available. */
    suspend fun byMeaning(context: Context, text: String, limit: Int = 60): List<Pair<Long, Float>>? = withContext(Dispatchers.Default) {
        if (text.isBlank() || !Embedder.isReady(context)) return@withContext null
        val vectors = Store.embeddings(ModelCatalog.EMBEDDING_KEY)
        if (vectors.isEmpty()) return@withContext null
        val q = runCatching { Embedder.query(context, text) }.getOrNull() ?: return@withContext null
        val scored = vectors.map { (id, v) -> id to Similarity.cosine(q, v) }.sortedByDescending { it.second }
        // Keep what stands out from the archive's average, not a fixed similarity value.
        val avg = scored.map { it.second }.average().toFloat()
        val best = scored.first().second
        val floor = avg + (best - avg) * 0.45f
        scored.filter { it.second >= floor }.take(limit)
    }

    /** The list for the documents screen: word matches and meaning matches together, most relevant first. */
    suspend fun documents(context: Context, filter: Filter, semantic: Boolean): List<Document> = withContext(Dispatchers.IO) {
        val text = QuerySyntax.parse(filter.query).text
        val words = Store.documents(filter)
        if (!semantic || text.isBlank() || filter.trash) return@withContext words
        val meaning = byMeaning(context, text) ?: return@withContext words
        val score = HashMap<Long, Float>()
        meaning.forEach { (id, s) -> score[id] = s }
        // A literal word match is a strong signal: rank those first among equals.
        words.forEach { d -> score[d.id] = (score[d.id] ?: 0f) + 1f }
        val ids = score.keys.toList()
        Store.documents(filter, 500, ids).sortedByDescending { score[it.id] ?: 0f }
    }

    /** Documents most like [doc]: by meaning when vectors exist, by shared words otherwise. */
    suspend fun similar(context: Context, doc: Document, limit: Int = 8): List<Document> = withContext(Dispatchers.IO) {
        val key = ModelCatalog.EMBEDDING_KEY
        val mine = Store.embedding(doc.id, key)
        val ranked: List<Long> = if (mine != null) {
            Store.embeddings(key).filterKeys { it != doc.id }.map { (id, v) -> id to Similarity.cosine(mine, v) }
                .sortedByDescending { it.second }.take(limit).map { it.first }
        } else {
            val others = Store.documents(Filter(), 2000).filter { it.id != doc.id }.map { it.id to (it.title + "\n" + it.content.take(3000)) }
            Similarity.rankByText(doc.title + "\n" + doc.content, others, limit).map { it.first }
        }
        val byId = Store.documents(Filter(), 500, ranked).associateBy { it.id }
        ranked.mapNotNull { byId[it] }
    }
}
