package com.papercontrol.app.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.papercontrol.app.core.Box
import com.papercontrol.app.core.Extraction
import com.papercontrol.app.core.Quad
import com.papercontrol.app.processing.Consumer
import com.papercontrol.app.processing.Images
import com.papercontrol.app.processing.PreparedPage
import com.papercontrol.app.processing.Sorting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { CAMERA, REVEAL }

/** One capture on its way through the reveal animation. */
class Capture(
    val photo: Bitmap,
    /** Where the paper sits in [photo], normalized. */
    val quad: Quad,
    val flat: Bitmap,
    val clean: Bitmap,
) {
    /** Set once the text is read: the highlighted fields, in [clean] pixel coordinates. */
    var highlights by mutableStateOf<List<Box>?>(null)
    var page: PreparedPage? = null
}

/**
 * State of one scanning session: the camera, then for every capture the reveal animation,
 * then the review. Several captures make a multi-page document ("Add page").
 */
class ScanSession(private val context: Context, private val scope: CoroutineScope) {
    var stage by mutableStateOf(Stage.CAMERA)
    val pages = mutableStateListOf<PreparedPage>()
    var capture by mutableStateOf<Capture?>(null)
    var sorting by mutableStateOf<Sorting?>(null)
    var extraction by mutableStateOf(Extraction())
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    private var work: Job? = null

    /** True once text and sorting are ready: the reveal may finish and the button lights up. */
    val ready get() = sorting != null

    fun onCaptured(photo: Bitmap, liveQuad: Quad?) {
        work?.cancel()
        sorting = null
        error = null
        work = scope.launch {
            try {
                val (quad, flat, clean) = withContext(Dispatchers.Default) {
                    val q = Images.detect(photo)?.quad ?: liveQuad ?: Quad.FULL
                    val f = Consumer.flatten(photo, q)
                    Triple(q, f, Consumer.clean(f))
                }
                val c = Capture(photo, quad, flat, clean)
                capture = c
                stage = Stage.REVEAL
                val page = Consumer.finish(photo, flat, clean, System.currentTimeMillis())
                c.highlights = page.extraction.highlights
                c.page = page
                pages += page
                extraction = Consumer.merge(pages)
                sorting = Consumer.sort(context, pages)
            } catch (t: Throwable) {
                Log.w("ScanSession", "processing failed", t)
                error = t.message ?: "Could not read this page."
                capture?.highlights = emptyList()
            }
        }
    }

    /** Back to the camera for one more page of the same document. */
    fun addPage() {
        capture = null
        stage = Stage.CAMERA
    }

    /** Drops the last capture and goes back to the camera. */
    fun retake() {
        work?.cancel()
        capture?.page?.let { last ->
            pages.remove(last)
            Consumer.discard(listOf(last))
        }
        extraction = if (pages.isEmpty()) Extraction() else Consumer.merge(pages)
        sorting = null
        capture = null
        stage = Stage.CAMERA
        if (pages.isNotEmpty()) scope.launch { sorting = Consumer.sort(context, pages) }
    }

    fun discardAll() {
        work?.cancel()
        Consumer.discard(pages)
        pages.clear()
    }

    suspend fun save(title: String?): Long? {
        val s = sorting ?: return null
        saving = true
        return try {
            Consumer.commit(context, pages.toList(), s, title ?: s.title).also { pages.clear() }
        } finally {
            saving = false
        }
    }
}
