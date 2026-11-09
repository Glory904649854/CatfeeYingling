package com.catfee.yingling.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.core.label
import kotlin.math.abs
import kotlin.math.min

/**
 * 紧凑悬浮窗：只保留“中文原文 + 英文译文 + 状态/方向/控制”，
 * 去掉声浪，尽量少占屏幕，同时保证原文和译文可以完整阅读（超长可滚动）。
 */
class OverlayController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onTogglePause()
        fun onStop()
        fun onHide()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var body: LinearLayout? = null
    private var scroll: MaxScrollView? = null
    private var dot: View? = null
    private var statusView: TextView? = null
    private var directionView: TextView? = null
    private var sourceView: TextView? = null
    private var targetView: TextView? = null
    private var pauseView: TextView? = null
    private var scopeView: TextView? = null

    private var pausedVisual = false
    private var collapsed = false

    val isShowing: Boolean get() = root != null

    fun show(direction: String, scopeLabel: String): Boolean {
        if (!Settings.canDrawOverlays(context)) return false

        val existing = root
        if (existing != null) {
            setDirection(direction)
            setScope(scopeLabel)
            return true
        }

        val dm = context.resources.displayMetrics
        val width = min((dm.widthPixels * 0.92f).toInt(), dp(360))
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - width - dp(8)
            y = dp(80)
        }

        val view = buildViews(direction, scopeLabel)
        return try {
            wm.addView(view, lp)
            root = view
            params = lp
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        val view = root ?: return
        root = null
        params = null
        runCatching { wm.removeView(view) }
    }

    fun setText(source: String, target: String) {
        sourceView?.text = source.ifBlank { "—" }
        targetView?.text = target.ifBlank { "等待语音…" }
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    fun setDirection(direction: String) {
        directionView?.text = direction
    }

    fun setScope(scopeLabel: String) {
        scopeView?.text = scopeLabel
    }

    fun setStatus(status: SessionStatus) {
        val color = when (status) {
            SessionStatus.RUNNING -> Colors.mint
            SessionStatus.PAUSED -> Colors.paused
            SessionStatus.STARTING -> Colors.blue
            SessionStatus.ERROR -> Colors.danger
            SessionStatus.IDLE -> Colors.muted
        }
        dot?.background = circle(color)
        statusView?.text = status.label()
        if (status == SessionStatus.ERROR) {
            targetView?.text = "音频采集异常"
        }
    }

    fun setPaused(paused: Boolean) {
        pausedVisual = paused
        dot?.background = circle(if (paused) Colors.paused else Colors.mint)
        statusView?.text = if (paused) "已暂停" else "运行中"
        pauseView?.text = if (paused) "继续" else "暂停"
    }

    // ---------------------------------------------------------------- views

    private fun buildViews(direction: String, scopeLabel: String): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Colors.bg)
                setStroke(dp(1), Colors.border)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusDot = View(context).apply { background = circle(Colors.mint) }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        dot = statusDot

        val title = TextView(context).apply {
            text = "应灵"
            setTextColor(Colors.text)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })

        val status = TextView(context).apply {
            text = "运行中"
            setTextColor(Colors.muted)
            textSize = 9f
        }
        header.addView(status, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        statusView = status

        val dir = TextView(context).apply {
            text = direction
            setTextColor(Colors.mint)
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = rounded(Colors.chipBg, 99)
        }
        header.addView(dir, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        directionView = dir

        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        val scope = TextView(context).apply {
            text = scopeLabel
            setTextColor(Colors.muted)
            textSize = 8.5f
        }
        header.addView(scope, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        scopeView = scope

        val collapse = TextView(context).apply {
            text = "收起"
            setTextColor(Colors.muted)
            textSize = 9f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            isClickable = true
        }
        header.addView(collapse)

        val close = TextView(context).apply {
            text = "×"
            setTextColor(Colors.muted)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, 0, dp(1))
            isClickable = true
            setOnClickListener { callbacks.onHide() }
        }
        header.addView(close)

        container.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        installDrag(header)

        val bodyView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(bodyView, LinearLayout.LayoutParams(MATCH, WRAP))
        body = bodyView
        bodyView.visibility = if (collapsed) View.GONE else View.VISIBLE

        collapse.setOnClickListener {
            collapsed = !collapsed
            bodyView.visibility = if (collapsed) View.GONE else View.VISIBLE
            collapse.text = if (collapsed) "展开" else "收起"
        }

        val maxHeight = min((context.resources.displayMetrics.heightPixels * 0.50f).toInt(), dp(320))
        val scroller = MaxScrollView(context, maxHeight)
        bodyView.addView(scroller, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        scroll = scroller

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))

        val source = TextView(context).apply {
            text = "—"
            setTextColor(Colors.muted)
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dp(3))
        }
        content.addView(source, LinearLayout.LayoutParams(MATCH, WRAP))
        sourceView = source

        val target = TextView(context).apply {
            text = "等待语音…"
            setTextColor(Colors.text)
            textSize = 16.5f
            setLineSpacing(0f, 1.15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        content.addView(target, LinearLayout.LayoutParams(MATCH, WRAP))
        targetView = target

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bodyView.addView(
            controls,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(9) },
        )

        val pause = TextView(context).apply {
            text = "暂停"
            setTextColor(Color.rgb(7, 18, 15))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(Colors.mint, 99)
            isClickable = true
            setOnClickListener { callbacks.onTogglePause() }
        }
        controls.addView(pause)
        pauseView = pause

        val stop = TextView(context).apply {
            text = "停止"
            setTextColor(Colors.danger)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(Colors.dangerBg, 99)
            isClickable = true
            setOnClickListener { callbacks.onStop() }
        }
        controls.addView(stop, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(7) })

        return container
    }

    private fun installDrag(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        handle.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            val view = root ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToEdge()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val lp = params ?: return
        val view = root ?: return
        val dm = context.resources.displayMetrics
        val target = if (lp.x + lp.width / 2 < dm.widthPixels / 2) {
            dp(8)
        } else {
            dm.widthPixels - lp.width - dp(8)
        }
        val animator = ValueAnimator.ofInt(lp.x, target)
        animator.duration = 180
        animator.addUpdateListener {
            lp.x = it.animatedValue as Int
            runCatching { wm.updateViewLayout(view, lp) }
        }
        animator.start()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** 限制最大高度的滚动容器：长句可以完整读，窗口不会盖满屏幕。 */
    private class MaxScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    private object Colors {
        val bg: Int = Color.argb(246, 15, 20, 25)
        val border: Int = Color.argb(30, 255, 255, 255)
        val text: Int = Color.rgb(238, 244, 247)
        val muted: Int = Color.rgb(138, 151, 162)
        val mint: Int = Color.rgb(91, 231, 196)
        val blue: Int = Color.rgb(76, 194, 255)
        val danger: Int = Color.rgb(255, 139, 139)
        val dangerBg: Int = Color.argb(26, 255, 107, 107)
        val paused: Int = Color.rgb(255, 184, 107)
        val chipBg: Int = Color.argb(26, 91, 231, 196)
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
