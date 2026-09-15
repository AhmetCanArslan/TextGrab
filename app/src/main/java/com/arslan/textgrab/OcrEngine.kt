package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around ML Kit's bundled on-device text recognizer.
 * The model ships inside the APK, so recognition works fully offline
 * and without Google Play services (GrapheneOS etc.).
 */
object OcrEngine {

    /** One recognized word with its bounding box in bitmap pixel coordinates. */
    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val blockId: Int,
    )

    data class Result(
        val words: List<Word>,
        val lineBoxes: List<RectF>,
    ) {
        val isEmpty get() = words.isEmpty()

        /** Joins a word range [start..end] back into readable text. */
        fun textOf(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) {
                if (i > start) {
                    val prev = words[i - 1]
                    val cur = words[i]
                    sb.append(
                        when {
                            prev.blockId != cur.blockId -> "\n\n"
                            prev.lineId != cur.lineId -> "\n"
                            else -> " "
                        }
                    )
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun warmUp() {
        // Trigger lazy init + model load off the critical path.
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { bmp.recycle() }
    }

    suspend fun recognize(bitmap: Bitmap): Result =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val words = ArrayList<Word>()
                    val lineBoxes = ArrayList<RectF>()
                    var lineId = 0
                    text.textBlocks.forEachIndexed { blockId, block ->
                        for (line in block.lines) {
                            line.boundingBox?.let { lineBoxes.add(RectF(it)) }
                            for (element in line.elements) {
                                val box = element.boundingBox ?: continue
                                if (element.text.isBlank()) continue
                                words.add(Word(element.text, RectF(box), lineId, blockId))
                            }
                            lineId++
                        }
                    }
                    cont.resume(Result(words, lineBoxes))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
