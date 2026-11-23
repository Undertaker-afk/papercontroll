package com.papercontrol.app.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.papercontrol.app.data.Config
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
import org.json.JSONObject
import java.io.File

/** What the chat model suggests for a document. Every field is optional. */
data class AiSuggestion(
    val title: String? = null,
    val correspondent: String? = null,
    val documentType: String? = null,
    val tags: List<String> = emptyList(),
    val folder: String? = null,
    val date: String? = null,
    val total: Double? = null,
    val currency: String? = null,
)

/**
 * The optional on-device chat model (Gemma via LiteRT-LM). It is loaded on first use and
 * unloaded shortly after the last request, because it needs gigabytes of memory.
 */
object Llm {
    private const val TAG = "Llm"
    private const val IDLE_UNLOAD_MS = 45_000L

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engineKey: String? = null
    private var unloadJob: Job? = null

    fun isReady(context: Context, cfg: Config = Settings.current): Boolean =
        cfg.llmEnabled && ModelCatalog.resolve(cfg)?.isReady(context) == true

    /**
     * Reads a document and sorts it. [known] lists existing names per kind so the model
     * reuses them instead of inventing near-duplicates.
     */
    suspend fun describe(
        context: Context,
        text: String,
        image: ByteArray?,
        known: Map<String, List<String>>,
    ): AiSuggestion? {
        val spec = ModelCatalog.resolve(Settings.current) ?: return null
        val prompt = buildString {
            appendLine("Here is the text of a scanned document (receipt, invoice, letter, contract or similar). Read it and sort it.")
            appendLine("Reply with one JSON object and nothing else, with these keys:")
            appendLine("title: short title, at most 6 words, e.g. \"Electricity bill March 2026\" or \"Nordlit Coffee\".")
            appendLine("correspondent: the company or person who sent or issued it.")
            appendLine("document_type: one of ${known["types"].orEmpty().joinToString(", ").ifEmpty { "Receipt, Invoice, Letter, Contract" }}, or a new short type.")
            appendLine("tags: up to 3 tags. Prefer these existing tags: ${known["tags"].orEmpty().joinToString(", ")}.")
            appendLine("folder: one of ${known["folders"].orEmpty().joinToString(", ")}, or a new short folder name.")
            appendLine("date: the document date as YYYY-MM-DD, or null.")
            appendLine("total: the amount to pay or paid as a number, or null.")
            appendLine("currency: ISO code such as EUR or USD, or null.")
            val corr = known["correspondents"].orEmpty()
            if (corr.isNotEmpty()) appendLine("Known correspondents: ${corr.take(60).joinToString(", ")}.")
            appendLine()
            appendLine("Document text:")
            append(text.take(6000))
        }
        val parts = buildList {
            if (image != null && spec.vision) add(Content.ImageBytes(image))
            add(Content.Text(prompt))
        }
        val reply = ask(context, "You file paper documents for a person. You reply with JSON only.", parts, maxTokens = 4096)
        return parseSuggestion(reply)
    }

    /** Whether two page texts belong to the same document; null when the model gives no clear answer. */
    suspend fun samePaper(context: Context, a: String, b: String): Boolean? {
        val reply = ask(
            context,
            "You sort photographed pages into documents. You answer with exactly one word: yes or no.",
            listOf(
                Content.Text(
                    "Page A:\n${a.take(1500)}\n\nPage B:\n${b.take(1500)}\n\n" +
                        "Are page A and page B pages (or parts) of the same single document, such as one invoice, one letter or one long receipt? Answer yes or no."
                )
            ),
            maxTokens = 4096,
        )
        val t = reply.trim().lowercase()
        return when {
            t.startsWith("yes") || t.startsWith("ja") -> true
            t.startsWith("no") || t.startsWith("nein") -> false
            else -> null
        }
    }

    fun parseSuggestion(reply: String): AiSuggestion? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(reply.substring(start, end + 1))
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.trim()
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { t -> t.isNotBlank() }?.trim() } }.orEmpty()
            AiSuggestion(
                title = s("title"),
                correspondent = s("correspondent"),
                documentType = s("document_type"),
                tags = tags.take(3),
                folder = s("folder"),
                date = s("date"),
                total = o.optDouble("total").takeIf { !it.isNaN() },
                currency = s("currency")?.uppercase()?.take(3),
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun release() = mutex.withLock { closeLocked() }

    private suspend fun ask(context: Context, system: String, parts: List<Content>, maxTokens: Int): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            unloadJob?.cancel()
            try {
                val e = ensureLocked(context.applicationContext, maxTokens)
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                )
                e.createConversation(config).use { conversation ->
                    val message = conversation.sendMessage(Contents.of(parts))
                    message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        @Suppress("OPT_IN_USAGE")
        unloadJob = GlobalScope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock { closeLocked() }
        }
    }

    private fun closeLocked() {
        try { engine?.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
        engine = null
        engineKey = null
    }

    private fun ensureLocked(context: Context, maxTokens: Int): Engine {
        var cfg = Settings.current
        val spec = ModelCatalog.resolve(cfg)
        if (spec == null || !spec.isReady(context)) throw IllegalStateException("The AI model is not downloaded.")
        var key = "${spec.fileName}|${cfg.llmBackend}"
        engine?.let { if (engineKey == key) return it }
        closeLocked()
        if (cfg.llmBackend == Config.BACKEND_GPU) {
            try {
                val e = create(context, spec.file(context), spec.vision, Backend.GPU(), maxTokens)
                Settings.clearLlmNote()
                engine = e; engineKey = key
                return e
            } catch (t: Throwable) {
                Log.w(TAG, "GPU start failed", t)
                Settings.llmFallBackToCpu("The AI model could not start on the GPU, so it runs on the CPU. (${t.message?.lineSequence()?.lastOrNull()?.take(120) ?: "unknown error"})")
                cfg = Settings.current
                key = "${spec.fileName}|${cfg.llmBackend}"
            }
        }
        val e = create(context, spec.file(context), spec.vision, Backend.CPU(), maxTokens)
        engine = e; engineKey = key
        return e
    }

    private fun create(context: Context, file: File, vision: Boolean, backend: Backend, maxTokens: Int): Engine {
        val cache = File(context.cacheDir, "litertlm-chat").apply { mkdirs() }
        val e = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                visionBackend = if (vision) backend else null,
                maxNumTokens = maxTokens,
                maxNumImages = if (vision) 1 else null,
                cacheDir = cache.absolutePath,
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
