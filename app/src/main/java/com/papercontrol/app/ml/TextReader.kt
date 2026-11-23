package com.papercontrol.app.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.papercontrol.app.core.Box
import com.papercontrol.app.core.OcrLine
import com.papercontrol.app.core.OcrResult
import kotlinx.coroutines.tasks.await

/** On-device text recognition (ML Kit, bundled model: works offline from the first start). */
object TextReader {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bitmap: Bitmap): OcrResult {
        val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(
                    line.text,
                    box.toBox(),
                    line.elements.mapNotNull { el -> el.boundingBox?.let { OcrLine(el.text, it.toBox()) } },
                )
            }
        }
        return OcrResult(lines, bitmap.width, bitmap.height)
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
