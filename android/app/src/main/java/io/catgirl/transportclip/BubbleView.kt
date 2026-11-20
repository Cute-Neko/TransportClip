package io.catgirl.transportclip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** 悬浮球以小圆点反馈发送状态，保持整个窗口可点按。 */
class BubbleView(context: Context) : View(context) {

    enum class State { IDLE, SENDING, OK, ERROR }

    var state = State.IDLE
        private set

    private val palette = Ui(context)
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setState(s: State, autoResetMs: Long = 0, handler: android.os.Handler? = null) {
        state = s
        invalidate()
        handler?.removeCallbacks(resetRun)
        if (autoResetMs > 0) handler?.postDelayed(resetRun, autoResetMs)
    }

    private val resetRun = Runnable {
        state = State.IDLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 5f * density
        fill.color = 0x3300120C
        canvas.drawCircle(cx, cy + density, r + density, fill)
        fill.color = palette.primary
        canvas.drawCircle(cx, cy, r, fill)
        iconPaint.color = palette.onPrimary
        val d = r * 0.37f
        glyph.rewind()
        glyph.moveTo(cx - d, cy + d)
        glyph.lineTo(cx + d, cy - d)
        glyph.moveTo(cx - d * 0.18f, cy - d)
        glyph.lineTo(cx + d, cy - d)
        glyph.lineTo(cx + d, cy + d * 0.18f)
        canvas.drawPath(glyph, iconPaint)
        if (state != State.IDLE) {
            val x = cx + r * 0.70f
            val y = cy + r * 0.70f
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, 5.5f * density, fill)
            fill.color = when (state) {
                State.OK -> 0xFF148653.toInt()
                State.ERROR -> 0xFFC02E46.toInt()
                else -> 0xFFC37A00.toInt()
            }
            canvas.drawCircle(x, y, 3.8f * density, fill)
        }
    }
}
