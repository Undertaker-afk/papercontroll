package com.papercontrol.app.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/** Reads every barcode and QR code on a page (ML Kit, bundled model, offline). */
object BarcodeReader {
    private val client by lazy { BarcodeScanning.getClient() }

    suspend fun read(bitmap: Bitmap): List<String> =
        runCatching { client.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { it.rawValue } }.getOrDefault(emptyList())
}
