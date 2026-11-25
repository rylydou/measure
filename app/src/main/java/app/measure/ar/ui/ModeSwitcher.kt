package app.measure.ar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.measure.ar.R

/** Bottom padding that screen content needs to stay clear of the floating [ModeSwitcher]. */
val ModeSwitcherClearance = 92.dp

/** Floating Measure / Level switcher, styled like the unit switcher: a connected button group in a pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModeSwitcher(mode: AppMode, onModeChange: (AppMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            AppMode.entries.forEachIndexed { index, entry ->
                ToggleButton(
                    checked = entry == mode,
                    onCheckedChange = { if (entry != mode) onModeChange(entry) },
                    shapes = if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .semantics { role = Role.Tab },
                ) {
                    Icon(
                        painterResource(entry.icon),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(entry.label, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val AppMode.icon
    get() = when (this) {
        AppMode.Measure -> R.drawable.ic_ruler
        AppMode.Level -> R.drawable.ic_level
    }

private val AppMode.label
    get() = when (this) {
        AppMode.Measure -> "Measure"
        AppMode.Level -> "Level"
    }
