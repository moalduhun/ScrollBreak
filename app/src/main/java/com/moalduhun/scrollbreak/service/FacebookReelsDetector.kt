package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic classifier for Facebook's Reels player. Mirrors [YouTubeShortsDetector] — walk
 * the tree, require independent signals to agree — but for the Facebook app.
 *
 * IMPORTANT: like the YouTube detector started out, these keywords/thresholds are a
 * conservative FIRST PASS and have NOT been confirmed against a real ScrollBreakDiag capture
 * of Facebook. Facebook (com.facebook.katana) heavily obfuscates class and resource names, so
 * this leans on the things that survive obfuscation:
 * - a "reel" hint in a content-description or resource id (Facebook labels the Reels surface
 *   and its entry points with the word),
 * - a full-screen video surface,
 * - the like / comment action rail.
 *
 * To avoid blocking the normal Facebook feed (whose inline videos also expose like/comment),
 * a "reel" hint must be present AND agree with either a full-screen video or the like+comment
 * rail. Everything reel/video-flavoured is logged as a diagnostic so a real capture can
 * replace these guesses, exactly as was done for Instagram and YouTube.
 */
object FacebookReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.70f

    private val REEL_HINT_KEYWORDS = listOf("reel")
    private val VIDEO_SURFACE_CLASS_KEYWORDS = listOf("surfaceview", "textureview", "videoview")
    private val LIKE_KEYWORDS = listOf("like")
    private val COMMENT_KEYWORDS = listOf("comment")

    private val DIAGNOSTIC_KEYWORDS =
        listOf("reel", "surface", "texture", "video", "player", "seek", "scrub", "like", "comment")

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
        var hasReelHint = false
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

            if (isVisible && (REEL_HINT_KEYWORDS.any { resourceId.contains(it) || contentDesc.contains(it) })) {
                matched += "hint:reel"
                hasReelHint = true
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

        // Strict until real captures let us relax it: a reel hint must be present and agree
        // with either a full-screen video or the like+comment rail, so the normal feed isn't
        // blocked at the cost of maybe missing reels until tuned.
        val isReels = hasReelHint && (hasFullScreenVideo || (hasLikeAction && hasCommentAction))

        return DetectionResult(isReels, matched.toList(), diagnostics)
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
