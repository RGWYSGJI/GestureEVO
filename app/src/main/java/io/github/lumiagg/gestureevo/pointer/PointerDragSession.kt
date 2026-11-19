package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.gesture.GestureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    val isActive: Boolean
        get() = active

    val isBusy: Boolean
        get() = busy || active

    /**
     * Starts the initial long-press hold. Pointer updates arriving during the initial 600 ms are
     * coalesced into [latestX]/[latestY] and applied as soon as the hold is established.
     */
    fun start(x: Float, y: Float) {
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
            finish()
            return
        }

        scope.launch {
            val accepted = try {
                service.beginPointerDrag(x, y, INITIAL_HOLD_MS)
            } catch (_: Throwable) {
                false
            }
            starting = false

            if (!accepted) {
                finish()
                return@launch
            }

            if (!active) {
                // The user ended the session while the initial hold was still being dispatched.
                try {
                    service.endPointerDrag()
                } catch (_: Throwable) {
                    // The gesture may already have been cancelled by the target.
                }
                finish()
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
            mutex.withLock {
                try {
                    service.endPointerDrag()
                } catch (_: Throwable) {
                    // The gesture may already have been cancelled by the target.
                }
            }
            finish()
        }
    }

    private suspend fun moveLoop() {
        while (active && scope.isActive) {
            val targetX = latestX
            val targetY = latestY
            if (abs(targetX - lastX) > MOVE_EPSILON || abs(targetY - lastY) > MOVE_EPSILON) {
                val accepted = mutex.withLock {
                    if (!active) {
                        false
                    } else {
                        try {
                            service.continuePointerDrag(targetX, targetY, MOVE_DURATION_MS)
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }
                if (!accepted) {
                    end()
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
            val accepted = mutex.withLock {
                if (!active) {
                    false
                } else {
                    try {
                        service.continuePointerDrag(lastX, lastY, HEARTBEAT_DURATION_MS)
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
            if (!accepted) {
                end()
                return
            }
        }
    }

    @Synchronized
    private fun finish() {
        if (finished) return
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
        const val INITIAL_HOLD_MS = 600L
        const val MOVE_INTERVAL_MS = 16L
        const val MOVE_DURATION_MS = 16L
        const val HEARTBEAT_INTERVAL_MS = 700L
        const val HEARTBEAT_DURATION_MS = 1L
        const val MOVE_EPSILON = 0.5f
    }
}
