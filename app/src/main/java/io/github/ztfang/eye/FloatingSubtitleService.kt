/** 悬浮字幕前台 Service：窗口创建/拖拽/缩放 + 字幕实时更新 + 位置持久化。 */
package io.github.ztfang.eye

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.SubtitleLine
import io.github.ztfang.eye.domain.model.SubtitleType
import io.github.ztfang.eye.viewmodel.SubtitleManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null // 窗口管理器，负责添加、移除和更新悬浮窗
    private var floatingView: View? = null // 悬浮窗根视图
    private var subtitleList: LinearLayout? = null // 字幕内容容器
    private var subtitleScroll: android.widget.ScrollView? = null // 字幕滚动容器
    private lateinit var layoutParams: WindowManager.LayoutParams // 窗口布局参数，控制位置、大小、类型、标志位

    private val mainHandler = Handler(Looper.getMainLooper()) // 主线程 Handler，用于从协程切回 UI 线程
    private val screenWidthPx: Int // 屏幕宽度（像素），适配 Android 11+ 的窗口指标 API
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
            windowMetrics?.bounds?.width() ?: resources.displayMetrics.widthPixels
        } else {
            resources.displayMetrics.widthPixels
        }
    private val screenHeightPx: Int // 屏幕高度（像素），适配 Android 11+ 的窗口指标 API
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
            windowMetrics?.bounds?.height() ?: resources.displayMetrics.heightPixels
        } else {
            resources.displayMetrics.heightPixels
        }

    // 最小尺寸:宽 240dp / 高 200dp(顶部条 + 字幕区 + padding)
    private val minWidthPx: Int // 窗口最小宽度（像素）
        get() = (240 * resources.displayMetrics.density).toInt()
    private val minHeightPx: Int // 窗口最小高度（像素）
        get() = (200 * resources.displayMetrics.density).toInt()

    // 初始尺寸:宽度占屏幕宽度×90%,高度占屏幕高度×20%
    private val defaultWidthPx: Int // 窗口默认宽度（像素），限制在最小和最大尺寸之间
        get() = (screenWidthPx * 0.90f).toInt().coerceIn(minWidthPx, maxWidthPx)
    private val defaultHeightPx: Int // 窗口默认高度（像素），限制在最小和最大尺寸之间
        get() = (screenHeightPx * 0.20f).toInt().coerceIn(minHeightPx, maxHeightPx)

    // 最大尺寸:整屏
    private val maxWidthPx: Int
        get() = screenWidthPx
    private val maxHeightPx: Int
        get() = screenHeightPx

    @Inject lateinit var subtitleManager: SubtitleManager

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    /** State shared with the main screen so closing the overlay rolls the switch back. */
    private var isAttached = false
    private var isMinimized = false
    private var lastNormalHeight = 0

    /** 顶部功能区自动隐藏：语音识别时隐藏，触摸显示，3秒后自动隐藏 */
    private var topActionsView: View? = null
    private val hideTopActionsRunnable = Runnable {
        topActionsView?.visibility = View.GONE
    }

    // WakeLock so audio recording is not paused when the screen turns off.
    private var wakeLock: PowerManager.WakeLock? = null

    // Reusable TextView pool — avoids removeAllViews()/recreate churn per subtitle emit.
    private val partialTextView: TextView by lazy { createSubtitleView(partialAlpha = true) }
    private val finalTextView: TextView by lazy { createSubtitleView(partialAlpha = false) }

    /** 翻译状态指示器容器（"实时翻译中"文本） */
    private var translatingIndicator: android.view.View? = null

    // ============ 个性化设置缓存（来自 SettingsRepository，由 Service 订阅） ============
    /** 当前背景透明度 0..1，影响悬浮窗背景 alpha */
    @Volatile private var bgTransparency: Float = 0.75f
    /** 当前字体大小（sp，连续值 12f..32f），默认 14sp 更紧凑 */
    @Volatile private var fontSize: Float = 14f
    /** 当前主色 ARGB，影响按钮图标和译文颜色 */
    @Volatile private var accentColor: Int = 0xFF1A73E8.toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务创建时初始化：前台化 + WakeLock + 悬浮窗视图 + 数据流订阅 + 触摸事件 + 引擎预热。
     */
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        // 【权限排查日志】记录 Service 启动时的关键状态，用于定位闪退
        Log.i(TAG, "========== FloatingSubtitleService onCreate 开始 ==========")
        Log.i(TAG, "onCreate: SDK=${Build.VERSION.SDK_INT}, " +
                "audioSource=${subtitleManager.audioSource.value}, " +
                "hasProjection=${subtitleManager.hasMediaProjection()}")
        promoteToForeground()
        acquireWakeLock()

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_subtitle_layout, null)
        subtitleList = floatingView?.findViewById(R.id.subtitle_list)
        subtitleScroll = floatingView?.findViewById(R.id.subtitle_scroll)
        translatingIndicator = floatingView?.findViewById(R.id.translating_indicator)
        topActionsView = floatingView?.findViewById(R.id.top_actions)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 根据 Android 版本选择窗口类型：Android 8+ 使用 TYPE_APPLICATION_OVERLAY，旧版本使用 TYPE_PHONE
        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        // FLAG_NOT_FOCUSABLE:不抢输入法焦点
        // FLAG_NOT_TOUCH_MODAL:触摸事件只派发给窗口内
        // FLAG_WATCH_OUTSIDE_TOUCH:接收窗口外触摸(用于边缘检测)
        // FLAG_LAYOUT_IN_SCREEN:窗口坐标基于屏幕
        // PixelFormat.TRANSPARENT:完全透明背景，无系统窗口阴影
        layoutParams = WindowManager.LayoutParams(
            defaultWidthPx,
            defaultHeightPx,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START // 窗口定位基准点为左上角
        layoutParams.x = 0
        layoutParams.y = 0

        // Mark active immediately so the main screen switch stays in sync.
        subtitleManager.setOverlayActive(true)

        // Apply overlay geometry — only accept non-zero values, so the initial
        // 0 emitted by the StateFlow does not snap the window to 0×0.
        // 订阅窗口位置和大小的数据流，恢复上次保存的窗口状态
        serviceScope.launch {
            subtitleManager.overlayX.collectLatest { x ->
                if (x > 0) {
                    mainHandler.post {
                        layoutParams.x = x
                        clampToScreen()
                        updateViewLayoutSafely()
                    }
                }
            }
        }
        serviceScope.launch {
            subtitleManager.overlayY.collectLatest { y ->
                if (y > 0) {
                    mainHandler.post {
                        layoutParams.y = y
                        clampToScreen()
                        updateViewLayoutSafely()
                    }
                }
            }
        }
        serviceScope.launch {
            subtitleManager.overlayWidth.collectLatest { w ->
                if (w >= minWidthPx) {
                    mainHandler.post {
                        layoutParams.width = w
                        clampToScreen()
                        updateViewLayoutSafely()
                    }
                }
            }
        }
        serviceScope.launch {
            subtitleManager.overlayHeight.collectLatest { h ->
                if (h >= minHeightPx) {
                    mainHandler.post {
                        layoutParams.height = h
                        clampToScreen()
                        updateViewLayoutSafely()
                    }
                }
            }
        }
        // 订阅字幕状态流，实时更新字幕显示（支持多句历史）
        serviceScope.launch {
            subtitleManager.subtitleState.collectLatest { state ->
                mainHandler.post {
                    refreshSubtitleDisplay(
                        state.lines,
                        state.displayMode
                    )
                }
            }
        }
        // runtimeError 不再关闭 Service（改为在 MainActivity 显示 Toast）
        // 滑动悬浮窗时主线程被占用，临时错误（翻译失败/ASR not ready）会误触发 stopSelf
        // 只有用户主动点取消按钮或系统杀死才会关闭 Service
        // VAD 状态提示:字幕为空时显示"正在聆听"/"未检测到声音"
        serviceScope.launch {
            subtitleManager.vadState.collectLatest { state ->
                mainHandler.post {
                    updateVadHint(state)
                    // 语音识别中（LISTENING）延迟隐藏功能区；静音时保持显示
                    if (state == SubtitleManager.VadState.LISTENING) {
                        mainHandler.removeCallbacks(hideTopActionsRunnable)
                        mainHandler.postDelayed(hideTopActionsRunnable, HIDE_TOP_ACTIONS_DELAY_MS)
                    } else {
                        mainHandler.removeCallbacks(hideTopActionsRunnable)
                        topActionsView?.visibility = View.VISIBLE
                    }
                }
            }
        }
        // 翻译中状态 - 左上角显示"实时翻译中"文本 + 声纹波动动画
        serviceScope.launch {
            subtitleManager.isTranslating.collectLatest { isTranslating ->
                mainHandler.post {
                    translatingIndicator?.visibility = if (isTranslating) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                }
            }
        }
        // ============ 个性化设置同步 ============
        // 背景透明度变化 → 重新应用背景 alpha
        serviceScope.launch {
            subtitleManager.backgroundTransparency.collectLatest { t ->
                bgTransparency = t
                mainHandler.post { applyPersonalization() }
            }
        }
        // 字体大小变化 → 更新 TextView textSize
        serviceScope.launch {
            subtitleManager.fontSize.collectLatest { sp ->
                fontSize = sp
                mainHandler.post { applyPersonalization() }
            }
        }
        // 主色变化 → 更新按钮/译文颜色
        serviceScope.launch {
            subtitleManager.accentColorIndex.collectLatest { idx ->
                accentColor = accentColorFromIndex(idx)
                mainHandler.post { applyPersonalization() }
            }
        }

        // 设置触摸事件处理和按钮点击事件
        setupOverlayDrag()
        setupCancelButton()
        setupSettingsButton()
        setupBackButton()

        // addView last so the layoutParams have a final value; this also keeps
        // the system from briefly rendering at 0×0.
        clampToScreen()
        attachFloatingView()

        // 预热翻译引擎与 ASR 模型，确保录音启动后第一帧即可转写
        subtitleManager.ensureModelsLoaded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start audio processing in the ViewModel
        subtitleManager.startAudioProcessing()
        return START_STICKY
    }

    /**
     * 服务销毁时的清理方法。
     *
     * 销毁清理：停动画 + 取消协程 + 停音频 + 移除悬浮窗 + 释放 WakeLock。
     */
    override fun onDestroy() {
        super.onDestroy()
        // 打印调用栈，定位是谁触发了 onDestroy
        Log.w(TAG, "onDestroy called by: " + Log.getStackTraceString(Exception()))
        mainHandler.removeCallbacks(hideTopActionsRunnable)
        serviceScope.cancel()
        subtitleManager.stopAudioProcessing()
        // 只释放 MediaProjection 实例，保留 token（避免下次启动重新弹窗授权）
        subtitleManager.releaseMediaProjectionInstance()
        subtitleManager.setOverlayActive(false)
        detachFloatingView()
        releaseWakeLock()
    }

    /**
     * 提升为前台 Service。
     * Android 14+ FGS 类型：仅麦克风=MICROPHONE；应用内声音+token 就绪=MICROPHONE|MEDIA_PROJECTION。
     * token 未就绪时强制声明 MEDIA_PROJECTION 会触发 ForegroundServiceStartNotAllowedException。
     */
    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: 有 token（已授权待创建 或 已有实例）时才声明 MEDIA_PROJECTION 类型
            val hasToken = subtitleManager.hasMediaProjectionToken()
            val audioSource = subtitleManager.audioSource.value
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (audioSource == 1 && hasToken) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            Log.i(TAG, "promoteToForeground: SDK=${Build.VERSION.SDK_INT}, " +
                    "audioSource=$audioSource, hasToken=$hasToken, type=$type")
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    type
                )
                Log.i(TAG, "promoteToForeground: 成功 (type=$type)")
                // 成为前台服务后，若有待处理的 token 则创建 MediaProjection 实例
                if (audioSource == 1 && !subtitleManager.hasMediaProjection()) {
                    val created = subtitleManager.createMediaProjectionFromFgs(this)
                    Log.i(TAG, "promoteToForeground: createMediaProjectionFromFgs 结果=$created")
                }
            } catch (e: Exception) {
                // 极端情况：token 失效或系统限制，回退到纯 microphone
                Log.w(TAG, "promoteToForeground: 失败 (type=$type), 错误: ${e.message}", e)
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    Log.i(TAG, "promoteToForeground: 回退 microphone-only 成功")
                } catch (e2: Exception) {
                    Log.e(TAG, "promoteToForeground: microphone-only 也失败: ${e2.message}", e2)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "promoteToForeground: 低版本 Android，直接 startForeground")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating subtitle",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active when the live subtitle overlay is running"
            setShowBadge(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, io.github.ztfang.eye.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EyeOpener 字幕运行中")
            .setContentText("点击返回应用")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EyeOpener:FloatingSubtitle"
        ).apply {
            setReferenceCounted(false)
            // Cap at 8h to avoid runaway locks if onDestroy fails.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
            // Already released.
        }
        wakeLock = null
    }

    private fun attachFloatingView() {
        val view = floatingView ?: return
        try {
            windowManager?.addView(view, layoutParams)
            isAttached = true
            // 应用个性化初始状态（背景透明度/字体/主色）
            applyPersonalization()
        } catch (e: WindowManager.BadTokenException) {
            // Service may have been started without a valid token.
            stopSelf()
        }
    }

    private fun detachFloatingView() {
        val view = floatingView
        if (isAttached && view != null) {
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
                // Already removed.
            }
            isAttached = false
        }
    }

    private fun setupCancelButton() {
        val btnCancel = floatingView?.findViewById<View>(R.id.btn_cancel) ?: return
        btnCancel.setOnClickListener { stopSelf() }
    }

    private fun setupSettingsButton() {
        val btnSettings = floatingView?.findViewById<View>(R.id.btn_settings) ?: return
        btnSettings.setOnClickListener {
            try {
                val intent = Intent(this, io.github.ztfang.eye.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("navigate_to", "personalization")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch settings from overlay", e)
            }
        }
    }

    private fun setupBackButton() {
        val btnBack = floatingView?.findViewById<View>(R.id.btn_back) ?: return
        btnBack.setOnClickListener {
            // 返回主界面（不关闭悬浮窗）
            try {
                val intent = Intent(this, io.github.ztfang.eye.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity from back button", e)
            }
        }
    }

    /**
     * 安全地更新悬浮窗布局。
     *
     * 注意：不轻易 stopSelf()。updateViewLayout 失败可能是临时窗口状态问题，
     * 标记 isAttached=false 停止后续布局更新即可，Service 继续运行保证录音不中断。
     * 如果窗口真的被系统移除，触摸事件也会自然停止。
     */
    private fun updateViewLayoutSafely() {
        val view = floatingView
        val wm = windowManager
        if (!isAttached || view == null || wm == null) {
            Log.w(TAG, "updateViewLayoutSafely: 跳过, isAttached=$isAttached, view=$view, wm=$wm")
            return
        }
        try {
            wm.updateViewLayout(view, layoutParams)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "updateViewLayout failed: IllegalArgumentException, " +
                "x=${layoutParams.x}, y=${layoutParams.y}, w=${layoutParams.width}, h=${layoutParams.height}", e)
            isAttached = false
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "updateViewLayout failed: BadTokenException", e)
            isAttached = false
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed: unexpected, " +
                "x=${layoutParams.x}, y=${layoutParams.y}, w=${layoutParams.width}, h=${layoutParams.height}", e)
            isAttached = false
        }
    }

    /**
     * 保存悬浮窗当前位置到持久化存储。
     *
     * 在拖拽操作结束后调用，将当前窗口的 x、y 坐标保存到 subtitleManager，
     * 以便下次启动时恢复窗口位置。
     */
    private fun saveOverlayPosition() {
        serviceScope.launch {
            subtitleManager.setOverlayX(layoutParams.x)
            subtitleManager.setOverlayY(layoutParams.y)
        }
    }

    /**
     * 保存悬浮窗当前尺寸到持久化存储。
     *
     * 在缩放操作结束后调用，将当前窗口的宽度和高度保存到 subtitleManager，
     * 以便下次启动时恢复窗口大小。
     */
    private fun saveOverlaySize() {
        serviceScope.launch {
            subtitleManager.setOverlayWidth(layoutParams.width)
            subtitleManager.setOverlayHeight(layoutParams.height)
        }
    }

    /** 将窗口位置/大小约束在屏幕范围内，宽高不超最小/最大值，坐标不越界。 */
    private fun clampToScreen() {
        val currentMaxWidth = screenWidthPx
        val currentMaxHeight = screenHeightPx
        val w = layoutParams.width.coerceIn(minWidthPx, currentMaxWidth)
        val h = layoutParams.height.coerceIn(minHeightPx, currentMaxHeight)
        layoutParams.width = w
        layoutParams.height = h
        layoutParams.x = layoutParams.x.coerceIn(0, max(0, currentMaxWidth - w))
        layoutParams.y = layoutParams.y.coerceIn(0, max(0, currentMaxHeight - h))
    }

    /** 设置触摸事件：topActions 区不拦截，右下角 24dp 触发缩放，其他区域拖拽。 */
    private fun setupOverlayDrag() {
        val rootView = floatingView ?: return
        val resizeSize = (24 * resources.displayMetrics.density).toInt() // 缩放触发区域大小（24dp）
        // 拖拽触发阈值：移动超过此距离才进入拖拽/缩放，避免轻点被误判为拖拽
        val dragThreshold = (8 * resources.displayMetrics.density).toInt()

        // 拖拽/缩放状态变量
        var startX = 0f // 触摸起始点的原始 X 坐标
        var startY = 0f // 触摸起始点的原始 Y 坐标
        var startXparam = 0 // 拖拽开始时窗口的 X 坐标
        var startYparam = 0 // 拖拽开始时窗口的 Y 坐标
        var startWidth = 0 // 缩放开始时窗口的宽度
        var startHeight = 0 // 缩放开始时窗口的高度
        var isDragging = false // 是否正在拖拽
        var isResizing = false // 是否正在缩放
        var mayResize = false // DOWN 时在缩放区域，待 MOVE 确认

        rootView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 全框触发：触摸任意位置都显示功能区
                    showTopActionsTemporarily()
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    isResizing = false
                    // 判断是否在右下角缩放区域
                    val w = rootView.width
                    val h = rootView.height
                    mayResize = w > 0 && h > 0 && event.x >= w - resizeSize && event.y >= h - resizeSize
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isAttached) {
                        isDragging = false
                        isResizing = false
                        return@setOnTouchListener true
                    }
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    // 移动超过阈值才进入拖拽/缩放模式
                    if (!isDragging && !isResizing) {
                        if (dx * dx + dy * dy > dragThreshold * dragThreshold) {
                            if (mayResize) {
                                isResizing = true
                                startWidth = layoutParams.width
                                startHeight = layoutParams.height
                            } else {
                                isDragging = true
                                startXparam = layoutParams.x
                                startYparam = layoutParams.y
                            }
                        } else {
                            return@setOnTouchListener true // 还没超过阈值，继续等
                        }
                    }
                    if (isResizing) {
                        val newWidth = (startWidth + dx).toInt()
                        val newHeight = (startHeight + dy).toInt()
                        layoutParams.width = newWidth.coerceIn(minWidthPx, maxWidthPx)
                        layoutParams.height = newHeight.coerceIn(minHeightPx, maxHeightPx)
                        clampToScreen()
                        updateViewLayoutSafely()
                    } else if (isDragging) {
                        layoutParams.x = startXparam + dx.toInt()
                        layoutParams.y = startYparam + dy.toInt()
                        clampToScreen()
                        updateViewLayoutSafely()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.d(TAG, "ACTION_${event.actionMasked}: dragging=$isDragging, resizing=$isResizing, " +
                        "x=${layoutParams.x}, y=${layoutParams.y}, w=${layoutParams.width}, h=${layoutParams.height}")
                    if (isAttached) {
                        if (isDragging) saveOverlayPosition()
                        if (isResizing) saveOverlaySize()
                    }
                    val wasInteracting = isDragging || isResizing
                    isDragging = false
                    isResizing = false
                    mayResize = false
                    // 轻点（未拖拽/缩放）不拦截事件，让子 view（如按钮）能收到点击
                    if (wasInteracting) true else false
                }
                else -> false
            }
        }
    }

    private fun MotionEvent.isWithinView(view: View?): Boolean {
        if (view == null) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = rawX.toInt()
        val y = rawY.toInt()
        return x >= location[0] && x <= location[0] + view.width &&
            y >= location[1] && y <= location[1] + view.height
    }

    private fun createSubtitleView(partialAlpha: Boolean): TextView {
        val color = if (partialAlpha) 0x80FFFFFF.toInt() else 0xFFFFFFFF.toInt()
        return TextView(this).apply {
            textSize = fontSize
            setTextColor(color)
            // Empty placeholder so the first setText does not show stale text.
            text = ""
        }
    }

    /**
     * 主色索引转 ARGB。
     * 与 PersonalizationScreen 的 SwatchPalette 6 色保持一致：
     * 0=紫 1=蓝 2=绿 3=橙 4=红 5=黑
     */
    private fun accentColorFromIndex(index: Int): Int = when (index) {
        0 -> 0xFF8B7FD8.toInt() // 紫
        1 -> 0xFF1A73E8.toInt() // 蓝
        2 -> 0xFF2EB89A.toInt() // 绿
        3 -> 0xFFFF8F00.toInt() // 橙
        4 -> 0xFFE53935.toInt() // 红
        5 -> 0xFF2C2C2C.toInt() // 黑
        else -> 0xFF1A73E8.toInt()
    }

    /**
     * 应用个性化设置到悬浮窗 UI。
     * 在主线程调用：更新背景透明度、字幕字体大小、按钮图标颜色。
     */
    private fun applyPersonalization() {
        val view = floatingView ?: return

        // 1. 背景透明度：通过 root View 的 alpha 实现（0.4..1.0）
        val bgAlpha = 0.4f + bgTransparency * 0.6f
        view.alpha = bgAlpha

        // 2. 字体大小
        partialTextView.textSize = fontSize
        finalTextView.textSize = fontSize

        // 3. 字体颜色：恢复纯白（accentColor 仅用于按钮等强调色）
        try {
            partialTextView.setTextColor(0x80FFFFFF.toInt())
            finalTextView.setTextColor(0xFFFFFFFF.toInt())
        } catch (_: Exception) {
        }

        // 4. 刷新字幕显示，应用新颜色
        val state = subtitleManager.subtitleState.value
        refreshSubtitleDisplay(state.lines, state.displayMode)
    }

    /** 当前是否有实际字幕内容(用于判断是否显示 VAD 提示) */
    private var hasSubtitleContent = false

    /** VAD 提示 TextView(懒加载,只在需要时创建) */
    private val vadHintTextView: TextView by lazy {
        TextView(this).apply {
            textSize = 16f
            setTextColor(0x80FFFFFF.toInt()) // 半透明白色
            text = "未检测到声音"
        }
    }

    /**
     * 更新 VAD 静音提示。仅在无字幕且持续静音时显示"未检测到声音"。
     */
    private fun updateVadHint(state: SubtitleManager.VadState) {
        val list = subtitleList ?: return
        val showHint = !hasSubtitleContent && state == SubtitleManager.VadState.SILENT
        if (showHint) {
            vadHintTextView.text = "未检测到声音"
            if (vadHintTextView.parent != list) {
                list.removeAllViews()
                list.addView(vadHintTextView)
            }
        } else {
            if (vadHintTextView.parent == list) {
                list.removeView(vadHintTextView)
            }
        }
    }

    /**
     * 触摸时临时显示顶部功能区，3秒后自动隐藏（仅在语音识别中生效）。
     * 静音状态下保持常显，不触发自动隐藏。
     */
    private fun showTopActionsTemporarily() {
        val view = topActionsView ?: return
        view.visibility = View.VISIBLE
        // 只有在语音识别中（LISTENING）才自动隐藏；静音时常显
        if (subtitleManager.vadState.value == SubtitleManager.VadState.LISTENING) {
            mainHandler.removeCallbacks(hideTopActionsRunnable)
            mainHandler.postDelayed(hideTopActionsRunnable, HIDE_TOP_ACTIONS_DELAY_MS)
        }
    }

    /**
     * 刷新字幕显示：视图复用 + 保留最近 3 句历史 + partial 半透明/final 不透明。
     */
    private fun refreshSubtitleDisplay(
        lines: List<SubtitleLine>,
        displayMode: DisplayMode
    ) {
        val list = subtitleList ?: return
        val hasContent = lines.isNotEmpty()
        hasSubtitleContent = hasContent

        if (!hasContent) {
            list.removeAllViews()
            updateVadHint(subtitleManager.vadState.value)
            return
        }

        if (vadHintTextView.parent == list) {
            list.removeView(vadHintTextView)
        }

        list.removeAllViews()

        for ((index, line) in lines.withIndex()) {
            val isPartial = line.subtitleType == SubtitleType.PARTIAL
            val alpha = if (isPartial) 0x80 else 0xFF
            // 字体颜色：纯白，partial 半透明、final 全透明
            val textColor = (alpha shl 24) or 0x00FFFFFF

            // 文本容器（垂直）
            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            when (displayMode) {
                DisplayMode.SOURCE_ONLY -> {
                    val tv = createSubtitleView(isPartial)
                    tv.text = line.sourceText
                    tv.setTextColor(textColor)
                    textContainer.addView(tv)
                }
                DisplayMode.TRANSLATION_ONLY -> {
                    val tv = createSubtitleView(false)
                    tv.text = line.translatedText.ifBlank { line.sourceText }
                    tv.setTextColor(textColor)
                    textContainer.addView(tv)
                }
                DisplayMode.BILINGUAL -> {
                    // 原文在上，译文在下；都用纯白
                    val sourceTv = createSubtitleView(isPartial)
                    sourceTv.text = line.sourceText
                    sourceTv.setTextColor(textColor)
                    textContainer.addView(sourceTv)

                    if (line.translatedText.isNotBlank()) {
                        val transTv = createSubtitleView(false)
                        transTv.text = line.translatedText
                        transTv.setTextColor(0xFFFFFFFF.toInt())
                        textContainer.addView(transTv)
                    }
                }
            }

            // 蓝绿渐变侧边栏 + 文本内容，水平排列
            val rowContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 蓝绿渐变侧边栏：宽度=4dp，高度随文本内容增长
            val density = resources.displayMetrics.density
            val barWidthPx = (4 * density).toInt()
            val sidebar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    barWidthPx,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                // 青蓝→绿垂直渐变，2dp圆角（参考 sidebar_gradient.xml 原始样式）
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#4DD0E1"), Color.parseColor("#81C784"))
                ).apply { cornerRadius = 2 * density }
            }
            rowContainer.addView(sidebar)

            // 文本区域加左边距
            val textParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                marginStart = (8 * density).toInt()
            }
            textContainer.layoutParams = textParams
            rowContainer.addView(textContainer)

            list.addView(rowContainer)

            if (index < lines.size - 1) {
                val spacer = View(this)
                spacer.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (6 * resources.displayMetrics.density).toInt()
                )
                list.addView(spacer)
            }
        }

        // 自动滚动到底部，显示最新内容
        subtitleScroll?.post {
            subtitleScroll?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private companion object {
        const val TAG = "FloatingSubtitleService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "floating_subtitle"
        // 8h: longest sensible meeting / lecture session.
        const val WAKE_LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L
        // 顶部功能区自动隐藏延迟（毫秒）
        const val HIDE_TOP_ACTIONS_DELAY_MS = 3000L
    }
}
