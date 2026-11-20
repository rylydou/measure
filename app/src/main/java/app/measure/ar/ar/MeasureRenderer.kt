package app.measure.ar.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns the AR frame loop: draws the camera, hit-tests the screen center, manages measurement
 * anchors, and publishes an [ArFrameState] (in screen pixels) for the Compose overlay.
 * All ARCore objects are touched only on the GL thread; the UI talks to it through [commands].
 */
class MeasureRenderer(private val context: Context) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null

    private val _state = MutableStateFlow(ArFrameState())
    val state: StateFlow<ArFrameState> = _state

    private enum class Command { AddPoint, Undo, DeleteSelected, ClearAll, BeginMove, EndMove, Weld }
    private val commands = ConcurrentLinkedQueue<Command>()

    fun addPoint() = commands.add(Command.AddPoint)
    /** Cancels the measurement or move in progress if there is one, otherwise undoes the last change. */
    fun undo() = commands.add(Command.Undo)
    /** Deletes the dot (and its lines) or line under the reticle. */
    fun deleteSelected() = commands.add(Command.DeleteSelected)
    fun clearAll() = commands.add(Command.ClearAll)
    /** Pick up the point under the reticle (if any) so it follows the reticle until [endMove]. */
    fun beginMove() = commands.add(Command.BeginMove)
    /** Drop the point being moved where the reticle is now. */
    fun endMove() = commands.add(Command.EndMove)
    /** Merge the point being moved into the other point it's hovering over. */
    fun weld() = commands.add(Command.Weld)

    /** A measured line. Immutable so undo snapshots can share them safely. */
    private class Segment(val start: Anchor, val end: Anchor) {
        fun touches(anchor: Anchor) = start === anchor || end === anchor
    }

    /** What the delete button would remove right now. */
    private sealed interface Selection {
        data class Dot(val anchor: Anchor) : Selection
        data class Line(val segment: Segment) : Selection
    }

    private var segments: List<Segment> = emptyList()
    private var pending: Anchor? = null

    /** Earlier versions of [segments], newest last; undo pops back to them. */
    private val history = ArrayDeque<List<Segment>>()
    /** Every anchor we've created and not yet detached. */
    private val liveAnchors = hashSetOf<Anchor>()

    /** The point a long press of add will pick up when the reticle isn't on one (aiming is fiddly). */
    private var lastSelected: Anchor? = null

    /** The point being dragged, plus where it currently is (follows the surface under the reticle). */
    private var moving: Anchor? = null
    private var movePosition: FloatArray? = null
    private var moveNormal: FloatArray? = null

    private val background = BackgroundRenderer()
    private var width = 0
    private var height = 0
    private var geometryChanged = false

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private var reticleShape = ReticleShape.Facing
    /** Surface normal each placed point was dropped on (when known), so the reticle can match it when snapping. */
    private val anchorNormals = HashMap<Anchor, FloatArray>()
    private val density = context.resources.displayMetrics.density
    private val snapRadiusPx = 28f * density
    private val hoverRadiusPx = 24f * density
    private val surfaceEstimator = SurfaceEstimator(density)

    /** Show surface-estimate debug info (see SurfaceDebug.kt). */
    @Volatile var debugEnabled = false
    private val debugDeviation = ArrayDeque<Float>()
    private val debugInstability = ArrayDeque<Float>()
    private val debugSteady = ArrayDeque<Boolean>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (geometryChanged) {
            val rotation = ContextCompat.getDisplayOrDefault(context).rotation
            session.setDisplayGeometry(rotation, width, height)
            geometryChanged = false
        }
        session.setCameraTextureName(background.textureId)

        val frame = try {
            session.update()
        } catch (e: Exception) {
            Log.w(TAG, "session.update failed", e)
            return
        }
        background.draw(frame)

        val camera = frame.camera
        countFrame(frame.timestamp)
        if (camera.trackingState != TrackingState.TRACKING) {
            surfaceEstimator.reset()
            drainCommands(session, surface = null, snapTarget = null, selection = null, weldTarget = null)
            _state.value = ArFrameState(
                trackingIssue = camera.trackingFailureReason.toIssue(),
                measuring = pending != null,
                moving = moving != null,
                canUndo = canUndo(),
                undoCancels = undoCancels(),
                canClear = canClear(),
                debug = if (debugEnabled) SurfaceDebug(sessionLines(session, frame)) else null,
            )
            return
        }

        camera.getProjectionMatrix(proj, 0, 0.01f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        val center = Offset(width / 2f, height / 2f)
        val debug = debugEnabled
        surfaceEstimator.debugEnabled = debug
        surfaceEstimator.debugMapMs = 0f
        val estimateStart = System.nanoTime()
        val surface = surfaceEstimator.estimate(frame, center)
        val estimateMs = (System.nanoTime() - estimateStart) / 1e6f
        // While dragging, the point can't snap onto itself; any other point it reaches can be welded to.
        val snapTarget = if (moving == null) findSnapTarget(center) else null
        val weldTarget = moving?.let { findSnapTarget(center, exclude = it) }
        // While building a line, focus stays on the point it starts from; hovering others doesn't steal it.
        if (snapTarget != null && pending == null) lastSelected = snapTarget

        drainCommands(session, surface, snapTarget, selectionAt(center, snapTarget), weldTarget)
        // Commands may have dropped or welded the point, which ends the drag.
        val activeWeldTarget = weldTarget?.takeIf { moving != null }
        if (moving != null) {
            // Over another point, preview the weld by sitting exactly on it.
            if (activeWeldTarget != null) {
                movePosition = activeWeldTarget.pose.translation
                moveNormal = anchorNormals[activeWeldTarget]
            } else if (surface != null) {
                movePosition = surface.position
                moveNormal = surface.normal
            }
        }

        // Where the reticle sits in the world: an existing point we snapped to, or the surface.
        val dot = snapTarget ?: activeWeldTarget
        val reticleWorld: FloatArray? = dot?.pose?.translation ?: surface?.position
        // No trusted normal (a corner, or depth too noisy to tell) means the reticle faces the camera.
        val reticleNormal = if (dot != null) anchorNormals[dot] else surface?.normal
        // The reticle stays in the middle of the screen unless it's sitting on an existing point.
        val reticleScreen = dot?.let { project(it.pose.translation) }
        // Lay the reticle flat on the surface, easing between shapes so it never pops.
        val targetShape = if (reticleWorld != null && reticleNormal != null) {
            reticleShapeFor(reticleWorld, reticleNormal)
        } else null
        reticleShape = reticleShape.lerp(targetShape ?: ReticleShape.Facing, RETICLE_SMOOTHING)

        val pendingAnchor = pending
        val pendingWorld = pendingAnchor?.let(::positionOf)
        val selection = selectionAt(center, snapTarget)

        // Hovering a dot or a line (or moving a point) highlights everything connected to it.
        val movingAnchor = moving
        val group: Set<Segment> = when {
            pendingAnchor != null -> emptySet() // The live measurement owns the readout.
            moving != null -> connectedTo(moving!!)
            selection is Selection.Dot -> connectedTo(selection.anchor)
            selection is Selection.Line -> connectedTo(selection.segment.start)
            else -> emptySet()
        }
        // The lines the delete button would remove.
        val doomed: Set<Segment> = when (selection) {
            is Selection.Dot -> segments.filter { it.touches(selection.anchor) }.toSet()
            is Selection.Line -> setOf(selection.segment)
            null -> emptySet()
        }
        val drawn = projectedSegments().map { (s, screen) ->
            screen.copy(highlighted = s in group, selected = s in doomed)
        }
        val polygons = closedLoops().mapNotNull { (loop, points) ->
            projectPolygon(points.map(::positionOf), highlighted = loop.any { it in group })
        }
        // The readout sums the hovered group; while dragging, it shows the one line being stretched,
        // but only when the point is a loose end (a corner joining several lines has no single length).
        val readoutLines: Set<Segment> = if (movingAnchor != null) {
            segments.filter { it.touches(movingAnchor) }.takeIf { it.size == 1 }?.toSet().orEmpty()
        } else group
        val live = if (pendingWorld != null && reticleWorld != null) {
            projectSegment(pendingWorld, reticleWorld)
        } else null

        _state.value = ArFrameState(
            trackingIssue = null,
            hasSurface = reticleWorld != null,
            snapped = dot != null,
            reticle = reticleScreen,
            reticleShape = reticleShape,
            segments = drawn,
            polygons = polygons,
            live = live,
            pendingPoint = pendingWorld?.let { project(it) },
            measuring = pendingAnchor != null,
            moving = moving != null,
            hoverTotal = if (readoutLines.isEmpty()) null else readoutLines.sumOf {
                positionOf(it.start).distanceTo(positionOf(it.end)).toDouble()
            }.toFloat(),
            hoverCount = readoutLines.size,
            hoverClosed = readoutLines.isNotEmpty() && loopOrder(readoutLines) != null,
            canWeld = activeWeldTarget != null,
            canPickUp = pendingAnchor == null && movingAnchor == null && (dot != null || pickUpFallback() != null),
            canUndo = canUndo(),
            undoCancels = undoCancels(),
            canDelete = selection != null,
            canClear = canClear(),
            debug = if (debug) buildDebug(session, frame, estimateMs) else null,
        )
    }

    private fun buildDebug(session: Session, frame: Frame, estimateMs: Float): SurfaceDebug {
        val info = surfaceEstimator.debugInfo ?: return SurfaceDebug(sessionLines(session, frame))
        fun <T> ArrayDeque<T>.push(value: T) {
            addLast(value)
            while (size > DEBUG_HISTORY) removeFirst()
        }
        debugDeviation.push(info.deviation)
        debugInstability.push(info.instability)
        debugSteady.push(info.steady)
        val projected = info.samples.map { project(it) }
        return SurfaceDebug(
            sessionLines = sessionLines(session, frame),
            lines = info.lines + run {
                // Report the estimate without the debug-only depth map, which is drawn every few frames.
                val mapMs = surfaceEstimator.debugMapMs
                debugEstimateMs += (estimateMs - mapMs - debugEstimateMs) * 0.1f
                if (mapMs > 0f) debugMapMs = mapMs
                "estimate ${"%.1f".format(debugEstimateMs)} ms avg  (+ debug map ${"%.1f".format(debugMapMs)} ms)"
            },
            samples = projected.filterNotNull(),
            inliers = projected.indices.filter { projected[it] != null }.map { info.inliers?.getOrNull(it) },
            deviationHistory = debugDeviation.toList(),
            instabilityHistory = debugInstability.toList(),
            steadyHistory = debugSteady.toList(),
            depthMap = info.depthMap?.asImageBitmap(),
            steadyBelowDeg = surfaceEstimator.steadyBelowDeg,
            flatAboveDeg = surfaceEstimator.flatAboveDeg,
            ringRadiusPx = surfaceEstimator.innerPx,
            patchRadiusPx = surfaceEstimator.outerPx,
        )
    }

    // Debug overlay: frame rates and session info.
    private var lastDrawNs = 0L
    private var lastCameraTimestamp = 0L
    private var lastCameraNs = 0L
    private var drawFps = 0f
    private var debugEstimateMs = 0f
    private var debugMapMs = 0f
    private var cameraFps = 0f

    private fun countFrame(cameraTimestamp: Long) {
        val now = System.nanoTime()
        if (lastDrawNs != 0L) drawFps += (1e9f / max(now - lastDrawNs, 1L) - drawFps) * 0.1f
        lastDrawNs = now
        if (cameraTimestamp != lastCameraTimestamp) {
            if (lastCameraNs != 0L) cameraFps += (1e9f / max(now - lastCameraNs, 1L) - cameraFps) * 0.1f
            lastCameraNs = now
            lastCameraTimestamp = cameraTimestamp
        }
    }

    private fun sessionLines(session: Session, frame: Frame): List<String> {
        val config = session.cameraConfig
        val image = config.imageSize
        val texture = config.textureSize
        val fpsRange = config.fpsRange
        val camera = frame.camera
        val tracking = camera.trackingState.name.lowercase() +
            camera.trackingFailureReason.takeIf { it != TrackingFailureReason.NONE }?.let { " (${it.name.lowercase()})" }.orEmpty()
        val planes = session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        val horizontal = planes.count { it.type != Plane.Type.VERTICAL }
        return listOf(
            "cam ${image.width}x${image.height} tex ${texture.width}x${texture.height} " +
                "${fpsRange.lower}-${fpsRange.upper}fps  depth ${session.config.depthMode.name.lowercase()}" +
                (if (config.depthSensorUsage == com.google.ar.core.CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) " +ToF" else ""),
            "draw ${drawFps.roundToInt()}fps  cam ${cameraFps.roundToInt()}fps  $tracking",
            "planes ${horizontal}h ${planes.size - horizontal}v  anchors ${liveAnchors.size}  lines ${segments.size}",
        )
    }

    /** Where a point is this frame; the point being moved is drawn where the reticle has dragged it. */
    private fun positionOf(anchor: Anchor): FloatArray =
        if (anchor === moving) movePosition ?: anchor.pose.translation else anchor.pose.translation

    private fun projectedSegments(): List<Pair<Segment, ScreenSegment>> = segments.mapNotNull { s ->
        if (s.start.isStopped() || s.end.isStopped()) null
        else projectSegment(positionOf(s.start), positionOf(s.end))?.let { s to it }
    }

    /** The dot snapped to, else the line closest to the screen center (if near enough). */
    private fun selectionAt(center: Offset, snapTarget: Anchor?): Selection? {
        if (pending != null || moving != null) return null
        if (snapTarget != null) return Selection.Dot(snapTarget)
        return projectedSegments()
            .map { (s, screen) -> s to distanceToSegment(center, screen.start, screen.end) }
            .filter { it.second <= hoverRadiusPx }
            .minByOrNull { it.second }
            ?.let { Selection.Line(it.first) }
    }

    /** Every line reachable from [anchor] through shared endpoints. */
    private fun connectedTo(anchor: Anchor): Set<Segment> {
        val points = hashSetOf(anchor)
        val result = linkedSetOf<Segment>()
        var grew = true
        while (grew) {
            grew = false
            for (s in segments) {
                if (s !in result && (s.start in points || s.end in points)) {
                    result += s
                    points += s.start
                    points += s.end
                    grew = true
                }
            }
        }
        return result
    }

    /** Every group of connected lines that forms a single closed loop, with its corners in order. */
    private fun closedLoops(): List<Pair<Set<Segment>, List<Anchor>>> {
        val seen = hashSetOf<Segment>()
        val loops = mutableListOf<Pair<Set<Segment>, List<Anchor>>>()
        for (s in segments) {
            if (s in seen) continue
            val group = connectedTo(s.start)
            seen += group
            loopOrder(group)?.let { loops += group to it }
        }
        return loops
    }

    /** The corners in walking order if [group] is one simple closed loop (every corner joins exactly two lines). */
    private fun loopOrder(group: Set<Segment>): List<Anchor>? {
        if (group.size < 3) return null
        val neighbors = HashMap<Anchor, MutableList<Anchor>>()
        for (s in group) {
            neighbors.getOrPut(s.start) { mutableListOf() } += s.end
            neighbors.getOrPut(s.end) { mutableListOf() } += s.start
        }
        if (neighbors.size != group.size || neighbors.values.any { it.size != 2 }) return null
        val start = neighbors.keys.first()
        val order = mutableListOf(start)
        var previous: Anchor? = null
        var current = start
        repeat(group.size - 1) {
            val next = neighbors.getValue(current).firstOrNull { it !== previous } ?: return null
            if (next in order) return null
            order += next
            previous = current
            current = next
        }
        return order
    }

    /** Area of a (roughly planar) 3D polygon via its vector area, plus its on-screen outline. */
    private fun projectPolygon(corners: List<FloatArray>, highlighted: Boolean): ScreenPolygon? {
        var vectorArea = vec(0f, 0f, 0f)
        corners.forEachIndexed { i, p -> vectorArea += p cross corners[(i + 1) % corners.size] }
        val area = vectorArea.length() / 2f
        // Skip the fill/label when part of the shape is behind the camera.
        val screen = corners.map { project(it) ?: return null }
        val center = project(corners.centroid()) ?: return null
        return ScreenPolygon(screen, center, area, highlighted)
    }

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val lengthSq = ab.x * ab.x + ab.y * ab.y
        val t = if (lengthSq == 0f) 0f else (((p - a).x * ab.x + (p - a).y * ab.y) / lengthSq).coerceIn(0f, 1f)
        val closest = a + ab * t
        return hypot(p.x - closest.x, p.y - closest.y)
    }

    // --- Editing ----------------------------------------------------------------------------------

    private fun drainCommands(
        session: Session,
        surface: Surface?,
        snapTarget: Anchor?,
        selection: Selection?,
        weldTarget: Anchor?,
    ) {
        var changed = false
        while (true) {
            when (commands.poll() ?: break) {
                Command.AddPoint -> placePoint(session, surface, snapTarget)
                Command.Undo -> undoOrCancel()
                Command.DeleteSelected -> delete(selection)
                Command.ClearAll -> clearEverything()
                Command.BeginMove -> beginMove(snapTarget ?: pickUpFallback())
                Command.EndMove -> endMove(session, surface)
                Command.Weld -> weld(weldTarget)
            }
            changed = true
        }
        if (changed) releaseUnusedAnchors()
    }

    /** Replace the measurements, remembering the old ones so the change can be undone. */
    private fun commit(newSegments: List<Segment>) {
        history.addLast(segments)
        while (history.size > MAX_HISTORY) history.removeFirst()
        segments = newSegments
    }

    private fun createAnchor(session: Session, surface: Surface?, position: FloatArray, normal: FloatArray?): Anchor? =
        try {
            (surface?.hit?.takeIf { surface.position === position }?.createAnchor()
                ?: session.createAnchor(Pose(position, IDENTITY_ROTATION)))
                .also {
                    liveAnchors += it
                    if (normal != null) anchorNormals[it] = normal
                }
        } catch (e: Exception) {
            Log.w(TAG, "createAnchor failed", e)
            null
        }

    private fun placePoint(session: Session, surface: Surface?, snapTarget: Anchor?) {
        val target = snapTarget
            ?: surface?.let { createAnchor(session, it, it.position, it.normal) }
            ?: return
        val start = pending
        if (start == null) {
            pending = target
            lastSelected = target
        } else if (target !== start) {
            commit(segments + Segment(start, target))
            pending = null
            lastSelected = target
        }
    }

    private fun beginMove(snapTarget: Anchor?) {
        if (moving != null || pending != null || snapTarget == null) return
        moving = snapTarget
        movePosition = snapTarget.pose.translation
        moveNormal = anchorNormals[snapTarget]
    }

    /** Re-anchor the moved point where it was dropped and swap it into every line that used it. */
    private fun endMove(session: Session, surface: Surface?) {
        val old = moving ?: return
        val position = movePosition
        val normal = moveNormal
        cancelMove()
        if (position == null || position.distanceTo(old.pose.translation) < MIN_MOVE_M) return
        val replacement = createAnchor(session, surface, position, normal) ?: return
        lastSelected = replacement
        commit(segments.map { s ->
            if (s.touches(old)) {
                Segment(if (s.start === old) replacement else s.start, if (s.end === old) replacement else s.end)
            } else s
        })
    }

    /**
     * Merge the dragged point into [target]: its lines now end at [target]. A line between the two
     * collapses to nothing and is dropped, as is any line that would duplicate an existing one.
     */
    private fun weld(target: Anchor?) {
        val old = moving ?: return
        if (target == null || target === old) return
        cancelMove()
        lastSelected = target
        val seen = hashSetOf<Set<Anchor>>()
        commit(segments.mapNotNull { s ->
            val start = if (s.start === old) target else s.start
            val end = if (s.end === old) target else s.end
            when {
                start === end -> null
                !seen.add(setOf(start, end)) -> null
                start === s.start && end === s.end -> s
                else -> Segment(start, end)
            }
        })
    }

    private fun cancelMove() {
        moving = null
        movePosition = null
        moveNormal = null
    }

    private fun undoOrCancel() {
        when {
            moving != null -> cancelMove()
            pending != null -> pending = null
            history.isNotEmpty() -> segments = history.removeLast()
        }
    }

    private fun delete(selection: Selection?) {
        when (selection) {
            is Selection.Dot -> commit(segments.filterNot { it.touches(selection.anchor) })
            is Selection.Line -> commit(segments - selection.segment)
            null -> Unit
        }
    }

    private fun clearEverything() {
        cancelMove()
        pending = null
        if (segments.isNotEmpty()) commit(emptyList())
    }

    /** Detach anchors that neither the current measurements nor the undo history refer to. */
    private fun releaseUnusedAnchors() {
        val used = hashSetOf<Anchor>()
        fun mark(list: List<Segment>) = list.forEach { used += it.start; used += it.end }
        mark(segments)
        history.forEach(::mark)
        pending?.let { used += it }
        moving?.let { used += it }
        val unused = liveAnchors.filter { it !in used }
        unused.forEach {
            it.detach()
            liveAnchors -= it
            anchorNormals -= it
        }
    }

    /** The remembered point, as long as it's still part of a measurement. */
    private fun pickUpFallback(): Anchor? =
        lastSelected?.takeIf { a -> !a.isStopped() && segments.any { it.touches(a) } }

    private fun canUndo() = undoCancels() || history.isNotEmpty()
    private fun undoCancels() = pending != null || moving != null
    private fun canClear() = segments.isNotEmpty() || pending != null

    /** The existing endpoint closest to the screen center, if it's within the snap radius. */
    private fun findSnapTarget(center: Offset, exclude: Anchor? = null): Anchor? {
        val candidates = segments.flatMap { listOf(it.start, it.end) } + listOfNotNull(pending)
        return candidates
            .filter { !it.isStopped() && it !== exclude }
            .mapNotNull { a -> project(a.pose.translation)?.let { a to it } }
            .map { (a, p) -> a to hypot(p.x - center.x, p.y - center.y) }
            .filter { it.second <= snapRadiusPx }
            .minByOrNull { it.second }
            ?.first
    }

    private fun Anchor.isStopped() = trackingState == TrackingState.STOPPED

    // --- Projection -----------------------------------------------------------------------------

    private val tmpIn = FloatArray(4)

    private fun toClip(p: FloatArray): FloatArray {
        tmpIn[0] = p[0]; tmpIn[1] = p[1]; tmpIn[2] = p[2]; tmpIn[3] = 1f
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, viewProj, 0, tmpIn, 0)
        return out
    }

    private fun clipToScreen(c: FloatArray) = Offset(
        x = (c[0] / c[3] + 1f) * 0.5f * width,
        y = (1f - c[1] / c[3]) * 0.5f * height,
    )

    private fun project(p: FloatArray): Offset? {
        val c = toClip(p)
        return if (c[3] <= NEAR_W) null else clipToScreen(c)
    }

    /**
     * Projects a circle lying on the surface with the given [normal], sized so it would be
     * [REF_PX] pixels across if it faced the camera, and returns the resulting ellipse.
     */
    private fun reticleShapeFor(center: FloatArray, normal: FloatArray): ReticleShape? {
        val clip = toClip(center)
        if (clip[3] <= NEAR_W) return null
        val pxPerUnitAtDepth = proj[5] * height / 2f / clip[3]
        val r = REF_PX / pxPerUnitAtDepth
        val c = clipToScreen(clip)
        // Any two perpendicular directions within the surface will do; the circle is symmetric.
        val helper = if (abs(normal[1]) < 0.9f) vec(0f, 1f, 0f) else vec(1f, 0f, 0f)
        val x = (helper cross normal).normalized() ?: return null
        val z = normal cross x
        val pu = project(center + x * r) ?: return null
        val pv = project(center + z * r) ?: return null
        val ux = (pu.x - c.x) / REF_PX
        val uy = (pu.y - c.y) / REF_PX
        val vx = (pv.x - c.x) / REF_PX
        val vy = (pv.y - c.y) / REF_PX
        // M = U Uᵀ + V Vᵀ: invariant to how the axes spin within the plane, so it's safe to smooth.
        return ReticleShape(a = ux * ux + vx * vx, b = ux * uy + vx * vy, c = uy * uy + vy * vy)
    }

    /** Projects a 3D segment to screen space, clipping the part that falls behind the camera. */
    private fun projectSegment(a: FloatArray, b: FloatArray): ScreenSegment? {
        var ca = toClip(a)
        var cb = toClip(b)
        val aVisible = ca[3] > NEAR_W
        val bVisible = cb[3] > NEAR_W
        if (!aVisible && !bVisible) return null
        if (!aVisible) ca = lerp(ca, cb, (NEAR_W - ca[3]) / (cb[3] - ca[3]))
        if (!bVisible) cb = lerp(cb, ca, (NEAR_W - cb[3]) / (ca[3] - cb[3]))
        return ScreenSegment(clipToScreen(ca), clipToScreen(cb), aVisible, bVisible, distance(a, b))
    }

    private fun lerp(from: FloatArray, to: FloatArray, t: Float) =
        FloatArray(4) { i -> from[i] + (to[i] - from[i]) * t }

    private fun distance(a: FloatArray, b: FloatArray) = a.distanceTo(b)

    private fun TrackingFailureReason.toIssue() = when (this) {
        TrackingFailureReason.EXCESSIVE_MOTION -> TrackingIssue.ExcessiveMotion
        TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingIssue.InsufficientLight
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingIssue.InsufficientFeatures
        TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingIssue.CameraUnavailable
        else -> TrackingIssue.Initializing
    }

    private companion object {
        const val TAG = "MeasureRenderer"
        const val NEAR_W = 0.01f
        const val REF_PX = 100f
        const val RETICLE_SMOOTHING = 0.5f
        const val MIN_MOVE_M = 0.001f
        const val MAX_HISTORY = 100
        const val DEBUG_HISTORY = 120
        val IDENTITY_ROTATION = floatArrayOf(0f, 0f, 0f, 1f)
    }
}
