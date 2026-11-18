package io.github.lumiagg.gestureevo.pointer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lumiagg.gestureevo.utils.dp2px

/**
 * Edge scroll strip shown only while the pointer is in mouse mode.
 *
 * The strip is attached to the inner edge of the touch pad: when the pad is on the left side of
 * the screen, the strip sits on its right edge, and vice versa. It deliberately contains only
 * four tap targets; a Windows-style scrollbar would compete with the pad's drag-to-move gesture.
 */
class MousePadOverlay(
    context: Context,
    private val onScroll: (Direction) -> Unit,
) : FrameLayout(context) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val strip = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val buttons = linkedMapOf<Direction, TextView>()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            strip,
            LayoutParams(STRIP_WIDTH_DP.dp2px.toInt(), LayoutParams.MATCH_PARENT, Gravity.END)
        )
        addButton(Direction.UP, "↑")
        addButton(Direction.DOWN, "↓")
        addButton(Direction.LEFT, "←")
        addButton(Direction.RIGHT, "→")
        visibility = View.GONE
    }

    private fun addButton(direction: Direction, label: String) {
        val button = TextView(context).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = 10f.dp2px
                setColor(0x66000000)
            }
            setOnClickListener { onScroll(direction) }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            val margin = 2f.dp2px.toInt()
            setMargins(margin, margin, margin, margin)
        }
        buttons[direction] = button
        strip.addView(button, params)
    }

    /** [touchPadOnLeft] is true when the pad itself occupies the left side of the screen. */
    fun setTouchPadSide(touchPadOnLeft: Boolean) {
        val params = strip.layoutParams as LayoutParams
        params.gravity = if (touchPadOnLeft) Gravity.END else Gravity.START
        strip.layoutParams = params
    }

    private companion object {
        const val STRIP_WIDTH_DP = 48f
    }
}
