package com.ghosttap.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * PIECE 2 (2026-11-18) — screen-see-and-tap.
 *
 * Ghost Tap's own tap control: an Android accessibility service that
 * can take a picture of the screen and tap where the brain points.
 * The service itself never decides anything — it only screenshots
 * and taps on the ViewModel's say-so, and the ViewModel only taps
 * where the thinking engine pointed. No key / no credit means no
 * tap, said plainly, never faked.
 *
 * The user turns this on once, in the phone's own accessibility
 * settings (the Visual Intel screen has one button that opens
 * exactly that page). Until then [instance] is null and every
 * see-and-tap attempt says so in plain words.
 */
class GhostTapAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ghost Tap never reacts to screen events on its own — it only
        // screenshots and taps when the user asks it to.
    }

    override fun onInterrupt() {
        // Nothing in flight to cancel.
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /**
     * A picture of the screen right now, as a plain software bitmap
     * the brain can be sent. Null when the phone can't do it
     * (needs Android 11 or newer) or the shot failed. The callback
     * runs on the main thread.
     */
    fun takeScreenshotNow(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val wrapped = runCatching {
                        Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                    }.getOrNull()
                    runCatching { screenshot.hardwareBuffer.close() }
                    // Copy off the hardware buffer so the bitmap is a
                    // plain one the brain call can compress anywhere.
                    val copy = wrapped?.let {
                        runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    }
                    if (wrapped != null && wrapped !== copy) {
                        runCatching { wrapped.recycle() }
                    }
                    onResult(copy)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            }
        )
    }

    /**
     * One tap at the given screen pixels. True when the phone took
     * the gesture; the tap landing on the right thing is the brain's
     * job, not this function's.
     */
    fun tapAt(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone(false)
                }
            },
            null
        )
    }

    companion object {
        /** The running service, or null when tap control is off. */
        var instance: GhostTapAccessibilityService? = null
            private set

        /** True when the user has switched Ghost Tap's tap control on. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, GhostTapAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it == component }
        }
    }
}
