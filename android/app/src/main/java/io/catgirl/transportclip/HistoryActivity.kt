package io.catgirl.transportclip

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** 历史图片管理:网格浏览,点开可预览/重发/删除。 */
class HistoryActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var grid: GridView
    private lateinit var countText: TextView
    private var files: List<File> = emptyList()

    private val ui by lazy { Ui(this) }
    private lateinit var emptyState: TextView
    private lateinit var clear: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(ui) {
            val activity = this@HistoryActivity
            val root = FrameLayout(activity).apply { setBackgroundColor(ui.background) }
            installInsets(activity, root)
            val page = column().apply {
                val gutter = dp(if (resources.configuration.screenWidthDp >= 600) 32 else 16)
                setPadding(gutter, dp(16), gutter, dp(8))
            }
            root.addView(page, FrameLayout.LayoutParams(-1, -1))
            val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val back = button("‹") { finish() }.apply { contentDescription = "返回" }
            top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            val heading = column()
            heading.append(text("图片历史", 23f, medium = true))
            countText = text("", 13f, muted)
            heading.append(countText)
            top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            clear = button("清空历史") {
                AlertDialog.Builder(activity)
                    .setTitle("清空历史")
                    .setMessage("删除全部历史图片？")
                    .setPositiveButton("删除") { _, _ ->
                        ImageStore.historyDir(activity).listFiles()?.forEach { it.delete() }
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            top.addView(clear, LinearLayout.LayoutParams(-2, -2))
            page.append(top)

            val content = FrameLayout(activity)
            page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
            grid = GridView(activity).apply {
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(if (resources.configuration.screenWidthDp >= 600) 156 else 132)
                horizontalSpacing = dp(10)
                verticalSpacing = dp(10)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                clipToPadding = false
                setPadding(0, 0, 0, dp(16))
            }
            emptyState = text("还没有已发送的图片", 16f, muted).apply { gravity = Gravity.CENTER }
            content.addView(grid, FrameLayout.LayoutParams(-1, -1))
            content.addView(emptyState, FrameLayout.LayoutParams(-1, -1))
            grid.emptyView = emptyState
            grid.setOnItemClickListener { _, _, position, _ -> showDetail(files[position]) }
            setContentView(root)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        files = ImageStore.historyDir(this).listFiles()
            ?.filter { it.isFile && it.extension == "png" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
        countText.text = "${files.size} 张 · 最多保留 100 张"
        grid.adapter = ThumbAdapter(files)
        clear.isEnabled = files.isNotEmpty()
    }

    private fun showDetail(f: File) {
        val bmp = decodeScaled(f, 1600)
        val view = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            val inset = ui.dp(12)
            setPadding(inset, inset, inset, inset)
        }
        AlertDialog.Builder(this)
            .setTitle(displayTime(f))
            .setView(view)
            .setPositiveButton("重发") { _, _ -> resend(f) }
            .setNeutralButton("删除") { _, _ ->
                f.delete()
                refresh()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun resend(f: File) {
        Toast.makeText(this, "重发中…", Toast.LENGTH_SHORT).show()
        io.execute {
            val bytes = runCatching { f.readBytes() }.getOrNull()
            val r = if (bytes == null) null
            else Uploader.upload(Prefs.uploadUrl(this), Prefs.token(this), bytes)
            runOnUiThread {
                val msg = when (r) {
                    is Uploader.Result.Delivered -> "✓ 已重新写入电脑剪贴板"
                    is Uploader.Result.Offline -> "✗ 电脑不在线"
                    is Uploader.Result.Failed -> "✗ ${r.detail}"
                    null -> "✗ 读取文件失败"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decodeScaled(f: File, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > maxSide || opts.outHeight / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun displayTime(file: File): String {
        val millis = file.nameWithoutExtension.toLongOrNull() ?: file.lastModified()
        return android.text.format.DateUtils.formatDateTime(this, millis,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_SHOW_TIME or
                android.text.format.DateUtils.FORMAT_SHOW_YEAR)
    }

    private inner class Thumbnail : LinearLayout(this@HistoryActivity) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            val inset = ui.dp(8)
            setPadding(inset, inset, inset, inset)
        }
        val date = ui.text("", 12f, ui.muted).apply {
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(10))
        }

        init {
            orientation = VERTICAL
            layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
            background = ui.shape(ui.surface, 16)
            clipToOutline = true
            foreground = ui.ripple(Color.TRANSPARENT, 16, ui.ink)
            addView(image, LayoutParams(-1, ui.dp(126)))
            addView(date, LayoutParams(-1, -2))
        }
    }

    private inner class ThumbAdapter(private val items: List<File>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? Thumbnail) ?: Thumbnail()
            val file = getItem(position)
            card.date.text = displayTime(file)
            if (card.tag != file) {
                card.tag = file
                card.image.setImageDrawable(null)
                io.execute {
                    val bmp = decodeScaled(file, 420)
                    runOnUiThread {
                        if (card.tag == file) card.image.setImageBitmap(bmp) else bmp?.recycle()
                    }
                }
            }
            return card
        }
    }

}
