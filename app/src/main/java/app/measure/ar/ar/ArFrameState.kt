package app.measure.ar.ar

import androidx.compose.ui.geometry.Offset

/** Why the camera isn't tracking, so the UI can coach the user. */
enum class TrackingIssue { Initializing, ExcessiveMotion, InsufficientLight, InsufficientFeatures, CameraUnavailable }

/**
 * How the reticle circle looks on screen when lying flat on the surface: the symmetric 2x2 matrix
 * M = [[a, b], [b, c]] maps the unit circle to the projected ellipse via sqrt(M). Units are
 * "screen px per nominal px", so identity means a circle facing the camera.
 */
data class ReticleShape(val a: Float = 1f, val b: Float = 0f, val c: Float = 1f) {
    fun lerp(to: ReticleShape, t: Float) =
        ReticleShape(a + (to.a - a) * t, b + (to.b - b) * t, c + (to.c - c) * t)

    companion object {
        val Facing = ReticleShape()
    }
}

/** A measured line already projected into screen pixels for the current frame. */
data class ScreenSegment(
    val start: Offset,
    val end: Offset,
    /** Whether each endpoint is in front of the camera (and so should get a dot). */
    val startVisible: Boolean,
    val endVisible: Boolean,
    val meters: Float,
    /** Part of the group of connected lines the reticle is hovering over (or moving a point of). */
    val highlighted: Boolean = false,
    /** Would be removed by the delete button right now. */
    val selected: Boolean = false,
)

/** A closed loop of lines, projected to screen, with the area it encloses. */
data class ScreenPolygon(
    val points: List<Offset>,
    val center: Offset,
    val squareMeters: Float,
    val highlighted: Boolean,
)

/** Snapshot published by the renderer every frame and drawn by the Compose overlay. */
data class ArFrameState(
    val trackingIssue: TrackingIssue? = TrackingIssue.Initializing,
    /** True when the center of the screen hits a real surface (or snaps to a point). */
    val hasSurface: Boolean = false,
    val snapped: Boolean = false,
    /** Where the reticle should be drawn; null means the screen center. */
    val reticle: Offset? = null,
    /** Orientation of the reticle on the surface it's resting on. */
    val reticleShape: ReticleShape = ReticleShape.Facing,
    val segments: List<ScreenSegment> = emptyList(),
    val polygons: List<ScreenPolygon> = emptyList(),
    /** The line from the first placed point to the reticle while measuring. */
    val live: ScreenSegment? = null,
    /** The first placed point while a measurement is in progress. */
    val pendingPoint: Offset? = null,
    val measuring: Boolean = false,
    /** Sum of all lines connected to the hovered dot or line; null when not hovering any. */
    val hoverTotal: Float? = null,
    /** How many lines make up [hoverTotal]. */
    val hoverCount: Int = 0,
    /** True when the hovered lines form a closed shape, so [hoverTotal] is its perimeter. */
    val hoverClosed: Boolean = false,
    /** True while a placed point is being dragged around with the reticle. */
    val moving: Boolean = false,
    val canUndo: Boolean = false,
    /** Undo would cancel an in-progress measurement or move rather than undo a change. */
    val undoCancels: Boolean = false,
    /** A point is being dragged over another point, so the two can be welded together. */
    val canWeld: Boolean = false,
    /** Holding add would pick up a point: the one under the reticle, else the last one used. */
    val canPickUp: Boolean = false,
    /** Something is under the reticle that the delete button can remove. */
    val canDelete: Boolean = false,
    /** There's anything to clear with a long press of delete. */
    val canClear: Boolean = false,
)
