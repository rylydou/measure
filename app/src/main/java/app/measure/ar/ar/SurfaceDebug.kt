package app.measure.ar.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

// Debug info about the reticle's surface estimate, shown by the debug overlay (long-press the help button).

/** What [SurfaceEstimator] saw this frame, in world space. */
internal class EstimatorDebug(
    val lines: List<String>,
    /** This frame's depth samples around the reticle. */
    val samples: List<FloatArray>,
    /** Whether each sample lies on the fitted plane; null when no plane was fitted. */
    val inliers: List<Boolean>?,
    val deviation: Float,
    val instability: Float,
    val steady: Boolean,
    val depthMap: Bitmap?,
)

/** Debug info projected to screen for the overlay. Estimate fields are empty while not tracking. */
data class SurfaceDebug(
    /** Session-level info: camera config, frame rates, tracking, planes. */
    val sessionLines: List<String>,
    /** What the surface estimate saw this frame. */
    val lines: List<String> = emptyList(),
    val samples: List<Offset> = emptyList(),
    /** Per sample: true on the plane, false off it, null when nothing was fitted. */
    val inliers: List<Boolean?> = emptyList(),
    /** Recent per-frame normal deviation and the running instability, oldest first, in degrees. */
    val deviationHistory: List<Float> = emptyList(),
    val instabilityHistory: List<Float> = emptyList(),
    val steadyHistory: List<Boolean> = emptyList(),
    val depthMap: ImageBitmap? = null,
    val steadyBelowDeg: Float = 0f,
    val flatAboveDeg: Float = 0f,
    /** The ring that must be flat, and the wider patch the angle is fitted over (screen px); 0 hides them. */
    val ringRadiusPx: Float = 0f,
    val patchRadiusPx: Float = 0f,
)
