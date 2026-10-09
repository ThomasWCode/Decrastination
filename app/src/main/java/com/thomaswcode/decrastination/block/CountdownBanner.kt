package com.thomaswcode.decrastination.block

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The banner every automatic Teams sync shows first (Q21): at the top of the screen, counting
 * down 10 s, with **Cancel** and **Delay 5 min**. A `TYPE_ACCESSIBILITY_OVERLAY` window, which an
 * accessibility service may add without the overlay permission; it looks like the Teams widget's
 * own sync pill, which takes over once the sync starts.
 */
class CountdownBanner(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var label: TextView? = null
    private var left = 0
    private var message = ""
    private var onTimeout: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    /** Shows [message] (with `%d` for the seconds left) for [seconds], then runs [onTimeout]. */
    fun show(message: String, seconds: Int, onCancel: () -> Unit, onDelay: () -> Unit, onTimeout: () -> Unit) {
        cancel()
        this.message = message
        this.left = seconds
        this.onTimeout = onTimeout
        val view = build(
            cancel = {
                cancel()
                onCancel()
            },
            delay = {
                cancel()
                onDelay()
            },
        )
        runCatching { windowManager.addView(view, layoutParams()) }.onFailure { return }
        root = view
        render()
        handler.postDelayed(tick, 1_000L)
    }

    /** Takes the banner away without running anything. */
    fun cancel() {
        handler.removeCallbacks(tick)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        label = null
        onTimeout = null
    }

    private val tick = object : Runnable {
        override fun run() {
            left--
            if (left <= 0) {
                val run = onTimeout
                cancel()
                run?.invoke()
            } else {
                render()
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private fun render() {
        label?.text = message.format(left)
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(44)
    }

    private fun build(cancel: () -> Unit, delay: () -> Unit): View {
        // Material You colours where available, so the banner matches the phone's theme.
        val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val surface = if (dynamic) service.getColor(android.R.color.system_neutral1_800) else 0xFF2B2D31.toInt()
        val onSurface = if (dynamic) service.getColor(android.R.color.system_neutral1_50) else Color.WHITE
        val accent = if (dynamic) service.getColor(android.R.color.system_accent1_200) else 0xFFA8C7FA.toInt()

        label = TextView(service).apply {
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), 0, dp(8), 0)
            maxLines = 1
            isSingleLine = true
        }
        fun button(text: String, onClick: () -> Unit) = TextView(service).apply {
            this.text = text
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 1
            isSingleLine = true
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) },
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(surface)
            }
            elevation = dp(6).toFloat()
            addView(label)
            addView(button("Cancel", cancel))
            addView(button("Delay 5 min", delay))
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
