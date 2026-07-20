package com.moalduhun.scrollbreak.util

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.moalduhun.scrollbreak.service.ReelsAccessibilityService

/**
 * There is no direct API to ask "is my AccessibilityService currently enabled" —
 * the documented way is to parse the colon-separated service list Android stores
 * in this Settings.Secure key and look for our service's component name in it.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent = "${context.packageName}/${ReelsAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
    }
    return false
}

fun isInstagramInstalled(context: Context): Boolean = try {
    context.packageManager.getPackageInfo("com.instagram.android", 0)
    true
} catch (_: Exception) {
    false
}
