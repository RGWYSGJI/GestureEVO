package io.github.lumiagg.gestureevo.pointer

import android.util.Log
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.gesture.GestureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Keeps one injected touch down after the initial long press and streams pointer movement as
 * continuation strokes.
 *
 * Accessibility gestures are immutable once dispatched, but a stroke created with
 * [android.accessibilityservice.GestureDescription.StrokeDescription]'s `willContinue` flag can
 * be extended with `continueStroke`. The initial 600 ms stroke is the long press itself; after
 * it completes the finger is still down, and every pointer update becomes a short MOVE
 * continuation. A final continuation with `willContinue = false` lifts the finger.
 */
class PointerDragSession(
    private val service: GestureService,
    private val scope: CoroutineScope,
    private val onStopped: () -> Unit,
) {
    private val mutex = Mutex()

    @Volatile
    private var active = false

    @Volatile
    private var busy = false

    @Volatile
    private var finished = true

    @Volatile
    private var starting = false

    private var latestX = 0f
    private var latestY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var startupJob: Job? = null

    val isActive: Boolean
        get() = active

    val isBusy: Boolean
        get() = busy || active

    /**
     * Starts the initial hold. [initialHoldMs] is the long-press duration for the long-press
     * button, or a very short hold for the immediate drag latch. Pointer updates arriving
     * during the initial hold are coalesced into [latestX]/[latestY] and applied as soon as
     * the hold is established.
     */
    fun start(x: Float, y: Float, initialHoldMs: Long = INITIAL_HOLD_MS) {
        if (busy || active) return
        active = true
        busy = true
        finished = false
        starting = true
        latestX = x
        latestY = y
        lastX = x
        lastY = y

        if (!scope.isActive) {
            finish("scope_inactive")
            return
        }

        startupJob = scope.launch {
            delay(START_TIMEOUT_MS)
            if (!starting) return@launch
            Log.d(TAG, "start timeout, resetting")
            starting = false
            active = false
            withTimeoutOrNull(300L) { endDragQuietly() }
            finish("start_timeout")
        }

        scope.launch {
            val accepted = try {
                service.beginPointerDrag(x, y, initialHoldMs)
            } catch (_: Throwable) {
                false
            }
            startupJob?.cancel()
            startupJob = null
            starting = false

            if (!accepted) {
                finish("start_rejected")
                return@launch
            }

            AppLogger.event(
                LogModule.OVERLAY, "pointer_drag_started", LogLevel.INFO,
                fields = mapOf("holdMs" to initialHoldMs),
            )

            if (!active) {
                // The user ended the session while the initial hold was still being dispatched.
                endDragQuietly()
                finish("ended_during_start")
                return@launch
            }

            moveJob = scope.launch { moveLoop() }
            heartbeatJob = scope.launch { heartbeatLoop() }
        }
    }

    /** Updates the target point; the move loop coalesces rapid touch-pad events. */
    fun moveTo(x: Float, y: Float) {
        if (!active) return
        latestX = x
        latestY = y
    }

    /** Lifts the injected finger. Safe to call repeatedly. */
    fun end() {
        if (finished) return
        // end() can be called from several paths (close, destroy, button, failure). Once the
        // session is no longer active, do not launch a second final lift.
        if (!active) return
        active = false
        // The initial hold cannot be continued before dispatchGesture reports completion.
        // The start coroutine sees active == false and ends it as soon as the hold callback
        // arrives.
        if (starting) return

        val move = moveJob
        val heartbeat = heartbeatJob
        moveJob = null
        heartbeatJob = null

        scope.launch {
            move?.cancelAndJoinQuietly()
            heartbeat?.cancelAndJoinQuietly()
            mutex.withLock { endDragQuietly() }
            finish("user_end")
        }
    }

    private suspend fun moveLoop() {
        while (active && scope.isActive) {
            val targetX = latestX
            val targetY = latestY
            if (abs(targetX - lastX) > MOVE_EPSILON || abs(targetY - lastY) > MOVE_EPSILON) {
                val accepted = continueDrag(targetX, targetY, MOVE_DURATION_MS)
                if (!accepted) {
                    finish("move_rejected")
                    return
                }
                lastX = targetX
                lastY = targetY
            }
            delay(MOVE_INTERVAL_MS)
        }
    }

    /**
     * A `willContinue` stroke stays down, but keeping a tiny continuation alive makes the
     * session robust across OEM gesture implementations that time out an otherwise idle stroke.
     */
    private suspend fun heartbeatLoop() {
        while (active && scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (!active) return
            val accepted = continueDrag(lastX, lastY, HEARTBEAT_DURATION_MS)
            if (!accepted) {
                finish("heartbeat_rejected")
                return
            }
        }
    }

    private suspend fun continueDrag(x: Float, y: Float, durationMs: Long): Boolean =
        mutex.withLock {
            if (!active) {
                false
            } else {
                try {
                    service.continuePointerDrag(x, y, durationMs)
                } catch (_: Throwable) {
                    false
                }
            }
        }

    private suspend fun endDragQuietly() {
        try {
            service.endPointerDrag()
        } catch (_: Throwable) {
            // The gesture may already have been cancelled by the target.
        }
    }

    @Synchronized
    private fun finish(reason: String = "unknown") {
        if (finished) return
        AppLogger.event(
            LogModule.OVERLAY, "pointer_drag_finished", LogLevel.INFO,
            fields = mapOf("reason" to reason, "active" to active, "busy" to busy),
        )
        Log.d(TAG, "finish: reason=$reason")
        startupJob?.cancel()
        startupJob = null
        finished = true
        starting = false
        busy = false
        active = false
        onStopped()
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        try {
            join()
        } catch (_: Throwable) {
            // The job may already be cancelled by the service scope.
        }
    }

    private companion object {
        const val TAG = "PointerDragSession"
        const val START_TIMEOUT_MS = 1_800L
        const val INITIAL_HOLD_MS = 600L
        const val MOVE_INTERVAL_MS = 16L
        const val MOVE_DURATION_MS = 16L
        const val HEARTBEAT_INTERVAL_MS = 700L
        const val HEARTBEAT_DURATION_MS = 1L
        const val MOVE_EPSILON = 0.5f
    }
}
