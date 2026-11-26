package io.github.lumiagg.gestureevo.gesture

import android.graphics.Rect
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.search.InjectionTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FreeTriggerEvent(
    val isConfig: Boolean,
    val configTrigger: FreeTriggerConfig? = null,
    val orientation: Int? = null,
    val isEnable: Boolean? = null,
    val reloadWheelTag: String? = null,
)

data class ActionViewEvent(
    val show: Boolean,
    val workMode: ActionView.WorkMode? = null,
    val mainItemListTag: String? = null,
    val subItemListTag: String? = null,
    val triggerConfig: FreeTriggerConfig? = null,
    val isMirror: Boolean = false,
)

data class KeyboardEvent(
    val show: Boolean,
    val reloadKeyboard: Boolean? = null,
    val side: String? = null,
)

data class InspireEvent(
    val rect: Rect? = null,
    val reSelect: Boolean? = null,
    val image: ManagedBitmap? = null,
    val text: String? = null,
    val skipAutoPin: Boolean = false,
    val fullscreen: Boolean = false,
    val forceImageTextSelection: Boolean = false,
    val side: String? = null,
)

data class PointerEvent(
    val enable: Boolean,
    val needShowTouchView: Boolean = false,
    val side: String? = null,
    val mouseMode: Boolean = false,
)

data class AccessibilityActionEvent(
    val action: Int,
)

data class NotificationActionEvent(val isAdd: Boolean)

data class SearchEvent(val task: InjectionTask)

data class PinPoolEvent(val show: Boolean, val side: String? = null)

data class ScreenSearchEvent(val show: Boolean = true, val side: String? = null)

object EventBusManager {
    val actionViewEventBus = MutableSharedFlow<ActionViewEvent>()
    val freeTriggerEventBus = MutableSharedFlow<FreeTriggerEvent>()
    val keyboardEventBus = MutableSharedFlow<KeyboardEvent>()
    val accessibilityActionEventBus = MutableSharedFlow<AccessibilityActionEvent>()
    val inspireEventBus = MutableSharedFlow<InspireEvent>()
    val pointerEventBus = MutableSharedFlow<PointerEvent>()
    val notificationActionEventBus = MutableSharedFlow<NotificationActionEvent>()
    val searchEventBus = MutableSharedFlow<SearchEvent>()
    val pinPoolEventBus = MutableSharedFlow<PinPoolEvent>()
    val screenSearchEventBus = MutableSharedFlow<ScreenSearchEvent>()

    fun produceActionViewEvent(lifecycleScope: CoroutineScope, event: ActionViewEvent) =
        lifecycleScope.launch {
            actionViewEventBus.emit(event)
        }

    suspend fun produceActionViewEvent(event: ActionViewEvent) =
        actionViewEventBus.emitOnIo(event)

    suspend fun produceKeyboardEvent(event: KeyboardEvent) =
        keyboardEventBus.emitOnIo(event)

    suspend fun produceAccessibilityActionEvent(
        action: AccessibilityActionEvent,
    ) {
        accessibilityActionEventBus.emit(action)
    }

    suspend fun produceInspireEvent(event: InspireEvent) =
        inspireEventBus.emitOnIo(event)

    suspend fun producePointerEvent(event: PointerEvent) =
        pointerEventBus.emitOnIo(event)

    suspend fun produceNotificationActionEvent(event: NotificationActionEvent) =
        notificationActionEventBus.emitOnIo(event)

    suspend fun produceFreeTriggerEvent(event: FreeTriggerEvent) =
        freeTriggerEventBus.emitOnIo(event)

    suspend fun produceSearchEvent(event: SearchEvent) =
        searchEventBus.emitOnIo(event)

    suspend fun producePinPoolEvent(event: PinPoolEvent) =
        pinPoolEventBus.emitOnIo(event)

    suspend fun produceScreenSearchEvent(event: ScreenSearchEvent) =
        screenSearchEventBus.emitOnIo(event)

    private suspend fun <T> MutableSharedFlow<T>.emitOnIo(event: T) =
        withContext(Dispatchers.IO) { emit(event) }
}
