package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.utils.Point

/** Maps a touch-pad point into the full-screen pointer coordinate space. */
internal data class PointerCoordinateMapping(
    val screenWidth: Int,
    val screenHeight: Int,
    val touchLeft: Int,
    val touchTop: Int,
    val touchWidth: Int,
    val touchHeight: Int,
) {
    fun map(input: Point): Point = Point(
        normalize(input.x, touchLeft, touchWidth) * screenWidth,
        normalize(input.y, touchTop, touchHeight) * screenHeight,
    )

    private fun normalize(value: Float, origin: Int, size: Int): Float =
        ((value - origin) / size).coerceIn(0f, 1f)
}
