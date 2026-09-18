package com.grok2api.gateway

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 产号悬浮层：照抄 com.taixu.grokreg.OverlayHost 的两个核心能力。
 *
 * 1. attachRender：把注册 WebView 从 Activity 视图树摘下来，挂到系统级 TYPE_APPLICATION_OVERLAY
 *    透明窗口（alpha 0.02 几乎不可见但系统判定"可见"）——Chromium 只在窗口可见时不节流 JS 定时器，
 *    Cloudflare 验证才能过。这是切后台续跑的关键。
 * 2. 悬浮球：独立小窗常驻显示进度徽章；需要人工点 Turnstile 时红色闪烁提醒，点球回 App。
 *
 * 注意：renderLayer 与 bubbleLayer 是两个独立窗口——WebView 挂透明层后仍需悬浮球回导航。
 */
object FarmOverlay {

    private const val BUBBLE_SIZE_DP = 56

    private var renderLayer: ViewGroup? = null
    private var originalParent: ViewGroup? = null
    private var bubbleLayer: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var badge: TextView? = null
    private var alertAnim: ValueAnimator? = null

    private val ui = Handler(Looper.getMainLooper())

    fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun permissionIntent(ctx: Context) =
        android.content.Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION",
            android.net.Uri.parse("package:" + ctx.packageName))

    fun isRenderAttached(): Boolean = renderLayer != null
    fun isBubbleShowing(): Boolean = bubbleLayer != null

    // ------------------------------------------------------------- 渲染层（后台续跑核心）

    /**
     * 把 [webStack]（含 WebView 的容器）挂到系统透明悬浮层。
     * 返回 false = 无权限或挂载失败（WebView 已安全放回原位）。
     */
    fun attachRender(ctx: Context, webStack: ViewGroup, original: ViewGroup): Boolean {
        if (renderLayer != null) return true
        if (!hasPermission(ctx)) return false
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        lp.x = 0; lp.y = 0
        lp.alpha = 0.02f   // taixu 同款：几乎不可见但系统认为可见，JS 定时器不被节流

        val holder = FrameLayout(ctx)
        holder.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // 从原视图树摘下 WebView 容器
        val prev = webStack.parent as? ViewGroup
        prev?.removeView(webStack)
        holder.addView(webStack, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            wm.addView(holder, lp)
            renderLayer = holder
            originalParent = original
            return true
        } catch (e: Throwable) {
            // 失败回滚：WebView 放回原位置
            runCatching { holder.removeView(webStack) }
            runCatching { prev?.addView(webStack, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            renderLayer = null
            return false
        }
    }

    /** 把 WebView 放回 Activity 视图树（回前台/批次结束时调用）。 */
    fun detachRender(ctx: Context, webStack: ViewGroup): Boolean {
        val holder = renderLayer ?: return false
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { holder.removeView(webStack) }
        runCatching { wm?.removeViewImmediate(holder) }
        renderLayer = null
        val original = originalParent
        return try {
            if (webStack.parent == null && original != null) {
                original.addView(webStack, android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            webStack.visibility = View.VISIBLE
            true
        } catch (e: Throwable) {
            false
        } finally {
            originalParent = null
        }
    }

    // ------------------------------------------------------------- 悬浮球（进度 + 人工提醒）

    fun showBubble(ctx: Context, onClick: () -> Unit, onLongClick: () -> Unit = {}): Boolean {
        if (bubbleLayer != null) return true
        if (!hasPermission(ctx)) return false
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false

        val density = ctx.resources.displayMetrics.density
        val size = (BUBBLE_SIZE_DP * density).toInt()

        val root = FrameLayout(ctx)
        val circle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4F46E5"))
            }
        }
        root.addView(circle, FrameLayout.LayoutParams(size, size))
        val badgeView = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "⚡"
        }
        root.addView(badgeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        badge = badgeView

        // 拖动 + 点击
        var downX = 0f; var downY = 0f; var moved = false
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 40; lp.y = 400
        root.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX - lp.x; downY = event.rawY - lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (event.rawX - downX).toInt(); val ny = (event.rawY - downY).toInt()
                    if (Math.abs(nx - lp.x) > 8 || Math.abs(ny - lp.y) > 8) moved = true
                    lp.x = nx; lp.y = ny
                    wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick()
                    else onLongClick()
                    true
                }
                else -> false
            }
        }

        return try {
            wm.addView(root, lp)
            bubbleLayer = root
            bubbleParams = lp
            true
        } catch (e: Throwable) {
            bubbleLayer = null
            false
        }
    }

    fun hideBubble(ctx: Context) {
        stopAlertAnim()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val view = bubbleLayer
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        bubbleLayer = null
        bubbleParams = null
        badge = null
    }

    fun updateBadge(text: String) {
        badge?.text = text
    }

    /** 红色闪烁：Turnstile 需要人工时。 */
    fun setAlert(ctx: Context, alerting: Boolean) {
        val circle = (bubbleLayer as? FrameLayout)?.getChildAt(0) ?: return
        if (!alerting) {
            stopAlertAnim()
            return
        }
        if (alertAnim != null) return
        val drawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4F46E5")) }
        circle.background = drawable
        alertAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val phase = a.animatedValue as Float
                val c = if (phase < 0.5f) Color.parseColor("#F87171") else Color.parseColor("#4F46E5")
                (drawable as GradientDrawable).setColor(c)
            }
            start()
        }
    }

    private fun stopAlertAnim() {
        alertAnim?.cancel()
        alertAnim = null
        // 恢复正常色
        val circle = (bubbleLayer as? FrameLayout)?.getChildAt(0)
        (circle?.background as? GradientDrawable)?.setColor(Color.parseColor("#4F46E5"))
    }
}