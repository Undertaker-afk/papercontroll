package com.papercontrol.app.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.papercontrol.app.data.ModelCatalog
import com.papercontrol.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma 2 via LiteRT-LM, text only: documents and search queries become vectors in
 * one space, so a query finds documents with the same meaning in other words or languages.
 * Adapted from the LiteRT image organizer; loaded on demand, unloaded when idle.
 */
object Embedder {
    private const val TAG = "Embedder"
    private const val DIM = 256
    private const val QUERY_PREFIX = "task: search result | query: "
    private const val MAX_CHARS = 700
    private const val SHORT_CHARS = 300
    private const val IDLE_UNLOAD_MS = 60_000L

    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null
    private var unloadJob: Job? = null
    @Volatile private var gpuFailed = false

    fun isReady(context: Context): Boolean = Settings.current.semanticSearch && ModelCatalog.embedding.isReady(context)

    suspend fun query(context: Context, text: String): FloatArray = embed(context, QUERY_PREFIX + text.trim())

    /** EmbeddingGemma's document format: title, then the text. */
    suspend fun document(context: Context, title: String, text: String): FloatArray =
        embed(context, "title: ${title.ifBlank { "none" }} | text: ${text.replace(Regex("\\s+"), " ").trim()}")

    private suspend fun embed(context: Context, text: String): FloatArray = mutex.withLock {
        withContext(Dispatchers.Default) {
            unloadJob?.cancel()
            try {
                val e = ensureLocked(context.applicationContext)
                val opts = EmbeddingOptions(true, null, DIM, null)
                try {
                    e.computeEmbedding(listOf(InputData.Text(text.take(MAX_CHARS))), opts).embedding
                } catch (t: Throwable) {
                    // More tokens than the model's text signature takes: retry shorter.
                    e.computeEmbedding(listOf(InputData.Text(text.take(SHORT_CHARS))), opts).embedding
                }
            } finally {
                @Suppress("OPT_IN_USAGE")
                unloadJob = GlobalScope.launch { delay(IDLE_UNLOAD_MS); mutex.withLock { closeLocked() } }
            }
        }
    }

    suspend fun release() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        try { engine?.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
        engine = null
    }

    private fun ensureLocked(context: Context): EmbeddingEngine {
        engine?.let { return it }
        val spec = ModelCatalog.embedding
        if (!spec.isReady(context)) throw IllegalStateException("The search model is not downloaded.")
        if (!gpuFailed) {
            try {
                return create(context, spec.file(context), Backend.GPU()).also { engine = it }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU start failed, using CPU", t)
                gpuFailed = true
            }
        }
        return create(context, spec.file(context), Backend.CPU()).also { engine = it }
    }

    private fun create(context: Context, file: File, backend: Backend): EmbeddingEngine {
        val cache = File(context.cacheDir, "litertlm-embed").apply { mkdirs() }
        val e = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                visionBackend = Backend.CPU(),
                audioBackend = null,
                cacheDir = cache.absolutePath,
                modelFd = null,
                maxInputLength = 256,
                visionTokensPerImage = 70,
            )
        )
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        return e
    }
}
