package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wrapper around ML Kit's bundled on-device text recognizers (Latin, Chinese,
 * Japanese, Korean). All models ship inside the APK, so recognition works
 * fully offline and without Google Play services (GrapheneOS etc.).
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
        /** ML Kit block (paragraph) of each line, indexed like [lineBoxes]. */
        val lineBlocks: List<Int>,
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

        /** Text of each line, indexed like [lineBoxes]. */
        val lineTexts: List<String>
            get() {
                val byLine = words.groupBy { it.lineId }
                return lineBoxes.indices.map { id ->
                    byLine[id]?.joinToString(" ") { it.text }.orEmpty()
                }
            }
    }

    private class Line(val box: RectF, val words: List<Pair<String, RectF>>, val blockId: Int)

    /** Filtered recognizer output plus a quality score used to pick between scripts. */
    private class Scored(val lines: List<Line>, val score: Float, val meanConfidence: Float)

    /** Words below this confidence are dropped; icons usually land here. */
    private const val MIN_CONFIDENCE = 0.45f

    /** Latin results below this mean confidence trigger the CJK recognizers. */
    private const val LATIN_TRUSTED_CONFIDENCE = 0.8f

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    fun warmUp() {
        // Trigger lazy init + model load off the critical path.
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        latin.process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { bmp.recycle() }
    }

    suspend fun recognize(bitmap: Bitmap): Result {
        val image = InputImage.fromBitmap(bitmap, 0)
        var best = score(latin.run(image))
        // Latin output on CJK text is low-confidence garbage; only then pay
        // for the other models, and keep whichever reads the image best.
        if (best.lines.isEmpty() || best.meanConfidence < LATIN_TRUSTED_CONFIDENCE) {
            val others = coroutineScope {
                listOf(chinese, japanese, korean)
                    .map { async { score(it.run(image)) } }
                    .awaitAll()
            }
            others.maxByOrNull { it.score }?.let { if (it.score > best.score) best = it }
        }
        return buildResult(best.lines)
    }

    private suspend fun TextRecognizer.run(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /** Drops icon-like noise and scores the result. */
    private fun score(text: Text): Scored {
        val lines = ArrayList<Line>()
        var score = 0f
        var confSum = 0f
        var confCount = 0
        for ((blockId, block) in text.textBlocks.withIndex()) {
            for (line in block.lines) {
                val words = ArrayList<Pair<String, RectF>>()
                for (e in line.elements) {
                    val box = e.boundingBox ?: continue
                    val t = e.text.trim()
                    // Some ML Kit builds report 0 when confidence is unavailable; don't filter on that.
                    val conf = e.confidence.takeIf { it > 0f }
                    if (conf != null) {
                        confSum += conf
                        confCount++
                    }
                    if (isNoise(t, conf)) continue
                    words.add(t to RectF(box))
                    score += t.count { it.isLetterOrDigit() } * (conf ?: 0.7f)
                }
                if (words.isEmpty()) continue
                // Rebuild the line box from kept words, so a dropped icon doesn't inflate it.
                val lineBox = RectF(words[0].second).apply { words.forEach { union(it.second) } }
                lines.add(Line(lineBox, words, blockId))
            }
        }
        val mean = if (confCount > 0) confSum / confCount else 1f
        return Scored(lines, score, mean)
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
        val lineBlocks = ArrayList<Int>()
        var lineId = 0
        rows.forEachIndexed { rowId, row ->
            for (line in row.sortedBy { it.box.left }) {
                lineBoxes.add(line.box)
                lineBlocks.add(line.blockId)
                for ((t, box) in line.words.sortedBy { it.second.left }) {
                    words.add(Word(t, box, lineId, rowId))
                }
                lineId++
            }
        }
        return Result(words, lineBoxes, lineBlocks)
    }

    private fun sameRow(a: RectF, b: RectF): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height(), b.height())
    }
}
