package com.arslan.textgrab

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
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

    /**
     * Per-line translations, indexed like [OcrEngine.Result.lineBoxes]. When set,
     * each translated line is painted over with its translation and selections
     * copy the translated text; null entries keep the original line. A null
     * list shows the original image.
     */
    var translations: List<String?>? = null
        set(value) {
            field = value
            overlayColors = value?.let { computeOverlayColors() }
            overlayStyles = value?.let { computeOverlayStyles(it) }
            if (selStart != -1) notifySelection()
            invalidate()
        }

    /** Background and text color per line, sampled once from the bitmap. */
    private var overlayColors: List<Pair<Int, Int>>? = null

    /** Text size (bitmap pixels) and horizontal scale per line, shared across a paragraph. */
    private var overlayStyles: List<Pair<Float, Float>>? = null

    private var bitmap: Bitmap? = null
    private var result: OcrEngine.Result? = null
    private var lines: LineIndex? = null
    private var backdrop: BlurredBackdrop? = null

    /**
     * How far the capture transition has run: 0 draws the image as a pixel-exact
     * copy of the screen it was taken from, 1 is the resting preview. Anything in
     * between is the animation, during which touch input is ignored.
     */
    private var captureProgress = 1f
    private var captureAnimator: ValueAnimator? = null

    /** Set by [playCaptureEntry] when the view has no size to animate in yet. */
    private var entryPending = false

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
    private val overlayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
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

    /**
     * Shows [bmp] before recognition has run. The image is pannable and
     * zoomable right away; words light up once [setContent] supplies them.
     */
    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        result = null
        lines = null
        backdrop = if (capturePreview) BlurredBackdrop(bmp) else null
        selStart = -1
        selEnd = -1
        translations = null
        if (width > 0 && height > 0) resetFit()
        invalidate()
    }

    /** Attaches [ocr] to an image, keeping the current zoom when it is already shown. */
    fun setContent(bmp: Bitmap, ocr: OcrEngine.Result) {
        val sameImage = bitmap === bmp
        bitmap = bmp
        result = ocr
        lines = LineIndex(ocr.words)
        if (!sameImage) backdrop = if (capturePreview) BlurredBackdrop(bmp) else null
        selStart = -1
        selEnd = -1
        translations = null
        if (!sameImage && width > 0 && height > 0) resetFit()
        invalidate()
    }

    fun clear() {
        captureAnimator?.cancel()
        captureAnimator = null
        captureProgress = 1f
        entryPending = false
        bitmap = null
        result = null
        lines = null
        backdrop = null
        selStart = -1
        selEnd = -1
        translations = null
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
            val t = translations ?: return r.textOf(selStart, selEnd)
            // Whole translated lines: word boundaries don't survive translation.
            val originals = r.lineTexts
            val sb = StringBuilder()
            var prev: OcrEngine.Word? = null
            for (i in selStart..selEnd) {
                val w = r.words[i]
                if (prev != null && prev.lineId == w.lineId) continue
                if (prev != null) sb.append(if (prev.rowId != w.rowId) "\n" else " ")
                sb.append(t.getOrNull(w.lineId) ?: originals[w.lineId])
                prev = w
            }
            return sb.toString()
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

    /** A placement of the image in view space; the matrix is a scale plus a translate. */
    private class Fit(val scale: Float, val tx: Float, val ty: Float)

    /** Where the image comes to rest: shrunk below the toolbar for captures. */
    private fun restFit(bmp: Bitmap): Fit {
        val availHeight = max(1f, height - effectiveTopInset)
        val scale = min(width.toFloat() / bmp.width, availHeight / bmp.height) * fitFraction
        return Fit(scale, (width - bmp.width * scale) / 2f, fittedTop(bmp.height * scale))
    }

    /**
     * The placement that makes the image indistinguishable from the screen it
     * was captured from: covering the view, centred, at native scale.
     */
    private fun coverFit(bmp: Bitmap): Fit {
        val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        return Fit(
            scale,
            (width - bmp.width * scale) / 2f,
            (height - bmp.height * scale) / 2f,
        )
    }

    private fun resetFit() {
        val bmp = bitmap ?: return
        val rest = restFit(bmp)
        // Zoom limits always refer to the resting size, never to a mid-animation one.
        fitScale = rest.scale
        val fit = if (captureProgress >= 1f) rest else {
            val cover = coverFit(bmp)
            val p = captureProgress
            Fit(
                cover.scale + (rest.scale - cover.scale) * p,
                cover.tx + (rest.tx - cover.tx) * p,
                cover.ty + (rest.ty - cover.ty) * p,
            )
        }
        currentScale = fit.scale
        imageMatrix.reset()
        imageMatrix.postScale(fit.scale, fit.scale)
        imageMatrix.postTranslate(fit.tx, fit.ty)
        syncInverse()
    }

    // ------------------------------------------------------- capture transition

    /**
     * Plays the capture transition. The screenshot is of the very screen behind
     * this window, so starting at [coverFit] means the first frame is
     * indistinguishable from what the user was already looking at: the capture
     * reads as that screen freezing and settling, not as an app opening over it.
     *
     * Deferred until the view has been measured and has put one frame on screen.
     */
    fun playCaptureEntry() {
        captureAnimator?.cancel()
        captureProgress = 0f
        entryPending = true
        if (width > 0 && height > 0) resetFit()
        invalidate()
    }

    /** Reverses the entry, leaving the screen exactly as the capture found it. */
    fun playCaptureExit(onEnd: () -> Unit) {
        if (bitmap == null || width == 0 || height == 0 || captureProgress <= 0f) {
            onEnd()
            return
        }
        entryPending = false
        animateCaptureTo(0f, EMPHASIZED_ACCELERATE, onEnd)
    }

    /** True while the transition owns the matrix, so gestures must keep off it. */
    val isAnimatingCapture get() = captureAnimator != null

    /**
     * Whether there is a played-in capture to play back out. The exit runs from
     * the resting placement, so a zoomed image leaves the ordinary way instead
     * of snapping back to fit first.
     */
    val canPlayCaptureExit
        get() = bitmap != null && captureProgress > 0f && width > 0 && height > 0 &&
            abs(currentScale - fitScale) < ZOOM_EPSILON

    private fun animateCaptureTo(
        target: Float,
        easing: PathInterpolator,
        onEnd: () -> Unit = {},
    ) {
        captureAnimator?.cancel()
        val start = captureProgress
        var cancelled = false
        captureAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = (CAPTURE_TRANSITION_MS * abs(target - start)).toLong().coerceAtLeast(1L)
            interpolator = easing
            addUpdateListener {
                captureProgress = it.animatedValue as Float
                resetFit()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    captureAnimator = null
                    if (cancelled) return
                    captureProgress = target
                    resetFit()
                    invalidate()
                    onEnd()
                }
            })
            start()
        }
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
        // Opaque base under the backdrop: while the backdrop is still fading in,
        // the margins must not show the window through.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (bmp != null && blur != null) blur.draw(canvas, width, height, captureProgress)
        if (bmp == null) return

        if (blur != null) {
            // Corners round off as the image pulls away from the screen edges;
            // at progress 0 it is a square, full-bleed copy of the screen.
            val radius = dp(PREVIEW_CORNER_RADIUS_DP) * captureProgress
            clipPath.rewind()
            clipPath.addRoundRect(mappedImageRect()!!, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        }

        // Start the entry only once the identity frame has actually been drawn,
        // so the transition never begins behind the splash or a blank window.
        if (entryPending) {
            entryPending = false
            post { if (captureProgress < 1f) animateCaptureTo(1f, EMPHASIZED_DECELERATE) }
        }

        val r = result ?: return
        val t = translations
        val colors = overlayColors
        val styles = overlayStyles
        if (t != null && colors != null && styles != null) {
            drawTranslations(canvas, r, t, colors, styles)
            if (selStart != -1) drawSelection(canvas, r)
        } else if (selStart == -1) drawHints(canvas, r) else drawSelection(canvas, r)
    }

    /** Covers each translated line with its sampled background and draws the translation. */
    private fun drawTranslations(
        canvas: Canvas,
        r: OcrEngine.Result,
        texts: List<String?>,
        colors: List<Pair<Int, Int>>,
        styles: List<Pair<Float, Float>>,
    ) {
        canvas.save()
        canvas.concat(imageMatrix)
        r.lineBoxes.forEachIndexed { i, box ->
            val text = texts.getOrNull(i) ?: return@forEachIndexed
            val (bg, fg) = colors[i]
            val pad = box.height() * 0.12f
            tmpRect.set(box)
            tmpRect.inset(-pad, -pad)
            overlayBgPaint.color = bg
            canvas.drawRoundRect(tmpRect, pad, pad, overlayBgPaint)
            if (text.isEmpty()) return@forEachIndexed

            overlayTextPaint.color = fg
            overlayTextPaint.textSize = styles[i].first
            overlayTextPaint.textScaleX = styles[i].second
            val fm = overlayTextPaint.fontMetrics
            val baseline = box.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, box.left, baseline, overlayTextPaint)
        }
        canvas.restore()
    }

    /**
     * Shrinks text that doesn't fit its line (down to [MIN_TEXT_SHRINK]), uses the
     * smallest size of each paragraph for all its lines so it reads as one piece,
     * then condenses any line that still overflows.
     */
    private fun computeOverlayStyles(texts: List<String?>): List<Pair<Float, Float>> {
        val r = result ?: return emptyList()
        val paint = Paint(overlayTextPaint)
        val sizes = r.lineBoxes.mapIndexed { i, box ->
            val text = texts.getOrNull(i)
            val base = box.height() * 0.8f
            if (text.isNullOrEmpty()) return@mapIndexed Float.MAX_VALUE
            paint.textSize = base
            val w = paint.measureText(text)
            if (w > box.width()) base * max(box.width() / w, MIN_TEXT_SHRINK) else base
        }
        val blockSize = HashMap<Int, Float>()
        sizes.forEachIndexed { i, s ->
            val b = r.lineBlocks[i]
            blockSize[b] = min(blockSize[b] ?: Float.MAX_VALUE, s)
        }
        return r.lineBoxes.mapIndexed { i, box ->
            val text = texts.getOrNull(i)
            if (text.isNullOrEmpty()) return@mapIndexed 0f to 1f
            val size = min(blockSize.getValue(r.lineBlocks[i]), box.height() * 0.8f)
            paint.textSize = size
            val w = paint.measureText(text)
            size to if (w > box.width()) max(box.width() / w, MIN_TEXT_SCALE_X) else 1f
        }
    }

    private fun computeOverlayColors(): List<Pair<Int, Int>> {
        val src = bitmap ?: return emptyList()
        val r = result ?: return emptyList()
        // Hardware bitmaps (instant captures) can't be read pixel by pixel.
        val bmp = if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE)
            src.copy(Bitmap.Config.ARGB_8888, false) else src
        return r.lineBoxes.map { box ->
            val bg = borderColor(bmp, box)
            val luminance = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
            bg to if (luminance > 140) 0xFF202124.toInt() else Color.WHITE
        }.also { if (bmp !== src) bmp.recycle() }
    }

    /** Average color of a thin ring just outside [box], which is usually plain background. */
    private fun borderColor(bmp: Bitmap, box: RectF): Int {
        val margin = max(2f, box.height() * 0.15f)
        val left = (box.left - margin).toInt().coerceIn(0, bmp.width - 1)
        val right = (box.right + margin).toInt().coerceIn(0, bmp.width - 1)
        val top = (box.top - margin).toInt().coerceIn(0, bmp.height - 1)
        val bottom = (box.bottom + margin).toInt().coerceIn(0, bmp.height - 1)
        var red = 0L; var green = 0L; var blue = 0L; var n = 0
        fun sample(x: Int, y: Int) {
            val c = bmp.getPixel(x, y)
            red += Color.red(c); green += Color.green(c); blue += Color.blue(c); n++
        }
        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 6)
        for (x in left..right step stepX) { sample(x, top); sample(x, bottom) }
        for (y in top..bottom step stepY) { sample(left, y); sample(right, y) }
        return Color.rgb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
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
        // The transition owns the matrix while it runs; a stray touch mid-flight
        // would otherwise leave the image stranded between the two placements.
        if (isAnimatingCapture) return true

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
        listener?.onSelectionChanged(selectedText, anchor)
    }

    companion object {
        /**
         * Length of the capture transition. Long enough to read as the screen
         * settling into place, short enough that nothing is ever waited on.
         */
        const val CAPTURE_TRANSITION_MS = 420f

        /** Below this, a scale counts as untouched by the user. */
        private const val ZOOM_EPSILON = 0.001f

        /** Material 3 emphasized easing: a quick start that glides into rest. */
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        /** Its mirror, for leaving: eases out of rest, then goes quickly. */
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        /** Smallest text size, relative to the line height, before condensing instead. */
        private const val MIN_TEXT_SHRINK = 0.6f
        private const val MIN_TEXT_SCALE_X = 0.7f
        /** Share of the free area a capture preview fills. */
        private const val CAPTURE_FIT_FRACTION = 0.87f
        private const val PREVIEW_CORNER_RADIUS_DP = 24f
    }
}
