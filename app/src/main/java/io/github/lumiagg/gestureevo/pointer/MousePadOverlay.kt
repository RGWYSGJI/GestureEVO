package io.github.lumiagg.gestureevo.pointer

import android.content.Context
import android.graphics.Color
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
 * "x" edge, left or right), while the two gesture buttons sit on the top short edge and the
 * three page/navigation buttons sit on the bottom short edge. That keeps the new actions off
 * the same side as the arrows without changing the pad's existing width/position.
 */
class MousePadOverlay(
    context: Context,
    private val onDirectionPress: (Direction) -> Unit,
    private val onDirectionRelease: () -> Unit,
    private val onDoubleClick: () -> Unit,
    private val onLongPressToggle: () -> Unit,
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
    private lateinit var longPressButton: TextView
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

        // Top short edge: double-click, then long-press.
        addActionButton(topBar, "×2", vertical = false) { onDoubleClick() }
        longPressButton = addActionButton(topBar, "长", vertical = false) {
            onLongPressToggle()
        }

        // Inner long edge: the existing four directions, unchanged in order.
        addDirectionButton(Direction.UP, "↑")
        addDirectionButton(Direction.DOWN, "↓")
        addDirectionButton(Direction.LEFT, "←")
        addDirectionButton(Direction.RIGHT, "→")

        // Bottom short edge: quick top, quick bottom, close.
        addActionButton(bottomBar, "顶", vertical = false) { onQuickScroll(true) }
        addActionButton(bottomBar, "底", vertical = false) { onQuickScroll(false) }
        closeButton = addActionButton(bottomBar, "关", vertical = false) {
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

    /** Toggle state for the long-press mode button: lit while enabled, dark while disabled. */
    fun setLongPressActive(active: Boolean) {
        if (!::longPressButton.isInitialized) return
        setButtonColor(longPressButton, if (active) BUTTON_ON_COLOR else BUTTON_OFF_COLOR)
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
