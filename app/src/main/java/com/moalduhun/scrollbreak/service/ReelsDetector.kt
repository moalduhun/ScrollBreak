package com.moalduhun.scrollbreak.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic classifier that decides whether the Instagram window currently on screen
 * is the Reels player.
 *
 * Every real resource-id used below is confirmed from `adb shell uiautomator dump`
 * captures of the actual Reels tab, an Explore-opened Reel, and a Reel embedded/
 * previewed inline in the Home feed and in a DM — not guessed.
 *
 * The big discovery: this app's accessibility service was never granted permission to
 * read Instagram's element names at all (`viewIdResourceName` came back blank on every
 * node, live, on this device), even though `uiautomator dump` — a system tool with more
 * access — showed full names for the exact same screens. That required the
 * `flagReportViewIds` flag in accessibility_service_config.xml, which was missing. With
 * it enabled, the ids below are readable for real and this detector can rely on Reels'
 * actual internal names instead of the earlier fallbacks (Reels-tab-selected, Like+
 * Comment icons) that were the whole reason Home/DMs sometimes got blocked by mistake.
 *
 * What the captures showed:
 * - Every genuine Reels player — reached from the Reels tab, Explore, or a DM — is built
 *   from the same `clips_video_container`/`clips_viewer_video_layout` chrome and, when
 *   its own top action bar is visible, a `clips_viewer_action_bar_title` node (showing
 *   the word "Reels", or briefly empty while it fades).
 * - Instagram reuses those same "clips_" ids for small Reels *previews* too — a Reel
 *   embedded in the Home feed, a DM's unopened preview bubble, a profile grid thumbnail.
 *   Those are never full-screen. So an id match only counts if the matching video
 *   element actually fills the screen — this is the same full-screen gate this detector
 *   has needed since the very first version, just now applied to real ids instead of
 *   guessed ones.
 * - A Reel opened from a DM launches in its own window,
 *   `com.instagram.modal.TransparentModalActivity` — kept as an alternate way to confirm
 *   a full-screen video is really a Reel, for the rare case the action-bar title isn't
 *   present (e.g. mid-transition, or its own back arrow instead of a title).
 * - `row_feed_`-prefixed ids belong only to ordinary Home feed posts, confirmed never to
 *   appear alongside the real Reels viewer — kept as a belt-and-suspenders veto.
 *
 * In practice, `flagReportViewIds` has not reliably taken effect on the test device —
 * ids come back readable sometimes and blank other times even after re-toggling the
 * permission, which is outside this app's control. Relying on the action-bar-title id
 * alone regressed the Reels tab and a Home-opened Reel to never blocking, while DMs kept
 * working because that path only needs the *window* class name (never gated by the
 * flag). So the pre-id fallbacks — the Reels tab's own "selected" state, and a Like icon
 * together with a Comment icon (both read from content-description, also never gated by
 * the flag) — are back as an alternate way to satisfy the Reels-chrome requirement below.
 * They are still only trusted alongside an actual full-screen video, same as the id
 * path — that full-screen gate is what stopped them from over-blocking Home before.
 */
object ReelsDetector {

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30
    private const val MAX_DIAGNOSTIC_LINES = 50

    // A node must cover at least this fraction of the window's width/height to count as
    // "full-screen" rather than a small preview/embed. Real numbers: the genuine Reels
    // player's video area covered 85-93% of window height across every capture; the
    // tallest confirmed non-Reels case (a tall video post in the ordinary Home feed)
    // covered ~72%. 78% sits between those with a safety margin on both sides.
    private const val FULLSCREEN_WIDTH_RATIO = 0.85f
    private const val FULLSCREEN_HEIGHT_RATIO = 0.78f

    private val VIDEO_SURFACE_CLASS_KEYWORDS = listOf("surfaceview", "textureview", "videoview")
    private val CLIPS_VIDEO_ID_KEYWORDS = listOf("clips_video_container", "clips_viewer_video_layout")
    private const val REELS_ACTION_BAR_TITLE_ID = "clips_viewer_action_bar_title"
    private val FEED_POST_ID_KEYWORDS = listOf("row_feed_")
    private const val REEL_DM_MODAL_WINDOW_CLASS = "com.instagram.modal.transparentmodalactivity"
    private val REELS_TAB_CONTENT_DESC = listOf("reels")

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

    fun evaluate(root: AccessibilityNodeInfo?, windowClassName: CharSequence? = null): DetectionResult {
        if (root == null) return DetectionResult(false, emptyList(), emptyList())

        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        val hasUsableWindowBounds = windowBounds.width() > 0 && windowBounds.height() > 0

        val matched = mutableSetOf<String>()
        val diagnostics = mutableListOf<String>()
        var nodesVisited = 0
        var hasFullScreenVideo = false
        var hasActionBarTitle = false
        var hasFeedPostIndicator = false
        var reelsTabSelected = false
        var hasLikeAction = false
        var hasCommentAction = false

        val isReelDmModalWindow = windowClassName?.toString()?.lowercase()
            ?.contains(REEL_DM_MODAL_WINDOW_CLASS) == true
        if (isReelDmModalWindow) matched += "window:transparent_modal"

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodesVisited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            nodesVisited++

            // Instagram keeps offscreen pages alive with stale state (confirmed in
            // practice for the Reels tab) so no signal here is trusted from a node that
            // isn't actually on screen right now.
            val isVisible = node.isVisibleToUser()

            val className = node.className?.toString()?.lowercase().orEmpty()
            val resourceId = node.viewIdResourceName?.lowercase().orEmpty()

            if (isVisible && !hasFullScreenVideo && hasUsableWindowBounds) {
                val looksLikeVideo = (className.isNotEmpty() && VIDEO_SURFACE_CLASS_KEYWORDS.any { className.contains(it) }) ||
                    (resourceId.isNotEmpty() && CLIPS_VIDEO_ID_KEYWORDS.any { resourceId.contains(it) })
                if (looksLikeVideo && isFullScreen(node, windowBounds)) {
                    hasFullScreenVideo = true
                    matched += "video:$className$resourceId"
                }
            }

            if (isVisible && resourceId.isNotEmpty() && resourceId.contains(REELS_ACTION_BAR_TITLE_ID)) {
                hasActionBarTitle = true
                matched += "id:$resourceId"
            }

            if (resourceId.isNotEmpty() && FEED_POST_ID_KEYWORDS.any { resourceId.contains(it) }) {
                hasFeedPostIndicator = true
            }

            val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (contentDesc.isNotEmpty()) {
                // Neither of these needs flagReportViewIds — content-description is
                // always readable — so they still work when the id-based signals above
                // don't. Same staleness caveat as the id check: Instagram keeps the
                // Reels tab's "selected" node around after leaving it, hidden, so this
                // only counts when the node is genuinely visible right now.
                if (isVisible && node.isSelected && REELS_TAB_CONTENT_DESC.any { contentDesc.contains(it) }) {
                    reelsTabSelected = true
                }
                if (contentDesc.contains("like")) hasLikeAction = true
                if (contentDesc.contains("comment")) hasCommentAction = true
            }

            if (diagnostics.size < MAX_DIAGNOSTIC_LINES) {
                val isDiagnosticCandidate = DIAGNOSTIC_KEYWORDS.any { className.contains(it) || resourceId.contains(it) }
                if (isDiagnosticCandidate) {
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

        if (hasFeedPostIndicator) matched += "feed_post_indicator"
        if (reelsTabSelected) matched += "tab:reels_selected"
        if (hasLikeAction && hasCommentAction) matched += "actions:like_and_comment"

        // The id-based signal is preferred when it's available, but ids have not
        // reliably come through on the test device even with flagReportViewIds granted
        // — so the pre-id fallbacks stay as a genuine alternate path, not just a note.
        val hasReelsChrome = hasActionBarTitle || isReelDmModalWindow || reelsTabSelected ||
            (hasLikeAction && hasCommentAction)
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
        val fullScreen = windowBounds.width() > 0 && windowBounds.height() > 0 && isFullScreen(node, windowBounds)
        val shortClassName = className.substringAfterLast('.')
        val shortDesc = contentDesc.take(40)
        return "class=$shortClassName id=$resourceId desc=\"$shortDesc\" " +
            "size=${bounds.width()}x${bounds.height()} fullScreen=$fullScreen visible=${node.isVisibleToUser()}"
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
