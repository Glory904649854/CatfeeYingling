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
import com.catfee.yingling.core.SubtitleLine
import kotlin.math.abs
import kotlin.math.min

/**
 * 紧凑悬浮窗：保留最近几句“原文 + 译文”，新句滑入，
 * 每句停留约 12 秒后渐隐移除，不会一直堆在窗口上。
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
    private var transcriptBox: LinearLayout? = null
    private var liveView: TextView? = null
    private var dot: View? = null
    private var directionView: TextView? = null
    private var pauseView: TextView? = null

    private val entries = ArrayList<Entry>()
    private var lastLineKey = ""
    private var currentLive = ""
    private var collapsed = false

    val isShowing: Boolean get() = root != null

    fun show(direction: String, scopeLabel: String): Boolean {
        if (!Settings.canDrawOverlays(context)) return false

        val existing = root
        if (existing != null) {
            setDirection(direction)
            return true
        }

        val dm = context.resources.displayMetrics
        val width = min((dm.widthPixels * 0.94f).toInt(), dp(380))
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - width - dp(6)
            y = dp(72)
        }

        val view = buildViews(direction)
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
        clearEntries()
        root = null
        params = null
        lastLineKey = ""
        runCatching { wm.removeView(view) }
    }

    fun setTranscript(history: List<SubtitleLine>, live: String) {
        currentLive = live

        if (history.isEmpty()) {
            if (entries.isNotEmpty()) clearEntries()
            lastLineKey = ""
            updateLive("")
            return
        }

        val last = history.last()
        val key = last.source + "\u0002" + last.translated

        if (lastLineKey.isEmpty()) {
            // 首次显示 / 重新打开悬浮窗：把当前历史一次性铺上
            clearEntries()
            history.forEach { addEntry(it, animate = false) }
            lastLineKey = key
            scrollToBottom(smooth = false)
        } else if (key != lastLineKey) {
            // 新句落定：只追加最后一句
            addEntry(last, animate = true)
            lastLineKey = key
            scrollToBottom(smooth = true)
        }

        updateLive(live)
    }

    fun setDirection(direction: String) {
        directionView?.text = direction
    }

    fun setScope(scopeLabel: String) = Unit

    fun setStatus(status: SessionStatus) {
        val color = when (status) {
            SessionStatus.RUNNING -> Colors.mint
            SessionStatus.PAUSED -> Colors.paused
            SessionStatus.STARTING -> Colors.blue
            SessionStatus.ERROR -> Colors.danger
            SessionStatus.IDLE -> Colors.muted
        }
        dot?.background = circle(color)
        if (status == SessionStatus.ERROR) {
            liveView?.text = "音频采集异常"
            liveView?.visibility = View.VISIBLE
        }
    }

    fun setPaused(paused: Boolean) {
        dot?.background = circle(if (paused) Colors.paused else Colors.mint)
        pauseView?.text = if (paused) "继续" else "暂停"
    }

    // ---------------------------------------------------------------- views

    private fun buildViews(direction: String): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(Colors.bg)
                setStroke(dp(1), Colors.border)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusDot = View(context).apply { background = circle(Colors.mint) }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(6), dp(6)))
        dot = statusDot

        val title = TextView(context).apply {
            text = "应灵"
            setTextColor(Colors.text)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(5) })

        val dir = TextView(context).apply {
            text = direction
            setTextColor(Colors.mint)
            textSize = 8.5f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(Colors.chipBg, 99)
        }
        header.addView(dir, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        directionView = dir

        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        val pause = TextView(context).apply {
            text = "暂停"
            setTextColor(Colors.mint)
            textSize = 9f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            isClickable = true
            setOnClickListener { callbacks.onTogglePause() }
        }
        header.addView(pause)
        pauseView = pause

        val stop = TextView(context).apply {
            text = "停止"
            setTextColor(Colors.danger)
            textSize = 9f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            isClickable = true
            setOnClickListener { callbacks.onStop() }
        }
        header.addView(stop)

        val collapse = TextView(context).apply {
            text = "▾"
            setTextColor(Colors.muted)
            textSize = 11f
            setPadding(dp(5), 0, dp(3), dp(2))
            isClickable = true
        }
        header.addView(collapse)

        val close = TextView(context).apply {
            text = "×"
            setTextColor(Colors.muted)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(3), 0, 0, dp(1))
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
            collapse.text = if (collapsed) "▸" else "▾"
        }

        val maxHeight = min((context.resources.displayMetrics.heightPixels * 0.55f).toInt(), dp(330))
        val scroller = MaxScrollView(context, maxHeight)
        bodyView.addView(scroller, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        scroll = scroller

        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))
        transcriptBox = box

        val placeholder = TextView(context).apply {
            text = "等待语音…"
            setTextColor(Colors.muted)
            textSize = 14f
        }
        box.addView(placeholder, LinearLayout.LayoutParams(MATCH, WRAP))
        liveView = placeholder

        return container
    }

    private fun addEntry(line: SubtitleLine, animate: Boolean) {
        val box = transcriptBox ?: return

        val entry = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }

        val source = TextView(context).apply {
            text = line.source
            setTextColor(Colors.muted)
            textSize = 9.5f
            setLineSpacing(0f, 1.05f)
        }
        entry.addView(source, LinearLayout.LayoutParams(MATCH, WRAP))

        val target = TextView(context).apply {
            text = line.translated
            setTextColor(Colors.text)
            textSize = 15f
            setLineSpacing(0f, 1.08f)
            setPadding(0, dp(1), 0, 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        entry.addView(target, LinearLayout.LayoutParams(MATCH, WRAP))

        if (animate) {
            entry.alpha = 0f
            entry.translationY = dp(10).toFloat()
        }

        val live = liveView
        val index = if (live != null && live.parent === box) box.indexOfChild(live) else box.childCount
        box.addView(entry, index, LinearLayout.LayoutParams(MATCH, WRAP))

        if (animate) {
            entry.animate().alpha(1f).translationY(0f).setDuration(240).start()
        }

        val fade = Runnable { fadeOut(entry) }
        entry.postDelayed(fade, HOLD_MS)
        entries.add(Entry(entry, fade))
    }

    private fun fadeOut(view: View) {
        view.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                transcriptBox?.removeView(view)
                entries.removeAll { it.view === view }
                if (entries.isEmpty() && currentLive.isBlank()) {
                    liveView?.text = "等待语音…"
                    liveView?.visibility = View.VISIBLE
                }
                scrollToBottom(smooth = false)
            }
            .start()
    }

    private fun clearEntries() {
        val box = transcriptBox ?: return
        entries.forEach { entry ->
            entry.view.removeCallbacks(entry.fade)
            box.removeView(entry.view)
        }
        entries.clear()
    }

    private fun updateLive(live: String) {
        val view = liveView ?: return
        val box = transcriptBox ?: return

        if (live.isBlank()) {
            if (entries.isEmpty()) {
                view.text = "等待语音…"
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
        } else {
            view.text = live
            view.visibility = View.VISIBLE
        }

        // live 行永远在最底部
        if (view.parent === box) {
            box.removeView(view)
        }
        box.addView(view, LinearLayout.LayoutParams(MATCH, WRAP))
        maybeScrollToBottom()
    }

    private fun scrollToBottom(smooth: Boolean) {
        val s = scroll ?: return
        s.post {
            val y = transcriptBox?.height ?: 0
            if (smooth) s.smoothScrollTo(0, y) else s.scrollTo(0, y)
        }
    }

    private fun maybeScrollToBottom() {
        val s = scroll ?: return
        val content = s.getChildAt(0) ?: return
        val nearBottom = s.scrollY + s.height >= content.height - dp(48)
        if (nearBottom) {
            s.post { s.fullScroll(View.FOCUS_DOWN) }
        }
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
            dp(6)
        } else {
            dm.widthPixels - lp.width - dp(6)
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

    private data class Entry(val view: View, val fade: Runnable)

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
        val paused: Int = Color.rgb(255, 184, 107)
        val chipBg: Int = Color.argb(26, 91, 231, 196)
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val HOLD_MS = 12_000L
        const val FADE_MS = 650L
    }
}
