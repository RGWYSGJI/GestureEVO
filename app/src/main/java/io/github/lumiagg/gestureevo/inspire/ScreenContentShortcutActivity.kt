package io.github.lumiagg.gestureevo.inspire

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.gesture.EventBusManager
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import kotlinx.coroutines.launch

/**
 * Entry point for the "screen content" launcher shortcut.
 *
 * The actual screenshot/selection UI is owned by GestureService; this activity only
 * posts the same event that the gesture action uses and gets out of the way.
 */
class ScreenContentShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            EventBusManager.produceInspireEvent(
                InspireEvent(
                    fullscreen = true,
                    forceImageTextSelection = true,
                )
            )
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
