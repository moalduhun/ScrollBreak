package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic classifier that decides whether the Instagram window currently on screen
 * is the Reels player.
 *
 * There is no public API for "what screen is open inside another app" — this walks the
 * accessibility node tree Instagram exposes and looks for multiple independent hints at
 * once, instead of trusting a single view id. Instagram changes its internal ids across
 * updates, so any one signal can silently stop matching; requiring two categories to
 * agree makes a single renamed id less likely to break detection outright, and keeping
 * the keyword lists in one place makes it fast to retune after Instagram ships a change.
 *
 * Reels is Instagram/Meta's internal code name "Clips" — matching on "clips" avoids
 * colliding with Stories, whose legacy internal name ("Reel"/"ReelViewerFragment")
 * predates the Reels product and would otherwise cause false positives.
 *
 * A Reel opened from a DM launches in its own distinct window,
 * `com.instagram.modal.TransparentModalActivity` (confirmed from real captures) — a
 * useful, obfuscation-proof signal since it's a whole Activity class name, not a
 * renamable id. It isn't trusted alone, though: Instagram likely reuses that same
 * generic wrapper for other modals/dialogs unrelated to Reels, so it only counts
 * alongside the existing Like+Comment hint, the same bar the rest of this detector uses.
 *
 * A Reel opened from Explore/Search doesn't reliably raise any of the signals above (no
 * distinct window, no reliably-selected tab). Instead of touching the working paths
 * above, this adds one independent extra path just for that case: [recentContentTap]
 * (set by the service when the user's last tap landed on the content grid, not the
 * search bar or the bottom nav) combined with an actual full-screen video surface. A
 * grid tap alone would also fire for a normal photo post, so it is never trusted by
 * itself — only together with a real full-screen video is it treated as Reels.
 */
object ReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    // A node must cover at least this fraction of the window's width/height to count as
    // "full-screen" — only used for the tap-triggered Explore path below, so it can't
    // affect the class/id/tab/window signals the other paths already rely on.
    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.78f

    private val CLASS_NAME_KEYWORDS = listOf("clips")
    private val RESOURCE_ID_KEYWORDS = listOf(
        "clips_viewer",
        "clips_tab",
        "clips_swipe",
        "clips_progress",
        "clip_viewer",
        "reels_viewer"
    )
    private val REELS_TAB_CONTENT_DESC = listOf("reels")
    private const val REEL_DM_MODAL_WINDOW_CLASS = "com.instagram.modal.transparentmodalactivity"
    private val VIDEO_SURFACE_CLASS_KEYWORDS = listOf("surfaceview", "textureview", "videoview")

    // Broader than the keywords above on purpose — this only feeds diagnostics, not the
    // block decision, so it is meant to surface things the strict keyword list misses.
    // Kept around for `adb logcat -s ScrollBreakDiag:V` captures used to tune the
    // keywords/thresholds above against a real Instagram install.
    private val DIAGNOSTIC_KEYWORDS = listOf("clip", "reel", "surface", "texture", "video", "player")

    data class DetectionResult(
        val isReels: Boolean,
        val matchedSignals: List<String>,
        val diagnostics: List<String>
    )

    fun evaluate(
        root: AccessibilityNodeInfo?,
        windowClassName: CharSequence? = null,
        recentContentTap: Boolean = false
    ): DetectionResult {
        if (root == null) return DetectionResult(false, emptyList(), emptyList())

        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        val hasUsableWindowBounds = windowBounds.width() > 0 && windowBounds.height() > 0

        val matched = mutableSetOf<String>()
        val isReelDmModal = windowClassName?.toString()?.lowercase()?.contains(REEL_DM_MODAL_WINDOW_CLASS) == true
        if (isReelDmModal) matched += "window:transparent_modal"
        if (recentContentTap) matched += "tap:content_grid"
        val diagnostics = mutableListOf<String>()
        var nodesVisited = 0
        var reelsTabSelected = false
        var hasLikeAction = false
        var hasCommentAction = false
        var hasFullScreenVideo = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodesVisited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            nodesVisited++

            // Instagram keeps offscreen pages alive with stale state (confirmed in
            // practice for the Reels tab, see the isVisibleToUser() check below) so no
            // signal here is trusted from a node that isn't actually on screen right now.
            val isVisible = node.isVisibleToUser()

            val className = node.className?.toString()?.lowercase().orEmpty()
            if (isVisible && className.isNotEmpty() && CLASS_NAME_KEYWORDS.any { className.contains(it) }) {
                matched += "class:$className"
            }

            if (recentContentTap && !hasFullScreenVideo && isVisible && hasUsableWindowBounds &&
                className.isNotEmpty() && VIDEO_SURFACE_CLASS_KEYWORDS.any { className.contains(it) }
            ) {
                val nodeBounds = Rect().also { node.getBoundsInScreen(it) }
                val isFullScreen = nodeBounds.width() >= windowBounds.width() * FULLSCREEN_WIDTH_RATIO &&
                    nodeBounds.height() >= windowBounds.height() * FULLSCREEN_HEIGHT_RATIO
                if (isFullScreen) {
                    hasFullScreenVideo = true
                    matched += "video:$className"
                }
            }

            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()
            if (isVisible && resourceId.isNotEmpty() && RESOURCE_ID_KEYWORDS.any { resourceId.contains(it) }) {
                matched += "id:$resourceId"
            }

            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (contentDesc.isNotEmpty()) {
                if (node.isSelected && REELS_TAB_CONTENT_DESC.any { contentDesc.contains(it) }) {
                    // Confirmed from a real ScrollBreakDiag capture: Instagram keeps the
                    // Reels tab's "selected" node around after you leave it — hidden, but
                    // still isSelected=true. Without this visibility check, that stale
                    // node kept matching on every other screen (Home, DMs, ...) after the
                    // Reels tab had been opened once. isVisibleToUser() is what tells the
                    // real, currently-on-screen tab apart from that leftover one.
                    if (isVisible) {
                        reelsTabSelected = true
                    }
                    diagnostics += "REELS_TAB_NODE: visibleToUser=$isVisible " +
                        describeNode(node, className, resourceId, contentDesc)
                }
                if (contentDesc.contains("like")) hasLikeAction = true
                if (contentDesc.contains("comment")) hasCommentAction = true
            }

            if (diagnostics.size < MAX_DIAGNOSTIC_LINES) {
                val isDiagnosticCandidate = DIAGNOSTIC_KEYWORDS.any { className.contains(it) || resourceId.contains(it) }
                val isSelectedTabItem = node.isSelected && contentDesc.isNotEmpty()
                if (isDiagnosticCandidate || isSelectedTabItem) {
                    diagnostics += describeNode(node, className, resourceId, contentDesc)
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

        if (reelsTabSelected) matched += "tab:reels_selected"
        if (hasLikeAction && hasCommentAction) matched += "actions:like_and_comment"

        val categoriesMatched = listOf(
            matched.any { it.startsWith("class:") },
            matched.any { it.startsWith("id:") },
            matched.contains("tab:reels_selected"),
            isReelDmModal
        ).count { it }

        // Require two agreeing categories, or one plus the weaker like+comment hint
        // (which alone also appears on a normal feed post, so it can't count by itself).
        val isReels = categoriesMatched >= 2 ||
            (categoriesMatched >= 1 && matched.contains("actions:like_and_comment")) ||
            // Explore/Search path: the user's last tap was on the content grid (not
            // search or the nav bar) and it opened an actual full-screen video. A grid
            // tap alone isn't trusted — a normal photo post would also satisfy it — so
            // this only fires together with a genuine full-screen video surface.
            (recentContentTap && hasFullScreenVideo)

        return DetectionResult(isReels, matched.toList(), diagnostics)
    }

    private fun describeNode(
        node: AccessibilityNodeInfo,
        className: String,
        resourceId: String,
        contentDesc: String
    ): String {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val shortClassName = className.substringAfterLast('.')
        val shortDesc = contentDesc.take(40)
        return "class=$shortClassName id=$resourceId desc=\"$shortDesc\" " +
            "size=${bounds.width()}x${bounds.height()} selected=${node.isSelected} " +
            "visible=${node.isVisibleToUser()}"
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
