package io.catgirl.transportclip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import kotlin.math.abs
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 无障碍服务:承载悬浮球、无障碍截屏、框选界面与上传。
 * 截屏机制参考了 GPL-3.0 项目 circle-to-share 的做法(TYPE_ACCESSIBILITY_OVERLAY +
 * AccessibilityService.takeScreenshot),本项目 Android 端同样以 GPL-3.0 发布。
 */
class TransportService : AccessibilityService() {

    companion object {
        private const val SHOT_INTERVAL_MS = 400L       // 系统截屏限频
        private const val BUBBLE_HIDE_DELAY_MS = 80L    // 截屏前先藏球,等它真正消失

        /** 供 MainActivity 通知悬浮球开关变化(服务与活动同进程) */
        @JvmStatic
        var instance: TransportService? = null
            private set
        val connectionListeners = mutableSetOf<() -> Unit>()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val padRenderIo = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleRetries = 0
    private val retryBubble = Runnable { showBubbleIfNeeded() }
    val bubbleAttached: Boolean get() = bubble?.isAttachedToWindow == true
    val bubbleInUse: Boolean get() = capturing.get() || overlayRoot != null || padRoot != null
    private var overlayRoot: FrameLayout? = null
    private var overlayCrop: CropView? = null
    private var sendButton: Button? = null
    private var resultCard: LinearLayout? = null
    private var noticeView: TextView? = null
    private var lastShotUptime = 0L

    // 手写板
    private var padRoot: FrameLayout? = null
    private var retiringPadRoot: FrameLayout? = null
    private var padView: InkPadView? = null
    private var padSending = false
    private data class DraftVersion(val file: File, val modified: Long)
    private var padDraft: DraftVersion? = null
    private var draftInFlight: DraftVersion? = null

    // ---------------- 生命周期 ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        CrashLog.install(this)
        instance = this
        refreshBubble()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        detachWindows()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachWindows()
        padRenderIo.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 裁剪/手写中的屏幕旋转会导致坐标错乱,直接取消本轮,悬浮球位置自动夹回屏内
        if (overlayRoot != null) {
            dismissOverlay()
        }
        if (padRoot != null) {
            closePad(saveDraft = true)
        }
        refreshBubble()
    }

    private fun detachWindows() {
        if (instance === this) {
            instance = null
            connectionListeners.forEach { it() }
        }
        ui.removeCallbacksAndMessages(null)
        capturing.set(false)
        removeBubble()
        dismissOverlay()
        closePad(saveDraft = true)
        retiringPadRoot?.let { runCatching { wm.removeView(it) } }
        retiringPadRoot = null
        dropNotice()
    }

    // ---------------- 工具 ----------------

    private val wm: WindowManager get() = getSystemService(WindowManager::class.java)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private val isScreenUsable: Boolean
        get() {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val kgm = getSystemService(android.app.KeyguardManager::class.java)
            return pm.isInteractive && !kgm.isKeyguardLocked
        }

    private fun displayBounds(): Rect = wm.maximumWindowMetrics.bounds

    /** 覆盖层窗口矩形:直接铺满整个物理屏幕 */
    private fun overlayFrame(): Rect = Rect(displayBounds())

    // ---------------- 悬浮球 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (instance !== this || bubble != null || capturing.get() ||
            overlayRoot != null || padRoot != null) return
        val size = dp(52)

        // 首次使用:默认放在右上角(避开状态栏);已保存过则用记忆位置
        val b = displayBounds()
        var bx = Prefs.bubbleX(this)
        var by = Prefs.bubbleY(this)
        if (bx == Int.MIN_VALUE || by == Int.MIN_VALUE) {
            bx = (b.width() - size - dp(28)).coerceAtLeast(0)
            by = dp(120)
            Prefs.setBubblePos(this, bx, by)
        }

        val view = BubbleView(this).apply { contentDescription = "公式快传悬浮球" }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = bx
            y = by
        }
        clampBubble(params) // 无论位置来自何处,一律夹回屏内
        view.isFocusable = false
        view.defaultFocusHighlightEnabled = false

        // 拖动 vs 单击 vs 长按:超 touchSlop 算拖动;轻点截屏;长按 500ms 打开手写板
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = 500L
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var longPressFired = false
        val longPressRun = Runnable {
            if (bubble !== view) return@Runnable
            longPressFired = true
            openPad()
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    longPressFired = false
                    ui.postDelayed(longPressRun, longPressTimeout)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        ui.removeCallbacks(longPressRun)
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampBubble(params)
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPressRun)
                    if (!longPressFired) {
                        if (!moved) beginCapture()
                        else Prefs.setBubblePos(this, params.x, params.y)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPressRun)
                    if (moved && !longPressFired) Prefs.setBubblePos(this, params.x, params.y)
                    true
                }
                else -> false
            }
        }

        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (bubble === v) connectionListeners.forEach { it() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (bubble !== v) return
                bubble = null
                bubbleParams = null
                connectionListeners.forEach { it() }
                ui.post(retryBubble)
            }
        })
        bubble = view
        bubbleParams = params
        try {
            wm.addView(view, params)
        } catch (e: RuntimeException) {
            removeBubble()
            throw e
        }
    }

    private fun clampBubble(params: WindowManager.LayoutParams) {
        val b = displayBounds()
        params.x = params.x.coerceIn(0, max(0, b.width() - dp(52)))
        params.y = params.y.coerceIn(0, max(0, b.height() - dp(52)))
    }

    private fun keepBubbleOnScreen() {
        val p = bubbleParams ?: return
        clampBubble(p)
        bubble?.let { wm.updateViewLayout(it, p) }
    }

    private fun removeBubble() {
        val view = bubble
        bubble = null
        bubbleParams = null
        view?.let { runCatching { wm.removeView(it) } }
        if (view != null && instance === this) connectionListeners.forEach { it() }
    }

    private fun showBubbleIfNeeded() {
        ui.removeCallbacks(retryBubble)
        if (instance !== this || !Prefs.showBall(this) || bubbleInUse || bubble != null) return
        try {
            addBubble()
            bubbleRetries = 0
        } catch (e: RuntimeException) {
            logE("添加悬浮球失败", e)
            if (++bubbleRetries <= 3) ui.postDelayed(retryBubble, 500L * bubbleRetries)
        }
        connectionListeners.forEach { it() }
    }

    fun refreshBubble(resetPosition: Boolean = false) {
        if (resetPosition) {
            Prefs.setBubblePos(this, Int.MIN_VALUE, Int.MIN_VALUE)
            removeBubble()
        } else {
            try {
                keepBubbleOnScreen()
            } catch (e: RuntimeException) {
                logE("更新悬浮球窗口失败", e)
                removeBubble()
            }
        }
        bubbleRetries = 0
        showBubbleIfNeeded()
        connectionListeners.forEach { it() }
    }

    // ---------------- 提示条(截屏失败等) ----------------

    private fun showNotice(msg: String) {
        dropNotice()
        val palette = Ui(this)
        val tv = palette.text(msg, 14f, medium = true).apply {
            background = palette.shape(palette.surface, 18)
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        if (runCatching { wm.addView(tv, params) }.isSuccess) {
            noticeView = tv
            ui.postDelayed({ dropNotice() }, 1800)
        }
    }

    private fun dropNotice() {
        noticeView?.let { runCatching { wm.removeView(it) } }
        noticeView = null
    }

    // ---------------- 截屏流程 ----------------

    private fun beginCapture() {
        if (instance !== this || capturing.get() || overlayRoot != null || padRoot != null) return
        capturing.set(true)
        removeBubble()
        dropNotice()
        ui.postDelayed({ runCapture() }, BUBBLE_HIDE_DELAY_MS)
    }

    private fun runCapture() {
        if (!capturing.get()) return
        // 系统限制两次截屏的最小间隔
        val since = SystemClock.uptimeMillis() - lastShotUptime
        if (since < SHOT_INTERVAL_MS) {
            ui.postDelayed({ runCapture() }, SHOT_INTERVAL_MS - since + 10)
            return
        }
        lastShotUptime = SystemClock.uptimeMillis()
        if (!isScreenUsable) {
            abandonCapture()
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (t: Throwable) {
                            null
                        } finally {
                            runCatching { screenshot.hardwareBuffer.close() }
                        }
                        if (instance !== this@TransportService) {
                            bmp?.recycle()
                            return
                        }
                        if (bmp == null || !isScreenUsable) {
                            bmp?.recycle()
                            abandonCapture("截屏失败")
                        } else {
                            capturing.set(false)
                            presentOverlay(bmp)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        abandonCapture("截屏失败(代码 $errorCode)")
                    }
                }
            )
        } catch (t: Throwable) {
            abandonCapture("截屏异常: ${t.message}")
        }
    }

    private fun abandonCapture(msg: String? = null) {
        capturing.set(false)
        msg?.let { showNotice(it) }
        showBubbleIfNeeded()
    }

    // ---------------- 框选界面 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun presentOverlay(bitmap: Bitmap) {
        val frame = overlayFrame()
        val display = displayBounds()

        val root = FrameLayout(this)
        val crop = CropView(this, bitmap)
        crop.setDisplayFrame(
            RectF(
                (-frame.left).toFloat(), (-frame.top).toFloat(),
                (display.width() - frame.left).toFloat(), (display.height() - frame.top).toFloat()
            )
        )
        root.addView(
            crop, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val buttons = Ui(this)
        val cancel = buttons.button("取消") { dismissOverlay() }
        val send = buttons.button("发送", filled = true) { trySend(crop) }
        addOverlayControls(root, "拖动框选，拖动圆点调整范围", cancel, send)
        sendButton = send

        val params = WindowManager.LayoutParams(
            frame.width(), frame.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = frame.left
            y = frame.top
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        runCatching { wm.addView(root, params) }.onFailure {
            logE("添加截图框选窗口失败", it)
            dismissOverlay()
            return
        }
        overlayRoot = root
        overlayCrop = crop
    }

    private fun dismissOverlay(feedback: BubbleView.State? = null) {
        // The window may draw again before removal completes. CropView owns
        // the screenshot until its view is collected.
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        overlayRoot = null
        overlayCrop = null
        sendButton = null
        resultCard = null
        showBubbleIfNeeded()
        feedback?.let { bubble?.setState(it, if (it == BubbleView.State.ERROR) 3000 else 1500, ui) }
    }

    // ---------------- 发送 ----------------

    private fun trySend(crop: CropView) {
        if (resultCard != null) return // 已在发送/展示结果
        val rect = crop.getCropRect()
        if (rect == null) {
            showNotice("请先框选要发送的区域")
            return
        }
        val root = overlayRoot ?: return
        val bmp = crop.bitmap
        val piece = Bitmap.createBitmap(
            bmp, rect.left.toInt(), rect.top.toInt(),
            rect.width().toInt(), rect.height().toInt()
        )
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            piece.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        if (piece !== bmp) piece.recycle()

        sendButton?.apply {
            text = "发送中…"
            isEnabled = false
        }

        // 先落盘,失败也不丢图
        val pendingFile = ImageStore.savePending(this, bytes)

        io.execute {
            val result = Uploader.upload(Prefs.uploadUrl(this), Prefs.token(this), bytes)
            if (result is Uploader.Result.Delivered) pendingFile?.let { ImageStore.promote(this, it) }
            ui.post {
                if (overlayRoot !== root) return@post
                sendButton?.apply { text = "发送"; isEnabled = true }
                when (result) {
                    is Uploader.Result.Delivered -> {
                        showResultCard(true, "已写入电脑剪贴板(${result.elapsedMs}ms)")
                    }
                    is Uploader.Result.Offline -> {
                        showResultCard(false, "电脑客户端不在线,可稍后重试")
                    }
                    is Uploader.Result.Failed -> {
                        showResultCard(false, "发送失败:${result.detail}")
                    }
                }
            }
        }
    }

    // ---------------- 临时手写板(长按悬浮球,Jetpack Ink 引擎) ----------------

    private fun draftFile(): File = File(filesDir, "pad-draft-ink.png")
    private fun legacyDraftFile(): File = File(filesDir, "pad-draft.png")

    fun openPad() {
        if (instance !== this || !isScreenUsable ||
            padRoot != null || overlayRoot != null || capturing.get()) return
        try {
            openPadInner()
        } catch (t: Throwable) {
            // 手写板任何初始化失败都不能打死无障碍服务
            logE("打开手写板失败", t)
            closePad(saveDraft = false)
            showNotice("手写板打开失败:${t.javaClass.simpleName}")
        }
    }

    private fun openPadInner() {
        dropNotice()

        val frame = overlayFrame()
        // MotionEventPredictor reads Context.getDisplay() during initialization.
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: error("找不到默认显示屏")
        val padContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val root = FrameLayout(padContext)
        val pad = InkPadView(padContext, Prefs.padStyle(this))
        val df = draftFile().takeIf { it.exists() } ?: legacyDraftFile()
        val draft = DraftVersion(df, df.lastModified())
        if (draft.modified != 0L && draft != draftInFlight) {
            val d = runCatching {
                android.graphics.BitmapFactory.decodeFile(df.absolutePath)
            }.getOrNull()
            if (d != null) {
                pad.setDraft(d, legacy = df == legacyDraftFile())
                padDraft = draft
            } else df.delete()
        }

        // 书写区铺满全屏
        root.addView(
            pad, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val buttons = Ui(padContext)
        val close = buttons.button("关闭") { closePad(saveDraft = true) }
        val send = buttons.button("完成", filled = true) { sendPad() }
        addOverlayControls(root, null, close, send)

        val params = WindowManager.LayoutParams(
            frame.width(), frame.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = frame.left
            y = frame.top
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        padRoot = root
        padView = pad
        // Use the service WindowManager so the overlay retains the accessibility token.
        wm.addView(root, params)
        removeBubble()
    }

    private fun addOverlayControls(root: FrameLayout, hint: String?, secondary: Button, primary: Button) {
        val palette = Ui(root.context)
        val panel = palette.column().apply {
            background = palette.shape(palette.surface, 24)
            elevation = dp(6).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            hint?.let {
                val label = palette.text(it, 13f, palette.muted).apply {
                    setPadding(dp(8), 0, dp(8), dp(4))
                }
                addView(label)
            }
        }
        val row = LinearLayout(root.context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(secondary, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(primary, LinearLayout.LayoutParams(-2, -2))
        }
        panel.addView(row)
        val baseMargin = dp(16)
        val position = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = baseMargin
            bottomMargin = baseMargin
            leftMargin = baseMargin
        }
        root.addView(panel, position)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            position.rightMargin = baseMargin + safe.right
            position.leftMargin = baseMargin + safe.left
            position.bottomMargin = baseMargin + safe.bottom
            panel.layoutParams = position
            insets
        }
    }

    fun closePad(saveDraft: Boolean, deferWindowRemoval: Boolean = false) {
        val root = padRoot ?: return
        val pad = padView
        if (saveDraft && pad != null) {
            runCatching {
                pad.snapshot()?.render(cropped = false)?.let { bmp ->
                    try {
                        val file = android.util.AtomicFile(draftFile())
                        val out = file.startWrite()
                        try {
                            check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
                            file.finishWrite(out)
                            legacyDraftFile().delete()
                        } catch (e: Exception) {
                            file.failWrite(out)
                            throw e
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }.onFailure { logE("保存手写草稿失败", it) }
        }
        if (deferWindowRemoval) {
            val params = root.layoutParams as WindowManager.LayoutParams
            params.alpha = 0f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { wm.updateViewLayout(root, params) }
            retiringPadRoot = root
            ui.postDelayed({
                if (retiringPadRoot === root) retiringPadRoot = null
                runCatching { wm.removeView(root) }
            }, 400)
        } else {
            runCatching { wm.removeView(root) }
        }
        padRoot = null
        padView = null
        padDraft = null
        padSending = false
        resultCard = null
        showBubbleIfNeeded()
    }

    private fun sendPad() {
        val root = padRoot ?: return
        val pad = padView ?: return
        if (resultCard != null || padSending) return
        if (!pad.hasContent) {
            closePad(saveDraft = false)
            return
        }
        padSending = true
        pad.snapshotAfterHandoff { snapshot ->
            if (padRoot !== root) return@snapshotAfterHandoff
            val previousDraft = padDraft
            closePad(saveDraft = false, deferWindowRemoval = true)
            if (snapshot == null) return@snapshotAfterHandoff
            if (previousDraft != null) draftInFlight = previousDraft
            bubble?.setState(BubbleView.State.SENDING, 0, ui)
            padRenderIo.execute {
                preparePadUpload(snapshot, previousDraft, Prefs.uploadUrl(this), Prefs.token(this))
            }
        }
    }

    private fun preparePadUpload(
        snapshot: InkPadView.Snapshot, previousDraft: DraftVersion?, url: String, token: String
    ) {
        val prepared = runCatching {
            val bitmap = snapshot.render(cropped = true)
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        val bytes = prepared.getOrElse {
            logE("生成手写图片失败", it)
            finishDraftSubmission(previousDraft, saved = false)
            postPadResult(false, "生成手写图片失败")
            return
        }
        val pending = ImageStore.savePending(this, bytes)
        finishDraftSubmission(previousDraft, saved = pending != null)
        if (pending == null) {
            postPadResult(false, "无法保存手写图片，请检查存储空间")
            return
        }
        try {
            io.execute {
                when (Uploader.upload(url, token, bytes)) {
                    is Uploader.Result.Delivered -> {
                        ImageStore.promote(this, pending)
                        postPadResult(true, null)
                    }
                    is Uploader.Result.Offline -> postPadResult(false, "电脑不在线，图片已存入「待重试」")
                    is Uploader.Result.Failed -> postPadResult(false, "发送失败，图片已存入「待重试」")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Service stopped after saving. The image is already available for retry.
        }
    }

    private fun finishDraftSubmission(previousDraft: DraftVersion?, saved: Boolean) {
        if (previousDraft == null) return
        ui.post {
            if (draftInFlight == previousDraft) draftInFlight = null
            // A newer draft may have been created while this image was rendered.
            if (saved && previousDraft.file.lastModified() == previousDraft.modified) {
                previousDraft.file.delete()
            }
        }
    }

    private fun postPadResult(ok: Boolean, message: String?) {
        ui.post {
            if (instance !== this) return@post
            bubble?.setState(if (ok) BubbleView.State.OK else BubbleView.State.ERROR, 2500, ui)
            if (message != null && !bubbleInUse) showNotice(message)
            connectionListeners.forEach { it() }
        }
    }

    /** 在指定容器中央显示结果卡片;失败带重试 */
    private fun showResultCard(ok: Boolean, msg: String) {
        val root = overlayRoot ?: return
        showResultCardImpl(root, ok, msg,
            onRetry = {
                root.removeView(resultCard)
                resultCard = null
                overlayCrop?.let { trySend(it) }
            },
            onClose = { dismissOverlay(BubbleView.State.ERROR) }
        )
    }

    private fun showResultCardImpl(
        root: FrameLayout, ok: Boolean, msg: String,
        onRetry: () -> Unit, onClose: () -> Unit
    ) {
        resultCard?.let { root.removeView(it) }
        val palette = Ui(root.context)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = palette.shape(palette.surface, 24)
            elevation = dp(8).toFloat()
            isClickable = true
        }
        val iconView = TextView(this).apply {
            text = if (ok) "✓" else "✗"
            textSize = 34f
            setTextColor(if (ok) palette.success else palette.error)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val msgView = TextView(this).apply {
            this.text = msg
            setTextColor(palette.ink)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(if (ok) 0 else 14))
        }
        card.addView(iconView)
        card.addView(msgView)
        if (!ok) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            }
            val retry = palette.button("重试", filled = true, action = onRetry)
            val close = palette.button("关闭", action = onClose)
            row.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            row.addView(close)
            card.addView(row)
        }
        root.addView(
            card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ).apply { setMargins(dp(20), 0, dp(20), 0) }
        )
        resultCard = card
        if (ok) {
            ui.postDelayed({
                if (overlayRoot === root) dismissOverlay(BubbleView.State.OK)
            }, 750)
        }
    }

    /** 供 MainActivity 开关悬浮球调用 */
    fun onBallPrefChanged() {
        if (Prefs.showBall(this)) refreshBubble() else removeBubble()
    }

    private fun logE(where: String, t: Throwable) {
        CrashLog.e(where, t)
    }
}
