package com.moalduhun.scrollbreak.service

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
 * agreeing, which is why both are kept broad enough to match the single-reel viewer too.
 */
object ReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30

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

    data class DetectionResult(val isReels: Boolean, val matchedSignals: List<String>)

    fun evaluate(root: AccessibilityNodeInfo?): DetectionResult {
        if (root == null) return DetectionResult(false, emptyList())

        val matched = mutableSetOf<String>()
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
            if (className.isNotEmpty() && CLASS_NAME_KEYWORDS.any { className.contains(it) }) {
                matched += "class:$className"
            }

            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()
            if (resourceId.isNotEmpty() && RESOURCE_ID_KEYWORDS.any { resourceId.contains(it) }) {
                matched += "id:$resourceId"
            }

            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (contentDesc.isNotEmpty()) {
                if (node.isSelected && REELS_TAB_CONTENT_DESC.any { contentDesc.contains(it) }) {
                    reelsTabSelected = true
                }
                if (contentDesc.contains("like")) hasLikeAction = true
                if (contentDesc.contains("comment")) hasCommentAction = true
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

        return DetectionResult(isReels, matched.toList())
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
