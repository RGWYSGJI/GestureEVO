package io.github.lumiagg.gestureevo.pointer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lumiagg.gestureevo.utils.dp2px

/**
 * Edge action strip shown only while the pointer is in mouse mode.
 *
 * The strip is attached to the inner edge of the touch pad: when the pad is on the left side of
 * the screen, the strip sits on its right edge, and vice versa. The four arrow buttons stay in
 * the middle; the two gesture buttons sit above them and the three navigation buttons below.
 */
class MousePadOverlay(
    context: Context,
    private val onScroll: (Direction) -> Unit,
    private val onDoubleClick: () -> Unit,
    private val onLongPressToggle: () -> Unit,
    private val onQuickScroll: (Boolean) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val strip = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val buttons = linkedMapOf<Direction, TextView>()
    private lateinit var longPressButton: TextView
    private lateinit var closeButton: TextView
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
        addView(
            strip,
            LayoutParams(STRIP_WIDTH_DP.dp2px.toInt(), LayoutParams.MATCH_PARENT, Gravity.END)
        )

        // Order is deliberate: gesture buttons on top, four directions in the middle,
        // page navigation on the bottom.
        addActionButton("×2") { onDoubleClick() }
        longPressButton = addActionButton("长") { onLongPressToggle() }
        addDirectionButton(Direction.UP, "↑")
        addDirectionButton(Direction.DOWN, "↓")
        addDirectionButton(Direction.LEFT, "←")
        addDirectionButton(Direction.RIGHT, "→")
        addActionButton("顶") { onQuickScroll(true) }
        addActionButton("底") { onQuickScroll(false) }
        closeButton = addActionButton("关") { handleCloseTap() }

        visibility = View.GONE
    }

    private fun addDirectionButton(direction: Direction, label: String) {
        val button = addActionButton(label) { onScroll(direction) }
        buttons[direction] = button
    }

    private fun addActionButton(label: String, action: () -> Unit): TextView {
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
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            val margin = 2f.dp2px.toInt()
            setMargins(margin, margin, margin, margin)
        }
        strip.addView(button, params)
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
        val params = strip.layoutParams as LayoutParams
        params.gravity = if (touchPadOnLeft) Gravity.END else Gravity.START
        strip.layoutParams = params
    }

    override fun onDetachedFromWindow() {
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
        const val BUTTON_OFF_COLOR = 0x66000000
        val BUTTON_ON_COLOR = 0xCC4CAF50.toInt()
        val BUTTON_ARMED_COLOR = 0xCCD32F2F.toInt()
        const val CLOSE_DOUBLE_TAP_MS = 300L
    }
}
