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
        }
    }

    private fun checkForReels(now: Long, event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val result = try {
            ReelsDetector.evaluate(root)
        } catch (t: Throwable) {
            // Never let a malformed node tree crash the accessibility service — that
            // would silently disable blocking until the user re-enables it manually.
            Log.w(DIAG_TAG, "Detection failed", t)
            return
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
                "rootWindowId=${rootInActiveWindow?.windowId} isReels=${result.isReels} ---"
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
     * Taps Instagram's own Home tab icon so leaving a blocked Reel lands specifically on
     * Instagram's home feed — not the device's home screen, and not a plain back press.
     *
     * This used to look for a resource-id named "tab_icon", but real captures from this
     * exact device show Instagram's element names are stripped to blank — so that lookup
     * silently failed on every single call, always falling through to a plain back press.
     * That explains two different symptoms: the button being unreliable (back doesn't
     * reliably land on Home — it can land on whatever was on the back stack, or do
     * nothing from the Reels tab), and it occasionally exiting the app outright (pressing
     * back from a screen with nothing behind it closes Instagram instead of navigating).
     *
     * With no id and no content-description to rely on, the Home tab is now found purely
     * by position and behaviour: the left-most *clickable* element sitting in the bottom
     * navigation row. Back is now only a last resort, tried once after several attempts
     * to find that icon have failed (e.g. while a Reel is still full-screen and the nav
     * bar isn't in the tree at all) — not the first thing this does.
     *
     * Every step here re-checks the detector first: if the Reels player is already gone,
     * this stops immediately and lets normal checking resume — it does not keep clicking
     * blindly, and it does not release [navigatingHome] until that is actually true (or a
     * hard timeout is hit, so a stuck loop can never disable blocking permanently).
     */
    private fun navigateToInstagramHome() {
        attemptHomeNavigationStep(HOME_CLICK_MAX_ATTEMPTS)
    }

    private fun attemptHomeNavigationStep(attemptsLeft: Int) {
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

            root?.let { findHomeTabIcon(it) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            attemptHomeNavigationStep(attemptsLeft - 1)
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
        private const val CONTENT_CHECK_THROTTLE_MS = 400L
        private const val BLOCK_SUPPRESSION_MS = 1500L

        // How long to wait after BlockActivity finishes before navigating Instagram.
        // Sending the action immediately can hit BlockActivity's own (already-finishing)
        // window instead of Instagram's, which is why this used to sometimes just close
        // the block screen without Instagram ever navigating anywhere.
        private const val NAVIGATE_HOME_DELAY_MS = 300L

        // How many times to retry finding the Home tab icon, and how long to wait between
        // attempts, while the app finishes transitioning out of the full-screen player
        // (during which the bottom nav isn't in the accessibility tree yet).
        private const val HOME_CLICK_MAX_ATTEMPTS = 5
        private const val HOME_CLICK_RETRY_DELAY_MS = 200L

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
