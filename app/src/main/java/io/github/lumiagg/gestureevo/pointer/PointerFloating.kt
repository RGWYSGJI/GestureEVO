package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.databinding.PointerFloatingTouchViewBinding

import io.github.lumiagg.gestureevo.databinding.GestureActionPointerViewBinding

import io.github.lumiagg.gestureevo.databinding.PointerFloatingViewBinding

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnNextLayout
import android.widget.FrameLayout
import com.google.android.material.card.MaterialCardView
import com.google.zxing.common.detector.MathUtils.distance
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.GestureManager.InteractionMode
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.inspire.SmartSegmenter
import io.github.lumiagg.gestureevo.inspire.resolveScreenContentRect
import io.github.lumiagg.gestureevo.pointer.ui.HighlightRectView
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_POINTER_COLOR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_POINTER_CLICK_FEEDBACK
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_POINTER_SHAPE
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_POINTER_SIZE
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SHAPE_ARROW
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SHAPE_CROSS
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SHAPE_DOT
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SHAPE_SMALL_DOT
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SIZE_DEFAULT
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SIZE_MAX
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.POINTER_SIZE_MIN
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_ENABLE_POINTER_DRAG_MODE
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_POINTER_HOVER_MENU
import io.github.lumiagg.gestureevo.pointer.radar.LinkRegion
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_MOUSE_ARROW_SPEED
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_MOUSE_WHEEL_SPEED
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_POINTER_TOUCH_AREA_OFFSET_DP
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_SHOW_POINTER_TRAIL
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.LinkedList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min


class PointerFloating(
    private val context: GestureService,
) : BaseFloatingWindow(context, context, "PointerFloating") {
    private val pointerFloatingView: ConstraintLayout by lazy {
        PointerFloatingViewBinding.inflate(LayoutInflater.from(context)).root
    }
    private val touchView: MaterialCardView by lazy {
        PointerFloatingTouchViewBinding.inflate(LayoutInflater.from(context)).root
    }
    private val mousePadOverlay: MousePadOverlay by lazy {
        MousePadOverlay(
            context = context,
            onDirectionPress = { direction -> onMouseDirectionPress(direction) },
            onDirectionRelease = { onMouseDirectionRelease() },
            onWheelScroll = { delta -> onMouseWheelScroll(delta) },
            onWheelRelease = { velocity -> onMouseWheelRelease(velocity) },
            onLongPress = { onMouseLongPress() },
            onQuickScroll = { toTop -> onMouseQuickScroll(toTop) },
            onClose = {
                Log.d("PointerFloating", "mousePad close button double tap")
                enable = false
            },
        )
    }
    private val pointerLayoutParams = WindowManager.LayoutParams()
    private val touchLayoutParams = WindowManager.LayoutParams()
    private var touchViewScale = 0.35f
    var screenStatusHeight = ScreenUtil.getStatusBarHeight(context)
    private val trailView = TrailView(context)
    private val highlightRectView = HighlightRectView(context)
    val pointerManger = PointerManger()
    var needShowTouchView = false
    private var enterMouseAfterShow = false
    private var touchAreaOffsetDp = 0
    private var mouseModeActive = false
    private var mouseLongPressHoldActive = false
    private val pointerHoldSession by lazy {
        PointerDragSession(context, context) { onPointerHoldStopped() }
    }
    private var mouseArrowSpeed = 1.0f
    private var mouseWheelSpeed = 1.0f
    private var mouseHoldScrollJob: Job? = null
    private var mouseHoldDirection: MousePadOverlay.Direction? = null
    private var mouseQuickScrollJob: Job? = null
    private var mouseWheelLoopJob: Job? = null
    private var mouseWheelOffsetPx = 0f
    private var mouseWheelDragging = false
    private var mouseWheelInertiaSpeedPxPerSec = 0f
    private var mouseWheelScrollRemainderPx = 0f
    private var mouseWheelFallbackJob: Job? = null
    private var pointerVisibleRequested = false
    private var hoverMenuEnabled = false
    var captureLinks: suspend () -> List<LinkRegion> = { emptyList() }
    private val hoverMenu by lazy {
        PointerHoverMenuController(context, pointerFloatingView, { captureLinks() }, { anchor ->
            pointerManger.beginMenuSelection(anchor)
        }, { anchor ->
            pointerManger.rebaseMenuPointer(anchor)
        }, { action, point, valid ->
            val success = if (action == PointerHoverAction.DOUBLE_TAP) {
                performPointerClick(point.x, point.y, 60L, valid, clickCount = 2)
            } else if (valid()) {
                performPointerClick(point.x, point.y, 600L, valid)
            } else false
            if (!success && valid()) {
                io.github.lumiagg.gestureevo.utils.ToastHelper.showInUIThread(context, R.string.pointer_hover_action_failed)
            }
        }, onSelectionPreview = { anchor ->
            pointerManger.previewMenuSelection(anchor)
        }, isCoordinateSpaceCurrent = {
            pointerManger.isMenuCoordinateSpaceCurrent()
        })
    }
    val isHoverInteractionActive: Boolean
        get() = !mouseModeActive && hoverMenuEnabled && hoverMenu.ownsInteraction

    /** True while the pointer is controlled by the simulated mouse touch pad. */
    val isMouseMode: Boolean get() = mouseModeActive

    fun onWindowEvent(event: AccessibilityEvent) {
        if (mouseModeActive) return
        if (hoverMenuEnabled && hoverMenu.onWindowEvent(event) && pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
    }

    fun cancelHoverInteraction() {
        if (mouseModeActive) return
        if (hoverMenuEnabled) {
            hoverMenu.cancel()
            if (pointerManger.isDragMode) pointerManger.exitDragMode()
        }
    }
    var triggerSide: String? = null

    /** Pointer icon state for the link radar: screen-space center plus icon radius. */
    data class PointerSnapshot(val centerX: Float, val centerY: Float, val radiusPx: Float)

    /**
     * True only while a drag selection box is actually being drawn (not mere drag mode).
     */
    val isPointerDragSelecting: Boolean
        get() = !mouseModeActive && pointerManger.isDragMode && pointerManger.isDragSelectionStarted

    /**
     * True from hover-stability until drag mode exits. The link radar yields for this whole
     * window: its capsule and hit areas would otherwise sit on top of the selection crosshair,
     * and its tap interception would turn a release into a link open.
     */
    val isPointerDragMode: Boolean
        get() = !mouseModeActive && pointerManger.isDragMode

    /** Current pointer center/radius in screen coordinates, null while the icon is hidden. */
    fun currentPointerSnapshot(): PointerSnapshot? =
        if (mouseModeActive) null else pointerManger.currentPointerSnapshot()

    /**
     * Lets the link radar consume the pointer's tap: with a link targeted, tapping the
     * pointer dispatches through the rules instead of clicking the screen. Return true to
     * skip the synthesized screen click.
     */
    var onPointerTapInterceptor: (() -> Boolean)? = null
    var onPointerLinkTargeted: (() -> Boolean)? = null
    var onPointerPositionChanged: ((PointerSnapshot?) -> Unit)? = null
    var onPointerHidden: (() -> Unit)? = null

    init {
        initView()
        initFloatingWindow()
        initTouchView()
        addTrailView()
        addHighlightRectView()
    }

    private fun addHighlightRectView() {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pointerFloatingView.addView(
            highlightRectView,
            1, // Add above trail view but below pointer
            layoutParams
        )
    }

    private fun addTrailView() {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pointerFloatingView.addView(
            trailView, 0, layoutParams
        ) // Add at index 0 to be behind pointer
    }

    private fun initView() {
        pointerFloatingView.visibility = View.GONE
    }

    fun refreshTheme() {
        if (hoverMenuEnabled) hoverMenu.refreshTheme()
        highlightRectView.setStrokeColor(pointerManger.getPointerColor())
        pointerManger.refreshTheme()
        // The trail stamps a copy of the pointer artwork, so it has to be rebuilt after the
        // pointer itself picks up the new shape and colour.
        trailView.refreshTheme()
    }

    private fun initFloatingWindow() {
        pointerLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:PointerFloating:Pointer",
        )
        pointerFloatingView.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchView() {
        // Some Material3 card themes inherit a 1dp outline. The touch pad should be only
        // the translucent fill, with no light rim in either pointer mode.
        touchView.strokeWidth = 0
        touchView.strokeColor = android.graphics.Color.TRANSPARENT
        touchLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            width = ViewGroup.LayoutParams.WRAP_CONTENT,
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            allowDisplayCutout = false,
            title = "GestureEVO:PointerFloating:Touch",
        )
        touchView.setOnTouchListener { _, event ->
            pointerManger.onTouchEvent(event, fromTouchView = true)
            // Consume the event while mouse mode owns the session. Otherwise the touch can
            // fall through to the full-screen pointer layer, whose outside-tap handler exits.
            mouseModeActive
        }
        touchView.addView(
            mousePadOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        touchView.visibility = View.GONE
    }

    private fun updateTouchView() {
        pointerFloatingView.post {
            if (!pointerVisibleRequested) {
                return@post
            }
            if (!pointerFloatingView.isAttachedToWindow || !touchView.isAttachedToWindow) {
                return@post
            }
            pointerFloatingView.visibility = View.VISIBLE
            if (pointerFloatingView.width > 0 && pointerFloatingView.height > 0) {
                updateTouchViewLayout()
            } else {
                pointerFloatingView.doOnNextLayout {
                    updateTouchViewLayout()
                }
            }
        }
    }

    private fun updateTouchViewLayout() {
        if (!pointerVisibleRequested) {
            return
        }
        if (!pointerFloatingView.isAttachedToWindow || !touchView.isAttachedToWindow) {
            return
        }
        val screenWidth = pointerFloatingView.width
        val screenHeight = pointerFloatingView.height
        if (screenWidth <= 0 || screenHeight <= 0) return

        val layoutParams = touchView.layoutParams as WindowManager.LayoutParams
        val basePadWidth = screenWidth * touchViewScale + 20f.dp2px
        // Mouse mode used to double this width. Keep the normal touch-pad width
        // now that the wheel has a fixed narrow slot; the remaining action
        // buttons shrink automatically with the pad.
        val requestedPadWidth = basePadWidth
        layoutParams.width = requestedPadWidth
            .coerceAtMost(screenWidth - 20f.dp2px)
            .toInt()
        layoutParams.height = ((screenHeight + screenStatusHeight) * touchViewScale).toInt()

        // The mouse pad uses the same margins as the normal pointer pad:
        // 10dp on both sides and 20dp from the bottom.
        val minDistanceFromTop = 20f.dp2px
        val minDistanceFromEdge = 10f.dp2px
        val minDistanceFromBottom = 20f.dp2px

        val minX = minDistanceFromEdge
        val maxX = screenWidth - layoutParams.width - minDistanceFromEdge
        val minY = minDistanceFromTop
        val maxY = screenHeight - layoutParams.height - minDistanceFromBottom

        val touchYRatio = (pointerManger.touchViewY / screenHeight).coerceIn(0f, 1f)
        val amplifiedTouchYRatio = ((touchYRatio - 0.5f) * 1.5f + 0.5f).coerceIn(0f, 1f)

        val baseX = (pointerManger.touchViewX - layoutParams.width / 2).toInt()
            .coerceIn(minX.toInt(), maxX.toInt())
        layoutParams.x = baseX

        layoutParams.y = (minY + (maxY - minY) * amplifiedTouchYRatio + touchAreaOffsetDp.dp2px).toInt()
            .coerceIn(minY.toInt(), maxY.toInt())

        updateViewLayoutSafely(touchView, layoutParams)
        touchView.alpha = if (shouldShowTouchArea()) 1f else 0f
        touchView.visibility = View.VISIBLE
    }

    private fun shouldShowTouchArea() =
        mouseModeActive || needShowTouchView

    private companion object {
        const val POINTER_SHOW_ANIMATION_DURATION = 140L
        const val POINTER_HIDE_ANIMATION_DURATION = 80L
        const val POINTER_ANIMATION_START_SCALE = 0.82f

        private val POINTER_SHAPES = setOf(
            POINTER_SHAPE_DOT,
            POINTER_SHAPE_SMALL_DOT,
            POINTER_SHAPE_CROSS,
            POINTER_SHAPE_ARROW
        )

        const val POINTER_SIZE_DEFAULT_DP = POINTER_SIZE_DEFAULT
        const val POINTER_SIZE_MIN_DP = POINTER_SIZE_MIN
        const val POINTER_SIZE_MAX_DP = POINTER_SIZE_MAX

        /**
         * Tap exit: press in, spring back past 1.0, then shrink and fade out. All three
         * segments drive the wrapper view, so the dot and its ring stay in lockstep.
         */
        const val POINTER_CLICK_PRESS_SCALE = 0.85f
        const val POINTER_CLICK_OVERSHOOT_SCALE = 1.06f
        const val POINTER_CLICK_PULSE_SHRINK_DURATION = 50L
        const val POINTER_CLICK_PULSE_RETURN_DURATION = 70L
        const val POINTER_EXIT_DURATION = 80L
        const val POINTER_EXIT_END_SCALE = 0.7f

        /** Fraction of the icon box kept as padding around the drawn shape. */
        const val POINTER_ICON_INSET_RATIO = 0.12f

        /**
         * Inset for the filled dot. Measured against the stock pointer (dot radius 21px vs
         * ring 42.5px on a 30dp box), 0.245 reproduces the original dot-to-ring proportion.
         */
        const val POINTER_DOT_INSET_RATIO = 0.245f

        /** Trail stencil size range: the newest copy is largest, the oldest smallest. */
        const val TRAIL_STENCIL_MIN_DP = 4f
        const val TRAIL_STENCIL_GROWTH_DP = 10f

        /** Simulated mouse mode. */
        const val MOUSE_LONG_PRESS_DURATION_MS = 600L
        const val MOUSE_SCROLL_DURATION_MS = 260L
        const val MOUSE_SCROLL_REPEAT_DELAY_MS = 40L
        const val MOUSE_QUICK_SCROLL_STEPS = 30
        const val MOUSE_SCROLL_DISTANCE_RATIO = 0.35f
        const val MOUSE_WHEEL_MAX_OFFSET_DP = 56f
        const val MOUSE_WHEEL_DEAD_ZONE = 0.10f
        const val MOUSE_WHEEL_MAX_SPEED_DP_PER_SEC = 500f
        const val MOUSE_WHEEL_STEP_DISTANCE_DP = 24f
        const val MOUSE_WHEEL_MAX_STEPS_PER_TICK = 2
        const val MOUSE_WHEEL_INERTIA_DECAY_PER_SEC = 6.0f
        const val MOUSE_WHEEL_MIN_SPEED_DP_PER_SEC = 36f
        const val MOUSE_WHEEL_SWIPE_DISTANCE_DP = 24f
        const val MOUSE_WHEEL_SWIPE_DURATION_MS = 120L
    }

    var enable = false
        set(value) {
            if (value && field && mouseModeActive) {
                // A second pointer action is the documented way to leave mouse mode.
                field = false
                hide()
                return
            }
            if (value) {
                field = true
                show()
            } else {
                field = false
                hide()
            }
        }

    fun show() = context.postOnMain {
        resetMouseModeState()
        pointerVisibleRequested = true
        ensureViewsAttached()
        updateTouchAreaConfig()
        hoverMenuEnabled = context.settingsStore.readKey(KEY_POINTER_HOVER_MENU, false)
        hoverMenu.reset()
        pointerManger.resetMenuMapping()
        pointerManger.updatePointerColorConfig()
        pointerManger.updatePointerAppearanceConfig()
        highlightRectView.setStrokeColor(pointerManger.getPointerColor())
        pointerFloatingView.visibility = View.VISIBLE
        updateTouchView()
        // Apply shape and size first: the trail setting below only refreshes trail points and
        // the theme, but it must see the current shape so it does not tint a cut-out shape's
        // background.
        pointerManger.applyPointerAppearance()
        pointerManger.showPointer()
        pointerManger.viewPositionMonitor.start()
        trailView.updateTrailConfig()
        if (enterMouseAfterShow) {
            mouseArrowSpeed = context.settingsStore.readKey(KEY_MOUSE_ARROW_SPEED, 1.0f)
                .coerceIn(0.1f, 2.0f)
            mouseWheelSpeed = context.settingsStore.readKey(KEY_MOUSE_WHEEL_SPEED, 1.0f)
                .coerceIn(0.1f, 3.0f)
            enterMouseAfterShow = false
            enterMouseMode()
        }
    }

    /** Starts touchpad mode for the TOUCHPAD action. Repeated invocations are ignored. */
    fun enterMouseModeFromAction() {
        if (mouseModeActive) return
        enterMouseAfterShow = true
        needShowTouchView = true
        enable = true
    }

    fun hide() = context.postOnMain {
        resetMouseModeState()
        pointerVisibleRequested = false
        hoverMenu.reset()
        onPointerHidden?.invoke()
        pointerManger.viewPositionMonitor.stop()
        if (pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
        if (!pointerFloatingView.isAttachedToWindow && !touchView.isAttachedToWindow) {
            needShowTouchView = false
            pointerManger.initPosition()
            trailView.clearTrail()
            return@postOnMain
        }
        touchView.animate().cancel()
        touchView.visibility = View.GONE
        needShowTouchView = false
        trailView.clearTrail()
        pointerManger.hidePointer {
            if (!pointerVisibleRequested) {
                pointerFloatingView.visibility = View.GONE
                pointerManger.initPosition()
                detachViewsIfNeeded()
            }
        }
    }

    private fun ensureViewsAttached() {
        val updateWhenReady = {
            if (pointerFloatingView.isAttachedToWindow && touchView.isAttachedToWindow) {
                updateTouchView()
            }
        }
        attachViewSafely(
            pointerFloatingView,
            pointerLayoutParams,
            shouldRetry = { pointerVisibleRequested },
            onAttached = {
                updateWhenReady()
                // A retried pointer attachment can arrive after the pad and land on top.
                // Re-assert the pad's z-order whenever the full-screen layer attaches.
                if (mouseModeActive) bringTouchViewAbovePointerLayer()
            }
        )
        attachViewSafely(
            touchView,
            touchLayoutParams,
            shouldRetry = { pointerVisibleRequested },
            onAttached = updateWhenReady
        )
    }

    private fun detachViewsIfNeeded() {
        detachViewSafely(pointerFloatingView)
        detachViewSafely(touchView)
    }

    fun destroy() = context.postOnMain {
        resetMouseModeState()
        pointerVisibleRequested = false
        hoverMenu.reset()
        onPointerHidden?.invoke()
        pointerManger.viewPositionMonitor.stop()
        if (pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
        pointerFloatingView.animate().cancel()
        touchView.animate().cancel()
        pointerFloatingView.visibility = View.GONE
        touchView.visibility = View.GONE
        needShowTouchView = false
        pointerManger.initPosition()
        trailView.clearTrail()
        detachViewsIfNeeded()
    }

    private suspend fun updateTouchAreaConfig() = withContext(Dispatchers.IO) {
        touchAreaOffsetDp = context.settingsStore.readKey(
            KEY_POINTER_TOUCH_AREA_OFFSET_DP,
            0
        ).coerceIn(-150, 150)
    }

    private fun enterMouseMode(bringPadToFront: Boolean = true) {
        if (!enable || !pointerVisibleRequested || mouseModeActive) return
        mouseModeActive = true
        needShowTouchView = true
        pointerManger.viewPositionMonitor.stop()
        hoverMenu.reset()
        if (pointerManger.isDragMode) pointerManger.exitDragMode()
        highlightRectView.hidePotentialRect()
        pointerManger.resetMenuMapping()
        pointerManger.startMouseMode()
        // Mouse mode owns the pointer session from here; stop link radar / link picking even
        // though the icon itself stays visible.
        onPointerHidden?.invoke()

        positionMousePad()
        // The close button is the explicit exit now. Do not attach the full-screen
        // catcher: on some devices it can be ordered above the pad after a relayout,
        // swallow the button's DOWN/UP and then treat the same point as an outside tap.
        if (bringPadToFront) bringTouchViewAbovePointerLayer()
    }

    /**
     * Window z-order is attach-order. Re-attaching the pad after the pointer layer
     * makes sure the visible pad is the window that receives its own touches.
     */
    private fun bringTouchViewAbovePointerLayer() {
        if (!mouseModeActive) return
        if (!touchView.isAttachedToWindow) {
            touchView.post { if (mouseModeActive) bringTouchViewAbovePointerLayer() }
            return
        }
        detachViewSafely(touchView)
        attachViewSafely(
            touchView,
            touchLayoutParams,
            shouldRetry = { mouseModeActive && pointerVisibleRequested },
            onAttached = { if (mouseModeActive) updateTouchView() },
        )
    }

    private fun positionMousePad() {
        if (!mouseModeActive) return
        val screenWidth = pointerFloatingView.width
        val screenHeight = pointerFloatingView.height
        if (screenWidth <= 0 || screenHeight <= 0) {
            pointerFloatingView.doOnNextLayout { positionMousePad() }
            return
        }
        val onLeft = (triggerSide ?: if (pointerManger.touchViewX < screenWidth / 2f) {
            TriggerSide.LEFT
        } else {
            TriggerSide.RIGHT
        }) == TriggerSide.LEFT
        val edgeInset = 10f.dp2px
        pointerManger.touchViewX = if (onLeft) edgeInset else screenWidth - edgeInset
        // Keep the vertical anchor at the screen bottom; updateTouchViewLayout applies
        // the fixed 20dp bottom margin while clamping.
        pointerManger.touchViewY = screenHeight.toFloat()
        updateTouchViewLayout()
        mousePadOverlay.setTouchPadSide(onLeft)
        mousePadOverlay.visibility = View.VISIBLE
    }

    /** Clears mouse-mode-only state. Safe to call repeatedly. */
    private fun resetMouseModeState() {
        mouseModeActive = false
        mouseHoldDirection = null
        mouseHoldScrollJob?.cancel()
        mouseHoldScrollJob = null
        mouseQuickScrollJob?.cancel()
        mouseQuickScrollJob = null
        mouseWheelLoopJob?.cancel()
        mouseWheelLoopJob = null
        mouseWheelFallbackJob?.cancel()
        mouseWheelFallbackJob = null
        mouseWheelDragging = false
        mouseWheelOffsetPx = 0f
        mouseWheelInertiaSpeedPxPerSec = 0f
        mouseWheelScrollRemainderPx = 0f
        mouseLongPressHoldActive = false
        pointerHoldSession.end()
        mousePadOverlay.setLongPressActive(false)
        pointerManger.stopMouseMode()
        mousePadOverlay.cancelDirectionHold()
        mousePadOverlay.visibility = View.GONE
    }

    private fun onMouseDirectionPress(direction: MousePadOverlay.Direction) {
        if (!mouseModeActive || mouseLongPressHoldActive) return
        mouseHoldDirection = direction
        mouseHoldScrollJob?.cancel()
        mouseHoldScrollJob = null

        mouseHoldScrollJob = context.launch {
            while (isActive && mouseModeActive && mouseHoldDirection == direction) {
                performMouseScrollStep(direction)
                delay(MOUSE_SCROLL_REPEAT_DELAY_MS)
            }
        }
    }

    private fun onMouseDirectionRelease() {
        mouseHoldDirection = null
        mouseHoldScrollJob?.cancel()
        mouseHoldScrollJob = null
    }

    private suspend fun performMouseScrollStep(direction: MousePadOverlay.Direction) {
        val screenWidth = pointerFloatingView.width.coerceAtLeast(1).toFloat()
        val screenHeight = pointerFloatingView.height.coerceAtLeast(1).toFloat()
        val center = pointerManger.pointerCenter()
        val distance = minOf(screenWidth, screenHeight) * MOUSE_SCROLL_DISTANCE_RATIO * mouseArrowSpeed
        val half = distance / 2f
        val (startX, startY, endX, endY) = when (direction) {
            MousePadOverlay.Direction.UP -> listOf(
                center.x, center.y - half, center.x, center.y + half
            )
            MousePadOverlay.Direction.DOWN -> listOf(
                center.x, center.y + half, center.x, center.y - half
            )
            MousePadOverlay.Direction.LEFT -> listOf(
                center.x - half, center.y, center.x + half, center.y
            )
            MousePadOverlay.Direction.RIGHT -> listOf(
                center.x + half, center.y, center.x - half, center.y
            )
        }
        context.performSwipeAsync(
            startX.coerceIn(1f, screenWidth - 1f),
            startY.coerceIn(1f, screenHeight - 1f),
            endX.coerceIn(1f, screenWidth - 1f),
            endY.coerceIn(1f, screenHeight - 1f),
            duration = MOUSE_SCROLL_DURATION_MS,
            useDecelerationCurve = false,
        )
    }

    private fun onMouseLongPress() {
        if (!mouseModeActive) return
        if (mouseLongPressHoldActive) {
            mouseLongPressHoldActive = false
            mousePadOverlay.setLongPressActive(false)
            pointerHoldSession.end()
            return
        }
        if (pointerHoldSession.isBusy) return

        mouseHoldScrollJob?.cancel()
        mouseHoldScrollJob = null
        mouseQuickScrollJob?.cancel()
        mouseQuickScrollJob = null
        mouseWheelLoopJob?.cancel()
        mouseWheelLoopJob = null

        mouseLongPressHoldActive = true
        mousePadOverlay.setLongPressActive(true)
        pointerManger.pulseClickFeedback()
        val point = pointerManger.pointerCenter()
        pointerHoldSession.start(point.x, point.y, MOUSE_LONG_PRESS_DURATION_MS)
    }

    private fun onPointerHoldStopped() {
        context.postOnMain {
            if (pointerHoldSession.isActive) return@postOnMain
            if (!mouseLongPressHoldActive) return@postOnMain
            mouseLongPressHoldActive = false
            mousePadOverlay.setLongPressActive(false)
        }
    }

    private fun onMouseWheelScroll(deltaY: Float) {
        if (!mouseModeActive || mouseLongPressHoldActive || deltaY == 0f) return
        if (!mouseWheelDragging) {
            mouseWheelOffsetPx = 0f
            mouseWheelInertiaSpeedPxPerSec = 0f
            mouseWheelScrollRemainderPx = 0f
            mouseWheelDragging = true
            mouseWheelFallbackJob?.cancel()
            mouseWheelFallbackJob = null
        }
        val maxOffset = MOUSE_WHEEL_MAX_OFFSET_DP.dp2px
        mouseWheelOffsetPx = (mouseWheelOffsetPx + deltaY).coerceIn(-maxOffset, maxOffset)
        ensureMouseWheelLoop()
    }

    private fun onMouseWheelRelease(velocity: Float) {
        if (!mouseModeActive || mouseLongPressHoldActive) return
        mouseWheelDragging = false
        val maxSpeed = MOUSE_WHEEL_MAX_SPEED_DP_PER_SEC.dp2px * mouseWheelSpeed
        val minSpeed = MOUSE_WHEEL_MIN_SPEED_DP_PER_SEC.dp2px
        val inertia = (velocity * 1000f).coerceIn(-maxSpeed, maxSpeed)
        mouseWheelInertiaSpeedPxPerSec = if (abs(inertia) < minSpeed) 0f else inertia
        ensureMouseWheelLoop()
    }

    private fun ensureMouseWheelLoop() {
        if (!mouseModeActive) return
        if (mouseWheelLoopJob?.isActive == true) return
        mouseWheelLoopJob = context.launch { mouseWheelLoop() }
    }

    /**
     * The wheel is analog: the current displacement sets a target speed, and the loop emits
     * small scroll steps at 60 Hz. After release the speed decays exponentially, so a light
     * push stops almost immediately and a hard push coasts for a short distance.
     */
    private suspend fun mouseWheelLoop() {
        var lastTime = SystemClock.uptimeMillis()
        try {
            while (currentCoroutineContext().isActive && mouseModeActive) {
                delay(16)
                val now = SystemClock.uptimeMillis()
                val dt = ((now - lastTime) / 1000f).coerceIn(0.001f, 0.05f)
                lastTime = now

                val speed = if (mouseWheelDragging) {
                    wheelDragSpeedPxPerSec()
                } else {
                    mouseWheelInertiaSpeedPxPerSec
                }
                if (!mouseWheelDragging) {
                    mouseWheelInertiaSpeedPxPerSec *= exp(
                        -MOUSE_WHEEL_INERTIA_DECAY_PER_SEC * dt
                    )
                    if (abs(mouseWheelInertiaSpeedPxPerSec) <
                        MOUSE_WHEEL_MIN_SPEED_DP_PER_SEC.dp2px
                    ) {
                        mouseWheelInertiaSpeedPxPerSec = 0f
                        break
                    }
                }

                if (abs(speed) < MOUSE_WHEEL_MIN_SPEED_DP_PER_SEC.dp2px) {
                    continue
                }

                mouseWheelScrollRemainderPx += speed * dt
                val stepPx = MOUSE_WHEEL_STEP_DISTANCE_DP.dp2px
                var steps = 0
                while (
                    abs(mouseWheelScrollRemainderPx) >= stepPx &&
                    steps < MOUSE_WHEEL_MAX_STEPS_PER_TICK
                ) {
                    val scrollUp = mouseWheelScrollRemainderPx < 0f
                    mouseWheelScrollRemainderPx -= if (scrollUp) -stepPx else stepPx
                    scrollWheelStep(scrollUp)
                    steps++
                }
            }
        } finally {
            mouseWheelLoopJob = null
        }
    }

    private fun wheelDragSpeedPxPerSec(): Float {
        val maxOffset = MOUSE_WHEEL_MAX_OFFSET_DP.dp2px
        val normalized = (mouseWheelOffsetPx / maxOffset).coerceIn(-1f, 1f)
        val magnitude = abs(normalized)
        if (magnitude <= MOUSE_WHEEL_DEAD_ZONE) return 0f
        val strength = (magnitude - MOUSE_WHEEL_DEAD_ZONE) / (1f - MOUSE_WHEEL_DEAD_ZONE)
        val speed = strength * MOUSE_WHEEL_MAX_SPEED_DP_PER_SEC.dp2px * mouseWheelSpeed
        return if (normalized < 0f) -speed else speed
    }

    private fun scrollWheelStep(scrollUp: Boolean) {
        if (!mouseModeActive) return
        // Do not use ACTION_SCROLL_* for the wheel: many apps treat it as a full page even
        // when a fractional amount is supplied, which is exactly the "too fast" behavior.
        // A short, slow swipe is app-independent and gives the wheel a predictable step.
        scheduleWheelSwipe(scrollUp)
    }

    private fun scheduleWheelSwipe(scrollUp: Boolean) {
        if (mouseWheelFallbackJob?.isActive == true) return
        mouseWheelFallbackJob = context.launch {
            try {
                swipeWheel(scrollUp)
            } finally {
                mouseWheelFallbackJob = null
            }
        }
    }

    private suspend fun swipeWheel(scrollUp: Boolean) {
        val screenWidth = pointerFloatingView.width.coerceAtLeast(1).toFloat()
        val screenHeight = pointerFloatingView.height.coerceAtLeast(1).toFloat()
        val center = pointerManger.pointerCenter()
        val half = MOUSE_WHEEL_SWIPE_DISTANCE_DP.dp2px / 2f
        val startY = if (scrollUp) center.y - half else center.y + half
        val endY = if (scrollUp) center.y + half else center.y - half
        context.performSwipeAsync(
            center.x.coerceIn(1f, screenWidth - 1f),
            startY.coerceIn(1f, screenHeight - 1f),
            center.x.coerceIn(1f, screenWidth - 1f),
            endY.coerceIn(1f, screenHeight - 1f),
            duration = MOUSE_WHEEL_SWIPE_DURATION_MS,
            useDecelerationCurve = false,
        )
    }

    private fun onMouseQuickScroll(toTop: Boolean) {
        if (!mouseModeActive || mouseLongPressHoldActive || mouseQuickScrollJob?.isActive == true) return
        val point = pointerManger.pointerCenter()
        mouseQuickScrollJob = context.launch {
            try {
                val handled = withContext(Dispatchers.Main) {
                    scrollToEdge(point.x.toInt(), point.y.toInt(), toTop)
                }
                if (!handled) {
                    flingToEdge(toTop)
                }
            } finally {
                mouseQuickScrollJob = null
            }
        }
    }

    /**
     * Best-effort "jump to edge" using the accessibility tree. ACTION_SCROLL_TO_POSITION is
     * exact for RecyclerView/ListView; repeated scroll actions are the fallback for containers
     * that only expose scrollable=true.
     */
    private fun scrollToEdge(x: Int, y: Int, toTop: Boolean): Boolean {
        val root = context.rootInActiveWindow ?: return false
        if (root.packageName == context.packageName) {
            root.recycle()
            return false
        }
        val scrollable = findScrollableNodeAt(root, x, y)
        if (scrollable == null) {
            root.recycle()
            return false
        }
        var handled = false
        val rowCount = scrollable.collectionInfo?.rowCount ?: 0
        if (rowCount > 0) {
            val arguments = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT,
                    if (toTop) 0 else rowCount - 1,
                )
            }
            handled = scrollable.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                arguments,
            )
        }
        if (!handled) {
            val action = if (toTop) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            for (i in 0 until MOUSE_QUICK_SCROLL_STEPS) {
                if (!scrollable.performAction(action)) break
                handled = true
            }
        }
        scrollable.recycle()
        if (scrollable !== root) root.recycle()
        return handled
    }

    private fun findScrollableNodeAt(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        // Prefer the deepest scrollable under the pointer.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNodeAt(child, x, y)
            if (found != null) return found
            child.recycle()
        }
        return if (node.isScrollable) node else null
    }

    private suspend fun flingToEdge(toTop: Boolean) {
        val screenWidth = pointerFloatingView.width
        val screenHeight = pointerFloatingView.height
        if (screenWidth <= 0 || screenHeight <= 0) return
        val x = screenWidth / 2f
        val startY = screenHeight * if (toTop) 0.30f else 0.70f
        val endY = screenHeight * if (toTop) 0.75f else 0.25f
        repeat(3) {
            context.performSwipeAsync(
                x,
                startY,
                x,
                endY,
                duration = 180L,
                useDecelerationCurve = false,
            )
            delay(80)
        }
    }

    // Trail View class to draw the pointer's trail
    inner class TrailView(context: Context) : View(context) {
        var enable = false
        private val MAX_TRAIL_POINTS = 10
        private val trailPoints = LinkedList<Pair<Float, Float>>()
        private val trailPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = DynamicColorUtils.primaryFixedColor
        }

        // Trail copies of the pointer artwork, so the trail keeps the configured shape.
        private var shapeDrawable: Drawable? = null
        private var shapeBounds = Rect()

        fun addPoint(x: Float, y: Float) {
            if (enable) {
                trailPoints.add(Pair(x, y))
                if (trailPoints.size > MAX_TRAIL_POINTS) {
                    trailPoints.removeFirst()
                }
                invalidate()
            }
        }

        suspend fun updateTrailConfig() {
            // The trail setting only decides whether trailing copies are drawn. It must not
            // touch the pointer icon: doing so used to clear the dot whenever the trail was on,
            // which silently disabled every shape and size setting in that mode.
            enable = context.settingsStore.readKey(KEY_SHOW_POINTER_TRAIL, false)
            if (!enable) clearTrail()
            withContext(Dispatchers.Main) {
                refreshTheme()
            }
        }

        fun refreshTheme() {
            trailPaint.color = pointerManger.getPointerColor()
            // Re-create the stamp so it matches the shape and colour currently in use.
            shapeDrawable = pointerManger.createTrailStencil()
            invalidate()
        }

        fun clearTrail() {
            trailPoints.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (trailPoints.size < 2) return

            val stencil = shapeDrawable
            trailPoints.forEachIndexed { index, point ->
                val ratio = index.toFloat() / trailPoints.size
                val alpha = (255 * ratio).toInt().coerceIn(0, 255)
                // The trailing copies shrink along the tail and fade towards the oldest point.
                val size = (TRAIL_STENCIL_MIN_DP + TRAIL_STENCIL_GROWTH_DP * ratio).dp2px
                val half = size / 2f

                if (stencil != null) {
                    shapeBounds.set(
                        (point.first - half).toInt(),
                        (point.second - half).toInt(),
                        (point.first + half).toInt(),
                        (point.second + half).toInt()
                    )
                    stencil.alpha = alpha
                    stencil.bounds = shapeBounds
                    stencil.draw(canvas)
                } else {
                    trailPaint.alpha = alpha
                    canvas.drawCircle(point.first, point.second, half, trailPaint)
                }
            }
        }
    }

    inner class PointerManger() {
        private val view = GestureActionPointerViewBinding.inflate(LayoutInflater.from(context)).root
        private val pointerIcon: ImageFilterView = view.findViewById(R.id.pointer)
        private var pointerColor: Int = 0
        private var pointerShape: String = POINTER_SHAPE_DOT
        private var pointerSizeDp: Int = POINTER_SIZE_DEFAULT_DP
        private var pointerClickFeedback: Boolean = true

        var touchViewX = 0f
        var touchViewY = 0f
        var isDragMode = false
        var mouseMode = false
            private set
        private var mouseLastX = 0f
        private var mouseLastY = 0f
        private var mouseDownX = 0f
        private var mouseDownY = 0f
        private var mouseMoved = false
        private var centerPointerPosted = false
        private val mouseTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        /** True only once the drag selection box is actually being drawn (not mere drag mode). */
        val isDragSelectionStarted: Boolean
            get() = hasStartedDragSelection
        /** Set when the pointer is leaving because of a tap, so hide plays the tap exit. */
        var tapExitPending = false
        /** True once the icon's layout or backplate has been changed away from the stock XML. */
        private var geometryCustomized = false
        /** Size the stock geometry was last applied at, so size-only changes still re-apply. */
        private var appliedSizeDp = -1
        private var isHoverLongPressActive = false
        private var hoverLongPressJob: Job? = null
        private var pendingHoverLongPress = false
        private var pendingHoverPoint: Point? = null
        private var isLongPressRunning = false
        private var potentialDragSelectRect: Rect? = null
        private var potentialDragSelectJob: Job? = null
        private var potentialDragSelectSourceRect: Rect? = null
        private var pendingPotentialDragSelectRect: Rect? = null
        private var hasStartedDragSelection = false
        private var lastPotentialThinRect: Rect? = null
        private val moveTouchHandler = MoveTouchHandler()
        private var menuMappingOffset = Point(0f, 0f)
        private var menuCoordinateMapping: PointerCoordinateMapping? = null
        val viewPositionMonitor = ViewPositionMonitor()

        fun resetMenuMapping() {
            menuMappingOffset = Point(0f, 0f)
            menuCoordinateMapping = null
        }

        fun isMenuCoordinateSpaceCurrent(): Boolean = enable && pointerVisibleRequested &&
            menuCoordinateMapping != null && menuCoordinateMapping == moveTouchHandler.coordinateMapping()

        fun previewMenuSelection(anchor: Point?) {
            if (anchor == null) {
                highlightRectView.hidePotentialRect()
                return
            }
            val end = moveTouchHandler.computePosition()
            highlightRectView.showPotentialRect(Rect(minOf(anchor.x, end.x).toInt(), minOf(anchor.y, end.y).toInt(),
                maxOf(anchor.x, end.x).toInt(), maxOf(anchor.y, end.y).toInt()))
        }

        fun beginMenuSelection(anchor: Point) {
            if (!isMenuCoordinateSpaceCurrent()) return
            enterDragMode(anchor, moveTouchHandler.computePosition())
        }

        private fun placePointer(point: Point) {
            view.x = point.x - view.width / 2f
            view.y = point.y - view.height / 2f
        }

        fun rebaseMenuPointer(anchor: Point) {
            val mapped = moveTouchHandler.computePosition()
            menuMappingOffset = Point(menuMappingOffset.x + anchor.x - mapped.x, menuMappingOffset.y + anchor.y - mapped.y)
            placePointer(anchor)
        }

        /** Snapshot for the link radar; null whenever the icon is invisible or not laid out. */
        fun currentPointerSnapshot(): PointerSnapshot? {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f || view.width <= 0) return null
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return PointerSnapshot(
                location[0] + view.width / 2f,
                location[1] + view.height / 2f,
                minOf(view.width, view.height) / 2f
            )
        }

        init {
            val layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            )

            // Constrain all four sides to parent container
            layoutParams.topToTop = LayoutParams.PARENT_ID
            layoutParams.bottomToBottom = LayoutParams.PARENT_ID
            layoutParams.startToStart = LayoutParams.PARENT_ID
            layoutParams.endToEnd = LayoutParams.PARENT_ID

            pointerFloatingView.addView(view, layoutParams)
        }

        fun initPosition() {
            view.animate().cancel()
            view.x = -100f
            view.y = -100f
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            // A pulse interrupted mid-flight would otherwise leave the icon scaled down.
            pointerIcon.animate().cancel()
            pointerIcon.scaleX = 1f
            pointerIcon.scaleY = 1f
            tapExitPending = false
            trailView.clearTrail()
        }

        fun showPointer() {
            view.animate().cancel()
            pointerIcon.animate().cancel()
            pointerIcon.scaleX = 1f
            pointerIcon.scaleY = 1f
            view.alpha = 0f
            view.scaleX = POINTER_ANIMATION_START_SCALE
            view.scaleY = POINTER_ANIMATION_START_SCALE
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(POINTER_SHOW_ANIMATION_DURATION)
                .start()
        }

        fun hidePointer(onEnd: () -> Unit) {
            view.animate().cancel()
            pointerIcon.animate().cancel()
            val tapExit = tapExitPending
            tapExitPending = false
            if (view.visibility != View.VISIBLE) {
                onEnd()
                return
            }
            if (tapExit && isClickFeedbackEnabled()) {
                // One animator chain on the wrapper: press in, spring back, then shrink and
                // fade away. The dot and its ring share this view, so a single chain keeps
                // them locked together for the whole exit.
                view.animate()
                    .scaleX(POINTER_CLICK_PRESS_SCALE)
                    .scaleY(POINTER_CLICK_PRESS_SCALE)
                    .setDuration(POINTER_CLICK_PULSE_SHRINK_DURATION)
                    .withEndAction {
                        view.animate()
                            .scaleX(POINTER_CLICK_OVERSHOOT_SCALE)
                            .scaleY(POINTER_CLICK_OVERSHOOT_SCALE)
                            .setDuration(POINTER_CLICK_PULSE_RETURN_DURATION)
                            .withEndAction {
                                view.animate()
                                    .alpha(0f)
                                    .scaleX(POINTER_EXIT_END_SCALE)
                                    .scaleY(POINTER_EXIT_END_SCALE)
                                    .setDuration(POINTER_EXIT_DURATION)
                                    .withEndAction {
                                        view.visibility = View.INVISIBLE
                                        view.alpha = 1f
                                        view.scaleX = 1f
                                        view.scaleY = 1f
                                        onEnd()
                                    }
                                    .start()
                            }
                            .start()
                    }
                    .start()
                return
            }
            view.animate()
                .alpha(0f)
                .scaleX(POINTER_ANIMATION_START_SCALE)
                .scaleY(POINTER_ANIMATION_START_SCALE)
                .setDuration(POINTER_HIDE_ANIMATION_DURATION)
                .withEndAction {
                    view.visibility = View.INVISIBLE
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    onEnd()
                }
                .start()
        }

        fun onTouchEvent(event: MotionEvent, fromTouchView: Boolean = false) {
            if (fromTouchView && mouseMode) {
                handleMouseTouch(event)
                return
            }
            if (mouseMode) {
                // Mouse mode owns the session. Events forwarded from gesture triggers must not
                // fall through to the normal ACTION_UP path, which sets enable=false.
                if (fromTouchView) handleMouseTouch(event)
                return
            }
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    hoverMenu.reset()
                    resetMenuMapping()
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    if (enable && fromTouchView && touchView.alpha == 1f) {
                        touchView.alpha = 0f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    if (enable) {
                        moveTouchHandler.handleActionMove(event)
                    }
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    if (enable) {
                        moveTouchHandler.handleActionUp(event)
                    }
                    enable = false
                }
            }
        }

        fun startMouseMode() {
            mouseMode = true
            mouseMoved = false
            centerPointerOnScreen()
        }

        /**
         * Mouse mode is relative: without an explicit starting point the pointer starts at
         * (0, 0) and the first movement drags it in from the top-left corner. Put it at the
         * visual screen center instead. The icon may not be measured yet when mouse mode is
         * entered, so retry once after layout.
         */
        private fun centerPointerOnScreen() {
            val screenWidth = pointerFloatingView.width
            val screenHeight = pointerFloatingView.height
            if (screenWidth <= 0 || screenHeight <= 0 || view.width <= 0 || view.height <= 0) {
                if (!centerPointerPosted) {
                    centerPointerPosted = true
                    pointerFloatingView.post {
                        centerPointerPosted = false
                        if (mouseMode) centerPointerOnScreen()
                    }
                }
                return
            }
            placePointer(Point(screenWidth / 2f, screenHeight / 2f))
        }

        fun stopMouseMode() {
            mouseMode = false
            mouseMoved = false
        }

        fun pointerCenter(): Point = Point(
            view.x + view.width / 2f,
            view.y + view.height / 2f,
        )

        private fun handleMouseTouch(event: MotionEvent) {
            if (!mouseMode) return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mouseLastX = event.rawX
                    mouseLastY = event.rawY
                    mouseDownX = event.rawX
                    mouseDownY = event.rawY
                    mouseMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - mouseLastX
                    val dy = event.rawY - mouseLastY
                    mouseLastX = event.rawX
                    mouseLastY = event.rawY
                    if (distance(mouseDownX, mouseDownY, event.rawX, event.rawY) > mouseTouchSlop) {
                        mouseMoved = true
                    }
                    // Match the old pointer's absolute mapping speed: one pixel of finger
                    // travel on the pad moves the pointer by screen/pad pixels.
                    movePointerBy(
                        dx * mouseScaleX(),
                        dy * mouseScaleY(),
                    )
                }
                MotionEvent.ACTION_CANCEL -> {}
                MotionEvent.ACTION_UP -> {
                    if (!mouseMoved) {
                        handleMouseTap()
                    }
                    mouseMoved = false
                }
            }
        }

        private fun mouseScaleX(): Float {
            val screenWidth = pointerFloatingView.width
            val padWidth = touchView.width
            return if (screenWidth > 0 && padWidth > 0) {
                screenWidth.toFloat() / padWidth
            } else {
                2.8f
            }
        }

        private fun mouseScaleY(): Float {
            val screenHeight = pointerFloatingView.height
            val padHeight = touchView.height
            return if (screenHeight > 0 && padHeight > 0) {
                screenHeight.toFloat() / padHeight
            } else {
                2.8f
            }
        }

        private fun movePointerBy(dx: Float, dy: Float) {
            val screenWidth = pointerFloatingView.width.coerceAtLeast(1)
            val screenHeight = pointerFloatingView.height.coerceAtLeast(1)
            val halfWidth = view.width / 2f
            val halfHeight = view.height / 2f
            val center = pointerCenter()
            val target = Point(
                (center.x + dx).coerceIn(halfWidth, screenWidth - halfWidth),
                (center.y + dy).coerceIn(halfHeight, screenHeight - halfHeight),
            )
            placePointer(target)
            if (mouseLongPressHoldActive) {
                pointerHoldSession.moveTo(target.x, target.y)
            }
        }

        private fun handleMouseTap() {
            if (mouseLongPressHoldActive) return
            pulseClickFeedback()
            val point = pointerCenter()
            // Match the normal pointer's tap path: click immediately after release, no
            // double-tap window. Double-click and long-press have their own explicit buttons.
            context.launch {
                delay(20)
                if (mouseMode) {
                    performPointerClick(point.x, point.y)
                }
            }
        }

        suspend fun updatePointerColorConfig() = withContext(Dispatchers.IO) {
            pointerColor = context.settingsStore.readKey(KEY_POINTER_COLOR, 0)
        }

        suspend fun updatePointerAppearanceConfig() = withContext(Dispatchers.IO) {
            // Fall back to the stock dot if the stored key is unknown or stale; otherwise a
            // bad value would render the dot artwork without its ring backplate.
            pointerShape = context.settingsStore.readKey(KEY_POINTER_SHAPE, POINTER_SHAPE_DOT)
                .takeIf { it in POINTER_SHAPES } ?: POINTER_SHAPE_DOT
            pointerSizeDp = context.settingsStore
                .readKey(KEY_POINTER_SIZE, POINTER_SIZE_DEFAULT_DP)
                .coerceIn(POINTER_SIZE_MIN_DP, POINTER_SIZE_MAX_DP)
            pointerClickFeedback = context.settingsStore
                .readKey(KEY_POINTER_CLICK_FEEDBACK, true)
        }

        fun isClickFeedbackEnabled(): Boolean = pointerClickFeedback

        /** Brief scale pulse used by simulated-mouse clicks while the pointer stays visible. */
        fun pulseClickFeedback() {
            if (!pointerClickFeedback || view.visibility != View.VISIBLE) return
            view.animate().cancel()
            view.animate()
                .scaleX(POINTER_CLICK_PRESS_SCALE)
                .scaleY(POINTER_CLICK_PRESS_SCALE)
                .setDuration(POINTER_CLICK_PULSE_SHRINK_DURATION)
                .withEndAction {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(POINTER_CLICK_PULSE_RETURN_DURATION)
                        .start()
                }
                .start()
        }

        fun getPointerColor(): Int {
            return if (pointerColor == 0) DynamicColorUtils.primaryColor else pointerColor
        }

        private fun applyPointerTheme(target: ImageFilterView) {
            val color = getPointerColor()
            target.imageTintList = ColorStateList.valueOf(color)
            target.backgroundTintList = ColorStateList.valueOf(color)
        }

        private fun pointerShapeRes(): Int = when (pointerShape) {
            POINTER_SHAPE_SMALL_DOT -> R.drawable.pointer_shape_small_dot
            POINTER_SHAPE_CROSS -> R.drawable.pointer_shape_cross
            POINTER_SHAPE_ARROW -> R.drawable.pointer_shape_arrow
            // The default dot is the original artwork, unchanged.
            else -> R.drawable.baseline_circle_24
        }

        /**
         * Restores the ring backplate geometry. Drag mode and the cut-out shapes mutate the
         * icon's size, padding and background, so every path back to the dot resets them.
         * The last applied size is remembered so a pure size change still re-applies while
         * repeated shows do not re-inflate the background.
         */
        private fun restoreStockGeometry() {
            if (!geometryCustomized && appliedSizeDp == pointerSizeDp) return
            val sizePx = pointerSizeDp.dp2px.toInt()
            val insetPx = (sizePx * POINTER_DOT_INSET_RATIO).toInt()
            pointerIcon.layoutParams = pointerIcon.layoutParams.apply {
                width = sizePx
                height = sizePx
            }
            pointerIcon.setPadding(insetPx, insetPx, insetPx, insetPx)
            pointerIcon.setBackgroundResource(R.drawable.pointer)
            geometryCustomized = false
            appliedSizeDp = pointerSizeDp
        }

        /** Applies the configured shape and size. Safe to call on every show. */
        fun applyPointerAppearance() {
            if (pointerShape == POINTER_SHAPE_DOT) {
                // Original artwork: ring backplate plus baseline_circle_24, sized by the slider.
                restoreStockGeometry()
                pointerIcon.setImageResource(R.drawable.baseline_circle_24)
                applyPointerTheme(pointerIcon)
                return
            }
            pointerIcon.apply {
                setImageResource(pointerShapeRes())
                val sizePx = pointerSizeDp.dp2px.toInt()
                layoutParams = layoutParams.apply {
                    width = sizePx
                    height = sizePx
                }
                val inset = (sizePx * POINTER_ICON_INSET_RATIO).toInt()
                setPadding(inset, inset, inset, inset)
                // Cut-out shapes have no backplate: drop both the drawable and its tint so
                // nothing is painted behind the icon.
                backgroundTintList = null
                setBackgroundResource(0)
                imageTintList = ColorStateList.valueOf(getPointerColor())
                geometryCustomized = true
                requestLayout()
            }
        }

        fun refreshTheme() {
            val color = getPointerColor()
            pointerIcon.imageTintList = ColorStateList.valueOf(color)
            // Only the dot owns the ring backplate. Tinting the background of a cut-out shape
            // makes ImageFilterView paint a solid disc behind it.
            if (pointerShape == POINTER_SHAPE_DOT) {
                pointerIcon.backgroundTintList = ColorStateList.valueOf(color)
            } else {
                pointerIcon.backgroundTintList = null
            }
        }

        /**
         * A standalone copy of the current pointer artwork, tinted with the pointer colour.
         * The trail draws this at each trailing point so it follows the configured shape
         * instead of always being a plain circle.
         */
        fun createTrailStencil(): Drawable? {
            val drawable = runCatching {
                AppCompatResources.getDrawable(context, pointerShapeRes())?.mutate()
            }.getOrNull() ?: return null
            val color = getPointerColor()
            drawable.setTint(color)
            return drawable
        }

        fun enterDragMode(
            startPoint: Point = moveTouchHandler.computePosition(),
            endPoint: Point = startPoint,
        ) {
            isDragMode = true
            clearPotentialDragSelection()
            hasStartedDragSelection = false
            context.gestureManager?.setInteractionMode(InteractionMode.POINTER_DRAG_SELECT)
            context.launch(Dispatchers.IO) {
                MlKitOcr.preload()
                SmartSegmenter.prewarm(context.applicationContext)
            }
            VibratorUtils.vibrator(context, true)
            trailView.enable = false
            trailView.clearTrail()
            highlightRectView.hidePotentialRect()
            highlightRectView.showRect(startPoint, endPoint)
            placePointer(endPoint)
            // Icon size changes must not become selection-coordinate changes.
            view.doOnNextLayout {
                if (isDragMode && enable && pointerVisibleRequested) placePointer(moveTouchHandler.computePosition())
            }
            pointerIcon.apply {
                setImageResource(R.drawable.baseline_add_24)
                // The crosshair has no backplate, so only the image is tinted.
                imageTintList = ColorStateList.valueOf(getPointerColor())
                setBackgroundResource(0)
                backgroundTintList = null
                setPadding(0, 0, 0, 0)
                // The drag-select crosshair keeps a fixed size regardless of the pointer setting.
                val fixed = POINTER_SIZE_DEFAULT_DP.dp2px.toInt()
                layoutParams = layoutParams.apply {
                    width = fixed
                    height = fixed
                }
                requestLayout()
            }
            geometryCustomized = true
            if (endPoint == startPoint) {
                loadPotentialDragSelectRect(startPoint)
            } else {
                updateDragSelection(endPoint)
            }
        }

        fun exitDragMode() {
            isDragMode = false
            clearPotentialDragSelection()
            hasStartedDragSelection = false
            context.gestureManager?.setInteractionMode(InteractionMode.NORMAL)
            context.postOnMain {
                // applyPointerAppearance restores the backplate, tint and size for the
                // configured shape, so the intermediate theme call is not needed here.
                applyPointerAppearance()
                trailView.updateTrailConfig()
                highlightRectView.hideRect()
                highlightRectView.hidePotentialRect()
            }
        }

        private fun clearPotentialDragSelection() {
            potentialDragSelectJob?.cancel()
            potentialDragSelectJob = null
            potentialDragSelectRect = null
            potentialDragSelectSourceRect = null
            pendingPotentialDragSelectRect = null
            lastPotentialThinRect = null
        }

        private fun updateDragSelection(targetPoint: Point) {
            val useDragSelection = highlightRectView.shouldUseDragSelection(targetPoint)
            if (useDragSelection) {
                if (!hasStartedDragSelection) {
                    hasStartedDragSelection = true
                    clearPotentialDragSelection()
                    highlightRectView.hidePotentialRect()
                }
            } else {
                if (hasStartedDragSelection) {
                    hasStartedDragSelection = false
                    lastPotentialThinRect = null
                }
                updatePotentialDragSelectRect(
                    highlightRectView.currentSelectionRect(targetPoint),
                    targetPoint
                )
            }
            highlightRectView.updateRect(targetPoint)
            if (highlightRectView.isBackToOrigin) {
                exitDragMode()
                if (hoverMenuEnabled && hoverMenu.ownsInteraction) hoverMenu.dismiss()
            }
        }

        private fun loadPotentialDragSelectRect(startPoint: Point) {
            val centerX = startPoint.x.toInt()
            val centerY = startPoint.y.toInt()
            val pointRect = Rect(
                centerX - 1,
                centerY - 1,
                centerX + 1,
                centerY + 1
            )
            val sourceRect = Rect(centerX, centerY, centerX, centerY)
            potentialDragSelectJob?.cancel()
            potentialDragSelectSourceRect = Rect(sourceRect)
            potentialDragSelectJob = context.launch(Dispatchers.Default) {
                val targetRect = resolveScreenContentRect(context, pointRect)
                if (potentialDragSelectSourceRect != sourceRect) return@launch
                potentialDragSelectRect = targetRect
                withContext(Dispatchers.Main) {
                    if (!isDragMode || hasStartedDragSelection) return@withContext
                    if (targetRect == null) {
                        highlightRectView.hidePotentialRect()
                    } else {
                        highlightRectView.showPotentialRect(targetRect)
                    }
                }
            }
        }

        private fun updatePotentialDragSelectRect(selectionRect: Rect, endPoint: Point) {
            if (highlightRectView.shouldUseDragSelection(endPoint)) {
                return
            }
            if (selectionRect == lastPotentialThinRect) {
                return
            }
            lastPotentialThinRect = Rect(selectionRect)
            pendingPotentialDragSelectRect = Rect(selectionRect)
            if (potentialDragSelectJob?.isActive == true) {
                return
            }
            potentialDragSelectJob = context.launch(Dispatchers.Default) {
                while (isDragMode && !hasStartedDragSelection) {
                    val sourceRect = pendingPotentialDragSelectRect ?: break
                    pendingPotentialDragSelectRect = null
                    val targetRect = resolveScreenContentRect(context, sourceRect, allowRegion = false)
                    potentialDragSelectSourceRect = Rect(sourceRect)
                    potentialDragSelectRect = targetRect
                    withContext(Dispatchers.Main) {
                        if (!isDragMode || hasStartedDragSelection) return@withContext
                        if (targetRect == null) {
                            highlightRectView.hidePotentialRect()
                        } else {
                            highlightRectView.showPotentialRect(targetRect)
                        }
                    }
                }
            }
        }

    private fun showHoverLongPressHint() {
        if (isDragMode || isHoverLongPressActive) return
        isHoverLongPressActive = true
        pendingHoverLongPress = true
    }

    private fun resetHoverLongPressHint() {
        if (!isHoverLongPressActive) return
        isHoverLongPressActive = false
        pendingHoverLongPress = false
        pendingHoverPoint = null
    }

        private fun prepareHoverLongPress(point: Point) {
            if (isDragMode) return
            showHoverLongPressHint()
            pendingHoverLongPress = true
            pendingHoverPoint = point
        }

        private inner class MoveTouchHandler() {

            fun coordinateMapping(): PointerCoordinateMapping? {
                val screenWidth = pointerFloatingView.width
                val screenHeight = pointerFloatingView.height
                val touchWidth = touchView.width
                val touchHeight = touchView.height
                if (screenWidth <= 0 || screenHeight <= 0 || touchWidth <= 0 || touchHeight <= 0) return null
                val origin = IntArray(2)
                touchView.getLocationOnScreen(origin)
                return PointerCoordinateMapping(screenWidth, screenHeight, origin[0], origin[1], touchWidth, touchHeight)
            }

            fun computePosition(): Point = coordinateMapping()?.map(Point(touchViewX, touchViewY), menuMappingOffset)
                ?: Point((view.x + view.width / 2).coerceAtLeast(0f), (view.y + view.height / 2).coerceAtLeast(0f))

            fun handleActionMove(event: MotionEvent) {
                if (hoverMenuEnabled && hoverMenu.isShowing) {
                    hoverMenu.move(touchViewY)
                    return
                }
                val targetPoint = computePosition()
                placePointer(targetPoint)

                if (isDragMode) updateDragSelection(targetPoint)

                // Add a point to the trail at the center of the pointer
                trailView.addPoint(targetPoint.x, targetPoint.y)
            }

            fun handleActionUp(event: MotionEvent) {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (hoverMenuEnabled) hoverMenu.cancel()
                    hoverLongPressJob?.cancel()
                    hoverLongPressJob = null
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    resetHoverLongPressHint()
                    if (isDragMode) exitDragMode()
                    return
                }
                if (hoverMenuEnabled && hoverMenu.isShowing) hoverMenu.move(touchViewY)
                if (hoverMenuEnabled && hoverMenu.release(triggerSide)) {
                    resetHoverLongPressHint()
                    trailView.clearTrail()
                    return
                }
                if (isDragMode) {
                    updateDragSelection(computePosition())
                    if (!isDragMode) return
                }
                if (!isLongPressRunning) {
                    hoverLongPressJob?.cancel()
                    hoverLongPressJob = null
                }
                // A radar target keeps the same release action even after a long hover.
                // Actual text selection still wins; cancelled gestures returned above.
                if (!(hoverMenuEnabled && hoverMenu.ownsInteraction) && !isPointerDragSelecting && onPointerTapInterceptor?.invoke() == true) {
                    resetHoverLongPressHint()
                    if (isDragMode) exitDragMode()
                    trailView.clearTrail()
                    return
                }
                if (isDragMode) {
                    resetHoverLongPressHint()
                    val currentSelectionRect = highlightRectView.currentSelectionRect(computePosition())
                    val previewSelectionRect = potentialDragSelectRect
                        ?.takeIf { potentialDragSelectSourceRect == currentSelectionRect }
                        ?.let { Rect(it) }
                    clearPotentialDragSelection()
                    highlightRectView.hidePotentialRect()
                    view.visibility = View.INVISIBLE
                    context.launch {
                        val resolvedSelectionRect = if (!hasStartedDragSelection) {
                            previewSelectionRect ?: resolveScreenContentRect(context, currentSelectionRect, allowRegion = false)
                        } else {
                            null
                        }
                        val newRect = if (resolvedSelectionRect != null) {
                            resolvedSelectionRect
                        } else currentSelectionRect.let {
                            Rect(
                                min(it.left, it.right),
                                min(it.top, it.bottom),
                                max(it.left, it.right),
                                max(it.top, it.bottom)
                            )
                        }
                        val effectiveSide = triggerSide
                            ?: (if (newRect.centerX() < context.resources.displayMetrics.widthPixels / 2f) TriggerSide.LEFT else TriggerSide.RIGHT)
                        produceInspireEvent(
                            InspireEvent(rect = newRect, side = effectiveSide)
                        )
                    }
                } else if (pendingHoverLongPress) {
                    val targetPoint = pendingHoverPoint ?: computePosition()
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    hoverLongPressJob = context.launch {
                        isLongPressRunning = true
                        try {
                            // Long press deliberately plays no feedback at all.
                            performPointerClick(targetPoint.x, targetPoint.y, 600L)
                        } finally {
                            isLongPressRunning = false
                            withContext(Dispatchers.Main) {
                                resetHoverLongPressHint()
                            }
                            hoverLongPressJob = null
                        }
                    }
                    trailView.clearTrail()
                } else {
                    val targetPoint = computePosition()
                    trailView.clearTrail()
                    Log.d(
                        "PointerFloating",
                        "handleActionUp: ${targetPoint.x}, ${targetPoint.y}"
                    )
                    // The exit animation is driven by hidePointer on the wrapper view, so the
                    // icon and its ring can never drift apart. This only flags the tap.
                    tapExitPending = true
                    context.launch {
                        delay(20)
                        performPointerClick(targetPoint.x, targetPoint.y)
                    }
                }
            }
        }

        inner class ViewPositionMonitor() {
            var enable = false
                private set

            // Thread-safe point history list
            private val historyPoints = Collections.synchronizedList(LinkedList<Point>())
            private var monitorJob: Job? = null
            private var checkJob: Job? = null

            // Hold weak reference to avoid memory leaks
            private val viewRef = WeakReference(view)

            // Coroutine exception handler
            private val errorHandler = CoroutineExceptionHandler { _, exception ->
                Log.e("ViewPositionMonitor", "Coroutine error: ${exception.message}", exception)
                stop()
            }

            private fun onStableChange(isStable: Boolean, stablePoint: Point) {
                // Dispatch UI operations to the main thread
                context.postOnMain {
                    if (!enable || !pointerVisibleRequested || mouseModeActive) return@postOnMain
                    if (hoverMenuEnabled) {
                        if (isStable && !isDragMode && hoverMenu.canShow) {
                            resetHoverLongPressHint()
                            val mapping = moveTouchHandler.coordinateMapping() ?: return@postOnMain
                            menuCoordinateMapping = mapping
                            val anchor = mapping.map(Point(touchViewX, touchViewY), menuMappingOffset)
                            placePointer(anchor)
                            hoverMenu.show(anchor, minOf(view.width, view.height) / 2f, touchViewY)
                        }
                        return@postOnMain
                    }
                    if (isStable && !isDragMode) {
                        // Drag selection wins once the pointer settles, even on a link: entering
                        // it makes the radar stand down through the pointer snapshot instead.
                        if (dragModeEnabled) {
                            enterDragMode()
                        } else if (onPointerLinkTargeted?.invoke() != true) {
                            prepareHoverLongPress(stablePoint)
                        }
                    }
                }
            }

            private fun analyzePosition() {
                synchronized(historyPoints) {
                    if (historyPoints.size < 2) return

                    // Calculate maximum displacement across recent samples
                    var maxDistance = 0f
                    val referencePosition = historyPoints.first()

                    for (position in historyPoints) {
                        val distance = distance(
                            referencePosition.x, referencePosition.y,
                            position.x, position.y
                        )
                        maxDistance = max(maxDistance, distance)
                    }

                    val isPositionStable = maxDistance < 10.dp2px

                    // Update checkJob atomically
                    val currentCheckJob = checkJob
                    if (isPositionStable && !isDragMode && (!hoverMenuEnabled || hoverMenu.canShow)) {
                        if (currentCheckJob == null || !currentCheckJob.isActive) {
                            // Cancel previous check job and schedule a new check
                            currentCheckJob?.cancel()
                            checkJob = context.launch(Dispatchers.Default + errorHandler) {
                                try {
                                    Log.d("ViewPositionMonitor", "Starting stability check")
                                    val currentPoint = historyPoints.lastOrNull() ?: return@launch

                                    try {
                                        withTimeout(500) {  // Timeout guard against stalled execution
                                            delay(400)
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        Log.d("ViewPositionMonitor", "Stability check timed out")
                                        return@launch
                                    }

                                    // Verify coroutine is still active
                                    ensureActive()

                                    // Read current view coordinates to avoid stale data
                                    val newView = viewRef.get() ?: return@launch
                                    val distance = distance(
                                        currentPoint.x,
                                        currentPoint.y,
                                        newView.x + newView.width / 2,
                                        newView.y + newView.height / 2
                                    )

                                    val isStable = distance < 5.dp2px
                                    if (!isStable && !dragModeEnabled) {
                                        context.postOnMain {
                                            if (!isLongPressRunning) {
                                                hoverLongPressJob?.cancel()
                                                hoverLongPressJob = null
                                            }
                                            resetHoverLongPressHint()
                                        }
                                    }
                                    onStableChange(
                                        isStable,
                                        Point(
                                            newView.x + newView.width / 2,
                                            newView.y + newView.height / 2
                                        )
                                    )
                                } finally {
                                    // Reset job state upon completion
                                    if (checkJob?.isActive == true && checkJob == currentCheckJob) {
                                        checkJob = null
                                    }
                                }
                            }
                        }
                    } else {
                        currentCheckJob?.cancel()
                        checkJob = null
                        if (!dragModeEnabled) {
                            context.postOnMain {
                                if (!isLongPressRunning) {
                                    hoverLongPressJob?.cancel()
                                    hoverLongPressJob = null
                                }
                                resetHoverLongPressHint()
                            }
                        }
                    }
                }
            }

            private var dragModeEnabled = false

            fun start() {
                context.launch(Dispatchers.Default + errorHandler) {
                    try {
                        // Stop existing tasks and ensure clean state
                        // Cancel all background jobs
                        monitorJob?.cancel()
                        checkJob?.cancel()
                        // Clear historical points
                        synchronized(historyPoints) {
                            historyPoints.clear()
                        }

                        dragModeEnabled =
                            context.settingsStore.readKey(KEY_ENABLE_POINTER_DRAG_MODE, false)
                        enable = true

                        // Start new monitoring loop
                        monitorJob = context.launch(Dispatchers.Default + errorHandler) {
                            try {
                                while (isActive) {
                                    delay(30)

                                    // Exit if view reference has been garbage-collected
                                    val currentView = viewRef.get()
                                    if (currentView == null) {
                                        Log.d(
                                            "ViewPositionMonitor",
                                            "View reference lost, stopping monitor"
                                        )
                                        cancel()
                                        break
                                    }

                                    // Safely append position to history list
                                    val x: Float
                                    val y: Float

                                    // Read view coordinates on main thread
                                    withContext(Dispatchers.Main) {
                                        x = currentView.x + currentView.width / 2
                                        y = currentView.y + currentView.height / 2
                                        onPointerPositionChanged?.invoke(
                                            if (pointerVisibleRequested && !isPointerDragMode && !isHoverInteractionActive) currentPointerSnapshot() else null
                                        )
                                        if (onPointerLinkTargeted?.invoke() == true &&
                                            (pendingHoverLongPress || isHoverLongPressActive)) {
                                            resetHoverLongPressHint()
                                        }
                                    }
                                    if (x >= 0 && y >= 0) {
                                        synchronized(historyPoints) {
                                            historyPoints.add(Point(x, y))
                                            if (historyPoints.size > 10) {
                                                historyPoints.removeFirstOrNull()
                                            }
                                        }
                                    }
                                    // Analyze position stability
                                    analyzePosition()
                                }
                            } catch (e: CancellationException) {
                                // Normal cancellation, no action required
                                Log.d("ViewPositionMonitor", "Monitor job cancelled")
                            } catch (e: Exception) {
                                Log.e("ViewPositionMonitor", "Error in monitor job", e)
                                throw e  // Re-throw to error handler
                            }
                        }

                        Log.d("ViewPositionMonitor", "Monitor started")
                    } catch (e: Exception) {
                        Log.e("ViewPositionMonitor", "Failed to start monitor", e)
                        stop()  // Ensure resources are cleaned up
                    }
                }
            }

            fun stop() {
                // Execute stop operations on main thread
                context.postOnMain {
                    try {
                        // Cancel coroutine jobs
                        monitorJob?.cancel()
                        checkJob?.cancel()
                        if (!isLongPressRunning) {
                            hoverLongPressJob?.cancel()
                            hoverLongPressJob = null
                        }
                        resetHoverLongPressHint()
                        enable = false

                        // Reset state
                        monitorJob = null
                        checkJob = null

                        // Clear historical points
                        synchronized(historyPoints) {
                            historyPoints.clear()
                        }

                        Log.d("ViewPositionMonitor", "Monitor stopped")
                    } catch (e: Exception) {
                        Log.e("ViewPositionMonitor", "Error while stopping monitor", e)
                    }
                }
            }

            // Called when the view lifecycle ends
            fun dispose() {
                stop()
                // Clear all references
                historyPoints.clear()
            }
        }
    }

    private suspend fun performPointerClick(
        x: Float,
        y: Float,
        duration: Long = 100L,
        isValid: () -> Boolean = { true },
        clickCount: Int = 1,
    ): Boolean {
        context.setActionViewTouchEnabled(false)
        return try {
            delay(50)
            if (isValid()) {
                withTimeoutOrNull(2500) { context.performClickAsync(x, y, duration, clickCount) } ?: false
            } else false
        } finally {
            withContext(NonCancellable) { context.setActionViewTouchEnabled(true) }
        }
    }
}
