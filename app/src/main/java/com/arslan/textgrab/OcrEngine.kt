package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around ML Kit's bundled on-device text recognizer.
 * The model ships inside the APK, so recognition works fully offline
 * and without Google Play services (GrapheneOS etc.).
 */
object OcrEngine {

    /**
     * One recognized word with its bounding box in bitmap pixel coordinates.
     * [lineId] is the recognized line; [rowId] groups lines that sit side by
     * side on the same visual row. Words are stored in reading order.
     */
    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val rowId: Int,
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
                    // ML Kit splits screenshots into many tiny blocks; treating
                    // those as paragraphs produced lots of empty lines.
                    sb.append(if (words[i - 1].rowId != words[i].rowId) "\n" else " ")
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)
    }

    private class Line(val box: RectF, val words: List<Pair<String, RectF>>)

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
                    val lines = ArrayList<Line>()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val words = line.elements.mapNotNull { e ->
                                val box = e.boundingBox ?: return@mapNotNull null
                                if (e.text.isBlank()) null else e.text to RectF(box)
                            }
                            if (words.isEmpty()) continue
                            val box = line.boundingBox?.let { RectF(it) }
                                ?: RectF(words[0].second).apply { words.forEach { union(it.second) } }
                            lines.add(Line(box, words))
                        }
                    }
                    cont.resume(buildResult(lines))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /** Orders lines top-to-bottom, grouping vertically overlapping lines into
     *  one row read left-to-right, so selection follows the visual layout. */
    private fun buildResult(lines: List<Line>): Result {
        val byTop = lines.sortedBy { it.box.centerY() }
        val rows = ArrayList<MutableList<Line>>()
        for (line in byTop) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row[0].box, line.box)) row.add(line) else rows.add(mutableListOf(line))
        }
        val words = ArrayList<Word>()
        val lineBoxes = ArrayList<RectF>()
        var lineId = 0
        rows.forEachIndexed { rowId, row ->
            for (line in row.sortedBy { it.box.left }) {
                lineBoxes.add(line.box)
                for ((t, box) in line.words.sortedBy { it.second.left }) {
                    words.add(Word(t, box, lineId, rowId))
                }
                lineId++
            }
        }
        return Result(words, lineBoxes)
    }

    private fun sameRow(a: RectF, b: RectF): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height(), b.height())
    }
}
