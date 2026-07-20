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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkForReels(now)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastContentCheckMs >= CONTENT_CHECK_THROTTLE_MS) {
                    lastContentCheckMs = now
                    checkForReels(now)
                }
            }
        }
    }

    private fun checkForReels(now: Long) {
        val root = rootInActiveWindow ?: return
        val result = try {
            ReelsDetector.evaluate(root)
        } catch (t: Throwable) {
            // Never let a malformed node tree crash the accessibility service — that
            // would silently disable blocking until the user re-enables it manually.
            Log.w(TAG, "Detection failed", t)
            return
        }

        if (result.isReels) {
            Log.d(TAG, "Reels detected, signals=${result.matchedSignals}")
            suppressUntilMs = now + BLOCK_SUPPRESSION_MS
            launchBlockScreen()
            scope.launch { repository.recordBlock() }
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
    }

    private companion object {
        const val TAG = "ReelsAccessibility"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val CONTENT_CHECK_THROTTLE_MS = 400L
        const val BLOCK_SUPPRESSION_MS = 1500L
    }
}
