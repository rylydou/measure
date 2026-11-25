package app.measure.ar.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
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
import kotlinx.coroutines.delay
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
 * Standing on an edge, the accent color fills the screen from the top down to a flat horizon.
 * Tilting the screen to face up slides the horizon off the top, then an accent bubble rolls in on
 * black (a floor level); tilting it to face down floods the screen with accent, then a black bubble
 * rolls in (a ceiling level). The readout is the tilt from upright on an edge and the tilt from flat
 * as a bubble. Tap to measure against the current angle; tap again to go back to true level.
 *
 * Leaving the phone face down on a level surface for a few seconds calibrates it: lying on its
 * screen it sits truly flat (face up it rocks on the camera bump), so whatever the sensor reads
 * there becomes the new zero.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LevelScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val level = remember {
        LevelState(
            calibration = LevelCalibration.load(context),
            onCalibrate = { LevelCalibration.save(context, it) },
        )
    }
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
    // The screen is hidden face down, so calibrating is felt rather than seen. The notice stays up
    // until a few seconds after the phone is picked up.
    LaunchedEffect(level.calibrations) {
        if (level.calibrations > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(level.justCalibrated, level.restingFaceDown) {
        if (level.justCalibrated && !level.restingFaceDown) {
            delay(3_000)
            level.dismissCalibratedNotice()
        }
    }

    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // A saved reference angle swaps the accent to tertiary so it's clear the reading is relative.
    val relative = level.relative
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

        val notice = when {
            level.justCalibrated -> "Calibrated to this surface"
            level.relative -> "Relative to saved angle · Tap to reset"
            else -> null
        }
        // Keep showing the last text while animating out.
        var lastNotice by remember { mutableStateOf(notice) }
        if (notice != null) lastNotice = notice
        AnimatedVisibility(
            visible = notice != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            Surface(shape = CircleShape, color = colors.tertiaryContainer, shadowElevation = 6.dp) {
                Text(
                    lastNotice.orEmpty(),
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
    // The target ring fades in between 30° and 25° from flat.
    val ringWeight = smoothstep(90f - RING_FADE_START, 90f - RING_FADE_END, tilt)
    val edgeWeight = 1f - smoothstep(EXIT_TILT - 10f, EXIT_TILT, tilt)
    val roundness = (1f - level.error / 10f).coerceIn(0f, 1f)
    val fluid = fluidPath(
        up = up,
        bubbleScale = bubbleScale,
        // Far from level the bubble is a scalloped cookie; it smooths into a circle as it closes in.
        morphProgress = if (level.isLevel) 1f else roundness * roundness,
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
    if (ringWeight > 0f) {
        twoTone { onFluid ->
            val color = if (onFluid) palette.onFill else palette.onBlack
            val alpha = ringWeight * if (onFluid) 0.7f else 0.5f + 0.5f * levelProgress
            drawCircle(color, radius = r0, center = center, style = Stroke(3.dp.toPx()), alpha = alpha)
        }
    }

    // The number stays upright against the real horizon, freezing near flat where that direction
    // stops being meaningful.
    val digitsWidth = text.size.width - degreeSignWidth
    val textTopLeft = Offset(center.x - digitsWidth / 2f, center.y - text.size.height / 2f)
    twoTone { onFluid ->
        rotate(level.textAngle, center) {
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
 * The accent-colored area.
 *
 * Near upright it's everything above a straight horizon. Tilting the screen to face up slides the
 * horizon off the top until the screen is black, then an accent bubble rolls in from that side.
 * Tilting it to face down slides the horizon off the bottom until the screen is all accent, then a
 * black bubble rolls in from the bottom.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun DrawScope.fluidPath(
    up: Up,
    bubbleScale: Float,
    morphProgress: Float,
    morph: Morph,
    morphPath: Path,
): Path {
    val diagonal = hypot(size.width, size.height)
    val tilt = asin(abs(up.z).coerceAtMost(1f))
    val exit = EXIT_TILT * PI.toFloat() / 180f
    val faceUp = up.z >= 0f

    val planar = hypot(up.x, up.y)
    val upOnScreen = if (planar > 1e-4f) Offset(up.x / planar, -up.y / planar) else Offset(0f, -1f)
    // How far the screen reaches in that direction from its center (to its farthest corner).
    val room = abs(upOnScreen.x) * size.width / 2f + abs(upOnScreen.y) * size.height / 2f

    if (tilt < exit) {
        // The horizon reaches the farthest corner exactly as the bubble starts to come in.
        val offset = room * sin(tilt) / sin(exit) * if (faceUp) 1f else -1f
        val onLine = center + upOnScreen * offset
        val across = Offset(-upOnScreen.y, upOnScreen.x) * diagonal
        val beyond = upOnScreen * (2f * diagonal)
        return Path().apply {
            moveTo((onLine + across).x, (onLine + across).y)
            lineTo((onLine - across).x, (onLine - across).y)
            lineTo((onLine - across + beyond).x, (onLine - across + beyond).y)
            lineTo((onLine + across + beyond).x, (onLine + across + beyond).y)
            close()
        }
    }

    // The bubble sits off the raised side (face up) or lowered side (face down) of the screen and
    // moves in as the phone nears flat: steadily near flat, then quickly out past the edge.
    val r0 = size.minDimension * BUBBLE_RADIUS
    val radius = r0 * bubbleScale
    val fromFlat = PI.toFloat() / 2f - tilt
    val maxFromFlat = PI.toFloat() / 2f - exit
    val travel = size.minDimension * BUBBLE_TRAVEL
    val offscreen = room + radius * 1.25f
    val catchUp = max(0f, offscreen - travel * sin(maxFromFlat))
    val progress = fromFlat / maxFromFlat
    val distance = travel * sin(fromFlat) + catchUp * progress * progress * progress
    val dir = if (faceUp) upOnScreen else -upOnScreen
    val bubbleCenter = center + dir * distance

    morphPath.rewind()
    morph.toPath(morphProgress, morphPath)
    val bounds = morphPath.getBounds()
    // Roll the bubble as it travels, like a ball.
    val rollDegrees = (dir.x * distance / r0) * 180f / PI.toFloat()
    val matrix = android.graphics.Matrix().apply {
        setTranslate(-bounds.center.x, -bounds.center.y)
        postScale(2f * radius / bounds.maxDimension, 2f * radius / bounds.maxDimension)
        postRotate(rollDegrees)
        postTranslate(bubbleCenter.x, bubbleCenter.y)
    }
    val bubble = Path().apply {
        addPath(morphPath)
        asAndroidPath().transform(matrix)
    }
    if (faceUp) return bubble
    val screen = Path().apply { addRect(Rect(Offset.Zero, size)) }
    return Path().apply { op(screen, bubble, PathOperation.Difference) }
}

/** Degrees from upright where the horizon has left the screen and the bubble takes over. */
private const val EXIT_TILT = 45f

/** Degrees from flat over which the bubble's target ring fades in. */
private const val RING_FADE_START = 30f
private const val RING_FADE_END = 5f

/** Degrees from flat around which the number eases to a stop instead of turning with the horizon. */
private const val TEXT_FREEZE = 20f
private const val BUBBLE_RADIUS = 0.3f
private const val BUBBLE_TRAVEL = 1.2f

@Stable
private class LevelState(calibration: Up?, private val onCalibrate: (Up) -> Unit) {
    /** The latest smoothed reading (relative to the reference, if set), or null until the sensor reports. */
    var up by mutableStateOf<Up?>(null)
        private set

    /** The latest raw sensor reading, and that reading with the sensor calibration applied. */
    private var latest: Up? = null
    private var corrected: Up? = null

    /** Corrects the sensor's own tilt, from what it read lying face down on a level surface. */
    private var correction = calibration?.let { Rotation.between(it, FACE_DOWN) }

    /** A saved pose the readings are measured against (tap to set). */
    private var reference by mutableStateOf<Rotation?>(null)
    val relative get() = reference != null

    /** Lying face down, nearly flat, waiting to see whether it holds still long enough to calibrate. */
    var restingFaceDown by mutableStateOf(false)
        private set
    private var restAnchor: Up? = null
    private var restSince = 0L
    private var restSumX = 0f
    private var restSumY = 0f
    private var restSumZ = 0f
    /** Calibrate once per time it's laid down, not over and over while it stays there. */
    private var calibrationArmed = true

    /** Counts calibrations, so each one can be signalled. */
    var calibrations by mutableIntStateOf(0)
        private set
    var justCalibrated by mutableStateOf(false)
        private set

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

    /** Clockwise screen rotation of the horizon, in degrees. Held as a bubble, where there's no horizon. */
    var roll by mutableFloatStateOf(0f)
        private set

    /** Rotation of the number: follows the horizon, easing to a stop around [TEXT_FREEZE] from flat. */
    var textAngle by mutableFloatStateOf(0f)
        private set

    /** The screen orientation the phone is nearest to (0, ±90, 180), for the marks. */
    var base by mutableFloatStateOf(0f)
        private set
    private var hasBase = false

    /** On an edge with the horizon lined up with the marks. */
    var rollAligned by mutableStateOf(false)
        private set

    fun update(reading: Up) {
        latest = reading
        watchForCalibration(reading)
        val c = correction?.apply(reading) ?: reading
        corrected = c
        val u = reference?.apply(c) ?: c
        up = u
        tilt = asin(abs(u.z).coerceAtMost(1f)).toDegrees()
        error = min(tilt, 90f - tilt)

        // Near flat the horizon's direction is mostly noise, so the number stops turning there
        // rather than spinning around as the phone lies down. It eases between following and
        // frozen across a band, so it never snaps to a new angle when it starts following again.
        val follow = smoothstep(TEXT_FREEZE - 5f, TEXT_FREEZE + 10f, 90f - tilt)
        if (follow > 0f) {
            val target = atan2(u.x, u.y).toDegrees()
            val catchUp = if (follow >= 1f) 1f else follow * follow * follow
            textAngle = wrapDegrees(textAngle + wrapDegrees(target - textAngle) * catchUp)
        }

        // The horizon itself (and the marks that line up with it) only exist on an edge.
        if (tilt < EXIT_TILT) {
            roll = atan2(u.x, u.y).toDegrees()
            if (!hasBase || abs(wrapDegrees(roll - base)) > 50f) {
                base = wrapDegrees((roll / 90f).roundToInt() * 90f)
                hasBase = true
            }
            val rollError = abs(wrapDegrees(roll - base))
            rollAligned = tilt < 45f && rollError < if (rollAligned) 1f else 0.5f
        } else {
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
        reference = if (reference != null) null else corrected?.let(Rotation::toNearestLevel)
        justCalibrated = false
        latest?.let(::update)
    }

    fun dismissCalibratedNotice() {
        justCalibrated = false
    }

    /**
     * Calibrates once the raw reading has stayed face down within [CALIBRATION_MAX_TILT] of flat,
     * moving less than [CALIBRATION_STILLNESS], for [CALIBRATION_HOLD_MS].
     */
    private fun watchForCalibration(raw: Up) {
        val fromFaceDown = angleBetween(raw, FACE_DOWN)
        val resting = fromFaceDown < CALIBRATION_MAX_TILT
        if (fromFaceDown > CALIBRATION_REARM_TILT) calibrationArmed = true
        restingFaceDown = resting
        if (!resting || !calibrationArmed) {
            restAnchor = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val anchor = restAnchor
        if (anchor == null || angleBetween(raw, anchor) > CALIBRATION_STILLNESS) {
            restAnchor = raw
            restSince = now
            restSumX = 0f; restSumY = 0f; restSumZ = 0f
        }
        restSumX += raw.x; restSumY += raw.y; restSumZ += raw.z
        if (now - restSince < CALIBRATION_HOLD_MS) return

        val length = sqrt(restSumX * restSumX + restSumY * restSumY + restSumZ * restSumZ)
        val average = Up(restSumX / length, restSumY / length, restSumZ / length)
        correction = Rotation.between(average, FACE_DOWN)
        reference = null
        onCalibrate(average)
        calibrationArmed = false
        restAnchor = null
        justCalibrated = true
        calibrations++
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
        fun toNearestLevel(from: Up): Rotation? = between(
            from,
            when {
                abs(from.z) >= sqrt(0.5f) -> Up(0f, 0f, sign(from.z))
                abs(from.x) > abs(from.y) -> Up(sign(from.x), 0f, 0f)
                else -> Up(0f, sign(from.y), 0f)
            },
        )

        /** The smallest rotation taking unit vector [from] to unit vector [to]. */
        fun between(from: Up, to: Up): Rotation? {
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

private val FACE_DOWN = Up(0f, 0f, -1f)

/** Face down further off flat than this, the surface clearly isn't level, so don't calibrate. */
private const val CALIBRATION_MAX_TILT = 5f
/** Tilt off face down that counts as picking the phone back up. */
private const val CALIBRATION_REARM_TILT = 20f
private const val CALIBRATION_STILLNESS = 0.3f
private const val CALIBRATION_HOLD_MS = 3_000L

private fun angleBetween(a: Up, b: Up): Float =
    acos((a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)) * 180f / PI.toFloat()

/** The level's saved calibration: what the sensor read lying face down on a level surface. */
private object LevelCalibration {
    private const val PREFS = "measure"
    private const val KEY = "level_calibration"

    fun load(context: Context): Up? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val v = saved.split(",").mapNotNull { it.toFloatOrNull() }
        return if (v.size == 3) Up(v[0], v[1], v[2]) else null
    }

    fun save(context: Context, reading: Up) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, "${reading.x},${reading.y},${reading.z}")
            .apply()
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
