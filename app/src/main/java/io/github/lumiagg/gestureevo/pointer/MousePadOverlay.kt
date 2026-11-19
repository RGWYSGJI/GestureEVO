package io.github.lumiagg.gestureevo.pointer

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lumiagg.gestureevo.utils.dp2px

/**
 * Action overlay shown only while the pointer is in mouse mode.
 *
 * The touch pad is a rectangle: the four direction buttons stay on its inner long edge (the
 * "x" edge, left or right), a scroll wheel runs along the top short edge, and the three
 * page/navigation buttons sit on the bottom short edge.
 */
class MousePadOverlay(
    context: Context,
    private val onDirectionPress: (Direction) -> Unit,
    private val onDirectionRelease: () -> Unit,
    private val onWheelScroll: (Float) -> Unit,
    private val onWheelRelease: (Float) -> Unit,
    private val onQuickScroll: (Boolean) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val bottomBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val directionStrip = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val buttons = linkedMapOf<Direction, TextView>()
    private lateinit var closeButton: TextView
    private var activeDirection: Direction? = null
    private var closeArmed = false
    private var lastCloseTapAt = 0L
    private val closeReset = Runnable {
        closeArmed = false
        lastCloseTapAt = 0L
        if (::closeButton.isInitialized) {
            setButtonColor(closeButton, BUTTON_OFF_COLOR)
        }
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val barHeight = BAR_HEIGHT_DP.dp2px.toInt()
        addView(
            topBar,
            LayoutParams(LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP),
        )
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM),
        )
        addView(
            directionStrip,
            LayoutParams(STRIP_WIDTH_DP.dp2px.toInt(), LayoutParams.MATCH_PARENT, Gravity.END).apply {
                topMargin = barHeight
                bottomMargin = barHeight
            },
        )

        // Top short edge: one scroll wheel, spanning the area that used to hold the two
        // gesture buttons.
        topBar.addView(
            MouseScrollWheelView(context, onWheelScroll, onWheelRelease),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                val margin = 2f.dp2px.toInt()
                setMargins(margin, margin, margin, margin)
            },
        )

        // Inner long edge: the existing four directions, unchanged in order.
        addDirectionButton(Direction.UP, "↑")
        addDirectionButton(Direction.DOWN, "↓")
        addDirectionButton(Direction.LEFT, "←")
        addDirectionButton(Direction.RIGHT, "→")

        // Bottom short edge: quick top, quick bottom, close. U+21D1/U+21D3 live in the
        // ordinary Arrows block, which is much more widely covered than Supplemental
        // Arrows-B; keep a plain-arrow fallback for ROMs that still lack them.
        addActionButton(bottomBar, edgeArrowLabel(up = true), vertical = false) {
            onQuickScroll(true)
        }
        addActionButton(bottomBar, edgeArrowLabel(up = false), vertical = false) {
            onQuickScroll(false)
        }
        closeButton = addActionButton(bottomBar, "关闭", vertical = false) {
            handleCloseTap()
        }

        visibility = View.GONE
    }

    private fun addDirectionButton(direction: Direction, label: String) {
        val button = addActionButton(directionStrip, label, vertical = true) {}
        button.setOnClickListener(null)
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (activeDirection == null) {
                        activeDirection = direction
                        view.isPressed = true
                        setButtonColor(view as TextView, BUTTON_ON_COLOR)
                        onDirectionPress(direction)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeDirection == direction) {
                        activeDirection = null
                        view.isPressed = false
                        setButtonColor(view as TextView, BUTTON_OFF_COLOR)
                        onDirectionRelease()
                    }
                    true
                }
                else -> true
            }
        }
        buttons[direction] = button
    }

    /** Stops a direction hold if the pad is hidden while the finger is still down. */
    fun cancelDirectionHold() {
        val direction = activeDirection ?: return
        activeDirection = null
        buttons[direction]?.let {
            it.isPressed = false
            setButtonColor(it, BUTTON_OFF_COLOR)
        }
        onDirectionRelease()
    }

    private fun edgeArrowLabel(up: Boolean): String {
        val symbol = if (up) "\u21D1" else "\u21D3"
        return if (Paint().hasGlyph(symbol)) symbol else if (up) "↑" else "↓"
    }

    private fun addActionButton(
        parent: LinearLayout,
        label: String,
        vertical: Boolean,
        action: () -> Unit,
    ): TextView {
        val button = TextView(context).apply {
            text = label
            textSize = if (label.length > 1) 14f else 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = false
            background = buttonBackground(BUTTON_OFF_COLOR)
            setOnClickListener { action() }
        }
        val params = if (vertical) {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        } else {
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
        }.apply {
            val margin = 2f.dp2px.toInt()
            setMargins(margin, margin, margin, margin)
        }
        parent.addView(button, params)
        return button
    }

    private fun handleCloseTap() {
        val now = SystemClock.uptimeMillis()
        if (closeArmed && now - lastCloseTapAt in 1L..CLOSE_DOUBLE_TAP_MS) {
            closeArmed = false
            lastCloseTapAt = 0L
            removeCallbacks(closeReset)
            onClose()
            return
        }
        closeArmed = true
        lastCloseTapAt = now
        setButtonColor(closeButton, BUTTON_ARMED_COLOR)
        removeCallbacks(closeReset)
        postDelayed(closeReset, CLOSE_DOUBLE_TAP_MS)
    }

    /** [touchPadOnLeft] is true when the pad itself occupies the left side of the screen. */
    fun setTouchPadSide(touchPadOnLeft: Boolean) {
        val params = directionStrip.layoutParams as LayoutParams
        params.gravity = if (touchPadOnLeft) Gravity.END else Gravity.START
        directionStrip.layoutParams = params
    }

    override fun onDetachedFromWindow() {
        cancelDirectionHold()
        removeCallbacks(closeReset)
        super.onDetachedFromWindow()
    }

    private fun setButtonColor(button: TextView, color: Int) {
        button.background = buttonBackground(color)
    }

    private fun buttonBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 10f.dp2px
        setColor(color)
    }

    private companion object {
        const val STRIP_WIDTH_DP = 48f
        const val BAR_HEIGHT_DP = 48f
        const val BUTTON_OFF_COLOR = 0x66000000
        val BUTTON_ON_COLOR = 0xCC4CAF50.toInt()
        val BUTTON_ARMED_COLOR = 0xCCD32F2F.toInt()
        const val CLOSE_DOUBLE_TAP_MS = 300L
    }
}
