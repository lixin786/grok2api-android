package com.grok2api.gateway

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮球保活。
 *
 * ## 它到底有没有用（先说清楚，不夸大）
 *
 * 悬浮球用 `TYPE_APPLICATION_OVERLAY`，属于**可见窗口**，会把进程优先级提升到 `VISIBLE` 档，
 * 在**内存不足（LMK）回收**时排在前台服务之后，确实能降低"内存一紧就被清掉"的概率。
 *
 * 但必须明确：**它挡不住厂商的强杀**。ColorOS / MIUI 的「一键清理」「速冻」「深度睡眠」
 * 走白名单机制——不在白名单里，窗口再多也照杀。所以它只是**辅助手段**，
 * 真正的保命线是电池优化白名单 + 自启动 + 回收后自动重启（见 ApiHostService）。
 *
 * ## 交互设计
 *
 * - **球形外观**：自绘圆形，比矩形小窗更轻、不遮挡内容
 * - **全屏拖动**：手指按住可拖到屏幕任意位置，边界内自动夹紧
 * - **自动半藏**：3 秒无操作后贴到最近一侧，只露半个球在边缘
 *
 * 收起时刻意保留**一半**可见：露出太少会让人以为"球丢了"而去反复找，
 * 露一半则一眼能看出是"躲在边上"，既不碍事也随点随到。
 * 同时它不能被完全隐藏——窗口不可见就等于失去 VISIBLE 优先级，保活收益直接归零。
 */
object FloatingWindowKeeper {

    private const val TAG = "FloatingKeeper"
    private const val PREFS = "native_service"
    private const val KEY_ENABLED = "floating_window_enabled"

    /** 球体直径（dp）。48dp 是 Android 最小可点击尺寸，小于它会很难拖准。 */
    private const val BALL_DP = 52

    /** 收起后露在屏幕内的宽度占比（0.5 = 只露半个球）。 */
    private const val COLLAPSED_VISIBLE_RATIO = 0.5f

    /** 收起时的透明度。留一点通透感，让用户知道它是可交互的，但不要降到看不清。 */
    private const val COLLAPSED_ALPHA = 0.88f

    /** 收起时的缩放比例。保持接近原始大小——缩太小就变成"消失在边缘"，反而不像"躲"。 */
    private const val COLLAPSED_SCALE = 1f

    /** 常态位置距屏幕边缘的留白（dp）。收起时不留白，展开/拖动后留出来，视觉上不黏边。 */
    private const val REST_MARGIN_DP = 8

    /** 静止多久后自动收拢（毫秒）。 */
    private const val AUTO_COLLAPSE_DELAY = 3000L

    /** 拖动时球体放大的比例，给手指一点"抓住"的反馈。 */
    private const val DRAG_SCALE = 1.08f

    private var windowView: BallView? = null
    private var windowManager: WindowManager? = null

    /** 权限是否已授予。授权页返回后需重新查询，系统不会回调通知。 */
    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 跳系统授权页；结果无回调，返回后由界面重新调用 [hasPermission] 判断。 */
    fun requestPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open overlay settings failed", it) }
    }

    /**
     * 显示悬浮球。无权限或未开启时静默跳过——保活是尽力而为的能力，
     * 不应因为一个球让主服务起不来。
     */
    fun show(context: Context, port: Int) {
        if (!isEnabled(context) || !hasPermission(context)) {
            Log.i(TAG, "skip show: enabled=${isEnabled(context)} permission=${hasPermission(context)}")
            return
        }
        if (windowView?.isAttachedToWindow == true) {
            windowView?.updateStatus(true)
            return
        }
        // 残留引用（服务重启但窗口已被系统回收）——清掉重建，否则会永远显示不出来。
        if (windowView != null) {
            Log.i(TAG, "stale ball reference, rebuilding")
            windowView = null
        }
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val ball = BallView(context.applicationContext, port)
            val size = ball.ballSizePx
            val (screenW, screenH) = screenSize(context, wm)
            val params = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // NOT_FOCUSABLE：不抢输入焦点，否则会影响用户正在用的其它应用（如输入法弹不出来）。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 初始位置靠右侧中部，与常见悬浮球习惯一致。
                x = screenW - size - dip(context, 6)
                y = (screenH * 0.42f).toInt()
            }
            wm.addView(ball, params)
            windowView = ball
            windowManager = wm
            ball.onAttached()
            Log.i(TAG, "floating ball shown at (${params.x}, ${params.y})")
        }.onFailure { Log.w(TAG, "show floating ball failed", it) }
    }

    /** 服务状态变化时刷新球的视觉状态（颜色/文案）。 */
    fun updateStatus(running: Boolean) {
        windowView?.updateStatus(running)
    }

    /** 服务停止时移除窗口，避免残留一个"运行中"的假象。 */
    fun hide(context: Context) {
        val view = windowView ?: return
        runCatching { windowManager?.removeView(view) }
            .onFailure { Log.w(TAG, "remove floating ball failed", it) }
        windowView = null
        windowManager = null
    }

    fun isShowing(): Boolean = windowView?.isAttachedToWindow == true

    private fun dip(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * 取当前屏幕可用尺寸。
     *
     * 不用已废弃的 `WindowManager.defaultDisplay`：它在折叠屏/多窗口/分屏下会返回整块物理屏的尺寸，
     * 而不是应用当前可用的区域，导致球被放到屏幕外。Android 11+ 用 WindowMetrics 才是准确值。
     */
    @Suppress("DEPRECATION")
    private fun screenSize(context: Context, wm: WindowManager): Pair<Int, Int> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val d = wm.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            d.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }.getOrElse { context.resources.displayMetrics.widthPixels to context.resources.displayMetrics.heightPixels }

    /**
     * 自绘悬浮球。
     *
     * 为什么不用布局 + 背景 drawable：拖动时需要跟随手指做位移、缩放、透明度三者的连续动画，
     * 自绘可以在一处统一控制，也省得每次重建窗口都 inflate 一层 XML。
     *
     * 位置更新统一走 `view.layoutParams`——View 自己被 addView 后就会持有 WindowManager
     * 写回的 LayoutParams，比在外部维护一份引用更不易出现状态不同步。
     */
    @SuppressLint("ViewConstructor")
    private class BallView(context: Context, private val port: Int) : View(context) {

        private val density = resources.displayMetrics.density
        val ballSizePx = (BALL_DP * density).toInt()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        /** 状态点专用画笔（含描边与实心两次绘制），与图标画笔分离避免 alpha 互相污染。 */
        private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val handler = Handler(Looper.getMainLooper())

        @Volatile private var running = true
        private var collapsed = false
        private var dragging = false
        private var scale = 1f
        private var alpha = 1f

        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var moved = false
        private val touchSlop = (8 * density).toInt()

        private val collapseTask = Runnable { collapse() }

        /** 当前正在播放的位置动画。用于在开始新动画前取消旧的，避免多个动画并发写同一份 LayoutParams。 */
        private var activeAnimator: ValueAnimator? = null

        /**
         * 收起时绘制内容的水平偏移量（px）。
         *
         * 为什么不用移动窗口来实现：窗口一旦被移出屏幕，露出的那一小条就是全部触摸区域，
         * 14dp 的点击目标用户几乎点不中。保持窗口完整留在屏内、只偏移绘制内容，
         * 触摸热区仍是完整的球（52dp），既看起来"躲起来了"，又依然好点。
         */
        private var drawOffsetX = 0f

        /** 收起时贴的是右侧还是左侧，决定状态点画在哪边（贴在屏幕内侧那一边才看得见）。 */
        private var collapsedRight = true

        init {
            bgPaint.color = 0xF24F46E5.toInt()
            ringPaint.color = 0x66FFFFFF
            iconPaint.color = Color.WHITE
            statusDotPaint.color = 0xFF4ADE80.toInt()
        }

        fun onAttached() {
            scheduleCollapse()
        }

        /** 取回当前窗口参数。addView 后由系统写入，这里每次重新读取而非缓存，避免与服务重建后的状态不同步。 */
        private fun windowLp(): WindowManager.LayoutParams? = layoutParams as? WindowManager.LayoutParams

        private fun windowManager(): WindowManager? =
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        // 尺寸统一走外层 screenSize()，保证"初始位置"和"收起位置"用的是同一套度量，
        // 否则在折叠屏/分屏下会出现收起后球跑到屏幕外（两边算出的宽度不一致）。
        private fun screenWidth(): Int =
            windowManager()?.let { screenSize(context, it).first } ?: resources.displayMetrics.widthPixels

        private fun screenHeight(): Int =
            windowManager()?.let { screenSize(context, it).second } ?: resources.displayMetrics.heightPixels

        fun updateStatus(isRunning: Boolean) {
            running = isRunning
            statusDotPaint.color = if (isRunning) 0xFF4ADE80.toInt() else 0xFFF87171.toInt()
            invalidate()
        }

        /** 3 秒无操作后收拢；用户再次触摸会取消并展开。 */
        private fun scheduleCollapse() {
            handler.removeCallbacks(collapseTask)
            handler.postDelayed(collapseTask, AUTO_COLLAPSE_DELAY)
        }

        private fun collapse() {
            if (collapsed || dragging) return
            val lp = windowLp() ?: return
            // 贴到最近的一侧：比较球心到左右边的距离，谁近贴谁。
            val screenW = screenWidth()
            val centerX = lp.x + ballSizePx / 2f
            val toRight = screenW - centerX >= centerX
            // 分两步才能得到"躲在屏幕边缘"的效果：
            //   1) 窗口先真正贴到屏幕最边缘（不留边距）——否则球停在距边 6dp 处，
            //      再怎么偏移也只是刚碰到边缘，看起来像"没躲"甚至"往屏幕里挪"。
            //   2) 再把绘制内容向屏幕外偏移，让球有一半被屏幕边缘裁掉。
            // 窗口本身始终完整留在屏内，触摸热区不受影响。
            val visible = (ballSizePx * COLLAPSED_VISIBLE_RATIO).toInt()
            val targetX = if (toRight) screenW - ballSizePx else 0
            val targetOffset = if (toRight)
                (ballSizePx - visible).toFloat()
            else
                -(ballSizePx - visible).toFloat()
            collapsed = true
            collapsedRight = toRight
            animateTo(
                toX = targetX,
                toScale = COLLAPSED_SCALE,
                toAlpha = COLLAPSED_ALPHA,
                toOffsetX = targetOffset
            )
        }

        private fun expand() {
            if (!collapsed) {
                scheduleCollapse()
                return
            }
            collapsed = false
            // 从紧贴边缘的位置弹回"留一点边距"的常态位置，否则展开后球会黏在屏幕边上。
            val targetX = if (collapsedRight) {
                screenWidth() - ballSizePx - restMarginPx()
            } else {
                restMarginPx()
            }
            animateTo(toX = targetX, toScale = 1f, toAlpha = 1f, toOffsetX = 0f)
        }

        /** 常态位置距屏幕边缘的留白。 */
        private fun restMarginPx(): Int = (REST_MARGIN_DP * density).toInt()

        /**
         * 统一动画入口：一次驱动「位置 + 缩放 + 透明度 + 绘制偏移」四个量。
         *
         * 之所以揉进一个 ValueAnimator 而不是各自动画：这几项必须同步到达终点，
         * 分开跑会出现"位置到了但透明度还没变完"的割裂感；而且并发动画会争抢同一份
         * LayoutParams，导致位置互相覆盖（表现为球在抖）。
         */
        private fun animateTo(
            toX: Int = windowLp()?.x ?: 0,
            toY: Int = windowLp()?.y ?: 0,
            toScale: Float = scale,
            toAlpha: Float = alpha,
            toOffsetX: Float = drawOffsetX
        ) {
            // 先取消上一个动画：快速连点/连拖时若多个 ValueAnimator 并发写同一份 LayoutParams，
            // 位置会互相覆盖，表现为"球在抖"或停在半路。
            activeAnimator?.cancel()
            val lp = windowLp() ?: return
            val fromX = lp.x
            val fromY = lp.y
            val startScale = scale
            val startAlpha = alpha
            val startOffset = drawOffsetX
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedFraction
                    scale = startScale + (toScale - startScale) * t
                    alpha = startAlpha + (toAlpha - startAlpha) * t
                    drawOffsetX = startOffset + (toOffsetX - startOffset) * t
                    applyPosition(
                        (fromX + (toX - fromX) * t).toInt(),
                        (fromY + (toY - fromY) * t).toInt()
                    )
                    invalidate()
                }
                start()
            }
            activeAnimator = animator
        }

        /**
         * 写回窗口位置。
         *
         * 直接改 `view.layoutParams` 再调 updateViewLayout：这是 View 当前真实持有的那份参数，
         * 避免在外部另存一份引用后两边不同步（历史上出现过"动画结束位置对不上"的问题）。
         */
        private fun applyPosition(x: Int, y: Int) {
            val lp = windowLp() ?: return
            lp.x = x
            lp.y = y
            runCatching { windowManager()?.updateViewLayout(this, lp) }
        }

        override fun onDraw(canvas: Canvas) {
            // 收起时把绘制内容整体推出屏幕边缘（窗口本身仍在屏内，触摸区不受影响）。
            canvas.save()
            canvas.translate(drawOffsetX, 0f)

            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f * scale - 2 * density
            if (radius <= 0) {
                canvas.restore()
                return
            }

            val opaque = (255 * alpha).toInt().coerceIn(0, 255)

            // 球体：主色填充，透明度随收起状态下降，减少对内容的干扰。
            bgPaint.alpha = opaque
            canvas.drawCircle(cx, cy, radius, bgPaint)

            // 细描边让球在浅色/深色壁纸上都有边界感。
            ringPaint.alpha = (150 * alpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius - 1f, ringPaint)

            // 图形：一个"链接/节点"符号，暗示这是网络服务而非普通 App。
            // 注意每个 Paint 用前都要显式设 alpha：Paint 对象是复用的，
            // 上一次绘制留下的 alpha 会带到下一次（曾导致状态点越画越透明）。
            val r = radius * 0.42f
            iconPaint.alpha = opaque
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -50f, 100f, false, iconPaint)
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 130f, 100f, false, iconPaint)

            // 状态点：右下角小圆，绿=运行中、红=已停止。比文字更省空间。
            val dotR = radius * 0.16f
            // 收起时球半藏在屏幕边缘，状态点要画在"朝屏幕内侧"的一边，否则会被边缘裁掉看不见。
            val side = if (collapsed && collapsedRight) -1f else 1f
            val dotX = cx + radius * 0.62f * side
            val dotY = cy + radius * 0.62f
            statusDotPaint.alpha = (230 * alpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(dotX, dotY, dotR + 1.8f * density, statusDotPaint)
            statusDotPaint.alpha = opaque
            canvas.drawCircle(dotX, dotY, dotR, statusDotPaint)

            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val lp = windowLp() ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = lp.x
                    downY = lp.y
                    moved = false
                    dragging = true
                    handler.removeCallbacks(collapseTask)
                    // 按下即展开：拖动过程中球必须完全可见，否则用户抓的是"半颗球"。
                    collapsed = false
                    animateTo(toScale = DRAG_SCALE, toAlpha = 1f, toOffsetX = 0f)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        // 一旦确认是拖动，立刻终止按下时的缩放动画：否则动画回调每帧还在写
                        // 位置，会和手指的位置相互覆盖，表现为"球跟不上手指"。
                        activeAnimator?.cancel()
                        activeAnimator = null
                        scale = DRAG_SCALE
                        alpha = 1f
                        drawOffsetX = 0f
                    }
                    if (moved) {
                        // 边界夹紧：允许略微出屏，但不让球完全拖出可视区——拖出去就再也拿不回来了。
                        val screenW = screenWidth()
                        val screenH = screenHeight()
                        val minX = -ballSizePx / 3
                        val maxX = screenW - ballSizePx * 2 / 3
                        val minY = -ballSizePx / 3
                        val maxY = screenH - ballSizePx * 2 / 3
                        applyPosition(
                            (downX + dx).toInt().coerceIn(minX, maxX),
                            (downY + dy).toInt().coerceIn(minY, maxY)
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (moved) {
                        // 拖完吸附到最近侧——这样不会挡住用户正在看的内容。
                        snapToEdge()
                    } else {
                        expand()
                        // 单击视为"我要用一下"，给一次新的收拢倒计时。
                        handler.removeCallbacks(collapseTask)
                        handler.postDelayed(collapseTask, AUTO_COLLAPSE_DELAY)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /** 松手后贴向左右最近的一侧，避免球停在屏幕中间碍事。 */
        private fun snapToEdge() {
            val lp = windowLp() ?: return
            val screenW = screenWidth()
            val centerX = lp.x + ballSizePx / 2f
            val toRight = screenW - centerX >= centerX
            val margin = restMarginPx()
            val targetX = if (toRight) screenW - ballSizePx - margin else margin
            animateTo(toX = targetX, toScale = 1f, toAlpha = 1f, toOffsetX = 0f)
            // 贴边后重新计时，用户不再碰它就会缩起来。
            handler.removeCallbacks(collapseTask)
            handler.postDelayed(collapseTask, AUTO_COLLAPSE_DELAY)
        }

        override fun onDetachedFromWindow() {
            handler.removeCallbacksAndMessages(null)
            activeAnimator?.cancel()
            activeAnimator = null
            super.onDetachedFromWindow()
        }
    }
}
