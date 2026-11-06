package com.catfee.yingling.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.catfee.yingling.core.SessionStatus
import com.catfee.yingling.core.label
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 悬浮窗：声浪、状态、翻译方向、暂停/继续、停止。
 *
 * 用原生 View 而不是 ComposeView，避免在 Service 里额外搭 LifecycleOwner，
 * 也方便直接挂到 WindowManager 上。
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
    private var wave: WaveformView? = null
    private var dot: View? = null
    private var statusView: TextView? = null
    private var directionView: TextView? = null
    private var sourceView: TextView? = null
    private var targetView: TextView? = null
    private var pauseView: TextView? = null
    private var metaView: TextView? = null

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
        val width = min((dm.widthPixels * 0.94f).toInt(), dp(372))
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
            y = dp(90)
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

    fun setLevel(level: Float) {
        wave?.push(level)
    }

    fun setText(source: String, target: String) {
        sourceView?.text = source.ifBlank { "—" }
        targetView?.text = target.ifBlank { "等待语音…" }
    }

    fun setDirection(direction: String) {
        directionView?.text = direction
    }

    fun setScope(scopeLabel: String) {
        metaView?.text = scopeLabel
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
        wave?.pausedVisual = paused
        dot?.background = circle(if (paused) Colors.paused else Colors.mint)
        statusView?.text = if (paused) "已暂停" else "运行中"
        pauseView?.text = if (paused) "继续" else "暂停"
        if (paused) wave?.push(0f)
    }

    // ---------------------------------------------------------------- views

    private fun buildViews(direction: String, scopeLabel: String): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Colors.bg)
                setStroke(dp(1), Colors.border)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusDot = View(context).apply {
            background = circle(Colors.mint)
        }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        dot = statusDot

        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = "应灵"
            setTextColor(Colors.text)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val status = TextView(context).apply {
            text = "运行中"
            setTextColor(Colors.muted)
            textSize = 10f
            setPadding(0, dp(1), 0, 0)
        }
        titles.addView(title)
        titles.addView(status)
        header.addView(
            titles,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(9)
            },
        )
        statusView = status

        val directionText = TextView(context).apply {
            text = direction
            setTextColor(Colors.mint)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = rounded(Colors.chipBg, 99)
        }
        header.addView(directionText)
        directionView = directionText

        val collapse = TextView(context).apply {
            text = "收起"
            setTextColor(Colors.muted)
            textSize = 10f
            setPadding(dp(10), dp(4), dp(6), dp(4))
            isClickable = true
        }
        header.addView(collapse)

        val close = TextView(context).apply {
            text = "×"
            setTextColor(Colors.muted)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, 0, dp(2))
            isClickable = true
            setOnClickListener { callbacks.onHide() }
        }
        header.addView(close)

        container.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        installDrag(header)

        val bodyView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(
            bodyView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        body = bodyView
        bodyView.visibility = if (collapsed) View.GONE else View.VISIBLE

        collapse.setOnClickListener {
            collapsed = !collapsed
            bodyView.visibility = if (collapsed) View.GONE else View.VISIBLE
            collapse.text = if (collapsed) "展开" else "收起"
        }

        val waveform = WaveformView(context)
        bodyView.addView(
            waveform,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46),
            ).apply { topMargin = dp(10) },
        )
        wave = waveform

        val source = TextView(context).apply {
            text = "—"
            setTextColor(Colors.muted)
            textSize = 11f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(8), 0, 0)
        }
        bodyView.addView(source)
        sourceView = source

        val target = TextView(context).apply {
            text = "等待语音…"
            setTextColor(Colors.text)
            textSize = 17f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(4), 0, 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        bodyView.addView(target)
        targetView = target

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bodyView.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val pause = TextView(context).apply {
            text = "暂停"
            setTextColor(Color.rgb(7, 18, 15))
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = rounded(Colors.mint, 99)
            isClickable = true
            setOnClickListener { callbacks.onTogglePause() }
        }
        controls.addView(pause)
        pauseView = pause

        val stop = TextView(context).apply {
            text = "停止"
            setTextColor(Colors.danger)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = rounded(Colors.dangerBg, 99)
            isClickable = true
            setOnClickListener { callbacks.onStop() }
        }
        controls.addView(
            stop,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )

        controls.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        val meta = TextView(context).apply {
            text = scopeLabel
            setTextColor(Colors.muted)
            textSize = 9.5f
        }
        controls.addView(meta)
        metaView = meta

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
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
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

    // ------------------------------------------------------------ waveform

    private class WaveformView(context: Context) : View(context) {

        private val levels = FloatArray(44)
        private var head = 0
        private var smooth = 0f
        private var shader: LinearGradient? = null
        private var grayShader: LinearGradient? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var pausedVisual = false

        fun push(level: Float) {
            val target = if (pausedVisual) 0f else level.coerceIn(0f, 1f)
            smooth += (target - smooth) * if (target > smooth) 0.6f else 0.14f
            levels[head] = smooth
            head = (head + 1) % levels.size
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(Colors.mint, Colors.blue), null,
                Shader.TileMode.CLAMP,
            )
            grayShader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(Color.rgb(70, 82, 92), Color.rgb(44, 53, 61)), null,
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val n = levels.size
            val slot = w / n
            val barWidth = max(slot * 0.5f, 1.5f)
            paint.shader = if (pausedVisual) grayShader else shader
            val idle = !pausedVisual && smooth < 0.02f
            val phase = SystemClock.elapsedRealtime() / 1000f * 1.6f

            for (i in 0 until n) {
                val levelValue = levels[(head + i) % n]
                val value = when {
                    pausedVisual -> 0.02f + 0.01f * (0.5f + 0.5f * sin(i * 0.6f))
                    idle -> 0.05f + 0.04f * (0.5f + 0.5f * sin(i * 0.6f + phase))
                    else -> levelValue
                }
                val minHeight = barWidth * 0.85f
                val barHeight = max(minHeight, value * (h - minHeight))
                val left = i * slot + (slot - barWidth) / 2f
                val top = (h - barHeight) / 2f
                canvas.drawRoundRect(
                    left, top, left + barWidth, top + barHeight,
                    barWidth / 2f, barWidth / 2f, paint,
                )
            }
        }
    }

    private object Colors {
        val bg: Int = Color.argb(243, 15, 20, 25)
        val border: Int = Color.argb(34, 255, 255, 255)
        val text: Int = Color.rgb(238, 244, 247)
        val muted: Int = Color.rgb(138, 151, 162)
        val mint: Int = Color.rgb(91, 231, 196)
        val blue: Int = Color.rgb(76, 194, 255)
        val danger: Int = Color.rgb(255, 139, 139)
        val dangerBg: Int = Color.argb(26, 255, 107, 107)
        val paused: Int = Color.rgb(255, 184, 107)
        val chipBg: Int = Color.argb(26, 91, 231, 196)
    }
}
