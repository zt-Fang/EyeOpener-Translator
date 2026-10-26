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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.domain.model.SubtitleLine
import io.github.ztfang.eye.domain.model.SubtitleType
import io.github.ztfang.eye.viewmodel.SubtitleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class FloatingSubtitleService : Service() {
    private var windowManager: WindowManager? = null // 窗口管理器，负责添加、移除和更新悬浮窗
    private var floatingView: View? = null // 悬浮窗根视图
    private var subtitleList: LinearLayout? = null // 字幕内容容器
    private var subtitleScroll: android.widget.ScrollView? = null // 字幕滚动容器
    private lateinit var layoutParams: WindowManager.LayoutParams // 窗口布局参数，控制位置、大小、类型、标志位

    private val mainHandler = Handler(Looper.getMainLooper()) // 主线程 Handler，用于从协程切回 UI 线程
    private val screenWidthPx: Int // 屏幕宽度（像素），适配 Android 11+ 的窗口指标 API
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics =
                    getSystemService(WindowManager::class.java)
                        ?.currentWindowMetrics
                windowMetrics?.bounds?.width() ?: resources.displayMetrics.widthPixels
            } else {
                resources.displayMetrics.widthPixels
            }
    private val screenHeightPx: Int // 屏幕高度（像素），适配 Android 11+ 的窗口指标 API
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics =
                    getSystemService(WindowManager::class.java)
                        ?.currentWindowMetrics
                windowMetrics?.bounds?.height() ?: resources.displayMetrics.heightPixels
            } else {
                resources.displayMetrics.heightPixels
            }

    // 最小尺寸：宽 140dp；高按功能区状态给下限（显示 108dp / 收起 70dp，收起时只剩一行字幕）
    private val minWidthPx: Int // 窗口最小宽度（像素）
        get() = (MIN_WIDTH_DP * resources.displayMetrics.density).toInt()
    private val minHeightPx: Int // 窗口最小高度（像素）
        get() {
            val dp =
                if (topActionsView?.visibility == View.VISIBLE) {
                    MIN_HEIGHT_WITH_CONTROLS_DP
                } else {
                    MIN_HEIGHT_COMPACT_DP
                }
            return (dp * resources.displayMetrics.density).toInt()
        }

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

    /** 顶部功能区（设置/返回/取消）与拖动/缩放提示条：显隐只由轻点浮窗控制。 */
    private var topActionsView: View? = null

    /** 展开顶部功能区前的高度：收起时要还原回去，否则「一行高度→点开→再收起」会永久变大。 */
    private var preExpandHeightPx = 0

    /** 翻译状态指示器的显隐去抖。 */
    private var translatingShowAtMs = 0L
    private val showTranslatingIndicatorRunnable =
        Runnable {
            translatingIndicator?.let {
                setVisibilityIfChanged(it, true)
                translatingShowAtMs = SystemClock.uptimeMillis()
            }
        }
    private val hideTranslatingIndicatorRunnable =
        Runnable {
            translatingIndicator?.let { setVisibilityIfChanged(it, false) }
        }

    /** 顶部功能区显隐（拖动/缩放提示条同步）。 */
    private fun setTopActionsVisible(visible: Boolean) {
        val v = topActionsView ?: return
        setVisibilityIfChanged(v, visible)
        dragHandleView?.let { setVisibilityIfChanged(it, visible) }
        resizeHandleView?.let { setVisibilityIfChanged(it, visible) }
        if (BuildConfig.DEBUG) Log.d(TAG, "topActions -> ${if (visible) "VISIBLE" else "GONE"}")
    }

    // WakeLock so audio recording is not paused when the screen turns off.
    private var wakeLock: PowerManager.WakeLock? = null

    /** 翻译状态指示器容器（"实时翻译中"文本） */
    private var translatingIndicator: android.view.View? = null

    /** 拖动/缩放示意条：与顶部功能区同步显隐（轻点浮窗切换）。 */
    private var dragHandleView: View? = null
    private var resizeHandleView: View? = null

    /**
     * 状态提示条：渲染 runtimeError、离线模型失败态与下载中提示，
     * 让用户切到别的 App 用悬浮字幕时也能看到原因。
     * 具体文案与优先级见 [renderStatusHint]。
     */
    private var errorTextView: TextView? = null

    // ============ 字幕视图复用 + 刷新节流 ============
    // 背景：ASR partial 每秒可产生多次状态变更，旧实现每次都在主线程 removeAllViews() 全量
    // 重建视图（含新建 GradientDrawable），叠加 ML Kit 回调（强制回主线程）后会把主线程压死。
    // 实测曾触发 `am_anr: Input dispatching timed out ... Waited 5002ms` → 进程被系统杀掉。
    // 因此这里做两件事：① 结构不变时只更新文本不重建视图；② 合并高频刷新到最小间隔。

    /** 一行字幕对应的视图句柄，用于复用（transTv 仅双语对照模式存在） */
    private class LineRowViews(
        val row: LinearLayout,
        val sourceTv: TextView,
        val transTv: TextView?,
    )

    /** 已渲染的行视图缓存；与 [renderedMode] 一起描述当前视图结构 */
    private val renderedRows = mutableListOf<LineRowViews>()

    /** 上次渲染使用的显示模式；与行数共同决定是否需要重建视图结构 */
    private var renderedMode: DisplayMode? = null

    /** 上次贴底时的末行签名，避免重复滚动 */
    private var lastScrolledTail: String = ""

    /** 上次上屏内容签名，用于 DEBUG 渲染快照去重。 */
    private var lastRenderSnapshot: String = ""

    /** 上一次贴底的原文，用于判断是否出现新的一条（决定要不要播上滑动画） */

    /** 上滑动画是否正在播（避免叠加导致跳动） */
    private var slideAnimating = false
    private var lastBottomSource: String = ""

    /** 待渲染的字幕快照（由 [scheduleSubtitleRefresh] 在主线程消费） */
    private var pendingLines: List<SubtitleLine> = emptyList()
    private var pendingMode: DisplayMode = DisplayMode.BILINGUAL

    /** 上次实际执行刷新的时刻（uptimeMillis），用于节流 */
    private var lastRefreshUptimeMs: Long = 0L

    /** 合并后的刷新任务 */
    private val refreshSubtitleRunnable =
        Runnable {
            lastRefreshUptimeMs = android.os.SystemClock.uptimeMillis()
            refreshSubtitleDisplay(pendingLines, pendingMode)
        }

    // ============ 个性化设置缓存（来自 SettingsRepository，由 Service 订阅） ============

    /** 当前背景透明度 0..1，影响悬浮窗背景 alpha */
    @Volatile private var bgTransparency: Float = 1.0f

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
        Log.i(
            TAG,
            "onCreate: SDK=${Build.VERSION.SDK_INT}, " +
                "audioSource=${subtitleManager.audioSource.value}, " +
                "hasProjection=${subtitleManager.hasMediaProjection()}",
        )
        promoteToForeground()
        acquireWakeLock()

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_subtitle_layout, null)
        subtitleList = floatingView?.findViewById(R.id.subtitle_list)
        subtitleScroll = floatingView?.findViewById(R.id.subtitle_scroll)
        // 浮窗高度变化（缩放/功能区显隐）后重新贴到底部
        subtitleScroll?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) scrollToBottom()
        }
        // 内容高度变化（新一条上屏）后同样贴底，保证最新一条始终在下沿
        subtitleList?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) scrollToBottom()
        }
        translatingIndicator = floatingView?.findViewById(R.id.translating_indicator)
        errorTextView = floatingView?.findViewById(R.id.subtitle_error)
        topActionsView = floatingView?.findViewById(R.id.top_actions)
        dragHandleView = floatingView?.findViewById(R.id.drag_handle)
        resizeHandleView = floatingView?.findViewById(R.id.resize_handle)
        // 文本区的 ScrollView 会消费触摸，所以轻点由根容器旁路观察
        (floatingView as? OverlayRootLayout)?.onTapListener = { x, y -> onOverlayTap(x, y) }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 根据 Android 版本选择窗口类型：Android 8+ 使用 TYPE_APPLICATION_OVERLAY，旧版本使用 TYPE_PHONE
        val typeFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

        // FLAG_NOT_FOCUSABLE:不抢输入法焦点
        // FLAG_NOT_TOUCH_MODAL:触摸事件只派发给窗口内
        // FLAG_WATCH_OUTSIDE_TOUCH:接收窗口外触摸(用于边缘检测)
        // FLAG_LAYOUT_IN_SCREEN:窗口坐标基于屏幕
        // PixelFormat.TRANSPARENT:完全透明背景，无系统窗口阴影
        layoutParams =
            WindowManager.LayoutParams(
                defaultWidthPx,
                defaultHeightPx,
                typeFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT,
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
        // 经 scheduleSubtitleRefresh 合并高频更新，避免每次 partial 都在主线程重建视图
        serviceScope.launch {
            subtitleManager.subtitleState.collectLatest { state ->
                scheduleSubtitleRefresh(state.lines, state.displayMode)
            }
        }
        // 状态提示条：把 runtimeError 与"离线模型下载中"合并渲染到悬浮窗内。
        //
        // 为什么合并：runtimeError 原本单独控制这条提示，用户切在别的 App 上看字幕时
        // 只能看到失败、看不到"正在下载"（表现为"只有原文没译文，也不知道在等什么"）。
        // 两个流各自 collectLatest 会互相覆盖，故合并后按优先级统一渲染：
        // 错误 > 下载中 > 隐藏。
        //
        // 这里只读不清：错误由 MainActivity 前台或下次成功翻译后清除。
        serviceScope.launch {
            combine(
                subtitleManager.runtimeError,
                subtitleManager.translationModelState,
            ) { err, modelState -> err to modelState }
                .collectLatest { (err, modelState) ->
                    mainHandler.post { renderStatusHint(err, modelState) }
                }
        }
        // runtimeError 不再关闭 Service（改为在 MainActivity 显示 Toast）
        // 滑动悬浮窗时主线程被占用，临时错误（翻译失败/ASR not ready）会误触发 stopSelf
        // 只有用户主动点取消按钮或系统杀死才会关闭 Service
        // VAD 状态提示:字幕为空时显示"正在聆听"/"未检测到声音"
        // 顶部功能区不随 VAD 自动收放（连读时会反复抖动），只由轻点浮窗切换
        serviceScope.launch {
            subtitleManager.vadState.collectLatest { state ->
                mainHandler.post { updateVadHint(state) }
            }
        }
        // 翻译中指示器：延迟显示 + 最短可见，避免本地引擎 20~40ms 的翻译亮一帧就灭
        serviceScope.launch {
            subtitleManager.isTranslating.collectLatest { isTranslating ->
                mainHandler.post {
                    if (isTranslating) {
                        mainHandler.removeCallbacks(hideTranslatingIndicatorRunnable)
                        mainHandler.removeCallbacks(showTranslatingIndicatorRunnable)
                        mainHandler.postDelayed(showTranslatingIndicatorRunnable, TRANSLATING_INDICATOR_SHOW_DELAY_MS)
                    } else {
                        mainHandler.removeCallbacks(showTranslatingIndicatorRunnable)
                        mainHandler.removeCallbacks(hideTranslatingIndicatorRunnable)
                        val shownFor = SystemClock.uptimeMillis() - translatingShowAtMs
                        val keepVisibleMs = TRANSLATING_INDICATOR_MIN_VISIBLE_MS - shownFor
                        if (translatingIndicator?.visibility == View.VISIBLE && keepVisibleMs > 0) {
                            mainHandler.postDelayed(hideTranslatingIndicatorRunnable, keepVisibleMs)
                        } else {
                            hideTranslatingIndicatorRunnable.run()
                        }
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
        mainHandler.removeCallbacks(showTranslatingIndicatorRunnable)
        mainHandler.removeCallbacks(hideTranslatingIndicatorRunnable)
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
            Log.i(
                TAG,
                "promoteToForeground: SDK=${Build.VERSION.SDK_INT}, " +
                    "audioSource=$audioSource, hasToken=$hasToken, type=$type",
            )
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    type,
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
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                    Log.i(TAG, "promoteToForeground: 回退 microphone-only 成功")
                } catch (e2: Exception) {
                    Log.e(TAG, "promoteToForeground: microphone-only 也失败: ${e2.message}", e2)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "promoteToForeground: 低版本 Android，直接 startForeground")
            // API 29–33 没有 FGS 类型约束，但同样需要把已授权的 token 兑换成 MediaProjection 实例，
            // 否则「应用内声音」永远拿不到投射实例，开关会在几秒后被回滚关闭。
            val audioSource = subtitleManager.audioSource.value
            if (audioSource == 1 && !subtitleManager.hasMediaProjection()) {
                val created = subtitleManager.createMediaProjectionFromFgs(this)
                Log.i(TAG, "promoteToForeground: createMediaProjectionFromFgs 结果=$created")
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Floating subtitle",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active when the live subtitle overlay is running"
                setShowBadge(false)
                setSound(null, null)
            }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, io.github.ztfang.eye.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
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
        wakeLock =
            pm
                .newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "EyeOpener:FloatingSubtitle",
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
                val intent =
                    Intent(this, io.github.ztfang.eye.MainActivity::class.java).apply {
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
                val intent =
                    Intent(this, io.github.ztfang.eye.MainActivity::class.java).apply {
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
            Log.e(
                TAG,
                "updateViewLayout failed: IllegalArgumentException, " +
                    "x=${layoutParams.x}, y=${layoutParams.y}, w=${layoutParams.width}, h=${layoutParams.height}",
                e,
            )
            isAttached = false
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "updateViewLayout failed: BadTokenException", e)
            isAttached = false
        } catch (e: Exception) {
            Log.e(
                TAG,
                "updateViewLayout failed: unexpected, " +
                    "x=${layoutParams.x}, y=${layoutParams.y}, w=${layoutParams.width}, h=${layoutParams.height}",
                e,
            )
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

    /** 触摸点是否落在字幕滚动区内（文本区留给 ScrollView 自己滚）。 */
    private fun isInsideScrollArea(
        rawX: Float,
        rawY: Float,
    ): Boolean {
        val scroll = subtitleScroll ?: return false
        if (scroll.width <= 0 || scroll.height <= 0) return false
        val loc = IntArray(2)
        scroll.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + scroll.width &&
            rawY >= loc[1] && rawY <= loc[1] + scroll.height
    }

    /** 拖动/缩放浮窗：右下角 [RESIZE_TOUCH_SIZE_DP] 内起手为缩放，其余为拖动。 */
    private fun setupOverlayDrag() {
        val root = floatingView as? OverlayRootLayout ?: return
        val resizeSize = (RESIZE_TOUCH_SIZE_DP * resources.displayMetrics.density).toInt()
        val dragThreshold = (8 * resources.displayMetrics.density).toInt()

        var startRawX = 0f
        var startRawY = 0f
        var startXparam = 0
        var startYparam = 0
        var startWidth = 0
        var startHeight = 0
        var isDragging = false
        var isResizing = false
        var mayResize = false
        // 本次手势是否属于「字幕滚动区」：属于就完全不介入，让 ScrollView 正常滚动
        var scrollOwnsGesture = false

        root.shouldIntercept = { !scrollOwnsGesture && (isDragging || isResizing) }

        // 用局部函数承载手势状态机：这样内部的 return 就是普通返回（赋给属性时没有 lambda 标签）
        fun handleGesture(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    isResizing = false
                    val w = root.width
                    val h = root.height
                    mayResize = w > 0 && h > 0 && event.x >= w - resizeSize && event.y >= h - resizeSize
                    // 缩放热区优先；其余落在字幕区的触摸交回 ScrollView
                    scrollOwnsGesture = !mayResize && isInsideScrollArea(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isAttached || scrollOwnsGesture) {
                        isDragging = false
                        isResizing = false
                        return
                    }
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && !isResizing) {
                        if (dx * dx + dy * dy <= dragThreshold * dragThreshold) return
                        if (mayResize) {
                            isResizing = true
                            startWidth = layoutParams.width
                            startHeight = layoutParams.height
                        } else {
                            isDragging = true
                            startXparam = layoutParams.x
                            startYparam = layoutParams.y
                        }
                    }
                    if (isResizing) {
                        layoutParams.width =
                            (startWidth + dx).toInt().coerceIn(minWidthPx, maxWidthPx)
                        layoutParams.height =
                            (startHeight + dy).toInt().coerceIn(minHeightPx, maxHeightPx)
                        clampToScreen()
                        updateViewLayoutSafely()
                    } else if (isDragging) {
                        layoutParams.x = startXparam + dx.toInt()
                        layoutParams.y = startYparam + dy.toInt()
                        clampToScreen()
                        updateViewLayoutSafely()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.d(
                        TAG,
                        "ACTION_${event.actionMasked}: dragging=$isDragging, resizing=$isResizing, " +
                            "x=${layoutParams.x}, y=${layoutParams.y}, w=${layoutParams.width}, h=${layoutParams.height}",
                    )
                    if (isAttached) {
                        if (isDragging) saveOverlayPosition()
                        if (isResizing) {
                            // 用户手动改了尺寸，之前记下的「展开前高度」就作废了
                            preExpandHeightPx = 0
                            saveOverlaySize()
                        }
                    }
                    isDragging = false
                    isResizing = false
                    mayResize = false
                    scrollOwnsGesture = false
                }
            }
        }

        root.touchObserver = { event -> handleGesture(event) }
    }

    private fun MotionEvent.isWithinView(view: View?): Boolean {
        if (view == null) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = rawX.toInt()
        val y = rawY.toInt()
        return x >= location[0] &&
            x <= location[0] + view.width &&
            y >= location[1] &&
            y <= location[1] + view.height
    }

    private fun createSubtitleView(textSizeSp: Float = fontSize): TextView =
        TextView(this).apply {
            textSize = textSizeSp
            // Empty placeholder so the first setText does not show stale text.
            text = ""
        }

    /** 原文字号：比译文小 2 号。 */
    private fun sourceTextSize(): Float = (fontSize - SOURCE_TEXT_SIZE_OFFSET_SP).coerceAtLeast(10f)

    /**
     * 主色索引转 ARGB。
     * 与 PersonalizationScreen 的 SwatchPalette 6 色保持一致：
     * 0=紫 1=蓝 2=绿 3=橙 4=红 5=黑
     */
    private fun accentColorFromIndex(index: Int): Int =
        when (index) {
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

        // 2. 字体大小：单语模式用默认字号，双语模式原文小 2 号
        val single = renderedMode != DisplayMode.BILINGUAL
        renderedRows.forEach {
            it.sourceTv.textSize = if (single) fontSize else sourceTextSize()
            it.transTv?.textSize = fontSize
        }

        // 4. 重建字幕视图以应用新字号/配色（视图复用缓存需作废）
        val state = subtitleManager.subtitleState.value
        invalidateSubtitleViews()
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
     * 渲染悬浮窗内的状态提示条。
     *
     * 优先级：离线模型失败 > 其它错误 > 离线模型下载中 > 隐藏。
     *
     * 下载中也要显示的原因：用户开了浮窗后通常就切到别的 App 了，此时主屏的状态条不可见。
     * 若悬浮窗只呈现"有原文没译文"，用户无从判断是在慢慢下载还是彻底坏了。
     *
     * 注意这里只给短文案：浮窗宽度有限，且失败的技术细节（域名/IP/证书）已收进
     * 「设置 → 翻译模型诊断」，不适合往浮层里塞。
     */
    private fun renderStatusHint(
        err: String?,
        modelState: SubtitleManager.TranslationModelState,
    ) {
        val tv = errorTextView ?: return
        when {
            modelState.status == SubtitleManager.TranslationModelStatus.FAILED -> {
                tv.text = getString(R.string.trans_model_floating_failed)
                tv.visibility = View.VISIBLE
            }

            !err.isNullOrBlank() -> {
                tv.text = err
                tv.visibility = View.VISIBLE
            }

            modelState.status == SubtitleManager.TranslationModelStatus.DOWNLOADING -> {
                tv.text =
                    getString(
                        R.string.trans_model_floating_downloading,
                        formatCompactDuration(modelState.elapsedMs),
                    )
                tv.visibility = View.VISIBLE
            }

            else -> tv.visibility = View.GONE
        }
    }

    /**
     * 把毫秒格式化为紧凑形式（"1m12s" / "12s"）。
     * 悬浮窗空间小，用紧凑写法；主屏状态条仍用"已等待 1 分 12 秒"的可读写法。
     */
    private fun formatCompactDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            getString(R.string.trans_model_duration_compact_min_sec, minutes.toInt(), seconds.toInt())
        } else {
            getString(R.string.trans_model_duration_compact_sec, seconds.toInt())
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

    /** 轻点浮窗：切换顶部功能区与提示条显隐，无自动显隐。 */
    private fun toggleTopActions() {
        val view = topActionsView ?: return
        val willShow = view.visibility != View.VISIBLE
        if (willShow) {
            // 收起态可能比「带功能区的下限」矮，先记下当前高度，收起时还原
            preExpandHeightPx = layoutParams.height
        }
        setTopActionsVisible(willShow)
        if (!isAttached) return
        if (!willShow && preExpandHeightPx > 0) {
            // 收起后还原到展开前的高度（仍受收起态最小高度约束）
            layoutParams.height = preExpandHeightPx.coerceIn(minHeightPx, maxHeightPx)
            preExpandHeightPx = 0
        }
        // 最小高度随功能区显隐变化，切换后重新收敛一次尺寸
        clampToScreen()
        updateViewLayoutSafely()
    }

    /** 轻点浮窗：落在功能区按钮上交给按钮，其余位置切换显隐（浮窗内坐标）。 */
    private fun onOverlayTap(
        x: Float,
        y: Float,
    ) {
        val actions = topActionsView
        if (actions != null &&
            actions.visibility == View.VISIBLE &&
            x >= actions.left &&
            x <= actions.right &&
            y >= actions.top &&
            y <= actions.bottom
        ) {
            return
        }
        toggleTopActions()
    }

    /** 集中式显隐：状态没变就不动 view，避免无意义的重绘（也避免 setVisibility 抖动）。 */
    private fun setVisibilityIfChanged(
        view: View,
        visible: Boolean,
    ) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) view.visibility = target
    }

    /**
     * 合并高频字幕刷新后再更新 UI。
     *
     * ASR partial 每秒可产生多次状态变更，旧实现每次都在主线程 removeAllViews() 重建视图，
     * 叠加 ML Kit 回调（强制回主线程）后会把主线程压死（实测触发 Input dispatching 超时 ANR）。
     * 这里以 [SUBTITLE_REFRESH_MIN_INTERVAL_MS] 为最小间隔合并刷新；末尾更新用 postDelayed
     * 补一次，保证不丢最后一帧。
     */
    private fun scheduleSubtitleRefresh(
        lines: List<SubtitleLine>,
        mode: DisplayMode,
    ) {
        pendingLines = lines
        pendingMode = mode
        mainHandler.removeCallbacks(refreshSubtitleRunnable)
        val elapsed = android.os.SystemClock.uptimeMillis() - lastRefreshUptimeMs
        val wait = (SUBTITLE_REFRESH_MIN_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        mainHandler.postDelayed(refreshSubtitleRunnable, wait)
    }

    /** 丢弃缓存的字幕视图（字体/配色变化后调用，强制下次刷新重建） */
    private fun invalidateSubtitleViews() {
        clearSubtitleRows()
    }

    /**
     * 刷新字幕显示：视图复用 + 保留最近 3 句历史 + partial 半透明/final 不透明。
     *
     * 性能关键：结构（行数 + 显示模式）不变时只更新 TextView 文本，不 removeAllViews、
     * 不重建侧边条 GradientDrawable；仅当行数或显示模式变化时才重建视图。
     */
    private fun refreshSubtitleDisplay(
        lines: List<SubtitleLine>,
        displayMode: DisplayMode,
    ) {
        val list = subtitleList ?: return
        hasSubtitleContent = lines.isNotEmpty()

        if (lines.isEmpty()) {
            clearSubtitleRows()
            updateVadHint(subtitleManager.vadState.value)
            return
        }

        if (vadHintTextView.parent == list) {
            list.removeView(vadHintTextView)
        }

        ensureSlotRows(displayMode)

        // 固定 5 格从下往上填：最新一条固定在最下沿，其余依次向上
        val visible = lines.takeLast(MAX_SUBTITLE_ROWS)
        val startSlot = MAX_SUBTITLE_ROWS - visible.size
        for (slot in 0 until MAX_SUBTITLE_ROWS) {
            val holder = renderedRows[slot]
            val line = visible.getOrNull(slot - startSlot)
            if (line == null) {
                if (holder.row.visibility != View.GONE) holder.row.visibility = View.GONE
                continue
            }
            if (holder.row.visibility != View.VISIBLE) holder.row.visibility = View.VISIBLE
            val isDraft = line.subtitleType == SubtitleType.PARTIAL
            // 草稿过半行就提前变亮，不必等到整句收口
            val halfDoneDraft =
                isDraft && line.sourceText.length * 2 >= subtitleManager.rowCapacityChars()
            // 原文始终偏暗；译文草稿未过半行时更暗，其余（含过半行草稿）最亮
            val translationColor =
                if (isDraft && !halfDoneDraft) TRANSLATION_DRAFT_COLOR else TRANSLATION_TEXT_COLOR
            when (displayMode) {
                DisplayMode.SOURCE_ONLY ->
                    // 只看原文时用最亮，方便阅读
                    setTextIfChanged(holder.sourceTv, line.sourceText, TRANSLATION_TEXT_COLOR)
                DisplayMode.TRANSLATION_ONLY -> {
                    // 只显示译文：译文没到时用占位符，不能拿原文顶上（否则「仅译文」会冒出英文）
                    val shown = line.translatedText.ifBlank { TRANSLATION_PLACEHOLDER }
                    setTextIfChanged(holder.sourceTv, shown, translationColor)
                }
                DisplayMode.BILINGUAL -> {
                    setTextIfChanged(holder.sourceTv, line.sourceText, SOURCE_TEXT_COLOR)
                    val transTv = holder.transTv
                    if (transTv != null) {
                        // 译文没到时也占住一行（不可见占位符），否则译文到达时整行变高、原文会跳一下
                        val shown = line.translatedText.ifBlank { TRANSLATION_PLACEHOLDER }
                        if (transTv.visibility != View.VISIBLE) transTv.visibility = View.VISIBLE
                        setTextIfChanged(transTv, shown, translationColor)
                    }
                }
            }
        }

        // DEBUG：记录真正上屏的行数、原文与译文（uiautomator 抓不到浮窗文本）
        if (BuildConfig.DEBUG) {
            val signature =
                lines.joinToString("\u241E") {
                    "${it.subtitleType}|${it.sourceText}|${it.translatedText}"
                }
            if (signature != lastRenderSnapshot) {
                lastRenderSnapshot = signature
                Log.d(TAG, "render[${lines.size}]: " + lines.joinToString(" ⏎ ") { line ->
                    val kind = if (line.subtitleType == SubtitleType.PARTIAL) "草稿" else "定稿"
                    "[$kind]原=${line.sourceText}｜译=${line.translatedText.ifBlank { "—" }}"
                })
                // 排版后记录折行、溢出、贴底位置与字号/配色（延迟一拍，等 scroll 落位）
                list.postDelayed({
                    val viewportH = subtitleScroll?.height ?: 0
                    val contentH = list.height
                    val scrollY = subtitleScroll?.scrollY ?: 0
                    val maxScroll = (contentH - viewportH).coerceAtLeast(0)
                    val styles =
                        renderedRows.joinToString(" ") { holder ->
                            val s = holder.sourceTv
                            val t = holder.transTv
                            val sAlpha = (s.currentTextColor ushr 24).toString(16)
                            val tAlpha = t?.let { (it.currentTextColor ushr 24).toString(16) } ?: "--"
                            val shown = s.text.toString().replace('\u00A0', '·').take(14)
                            "${s.lineCount}/${t?.lineCount ?: 0}" +
                                "@${s.textSize.toInt()}/${t?.textSize?.toInt() ?: 0}px" +
                                ":${sAlpha}/${tAlpha}='$shown'"
                        }
                    Log.d(
                        TAG,
                        "visual: 可视=${viewportH}px 内容=${contentH}px 溢出=${contentH - viewportH}px " +
                            "滚动=${scrollY}/${maxScroll} 每行=[$styles]",
                    )
                }, 80)
            }
        }

        // 出现新的一条（不是同一行继续变长）才整列上滑，距离 = 新增那一行的高度
        val bottomSource = lines.last().sourceText
        val isNewRow = bottomSource.isNotEmpty() && !bottomSource.startsWith(lastBottomSource)
        lastBottomSource = bottomSource
        if (isNewRow) {
            slideUpByNewRow()
        }

        // 贴底：内容或窗口高度变化都滚动到最下沿（最新一条固定在下沿）
        val tail = bottomSource + "\u0000" + lines.last().translatedText
        if (tail != lastScrolledTail) {
            lastScrolledTail = tail
            scrollToBottom()
        }
    }

    /** 滚到内容最底部（ScrollView 会自行收敛到合法范围）。 */
    private fun scrollToBottom() {
        subtitleScroll?.post { subtitleScroll?.scrollTo(0, subtitleList?.height ?: 0) }
    }

    /** 固定 5 格视图（1 条最新 + 4 条历史），只在显示模式变化时重建。 */
    private fun ensureSlotRows(displayMode: DisplayMode) {
        if (renderedMode == displayMode && renderedRows.size == MAX_SUBTITLE_ROWS) return
        val list = subtitleList ?: return
        clearSubtitleRows()
        val density = resources.displayMetrics.density
        val gapPx = (LINE_ROW_GAP_DP * density).toInt()
        for (index in 0 until MAX_SUBTITLE_ROWS) {
            val holder = buildLineRow(displayMode, density)
            holder.row.layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = if (index == 0) 0 else gapPx }
            holder.row.visibility = View.GONE
            list.addView(holder.row)
            renderedRows.add(holder)
        }
        renderedMode = displayMode
        if (BuildConfig.DEBUG) Log.d(TAG, "slotRows: 重建 $MAX_SUBTITLE_ROWS 格 (mode=$displayMode)")
    }

    /** 清空字幕行视图与相关缓存。 */
    private fun clearSubtitleRows() {
        subtitleList?.let { if (it.childCount > 0) it.removeAllViews() }
        renderedRows.clear()
        renderedMode = null
        lastScrolledTail = ""
        lastBottomSource = ""
    }

    /**
     * 新一条字幕落位时整列上滑：先把内容整体下移「新增行的高度」，再动画回原位。
     * 位移量按实际新增高度算，动画进行中不重复触发。
     */
    private fun slideUpByNewRow() {
        val list = subtitleList ?: return
        val density = resources.displayMetrics.density
        list.post {
            if (slideAnimating) return@post
            val shift = (renderedRows.lastOrNull { it.row.visibility == View.VISIBLE }?.row?.height ?: 0)
                .takeIf { it > 0 }?.toFloat() ?: (SUB_SLIDE_FALLBACK_DP * density)
            slideAnimating = true
            list.translationY = shift
            list
                .animate()
                .translationY(0f)
                .setDuration(SUB_SLIDE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { slideAnimating = false }
                .start()
        }
    }

    /**
     * 构建一行字幕视图：渐变侧边条 + 文本容器（原文 / 译文）。
     * 只在视图结构需要重建时调用（见 [refreshSubtitleDisplay]）。
     */
    private fun buildLineRow(
        displayMode: DisplayMode,
        density: Float,
    ): LineRowViews {
        val textContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        // 单语模式（仅原文 / 仅译文）只有一个文本，用默认字号；双语模式下原文才小 2 号
        val singleLineMode = displayMode != DisplayMode.BILINGUAL
        val sourceTv =
            createSubtitleView(if (singleLineMode) fontSize else sourceTextSize()).apply {
                setTextColor(SOURCE_TEXT_COLOR)
            }
        var transTv: TextView? = null
        when (displayMode) {
            DisplayMode.SOURCE_ONLY,
            DisplayMode.TRANSLATION_ONLY,
            -> textContainer.addView(sourceTv)
            DisplayMode.BILINGUAL -> {
                // 原文在上（字号小 2 号、恒暗）、译文在下（草稿暗、定稿亮）
                textContainer.addView(sourceTv)
                transTv =
                    createSubtitleView().apply {
                        setTextColor(TRANSLATION_TEXT_COLOR)
                        // 原文与译文之间留一点间距，否则中英两行贴在一起看着像「粘连」
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                ).apply { topMargin = (TRANSLATION_TOP_GAP_DP * density).toInt() }
                        // 始终挂载、用 GONE 控制显隐，避免译文到达时重建整行
                        visibility = View.GONE
                    }
                textContainer.addView(transTv)
            }
        }

        // 蓝绿渐变侧边栏 + 文本内容，水平排列
        val rowContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        // 蓝绿渐变侧边栏：宽度=4dp，高度随文本内容增长
        rowContainer.addView(
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        (4 * density).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
                // 青蓝→绿垂直渐变，2dp圆角（参考 sidebar_gradient.xml 原始样式）
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.parseColor("#4DD0E1"), Color.parseColor("#81C784")),
                    ).apply { cornerRadius = 2 * density }
            },
        )

        // 文本区域加左边距
        textContainer.layoutParams =
            LinearLayout
                .LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    weight = 1f
                    marginStart = (8 * density).toInt()
                }
        rowContainer.addView(textContainer)

        return LineRowViews(rowContainer, sourceTv, transTv)
    }

    /** 文本或颜色变化时才写回，减少无谓的 measure/layout */
    private fun setTextIfChanged(
        tv: TextView,
        value: String,
        color: Int,
    ) {
        if (tv.text.toString() != value) tv.text = value
        if (tv.currentTextColor != color) tv.setTextColor(color)
    }

    private companion object {
        const val TAG = "FloatingSubtitleService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "floating_subtitle"

        // 8h: longest sensible meeting / lecture session.
        const val WAKE_LOCK_TIMEOUT_MS = 8L * 60L * 60L * 1000L

        // 顶部功能区不再自动显隐，原先的 3s 自动隐藏与静音重显常量已删除

        /** 翻译耗时超过这个值才显示「实时翻译中」，避免本地引擎 20~40ms 的翻译亮一帧就灭。 */
        const val TRANSLATING_INDICATOR_SHOW_DELAY_MS = 400L

        /** 原文颜色：始终偏暗 */
        val SOURCE_TEXT_COLOR = 0xB3FFFFFF.toInt()

        /** 译文定稿后的颜色。 */
        val TRANSLATION_TEXT_COLOR = 0xFFFFFFFF.toInt()

        /** 译文草稿时的颜色：半透明 */
        val TRANSLATION_DRAFT_COLOR = 0x80FFFFFF.toInt()

        /** 字幕固定 10 行：1 条最新 + 9 条历史 */
        const val MAX_SUBTITLE_ROWS = 10

        /** 原文字号比译文小 2 号 */
        const val SOURCE_TEXT_SIZE_OFFSET_SP = 2f

        /** 新一条字幕滑入动画时长（ms） */
        const val SUB_SLIDE_IN_MS = 200L

        /** 拿不到行高时的滑入位移（dp） */
        const val SUB_SLIDE_FALLBACK_DP = 24f

        /** 译文还没到时占位用的不可见字符：占住一行高度，避免译文到达时整行跳动 */
        const val TRANSLATION_PLACEHOLDER = "\u00A0"

        /** 右下角缩放热区边长（dp）：48dp 是常见的可点最小尺寸 */
        const val RESIZE_TOUCH_SIZE_DP = 48

        /** 原文与译文的行间距（dp）：太小会显得中英两行「粘」在一起。 */
        const val TRANSLATION_TOP_GAP_DP = 3

        /** 相邻两条字幕之间的间距（dp）。 */
        const val LINE_ROW_GAP_DP = 10

        /** 浮窗最小宽度（dp）。 */
        const val MIN_WIDTH_DP = 140

        /** 顶部功能区显示时的最小高度（dp）：padding + 顶部条 + 一行字幕。 */
        const val MIN_HEIGHT_WITH_CONTROLS_DP = 108

        /** 顶部功能区收起时的最小高度（dp）：padding + 一行字幕。 */
        const val MIN_HEIGHT_COMPACT_DP = 70

        /** 「实时翻译中」一旦显示，至少保持这么久，避免快速闪烁。 */
        const val TRANSLATING_INDICATOR_MIN_VISIBLE_MS = 600L

        /**
         * 字幕 UI 刷新最小间隔（毫秒）。
         * ASR partial 每秒可产生多次状态变更；合并到约 8Hz 显示，肉眼无差别，
         * 但能把主线程的 measure/layout 压力降低一个量级（防 ANR）。
         */
        const val SUBTITLE_REFRESH_MIN_INTERVAL_MS = 120L
    }
}



