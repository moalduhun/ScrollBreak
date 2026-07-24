package com.moalduhun.scrollbreak.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.moalduhun.scrollbreak.data.BlockerRepository
import com.moalduhun.scrollbreak.ui.block.BlockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches Instagram's window for the Reels player and blocks it the moment it appears.
 *
 * Content-changed events fire constantly while scrolling a normal feed, so re-running the
 * (cheap but non-free) tree walk on every single one would burn CPU for no benefit — it is
 * throttled to roughly the human perception threshold instead. Window-state-change events
 * (an actual screen/tab switch) are rare and always checked immediately, since that is the
 * moment Reels is most likely to have just opened.
 */
class ReelsAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: BlockerRepository

    @Volatile private var blockingEnabled = true
    // Per-app coverage the user picks on the Home screen — an app is only checked/blocked
    // while its flag is on. Kept as plain volatiles updated from the repository flows, the
    // same way blockingEnabled is, so onAccessibilityEvent can read them without suspending.
    @Volatile private var coverInstagram = true
    @Volatile private var coverYouTube = true
    @Volatile private var lastContentCheckMs = 0L
    @Volatile private var suppressUntilMs = 0L

    // Set whenever the user's last tap landed on Explore/Search's content grid (not the
    // search bar or the bottom nav). Used only as one extra, opt-in signal for the
    // Explore case — see ReelsDetector's recentContentTap parameter.
    @Volatile private var lastContentTapMs = 0L

    // Remembers which bottom tab (home/search and explore/reels/message/profile) was
    // last visibly selected, even after that tab bar disappears behind a full-screen
    // video — which is exactly when it's needed. Read from ReelsDetector.evaluate's
    // own result and fed back into the next call, since the detector itself is stateless.
    @Volatile private var lastKnownTab: String? = null

    // While true, every check is skipped — set the instant "Go back" is pressed and only
    // cleared once we've actually confirmed the Reels screen is gone, not after a fixed
    // delay. Without this, a check could fire mid-transition (e.g. between two attempts
    // at finding the Home tab), still see the Reels player, and immediately re-block.
    @Volatile private var navigatingHome = false

    // Which app's short-form content triggered the most recent block. The "Go back" flow
    // reads this to leave the right way: Instagram gets the Home-tab navigation below,
    // YouTube just gets repeated back presses until the Short is gone.
    @Volatile private var blockedPackage: String = INSTAGRAM_PACKAGE

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = BlockerRepository(applicationContext)
        scope.launch {
            repository.isBlockingEnabled.collect { enabled -> blockingEnabled = enabled }
        }
        scope.launch {
            repository.coverInstagram.collect { enabled -> coverInstagram = enabled }
        }
        scope.launch {
            repository.coverYouTube.collect { enabled -> coverYouTube = enabled }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // TEMPORARY: logs every event on the whole phone, regardless of package, to find
        // out why Explore detection isn't firing — see accessibility_service_config.xml.
        // Everything below this line is unchanged and still Instagram-only.
        logRawEvent(event)

        if (!blockingEnabled) return
        if (navigatingHome) return
        val pkg = event.packageName?.toString()
        if (pkg != INSTAGRAM_PACKAGE && pkg != YOUTUBE_PACKAGE) return
        // Respect the user's per-app choice: skip an app entirely while its coverage is off.
        if (pkg == INSTAGRAM_PACKAGE && !coverInstagram) return
        if (pkg == YOUTUBE_PACKAGE && !coverYouTube) return

        val now = System.currentTimeMillis()
        if (now < suppressUntilMs) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkForShortForm(now, event, pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastContentCheckMs >= CONTENT_CHECK_THROTTLE_MS) {
                    lastContentCheckMs = now
                    checkForShortForm(now, event, pkg)
                }
            }
            // A content-grid tap is only a signal for Instagram's Explore path.
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (pkg == INSTAGRAM_PACKAGE) recordContentTap(event)
        }
    }

    /**
     * Raw, unfiltered dump of every accessibility event on the device — package, event
     * type, source class, text, content-description. Use `adb logcat -s
     * ScrollBreakDiag:V | grep RAW` to follow just this while reproducing the Explore
     * issue; the rest of ScrollBreakDiag's usual Instagram-only logging still interleaves
     * with it in the same stream.
     */
    private fun logRawEvent(event: AccessibilityEvent) {
        val pkg = event.packageName ?: "?"
        val eventName = AccessibilityEvent.eventTypeToString(event.eventType)
        val sourceClass = event.className ?: "?"
        val text = event.text?.joinToString(separator = "|").orEmpty().take(60)
        val desc = event.contentDescription?.toString().orEmpty().take(60)
        Log.d(DIAG_TAG, "RAW pkg=$pkg event=$eventName class=$sourceClass text=\"$text\" desc=\"$desc\"")
    }

    /**
     * Explore/Search never raises a reliable window/tab signal when a Reel is opened
     * from it, so this uses where the user actually tapped as an extra clue instead: a
     * tap on the content grid (not the search bar up top, not the bottom nav) is a
     * reasonable sign something is about to open. It isn't trusted alone — a tap also
     * opens ordinary photo posts — [ReelsDetector] only uses it together with an actual
     * full-screen video appearing afterward.
     */
    private fun recordContentTap(event: AccessibilityEvent) {
        val source = event.source ?: return
        val windowBounds = Rect().also { rootInActiveWindow?.getBoundsInScreen(it) }
        if (windowBounds.height() <= 0) return
        val tapBounds = Rect().also { source.getBoundsInScreen(it) }
        val relativeY = (tapBounds.centerY() - windowBounds.top).toFloat() / windowBounds.height()
        if (relativeY in CONTENT_ZONE_TOP..CONTENT_ZONE_BOTTOM) {
            lastContentTapMs = System.currentTimeMillis()
        }
    }

    /**
     * Runs the right detector for whichever app the event came from — [ReelsDetector] for
     * Instagram, [YouTubeShortsDetector] for YouTube — and blocks if it fires. The two paths
     * are kept fully separate so YouTube support can't affect the confirmed-working
     * Instagram detection.
     */
    private fun checkForShortForm(now: Long, event: AccessibilityEvent, pkg: String) {
        val root = rootInActiveWindow ?: return

        val isBlocked: Boolean
        val signals: List<String>

        if (pkg == YOUTUBE_PACKAGE) {
            val result = try {
                YouTubeShortsDetector.evaluate(root)
            } catch (t: Throwable) {
                Log.w(DIAG_TAG, "YouTube detection failed", t)
                return
            }
            logYouTubeDiagnostics(event, result)
            isBlocked = result.isShorts
            signals = result.matchedSignals
        } else {
            val recentContentTap = now - lastContentTapMs < CONTENT_TAP_WINDOW_MS
            val result = try {
                ReelsDetector.evaluate(root, event.className, recentContentTap, lastKnownTab)
            } catch (t: Throwable) {
                // Never let a malformed node tree crash the accessibility service — that
                // would silently disable blocking until the user re-enables it manually.
                Log.w(DIAG_TAG, "Detection failed", t)
                return
            }
            if (result.detectedTabLabel != null) {
                lastKnownTab = result.detectedTabLabel
            }
            logDiagnostics(event, result)
            isBlocked = result.isReels
            signals = result.matchedSignals
        }

        if (isBlocked) {
            Log.d(DIAG_TAG, ">>> BLOCKING pkg=$pkg signals=$signals")
            blockedPackage = pkg
            suppressUntilMs = now + BLOCK_SUPPRESSION_MS
            launchBlockScreen()
            // Covering the app with the block screen pushes it to the background, which is
            // exactly when it spawns a Picture-in-Picture mini-player that keeps the video
            // playing in its own floating window. Chase it down and close it.
            schedulePipDismissal()
            scope.launch { repository.recordBlock() }
        }
    }

    /**
     * PiP doesn't appear instantly — the app enters it as it's being backgrounded, a moment
     * after the block screen launches — so this fires a few dismissal passes over the next
     * ~1.5s instead of a single one that might run too early.
     */
    private fun schedulePipDismissal() {
        for (delay in PIP_DISMISS_DELAYS_MS) {
            mainHandler.postDelayed({ dismissPictureInPicture() }, delay)
        }
    }

    /**
     * Finds a blocked app's Picture-in-Picture mini-player and closes it.
     *
     * Confirmed on a real Samsung capture: the PiP is a small application window belonging to
     * the app (YouTube), but its "Close" affordance is NOT inside that window — it lives in a
     * separate `com.android.systemui` overlay ("Open in split screen view | Expand | Close |
     * Settings"), and [AccessibilityNodeInfo.ACTION_DISMISS] on the app window is rejected.
     * So this first locates the PiP window (to confirm PiP is up and get its position), then
     * searches EVERY window — including the system overlay — for either a working dismiss
     * action or a clickable "Close" control sitting over the PiP.
     */
    private fun dismissPictureInPicture() {
        val activeWindows = try {
            windows
        } catch (t: Throwable) {
            return
        }
        val metrics = resources.displayMetrics
        val maxPipWidth = (metrics.widthPixels * 0.7f).toInt()
        val maxPipHeight = (metrics.heightPixels * 0.7f).toInt()

        var pipBounds: Rect? = null
        for (window in activeWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val pkg = root.packageName?.toString()
            if (pkg != INSTAGRAM_PACKAGE && pkg != YOUTUBE_PACKAGE) continue

            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.width() in 1 until maxPipWidth && bounds.height() in 1 until maxPipHeight) {
                pipBounds = bounds
                // Some devices do accept dismiss on the app window — try it before looking
                // for the system overlay's Close button.
                if (tryDismissTree(root)) {
                    Log.d(DIAG_TAG, "PiP dismissed via app window")
                    return
                }
            }
        }

        val pip = pipBounds ?: return
        Log.d(DIAG_TAG, "PiP present at $pip — searching all windows for a close control")

        // The close control usually overlays the PiP; only accept one that sits over it so we
        // can't accidentally click an unrelated "Close" elsewhere on screen.
        val searchArea = Rect(pip).apply { inset(-160, -160) }
        for (window in activeWindows) {
            val root = window.root ?: continue
            if (tryDismissTree(root)) {
                Log.d(DIAG_TAG, "PiP dismissed via ${root.packageName}")
                return
            }
            val closer = findCloseControlOver(root, searchArea)
            if (closer != null) {
                val ok = closer.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(DIAG_TAG, "Clicked PiP close in ${root.packageName} ok=$ok")
                if (ok) return
            }
        }
        Log.d(DIAG_TAG, "PiP still up after this pass")
    }

    /** Tries [AccessibilityNodeInfo.ACTION_DISMISS] on every node; true if any accepts it. */
    private fun tryDismissTree(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 250) {
            val node = queue.removeFirst()
            visited++
            if (node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /** Finds a clickable "Close" node whose bounds fall within [area] (over the PiP). */
    private fun findCloseControlOver(root: AccessibilityNodeInfo, area: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            val looksLikeClose = desc.contains("close") || text == "close"
            if (looksLikeClose) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (Rect.intersects(area, bounds)) {
                    // The labelled node isn't always the clickable one — the tappable target
                    // is often an ancestor, so climb to the nearest clickable one.
                    var candidate: AccessibilityNodeInfo? = node
                    while (candidate != null && !candidate.isClickable) {
                        candidate = candidate.parent
                    }
                    return candidate ?: node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun logYouTubeDiagnostics(event: AccessibilityEvent, result: YouTubeShortsDetector.DetectionResult) {
        val eventName = AccessibilityEvent.eventTypeToString(event.eventType)
        val windowClass = event.className ?: "unknown"
        Log.d(DIAG_TAG, "--- YT check event=$eventName windowClass=$windowClass isShorts=${result.isShorts} ---")
        if (result.matchedSignals.isNotEmpty()) {
            Log.d(DIAG_TAG, "YT matched=${result.matchedSignals}")
        }
        if (result.diagnostics.isEmpty()) {
            Log.d(DIAG_TAG, "no shorts/reel-flavoured nodes on this YouTube screen")
        } else {
            result.diagnostics.forEach { Log.d(DIAG_TAG, "YT $it") }
        }
    }

    /**
     * Prints exactly what the detector saw on this screen, so real Instagram behaviour can
     * be captured with `adb logcat -s ScrollBreakDiag:V` and used to replace guessed
     * keywords/thresholds with real ones. Safe to leave on: it only runs at the same
     * throttled rate as detection itself, and every value here already came from the
     * accessibility tree Instagram exposes to any accessibility service.
     */
    private fun logDiagnostics(event: AccessibilityEvent, result: ReelsDetector.DetectionResult) {
        val eventName = AccessibilityEvent.eventTypeToString(event.eventType)
        val windowClass = event.className ?: "unknown"
        Log.d(
            DIAG_TAG,
            "--- check event=$eventName windowClass=$windowClass eventWindowId=${event.windowId} " +
                "rootWindowId=${rootInActiveWindow?.windowId} lastKnownTab=$lastKnownTab isReels=${result.isReels} ---"
        )
        // Testing whether Instagram is keeping more than one window/page alive at once —
        // if a stale Reels window is still listed here after leaving it, that would
        // explain the detector still seeing "Reels" content behind the current screen.
        logActiveWindows()
        if (result.matchedSignals.isNotEmpty()) {
            Log.d(DIAG_TAG, "matched=${result.matchedSignals}")
        }
        if (result.diagnostics.isEmpty()) {
            Log.d(DIAG_TAG, "no clips/reel-flavoured nodes on this screen")
        } else {
            result.diagnostics.forEach { Log.d(DIAG_TAG, it) }
        }
    }

    private fun logActiveWindows() {
        val activeWindows = try {
            windows
        } catch (t: Throwable) {
            Log.w(DIAG_TAG, "Could not read windows()", t)
            return
        }
        Log.d(DIAG_TAG, "windows(${activeWindows.size}):")
        for (window in activeWindows) {
            Log.d(
                DIAG_TAG,
                "  id=${window.id} title=${window.title} type=${window.type} " +
                    "active=${window.isActive} focused=${window.isFocused}"
            )
        }
    }

    private fun launchBlockScreen() {
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        startActivity(intent)
    }

    /**
     * Leaves a blocked Reel and lands back in Instagram, checks paused the whole time
     * ([navigatingHome]) so a check firing mid-transition can't see a stale Reels screen
     * and re-block before this finishes.
     *
     * Tries a plain back press first: a Reel opened from Home, a DM, Search, or a
     * profile has a real destination behind it, so back alone naturally returns to
     * exactly that screen — faster and more correct than forcing everything to Home.
     * Only if a check afterwards confirms Reels is still showing (e.g. this was the
     * Reels tab itself, which can have nothing to back into) does this fall back to
     * finding and tapping Instagram's own Home tab icon.
     *
     * That Home tab lookup used to search for a resource-id named "tab_icon", but real
     * captures from this exact device show Instagram's element names come through
     * blank — so it silently failed every time. With no id or content-description to
     * rely on, it's found by position and behaviour instead: the left-most *clickable*
     * element sitting in the bottom navigation row.
     */
    private fun navigateToInstagramHome() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        attemptHomeNavigationStep(HOME_CLICK_MAX_ATTEMPTS, alreadyClicked = false)
    }

    /**
     * Leaving a blocked YouTube Short is simpler than Instagram: a Short always has a real
     * destination behind it (the Shorts feed, Home, Subscriptions, wherever it was opened
     * from), so a plain back press exits it. This just presses back and, if a check still
     * sees the Shorts player, presses again — no icon hunting needed. Checks stay paused
     * ([navigatingHome]) the whole time so a mid-transition check can't re-block.
     */
    private fun navigateAwayFromYouTubeShorts() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        attemptLeaveYouTubeShortsStep(HOME_CLICK_MAX_ATTEMPTS)
    }

    private fun attemptLeaveYouTubeShortsStep(attemptsLeft: Int) {
        mainHandler.postDelayed({
            val root = rootInActiveWindow
            val stillOnShorts = root != null && try {
                YouTubeShortsDetector.evaluate(root).isShorts
            } catch (t: Throwable) {
                Log.w(DIAG_TAG, "YouTube detection failed during leave", t)
                false
            }

            if (!stillOnShorts) {
                navigatingHome = false
                return@postDelayed
            }

            if (attemptsLeft <= 0) {
                // Out of attempts — resume checks so blocking can never get stuck disabled.
                navigatingHome = false
                return@postDelayed
            }

            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            attemptLeaveYouTubeShortsStep(attemptsLeft - 1)
        }, HOME_CLICK_RETRY_DELAY_MS)
    }

    /**
     * [alreadyClicked] stops this from tapping the Home icon again on every retry once one
     * click has actually landed — Instagram just hadn't finished reacting to it yet on the
     * next check. Without this, a slow transition looked like the click failed and got
     * re-sent, landing as an unwanted double-tap on the Home tab.
     */
    private fun attemptHomeNavigationStep(attemptsLeft: Int, alreadyClicked: Boolean) {
        mainHandler.postDelayed({
            val root = rootInActiveWindow
            val stillOnReels = root != null && try {
                ReelsDetector.evaluate(root).isReels
            } catch (t: Throwable) {
                Log.w(DIAG_TAG, "Detection failed during home navigation", t)
                false
            }

            if (!stillOnReels) {
                // Confirmed: the Reels player is gone. Safe to resume normal checking.
                navigatingHome = false
                return@postDelayed
            }

            if (attemptsLeft <= 0) {
                // Out of attempts — try a plain back press as a last resort, then give up
                // holding the checks off so blocking can never be stuck disabled.
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                mainHandler.postDelayed({ navigatingHome = false }, HOME_CLICK_RETRY_DELAY_MS)
                return@postDelayed
            }

            val didClickThisStep = if (!alreadyClicked) {
                root?.let { findHomeTabIcon(it) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } else {
                true
            }
            attemptHomeNavigationStep(attemptsLeft - 1, alreadyClicked = didClickThisStep)
        }, HOME_CLICK_RETRY_DELAY_MS)
    }

    private fun findHomeTabIcon(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        if (windowBounds.height() <= 0) return null

        var best: AccessibilityNodeInfo? = null
        var bestLeft = Int.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nodesVisited = 0

        while (queue.isNotEmpty() && nodesVisited < 400) {
            val node = queue.removeFirst()
            nodesVisited++

            if (node.isClickable && node.isVisibleToUser) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val relativeTop = (bounds.top - windowBounds.top).toFloat() / windowBounds.height()
                val isIconSized = bounds.width() in 1..250 && bounds.height() in 1..250
                // Only a small, clickable element sitting in the bottom navigation row
                // counts — this rules out the row's own container (also clickable, but
                // spans the full row) and anything higher up the screen.
                if (relativeTop >= 0.8f && isIconSized && bounds.left < bestLeft) {
                    bestLeft = bounds.left
                    best = node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    override fun onInterrupt() {
        // Required override; nothing to clean up.
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        job.cancel()
        if (instance === this) instance = null
    }

    companion object {
        private const val DIAG_TAG = "ScrollBreakDiag"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        // A tap counts as "on the content grid" only in this vertical band — above it is
        // the search bar/header/category tabs, below it is the bottom nav row. Matches
        // the real search bar position confirmed from a capture (relative top ~17-22%)
        // and the bottom nav row (relative top ~94-97%), with margin on both sides.
        private const val CONTENT_ZONE_TOP = 0.28f
        private const val CONTENT_ZONE_BOTTOM = 0.90f

        // How long a content-grid tap stays "recent" enough to count toward Explore
        // detection — long enough to cover the tap-to-full-screen transition.
        private const val CONTENT_TAP_WINDOW_MS = 2_500L

        // Kept low so a Reel is caught the moment it starts rendering rather than up to
        // 400ms later — a real screen switch (TYPE_WINDOW_STATE_CHANGED) is already
        // checked with no throttle at all; this only limits how often content-changed
        // events (which fire constantly while scrolling a normal feed) get re-checked.
        private const val CONTENT_CHECK_THROTTLE_MS = 120L
        private const val BLOCK_SUPPRESSION_MS = 1500L

        // A PiP mini-player appears a beat after the block screen backgrounds the app — a
        // real capture showed it landing ~600ms later and the system's Close overlay a moment
        // after that — so dismissal is retried across this spread rather than fired once.
        // Later passes run after the block-suppression window, which is fine: they only read
        // windows and click Close, they don't re-run detection.
        private val PIP_DISMISS_DELAYS_MS = longArrayOf(400L, 800L, 1200L, 1800L, 2500L)

        // How long to wait after BlockActivity finishes before navigating Instagram.
        // Sending the action immediately can hit BlockActivity's own (already-finishing)
        // window instead of Instagram's, which is why this used to sometimes just close
        // the block screen without Instagram ever navigating anywhere. Kept as short as
        // that race allows.
        private const val NAVIGATE_HOME_DELAY_MS = 120L

        // How many times to retry finding the Home tab icon, and how long to wait between
        // attempts, while the app finishes transitioning out of the full-screen player
        // (during which the bottom nav isn't in the accessibility tree yet).
        private const val HOME_CLICK_MAX_ATTEMPTS = 6
        private const val HOME_CLICK_RETRY_DELAY_MS = 100L

        // Absolute cap on how long checks stay paused for, no matter what happens during
        // navigation — guarantees blocking always comes back even if something unexpected
        // goes wrong (e.g. the app is backgrounded mid-navigation).
        private const val NAVIGATE_HOME_TIMEOUT_MS = 6_000L

        @Volatile private var instance: ReelsAccessibilityService? = null

        /**
         * Leaves whichever blocked short-form screen is showing and returns to normal use —
         * Instagram's own Home feed for a Reel, a plain back-out for a YouTube Short. Only
         * the accessibility service can drive the other app's window; [BlockActivity] has no
         * way to do it itself, so it calls through this singleton reference.
         *
         * Detection is paused ([navigatingHome]) starting the instant this is called, not
         * just while the action itself is happening — a check firing in between attempts
         * would still see the player and re-block before navigation finishes. It is only
         * resumed once the player is confirmed gone, or the timeout below fires.
         */
        fun leaveBlockedApp() {
            val service = instance ?: return
            service.navigatingHome = true
            service.mainHandler.postDelayed({
                if (service.blockedPackage == YOUTUBE_PACKAGE) {
                    service.navigateAwayFromYouTubeShorts()
                } else {
                    service.navigateToInstagramHome()
                }
            }, NAVIGATE_HOME_DELAY_MS)
            service.mainHandler.postDelayed({
                service.navigatingHome = false
            }, NAVIGATE_HOME_TIMEOUT_MS)
        }
    }
}
