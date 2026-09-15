package com.arslan.textgrab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders an image with its recognized words and provides iOS-Live-Text-style
 * interaction: tap a word to select it, long-press and drag to sweep,
 * drag the round handles to refine, pinch to zoom, double-tap to toggle zoom.
 *
 * All word boxes are in bitmap pixel space; [imageMatrix] maps them to view space.
 */
class SelectableOcrView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** [text] is null when the selection was cleared. [anchor] is in view coordinates. */
        fun onSelectionChanged(text: String?, anchor: RectF?)
    }

    var listener: Listener? = null

    private var bitmap: Bitmap? = null
    private var result: OcrEngine.Result? = null

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var fitScale = 1f
    private var currentScale = 1f

    // Selection = inclusive word index range, -1 when empty.
    private var selStart = -1
    private var selEnd = -1

    private var dragMode = DragMode.NONE
    private var dragAnchorWord = -1

    private enum class DragMode { NONE, SWEEP, HANDLE_START, HANDLE_END }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 40
        style = Paint.Style.FILL
    }
    private val hintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 90
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x663B82F6
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.FILL
    }
    private val handleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val backgroundPaint = Paint().apply { color = 0xFF101014.toInt() }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val handleRadius get() = dp(7f)
    private val handleTouchRadius get() = dp(26f)

    private var lines: LineIndex? = null

    /** Touch-to-handle offset captured on grab, so the finger never hides the target. */
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    fun setContent(bmp: Bitmap, ocr: OcrEngine.Result) {
        bitmap = bmp
        result = ocr
        lines = LineIndex(ocr.words)
        selStart = -1
        selEnd = -1
        if (width > 0 && height > 0) resetFit()
        invalidate()
    }

    fun clear() {
        bitmap = null
        result = null
        lines = null
        selStart = -1
        selEnd = -1
        invalidate()
    }

    fun selectAll() {
        val r = result ?: return
        if (r.isEmpty) return
        selStart = 0
        selEnd = r.words.size - 1
        notifySelection()
        invalidate()
    }

    fun clearSelection() {
        if (selStart == -1) return
        selStart = -1
        selEnd = -1
        notifySelection()
        invalidate()
    }

    fun hasSelection() = selStart != -1

    val selectedText: String?
        get() {
            val r = result ?: return null
            if (selStart == -1) return null
            return r.textOf(selStart, selEnd)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) resetFit()
        if (selStart != -1) notifySelection()
    }

    private fun resetFit() {
        val bmp = bitmap ?: return
        fitScale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        currentScale = fitScale
        imageMatrix.reset()
        imageMatrix.postScale(fitScale, fitScale)
        imageMatrix.postTranslate(
            (width - bmp.width * fitScale) / 2f,
            (height - bmp.height * fitScale) / 2f
        )
        syncInverse()
    }

    private fun syncInverse() {
        imageMatrix.invert(inverseMatrix)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

        val r = result ?: return
        val tmp = RectF()

        // Subtle hint that text was found and is selectable.
        if (selStart == -1) {
            for (box in r.lineBoxes) {
                tmp.set(box)
                imageMatrix.mapRect(tmp)
                tmp.inset(-dp(2f), -dp(2f))
                val radius = tmp.height() * 0.25f
                canvas.drawRoundRect(tmp, radius, radius, hintPaint)
                canvas.drawRoundRect(tmp, radius, radius, hintStroke)
            }
            return
        }

        // Selection: one rounded rect per line, spanning selected words.
        var i = selStart
        var firstRect: RectF? = null
        var lastRect: RectF? = null
        while (i <= selEnd) {
            val lineId = r.words[i].lineId
            val union = RectF(r.words[i].box)
            var j = i
            while (j + 1 <= selEnd && r.words[j + 1].lineId == lineId) {
                j++
                union.union(r.words[j].box)
            }
            imageMatrix.mapRect(union)
            union.inset(-dp(3f), -dp(3f))
            val radius = dp(4f)
            canvas.drawRoundRect(union, radius, radius, selectionPaint)
            if (firstRect == null) firstRect = RectF(union)
            lastRect = RectF(union)
            i = j + 1
        }

        // iOS-style selection handles: stem + ball.
        firstRect?.let { fr ->
            canvas.drawLine(fr.left, fr.top, fr.left, fr.bottom, handleStemPaint)
            canvas.drawCircle(fr.left, fr.top - handleRadius * 0.7f, handleRadius, handlePaint)
        }
        lastRect?.let { lr ->
            canvas.drawLine(lr.right, lr.top, lr.right, lr.bottom, handleStemPaint)
            canvas.drawCircle(lr.right, lr.bottom + handleRadius * 0.7f, handleRadius, handlePaint)
        }
    }

    // --------------------------------------------------------------- gestures

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (dragMode != DragMode.NONE) return true
                val target = (currentScale * detector.scaleFactor)
                    .coerceIn(fitScale * 0.8f, fitScale * 12f)
                val factor = target / currentScale
                currentScale = target
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampTranslation()
                syncInverse()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float,
            ): Boolean {
                if (dragMode != DragMode.NONE || scaleDetector.isInProgress) return false
                imageMatrix.postTranslate(-dx, -dy)
                clampTranslation()
                syncInverse()
                invalidate()
                if (selStart != -1) notifySelection()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = wordAt(e.x, e.y, forgiving = true)
                if (hit != -1) {
                    selStart = hit
                    selEnd = hit
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    notifySelection()
                } else {
                    clearSelection()
                }
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale > fitScale * 1.4f) fitScale else fitScale * 2.5f
                val factor = target / currentScale
                currentScale = target
                imageMatrix.postScale(factor, factor, e.x, e.y)
                clampTranslation()
                syncInverse()
                invalidate()
                if (selStart != -1) notifySelection()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = wordAt(e.x, e.y, forgiving = true)
                if (hit != -1) {
                    dragMode = DragMode.SWEEP
                    dragAnchorWord = hit
                    selStart = hit
                    selEnd = hit
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    notifySelection()
                    invalidate()
                }
            }
        })

    init {
        gestureDetector.setIsLongpressEnabled(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = when {
                    hitsHandle(event.x, event.y, start = true) -> DragMode.HANDLE_START
                    hitsHandle(event.x, event.y, start = false) -> DragMode.HANDLE_END
                    else -> DragMode.NONE
                }
                if (dragMode != DragMode.NONE) {
                    // Aim at the middle of the handle's word, not at the finger.
                    val start = dragMode == DragMode.HANDLE_START
                    val box = handleWordRect(start)
                    dragOffsetX = (if (start) box.left else box.right) - event.x
                    dragOffsetY = box.centerY() - event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Keep handle drags away from the gesture detectors, otherwise
                    // their long-press timer fires mid-drag and resets the selection.
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode != DragMode.NONE && event.pointerCount == 1) {
                    onSelectionDrag(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    dragMode = DragMode.NONE
                    dragAnchorWord = -1
                    if (selStart != -1) notifySelection()
                    return true
                }
            }
        }
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun onSelectionDrag(x: Float, y: Float) {
        val nearest = if (dragMode == DragMode.SWEEP) nearestWord(x, y)
        else nearestWord(x + dragOffsetX, y + dragOffsetY)
        if (nearest == -1) return
        val oldStart = selStart
        val oldEnd = selEnd
        when (dragMode) {
            DragMode.SWEEP -> {
                selStart = min(dragAnchorWord, nearest)
                selEnd = max(dragAnchorWord, nearest)
            }
            DragMode.HANDLE_START -> {
                if (nearest <= selEnd) selStart = nearest else {
                    // Crossed over the other handle: swap roles.
                    selStart = selEnd
                    selEnd = nearest
                    dragMode = DragMode.HANDLE_END
                }
            }
            DragMode.HANDLE_END -> {
                if (nearest >= selStart) selEnd = nearest else {
                    selEnd = selStart
                    selStart = nearest
                    dragMode = DragMode.HANDLE_START
                }
            }
            DragMode.NONE -> return
        }
        if (selStart != oldStart || selEnd != oldEnd) {
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE
                else HapticFeedbackConstants.CONTEXT_CLICK
            )
            notifySelection()
            invalidate()
        }
    }

    // ------------------------------------------------------------- hit tests

    /** Maps a view point into bitmap space. */
    private fun toBitmapSpace(x: Float, y: Float): Pair<Float, Float> {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return pts[0] to pts[1]
    }

    private fun wordAt(vx: Float, vy: Float, forgiving: Boolean): Int {
        val index = lines ?: return -1
        val (x, y) = toBitmapSpace(vx, vy)
        return index.wordAt(x, y, slop = if (forgiving) dp(6f) / currentScale else 0f)
    }

    private fun nearestWord(vx: Float, vy: Float): Int {
        val index = lines ?: return -1
        val (x, y) = toBitmapSpace(vx, vy)
        return index.nearestWord(x, y)
    }

    /** View-space box of the word under the start or end handle, grown to the selection padding. */
    private fun handleWordRect(start: Boolean): RectF {
        val words = result!!.words
        return RectF(words[if (start) selStart else selEnd].box).also {
            imageMatrix.mapRect(it)
            it.inset(-dp(3f), -dp(3f))
        }
    }

    private fun hitsHandle(x: Float, y: Float, start: Boolean): Boolean {
        if (selStart == -1 || result == null) return false
        val rect = handleWordRect(start)
        val hx = if (start) rect.left else rect.right
        val hy = if (start) rect.top - handleRadius * 0.7f else rect.bottom + handleRadius * 0.7f
        return abs(x - hx) < handleTouchRadius && abs(y - hy) < handleTouchRadius
    }

    // ------------------------------------------------------------ helpers

    private fun clampTranslation() {
        val bmp = bitmap ?: return
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) {
            dx = (width - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
        }
        if (rect.height() <= height) {
            dy = (height - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
        }
        imageMatrix.postTranslate(dx, dy)
    }

    private fun notifySelection() {
        val r = result
        if (r == null || selStart == -1) {
            listener?.onSelectionChanged(null, null)
            return
        }
        val anchor = RectF(r.words[selStart].box)
        for (i in selStart..selEnd) anchor.union(r.words[i].box)
        imageMatrix.mapRect(anchor)
        listener?.onSelectionChanged(r.textOf(selStart, selEnd), anchor)
    }
}
