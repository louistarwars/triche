package fr.triche.piano

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Service d'accessibilité : sert uniquement à injecter des touchers sur l'écran. */
class TapService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: TapService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Un toucher le plus bref possible (1 ms). [done] est appelé quand le système a fini d'injecter le geste. */
    fun tap(x: Float, y: Float, done: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        val cb = if (done == null) null else object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }
        return dispatchGesture(gesture, cb, null)
    }

    /**
     * Plusieurs touchers de 1 ms dans un même geste, chacun à son heure (début en ms, jamais deux doigts à la fois).
     * [done] reçoit true si le geste est allé au bout.
     */
    fun tapSeq(points: List<Triple<Float, Float, Long>>, done: (Boolean) -> Unit): Boolean {
        val b = GestureDescription.Builder()
        for ((x, y, start) in points) {
            val path = Path().apply { moveTo(x, y) }
            b.addStroke(GestureDescription.StrokeDescription(path, start, 1))
        }
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }
        return dispatchGesture(b.build(), cb, null)
    }
}
