package io.catgirl.transportclip

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal class Ui(private val context: Context) {
    val background get() = context.getColor(R.color.tc_background)
    val surface get() = context.getColor(R.color.tc_surface)
    val container get() = context.getColor(R.color.tc_container)
    val ink get() = context.getColor(R.color.tc_ink)
    val muted get() = context.getColor(R.color.tc_muted)
    val primary get() = context.getColor(R.color.tc_primary)
    val onPrimary get() = context.getColor(R.color.tc_on_primary)
    val outline get() = context.getColor(R.color.tc_outline)
    val success get() = context.getColor(R.color.tc_success)
    val error get() = context.getColor(R.color.tc_error)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(color: Int, radius: Int, foreground: Int) = RippleDrawable(
        ColorStateList.valueOf((foreground and 0x00ffffff) or 0x22000000),
        shape(color, radius),
        shape(android.graphics.Color.WHITE, radius)
    )

    fun text(value: String, size: Float, color: Int = ink, medium: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setLineSpacing(0f, 1.12f)
        }

    fun button(label: String, filled: Boolean = false, action: () -> Unit) = Button(context).apply {
        val foreground = if (filled) onPrimary else ink
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        backgroundTintList = null
        background = ripple(if (filled) primary else container, 24, foreground)
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (filled) onPrimary else muted, foreground)
        ))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { action() }
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun LinearLayout.append(view: View, gap: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    fun installInsets(activity: Activity, root: View) {
        activity.window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val safe = windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            windowInsets
        }
    }
}
