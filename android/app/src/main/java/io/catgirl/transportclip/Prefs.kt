package io.catgirl.transportclip

import android.content.Context

internal enum class PenColor(val label: String, val argb: Int) {
    BLACK("墨黑", 0xFF111827.toInt()),
    BLUE("深蓝", 0xFF1749B1.toInt()),
    RED("朱红", 0xFFAA2838.toInt()),
    GREEN("墨绿", 0xFF17644D.toInt()),
    PURPLE("深紫", 0xFF6D3FA0.toInt())
}

internal data class PadStyle(val penWidth: Float, val penColor: PenColor, val eraserRadius: Int)

object Prefs {
    private const val FILE = "transport"

    const val DEFAULT_UPLOAD_URL = "https://relay.example.com/tr-relay/upload"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    internal fun padStyle(ctx: Context): PadStyle = with(sp(ctx)) {
        val color = PenColor.entries.firstOrNull { it.name == getString("pad_pen_color", null) }
        PadStyle(
            getInt("pad_pen_half_dp", 5).coerceIn(2, 16) / 2f,
            color ?: PenColor.BLACK,
            getInt("pad_eraser_dp", 16).coerceIn(6, 40)
        )
    }

    internal fun setPadStyle(ctx: Context, style: PadStyle) {
        sp(ctx).edit()
            .putInt("pad_pen_half_dp", (style.penWidth * 2).toInt())
            .putString("pad_pen_color", style.penColor.name)
            .putInt("pad_eraser_dp", style.eraserRadius)
            .apply()
    }

    var showBall: (ctx: Context) -> Boolean = { ctx ->
        sp(ctx).getBoolean("show_ball", true)
    }
    var setShowBall: (ctx: Context, Boolean) -> Unit = { ctx, v ->
        sp(ctx).edit().putBoolean("show_ball", v).apply()
    }

    var bubbleX: (ctx: Context) -> Int = { ctx ->
        sp(ctx).getInt("bubble_x", Int.MIN_VALUE)
    }
    var bubbleY: (ctx: Context) -> Int = { ctx ->
        sp(ctx).getInt("bubble_y", Int.MIN_VALUE)
    }

    fun setBubblePos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt("bubble_x", x).putInt("bubble_y", y).apply()
    }

    var uploadUrl: (ctx: Context) -> String = { ctx ->
        sp(ctx).getString("upload_url", DEFAULT_UPLOAD_URL) ?: DEFAULT_UPLOAD_URL
    }
    var setUploadUrl: (ctx: Context, String) -> Unit = { ctx, v ->
        sp(ctx).edit().putString("upload_url", v.trim().removeSuffix("/")).apply()
    }

    /** 令牌(Keystore 加密存储) */
    fun token(ctx: Context): String {
        val enc = sp(ctx).getString("token_enc", null) ?: return ""
        return SecurePrefs.decrypt(enc) ?: ""
    }

    fun setToken(ctx: Context, plain: String) {
        val enc = if (plain.isBlank()) "" else SecurePrefs.encrypt(plain.trim())
        sp(ctx).edit().putString("token_enc", enc).apply()
    }
}
