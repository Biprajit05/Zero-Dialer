package com.example.quietdialer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing by itself. Android only lets the microphone work during a phone call for apps with
 * an enabled accessibility service, so enabling this is what makes call recording audible.
 */
class RecorderAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
