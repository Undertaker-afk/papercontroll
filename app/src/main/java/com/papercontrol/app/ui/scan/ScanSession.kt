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
import com.papercontrol.app.processing.Duplicate
import com.papercontrol.app.processing.Images
import com.papercontrol.app.processing.PreparedPage
import com.papercontrol.app.processing.Sorting
import com.papercontrol.app.vision.Found
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { CAMERA, REVEAL }

/** One capture on its way through the reveal animation. */
class Capture(
    /** The photo as taken. */
    val photo: Bitmap,
    /** Where the page is in [photo] (normalized). */
    val quad: Quad,
    /** The straightened page as photographed (upright). */
    val flat: Bitmap,
    /** The scanner-style version of it. */
    val clean: Bitmap,
    /** Fields found on it, in [clean] pixels: what the scan band inks in. */
    val highlights: List<Box>,
    val page: PreparedPage,
    /** Quarter turns clockwise applied to the straightened page to make its text upright. */
    val turns: Int = 0,
) {
    /** The quad's corners as they appear on the upright page: tl, tr, br, bl of [flat]. */
    val corners get() = quad.points.let { p -> List(4) { i -> p[((i - turns) % 4 + 4) % 4] } }
}

/**
 * State of one scanning session: the camera, then for every capture the reveal animation,
 * then the review. Several captures make a multi-page document ("Add page").
 */
class ScanSession(private val context: Context, private val scope: CoroutineScope) {
    var stage by mutableStateOf(Stage.CAMERA)
    val pages = mutableStateListOf<PreparedPage>()

    /** The photo just taken, shown while the page is found and read. */
    var pendingPhoto by mutableStateOf<Bitmap?>(null)
    /** The page as found in [pendingPhoto]; null until found. */
    var pendingQuad by mutableStateOf<Quad?>(null)
    var pendingHow by mutableStateOf<Found?>(null)

    var capture by mutableStateOf<Capture?>(null)
    var sorting by mutableStateOf<Sorting?>(null)
    /** Documents already in the archive that this scan looks like. */
    var duplicates by mutableStateOf<List<Duplicate>>(emptyList())
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
        capture = null
        pendingQuad = null
        pendingHow = null
        pendingPhoto = photo
        stage = Stage.REVEAL
        work = scope.launch {
            try {
                // 1. Which page is it? Shown at once as an outline on the frozen photo.
                val det = withContext(Dispatchers.Default) { Images.detect(photo, thorough = true) }
                val quad = det?.quad ?: liveQuad ?: Quad.FULL
                pendingQuad = quad
                pendingHow = det?.how
                // 2. Straighten, clean up, read, turn upright.
                val p = Consumer.process(photo, quad, det?.how)
                val c = Capture(photo, p.quad, p.flat, p.clean, p.page.extraction.highlights, p.page, p.turns)
                pages += p.page
                extraction = Consumer.merge(pages)
                capture = c
                val s = Consumer.sort(context, pages)
                duplicates = Consumer.duplicates(pages, s)
                sorting = s
            } catch (t: Throwable) {
                Log.w("ScanSession", "processing failed", t)
                error = t.message ?: "Could not read this page."
            }
        }
    }

    /** Back to the camera for one more page of the same document. */
    fun addPage() {
        capture = null
        pendingPhoto = null
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
        pendingPhoto = null
        pendingQuad = null
        error = null
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
