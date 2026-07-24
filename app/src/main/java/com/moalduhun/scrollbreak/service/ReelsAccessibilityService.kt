package com.moalduhun.scrollbreak.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.moalduhun.scrollbreak.data.BlockerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches Instagram and YouTube for the Reels / Shorts player and blocks it the moment it
 * appears by covering it with an overlay.
 *
 * Content-changed events fire constantly while scrolling a normal feed, so re-running the
 * (cheap but non-free) tree walk on every single one would burn CPU for no benefit — it is
 * throttled to roughly the human perception threshold instead. Window-state-change events
 * (an actual screen/tab switch) are rare and always checked immediately, since that is the
 * moment the player is most likely to have just opened.
 *
 * The block screen is drawn as an overlay window ([BlockOverlay]) rather than launched as a
 * separate Activity on purpose: an Activity would push the app to the background, which is
 * what makes it spawn a Picture-in-Picture mini-player that keeps playing. An overlay leaves
 * the app in the foreground, so nothing goes to PiP; the audio is muted underneath instead,
 * and "Go back" simply presses Back to return to the previous screen and unmutes.
 */
class ReelsAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: BlockerRepository
    private lateinit var blockOverlay: BlockOverlay

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

    // True while the block overlay is up. All detection is skipped meanwhile — the covered
    // app keeps generating events (it's still playing, just muted and hidden) and we don't
    // want to re-trigger on them.
    @Volatile private var overlayVisible = false

    // Which app's short-form content the overlay is currently covering, so a stray
    // window-switch to a different app can take the overlay down instead of stranding it.
    @Volatile private var blockedPackage: String = INSTAGRAM_PACKAGE

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = BlockerRepository(applicationContext)
        blockOverlay = BlockOverlay(this)
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
        // TEMPORARY: logs every event on the whole phone, regardless of package, to help tune
        // detection — see accessibility_service_config.xml.
        logRawEvent(event)

        if (overlayVisible) {
            // Safety net: if the user leaves the blocked app on their own (e.g. Home button
            // to the launcher, or switches apps) while the overlay is up, take it down so it
            // can't get stranded over something else. Ignore the app itself and the system UI.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val p = event.packageName?.toString()
                if (p != null && p != blockedPackage && p != SYSTEMUI_PACKAGE && p != packageName) {
                    hideOverlay(performBack = false)
                }
            }
            return
        }

        if (!blockingEnabled) return
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
     * type, source class, text, content-description. Use `adb logcat -s ScrollBreakDiag:V`
     * to follow it while reproducing an issue.
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
     * Instagram, [YouTubeShortsDetector] for YouTube — and shows the block overlay if it
     * fires. The two paths are kept fully separate so YouTube support can't affect the
     * confirmed-working Instagram detection.
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
            overlayVisible = true
            blockOverlay.show(onGoBack = ::handleGoBack)
            scope.launch { repository.recordBlock() }
        }
    }

    private fun handleGoBack() {
        hideOverlay(performBack = true)
    }

    /**
     * Takes the overlay down and unmutes. [performBack] presses Back so the app leaves the
     * reel/short and returns to the previous screen — used for the "Go back" button, but not
     * when the overlay is being cleared because the user already left the app.
     *
     * The Back press is delayed a moment: the overlay is a focusable window, so pressing Back
     * the instant it's removed raced the app regaining input focus and the press went nowhere
     * (this is why "Go back" wasn't working). Waiting for the window to actually come down
     * lets Back reach the app. A suppression window stops the reel still finishing its exit
     * transition from instantly re-triggering the block.
     */
    private fun hideOverlay(performBack: Boolean) {
        blockOverlay.hide()
        overlayVisible = false
        suppressUntilMs = System.currentTimeMillis() + BACK_SUPPRESSION_MS
        if (performBack) {
            mainHandler.postDelayed({
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                suppressUntilMs = System.currentTimeMillis() + BACK_SUPPRESSION_MS
            }, BACK_ACTION_DELAY_MS)
        }
    }

    /**
     * While the block overlay is up, swallow the hardware volume keys so the user can't turn
     * the muted reel/short back up to hear it. Everything else passes through untouched.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (overlayVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.onKeyEvent(event)
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
     * Prints exactly what the detector saw on this screen, so real behaviour can be captured
     * with `adb logcat -s ScrollBreakDiag:V` and used to tune the keywords/thresholds.
     */
    private fun logDiagnostics(event: AccessibilityEvent, result: ReelsDetector.DetectionResult) {
        val eventName = AccessibilityEvent.eventTypeToString(event.eventType)
        val windowClass = event.className ?: "unknown"
        Log.d(
            DIAG_TAG,
            "--- check event=$eventName windowClass=$windowClass eventWindowId=${event.windowId} " +
                "rootWindowId=${rootInActiveWindow?.windowId} lastKnownTab=$lastKnownTab isReels=${result.isReels} ---"
        )
        if (result.matchedSignals.isNotEmpty()) {
            Log.d(DIAG_TAG, "matched=${result.matchedSignals}")
        }
        if (result.diagnostics.isEmpty()) {
            Log.d(DIAG_TAG, "no clips/reel-flavoured nodes on this screen")
        } else {
            result.diagnostics.forEach { Log.d(DIAG_TAG, it) }
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to clean up.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Make sure we never leave the device muted or the overlay stranded if the service
        // is torn down while a block is showing.
        if (::blockOverlay.isInitialized) blockOverlay.hide()
        mainHandler.removeCallbacksAndMessages(null)
        job.cancel()
    }

    companion object {
        private const val DIAG_TAG = "ScrollBreakDiag"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        // A tap counts as "on the content grid" only in this vertical band — above it is
        // the search bar/header/category tabs, below it is the bottom nav row.
        private const val CONTENT_ZONE_TOP = 0.28f
        private const val CONTENT_ZONE_BOTTOM = 0.90f

        // How long a content-grid tap stays "recent" enough to count toward Explore
        // detection — long enough to cover the tap-to-full-screen transition.
        private const val CONTENT_TAP_WINDOW_MS = 2_500L

        // Limits how often content-changed events (which fire constantly while scrolling a
        // normal feed) get re-checked; window-state changes are always checked immediately.
        private const val CONTENT_CHECK_THROTTLE_MS = 120L

        // After "Go back", detection is paused briefly so the reel still finishing its exit
        // transition can't be seen and re-blocked.
        private const val BACK_SUPPRESSION_MS = 1_200L

        // Wait for the (focusable) overlay window to actually come down before pressing Back,
        // otherwise the press races window-focus handoff and is lost.
        private const val BACK_ACTION_DELAY_MS = 150L
    }
}
