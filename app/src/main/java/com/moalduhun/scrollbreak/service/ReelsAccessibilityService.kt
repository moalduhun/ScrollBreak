package com.moalduhun.scrollbreak.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
    private lateinit var repository: BlockerRepository

    @Volatile private var blockingEnabled = true
    @Volatile private var lastContentCheckMs = 0L
    @Volatile private var suppressUntilMs = 0L

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
        Log.d(DIAG_TAG, "--- check event=$eventName windowClass=$windowClass isReels=${result.isReels} ---")
        if (result.matchedSignals.isNotEmpty()) {
            Log.d(DIAG_TAG, "matched=${result.matchedSignals}")
        }
        if (result.diagnostics.isEmpty()) {
            Log.d(DIAG_TAG, "no clips/reel-flavoured nodes on this screen")
        } else {
            result.diagnostics.forEach { Log.d(DIAG_TAG, it) }
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

    override fun onInterrupt() {
        // Required override; nothing to clean up.
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        if (instance === this) instance = null
    }

    companion object {
        private const val DIAG_TAG = "ScrollBreakDiag"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val CONTENT_CHECK_THROTTLE_MS = 400L
        private const val BLOCK_SUPPRESSION_MS = 1500L

        @Volatile private var instance: ReelsAccessibilityService? = null

        /**
         * Simulates the user pressing the system back button, so leaving a blocked Reels
         * screen feels the same as if the user had backed out of it themselves — instead of
         * dropping them out of Instagram entirely, which "go to home screen" used to do.
         * Only the accessibility service can perform this; [BlockActivity] has no way to
         * send a global action itself, so it calls through this singleton reference.
         */
        fun goBack(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }
}
