package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

class BlurredBackdrop(src: Bitmap) {

    private val blurred: Bitmap
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint().apply { color = 0x000000 or (TINT_ALPHA shl 24) }

    init {
        val w = max(1, src.width / DOWNSCALE)
        val h = max(1, src.height / DOWNSCALE)
        val px = IntArray(w * h)
        Bitmap.createScaledBitmap(src, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, radius = 2) }
        blurred = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun draw(canvas: Canvas, width: Int, height: Int, alpha: Float = 1f) {
        if (alpha <= 0f) return
        val scale = max(width.toFloat() / blurred.width, height.toFloat() / blurred.height)
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (width - blurred.width * scale) / 2f,
            (height - blurred.height * scale) / 2f
        )
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        tint.alpha = (alpha * TINT_ALPHA).toInt().coerceIn(0, 255)
        canvas.drawBitmap(blurred, matrix, paint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)
    }

    private companion object {
        const val DOWNSCALE = 24

        const val TINT_ALPHA = 0x59

        fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
            val tmp = IntArray(px.size)
            pass(px, tmp, len = w, lines = h, radius) { y, x -> y * w + x }
            pass(tmp, px, len = h, lines = w, radius) { x, y -> y * w + x }
        }

        inline fun pass(
            src: IntArray, dst: IntArray, len: Int, lines: Int, radius: Int,
            at: (line: Int, i: Int) -> Int,
        ) {
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var r = 0; var g = 0; var b = 0
                    for (k in -radius..radius) {
                        val c = src[at(line, (i + k).coerceIn(0, len - 1))]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    }
                    val n = 2 * radius + 1
                    dst[at(line, i)] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
    }
}
