package com.papercontrol.app.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import com.papercontrol.app.core.PageGroup
import com.papercontrol.app.core.PageInput
import com.papercontrol.app.core.PaperMapper
import com.papercontrol.app.core.Quad
import com.papercontrol.app.data.Settings
import com.papercontrol.app.ml.Llm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A document proposed by paper mapping, before it is saved. */
data class Proposal(
    val pages: List<PreparedPage>,
    val reasons: List<String>,
    val confidence: Double,
    val sorting: Sorting? = null,
    /** Documents already in the archive that this one looks like. */
    val duplicates: List<Duplicate> = emptyList(),
)

data class ImportState(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val proposals: List<Proposal> = emptyList(),
    val error: String? = null,
)

/**
 * Batch import with paper mapping: many photos (and PDFs) in, documents out. Photos are
 * cropped and read one by one, [PaperMapper] decides which belong together and in which
 * order, the chat model (if on) settles close calls, then every document is sorted.
 */
object Importer {
    private const val TAG = "Importer"
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state

    fun clear(discard: Boolean) {
        if (discard) _state.value.proposals.forEach { Consumer.discard(it.pages) }
        _state.value = ImportState()
    }

    suspend fun run(context: Context, uris: List<Uri>) = withContext(Dispatchers.Default) {
        clear(discard = true)
        _state.value = ImportState(running = true, phase = "Reading pages", total = uris.size)
        try {
            val photos = mutableListOf<PreparedPage>()
            val pdfDocs = mutableListOf<List<PreparedPage>>()
            for ((i, uri) in uris.withIndex()) {
                val type = context.contentResolver.getType(uri).orEmpty()
                try {
                    if (type == "application/pdf" || uri.toString().endsWith(".pdf", true)) {
                        // A PDF is one document already; its pages stay together and in order.
                        val pages = Pdf.render(context, uri).map { bmp -> Consumer.prepare(context, bmp, null) }
                        if (pages.isNotEmpty()) pdfDocs += pages
                    } else {
                        val bmp = Images.decode(context, uri) ?: continue
                        val quad = Images.detect(bmp)?.quad ?: Quad.FULL
                        val taken = Images.capturedAt(context, uri).takeIf { it > 0 } ?: (System.currentTimeMillis() - (uris.size - i) * 1000L)
                        photos += Consumer.prepare(context, bmp, quad, taken)
                        bmp.recycle()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "could not read $uri", t)
                }
                _state.value = _state.value.copy(done = i + 1)
            }

            _state.value = _state.value.copy(phase = "Mapping pages")
            val inputs = photos.mapIndexed { i, p -> PageInput(i, p.ocr, p.capturedAt, p.tone, p.extraction) }
            val separated = photos.any { it.codes.separator }
            var groups: List<PageGroup> = when {
                // Separator sheets decide exactly where documents start, as in Paperless-ngx.
                separated -> splitAtSeparators(photos)
                Settings.current.paperMapping -> PaperMapper.group(inputs)
                else -> inputs.map { PageGroup(listOf(it.key), 1.0, emptyList()) }
            }
            photos.filter { it.codes.separator }.let { Consumer.discard(it) }

            if (!separated && Settings.current.paperMapping && Llm.isReady(context)) {
                _state.value = _state.value.copy(phase = "Checking close calls with AI")
                groups = settleWithAi(context, inputs, groups, photos)
            }

            val proposals = groups.map { g -> Proposal(g.keys.map { photos[it] }, g.reasons, g.confidence) } +
                pdfDocs.map { Proposal(it, listOf("Pages of one PDF"), 1.0) }

            _state.value = _state.value.copy(phase = "Sorting documents", done = 0, total = proposals.size, proposals = proposals)
            val sorted = proposals.mapIndexed { i, p ->
                val s = runCatching { Consumer.sort(context, p.pages) }.getOrNull()
                val dups = runCatching { Consumer.duplicates(p.pages, s) }.getOrDefault(emptyList())
                _state.value = _state.value.copy(done = i + 1)
                p.copy(sorting = s, duplicates = dups)
            }
            _state.value = ImportState(running = false, phase = "Ready", done = sorted.size, total = sorted.size, proposals = sorted)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed", t)
            _state.value = _state.value.copy(running = false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    /** Pages in capture order, cut at separator sheets; the sheets themselves are left out. */
    private fun splitAtSeparators(photos: List<PreparedPage>): List<PageGroup> {
        val order = photos.indices.sortedBy { photos[it].capturedAt }
        val out = mutableListOf<PageGroup>()
        var cur = mutableListOf<Int>()
        for (i in order) {
            if (photos[i].codes.separator) {
                if (cur.isNotEmpty()) out += PageGroup(cur, 1.0, listOf("Separator sheet"))
                cur = mutableListOf()
            } else cur += i
        }
        if (cur.isNotEmpty()) out += PageGroup(cur, 1.0, listOf("Separator sheet"))
        return out
    }

    private suspend fun settleWithAi(context: Context, inputs: List<PageInput>, groups: List<PageGroup>, photos: List<PreparedPage>): List<PageGroup> {
        val pairs = PaperMapper.undecided(inputs, groups)
        if (pairs.isEmpty()) return groups
        val joinWithNext = BooleanArray(groups.size)
        for ((a, b) in pairs) {
            val ta = photos[groups[a].keys.last()].ocr.text
            val tb = photos[groups[b].keys.first()].ocr.text
            if (runCatching { Llm.samePaper(context, ta, tb) }.getOrNull() == true) joinWithNext[a] = true
        }
        val out = mutableListOf<PageGroup>()
        var cur: PageGroup? = null
        for ((i, g) in groups.withIndex()) {
            cur = if (cur == null) g else PageGroup(cur.keys + g.keys, minOf(cur.confidence, 0.6), (cur.reasons + "AI: same document").distinct())
            if (!joinWithNext[i]) { out += cur; cur = null }
        }
        cur?.let { out += it }
        return out
    }

    // ---- edits on the review screen

    fun movePage(from: Int, pageIndex: Int, to: Int) = edit { list ->
        val page = list[from].pages[pageIndex]
        list[from] = list[from].copy(pages = list[from].pages - page)
        if (to >= list.size) list += Proposal(listOf(page), listOf("Moved by you"), 1.0) else list[to] = list[to].copy(pages = list[to].pages + page)
        list.removeAll { it.pages.isEmpty() }
    }

    fun mergeWithNext(index: Int) = edit { list ->
        if (index + 1 >= list.size) return@edit
        list[index] = list[index].copy(pages = list[index].pages + list[index + 1].pages, reasons = list[index].reasons + "Joined by you")
        list.removeAt(index + 1)
    }

    fun splitOff(index: Int, pageIndex: Int) = edit { list ->
        val p = list[index]
        if (p.pages.size < 2) return@edit
        val page = p.pages[pageIndex]
        list[index] = p.copy(pages = p.pages - page)
        list.add(index + 1, Proposal(listOf(page), listOf("Split off by you"), 1.0, p.sorting))
    }

    fun movePageWithin(index: Int, pageIndex: Int, delta: Int) = edit { list ->
        val pages = list[index].pages.toMutableList()
        val to = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        pages.add(to, pages.removeAt(pageIndex))
        list[index] = list[index].copy(pages = pages)
    }

    fun remove(index: Int) = edit { list -> Consumer.discard(list[index].pages); list.removeAt(index) }

    private fun edit(f: (MutableList<Proposal>) -> Unit) {
        val list = _state.value.proposals.toMutableList()
        f(list)
        _state.value = _state.value.copy(proposals = list)
    }

    /** Saves every proposal as a document. */
    suspend fun saveAll(context: Context): Int {
        val list = _state.value.proposals
        _state.value = _state.value.copy(running = true, phase = "Saving", done = 0, total = list.size)
        var n = 0
        for (p in list) {
            val sorting = p.sorting ?: Consumer.sort(context, p.pages)
            Consumer.commit(context, p.pages, sorting)
            n++
            _state.value = _state.value.copy(done = n)
        }
        _state.value = ImportState()
        return n
    }
}
