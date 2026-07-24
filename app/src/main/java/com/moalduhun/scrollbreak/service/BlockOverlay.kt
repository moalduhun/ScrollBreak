package com.moalduhun.scrollbreak.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.moalduhun.scrollbreak.R

/**
 * Full-screen block screen drawn directly by the accessibility service as an overlay window.
 *
 * Deliberately a window ([WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]), NOT a
 * separate Activity. Launching an activity pushes Instagram/YouTube to the background, which
 * is exactly what makes them spawn a Picture-in-Picture mini-player that keeps the video
 * playing in its own floating window. An overlay floats on top while the app stays in the
 * foreground, so no PiP is ever triggered. Media audio is muted while the overlay is up and
 * restored when it's taken down, so the covered reel/short is silent underneath.
 *
 * TYPE_ACCESSIBILITY_OVERLAY needs no "draw over other apps" permission — an accessibility
 * service is allowed to add it directly.
 */
class BlockOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audioManager = service.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var muted = false

    val isShowing: Boolean get() = overlayView != null

    fun show(onGoBack: () -> Unit) {
        if (overlayView != null) return
        muteMedia()
        val view = buildView(onGoBack)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        try {
            windowManager?.addView(view, params)
            overlayView = view
            view.requestFocus()
        } catch (t: Throwable) {
            // If the overlay can't be shown, don't leave the device muted.
            unmuteMedia()
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        overlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        overlayView = null
        unmuteMedia()
    }

    private fun muteMedia() {
        if (muted) return
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            muted = true
        } catch (_: Throwable) {
        }
    }

    private fun unmuteMedia() {
        if (!muted) return
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Throwable) {
        }
        muted = false
    }

    private fun buildView(onGoBack: () -> Unit): View {
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Match the app's palette (see ui/theme/Color.kt) for the active light/dark mode.
        val backgroundColor = if (night) 0xFF101014.toInt() else 0xFFFAFAFD.toInt()
        val mutedText = if (night) 0xFFC4C6D0.toInt() else 0xFF44464F.toInt()
        val primary = if (night) 0xFFB4B0FF.toInt() else 0xFF4F46E5.toInt()
        val primaryContainer = if (night) 0xFF322F7A.toInt() else 0xFFE4E1FF.toInt()
        val onPrimary = if (night) 0xFF17171D.toInt() else 0xFFFFFFFF.toInt()

        val density = service.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = FrameLayout(service).apply {
            setBackgroundColor(backgroundColor)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ ->
                // Swallow the system Back button so only the in-overlay button dismisses.
                keyCode == KeyEvent.KEYCODE_BACK
            }
        }

        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(36), dp(36), dp(36))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }

        val badge = FrameLayout(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(primaryContainer)
            }
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(28)
            }
        }
        val icon = ImageView(service).apply {
            setImageResource(R.drawable.ic_block)
            setColorFilter(primary)
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.CENTER
            }
        }
        badge.addView(icon)

        val message = TextView(service).apply {
            text = service.getString(R.string.block_overlay_message)
            setTextColor(mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val button = Button(service).apply {
            isAllCaps = false
            setTextColor(onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(primary)
            }
            isEnabled = false
            text = service.getString(R.string.block_overlay_cooldown, COOLDOWN_SECONDS)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { topMargin = dp(36) }
            setOnClickListener { onGoBack() }
        }

        // Brief cooldown before the button works — the same friction the old block screen had,
        // so it can't be tapped through by reflex the instant it appears.
        var remaining = COOLDOWN_SECONDS
        val ticker = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    button.isEnabled = true
                    button.text = service.getString(R.string.block_overlay_go_back)
                } else {
                    button.text = service.getString(R.string.block_overlay_cooldown, remaining)
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.postDelayed(ticker, 1000L)

        column.addView(badge)
        column.addView(message)
        column.addView(button)
        root.addView(column)
        return root
    }

    private companion object {
        const val COOLDOWN_SECONDS = 3
    }
}
