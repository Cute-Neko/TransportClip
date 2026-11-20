package io.catgirl.transportclip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 冻结截图 + 矩形框选视图。
 * 触摸坐标始终在 view 空间,与 bitmap 像素之间通过 imageMatrix/inverseMatrix 换算,
 * 因此截图分辨率与屏幕分辨率不一致时也能正确裁剪。
 * 单指:拖出新框 / 框内移动 / 四角调整;双指:捏合缩放 + 平移(公式细节放大用)。
 */
class CropView(context: Context, val bitmap: Bitmap) : View(context) {

    private val fitMatrix = Matrix()
    private val userMatrix = Matrix()
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val bitmapRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())

    /** 整个物理屏幕在本视图坐标系中的位置(窗口未铺满屏幕时用于对齐) */
    private var displayFrame = RectF()

    private var sel: RectF? = null
    private var selAtDown: RectF? = null

    private enum class Mode { NONE, DRAWING, MOVING, RESIZING, ZOOM }
    private var mode = Mode.NONE
    private var activeCorner = -1 // 0=左上 1=右上 2=右下 3=左下
    private var downX = 0f
    private var downY = 0f

    private val touchSlop = 12f
    private val cornerHit = 88f // 四角命中半径(px)
    private val minSize = 72f

    private val density = resources.displayMetrics.density
    private val selectionColor = 0xFFB0F5D8.toInt()
    private val dimPaint = Paint().apply { color = 0x990C1813.toInt() }
    private val shadePath = Path()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = selectionColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = selectionColor
    }
    private val cornerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF164C3A.toInt()
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userMatrix.postScale(detector.scaleFactor, detector.scaleFactor,
                    detector.focusX, detector.focusY)
                clampUserMatrix()
                updateMatrix()
                return true
            }
        }
    )
    private var zoomFocusX = 0f
    private var zoomFocusY = 0f

    var onSelectionChanged: (() -> Unit)? = null

    fun setDisplayFrame(frame: RectF) {
        displayFrame = frame
        updateMatrix()
    }

    private fun updateMatrix() {
        if (width == 0 || height == 0) return
        val dst = if (displayFrame.isEmpty)
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        else
            displayFrame
        fitMatrix.setRectToRect(bitmapRect, dst, Matrix.ScaleToFit.CENTER)
        imageMatrix.set(fitMatrix)
        imageMatrix.postConcat(userMatrix)
        imageMatrix.invert(inverseMatrix)
        invalidate()
    }

    /** 限制缩放 1~6 倍,并保证图片不会平移出视野 */
    private fun clampUserMatrix() {
        val v = FloatArray(9)
        userMatrix.getValues(v)
        val s = v[Matrix.MSCALE_X]
        if (s > 6f) userMatrix.postScale(6f / s, 6f / s, width / 2f, height / 2f)
        if (s < 1f) userMatrix.postScale(1f / s, 1f / s, width / 2f, height / 2f)

        val m = Matrix(fitMatrix)
        m.postConcat(userMatrix)
        val mapped = RectF(bitmapRect)
        m.mapRect(mapped)
        val vw = width.toFloat(); val vh = height.toFloat()
        var dx = 0f; var dy = 0f
        if (mapped.width() >= vw - 0.5f) {
            if (mapped.left > 0) dx = -mapped.left
            if (mapped.right < vw) dx = vw - mapped.right
        } else {
            dx = (vw - mapped.width()) / 2f - mapped.left
        }
        if (mapped.height() >= vh - 0.5f) {
            if (mapped.top > 0) dy = -mapped.top
            if (mapped.bottom < vh) dy = vh - mapped.bottom
        } else {
            dy = (vh - mapped.height()) / 2f - mapped.top
        }
        userMatrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    val hasSelection: Boolean get() = sel != null

    /** 返回 bitmap 像素坐标的选区,null 表示没有有效选区 */
    fun getCropRect(): RectF? {
        val s = sel ?: return null
        val r = RectF(s)
        inverseMatrix.mapRect(r)
        r.left = max(0f, r.left).toInt().toFloat()
        r.top = max(0f, r.top).toInt().toFloat()
        r.right = min(bitmap.width.toFloat(), r.right).toInt().toFloat()
        r.bottom = min(bitmap.height.toFloat(), r.bottom).toInt().toFloat()
        return if (r.width() >= 8 && r.height() >= 8) r else null
    }

    fun clearSelection() {
        sel = null
        mode = Mode.NONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(bitmap, imageMatrix, bitmapPaint)

        val s = sel
        if (s == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        } else {
            shadePath.rewind()
            shadePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            shadePath.addRect(s, Path.Direction.CCW)
            canvas.drawPath(shadePath, dimPaint)
            canvas.drawRect(s, borderPaint)
            for (i in 0..3) {
                val x = if (i == 0 || i == 3) s.left else s.right
                val y = if (i < 2) s.top else s.bottom
                canvas.drawCircle(x, y, 6f * density, cornerOutlinePaint)
                canvas.drawCircle(x, y, 4.5f * density, cornerPaint)
            }
        }
    }

    private fun hitCorner(x: Float, y: Float, s: RectF): Int {
        val cs = arrayOf(
            s.left to s.top, s.right to s.top,
            s.right to s.bottom, s.left to s.bottom
        )
        for (i in cs.indices) {
            if (abs(x - cs[i].first) < cornerHit && abs(y - cs[i].second) < cornerHit) return i
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下:进入缩放模式,丢弃本次单指操作
                if (event.pointerCount == 2) {
                    mode = Mode.ZOOM
                    zoomFocusX = (event.getX(0) + event.getX(1)) / 2f
                    zoomFocusY = (event.getY(0) + event.getY(1)) / 2f
                    sel = null
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.ZOOM) {
                    if (event.pointerCount >= 2) {
                        val fx = (event.getX(0) + event.getX(1)) / 2f
                        val fy = (event.getY(0) + event.getY(1)) / 2f
                        userMatrix.postTranslate(fx - zoomFocusX, fy - zoomFocusY)
                        zoomFocusX = fx; zoomFocusY = fy
                        clampUserMatrix()
                        updateMatrix()
                    }
                    return true
                }
                val s = sel ?: return true
                when (mode) {
                    Mode.DRAWING -> {
                        s.set(min(downX, event.x), min(downY, event.y),
                            max(downX, event.x), max(downY, event.y))
                    }
                    Mode.MOVING -> {
                        val d = selAtDown ?: return true
                        val dx = event.x - downX
                        val dy = event.y - downY
                        val w = d.width(); val h = d.height()
                        s.left = d.left + dx; s.top = d.top + dy
                        s.right = s.left + w; s.bottom = s.top + h
                        clampSel(s)
                    }
                    Mode.RESIZING -> {
                        val d = selAtDown ?: return true
                        when (activeCorner) {
                            0 -> s.set(min(event.x, d.right - minSize), min(event.y, d.bottom - minSize), d.right, d.bottom)
                            1 -> s.set(d.left, min(event.y, d.bottom - minSize), max(event.x, d.left + minSize), d.bottom)
                            2 -> s.set(d.left, d.top, max(event.x, d.left + minSize), max(event.y, d.top + minSize))
                            3 -> s.set(min(event.x, d.right - minSize), d.top, d.right, max(event.y, d.top + minSize))
                        }
                        clampSel(s)
                    }
                    Mode.NONE, Mode.ZOOM -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 1 && mode != Mode.ZOOM) {
                    val s = sel
                    if (s != null && mode == Mode.DRAWING &&
                        (s.width() < touchSlop * 2 || s.height() < touchSlop * 2)
                    ) {
                        sel = null // 选区过小视为轻点取消
                        onSelectionChanged?.invoke()
                    }
                }
                if (event.pointerCount <= 1) mode = Mode.NONE
                invalidate()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val s = sel
                if (s == null) {
                    mode = Mode.DRAWING
                    sel = RectF(event.x, event.y, event.x, event.y)
                } else {
                    activeCorner = hitCorner(event.x, event.y, s)
                    mode = if (activeCorner >= 0) Mode.RESIZING else Mode.MOVING
                    selAtDown = RectF(s)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampSel(s: RectF) {
        val vw = width.toFloat(); val vh = height.toFloat()
        val w = s.width().coerceAtLeast(minSize)
        val h = s.height().coerceAtLeast(minSize)
        s.left = s.left.coerceIn(0f, vw - w)
        s.right = s.left + w
        s.top = s.top.coerceIn(0f, vh - h)
        s.bottom = s.top + h
    }
}
