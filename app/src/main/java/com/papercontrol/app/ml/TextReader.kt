package com.papercontrol.app.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.papercontrol.app.core.Box
import com.papercontrol.app.core.OcrLine
import com.papercontrol.app.core.OcrResult
import com.papercontrol.app.data.Settings
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

/**
 * On-device text recognition (ML Kit). Latin script is bundled and works offline from the
 * first start; Chinese, Devanagari, Japanese and Korean can be switched on in settings
 * (Google Play services downloads those models once). With several scripts on, the reading
 * with the most recognized characters wins.
 */
object TextReader {
    val SCRIPTS = linkedMapOf("chinese" to "Chinese", "japanese" to "Japanese", "korean" to "Korean", "devanagari" to "Devanagari (Hindi, Marathi, Nepali)")

    private val clients = HashMap<String, TextRecognizer>()

    private fun client(script: String): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "devanagari" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    suspend fun read(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        var best = client("latin").process(image).await()
        for (script in Settings.current.ocrScripts) {
            val other = runCatching { client(script).process(image).await() }.getOrNull() ?: continue
            if (other.text.count { !it.isWhitespace() } > best.text.count { !it.isWhitespace() } * 1.1) best = other
        }
        return convert(best, bitmap.width, bitmap.height)
    }

    private fun convert(text: Text, w: Int, h: Int): OcrResult {
        val lines = text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val cp = line.cornerPoints
                val angle = if (cp != null && cp.size >= 2) Math.toDegrees(kotlin.math.atan2((cp[1].y - cp[0].y).toDouble(), (cp[1].x - cp[0].x).toDouble())).toFloat() else line.angle
                OcrLine(line.text, box.toBox(), line.elements.mapNotNull { el -> el.boundingBox?.let { OcrLine(el.text, it.toBox()) } }, angle, line.confidence)
            }
        }
        return OcrResult(lines, w, h)
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    /** Lines and positions as compact JSON, stored with the page for the PDF text layer. */
    fun toJson(r: OcrResult): String = JSONArray().apply {
        put(r.width); put(r.height)
        for (l in r.lines) put(JSONArray().put(l.text).put(l.box.left.toInt()).put(l.box.top.toInt()).put(l.box.right.toInt()).put(l.box.bottom.toInt()))
    }.toString()

    fun fromJson(s: String): OcrResult? = runCatching {
        val a = JSONArray(s)
        val lines = (2 until a.length()).map { i ->
            val l = a.getJSONArray(i)
            OcrLine(l.getString(0), Box(l.getInt(1).toFloat(), l.getInt(2).toFloat(), l.getInt(3).toFloat(), l.getInt(4).toFloat()))
        }
        OcrResult(lines, a.getInt(0), a.getInt(1))
    }.getOrNull()
}
