package com.arslan.textgrab

import android.graphics.RectF
import kotlin.math.max

class LineIndex(private val words: List<OcrEngine.Word>) {

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

    fun wordAt(x: Float, y: Float, slop: Float): Int =
        words.indexOfFirst { w ->
            x >= w.box.left - slop && x <= w.box.right + slop &&
                y >= w.box.top - slop && y <= w.box.bottom + slop
        }

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
