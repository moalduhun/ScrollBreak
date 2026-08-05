package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Classifier for Facebook's Reels player, tuned from a real ScrollBreakDiag capture.
 *
 * The tricky part on Facebook (com.facebook.katana) is that the normal News Feed embeds a
 * "Reels" tray and the bottom nav has a "Reels" tab, so the word "reel" and the like/comment
 * rail both appear on the ordinary feed — an early version keyed on those and wrongly blocked
 * the feed.
 *
 * The capture showed a clean separator instead: whenever the user is actually watching a reel
 * full-screen, the tree contains a VISIBLE, near-full-width, tall node whose content
 * description contains "reel":
 * - the Reels tab player exposes `reels tab details` at ~1080x2051 (visible),
 * - a reel opened inline from the feed exposes `reel` at ~1080x1350 (visible).
 *
 * On the feed those very nodes are present but collapsed (`0x2051`, visible=false), and the
 * only visible "reel" nodes are small tray thumbnails (well under full width) or the tiny nav
 * tab. So a single rule — a visible node with "reel" in its description that spans most of the
 * width and a good chunk of the height — matches the player and not the feed. The like/comment
 * rail is deliberately NOT used, since it's what caused the feed false-positive.
 */
object FacebookReelsDetector {

    private const val MAX_NODES = 700
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    // A reel's full-screen container spans the whole width and most of the height. Feed tray
    // thumbnails are ~684 wide (well under full) and the collapsed player nodes are 0-wide, so
    // these thresholds keep the feed out.
    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.55f

    // A reel embedded in the feed is smaller than full-screen but still a real video area
    // (confirmed ~684x1216 in a capture). Small tray labels and the nav tab fall under these.
    private const val FEED_WIDTH_RATIO = 0.40f
    private const val FEED_HEIGHT_RATIO = 0.30f

    private const val REEL_DESC_KEYWORD = "reel"

    private val DIAGNOSTIC_KEYWORDS =
        listOf("reel", "surface", "texture", "video", "player", "seek", "scrub", "like", "comment")

    data class DetectionResult(
        /** A dedicated full-screen reel player is showing — cover the whole screen and block. */
        val isFullScreenReel: Boolean,
        /** Bounds of reel(s) embedded in a scrolling feed to cover in place, or null. */
        val feedReelBounds: Rect?,
        val matchedSignals: List<String>,
        val diagnostics: List<String>
    )

    fun evaluate(root: AccessibilityNodeInfo?): DetectionResult {
        if (root == null) return DetectionResult(false, null, emptyList(), emptyList())

        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        val winWidth = windowBounds.width()
        val winHeight = windowBounds.height()
        if (winWidth <= 0 || winHeight <= 0) return DetectionResult(false, null, emptyList(), emptyList())

        val matched = mutableSetOf<String>()
        val diagnostics = mutableListOf<String>()
        var nodesVisited = 0
        var hasReelPlayer = false
        var feedUnion: Rect? = null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodesVisited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            nodesVisited++

            val isVisible = node.isVisibleToUser()
            val className = node.className?.toString()?.lowercase().orEmpty()
            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()
            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()

            // A visible node with "reel" in its description. If it spans most of the width and
            // a good part of the height it's the full-screen player; if it's smaller but still
            // a real video area it's a reel embedded in the feed to cover in place. On the feed
            // the full-screen equivalents are collapsed/invisible and only these mid-size reel
            // videos are visible, so the two never collide.
            if (isVisible && contentDesc.contains(REEL_DESC_KEYWORD)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val wr = bounds.width().toFloat() / winWidth
                val hr = bounds.height().toFloat() / winHeight
                if (wr >= FULLSCREEN_WIDTH_RATIO && hr >= FULLSCREEN_HEIGHT_RATIO) {
                    hasReelPlayer = true
                    matched += "reel_player:${contentDesc.take(24)}"
                } else if (wr >= FEED_WIDTH_RATIO && hr >= FEED_HEIGHT_RATIO) {
                    feedUnion = feedUnion?.also { it.union(bounds) } ?: Rect(bounds)
                    matched += "feed_reel:${contentDesc.take(24)}"
                }
            }

            if (diagnostics.size < MAX_DIAGNOSTIC_LINES) {
                val isDiagnosticCandidate = DIAGNOSTIC_KEYWORDS.any {
                    className.contains(it) || resourceId.contains(it) || contentDesc.contains(it)
                }
                if (isDiagnosticCandidate) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    diagnostics += "class=${className.substringAfterLast('.')} id=$resourceId " +
                        "desc=\"${contentDesc.take(40)}\" size=${bounds.width()}x${bounds.height()} " +
                        "visible=$isVisible"
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child to depth + 1)
                }
            }
            recycleSafely(node)
        }

        while (queue.isNotEmpty()) {
            recycleSafely(queue.removeFirst().first)
        }

        // The full-screen player wins — don't also try to cover a feed reel in that case. Clamp
        // the feed union to the window so an oddly-reported node can't push it off-screen.
        val feedBounds = if (hasReelPlayer) {
            null
        } else {
            feedUnion?.takeIf { it.intersect(windowBounds) }
        }

        return DetectionResult(hasReelPlayer, feedBounds, matched.toList(), diagnostics)
    }

    @Suppress("DEPRECATION")
    private fun recycleSafely(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: IllegalStateException) {
            // Already recycled elsewhere — safe to ignore.
        }
    }
}
