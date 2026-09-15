package com.arslan.textgrab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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

    /**
     * Capture preview style: the image is shrunk below [topInset] with rounded
     * corners over a blurred copy of itself, so text at the screen edges stays
     * easy to reach. Otherwise the image fills the view on a dark background.
     */
    var capturePreview = false
        set(value) {
            if (field == value) return
            field = value
            backdrop = bitmap?.takeIf { value }?.let { BlurredBackdrop(it) }
            refit()
        }

    /** Space reserved at the top for the toolbar; only applies to [capturePreview]. */
    var topInset = 0f
        set(value) {
            if (field == value) return
            field = value
            refit()
        }

    private var bitmap: Bitmap? = null
    private var result: OcrEngine.Result? = null
    private var lines: LineIndex? = null
    private var backdrop: BlurredBackdrop? = null

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var fitScale = 1f
    private var currentScale = 1f

    // Selection = inclusive word index range, -1 when empty.
    private var selStart = -1
    private var selEnd = -1

    private var dragMode = DragMode.NONE
    private var dragAnchorWord = -1

    /** Touch-to-handle offset captured on grab, so the finger never hides the target. */
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

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
    private val clipPath = Path()
    private val tmpRect = RectF()

    private val handleRadius get() = dp(7f)
    private val handleTouchRadius get() = dp(26f)

    // Layout derived from the preview style.
    private val fitFraction get() = if (capturePreview) CAPTURE_FIT_FRACTION else 1f
    private val effectiveTopInset get() = if (capturePreview) topInset else 0f
    /** Share of the free vertical space placed above the image: 0.5 centers it. */
    private val topGapShare get() = if (capturePreview) 0.2f else 0.5f

    fun setContent(bmp: Bitmap, ocr: OcrEngine.Result) {
        bitmap = bmp
        result = ocr
        lines = LineIndex(ocr.words)
        backdrop = if (capturePreview) BlurredBackdrop(bmp) else null
        selStart = -1
        selEnd = -1
        if (width > 0 && height > 0) resetFit()
        invalidate()
    }

    fun clear() {
        bitmap = null
        result = null
        lines = null
        backdrop = null
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

    // ----------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) resetFit()
        if (selStart != -1) notifySelection()
    }

    private fun refit() {
        if (bitmap == null || width == 0 || height == 0) return
        resetFit()
        if (selStart != -1) notifySelection()
        invalidate()
    }

    private fun resetFit() {
        val bmp = bitmap ?: return
        val availHeight = max(1f, height - effectiveTopInset)
        fitScale = min(width.toFloat() / bmp.width, availHeight / bmp.height) * fitFraction
        currentScale = fitScale
        imageMatrix.reset()
        imageMatrix.postScale(fitScale, fitScale)
        imageMatrix.postTranslate((width - bmp.width * fitScale) / 2f, fittedTop(bmp.height * fitScale))
        syncInverse()
    }

    /** Top edge for content of [contentHeight] that fits in the space below the inset. */
    private fun fittedTop(contentHeight: Float) =
        effectiveTopInset + (height - effectiveTopInset - contentHeight) * topGapShare

    /** Keeps the image centered while it fits, and its edges on screen once zoomed past it. */
    private fun clampTranslation() {
        val rect = mappedImageRect() ?: return
        val dx = if (rect.width() <= width) {
            (width - rect.width()) / 2f - rect.left
        } else when {
            rect.left > 0 -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        val inset = effectiveTopInset
        val dy = if (rect.height() <= height - inset) {
            fittedTop(rect.height()) - rect.top
        } else when {
            rect.top > inset -> inset - rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        imageMatrix.postTranslate(dx, dy)
    }

    /** Re-clamps after any matrix change and refreshes everything that depends on it. */
    private fun onTransformed() {
        clampTranslation()
        syncInverse()
        invalidate()
        if (selStart != -1) notifySelection()
    }

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val factor = target / currentScale
        currentScale = target
        imageMatrix.postScale(factor, factor, focusX, focusY)
        onTransformed()
    }

    private fun syncInverse() {
        imageMatrix.invert(inverseMatrix)
    }

    private fun mappedImageRect(): RectF? {
        val bmp = bitmap ?: return null
        return RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()).also { imageMatrix.mapRect(it) }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        val blur = backdrop
        if (bmp != null && blur != null) blur.draw(canvas, width, height)
        else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (bmp == null) return

        if (blur != null) {
            val radius = dp(PREVIEW_CORNER_RADIUS_DP)
            clipPath.rewind()
            clipPath.addRoundRect(mappedImageRect()!!, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        }

        val r = result ?: return
        if (selStart == -1) drawHints(canvas, r) else drawSelection(canvas, r)
    }

    /** Subtle hint that text was found and is selectable. */
    private fun drawHints(canvas: Canvas, r: OcrEngine.Result) {
        for (box in r.lineBoxes) {
            tmpRect.set(box)
            imageMatrix.mapRect(tmpRect)
            tmpRect.inset(-dp(2f), -dp(2f))
            val radius = tmpRect.height() * 0.25f
            canvas.drawRoundRect(tmpRect, radius, radius, hintPaint)
            canvas.drawRoundRect(tmpRect, radius, radius, hintStroke)
        }
    }

    /** One rounded rect per line spanning the selected words, plus iOS-style handles. */
    private fun drawSelection(canvas: Canvas, r: OcrEngine.Result) {
        var first: RectF? = null
        var last: RectF? = null
        var i = selStart
        while (i <= selEnd) {
            val union = RectF(r.words[i].box)
            var j = i
            while (j + 1 <= selEnd && r.words[j + 1].lineId == r.words[i].lineId) {
                union.union(r.words[++j].box)
            }
            imageMatrix.mapRect(union)
            union.inset(-dp(3f), -dp(3f))
            canvas.drawRoundRect(union, dp(4f), dp(4f), selectionPaint)
            if (first == null) first = union
            last = union
            i = j + 1
        }
        first?.let {
            canvas.drawLine(it.left, it.top, it.left, it.bottom, handleStemPaint)
            canvas.drawCircle(it.left, it.top - handleRadius * 0.7f, handleRadius, handlePaint)
        }
        last?.let {
            canvas.drawLine(it.right, it.top, it.right, it.bottom, handleStemPaint)
            canvas.drawCircle(it.right, it.bottom + handleRadius * 0.7f, handleRadius, handlePaint)
        }
    }

    // --------------------------------------------------------------- gestures

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (dragMode != DragMode.NONE) return true
                val target = (currentScale * detector.scaleFactor)
                    .coerceIn(fitScale * 0.8f, fitScale * 12f)
                zoomTo(target, detector.focusX, detector.focusY)
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
                onTransformed()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = wordAt(e.x, e.y)
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
                zoomTo(if (currentScale > fitScale * 1.4f) fitScale else fitScale * 2.5f, e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = wordAt(e.x, e.y)
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
    private fun toBitmapSpace(x: Float, y: Float): FloatArray =
        floatArrayOf(x, y).also { inverseMatrix.mapPoints(it) }

    private fun wordAt(vx: Float, vy: Float): Int {
        val index = lines ?: return -1
        val (x, y) = toBitmapSpace(vx, vy)
        return index.wordAt(x, y, slop = dp(6f) / currentScale)
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

    private companion object {
        /** Share of the free area a capture preview fills. */
        const val CAPTURE_FIT_FRACTION = 0.87f
        const val PREVIEW_CORNER_RADIUS_DP = 24f
    }
}
