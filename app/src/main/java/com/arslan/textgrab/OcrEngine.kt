package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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

    /** Words below this confidence are dropped; icons usually land here. */
    private const val MIN_CONFIDENCE = 0.45f

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
                .addOnSuccessListener { text -> cont.resume(buildResult(filter(text))) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /** Drops icon-like noise; line boxes are rebuilt from the words that remain. */
    private fun filter(text: Text): List<Line> {
        val lines = ArrayList<Line>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val words = ArrayList<Pair<String, RectF>>()
                for (e in line.elements) {
                    val box = e.boundingBox ?: continue
                    val t = e.text.trim()
                    // Some ML Kit builds report 0 when confidence is unavailable; don't filter on that.
                    val conf = e.confidence.takeIf { it > 0f }
                    if (isNoise(t, conf)) continue
                    words.add(t to RectF(box))
                }
                if (words.isEmpty()) continue
                // Rebuild the line box from kept words, so a dropped icon doesn't inflate it.
                val lineBox = RectF(words[0].second).apply { words.forEach { union(it.second) } }
                lines.add(Line(lineBox, words))
            }
        }
        return lines
    }

    private fun isNoise(t: String, conf: Float?): Boolean {
        if (t.isEmpty()) return true
        if (conf != null && conf < MIN_CONFIDENCE) return true
        val letters = t.count { it.isLetterOrDigit() }
        // Pure symbols ("<", "©", "|", "•") are almost always icons or dividers.
        if (letters == 0 && t.length <= 2) return true
        // Lone letters are how icons most often get misread ("O", "Q", "e", "@").
        if (t.length == 1 && conf != null && conf < 0.7f) return true
        return false
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
