package io.catgirl.transportclip

import android.content.Context
import java.io.File

/** pending(待重试)与 history(已送达)目录管理。 */
object ImageStore {
    private const val HISTORY_MAX = 100

    fun pendingDir(ctx: Context): File = File(ctx.filesDir, "pending").apply { mkdirs() }
    fun historyDir(ctx: Context): File = File(ctx.filesDir, "history").apply { mkdirs() }

    @Synchronized
    fun savePending(ctx: Context, bytes: ByteArray): File? = runCatching {
        val pending = pendingDir(ctx)
        val history = historyDir(ctx)
        var time = System.currentTimeMillis()
        while (File(pending, "$time.png").exists() || File(history, "$time.png").exists()) time++
        val target = File(pending, "$time.png")
        val partial = File.createTempFile("image-", ".part", pending)
        try {
            partial.writeBytes(bytes)
            check(partial.renameTo(target))
            target
        } finally {
            partial.delete()
        }
    }.getOrNull()

    fun pendingFiles(ctx: Context): List<File> =
        pendingDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension == "png" }
            ?.sortedBy { it.name } // 时间戳命名,旧在前,补发保持顺序
            ?: emptyList()

    fun promote(ctx: Context, f: File): Boolean = runCatching {
        if (f.renameTo(File(historyDir(ctx), f.name))) trimHistory(ctx)
        true
    }.getOrDefault(false)

    fun trimHistory(ctx: Context) {
        val files = historyDir(ctx).listFiles()?.filter { it.extension == "png" } ?: return
        if (files.size > HISTORY_MAX) {
            files.sortedBy { it.name }.take(files.size - HISTORY_MAX).forEach { it.delete() }
        }
    }
}
