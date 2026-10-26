package io.github.ztfang.eye

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** 浮窗根容器：旁路观察触摸事件，用于识别轻点与拖动/缩放（ScrollView 会消费掉子 view 的事件）。 */
class OverlayRootLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        /** 轻点回调，参数为浮窗内坐标（用于区分是否点在顶部功能区按钮上）。 */
        var onTapListener: ((x: Float, y: Float) -> Unit)? = null

        /** 每个触摸事件的观察者，参数为原始事件（event.x/y 是浮窗内坐标）。 */
        var touchObserver: ((MotionEvent) -> Unit)? = null

        /** 是否已进入拖拽/缩放：为 true 时拦截后续事件，保证手势不被子 view 抢走。 */
        var shouldIntercept: (() -> Boolean)? = null

        private var downX = 0f
        private var downY = 0f
        private var downAtMs = 0L
        private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downAtMs = ev.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val isTap =
                        dx * dx + dy * dy <= touchSlopPx * touchSlopPx &&
                            ev.eventTime - downAtMs <= TAP_TIMEOUT_MS
                    if (isTap) onTapListener?.invoke(downX, downY)
                }
            }
            // 观察在 super 之前：即使子 view 随后消费掉事件，我们也能看到完整手势
            touchObserver?.invoke(ev)
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) return false
            return shouldIntercept?.invoke() == true
        }

        /** 浮窗自己吃掉窗口内的触摸（与原 OnTouchListener 行为等价），保证拖拽能收到 MOVE/UP。 */
        override fun onTouchEvent(ev: MotionEvent): Boolean = true

        private companion object {
            /** 超过这个时长视为长按，不算轻点。 */
            const val TAP_TIMEOUT_MS = 300L
        }
    }
