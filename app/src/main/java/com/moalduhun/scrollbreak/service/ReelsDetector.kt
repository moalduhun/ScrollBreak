package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic classifier that decides whether the Instagram window currently on screen
 * is the Reels player.
 *
 * This is no longer a guess. It is built from two real `adb shell uiautomator dump`
 * captures (34 screens total) taken directly from a real Instagram install: the real
 * Reels tab playing, a tall video post in the ordinary Home feed, a Reel embedded/
 * previewed inline in the Home feed, a Reel shared in a DM (unopened preview bubble),
 * a profile grid, and a followers list.
 *
 * What that data showed:
 * - Instagram's real Reels viewer always contains a resource-id
 *   `clips_viewer_action_bar_title` — a small title-bar node that shows "Reels" (or is
 *   briefly empty while it fades). It appeared on every real Reels-tab capture and on
 *   no other screen in the whole dataset (Home, DMs, Profile, Followers list).
 * - A full-screen video ALONE is not enough: an ordinary tall video post in the Home
 *   feed had its video surface (a platform `SurfaceView`) cover ~72% of the window
 *   height, which would have passed a loose "mostly full screen" check. The real Reels
 *   player's surface covered ~92%+. The threshold below is set between those two real
 *   numbers, not guessed.
 * - Instagram reuses "clips_"-prefixed resource-ids (author name, follow button, like/
 *   comment/share/save row, more-options button, audio badge, "double tap to play"
 *   description) for Reels content that is only *previewed* small inside Home or a DM —
 *   not only for the real full-screen player. So "clips_" ids alone, without the
 *   full-screen video, do not mean Reels either.
 * - Feed posts use their own distinct ids (`row_feed_button_like`, `row_feed_photo_*`),
 *   never seen alongside the real Reels viewer — kept here as a belt-and-suspenders
 *   negative signal.
 *
 * So a screen only counts as Reels when the video is genuinely full-screen AND the
 * viewer shows Reels-specific chrome — not from a single weak hint like "a Like and a
 * Comment icon are visible", which is true of almost every screen with a post and is
 * what caused Home/DMs to get blocked in an earlier version of this detector.
 */
object ReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    // A node must cover at least this fraction of the window's width/height to count as
    // "full-screen". Set from real data: the real Reels player's video surface covered
    // ~92% of window height; an ordinary tall video post in the Home feed covered ~72%.
    private const val FULLSCREEN_WIDTH_RATIO = 0.90f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.85f

    // How many distinct Reels chrome elements are required before trusting them without
    // the explicit action-bar-title node (in case a future Instagram build hides/renames
    // just that one node).
    private const val MIN_CLIPS_ID_FALLBACK_COUNT = 2

    private val VIDEO_SURFACE_CLASS_KEYWORDS = listOf("surfaceview", "textureview", "videoview")

    // The one id seen on every real Reels-viewer capture and nowhere else in the dataset.
    private const val REELS_ACTION_BAR_TITLE_ID = "clips_viewer_action_bar_title"

    // Other "clips_"-prefixed ids confirmed real, used only as a fallback count when the
    // action bar title node above is missing.
    private val CLIPS_ID_KEYWORDS = listOf("clips_")

    // Confirmed to belong only to ordinary feed posts, never the Reels viewer.
    private val FEED_POST_ID_KEYWORDS = listOf("row_feed_")

    // Broader than the keywords above on purpose — this only feeds diagnostics, not the
    // block decision, so it is meant to surface things a strict keyword list would miss.
    private val DIAGNOSTIC_KEYWORDS = listOf("clip", "reel", "surface", "texture", "video", "player")

    data class DetectionResult(
        val isReels: Boolean,
        val matchedSignals: List<String>,
        val diagnostics: List<String>
    )

    fun evaluate(root: AccessibilityNodeInfo?): DetectionResult {
        if (root == null) return DetectionResult(false, emptyList(), emptyList())

        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        val hasUsableWindowBounds = windowBounds.width() > 0 && windowBounds.height() > 0

        val matched = mutableSetOf<String>()
        val diagnostics = mutableListOf<String>()
        val clipsIdsSeen = mutableSetOf<String>()
        var nodesVisited = 0
        var hasFullScreenVideo = false
        var hasActionBarTitle = false
        var hasFeedPostIndicator = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodesVisited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            nodesVisited++

            val className = node.className?.toString()?.lowercase().orEmpty()
            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()

            if (!hasFullScreenVideo &&
                className.isNotEmpty() &&
                VIDEO_SURFACE_CLASS_KEYWORDS.any { className.contains(it) } &&
                hasUsableWindowBounds &&
                isFullScreen(node, windowBounds)
            ) {
                hasFullScreenVideo = true
                matched += "video_surface:$className"
            }

            if (resourceId.isNotEmpty()) {
                if (resourceId.contains(REELS_ACTION_BAR_TITLE_ID)) {
                    hasActionBarTitle = true
                    matched += "id:$resourceId"
                }
                if (CLIPS_ID_KEYWORDS.any { resourceId.contains(it) }) {
                    clipsIdsSeen += resourceId
                }
                if (FEED_POST_ID_KEYWORDS.any { resourceId.contains(it) }) {
                    hasFeedPostIndicator = true
                }
            }

            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()

            if (diagnostics.size < MAX_DIAGNOSTIC_LINES) {
                val isDiagnosticCandidate = DIAGNOSTIC_KEYWORDS.any { className.contains(it) || resourceId.contains(it) }
                val isSelectedTabItem = node.isSelected && contentDesc.isNotEmpty()
                if (isDiagnosticCandidate || isSelectedTabItem) {
                    diagnostics += describeNode(node, className, resourceId, contentDesc, windowBounds)
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

        // Drain and recycle anything left over if we hit the node/depth cap early.
        while (queue.isNotEmpty()) {
            recycleSafely(queue.removeFirst().first)
        }

        if (clipsIdsSeen.isNotEmpty()) matched += "clips_id_count:${clipsIdsSeen.size}"
        if (hasFeedPostIndicator) matched += "feed_post_indicator"

        val hasReelsChrome = hasActionBarTitle || clipsIdsSeen.size >= MIN_CLIPS_ID_FALLBACK_COUNT
        val isReels = hasFullScreenVideo && hasReelsChrome && !hasFeedPostIndicator

        return DetectionResult(isReels, matched.toList(), diagnostics)
    }

    private fun describeNode(
        node: AccessibilityNodeInfo,
        className: String,
        resourceId: String,
        contentDesc: String,
        windowBounds: Rect
    ): String {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val fullScreen = if (windowBounds.width() > 0 && windowBounds.height() > 0) {
            isFullScreen(node, windowBounds)
        } else {
            false
        }
        val shortClassName = className.substringAfterLast('.')
        val shortDesc = contentDesc.take(40)
        return "class=$shortClassName id=$resourceId desc=\"$shortDesc\" " +
            "size=${bounds.width()}x${bounds.height()} fullScreen=$fullScreen selected=${node.isSelected}"
    }

    private fun isFullScreen(node: AccessibilityNodeInfo, windowBounds: Rect): Boolean {
        val nodeBounds = Rect().also { node.getBoundsInScreen(it) }
        if (nodeBounds.width() <= 0 || nodeBounds.height() <= 0) return false
        return nodeBounds.width() >= windowBounds.width() * FULLSCREEN_WIDTH_RATIO &&
            nodeBounds.height() >= windowBounds.height() * FULLSCREEN_HEIGHT_RATIO
    }

    @Suppress("DEPRECATION")
    private fun recycleSafely(node: AccessibilityNodeInfo) {
        // recycle() is a no-op on API 33+ (node pooling was removed) but still matters
        // for the node pool on older OS versions this app supports (minSdk 28).
        try {
            node.recycle()
        } catch (_: IllegalStateException) {
            // Already recycled elsewhere — safe to ignore.
        }
    }
}
