package io.github.lumiagg.gestureevo.gesture.ui

/**
 * Geometry shared by the runtime wheel and the configuration preview.
 *
 * The setup guide used to own this file. Keep only the runtime-independent shape and radius
 * helpers here; guide-specific bounds/fitting helpers were removed with the onboarding flow.
 */

internal data class WheelAdaptiveShape(
    val startAngle: Float,
    val sweepAngle: Float,
    val isSemicircle: Boolean,
    val isUpper: Boolean,
    val quadrant: Int,
)

internal fun quarterWheelOuterRadius(
    itemCount: Int,
    radius: Float,
    diameter: Float,
    gap: Float,
): Pair<Float, Int> {
    var ringRadius = radius
    var remaining = itemCount - 1
    var rings = 0
    repeat(10) {
        if (remaining <= 0) return@repeat
        val ring = Ring(0f, 90f, ringRadius, rings % 2 != 0, diameter, gap)
        remaining -= ring.items.size
        rings++
        ringRadius += diameter * 1.3f
    }
    return (ringRadius + diameter / 2f) to rings
}

internal fun resolveWheelAdaptiveShape(
    isLeft: Boolean,
    verticalPercent: Int,
    cornerThresholdPercent: Float = 25f,
): WheelAdaptiveShape = when {
    verticalPercent < cornerThresholdPercent -> {
        val quadrant = if (isLeft) 3 else 2
        val startAngle = if (isLeft) 270f else 180f
        WheelAdaptiveShape(
            startAngle = startAngle,
            sweepAngle = 90f,
            isSemicircle = false,
            isUpper = true,
            quadrant = quadrant,
        )
    }

    verticalPercent > 100f - cornerThresholdPercent -> {
        val quadrant = if (isLeft) 0 else 1
        val startAngle = if (isLeft) 0f else 90f
        WheelAdaptiveShape(
            startAngle = startAngle,
            sweepAngle = 90f,
            isSemicircle = false,
            isUpper = false,
            quadrant = quadrant,
        )
    }

    else -> {
        val startAngle = if (isLeft) 270f else 90f
        val quadrant = if (isLeft) 3 else 1
        WheelAdaptiveShape(
            startAngle = startAngle,
            sweepAngle = 180f,
            isSemicircle = true,
            isUpper = verticalPercent < 50,
            quadrant = quadrant,
        )
    }
}

internal data class WheelSlot(
    val index: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val centerX: Float = x + size / 2f,
    val centerY: Float = y + size / 2f,
    val isCenterItem: Boolean = false,
    val ringIndex: Int = 0,
)

internal data class FittedWheelLayout(
    val cx: Float,
    val cy: Float,
    val startAngle: Float,
    val sweepAngle: Float,
    val quadrant: Int,
    val isLeft: Boolean,
    val isUpper: Boolean,
    val slots: List<WheelSlot>,
    val ringRadii: List<Float>,
    val maxRadius: Float,
    val scale: Float,
)
