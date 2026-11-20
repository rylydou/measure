package app.measure.ar.ui

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

enum class LengthUnit(val label: String) {
    Centimeters("cm"),
    Meters("m"),
    Inches("in"),
    Feet("ft");

    fun format(meters: Float): String = when (this) {
        Centimeters -> "%.1f cm".format(meters * 100f)
        Meters -> "%.2f m".format(meters)
        Inches -> "%.1f in".format(meters / METERS_PER_INCH)
        Feet -> {
            val totalInches = (meters / METERS_PER_INCH).roundToInt()
            val feet = totalInches / 12
            val inches = totalInches % 12
            if (feet == 0) "$inches″" else "$feet′ $inches″"
        }
    }

    /** Area in this unit squared; switches to the larger unit once small units get unwieldy. */
    fun formatArea(squareMeters: Float): String = when (this) {
        Centimeters ->
            if (squareMeters < 1f) "%,.0f cm²".format(squareMeters * 10_000f) else "%.2f m²".format(squareMeters)
        Meters -> "%.2f m²".format(squareMeters)
        Inches -> {
            val sqIn = squareMeters / (METERS_PER_INCH * METERS_PER_INCH)
            if (sqIn < 144f) "%.0f in²".format(sqIn) else "%.1f ft²".format(sqIn / 144f)
        }
        Feet -> "%.1f ft²".format(squareMeters / (METERS_PER_INCH * METERS_PER_INCH * 144f))
    }

    companion object {
        private const val METERS_PER_INCH = 0.0254f
        private const val PREFS = "measure"
        private const val KEY = "unit"

        fun load(context: Context): LengthUnit {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: localeDefault()
        }

        fun save(context: Context, unit: LengthUnit) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, unit.name).apply()
        }

        private fun localeDefault(): LengthUnit =
            if (Locale.getDefault().country in setOf("US", "LR", "MM")) Inches else Centimeters
    }
}
