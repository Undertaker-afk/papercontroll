package com.papercontrol.app.data

import android.content.Context
import java.io.File

/** A chat model the user can pick in settings. */
data class ModelSpec(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val fileName: String,
    val approxMb: Int,
    /** Whether the model can look at images. */
    val vision: Boolean = true,
) {
    fun file(context: Context): File = File(ModelCatalog.dir(context), fileName)
    fun partFile(context: Context): File = File(ModelCatalog.dir(context), "$fileName.part")
    private fun doneMarker(context: Context): File = File(ModelCatalog.dir(context), "$fileName.ok")
    fun isReady(context: Context): Boolean = file(context).exists() && doneMarker(context).exists()
    fun markReady(context: Context) = doneMarker(context).writeText("ok")
    fun delete(context: Context) {
        doneMarker(context).delete(); file(context).delete(); partFile(context).delete()
    }
}

/**
 * Optional on-device chat models (LiteRT-LM). Without one the app still reads, sorts and
 * maps documents with its built-in rules and the learned Auto classifier; with one it also
 * writes titles, reads fields the rules miss and double-checks sorting.
 */
object ModelCatalog {
    private const val HF = "https://huggingface.co"
    const val DEFAULT_LLM_ID = "gemma-4-e2b"
    const val CUSTOM_LLM_ID = "custom"

    val models = listOf(
        ModelSpec(
            id = DEFAULT_LLM_ID,
            title = "Gemma 4 E2B",
            summary = "Google's official build. Reads text and looks at photos.",
            url = "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            approxMb = 2588,
        ),
        ModelSpec(
            id = "gemma-4-e2b-abliterated",
            title = "Gemma 4 E2B abliterated",
            summary = "Community build (huihui-ai, converted by edp1096) with refusals removed. Unofficial.",
            url = "$HF/edp1096/Huihui-gemma-4-E2B-it-abliterated-litert-lm/resolve/main/Huihui-gemma-4-E2B-it-abliterated.litertlm",
            fileName = "Huihui-gemma-4-E2B-it-abliterated.litertlm",
            approxMb = 5248,
        ),
    )

    /** EmbeddingGemma 2 (Apache 2.0): turns text into vectors for search by meaning and "more like this". */
    val embedding = ModelSpec(
        id = "eg2-text-vision-440m",
        title = "EmbeddingGemma 2 · 440M",
        summary = "Search by meaning in 100+ languages.",
        url = "$HF/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm",
        fileName = "embeddinggemma-2-text-vision-440m.litertlm",
        approxMb = 388,
    )

    /** Identifies the vector space; vectors from another model or size are kept apart. */
    const val EMBEDDING_KEY = "eg2-440m/256"

    fun resolve(config: Config): ModelSpec? =
        if (config.llmModelId == CUSTOM_LLM_ID) custom(config.llmCustomUrl)
        else models.firstOrNull { it.id == config.llmModelId } ?: models.first()

    /** A model the user points to by link. Null for a link that is not usable. */
    fun custom(url: String): ModelSpec? {
        val clean = url.trim()
        if (!clean.startsWith("https://")) return null
        val direct = clean.replace("/blob/", "/resolve/").substringBefore('?')
        val name = direct.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.endsWith(".litertlm")) return null
        return ModelSpec(CUSTOM_LLM_ID, "Your own model", name, direct, "custom-$name", 0)
    }

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun deleteUnused(context: Context, config: Config) {
        val keep = listOfNotNull(resolve(config)?.fileName?.takeIf { config.llmEnabled }, embedding.fileName.takeIf { config.semanticSearch })
        dir(context).listFiles()?.forEach { f -> if (keep.none { f.name.startsWith(it) }) f.delete() }
    }
}
