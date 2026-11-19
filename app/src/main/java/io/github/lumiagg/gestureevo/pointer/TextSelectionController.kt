package io.github.lumiagg.gestureevo.pointer

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.inspire.readAccessibilityCharacterLocations

/**
 * Turns the held long-press into a character-accurate text selection.
 *
 * The system selection handles are drawn by the target app and never appear in the
 * accessibility tree, so dragging them directly is unreliable. Instead we keep the selection
 * node and its per-character screen rectangles, then update the selection with
 * [AccessibilityNodeInfo.ACTION_SET_SELECTION]. The native handles and toolbar follow the
 * accessibility selection automatically.
 */
internal class TextSelectionController(
    private val service: GestureService,
) {
    private var selectionNode: AccessibilityNodeInfo? = null
    private var characterBounds: Array<RectF?>? = null
    private var nativeStart = -1
    private var nativeEnd = -1
    private var initialIndex = -1
    private var anchorIndex = -1
    private var lastUpdateAt = 0L

    fun startAt(point: PointF): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findTextNodeAt(root, point.x, point.y) ?: return false
        val text = node.text?.toString() ?: return false
        if (text.isEmpty()) return false
        val bounds = readAccessibilityCharacterLocations(node, text).bounds ?: return false
        val index = findCharacterIndex(bounds, point)
        if (index < 0) return false

        selectionNode = node
        characterBounds = bounds
        nativeStart = node.textSelectionStart
        nativeEnd = node.textSelectionEnd
        initialIndex = index
        anchorIndex = -1
        lastUpdateAt = 0L
        Log.d(TAG, "text selection ready chars=${bounds.size} anchor=$index")
        return true
    }

    fun updateSelection(point: PointF) {
        val node = selectionNode ?: return
        val bounds = characterBounds ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastUpdateAt < UPDATE_INTERVAL_MS) return
        lastUpdateAt = now
        if (!node.refresh()) return

        val end = findCharacterIndex(bounds, point)
        if (end < 0) return

        if (anchorIndex < 0) {
            // Preserve the word selected by the native long press: dragging right extends from
            // its start, dragging left extends from its end.
            anchorIndex = when {
                nativeStart in 0 until nativeEnd && end >= initialIndex -> nativeStart
                nativeStart in 0 until nativeEnd && end < initialIndex -> nativeEnd
                else -> initialIndex
            }
        }

        val start = minOf(anchorIndex, end)
        val stop = maxOf(anchorIndex, end)
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, stop)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)) {
            Log.w(TAG, "set_selection_failed start=$start end=$stop")
        }
    }

    fun stop() {
        selectionNode = null
        characterBounds = null
        nativeStart = -1
        nativeEnd = -1
        initialIndex = -1
        anchorIndex = -1
        lastUpdateAt = 0L
    }

    private fun findTextNodeAt(
        node: AccessibilityNodeInfo?,
        x: Float,
        y: Float,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x.toInt(), y.toInt())) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTextNodeAt(child, x, y)
            if (found != null) return found
        }
        if (node.packageName == service.packageName) return null
        val text = node.text?.toString() ?: return null
        if (text.isEmpty()) return null
        val supportsCharacters = node.availableExtraData.contains(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        )
        return if (node.isEditable || supportsCharacters) node else null
    }

    private fun findCharacterIndex(bounds: Array<RectF?>, point: PointF): Int {
        bounds.forEachIndexed { index, rect ->
            if (rect != null && rect.contains(point.x, point.y)) return index
        }
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        bounds.forEachIndexed { index, rect ->
            if (rect == null) return@forEachIndexed
            val dx = rect.centerX() - point.x
            val dy = rect.centerY() - point.y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private companion object {
        const val TAG = "TextSelection"
        const val UPDATE_INTERVAL_MS = 24L
    }
}
