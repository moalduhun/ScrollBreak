package com.moalduhun.scrollbreak.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = BlockerRepository(applicationContext)
        scope.launch {
            repository.isBlockingEnabled.collect { enabled -> blockingEnabled = enabled }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!blockingEnabled) return
        if (navigatingHome) return
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now < suppressUntilMs) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkForReels(now, event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastContentCheckMs >= CONTENT_CHECK_THROTTLE_MS) {
                    lastContentCheckMs = now
                    checkForReels(now, event)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordContentTap(event)
        }
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

    private fun checkForReels(now: Long, event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
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

        if (result.isReels) {
            Log.d(DIAG_TAG, ">>> BLOCKING, signals=${result.matchedSignals}")
            suppressUntilMs = now + BLOCK_SUPPRESSION_MS
            launchBlockScreen()
            scope.launch { repository.recordBlock() }
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
         * Taps Instagram's Home tab so leaving a blocked Reels screen lands on Instagram's
         * own home feed. Only the accessibility service can do this; [BlockActivity] has
         * no way to interact with Instagram's window itself, so it calls through this
         * singleton reference.
         *
         * Detection is paused ([navigatingHome]) starting the instant this is called, not
         * just while the click itself is happening — a check firing in between attempts
         * would still see the Reels player and re-block before navigation finishes. It is
         * only resumed once the Reels player is confirmed gone, or the timeout below fires.
         */
        fun goToInstagramHome() {
            val service = instance ?: return
            service.navigatingHome = true
            service.mainHandler.postDelayed({
                service.navigateToInstagramHome()
            }, NAVIGATE_HOME_DELAY_MS)
            service.mainHandler.postDelayed({
                service.navigatingHome = false
            }, NAVIGATE_HOME_TIMEOUT_MS)
        }
    }
}
