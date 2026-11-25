package app.measure.ar.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class AppMode { Measure, Level }

/** Unit vector pointing away from the ground, in device coordinates (x right, y up, z out of the screen). */
private data class Up(val x: Float, val y: Float, val z: Float)

/**
 * A bubble level in the style of Apple's Measure app, drawn as one continuous fluid.
 *
 * Standing on an edge, the accent color fills the screen from the top down to the horizon. Tilting
 * the screen to face up pulls that fill into a round bubble on a black background (a floor level);
 * tilting it to face down shrinks the black part into a bubble instead (a ceiling level). The
 * readout is the tilt from upright on an edge and the tilt from flat as a bubble. Tap to measure
 * against the current angle; tap again to go back to true level.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LevelScreen(modifier: Modifier = Modifier) {
    val level = remember { LevelState() }
    val context = LocalContext.current
    LifecycleResumeEffect(level) {
        val stop = listenForGravity(context, level::update)
        onPauseOrDispose { stop() }
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(level.isLevel) {
        if (level.isLevel) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    LaunchedEffect(level.rollAligned) {
        if (level.rollAligned) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // A saved reference angle swaps the accent to tertiary so it's clear the reading is relative.
    val relative = level.calibrated
    val accent by animateColorAsState(
        if (relative) colors.tertiary else colors.primary, motion.defaultEffectsSpec(), label = "accent",
    )
    val onAccent by animateColorAsState(
        if (relative) colors.onTertiary else colors.onPrimary, motion.defaultEffectsSpec(), label = "onAccent",
    )
    val container by animateColorAsState(
        if (relative) colors.tertiaryContainer else colors.primaryContainer, motion.defaultEffectsSpec(), label = "container",
    )
    val onContainer by animateColorAsState(
        if (relative) colors.onTertiaryContainer else colors.onPrimaryContainer, motion.defaultEffectsSpec(), label = "onContainer",
    )
    val levelProgress by animateFloatAsState(
        if (level.isLevel) 1f else 0f, motion.fastEffectsSpec(), label = "level",
    )
    val alignProgress by animateFloatAsState(
        if (level.rollAligned) 1f else 0f, motion.fastEffectsSpec(), label = "aligned",
    )
    // The bubble swells to fill the target ring with a little bounce once it's level.
    val bubbleScale by animateFloatAsState(
        if (level.isLevel) 1f else 0.9f, motion.defaultSpatialSpec(), label = "bubbleScale",
    )

    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.displayLargeEmphasized.copy(
        fontSize = 96.sp,
        lineHeight = 96.sp,
        fontFeatureSettings = "tnum",
    )
    val text = remember(level.degrees, style) { textMeasurer.measure("${level.degrees}°", style) }
    val degreeSignWidth = remember(style) { textMeasurer.measure("°", style).size.width }
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    val morphPath = remember { Path() }
    val onBlack = colors.onSurface

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = null, indication = null, onClickLabel = "Set reference angle") {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                level.toggleReference()
            }
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = if (level.isLevel) "Level" else "${level.degrees} degrees"
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val up = level.up ?: return@Canvas
            val palette = Palette(
                fill = lerp(container, accent, levelProgress),
                onFill = lerp(onContainer, onAccent, levelProgress),
                onBlack = lerp(onBlack, accent, levelProgress),
                accent = accent,
            )
            drawLevel(level, up, palette, levelProgress, alignProgress, bubbleScale, morph, morphPath, text, degreeSignWidth)
        }

        AnimatedVisibility(
            visible = level.calibrated,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            Surface(shape = CircleShape, color = colors.tertiaryContainer, shadowElevation = 6.dp) {
                Text(
                    "Relative to saved angle · Tap to reset",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onTertiaryContainer,
                )
            }
        }
    }
}

private class Palette(val fill: Color, val onFill: Color, val onBlack: Color, val accent: Color)

private fun DrawScope.drawLevel(
    level: LevelState,
    up: Up,
    palette: Palette,
    levelProgress: Float,
    alignProgress: Float,
    bubbleScale: Float,
    morph: Morph,
    morphPath: Path,
    text: TextLayoutResult,
    degreeSignWidth: Int,
) {
    val tilt = level.tilt
    val bubbleWeight = smoothstep(60f, 80f, tilt)
    val edgeWeight = 1f - smoothstep(25f, 45f, tilt)
    val roundness = (1f - level.error / 10f).coerceIn(0f, 1f)
    val fluid = fluidPath(
        up = up,
        bubbleWeight = bubbleWeight,
        bubbleScale = bubbleScale,
        // Far from level the bubble is a scalloped cookie; it smooths into a circle as it closes in.
        morphProgress = if (level.isLevel) 1f else 1f - (1f - roundness * roundness) * bubbleWeight,
        morph = morph,
        morphPath = morphPath,
    )
    drawPath(fluid, palette.fill)

    // Everything on top is drawn twice: once for the black area, then clipped to the fluid in its
    // contrasting color, so text and marks invert where the fluid crosses them.
    fun twoTone(block: DrawScope.(onFluid: Boolean) -> Unit) {
        block(false)
        clipPath(fluid) { block(true) }
    }

    val r0 = size.minDimension * BUBBLE_RADIUS
    if (bubbleWeight > 0f) {
        val tick = r0 * 1.35f
        twoTone { onFluid ->
            val color = if (onFluid) palette.onFill else palette.onBlack
            val alpha = bubbleWeight * if (onFluid) 0.7f else 0.5f + 0.5f * levelProgress
            drawLine(color, center - Offset(tick, 0f), center + Offset(tick, 0f), 1.dp.toPx(), alpha = alpha * 0.6f)
            drawLine(color, center - Offset(0f, tick), center + Offset(0f, tick), 1.dp.toPx(), alpha = alpha * 0.6f)
            drawCircle(color, radius = r0, center = center, style = Stroke(3.dp.toPx()), alpha = alpha)
        }
    }

    // The number stays upright against the real horizon, like the fluid. As a bubble there's no
    // horizon, so it eases back to the phone's own orientation.
    val textAngle = level.base + wrapDegrees(level.roll - level.base) * edgeWeight
    val digitsWidth = text.size.width - degreeSignWidth
    val textTopLeft = Offset(center.x - digitsWidth / 2f, center.y - text.size.height / 2f)
    twoTone { onFluid ->
        rotate(textAngle, center) {
            drawText(text, color = if (onFluid) palette.onFill else palette.onBlack, topLeft = textTopLeft)
        }
    }

    // Marks beside the number sit on the phone's own level line. When the horizon lines up with
    // them, the phone is level side to side.
    if (edgeWeight > 0f) {
        val gap = digitsWidth / 2f + degreeSignWidth + 24.dp.toPx()
        val length = 36.dp.toPx()
        val width = (3f + 2f * alignProgress).dp.toPx()
        twoTone { onFluid ->
            val color = when {
                onFluid -> palette.onFill
                else -> lerp(palette.onBlack, palette.accent, alignProgress)
            }
            rotate(level.base, center) {
                drawLine(color, Offset(center.x - gap - length, center.y), Offset(center.x - gap, center.y), width, StrokeCap.Round, alpha = edgeWeight)
                drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + gap + length, center.y), width, StrokeCap.Round, alpha = edgeWeight)
            }
        }
    }
}

/**
 * The accent-colored area. It's a disk whose edge nearest the screen center is the horizon: huge
 * (so the edge looks straight) when the phone is upright, shrinking to the bubble as it lies flat.
 * Face down, the disk is the black area instead and the accent is everything around it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun DrawScope.fluidPath(
    up: Up,
    bubbleWeight: Float,
    bubbleScale: Float,
    morphProgress: Float,
    morph: Morph,
    morphPath: Path,
): Path {
    val diagonal = hypot(size.width, size.height)
    val r0 = size.minDimension * BUBBLE_RADIUS
    val t = asin(abs(up.z).coerceAtMost(1f))
    val s = sin(t)
    val s2 = s * s

    // Radius r0/sin²: effectively infinite on an edge, exactly r0 flat. The near edge starts at the
    // screen center, then the shape drifts toward the raised side as it rounds into the bubble.
    val baseRadius = r0 / max(s2, 1e-7f)
    val scale = 1f + (bubbleScale - 1f) * bubbleWeight
    val radius = baseRadius * scale

    val planar = hypot(up.x, up.y)
    val upOnScreen = if (planar > 1e-4f) Offset(up.x / planar, -up.y / planar) else Offset(0f, -1f)
    val dir = if (up.z >= 0f) upOnScreen else -upOnScreen

    // How far the fluid drifts: scaled to the room on screen in that direction while it's a
    // horizon, so landscape behaves like portrait, but even in every direction as a bubble.
    val room = abs(dir.x) * size.width / 2f + abs(dir.y) * size.height / 2f
    val travel = room * HORIZON_TRAVEL + (size.minDimension * BUBBLE_TRAVEL - room * HORIZON_TRAVEL) * bubbleWeight
    val nearEdge = -r0 * s2 + travel * cos(t) * s + baseRadius - radius
    val perp = Offset(-dir.y, dir.x)
    val near = center + dir * nearEdge

    val disk = if (radius < 2f * diagonal) {
        val diskCenter = near + dir * radius
        if (bubbleWeight > 0f && radius < 3f * r0) {
            morphPath.rewind()
            morph.toPath(morphProgress, morphPath)
            val bounds = morphPath.getBounds()
            // Roll the bubble as it travels, like a ball.
            val rollDegrees = (dir.x * (nearEdge + radius) / r0) * 180f / PI.toFloat()
            val matrix = android.graphics.Matrix().apply {
                setTranslate(-bounds.center.x, -bounds.center.y)
                postScale(2f * radius / bounds.maxDimension, 2f * radius / bounds.maxDimension)
                postRotate(rollDegrees)
                postTranslate(diskCenter.x, diskCenter.y)
            }
            Path().apply {
                addPath(morphPath)
                asAndroidPath().transform(matrix)
            }
        } else {
            Path().apply { addOval(Rect(diskCenter, radius)) }
        }
    } else {
        // Too big to hand to the renderer as a circle: trace only the arc that can reach the
        // screen, then close the shape far beyond it on the inside.
        val span = min(1.3f * 2f * asin(diagonal / (2f * radius)), PI.toFloat() / 2f)
        Path().apply {
            for (i in 0..ARC_STEPS) {
                val theta = -span + 2f * span * i / ARC_STEPS
                val half = sin(theta / 2f)
                val point = near + dir * (2f * radius * half * half) + perp * (radius * sin(theta))
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            val endLateral = radius * sin(span)
            val endDepth = 2f * radius * sin(span / 2f) * sin(span / 2f)
            val far = near + dir * (endDepth + 3f * diagonal)
            lineTo((far + perp * endLateral).x, (far + perp * endLateral).y)
            lineTo((far - perp * endLateral).x, (far - perp * endLateral).y)
            close()
        }
    }
    if (up.z >= 0f) return disk
    val screen = Path().apply { addRect(Rect(Offset.Zero, size)) }
    return Path().apply { op(screen, disk, PathOperation.Difference) }
}

private const val BUBBLE_RADIUS = 0.3f
private const val BUBBLE_TRAVEL = 1.2f
private const val HORIZON_TRAVEL = 1.07f
private const val ARC_STEPS = 64

@Stable
private class LevelState {
    /** The latest smoothed reading (relative to the reference, if set), or null until the sensor reports. */
    var up by mutableStateOf<Up?>(null)
        private set
    private var raw: Up? = null

    private var reference by mutableStateOf<Rotation?>(null)
    val calibrated get() = reference != null

    /** Degrees the screen faces away from vertical: 0 standing on an edge, 90 lying flat. */
    var tilt by mutableFloatStateOf(0f)
        private set

    /** Degrees off level: the tilt from upright on an edge, or from flat as a bubble. */
    var error by mutableFloatStateOf(0f)
        private set
    var isLevel by mutableStateOf(false)
        private set

    /** The whole-degree readout, with a little hysteresis so it doesn't flicker between numbers. */
    var degrees by mutableIntStateOf(0)
        private set

    /** Clockwise screen rotation of the horizon, in degrees. */
    var roll by mutableFloatStateOf(0f)
        private set

    /** The screen orientation the phone is nearest to (0, ±90, 180), for the marks and text. */
    var base by mutableFloatStateOf(0f)
        private set
    private var hasBase = false

    /** On an edge with the horizon lined up with the marks. */
    var rollAligned by mutableStateOf(false)
        private set

    fun update(reading: Up) {
        raw = reading
        val u = reference?.apply(reading) ?: reading
        up = u
        tilt = asin(abs(u.z).coerceAtMost(1f)).toDegrees()
        error = min(tilt, 90f - tilt)

        // The horizon's direction is only meaningful while the phone isn't close to flat.
        if (hypot(u.x, u.y) > 0.35f) {
            roll = atan2(u.x, u.y).toDegrees()
            if (!hasBase || abs(wrapDegrees(roll - base)) > 50f) {
                base = wrapDegrees((roll / 90f).roundToInt() * 90f)
                hasBase = true
            }
            val rollError = abs(wrapDegrees(roll - base))
            rollAligned = tilt < 45f && rollError < if (rollAligned) 1f else 0.5f
        } else {
            roll = base
            rollAligned = false
        }

        isLevel = error < if (isLevel) 1f else 0.5f
        degrees = when {
            isLevel -> 0
            abs(error - degrees) > 0.6f -> error.roundToInt()
            else -> degrees
        }.let { if (!isLevel && it == 0) 1 else it }
    }

    fun toggleReference() {
        reference = if (reference != null) null else raw?.let(Rotation::toNearestLevel)
        raw?.let(::update)
    }

    private fun Float.toDegrees() = this * 180f / PI.toFloat()
}

/** A rotation (axis and angle) applied to readings so a saved pose reads as level. */
private class Rotation(private val axis: Up, private val cos: Float, private val sin: Float) {
    fun apply(v: Up): Up {
        // Rodrigues' rotation formula.
        val k = axis
        val dot = k.x * v.x + k.y * v.y + k.z * v.z
        val cx = k.y * v.z - k.z * v.y
        val cy = k.z * v.x - k.x * v.z
        val cz = k.x * v.y - k.y * v.x
        return Up(
            v.x * cos + cx * sin + k.x * dot * (1f - cos),
            v.y * cos + cy * sin + k.y * dot * (1f - cos),
            v.z * cos + cz * sin + k.z * dot * (1f - cos),
        )
    }

    companion object {
        /** The rotation taking [from] to the level pose it's closest to: flat, or upright on an edge. */
        fun toNearestLevel(from: Up): Rotation? {
            val to = when {
                abs(from.z) >= sqrt(0.5f) -> Up(0f, 0f, sign(from.z))
                abs(from.x) > abs(from.y) -> Up(sign(from.x), 0f, 0f)
                else -> Up(0f, sign(from.y), 0f)
            }
            val ax = from.y * to.z - from.z * to.y
            val ay = from.z * to.x - from.x * to.z
            val az = from.x * to.y - from.y * to.x
            val length = sqrt(ax * ax + ay * ay + az * az)
            if (length < 1e-6f) return null
            val cos = (from.x * to.x + from.y * to.y + from.z * to.z).coerceIn(-1f, 1f)
            return Rotation(Up(ax / length, ay / length, az / length), cos, sin(acos(cos)))
        }
    }
}

private fun wrapDegrees(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Streams a smoothed, normalized "up" vector from the gravity sensor (falling back to the raw
 * accelerometer) until the returned function is called.
 */
private fun listenForGravity(context: Context, onReading: (Up) -> Unit): () -> Unit {
    val sensors = context.getSystemService(SensorManager::class.java) ?: return {}
    val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: return {}
    // The fused gravity sensor is already clean; raw acceleration needs a heavier filter.
    val smoothing = if (sensor.type == Sensor.TYPE_GRAVITY) 0.25f else 0.08f
    val listener = object : SensorEventListener {
        private var x = 0f
        private var y = 0f
        private var z = 0f
        private var started = false

        override fun onSensorChanged(event: SensorEvent) {
            val (rx, ry, rz) = event.values
            if (started) {
                x += smoothing * (rx - x)
                y += smoothing * (ry - y)
                z += smoothing * (rz - z)
            } else {
                x = rx; y = ry; z = rz
                started = true
            }
            val length = sqrt(x * x + y * y + z * z)
            if (length > 1e-3f) onReading(Up(x / length, y / length, z / length))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    return { sensors.unregisterListener(listener) }
}
