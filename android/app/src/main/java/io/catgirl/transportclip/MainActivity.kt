package io.catgirl.transportclip

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val statusIo = Executors.newSingleThreadExecutor()
    private val serviceChanged: () -> Unit = { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        TransportService.instance?.refreshBubble()
        checkConnection()
    }

    override fun onStart() {
        super.onStart()
        TransportService.connectionListeners.add(serviceChanged)
    }

    override fun onStop() {
        TransportService.connectionListeners.remove(serviceChanged)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        statusIo.shutdownNow()
        super.onDestroy()
    }

    private val ui by lazy { Ui(this) }
    private var linkResult: Uploader.Result? = null
    private var connection: Uploader.Connection? = null
    private var statusRequest = 0
    private lateinit var connectionText: TextView
    private lateinit var ballState: TextView
    private lateinit var appInfoButton: View
    private lateinit var resetBallButton: View
    private lateinit var pendingText: TextView
    private lateinit var diagnostics: TextView

    private fun buildUi(): View = with(ui) {
        val activity = this@MainActivity
        val root = android.widget.FrameLayout(activity).apply { setBackgroundColor(ui.background) }
        installInsets(activity, root)
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        root.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        val page = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(dp(680))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            val gutter = dp(if (resources.configuration.screenWidthDp >= 600) 32 else 20)
            setPadding(gutter, dp(24), gutter, dp(32))
        }
        scroll.addView(page, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val mark = android.widget.ImageView(activity).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = column().apply {
            append(text("TransportClip", 15f, muted, medium = true))
            append(text(getString(R.string.app_name), 30f, medium = true))
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        page.append(header)

        fun card(title: String): LinearLayout = column().apply {
            background = shape(surface, 24)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            append(text(title, 19f, medium = true))
            page.append(this, 16)
        }

        card("连接").apply {
            connectionText = text("", 15f, medium = true)
            append(connectionText, 8)
            append(button("发送测试图片", filled = true) { sendTestImage() }, 14)
            append(button("服务器设置") { showServerSettings() }, 8)
        }
        card("悬浮球").apply {
            ballState = text("", 14f, muted)
            append(ballState, 6)
            val toggle = Switch(activity).apply {
                text = "显示悬浮球"
                textSize = 15f
                setTextColor(ink)
                minHeight = dp(48)
                isChecked = Prefs.showBall(activity)
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setShowBall(activity, checked)
                    TransportService.instance?.onBallPrefChanged()
                    refreshStatus()
                }
            }
            append(toggle, 4)
            append(text("轻点截屏框选，长按打开手写板，拖动调整位置。", 14f, muted))
            append(button("打开无障碍设置") {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }, 12)
            appInfoButton = button("打开应用信息") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)))
            }
            append(appInfoButton, 8)
            resetBallButton = button("恢复悬浮球位置") {
                TransportService.instance?.refreshBubble(resetPosition = true)
            }
            append(resetBallButton, 8)
        }
        card("临时手写板").apply {
            append(button("笔迹与橡皮擦设置") { showPadSettings() }, 12)
        }
        card("图片").apply {
            append(button("查看历史图片") { startActivity(Intent(activity, HistoryActivity::class.java)) }, 12)
            pendingText = text("", 14f, muted)
            append(pendingText, 12)
            append(button("全部重发") { resendPending() }, 8)
            append(button("清空待重试") {
                ImageStore.pendingFiles(activity).forEach { it.delete() }
                refreshStatus()
                Toast.makeText(activity, "已清空", Toast.LENGTH_SHORT).show()
            }, 8)
        }
        diagnostics = text("", 13f, muted).apply { visibility = View.GONE }
        page.append(diagnostics, 16)
        root
    }

    private fun refreshStatus() {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any {
            android.content.ComponentName.unflattenFromString(it) ==
                android.content.ComponentName(this, TransportService::class.java)
        }
        val service = TransportService.instance
        ballState.text = when {
            !enabled -> "无障碍未开启。若「公式快传」显示受限，先点该项，再回应用信息查看授权入口。"
            service == null -> "系统显示已开启，但服务未连接。请在无障碍设置中关一次再打开。"
            !Prefs.showBall(this) -> "无障碍运行中 · 悬浮球已关闭"
            service.bubbleAttached -> "无障碍运行中 · 悬浮球已挂载"
            service.bubbleInUse -> "无障碍运行中 · 截图或手写时暂时隐藏"
            else -> "无障碍运行中 · 悬浮球窗口尚未挂载"
        }
        appInfoButton.visibility = if (enabled) View.GONE else View.VISIBLE
        resetBallButton.visibility = if (service != null && Prefs.showBall(this)) View.VISIBLE else View.GONE
        val configured = Prefs.uploadUrl(this).isNotBlank() && Prefs.token(this).length >= 32
        connectionText.text = when (linkResult) {
            is Uploader.Result.Delivered -> "最近一次测试 · 已写入电脑剪贴板"
            Uploader.Result.Offline -> "最近一次测试 · 电脑不在线"
            is Uploader.Result.Failed -> "最近一次测试 · 发送失败"
            null -> when (connection) {
                Uploader.Connection.ONLINE -> "中继已连接 · 电脑在线"
                Uploader.Connection.OFFLINE -> "中继已连接 · 电脑不在线"
                Uploader.Connection.UNAUTHORIZED -> "设备令牌验证失败"
                Uploader.Connection.UNAVAILABLE -> "无法获取连接状态 · 可发送测试图片"
                null -> if (configured) "正在检查连接…" else "请在服务器设置中填写中继地址和设备令牌"
            }
        }
        connectionText.setTextColor(when (linkResult) {
            is Uploader.Result.Delivered -> ui.success
            Uploader.Result.Offline, is Uploader.Result.Failed -> ui.error
            null -> when (connection) {
                Uploader.Connection.ONLINE -> ui.success
                Uploader.Connection.UNAUTHORIZED -> ui.error
                else -> ui.muted
            }
        })
        pendingText.text = "待重试 · ${ImageStore.pendingFiles(this).size} 张"
        val log = CrashLog.tail(700)
        diagnostics.text = log?.let { "最近的诊断日志\n$it" }.orEmpty()
        diagnostics.visibility = if (log == null) View.GONE else View.VISIBLE
    }

    private fun checkConnection() {
        val request = ++statusRequest
        val token = Prefs.token(this)
        val url = Prefs.uploadUrl(this)
        linkResult = null
        connection = null
        refreshStatus()
        if (url.isBlank() || token.length < 32) return
        statusIo.execute {
            val result = Uploader.connectionStatus(url, token)
            runOnUiThread {
                if (isDestroyed || request != statusRequest) return@runOnUiThread
                connection = result
                refreshStatus()
            }
        }
    }

    private fun showPadSettings() = with(ui) {
        val activity = this@MainActivity
        val current = Prefs.padStyle(activity)
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), dp(12)) }
        fun slider(label: String, maxValue: Int, initial: Int, format: (Int) -> String): android.widget.SeekBar {
            val caption = text("", 15f, medium = true)
            val bar = android.widget.SeekBar(activity).apply {
                max = maxValue
                progress = initial
                contentDescription = label
                minHeight = dp(48)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                        caption.text = "$label · ${format(value)}"
                    }
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
                })
            }
            caption.text = "$label · ${format(initial)}"
            form.append(caption, 12)
            form.append(bar, 2)
            return bar
        }
        val pen = slider("笔迹粗细", 14, (current.penWidth * 2).toInt() - 2) {
            "${(it + 2) / 2f} dp"
        }
        val palette = PenColor.entries
        val color = android.widget.Spinner(activity).apply {
            minimumHeight = dp(48)
            adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item,
                palette.map { it.label }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(current.penColor.ordinal)
            contentDescription = "笔迹颜色"
        }
        form.append(text("笔迹颜色", 15f, medium = true), 12)
        form.append(color, 2)
        val eraser = slider("橡皮擦范围", 34, current.eraserRadius - 6) { "半径 ${it + 6} dp" }
        val scroll = ScrollView(activity).apply { addView(form) }
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("笔迹与橡皮擦设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setPadStyle(activity, PadStyle((pen.progress + 2) / 2f,
                    palette[color.selectedItemPosition], eraser.progress + 6))
            }
            .show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(shape(surface, 28), dp(12)))
    }

    private fun showServerSettings() = with(ui) {
        val activity = this@MainActivity
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), dp(12)) }
        fun field(label: String, value: String, placeholder: String, type: Int): EditText {
            val input = EditText(activity).apply {
                id = View.generateViewId()
                inputType = type
                setSingleLine()
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT
                setTextColor(ink)
                setHintTextColor(muted)
                background = shape(surface, 12, outline)
                setOnFocusChangeListener { _, focused ->
                    background = shape(surface, 12, if (focused) primary else outline)
                }
                minimumHeight = dp(52)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setText(value)
                hint = placeholder
            }
            form.append(text(label, 14f, medium = true).apply { labelFor = input.id }, 14)
            form.append(input, 6)
            return input
        }
        val url = field("上传地址", Prefs.uploadUrl(activity), "请输入中继的 HTTPS 上传地址",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val token = field("设备令牌", Prefs.token(activity), "与 Windows 端的令牌相同",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.append(text("支持粘贴纯令牌或配对 JSON。", 13f, muted), 8)
        val scroll = ScrollView(activity).apply { addView(form) }
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("服务器设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(shape(surface, 28), dp(12)))
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (saveSettings(url, token)) dialog.dismiss()
        }
    }

    private fun saveSettings(urlInput: EditText, tokenInput: EditText): Boolean {
        val raw = tokenInput.text.toString().trim()
        var url = urlInput.text.toString()
        var token = raw
        if (raw.startsWith("{")) {
            val pairing = runCatching { org.json.JSONObject(raw) }.getOrElse {
                tokenInput.error = "JSON 格式不正确"
                return false
            }
            token = pairing.optString("t").ifBlank { Prefs.token(this) }
            url = pairing.optString("u").ifBlank { url }
        }
        Prefs.setToken(this, token)
        Prefs.setUploadUrl(this, url)
        checkConnection()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun resendPending() {
        val files = ImageStore.pendingFiles(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "没有待重试的图片", Toast.LENGTH_SHORT).show()
            return
        }
        val url = Prefs.uploadUrl(this)
        val token = Prefs.token(this)
        Toast.makeText(this, "补发 ${files.size} 张…", Toast.LENGTH_SHORT).show()
        io.execute {
            var ok = 0
            var lastErr = ""
            for (f in files) {
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                when (val r = Uploader.upload(url, token, bytes)) {
                    is Uploader.Result.Delivered -> {
                        ImageStore.promote(this, f)
                        ok++
                    }
                    is Uploader.Result.Offline -> { lastErr = "电脑不在线"; break }
                    is Uploader.Result.Failed -> { lastErr = r.detail; break }
                }
            }
            runOnUiThread {
                val msg = if (ok == files.size) "✓ 全部 ${ok} 张已送达"
                else "送达 ${ok}/${files.size} 张${if (lastErr.isNotEmpty()) ",中止:$lastErr" else ""}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun sendTestImage() {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val bmp = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val border = Paint().apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        c.drawRect(3f, 3f, 637f, 237f, border)
        val tp = Paint().apply {
            color = Color.BLACK; textSize = 40f; isAntiAlias = true
        }
        c.drawText("公式快传 · 链路测试", 40f, 80f, tp)
        c.drawText("Transport test $stamp", 40f, 150f, tp)
        val rp = Paint().apply {
            color = Color.rgb(200, 30, 30); textSize = 40f; isAntiAlias = true
        }
        c.drawText("E = mc\u00B2  \u222B f(x)dx", 40f, 205f, rp)

        val bytes = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bmp.recycle()

        val url = Prefs.uploadUrl(this)
        val token = Prefs.token(this)
        Toast.makeText(this, "发送中…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = Uploader.upload(url, token, bytes)
            runOnUiThread {
                if (url == Prefs.uploadUrl(this) && token == Prefs.token(this)) linkResult = result
                val msg = when (result) {
                    is Uploader.Result.Delivered -> "✓ 已写入电脑剪贴板(${result.elapsedMs}ms),去电脑 Ctrl+V 试试"
                    is Uploader.Result.Offline -> "✗ 电脑客户端不在线,请先启动电脑端 TransportClip"
                    is Uploader.Result.Failed -> "✗ 发送失败:${result.detail}"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }
}
