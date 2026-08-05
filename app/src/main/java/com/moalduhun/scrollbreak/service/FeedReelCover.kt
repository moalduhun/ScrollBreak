package com.moalduhun.scrollbreak.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.moalduhun.scrollbreak.R

/**
 * A black patch that covers just a Reel embedded in a scrolling feed — used for Facebook,
 * where reels appear inline in the News Feed rather than only in a full-screen player. Unlike
 * [BlockOverlay] it doesn't take over the whole screen: it's a small, positioned, NON-touchable
 * window sized to the reel's bounds, so the user can still scroll the feed underneath it. It's
 * repositioned to track the reel as the feed scrolls, shows a "blocked" label, and mutes media
 * audio while it's up so the covered reel is silent.
 */
class FeedReelCover(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audioManager = service.getSystemService(AudioManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var savedVolume = -1

    val isShowing: Boolean get() = view != null

    /** Shows the cover over [rect], or moves the existing one there. Mutes media audio. */
    fun showAt(rect: Rect) {
        val wm = windowManager ?: return
        val width = rect.width().coerceAtLeast(1)
        val height = rect.height().coerceAtLeast(1)

        val existing = view
        if (existing == null) {
            val v = buildView()
            val p = WindowManager.LayoutParams(
                width,
                height,
                rect.left,
                rect.top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(v, p)
                view = v
                params = p
            } catch (t: Throwable) {
                return
            }
        } else {
            val p = params ?: return
            p.x = rect.left
            p.y = rect.top
            p.width = width
            p.height = height
            try {
                wm.updateViewLayout(existing, p)
            } catch (_: Throwable) {
            }
        }
        muteMedia()
    }

    /** Removes the cover and restores audio. */
    fun hide() {
        view?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Throwable) {
            }
        }
        view = null
        params = null
        unmuteMedia()
    }

    private fun buildView(): View {
        return FrameLayout(service).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                TextView(service).apply {
                    text = service.getString(R.string.feed_reel_blocked)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply { gravity = Gravity.CENTER }
                }
            )
        }
    }

    private fun muteMedia() {
        val am = audioManager ?: return
        if (savedVolume >= 0) return
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Throwable) {
        }
    }

    private fun unmuteMedia() {
        if (savedVolume < 0) return
        try {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        } catch (_: Throwable) {
        }
        savedVolume = -1
    }
}
