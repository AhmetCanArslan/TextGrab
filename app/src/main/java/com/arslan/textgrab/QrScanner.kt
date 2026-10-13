package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

object QrScanner {

    data class Code(val text: String, val box: RectF)

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    suspend fun scan(bitmap: Bitmap): List<Code> =
        BarcodeScanning.getClient(options).use { scanner ->
            scanner.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { code ->
                val text = code.rawValue?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val box = code.boundingBox ?: return@mapNotNull null
                Code(text, RectF(box))
            }
        }
}
