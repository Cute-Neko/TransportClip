package io.catgirl.transportclip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.max
import kotlin.math.min

/**
 * 基于 Jetpack Ink 1.0.0 的临时手写板:
 * - InProgressStrokesView 前缓冲低延迟渲染正在书写的笔画,抬笔不闪烁
 * - MotionEventPredictor 运动预测,笔迹紧跟笔尖
 * - 仅 S Pen(触控笔)落笔,手指与手掌全部忽略,防误触
 * - 按住 S Pen 笔身按钮 = 整笔画橡皮擦,基于真实笔迹输入点碰撞检测
 * - 完成时按笔迹包围盒自动裁剪导出,白底彩色笔迹,不含界面元素
 */
internal class InkPadView(context: Context, private val style: PadStyle) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val paper = context.getColor(R.color.tc_pad_paper)
    private val darkPaper = paper != Color.WHITE
    private val finished = FinishedLayer(context, darkPaper)
    private val inProgress = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)

    /** 保留 S Pen 笔压，根据预设调整颜色和宽度。 */
    private val brush: Brush = Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePen(),
        colorIntArgb = if (darkPaper) previewColor(style.penColor.argb) else style.penColor.argb,
        size = style.penWidth * density,
        epsilon = 0.1f,
    )

    private var activePointerId = -1
    private var erasing = false
    private val eraseRadius = style.eraserRadius * density
    private var awaitingHandoff = 0
    private var afterHandoff: ((Snapshot?) -> Unit)? = null

    init {
        setBackgroundColor(paper)
        addView(finished, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(inProgress, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                finished.addStrokes(strokes.values)
                inProgress.removeFinishedStrokes(strokes.keys)
                awaitingHandoff = (awaitingHandoff - strokes.size).coerceAtLeast(0)
                if (awaitingHandoff == 0) {
                    val callback = afterHandoff
                    afterHandoff = null
                    callback?.let { ready -> post { ready(snapshot()) } }
                }
            }
        })
    }

    val hasContent: Boolean get() = !finished.isEmpty || awaitingHandoff > 0

    fun setDraft(bitmap: Bitmap, legacy: Boolean) = finished.setDraft(bitmap, legacy)
    // ---------------- 输入 ----------------

    private fun isStylus(e: MotionEvent, index: Int): Boolean = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        else -> false
    }

    /** S Pen 笔身按钮(BUTTON_STYLUS_PRIMARY / SECONDARY) */
    private fun eraserButtonHeld(e: MotionEvent): Boolean =
        (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val buttonHeld = eraserButtonHeld(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)
                if (!isStylus(event, pointerIndex)) return false // 手指/手掌不产生笔迹
                if (buttonHeld) {
                    erasing = true
                    eraseAt(event.x, event.y)
                } else {
                    erasing = false
                    inProgress.startStroke(event, pointerId, brush)
                    activePointerId = pointerId
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 笔键状态中途变化也要正确流转,避免卡在错误状态
                if (activePointerId != -1 && buttonHeld && !erasing) {
                    // 书写中按下笔键:丢弃当前笔画,转橡皮
                    inProgress.cancelStroke(event, activePointerId)
                    activePointerId = -1
                    erasing = true
                } else if (activePointerId == -1 && !buttonHeld && erasing) {
                    // 擦除中松开笔键后等待下一次落笔。
                    erasing = false
                }
                if (erasing) {
                    eraseAt(event.x, event.y)
                    return true
                }
                if (activePointerId == -1) return true
                val predicted = predictor.predict()
                try {
                    inProgress.addToStroke(event, activePointerId, predicted)
                } finally {
                    predicted?.recycle()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId != -1) {
                    awaitingHandoff++
                    if (!inProgress.finishStroke(event, activePointerId)) awaitingHandoff--
                }
                activePointerId = -1
                erasing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId != -1) {
                    inProgress.cancelStroke(event, activePointerId)
                }
                activePointerId = -1
                erasing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ---------------- 橡皮擦 ----------------

    /** 整笔画橡皮:与真实笔迹输入点做距离碰撞,不误删未接触的笔画 */
    private fun eraseAt(x: Float, y: Float) {
        val r2 = eraseRadius * eraseRadius
        val hit = finished.strokes().filter { s ->
            val inputs = s.inputs
            for (i in 0 until inputs.size) {
                val p = inputs.get(i)
                val dx = p.x - x
                val dy = p.y - y
                if (dx * dx + dy * dy <= r2) return@filter true
            }
            false
        }
        if (hit.isNotEmpty()) finished.removeStrokes(hit)
    }

    // ---------------- 导出 ----------------

    /** 只在主线程捕获不可变笔迹，位图分配和渲染可在后台完成。 */
    fun snapshot(): Snapshot? = finished.snapshot(width, height, density, style.penColor.argb)

    /** 快速点完成时，等刚抬起的最后一笔交给干笔迹层。 */
    fun snapshotAfterHandoff(onReady: (Snapshot?) -> Unit) {
        if (awaitingHandoff == 0) {
            onReady(snapshot())
            return
        }
        afterHandoff = onReady
        if (activePointerId != -1) inProgress.cancelUnfinishedStrokes()
    }

    class Snapshot internal constructor(
        private val strokes: List<Stroke>,
        private val draft: Bitmap?,
        private val legacyDraft: Boolean,
        private val width: Int,
        private val height: Int,
        private val density: Float,
        private val exportColor: Int
    ) {
        fun render(cropped: Boolean): Bitmap {
            val area = if (cropped) cropBounds() else RectF(0f, 0f, width.toFloat(), height.toFloat())
            val left = area.left.toInt()
            val top = area.top.toInt()
            val w = kotlin.math.ceil(area.right).toInt() - left
            val h = kotlin.math.ceil(area.bottom).toInt() - top
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (cropped) canvas.drawColor(Color.WHITE)
            canvas.translate(-left.toFloat(), -top.toFloat())
            draft?.let { drawDraft(canvas, it, width, height, draftPaint(legacyDraft, false)) }
            if (strokes.isNotEmpty()) {
                val renderer = CanvasStrokeRenderer.create()
                val matrix = Matrix()
                val brush = strokes.first().brush
                val exportBrush = if (brush.colorIntArgb == exportColor) brush
                    else brush.copyWithColorIntArgb(exportColor)
                strokes.forEach { stroke ->
                    renderer.draw(canvas, if (exportBrush === brush) stroke else stroke.copy(exportBrush), matrix)
                }
            }
            return bitmap
        }

        private fun cropBounds(): RectF {
            if (draft != null) return RectF(0f, 0f, width.toFloat(), height.toFloat())
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (stroke in strokes) {
                val inputs = stroke.inputs
                for (i in 0 until inputs.size) {
                    val input = inputs.get(i)
                    left = min(left, input.x)
                    top = min(top, input.y)
                    right = max(right, input.x)
                    bottom = max(bottom, input.y)
                }
            }
            val padding = strokes.first().brush.size + 12f * density
            val minSide = 96f * density
            fun axis(start: Float, end: Float, available: Int): Pair<Float, Float> {
                val length = max(end - start + 2 * padding, minSide).coerceAtMost(available.toFloat())
                val first = ((start + end - length) / 2).coerceIn(0f, available - length)
                return first to (first + length).coerceAtMost(available.toFloat())
            }
            if (!left.isFinite()) return RectF(0f, 0f, width.toFloat(), height.toFloat())
            val (x1, x2) = axis(left, right, width)
            val (y1, y2) = axis(top, bottom, height)
            return RectF(x1, y1, x2, y2)
        }
    }

    // ---------------- 干笔迹层 ----------------

    private class FinishedLayer(context: Context, private val darkPaper: Boolean) : View(context) {
        private val strokes = mutableListOf<Stroke>()
        private val renderer = CanvasStrokeRenderer.create()
        private val identity = Matrix()
        private var draft: Bitmap? = null
        private var legacyDraft = false
        private var previewPaint = draftPaint(false, darkPaper)

        val isEmpty: Boolean get() = strokes.isEmpty() && draft == null

        fun addStrokes(list: Collection<Stroke>) {
            strokes.addAll(list)
            invalidate()
        }

        fun removeStrokes(list: Collection<Stroke>) {
            strokes.removeAll(list.toSet())
            invalidate()
        }

        fun setDraft(bitmap: Bitmap?, legacy: Boolean) {
            draft = bitmap
            legacyDraft = legacy
            previewPaint = draftPaint(legacy, darkPaper)
            invalidate()
        }

        fun strokes(): List<Stroke> = strokes.toList()

        fun snapshot(width: Int, height: Int, density: Float, color: Int): Snapshot? =
            if (isEmpty || width <= 0 || height <= 0) null
            else Snapshot(strokes(), draft, legacyDraft, width, height, density, color)

        override fun onDraw(canvas: Canvas) {
            draft?.let { drawDraft(canvas, it, width, height, previewPaint) }
            strokes.forEach { renderer.draw(canvas, it, identity) }
        }
    }

    companion object {
        private fun previewColor(color: Int): Int {
            fun channel(component: Int) = (component * 0.56f + 112).toInt()
            return Color.rgb(channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
        }

        private fun draftPaint(legacy: Boolean, dark: Boolean) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (legacy) {
                // Older drafts were opaque white with a fixed near-black pen.
                val ink = if (dark) previewColor(PenColor.BLACK.argb) else PenColor.BLACK.argb
                colorFilter = ColorMatrixColorFilter(floatArrayOf(
                    0f, 0f, 0f, 0f, Color.red(ink).toFloat(),
                    0f, 0f, 0f, 0f, Color.green(ink).toFloat(),
                    0f, 0f, 0f, 0f, Color.blue(ink).toFloat(),
                    -1.0714f, 0f, 0f, 0f, 273.2f
                ))
            } else if (dark) {
                colorFilter = ColorMatrixColorFilter(floatArrayOf(
                    0.56f, 0f, 0f, 0f, 112f,
                    0f, 0.56f, 0f, 0f, 112f,
                    0f, 0f, 0.56f, 0f, 112f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
        }

        private fun drawDraft(canvas: Canvas, draft: Bitmap, width: Int, height: Int, paint: Paint) {
            canvas.drawBitmap(draft, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        }
    }
}
