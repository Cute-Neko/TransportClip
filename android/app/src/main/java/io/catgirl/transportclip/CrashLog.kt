package io.catgirl.transportclip

import android.util.Log

/** 崩溃/异常统一记录:Logcat(tag=TransportClip)+ 私有文件,便于用户导出排障。 */
object CrashLog {
    private const val TAG = "TransportClip"
    @Volatile private var appContext: android.content.Context? = null

    @Synchronized
    fun install(ctx: android.content.Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("FATAL thread=${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }

    fun e(where: String, t: Throwable) {
        Log.e(TAG, where, t)
        runCatching { write(where, t) }
    }

    @Synchronized
    private fun write(where: String, t: Throwable) {
        val ctx = appContext ?: return
        val f = java.io.File(ctx.filesDir, "crash.log")
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line = "$stamp $where: ${Log.getStackTraceString(t)}\n"
        val old = if (f.exists()) f.readText() else ""
        f.writeText((old.takeLast(24 * 1024) + line.take(8 * 1024)))
    }

    /** 供状态页展示的最近崩溃摘录 */
    fun tail(maxChars: Int): String? = runCatching {
        val ctx = appContext ?: return@runCatching null
        val f = java.io.File(ctx.filesDir, "crash.log")
        if (!f.exists()) null else {
            val text = f.readText()
            if (text.isBlank()) null else text.takeLast(maxChars)
        }
    }.getOrNull()
}
