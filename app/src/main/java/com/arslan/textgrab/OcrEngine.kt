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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

object OcrEngine {

    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val rowId: Int,
    )

    data class Result(
        val words: List<Word>,
        val lineBoxes: List<RectF>,

        val lineBlocks: List<Int>,
    ) {
        val isEmpty get() = words.isEmpty()

        fun textOf(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) {
                if (i > start) {

                    sb.append(if (words[i - 1].rowId != words[i].rowId) "\n" else " ")
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)

        val lineTexts: List<String>
            get() {
                val byLine = words.groupBy { it.lineId }
                return lineBoxes.indices.map { id ->
                    byLine[id]?.joinToString(" ") { it.text }.orEmpty()
                }
            }
    }

    class Reading(val result: Result, val codes: List<QrScanner.Code>)

    private class Line(
        val box: RectF,
        val words: List<Pair<String, RectF>>,
        val blockId: Int,
        val confidence: Float,
    )

    private class Scored(val lines: List<Line>, val score: Float, val meanConfidence: Float)

    private const val MIN_CONFIDENCE = 0.45f

    private const val LATIN_TRUSTED_CONFIDENCE = 0.8f

    private const val UNKNOWN_CONFIDENCE = 0.7f

    private val cjkOptions = listOf(
        ChineseTextRecognizerOptions.Builder().build(),
        JapaneseTextRecognizerOptions.Builder().build(),
        KoreanTextRecognizerOptions.Builder().build(),
    )

    private var latin: TextRecognizer? = null

    private fun latin(): TextRecognizer =
        latin ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { latin = it }

    /** Reads in flight; the models stay loaded until the last one finishes. */
    private var reading = 0
    private var releasePending = false

    /** Loads the models in the background, so the first image does not pay for it. */
    fun warmUp() {
        if (latin != null) return
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val image = InputImage.fromBitmap(bmp, 0)
        QrScanner.warmUp(image)
        latin().process(image)
            .addOnCompleteListener { bmp.recycle() }
    }

    /** Frees the models' memory, or does so once the reads in flight are done. */
    fun release() {
        if (reading > 0) {
            releasePending = true
            return
        }
        releasePending = false
        latin?.close()
        latin = null
        ArabicOcr.release()
        QrScanner.release()
    }

    /** Text and QR codes of [bitmap]; both models run side by side. */
    suspend fun read(bitmap: Bitmap): Reading {
        reading++
        try {
            return coroutineScope {
                // A QR code that fails to scan must not cost the user the text around it.
                val codes = async {
                    try {
                        QrScanner.scan(bitmap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("TextGrab", "QR scan failed", e)
                        emptyList()
                    }
                }
                val result = recognize(bitmap) { codes.await().map { it.box } }
                Reading(result, codes.await())
            }
        } finally {
            reading--
            if (reading == 0 && releasePending) release()
        }
    }

    /** Lines centred inside [exclude] are dropped: the modules of a QR code read as junk text. */
    private suspend fun recognize(bitmap: Bitmap, exclude: suspend () -> List<RectF>): Result {
        val image = InputImage.fromBitmap(bitmap, 0)
        return coroutineScope {
            // ML Kit drops Arabic script without a trace, so a screen of it cannot be told apart
            // from an empty one. Read it alongside when asked to, or when ML Kit is unsure.
            val always = ArabicOcr.isEnabled(App.instance)
            val early = if (always) async { readArabic(bitmap) } else null
            var best = score(latin().run(image))
            var arabic = early
            if (best.lines.isEmpty() || best.meanConfidence < LATIN_TRUSTED_CONFIDENCE) {
                arabic = arabic ?: async { readArabic(bitmap) }
                for (options in cjkOptions) {
                    val candidate = TextRecognition.getClient(options).use { score(it.run(image)) }
                    if (candidate.score > best.score) best = candidate
                }
            }
            val lines = merge(best.lines, arabic?.await().orEmpty())
            val excluded = exclude()
            buildResult(lines.filterNot { line ->
                excluded.any { it.contains(line.box.centerX(), line.box.centerY()) }
            })
        }
    }

    private suspend fun readArabic(bitmap: Bitmap): List<ArabicOcr.Line> = try {
        ArabicOcr.read(App.instance, bitmap)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("TextGrab", "Arabic recognition failed", e)
        emptyList()
    }

    /** Each region keeps whichever recognizer was surer of it, so mixed screens read both. */
    private fun merge(lines: List<Line>, arabic: List<ArabicOcr.Line>): List<Line> {
        if (arabic.isEmpty()) return lines
        val kept = arabic.filter { a ->
            lines.none { overlaps(it.box, a.box) && it.confidence >= a.confidence }
        }
        if (kept.isEmpty()) return lines
        val blockBase = (lines.maxOfOrNull { it.blockId } ?: -1) + 1
        return lines.filterNot { l -> kept.any { overlaps(l.box, it.box) } } +
            kept.map { Line(it.box, it.words, blockBase + it.blockId, it.confidence) }
    }

    private fun overlaps(a: RectF, b: RectF): Boolean {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return false
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return w * h > 0.3f * smaller
    }

    private suspend fun TextRecognizer.run(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun score(text: Text): Scored {
        val lines = ArrayList<Line>()
        var score = 0f
        var confSum = 0f
        var confCount = 0
        for ((blockId, block) in text.textBlocks.withIndex()) {
            for (line in block.lines) {
                val words = ArrayList<Pair<String, RectF>>()
                var lineConf = 0f
                for (e in line.elements) {
                    val box = e.boundingBox ?: continue
                    val t = e.text.trim()

                    val conf = e.confidence.takeIf { it > 0f }
                    if (conf != null) {
                        confSum += conf
                        confCount++
                    }
                    if (isNoise(t, conf)) continue
                    words.add(t to RectF(box))
                    lineConf += conf ?: UNKNOWN_CONFIDENCE
                    score += t.count { it.isLetterOrDigit() } * (conf ?: UNKNOWN_CONFIDENCE)
                }
                if (words.isEmpty()) continue

                val lineBox = RectF(words[0].second).apply { words.forEach { union(it.second) } }
                lines.add(Line(lineBox, words, blockId, lineConf / words.size))
            }
        }
        val mean = if (confCount > 0) confSum / confCount else 1f
        return Scored(lines, score, mean)
    }

    private fun isNoise(t: String, conf: Float?): Boolean {
        if (t.isEmpty()) return true
        if (conf != null && conf < MIN_CONFIDENCE) return true
        val letters = t.count { it.isLetterOrDigit() }

        if (letters == 0 && t.length <= 2) return true

        if (t.length == 1 && conf != null && conf < 0.7f) return true
        return false
    }

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
            // Right-to-left text reads from the right edge, both within a line and across a row.
            val rtl = row.map { isRtl(it.words) }
            val rowRtl = rtl.count { it } * 2 > row.size
            val ordered = row.indices.sortedBy { if (rowRtl) -row[it].box.right else row[it].box.left }
            for (i in ordered) {
                val line = row[i]
                lineBoxes.add(line.box)
                lineBlocks.add(line.blockId)
                val inOrder = if (rtl[i]) line.words.sortedByDescending { it.second.right }
                else line.words.sortedBy { it.second.left }
                for ((t, box) in inOrder) {
                    words.add(Word(t, box, lineId, rowId))
                }
                lineId++
            }
        }
        return Result(words, lineBoxes, lineBlocks)
    }

    private fun isRtl(words: List<Pair<String, RectF>>): Boolean {
        for ((t, _) in words) for (c in t) {
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    private fun sameRow(a: RectF, b: RectF): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height(), b.height())
    }
}
