package io.github.lumiagg.gestureevo.pointer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.abs

/**
 * A compact horizontal scroll wheel used on the top edge of the simulated-mouse pad.
 *
 * Drag it vertically: the ridges move under the finger and [onScroll] receives the raw
 * delta. [onRelease] receives the last vertical velocity (px/ms) so the owner can add
 * inertia. This view deliberately does not scroll anything by itself; the pointer overlay
 * decides how far and how fast to scroll, using short swipe steps so the step size does not
 * depend on how a target app interprets ACTION_SCROLL_*.
 */
class MouseScrollWheelView(
    context: Context,
    private val onScroll: (Float) -> Unit,
    private val onRelease: (Float) -> Unit,
) : View(context) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BODY_COLOR
    }
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RIDGE_COLOR
        strokeWidth = 1f.dp2px
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp2px
    }

    private var wheelOffset = 0f
    private var lastY = 0f
    private var lastEventTime = 0L
    private var velocity = 0f

    init {
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = "滚轮"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        drawBody(canvas, width, height)
        drawBorder(canvas, width, height)
        drawRidges(canvas, width, height)
    }

    private fun drawBody(canvas: Canvas, width: Float, height: Float) {
        bodyPaint.color = if (isPressed) BODY_PRESSED_COLOR else BODY_COLOR
        val radius = CORNER_RADIUS_DP.dp2px
        canvas.drawRoundRect(RectF(0f, 0f, width, height), radius, radius, bodyPaint)
    }

    private fun drawBorder(canvas: Canvas, width: Float, height: Float) {
        val inset = BORDER_INSET_DP.dp2px
        val radius = CORNER_RADIUS_DP.dp2px
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius,
            radius,
            borderPaint,
        )
    }

    private fun drawRidges(canvas: Canvas, width: Float, height: Float) {
        val spacing = RIDGE_SPACING_DP.dp2px
        val offset = ((wheelOffset % spacing) + spacing) % spacing
        val inset = RIDGE_INSET_DP.dp2px
        var y = offset - spacing
        while (y <= height + spacing) {
            if (y > 1f && y < height - 1f) {
                canvas.drawLine(inset, y, width - inset, y, ridgePaint)
            }
            y += spacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleDown(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                handleMove(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleRelease(event)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDown(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        lastY = event.rawY
        lastEventTime = event.eventTime
        velocity = 0f
        isPressed = true
        invalidate()
    }

    private fun handleMove(event: MotionEvent) {
        val now = event.eventTime
        val dy = event.rawY - lastY
        val dt = (now - lastEventTime).coerceAtLeast(1L)
        if (abs(dy) >= MIN_SCROLL_DELTA_PX) {
            velocity = dy / dt.toFloat()
            wheelOffset = (wheelOffset + dy) % WHEEL_OFFSET_WRAP
            onScroll(dy)
        }
        lastY = event.rawY
        lastEventTime = now
        invalidate()
    }

    private fun handleRelease(event: MotionEvent) {
        // A pause before lifting means the wheel was held still, not flicked. Drop the
        // stale velocity so releasing after a pause does not produce phantom inertia.
        if (event.eventTime - lastEventTime > STALE_RELEASE_MS) {
            velocity = 0f
        }
        // Always notify, including a stationary release. The owner uses this to stop
        // the analog scroll loop; otherwise holding the wheel still would keep scrolling.
        onRelease(velocity)
        velocity = 0f
        isPressed = false
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val BODY_COLOR = 0x26000000
        const val BODY_PRESSED_COLOR = 0x33000000
        const val BORDER_COLOR = 0x1FFFFFFF
        const val RIDGE_COLOR = 0x33FFFFFF

        const val CORNER_RADIUS_DP = 8f
        const val BORDER_INSET_DP = 0.5f
        const val RIDGE_SPACING_DP = 9f
        const val RIDGE_INSET_DP = 4f
        const val WHEEL_OFFSET_WRAP = 10_000f
        const val MIN_SCROLL_DELTA_PX = 0.5f
        const val STALE_RELEASE_MS = 80L
    }
}
