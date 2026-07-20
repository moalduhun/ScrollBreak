package com.moalduhun.scrollbreak

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.moalduhun.scrollbreak.ui.home.HomeRoute
import com.moalduhun.scrollbreak.ui.onboarding.PermissionScreen
import com.moalduhun.scrollbreak.ui.theme.ScrollBreakTheme
import com.moalduhun.scrollbreak.util.isAccessibilityServiceEnabled

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScrollBreakTheme {
                ScrollBreakApp()
            }
        }
    }
}

@Composable
private fun ScrollBreakApp() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // The user grants this permission in system Settings, outside our activity, so the
    // only reliable moment to notice it changed is when this screen resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Crossfade(targetState = permissionGranted, label = "permissionCrossfade") { granted ->
        if (granted) {
            HomeRoute()
        } else {
            PermissionScreen(
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
    }
}
