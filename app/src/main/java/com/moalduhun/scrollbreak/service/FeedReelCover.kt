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
 * Black patches that cover Reels embedded in a scrolling feed (Facebook), where reels appear
 * inline in the News Feed rather than only in a full-screen player. Unlike [BlockOverlay] this
 * doesn't take over the screen: each patch is a small, positioned, NON-touchable window sized
 * to one reel's bounds, so the user can still scroll the feed underneath. Multiple reels on
 * screen each get their own patch. The patches are repositioned/resized to track the reels as
 * the feed scrolls, show a "blocked" label, and media audio is muted while any is up.
 */
class FeedReelCover(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audioManager = service.getSystemService(AudioManager::class.java)
    private val covers = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private var savedVolume = -1

    val isShowing: Boolean get() = covers.isNotEmpty()

    /** Covers each of [regions] with a patch (adding/removing/repositioning as needed). */
    fun showRegions(regions: List<Rect>) {
        val wm = windowManager ?: return
        if (regions.isEmpty()) {
            hide()
            return
        }

        // Add patches until we have one per region.
        while (covers.size < regions.size) {
            val view = buildView()
            val params = newParams()
            try {
                wm.addView(view, params)
                covers.add(view to params)
            } catch (t: Throwable) {
                break
            }
        }
        // Remove any extra patches from a previous frame that had more reels.
        while (covers.size > regions.size) {
            val (view, _) = covers.removeAt(covers.lastIndex)
            try {
                wm.removeView(view)
            } catch (_: Throwable) {
            }
        }

        for (i in covers.indices) {
            val (view, params) = covers[i]
            val r = regions[i]
            params.x = r.left
            params.y = r.top
            params.width = r.width().coerceAtLeast(1)
            params.height = r.height().coerceAtLeast(1)
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Throwable) {
            }
        }
        muteMedia()
    }

    /** Removes all patches and restores audio. */
    fun hide() {
        for ((view, _) in covers) {
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        covers.clear()
        unmuteMedia()
    }

    private fun newParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            1,
            1,
            0,
            0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }

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
