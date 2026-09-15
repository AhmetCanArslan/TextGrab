package com.arslan.textgrab

import android.graphics.RectF
import kotlin.math.max

/**
 * Line-level lookup over OCR words, all in bitmap pixel space. Used by
 * [SelectableOcrView] for tap and drag hit tests.
 */
class LineIndex(private val words: List<OcrEngine.Word>) {

    /** Word index range and bounds of each recognized line, in reading order. */
    private val lines: List<Pair<IntRange, RectF>> = buildList {
        var i = 0
        while (i < words.size) {
            val box = RectF(words[i].box)
            var j = i
            while (j + 1 < words.size && words[j + 1].lineId == words[i].lineId) {
                j++
                box.union(words[j].box)
            }
            add(i..j to box)
            i = j + 1
        }
    }

    /** Index of the word containing (x, y), grown by [slop] on each side, or -1. */
    fun wordAt(x: Float, y: Float, slop: Float): Int =
        words.indexOfFirst { w ->
            x >= w.box.left - slop && x <= w.box.right + slop &&
                y >= w.box.top - slop && y <= w.box.bottom + slop
        }

    /**
     * Native-style drag target: pick the closest line first (vertical distance
     * dominates, so gaps between lines don't make the selection jump), then the
     * word under x within that line, clamping to its first/last word.
     */
    fun nearestWord(x: Float, y: Float): Int {
        val range = lines.minByOrNull { (_, box) ->
            val dx = distance(x, box.left, box.right)
            val dy = distance(y, box.top, box.bottom)
            dx * dx + dy * dy * 16f
        }?.first ?: return -1
        if (x <= words[range.first].box.right) return range.first
        if (x >= words[range.last].box.left) return range.last
        return range.minBy { distance(x, words[it].box.left, words[it].box.right) }
    }

    private fun distance(v: Float, from: Float, to: Float) = max(0f, max(from - v, v - to))
}
