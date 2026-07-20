package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic classifier that decides whether the YouTube window currently on screen is the
 * Shorts player. It mirrors [ReelsDetector]'s approach — walk the accessibility node tree,
 * require independent signals to agree instead of trusting one id — but for YouTube.
 *
 * IMPORTANT: unlike [ReelsDetector], the keyword/threshold values here have NOT yet been
 * confirmed against a real ScrollBreakDiag capture of YouTube Shorts. They are a
 * deliberately conservative first pass built from YouTube's known structure:
 * - YouTube's internal name for Shorts is "reel" (the vertical Shorts pager is
 *   `reel_recycler`, the player `reel_player_...`, the scrubber `reel_time_bar`), so the
 *   resource-id keywords look for "reel_" — though ids may come back blank on-device the
 *   same way they do for Instagram (no flagReportViewIds), in which case this leans on the
 *   content-description and video signals instead.
 * - The Shorts surface and its bottom-nav tab expose a "Shorts" content-description.
 * - The Shorts player shows a like / dislike / comment / share / remix rail.
 *
 * To avoid blocking the whole YouTube app (home feed, normal watch page, search) before we
 * have real data, the decision is intentionally strict: a "Shorts" hint must be present AND
 * agree with either a full-screen video surface or the like+comment rail. The [diagnostics]
 * list logs anything reel/short/video-flavoured so `adb logcat -s ScrollBreakDiag:V` can be
 * used to replace these guesses with confirmed values, exactly as was done for Instagram.
 */
object YouTubeShortsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    // Shorts play in a nearly-full-height surface; kept a little looser than Instagram's
    // because YouTube leaves more chrome (title/search) visible in some entry points.
    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.70f

    private val RESOURCE_ID_KEYWORDS = listOf(
        "reel_recycler",
        "reel_player",
        "reel_watch",
        "reel_time_bar",
        "reel_progress",
        "shorts_"
    )
    private const val SHORTS_CONTENT_DESC = "shorts"
    private val VIDEO_SURFACE_CLASS_KEYWORDS = listOf("surfaceview", "textureview", "videoview")

    // YouTube's like/comment buttons expose descriptions like "like this video along with…",
    // "I dislike this", "Comments". Matched loosely; only ever used together with a Shorts
    // hint, never on their own (a normal watch page has the same rail).
    private val LIKE_KEYWORDS = listOf("like this", "unlike", "dislike")
    private val COMMENT_KEYWORDS = listOf("comment")

    // Broader than the keywords above on purpose — feeds diagnostics only, never the block
    // decision, so it surfaces things the strict lists miss while tuning against a real app.
    private val DIAGNOSTIC_KEYWORDS =
        listOf("reel", "short", "surface", "texture", "video", "player", "seek", "scrub")

    data class DetectionResult(
        val isShorts: Boolean,
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
        var hasShortsHint = false
        var hasFullScreenVideo = false
        var hasLikeAction = false
        var hasCommentAction = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodesVisited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            nodesVisited++

            val isVisible = node.isVisibleToUser()
            val className = node.className?.toString()?.lowercase().orEmpty()
            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()
            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()

            if (isVisible && resourceId.isNotEmpty() && RESOURCE_ID_KEYWORDS.any { resourceId.contains(it) }) {
                matched += "id:$resourceId"
                hasShortsHint = true
            }

            if (isVisible && contentDesc.contains(SHORTS_CONTENT_DESC)) {
                matched += "desc:shorts"
                hasShortsHint = true
            }

            if (!hasFullScreenVideo && isVisible && hasUsableWindowBounds &&
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

            if (contentDesc.isNotEmpty()) {
                if (LIKE_KEYWORDS.any { contentDesc.contains(it) }) hasLikeAction = true
                if (COMMENT_KEYWORDS.any { contentDesc.contains(it) }) hasCommentAction = true
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

        if (hasLikeAction && hasCommentAction) matched += "actions:like_and_comment"

        // Strict on purpose until real captures let us relax it: a Shorts hint must be
        // present, and it must agree with either a full-screen video surface or the
        // like+comment rail. This keeps the normal YouTube home feed / watch page / search
        // from being blocked, at the cost of possibly missing Shorts until tuned.
        val isShorts = hasShortsHint && (hasFullScreenVideo || (hasLikeAction && hasCommentAction))

        return DetectionResult(isShorts, matched.toList(), diagnostics)
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
