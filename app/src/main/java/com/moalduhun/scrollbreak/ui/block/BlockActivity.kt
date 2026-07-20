package com.moalduhun.scrollbreak.ui.block

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.moalduhun.scrollbreak.service.ReelsAccessibilityService
import com.moalduhun.scrollbreak.ui.theme.ScrollBreakTheme
import kotlinx.coroutines.delay

/**
 * Full-screen takeover launched by [com.moalduhun.scrollbreak.service.ReelsAccessibilityService]
 * the instant Reels is detected. Its whole job is to cover the Reels content immediately —
 * every extra composition step here is time the user spends looking at Reels instead.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScrollBreakTheme {
                BlockScreen(onGoBack = { goBack() })
            }
        }
    }

    // Labelled "Go back" in the UI, but it lands specifically on Instagram's own Home
    // feed rather than doing a plain back press — see goToInstagramHome() for why.
    private fun goBack() {
        ReelsAccessibilityService.goToInstagramHome()
        finish()
    }
}

private const val GO_BACK_COOLDOWN_SECONDS = 3

@Composable
private fun BlockScreen(onGoBack: () -> Unit) {
    // Swallow the Android system back button/gesture entirely so it can't dismiss this
    // screen and drop the user straight back onto the Reel. Leaving is only allowed via
    // the app's own "Go back" button below, which routes through goToInstagramHome()
    // instead of a plain back press. (Home and Recents are intentionally still available —
    // an accessibility service can't and shouldn't trap those.)
    BackHandler(enabled = true) { /* intentionally consume, do nothing */ }

    var animateIn by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.6f,
        animationSpec = tween(durationMillis = 420, easing = EaseOutBack),
        label = "iconScale"
    )
    LaunchedEffect(Unit) { animateIn = true }

    // A brief, visible cooldown before "Go back" can be tapped — mainly so it can't be
    // hit before the screen (and the accessibility service behind it) has actually
    // settled, which is what caused the earlier double-click/timing issues.
    var secondsRemaining by remember { mutableStateOf(GO_BACK_COOLDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1_000)
            secondsRemaining--
        }
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(scale)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(Modifier.height(28.dp))

                Text(
                    text = "ScrollBreak stopped Reels before it could pull you in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = onGoBack,
                    enabled = secondsRemaining <= 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (secondsRemaining > 0) {
                            "You can go back in ${secondsRemaining}s"
                        } else {
                            "Go back"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BlockScreenPreview() {
    ScrollBreakTheme {
        BlockScreen(onGoBack = {})
    }
}
