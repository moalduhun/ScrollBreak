package com.moalduhun.scrollbreak.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Decides what to do with whatever TikTok screen is currently showing.
 *
 * TikTok (package com.zhiliaoapp.musically) is short-form all the way down, so this is a
 * navigation policy rather than a single "is it a reel" check. Built from a real
 * ScrollBreakDiag capture:
 * - The foreground activity class name cleanly separates the full-screen viewers:
 *   `detail.ui.DetailActivity` is a single opened video (block), `PostModeDetailActivity` is
 *   the photo-carousel viewer (allow), `host.TikTokHostActivity` is the Shop (allow).
 * - Everything else lives in `main.MainActivity`; there the selected bottom tab decides —
 *   it shows up as a selected node with contentDescription Home / Friends / Inbox / Profile.
 *   The Home feed and Friends are video, so they're bounced to Inbox; Inbox and Profile are
 *   fine. The search page (opened over MainActivity) is allowed wherever it appears, spotted
 *   by its distinctive sub-tabs (Sounds / Hashtags / Places) that exist nowhere else.
 *
 * Unknown states deliberately fall through to [Decision.ALLOW] — better to miss a block than
 * to trap the user bouncing around a screen we didn't recognise. This is a v1 from a single
 * capture and will want on-device tuning, exactly like the other apps did.
 */
object TikTokClassifier {

    enum class Decision {
        /** Leave it alone. */
        ALLOW,
        /** A video feed (Home / Friends) — send the user to the Inbox tab. */
        REDIRECT_INBOX,
        /** A single video opened on top — press Back to return where they were. */
        GO_BACK
    }

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30

    private val BOTTOM_TABS = listOf("home", "friends", "inbox", "profile")
    private val REDIRECT_TABS = listOf("home", "friends")
    // Sub-tabs that only exist on the search page, so seeing one means search is open.
    private val SEARCH_MARKERS = listOf("hashtags", "sounds", "places")

    fun classify(root: AccessibilityNodeInfo?, windowClassName: CharSequence?): Decision {
        if (root == null) return Decision.ALLOW
        val cls = windowClassName?.toString()?.lowercase().orEmpty()

        // Full-screen viewers, identified by their activity class regardless of tree contents.
        if (cls.contains("detailactivity") && !cls.contains("postmode")) return Decision.GO_BACK
        if (cls.contains("postmodedetail") || cls.contains("tiktokhost")) return Decision.ALLOW

        // Otherwise we're in the main tabbed app: decide by the selected bottom tab, but let
        // the search page through wherever it's open.
        var selectedTab: String? = null
        var searchOpen = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (desc.isNotEmpty()) {
                if (!searchOpen && SEARCH_MARKERS.contains(desc)) searchOpen = true
                if (selectedTab == null && node.isSelected && BOTTOM_TABS.contains(desc)) {
                    selectedTab = desc
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        if (searchOpen) return Decision.ALLOW
        return if (selectedTab != null && REDIRECT_TABS.contains(selectedTab)) {
            Decision.REDIRECT_INBOX
        } else {
            Decision.ALLOW
        }
    }
}
