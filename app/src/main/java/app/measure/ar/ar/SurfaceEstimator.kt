package app.measure.ar.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/** The surface under the reticle: a point and, when we trust it, its normal (pointing toward the camera). */
internal class Surface(
    /** Always on the ray through the screen center, so the reticle stays put on screen. */
    val position: FloatArray,
    /**
     * Null when the orientation is uncertain (noisy depth, a corner or edge under the reticle, or
     * an estimate that keeps jittering); the reticle then faces the camera instead of guessing.
     */
    val normal: FloatArray?,
    /** The raw hit, when [position] came straight from it (lets the anchor attach to a plane). */
    val hit: HitResult?,
    val isPlane: Boolean,
)

/**
 * Finds the surface under the screen center and how it's oriented.
 *
 * Position: the nearest hit along the center ray (a detected plane only wins when it's about as
 * near as the depth). Only the distance along the ray is smoothed, never the 3D point, so the
 * reticle can't trail behind when the camera pans.
 *
 * Orientation: detected planes are used as-is. Elsewhere, phone depth is too coarse and noisy for
 * a reticle-sized patch to give a steady angle, so we sample a wider patch (interpolating between
 * depth pixels), pool it over the last few depth frames in world space, and fit a plane robustly.
 * The wide patch sets the angle; the part right under the reticle decides whether it's trusted:
 * if that part doesn't lie on the plane (a corner or an edge), there's no normal.
 *
 * The result is filtered (small wobble averaged away; a big change has to persist a few frames,
 * then is taken at once), and the reticle only tilts while the raw estimate has been steady.
 * Sustained jitter or repeated dropouts fall back to a camera-facing reticle.
 */
internal class SurfaceEstimator(density: Float) {

    val outerPx = OUTER_DP * density
    val innerPx = INNER_DP * density
    private val viewGrid = FloatArray(SAMPLES * 2)
    private val textureGrid = FloatArray(SAMPLES * 2)
    private val projection = FloatArray(16)
    private val random = Random(7)

    /** World-space depth samples from the most recent depth images, newest last. */
    private val depthHistory = ArrayDeque<List<FloatArray>>()
    private var lastDepthTimestamp = Long.MIN_VALUE

    private var smoothedDistance = Float.NaN
    private var filteredNormal: FloatArray? = null
    /** Consecutive frames whose raw normal disagreed strongly with [filteredNormal]. */
    private var disagreeFrames = 0
    /** Running average of how far each frame's raw normal strays from the filtered one, in degrees. */
    private var instability = START_INSTABILITY
    private var steady = false
    private var gravitySnap = GravitySnap.None

    private enum class GravitySnap { None, Horizontal, Vertical }

    fun reset() {
        depthHistory.clear()
        lastDepthTimestamp = Long.MIN_VALUE
        smoothedDistance = Float.NaN
        filteredNormal = null
        disagreeFrames = 0
        instability = START_INSTABILITY
        steady = false
        gravitySnap = GravitySnap.None
    }

    fun estimate(frame: Frame, center: Offset): Surface? {
        val cameraPos = frame.camera.pose.translation
        val hit = nearestHit(frame, center)
        val hitPos = hit?.hitPose?.translation
        val ray = hitPos?.let { (it - cameraPos).normalized() }
        if (hit == null || hitPos == null || ray == null) {
            reset()
            if (debugEnabled) collectDepth(frame, center) // Keep the depth map live while aiming at nothing.
            debugInfo = if (debugEnabled) debugSnapshot("none", 0f) else null
            return null
        }
        collectDepth(frame, center)
        val toCamera = -ray
        var distance = hitPos.distanceTo(cameraPos)

        val isPlane = hit.trackable is Plane
        dbgFit = null
        dbgRawNormal = null
        val rawNormal: FloatArray? = if (isPlane) {
            dbgStatus = "plane"
            hit.hitPose.yAxis.facing(toCamera)
        } else {
            fitPlane(frame, center, cameraPos, ray, hitPos, distance)?.let { fit ->
                val n = fit.normal.facing(toCamera)
                val cos = ray dot n
                if (abs(cos) < MIN_RAY_ANGLE_COS) { // Too grazing to trust depth normals.
                    dbgStatus = "too grazing"
                    return@let null
                }
                // The fitted plane pins down the distance better than the single raw hit.
                distance = ((fit.point - cameraPos) dot n) / cos
                n
            }
        }
        dbgRawNormal = rawNormal

        val previous = smoothedDistance
        val jumped = previous.isNaN() || abs(distance - previous) > max(JUMP_MIN_M, previous * JUMP_FRACTION)
        smoothedDistance = if (isPlane || jumped) distance else previous + (distance - previous) * DISTANCE_SMOOTHING
        val position = if (isPlane) hitPos else cameraPos + ray * smoothedDistance

        val normal = updateNormal(rawNormal, jumped)
        debugInfo = if (debugEnabled) {
            val source = when (hit.trackable) {
                is Plane -> "plane"
                is DepthPoint -> "depth"
                is Point -> "feature pt"
                else -> "?"
            }
            debugSnapshot(source, smoothedDistance, normal)
        } else null
        return Surface(position, normal, hit.takeIf { isPlane }, isPlane)
    }

    /**
     * Filters the normal and decides whether it's steady enough to show. A real surface doesn't
     * rotate, so small differences are noise and are averaged heavily. A big difference is either
     * a glitch or a new surface: it's ignored unless it persists for a few frames, then taken at once.
     */
    private fun updateNormal(raw: FloatArray?, jumped: Boolean): FloatArray? {
        val previous = filteredNormal
        val deviation = when {
            raw == null -> MAX_DEVIATION_DEG
            previous == null -> 0f
            else -> min(angleDeg(previous, raw), MAX_DEVIATION_DEG)
        }
        dbgDeviation = deviation
        if (raw != null) {
            when {
                previous == null || jumped -> {
                    filteredNormal = raw
                    disagreeFrames = 0
                }
                deviation >= CHANGE_DEG -> if (++disagreeFrames >= CHANGE_FRAMES) {
                    filteredNormal = raw
                    disagreeFrames = 0
                }
                else -> {
                    disagreeFrames = 0
                    filteredNormal = previous.lerp(raw, NORMAL_SMOOTHING).normalized() ?: raw
                }
            }
        }
        instability += (deviation - instability) * INSTABILITY_ALPHA
        steady = instability < if (steady) UNSTEADY_DEG else STEADY_DEG
        val normal = filteredNormal ?: return null
        return if (steady) snapToGravity(normal) else null
    }

    /**
     * ARCore's world Y is up and rooms are mostly square to gravity, so normals near vertical or
     * level are snapped to exactly that, with hysteresis so it doesn't toggle at the boundary.
     */
    private fun snapToGravity(n: FloatArray): FloatArray {
        val tilt = tiltDeg(n)
        gravitySnap = when {
            tilt < if (gravitySnap == GravitySnap.Horizontal) SNAP_EXIT_DEG else SNAP_ENTER_DEG -> GravitySnap.Horizontal
            tilt > 90f - if (gravitySnap == GravitySnap.Vertical) SNAP_EXIT_DEG else SNAP_ENTER_DEG -> GravitySnap.Vertical
            else -> GravitySnap.None
        }
        return when (gravitySnap) {
            GravitySnap.Horizontal -> vec(0f, sign(n[1]), 0f)
            GravitySnap.Vertical -> vec(n[0], 0f, n[2]).normalized() ?: n
            GravitySnap.None -> n
        }
    }

    /**
     * The closest usable hit along the center ray. Among hits at about that distance, a detected
     * plane beats a depth point, which beats a sparse feature point.
     */
    private fun nearestHit(frame: Frame, center: Offset): HitResult? {
        val hits = frame.hitTest(center.x, center.y).filter { it.isUsable() } // Sorted nearest first.
        val nearest = hits.firstOrNull() ?: return null
        val tolerance = toleranceAt(nearest.distance) * 2f
        val near = hits.filter { it.distance - nearest.distance <= tolerance }
        return near.firstOrNull { it.trackable is Plane }
            ?: near.firstOrNull { it.trackable is DepthPoint }
            ?: nearest
    }

    private class PlaneFit(val point: FloatArray, val normal: FloatArray)

    /**
     * Robust plane through the recent depth samples around the center ray, or null if the part
     * under the reticle isn't one flat surface containing the hit.
     */
    private fun fitPlane(
        frame: Frame,
        center: Offset,
        cameraPos: FloatArray,
        ray: FloatArray,
        hitPos: FloatArray,
        distance: Float,
    ): PlaneFit? {
        dbgPooled = 0
        dbgInnerCount = 0
        dbgStatus = "no depth"
        val current = depthHistory.lastOrNull() ?: return null
        dbgStatus = "too few samples"
        if (current.size < SAMPLES * MIN_VALID_FRACTION) return null

        // Keep pooled samples that lie within the patch's cone around the center ray now (older
        // frames may have aimed elsewhere) and at roughly the hit's depth.
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        val focalPx = projection[5] * center.y // proj[5] = 2f/height, center.y = height/2.
        val outerTan = outerPx / focalPx
        val innerTan = innerPx / focalPx
        val points = ArrayList<FloatArray>()
        val inner = ArrayList<Boolean>()
        for (samples in depthHistory) for (p in samples) {
            val v = p - cameraPos
            val along = v dot ray
            if (along <= 0f || abs(along - distance) > distance * MAX_DEPTH_SPREAD) continue
            val off = (v - ray * along).length() / along
            if (off > outerTan) continue
            points += p
            inner += off <= innerTan
        }
        val innerCount = inner.count { it }
        dbgPooled = points.size
        dbgInnerCount = innerCount
        if (innerCount < MIN_INNER_POINTS || points.size < MIN_POINTS) return null
        val tolerance = toleranceAt(distance)
        dbgTolerance = tolerance
        val tolSq = tolerance * tolerance

        // MSAC: like RANSAC, but scored by summed (capped) squared distance rather than inlier
        // count, so among planes that most points agree with, the tightest fit wins. Seeded with
        // last frame's plane so the choice doesn't flip between near-equals.
        fun score(origin: FloatArray, n: FloatArray): Float {
            var sum = 0f
            for (p in points) sum += min(planeDistance(p, origin, n).let { it * it }, tolSq)
            return sum
        }
        var bestNormal: FloatArray? = filteredNormal
        var bestPoint: FloatArray = hitPos
        var bestScore = bestNormal?.let { score(hitPos, it) } ?: Float.MAX_VALUE
        repeat(RANSAC_ITERATIONS) {
            val a = points[random.nextInt(points.size)]
            val b = points[random.nextInt(points.size)]
            val c = points[random.nextInt(points.size)]
            val ab = b - a
            val ac = c - a
            val cross = ab cross ac
            // Skip nearly collinear triples; their normal is mostly noise.
            if (cross.length() < MIN_TRIANGLE_SIN * ab.length() * ac.length()) return@repeat
            val normal = cross.normalized() ?: return@repeat
            val s = score(a, normal)
            if (s < bestScore) {
                bestScore = s
                bestNormal = normal
                bestPoint = a
            }
        }
        val seedNormal = bestNormal ?: return null

        // Refine with a least-squares fit over the inliers, then check the plane can be trusted.
        val inliers = points.filter { planeDistance(it, bestPoint, seedNormal) <= tolerance }
        if (inliers.size < MIN_POINTS) return null
        val mean = inliers.centroid()
        val normal = leastSquaresNormal(inliers, mean, seedNormal)
        val fit = PlaneFit(mean, normal)
        dbgFit = fit

        var agreeing = 0
        var innerAgreeing = 0
        var squares = 0f
        points.forEachIndexed { i, p ->
            val d = planeDistance(p, mean, normal)
            if (d <= tolerance) {
                agreeing++
                squares += d * d
                if (inner[i]) innerAgreeing++
            }
        }
        dbgAgree = agreeing.toFloat() / points.size
        dbgInnerAgree = innerAgreeing.toFloat() / innerCount
        dbgRmsMm = if (agreeing > 0) sqrt(squares / agreeing) * 1000f else 0f
        dbgStatus = "corner/edge under reticle"
        if (innerAgreeing < innerCount * MIN_INNER_AGREE) return null
        dbgStatus = "patch not mostly one surface"
        if (agreeing < points.size * MIN_TOTAL_AGREE) return null
        // The plane has to be the thing actually under the reticle.
        dbgStatus = "hit off plane"
        if (planeDistance(hitPos, mean, normal) > tolerance) return null
        dbgStatus = "ok"
        return fit
    }

    /**
     * Unprojects a grid of the depth image around [center] into world space and remembers it, if
     * the depth image is new.
     */
    private fun collectDepth(frame: Frame, center: Offset) {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: Exception) {
            return // Depth unsupported, disabled, or not ready yet.
        }
        image.use {
            if (image.timestamp == lastDepthTimestamp) return
            lastDepthTimestamp = image.timestamp
            val plane = image.planes[0]
            val depth = DepthReader(
                plane.buffer.order(ByteOrder.LITTLE_ENDIAN),
                image.width,
                image.height,
                plane.rowStride,
                plane.pixelStride,
            )

            val step = 2f * outerPx / (GRID - 1)
            for (j in 0 until GRID) for (i in 0 until GRID) {
                val k = (j * GRID + i) * 2
                viewGrid[k] = center.x - outerPx + i * step
                viewGrid[k + 1] = center.y - outerPx + j * step
            }
            frame.transformCoordinates2d(Coordinates2d.VIEW, viewGrid, Coordinates2d.TEXTURE_NORMALIZED, textureGrid)

            // Texture intrinsics in normalized units, so they apply to the depth image at any resolution.
            val camera = frame.camera
            val intrinsics = camera.textureIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions
            val fx = focal[0] / dims[0]
            val fy = focal[1] / dims[1]
            val cx = principal[0] / dims[0]
            val cy = principal[1] / dims[1]
            val pose = camera.pose

            val samples = ArrayList<FloatArray>(SAMPLES)
            for (s in 0 until SAMPLES) {
                val u = textureGrid[s * 2]
                val v = textureGrid[s * 2 + 1]
                val d = depth.metersAt(u, v)
                if (d <= 0f || d > MAX_DEPTH_M) continue
                // Image axes (x right, y down, z forward) to the OpenGL camera frame (y up, looking down -z).
                samples += pose.transformPoint(floatArrayOf(d * (u - cx) / fx, -d * (v - cy) / fy, -d))
            }
            depthHistory.addLast(samples)
            while (depthHistory.size > DEPTH_HISTORY_FRAMES) depthHistory.removeFirst()

            if (debugEnabled) {
                dbgDepthSize = "${image.width}x${image.height}"
                if (--depthMapCountdown <= 0) {
                    depthMapCountdown = DEPTH_MAP_EVERY
                    val start = System.nanoTime()
                    depthMap = renderDepthMap(frame, center.x * 2f, center.y * 2f, depth)
                    debugMapMs = (System.nanoTime() - start) / 1e6f
                }
            }
        }
    }

    /** Reads a 16-bit depth image (millimeters, 0 = unknown) at normalized texture coordinates. */
    private class DepthReader(
        private val buffer: java.nio.ByteBuffer,
        private val width: Int,
        private val height: Int,
        private val rowStride: Int,
        private val pixelStride: Int,
    ) {
        private fun mm(x: Int, y: Int) =
            buffer.getShort(y.coerceIn(0, height - 1) * rowStride + x.coerceIn(0, width - 1) * pixelStride).toInt() and 0xFFFF

        /**
         * Depth in meters, interpolated between the four nearest pixels so neighboring samples
         * don't collapse onto the same coarse pixel. Across a depth edge (or next to unknown
         * pixels) interpolating would invent a surface in between, so it takes the nearest pixel.
         */
        fun metersAt(u: Float, v: Float): Float {
            if (u < 0f || u > 1f || v < 0f || v > 1f) return 0f
            val fx = u * width - 0.5f
            val fy = v * height - 0.5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            val d00 = mm(x0, y0)
            val d10 = mm(x0 + 1, y0)
            val d01 = mm(x0, y0 + 1)
            val d11 = mm(x0 + 1, y0 + 1)
            val lo = min(min(d00, d10), min(d01, d11))
            val hi = max(max(d00, d10), max(d01, d11))
            if (lo == 0 || hi > lo * 1.1f) {
                val nearest = if (tx < 0.5f) (if (ty < 0.5f) d00 else d01) else (if (ty < 0.5f) d10 else d11)
                return nearest / 1000f
            }
            val top = d00 + (d10 - d00) * tx
            val bottom = d01 + (d11 - d01) * tx
            return (top + (bottom - top) * ty) / 1000f
        }
    }

    private fun HitResult.isUsable(): Boolean = when (val t = trackable) {
        is Plane -> t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hitPose)
        is DepthPoint -> true
        is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        else -> false
    }

    // --- Debug (see SurfaceDebug.kt) -------------------------------------------------------------

    /** Set from the debug button; when on, [debugInfo] is filled in every frame. */
    var debugEnabled = false
    var debugInfo: EstimatorDebug? = null
        private set
    /** Time spent drawing the debug depth map this frame (0 on frames it isn't redrawn). */
    var debugMapMs = 0f
    val steadyBelowDeg get() = STEADY_DEG
    val flatAboveDeg get() = UNSTEADY_DEG
    private var dbgStatus = ""
    private var dbgAgree: Float? = null
    private var dbgInnerAgree: Float? = null
    private var dbgRmsMm = 0f
    private var dbgPooled = 0
    private var dbgInnerCount = 0
    private var dbgFit: PlaneFit? = null
    private var dbgRawNormal: FloatArray? = null
    private var dbgTolerance = 0f
    private var dbgDeviation = 0f
    private var dbgDepthSize = ""
    private var depthMap: Bitmap? = null
    private var depthMapCountdown = 0
    private var mapView = FloatArray(0)
    private var mapTexture = FloatArray(0)

    private fun debugSnapshot(source: String, distance: Float, normal: FloatArray? = null): EstimatorDebug {
        val samples = depthHistory.lastOrNull().orEmpty()
        val fit = dbgFit
        val rawTilt = dbgRawNormal?.let { "%.0f°".format(tiltDeg(it)) } ?: "-"
        val shownTilt = normal?.let { "%.0f°".format(tiltDeg(it)) } ?: "facing cam"
        return EstimatorDebug(
            lines = listOf(
                "hit: $source  ${"%.2f".format(distance)} m",
                "fit: $dbgStatus",
                if (fit == null) "agree: -" else
                    "agree: ring ${pct(dbgInnerAgree)} all ${pct(dbgAgree)}  rms ${"%.0f".format(dbgRmsMm)}mm",
                "pts: ${samples.size}/$SAMPLES  pooled $dbgPooled (ring $dbgInnerCount)",
                "tol ${"%.0f".format(dbgTolerance * 1000)}mm  depth $dbgDepthSize",
                "tilt: raw $rawTilt  shown $shownTilt",
                "dev ${"%.1f".format(dbgDeviation)}°  avg ${"%.1f".format(instability)}°  " +
                    (if (steady) "STEADY" else "FLAT") + "  snap $gravitySnap",
            ),
            samples = samples,
            inliers = fit?.let { p -> samples.map { planeDistance(it, p.point, p.normal) <= dbgTolerance } },
            deviation = dbgDeviation,
            instability = instability,
            steady = steady,
            depthMap = depthMap,
        )
    }

    private fun pct(f: Float?) = f?.let { "${(it * 100).toInt()}%" } ?: "-"

    /** The depth image resampled to screen orientation and colored near (red) to far (blue). */
    private fun renderDepthMap(frame: Frame, viewWidth: Float, viewHeight: Float, depth: DepthReader): Bitmap {
        val w = DEPTH_MAP_WIDTH
        val h = (w * viewHeight / viewWidth).toInt().coerceAtLeast(1)
        if (mapView.size != w * h * 2) {
            mapView = FloatArray(w * h * 2)
            mapTexture = FloatArray(w * h * 2)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val k = (y * w + x) * 2
            mapView[k] = (x + 0.5f) / w * viewWidth
            mapView[k + 1] = (y + 0.5f) / h * viewHeight
        }
        frame.transformCoordinates2d(Coordinates2d.VIEW, mapView, Coordinates2d.TEXTURE_NORMALIZED, mapTexture)
        val pixels = IntArray(w * h) { i ->
            val m = depth.metersAt(mapTexture[i * 2], mapTexture[i * 2 + 1])
            if (m <= 0f) 0xFF000000.toInt() else depthColor(m)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        /** Half-size of the sampled patch. Wide, because the angle needs a long baseline over noisy depth. */
        const val OUTER_DP = 64f
        /** Radius of the part under the reticle ring, which must be flat for the angle to be trusted. */
        const val INNER_DP = 30f
        const val GRID = 13
        const val SAMPLES = GRID * GRID
        /** Depth frames pooled for each fit; more averages out noise, fewer reacts faster. */
        const val DEPTH_HISTORY_FRAMES = 6
        const val MIN_VALID_FRACTION = 0.4f
        const val MIN_POINTS = 30
        const val MIN_INNER_POINTS = 12
        /** Pooled samples further than this fraction of the hit distance along the ray are elsewhere. */
        const val MAX_DEPTH_SPREAD = 0.4f
        /** Share of the ring's samples that must be on the plane: lower means corners go undetected. */
        const val MIN_INNER_AGREE = 0.85f
        /** Share of the whole patch on the plane; the outer part may include other surfaces. */
        const val MIN_TOTAL_AGREE = 0.5f
        const val RANSAC_ITERATIONS = 60
        const val MIN_TRIANGLE_SIN = 0.3f
        const val MAX_DEPTH_M = 8f
        const val MIN_TOLERANCE_M = 0.01f
        const val TOLERANCE_PER_M = 0.02f
        const val MIN_RAY_ANGLE_COS = 0.15f

        const val DISTANCE_SMOOTHING = 0.4f
        const val JUMP_MIN_M = 0.05f
        const val JUMP_FRACTION = 0.08f

        /** Per-frame weight of a raw normal close to the current one. */
        const val NORMAL_SMOOTHING = 0.1f
        /** A raw normal this far off is a glitch, unless it lasts [CHANGE_FRAMES] frames (a new surface). */
        const val CHANGE_DEG = 20f
        const val CHANGE_FRAMES = 4

        /** How a frame with no usable normal counts toward [instability]; deviations are capped here too. */
        const val MAX_DEVIATION_DEG = 25f
        /** Per-frame weight of the instability average (~0.7 s memory at 30 fps). */
        const val INSTABILITY_ALPHA = 0.05f
        /** Start just past the give-up level, so a new surface has to settle briefly before the reticle tilts. */
        const val START_INSTABILITY = 13f
        const val STEADY_DEG = 8f
        const val UNSTEADY_DEG = 12f

        const val SNAP_ENTER_DEG = 10f
        const val SNAP_EXIT_DEG = 15f

        const val DEPTH_MAP_WIDTH = 90
        const val DEPTH_MAP_EVERY = 3
        const val DEPTH_MAP_FAR_M = 4f

        /** How far from a plane a depth sample may be and still count as on it; depth gets noisier with range. */
        fun toleranceAt(distance: Float) = max(MIN_TOLERANCE_M, TOLERANCE_PER_M * distance)

        fun FloatArray.facing(toCamera: FloatArray) = if ((this dot toCamera) < 0f) -this else this

        /** |(p - origin) · n| without allocating; this runs tens of thousands of times a frame. */
        fun planeDistance(p: FloatArray, origin: FloatArray, n: FloatArray) =
            abs((p[0] - origin[0]) * n[0] + (p[1] - origin[1]) * n[1] + (p[2] - origin[2]) * n[2])

        /** Angle between two unit vectors, ignoring which way they point. */
        fun angleDeg(a: FloatArray, b: FloatArray) =
            Math.toDegrees(acos(abs(a dot b).coerceAtMost(1f).toDouble())).toFloat()

        /** Angle of a normal from vertical: 0° for floors and ceilings, 90° for walls. */
        fun tiltDeg(n: FloatArray) = Math.toDegrees(acos(abs(n[1]).coerceAtMost(1f).toDouble())).toFloat()

        /** Red (near) through yellow, green and cyan to blue (far, [DEPTH_MAP_FAR_M]+). */
        fun depthColor(meters: Float): Int {
            val t = (meters / DEPTH_MAP_FAR_M).coerceIn(0f, 1f) * 4f
            val f = t - t.toInt()
            val (r, g, b) = when (t.toInt()) {
                0 -> Triple(1f, f, 0f)
                1 -> Triple(1f - f, 1f, 0f)
                2 -> Triple(0f, 1f, f)
                3 -> Triple(0f, 1f - f, 1f)
                else -> Triple(0f, 0f, 1f)
            }
            return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }

        /**
         * Normal of the least-squares plane through [points]: the eigenvector of their covariance
         * with the smallest eigenvalue. The adjugate has the same eigenvectors with that one's
         * eigenvalue the largest, so a few power iterations from the RANSAC guess converge on it.
         */
        fun leastSquaresNormal(points: List<FloatArray>, mean: FloatArray, guess: FloatArray): FloatArray {
            var xx = 0f; var xy = 0f; var xz = 0f; var yy = 0f; var yz = 0f; var zz = 0f
            for (p in points) {
                val x = p[0] - mean[0]
                val y = p[1] - mean[1]
                val z = p[2] - mean[2]
                xx += x * x; xy += x * y; xz += x * z
                yy += y * y; yz += y * z; zz += z * z
            }
            // Scale to unit trace so the products below don't underflow on small, close patches.
            val trace = xx + yy + zz
            if (trace < 1e-12f) return guess
            xx /= trace; xy /= trace; xz /= trace; yy /= trace; yz /= trace; zz /= trace
            val a00 = yy * zz - yz * yz
            val a01 = xz * yz - xy * zz
            val a02 = xy * yz - xz * yy
            val a11 = xx * zz - xz * xz
            val a12 = xy * xz - xx * yz
            val a22 = xx * yy - xy * xy
            var v = guess
            repeat(6) {
                v = vec(
                    a00 * v[0] + a01 * v[1] + a02 * v[2],
                    a01 * v[0] + a11 * v[1] + a12 * v[2],
                    a02 * v[0] + a12 * v[1] + a22 * v[2],
                ).normalized() ?: return guess
            }
            return v
        }
    }
}
