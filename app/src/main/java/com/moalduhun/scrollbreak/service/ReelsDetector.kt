package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic classifier that decides whether the Instagram window currently on screen
 * is the Reels player.
 *
 * There is no public API for "what screen is open inside another app" — this walks the
 * accessibility node tree Instagram exposes and looks for hints Reels leaves behind.
 *
 * A real device capture (via the ScrollBreakDiag logging below) showed that Instagram's
 * own resource-ids are stripped (empty) and its custom view classes are reported as plain
 * Android widget names (framelayout, viewgroup, ...) — so matching on "clips" class/id
 * keywords, which was the original plan, never fires on this build and is kept only as a
 * cheap bonus signal in case a different build does expose them.
 *
 * What the capture also showed: the combination that was actually triggering blocks —
 * "Reels tab selected" plus "a Like and a Comment icon are visible" — is too weak on its
 * own. Like/Comment icons sit on almost every screen with a post (Home, DMs, a profile),
 * so once that combination fired anywhere it started blocking those screens too, not just
 * the real Reels player.
 *
 * The structural signal used now instead: Reels is, physically, one thing every version of
 * Instagram must use no matter how it obfuscates its own code — a video-rendering surface
 * (SurfaceView/TextureView/VideoView are platform classes; an app cannot rename or hide
 * them from accessibility) that fills nearly the whole screen. Stories also play full-
 * screen video, so this alone does not mean Reels — it has to be paired with a corroborating
 * hint (the Reels tab being selected, or Like+Comment icons on screen) before it counts.
 * Neither corroborating hint is trusted by itself anymore.
 */
object ReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    // A node must cover at least this fraction of the window's width/height to be
    // considered "full-screen" rather than a small preview thumbnail or shelf card.
    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.6f

    private val VIDEO_SURFACE_CLASS_KEYWORDS = listOf("surfaceview", "textureview", "videoview")
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
            val isFullScreenNode = hasUsableWindowBounds && isFullScreen(node, windowBounds)

            val isVideoSurface = className.isNotEmpty() && VIDEO_SURFACE_CLASS_KEYWORDS.any { className.contains(it) }
            if (isVideoSurface && isFullScreenNode) {
                matched += "video_surface:$className"
            }

            val looksLikeClips = (className.isNotEmpty() && CLASS_NAME_KEYWORDS.any { className.contains(it) }) ||
                (resourceId.isNotEmpty() && RESOURCE_ID_KEYWORDS.any { resourceId.contains(it) })
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

        val hasFullScreenVideo = matched.any { it.startsWith("video_surface:") }
        val hasClipsKeyword = matched.any { it.startsWith("class:") || it.startsWith("id:") }
        val hasCorroboratingHint = matched.contains("tab:reels_selected") || matched.contains("actions:like_and_comment")

        // A structural signal (an actual full-screen video surface, or — if a future
        // Instagram build exposes them again — the "clips" class/id keywords) must be
        // present. Reels-tab-selected and Like+Comment are corroborating only: neither
        // one, nor the two of them together, is allowed to trigger a block by itself,
        // because both show up on ordinary screens (Home, DMs, a profile) too.
        val isReels = hasClipsKeyword || (hasFullScreenVideo && hasCorroboratingHint)

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
