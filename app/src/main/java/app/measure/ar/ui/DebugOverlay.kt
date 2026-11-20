package app.measure.ar.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.measure.ar.ar.SurfaceDebug
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val Inlier = Color(0xFF4CFF6A)
private val Outlier = Color(0xFFFF4C4C)
private val Unfitted = Color.White
private val Panel = Color(0xB0000000)
private val LineStyle = TextStyle(color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace)
private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Where the panel starts: below the help and unit buttons. */
private val PanelTop = 72.dp
/** Largest reticle ring (when there's no surface), used when the patch outline isn't known. */
private val ReticleRadius = 34.dp
/** Gap kept between the panels and the reticle area. */
private val ReticleGap = 12.dp

/**
 * Full-screen debug layer: depth samples around the reticle (green on the fitted plane, red off it,
 * white when nothing was fitted), an optional full-screen depth map, and an info panel with device
 * details, session state, the surface estimate and a jitter graph. Everything is laid out to stay
 * clear of the reticle and the patch it samples, so screenshots show what the reticle was doing.
 */
@Composable
fun DebugOverlay(debug: SurfaceDebug?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val device = remember { deviceLines(context) }
    var fullDepth by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val map = debug?.depthMap
            if (fullDepth && map != null) {
                drawImage(
                    map,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    dstOffset = IntOffset.Zero,
                    alpha = 0.55f,
                )
            }
            if (debug == null) return@Canvas
            val mid = Offset(size.width / 2f, size.height / 2f)
            val dash = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            if (debug.ringRadiusPx > 0f) drawCircle(Color.Yellow, debug.ringRadiusPx, mid, alpha = 0.7f, style = dash)
            if (debug.patchRadiusPx > 0f) drawCircle(Color.White, debug.patchRadiusPx, mid, alpha = 0.5f, style = dash)
            debug.samples.forEachIndexed { i, p ->
                val color = when (debug.inliers.getOrNull(i)) {
                    true -> Inlier
                    false -> Outlier
                    null -> Unfitted
                }
                drawCircle(Color.Black, radius = 3.dp.toPx(), center = p, alpha = 0.6f)
                drawCircle(color, radius = 2.dp.toPx(), center = p)
            }
        }

        // Everything must stay outside this radius around the screen center (the reticle sits there).
        val patchRadius = with(LocalDensity.current) { (debug?.patchRadiusPx ?: 0f).toDp() }
        val keepOut = max(patchRadius, ReticleRadius) + ReticleGap
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PanelTop
        val panelMaxHeight = (maxHeight / 2 - keepOut - top).coerceAtLeast(0.dp)

        // Info panel: full width across the top, capped so it ends above the reticle area.
        Column(
            modifier = Modifier
                .padding(top = top, start = 8.dp, end = 8.dp)
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .clipToBounds()
                .background(Panel, RoundedCornerShape(10.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            device.forEach { Line(it) }
            Line("time ${LocalTime.now().format(ClockFormat)}")
            Divider()
            debug?.sessionLines?.forEach { Line(it) }
            if (!debug?.lines.isNullOrEmpty()) {
                Divider()
                debug.lines.forEach { Line(it) }
                JitterGraph(debug, Modifier.padding(top = 2.dp).fillMaxWidth().height(36.dp))
                Line("gray dev · yellow avg · green/red steady/flat limits · red bg = flat", size = 8)
            }
        }

        // Depth map thumbnail: middle of the left edge, beside (not over) the reticle area.
        val map = debug?.depthMap
        val thumbWidth = 56.dp
        if (map != null && maxWidth / 2 - keepOut >= thumbWidth + 16.dp) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = map,
                    contentDescription = "Depth map",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(thumbWidth, thumbWidth * map.height / map.width)
                        .clickable { fullDepth = !fullDepth },
                )
                Text("red near\nblue 4m+\ntap: full", style = LineStyle.copy(fontSize = 8.sp, lineHeight = 9.sp))
            }
        }
    }
}

@Composable
private fun Line(text: String, size: Int = 9) {
    Text(
        text,
        style = if (size == 9) LineStyle else LineStyle.copy(fontSize = size.sp, lineHeight = (size + 2).sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Divider(height: Dp = 1.dp) {
    Canvas(Modifier.padding(vertical = 2.dp).fillMaxWidth().height(height)) {
        drawRect(Color.White.copy(alpha = 0.25f))
    }
}

/** Static facts about the device and build, so a screenshot says where it came from. */
private fun deviceLines(context: Context): List<String> {
    val pm = context.packageManager
    val app = pm.getPackageInfo(context.packageName, 0)
    val arcore = runCatching { pm.getPackageInfo("com.google.ar.core", 0).versionName }.getOrNull() ?: "?"
    val metrics = context.resources.displayMetrics
    return listOf(
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · patch ${Build.VERSION.SECURITY_PATCH} · ${Build.ID}",
        "app ${app.versionName} (${app.longVersionCode}) · ARCore $arcore · " +
            "${metrics.widthPixels}x${metrics.heightPixels} @${"%.2f".format(metrics.density)}x",
    )
}

/** Deviation and instability over the last few seconds against the steady/flat thresholds. */
@Composable
private fun JitterGraph(debug: SurfaceDebug, modifier: Modifier) {
    Canvas(modifier.background(Color(0x40FFFFFF))) {
        val maxDeg = 25f
        val n = debug.instabilityHistory.size
        if (n < 2) return@Canvas
        val dx = size.width / (n - 1)
        fun y(deg: Float) = size.height * (1f - (deg / maxDeg).coerceIn(0f, 1f))

        // Shade the stretches where the reticle fell back to flat.
        debug.steadyHistory.forEachIndexed { i, steady ->
            if (!steady) drawRect(Outlier.copy(alpha = 0.18f), Offset(i * dx - dx / 2, 0f), Size(dx, size.height))
        }
        drawLine(Inlier, Offset(0f, y(debug.steadyBelowDeg)), Offset(size.width, y(debug.steadyBelowDeg)), strokeWidth = 1.dp.toPx())
        drawLine(Outlier, Offset(0f, y(debug.flatAboveDeg)), Offset(size.width, y(debug.flatAboveDeg)), strokeWidth = 1.dp.toPx())

        fun series(values: List<Float>, color: Color, width: Float) {
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * dx, y(v)) }
            drawPath(path, color, style = Stroke(width))
        }
        series(debug.deviationHistory, Color.LightGray.copy(alpha = 0.8f), 1.dp.toPx())
        series(debug.instabilityHistory, Color(0xFFFFD54F), 2.dp.toPx())
    }
}
