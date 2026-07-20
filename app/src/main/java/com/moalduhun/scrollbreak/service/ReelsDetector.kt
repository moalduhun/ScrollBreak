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
 * This only looks at what is currently on screen, never how the user got there — so a
 * Reel opened from a DM share, a Search/Explore result, or someone's profile grid is
 * caught the same way as one opened from the Reels tab: Instagram reuses the same player
 * component for all of them, it just arrives with a different back stack underneath it.
 * The one signal that does depend on entry point is the "Reels tab selected" hint, which
 * is only present for tab entry — everywhere else has to rely on class name + resource id
 * agreeing.
 *
 * Instagram also reuses the "clips" widget classes and id prefixes for small Reels
 * *previews* — the recommendation shelf on the Home feed, grid thumbnails on Search, and
 * on a profile's Reels tab. A class/id hit only counts toward blocking if the matching
 * node fills nearly the whole screen, which is true for the immersive player but not for
 * a shelf card or a grid thumbnail.
 *
 * Every keyword/threshold here is a guess made without a real Instagram install to test
 * against. [DetectionResult.diagnostics] exists so the guesses can be replaced with real
 * data: it records every node this pass saw that even loosely looks Reels-related, plus
 * whichever bottom-tab item is currently selected, regardless of whether it counted
 * toward the block decision. See [ReelsAccessibilityService] for where this gets logged.
 */
object ReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 40

    // A node must cover at least this fraction of the window's width/height to be
    // considered "full-screen" rather than a small preview thumbnail or shelf card.
    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.6f

    private val CLASS_NAME_KEYWORDS = listOf("clips")
    private val RESOURCE_ID_KEYWORDS = listOf(
        "clips_viewer",
        "clips_tab",
        "clips_swipe",
        "clips_progress",
        "clip_viewer",
        "reels_viewer",
        "clips_single",
        "single_clip",
        "clips_media",
        "clips_caption"
    )
    private val REELS_TAB_CONTENT_DESC = listOf("reels")

    // Broader than the keywords above on purpose — this only feeds diagnostics, not the
    // block decision, so it is meant to surface things the strict keyword list misses.
    private val DIAGNOSTIC_KEYWORDS = listOf("clip", "reel")

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
        var nodesVisited = 0
        var reelsTabSelected = false
        var hasLikeAction = false
        var hasCommentAction = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodesVisited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            nodesVisited++

            val className = node.className?.toString()?.lowercase().orEmpty()
            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()
            val looksLikeClips = (className.isNotEmpty() && CLASS_NAME_KEYWORDS.any { className.contains(it) }) ||
                (resourceId.isNotEmpty() && RESOURCE_ID_KEYWORDS.any { resourceId.contains(it) })
            val isFullScreenNode = !hasUsableWindowBounds || isFullScreen(node, windowBounds)

            if (looksLikeClips && isFullScreenNode) {
                if (className.isNotEmpty() && CLASS_NAME_KEYWORDS.any { className.contains(it) }) {
                    matched += "class:$className"
                }
                if (resourceId.isNotEmpty() && RESOURCE_ID_KEYWORDS.any { resourceId.contains(it) }) {
                    matched += "id:$resourceId"
                }
            }

            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (contentDesc.isNotEmpty()) {
                if (node.isSelected && REELS_TAB_CONTENT_DESC.any { contentDesc.contains(it) }) {
                    reelsTabSelected = true
                }
                if (contentDesc.contains("like")) hasLikeAction = true
                if (contentDesc.contains("comment")) hasCommentAction = true
            }

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

        if (reelsTabSelected) matched += "tab:reels_selected"
        if (hasLikeAction && hasCommentAction) matched += "actions:like_and_comment"

        val categoriesMatched = listOf(
            matched.any { it.startsWith("class:") },
            matched.any { it.startsWith("id:") },
            matched.contains("tab:reels_selected")
        ).count { it }

        // Require two agreeing categories, or one plus the weaker like+comment hint
        // (which alone also appears on a normal feed post, so it can't count by itself).
        val isReels = categoriesMatched >= 2 ||
            (categoriesMatched >= 1 && matched.contains("actions:like_and_comment"))

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
