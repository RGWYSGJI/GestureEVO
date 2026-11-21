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
 * decides how far and how fast to scroll; it currently uses short swipe steps so the step
 * size does not depend on how a target app interprets ACTION_SCROLL_*.
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        bodyPaint.color = if (isPressed) BODY_PRESSED_COLOR else BODY_COLOR
        val radius = 8f.dp2px
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, bodyPaint)
        val borderInset = 0.5f.dp2px
        canvas.drawRoundRect(
            RectF(borderInset, borderInset, w - borderInset, h - borderInset),
            radius,
            radius,
            borderPaint,
        )

        // Horizontal ridges that travel vertically, like the surface of a wheel.
        val spacing = 9f.dp2px
        val offset = ((wheelOffset % spacing) + spacing) % spacing
        var y = offset - spacing
        val inset = 4f.dp2px
        while (y <= h + spacing) {
            if (y > 1f && y < h - 1f) {
                canvas.drawLine(inset, y, w - inset, y, ridgePaint)
            }
            y += spacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = event.rawY
                lastEventTime = event.eventTime
                velocity = 0f
                isPressed = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val now = event.eventTime
                val dy = event.rawY - lastY
                val dt = (now - lastEventTime).coerceAtLeast(1L)
                if (abs(dy) >= 0.5f) {
                    velocity = dy / dt.toFloat()
                    wheelOffset = (wheelOffset + dy) % 10000f
                    onScroll(dy)
                }
                lastY = event.rawY
                lastEventTime = now
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A pause before lifting means the wheel was held still, not flicked. Drop the
                // stale velocity so releasing after a pause does not produce phantom inertia.
                if (event.eventTime - lastEventTime > 80L) {
                    velocity = 0f
                }
                // Always notify, including a stationary release. The owner uses this to stop
                // the analog scroll loop; otherwise holding the wheel still would keep scrolling.
                onRelease(velocity)
                velocity = 0f
                isPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        val BODY_COLOR = 0x26000000
        val BODY_PRESSED_COLOR = 0x33000000
        val BORDER_COLOR = 0x1FFFFFFF
        val RIDGE_COLOR = 0x33FFFFFF
    }
}
