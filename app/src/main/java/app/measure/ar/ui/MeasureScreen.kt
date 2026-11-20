package app.measure.ar.ui

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.measure.ar.R
import app.measure.ar.ar.ArFrameState
import app.measure.ar.ar.ReticleShape
import app.measure.ar.ar.ScreenSegment
import app.measure.ar.ar.TrackingIssue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MeasureScreen(
    state: ArFrameState,
    unit: LengthUnit,
    onUnitChange: (LengthUnit) -> Unit,
    onAddPoint: () -> Unit,
    onBeginMove: () -> Unit,
    onEndMove: () -> Unit,
    onUndo: () -> Unit,
    onWeld: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearAll: () -> Unit,
    onDebugChange: (Boolean) -> Unit,
    camera: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.snapped) {
        if (state.snapped) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    var unitsExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = unitsExpanded) { unitsExpanded = false }

    // A short-lived tip, e.g. when delete is tapped with nothing under the reticle.
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2_500)
            hint = null
        }
    }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Measurement", text).toClipEntry()) }
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) hint = "Copied $text"
    }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    // Debug overlay toggle.
    var showDebug by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showDebug) { onDebugChange(showDebug) }

    Box(Modifier.fillMaxSize()) {
        camera()
        MeasurementOverlay(state, unit, onCopy = copy)
        if (showDebug) DebugOverlay(state.debug)

        if (unitsExpanded) {
            // Tapping anywhere else closes the unit picker.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null) { unitsExpanded = false },
            )
        }

        CornerButton(
            onClick = { showHelp = true },
            // Long-pressing help toggles the hidden debug overlay.
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                showDebug = !showDebug
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp)
                .semantics { contentDescription = "Help" },
        ) {
            Text(
                text = "?",
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        UnitSwitcher(
            selected = unit,
            expanded = unitsExpanded,
            onExpandedChange = { unitsExpanded = it },
            onSelect = onUnitChange,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp),
        )

        val tracking = statusMessage(state)
        StatusPill(
            message = tracking ?: hint ?: "Tap the link button to weld these points".takeIf { state.canWeld },
            loading = tracking != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 80.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Readout(readoutFor(state, unit), onCopy = copy)
            Controls(
                state = state,
                onAddPoint = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onAddPoint()
                },
                onBeginMove = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBeginMove()
                },
                onEndMove = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onEndMove()
                },
                onUndo = onUndo,
                onWeld = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onWeld()
                },
                onDelete = {
                    if (state.canDelete) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDeleteSelected()
                    } else {
                        hint = "Aim at a point or line to delete it. Hold to clear all."
                    }
                },
                onClearAll = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClearAll()
                    hint = "Cleared all. Tap undo to bring them back."
                },
            )
        }
    }

    if (showHelp) HelpSheet(onDismiss = { showHelp = false })
}

/** A small round button matching the collapsed unit switcher. */
@Composable
private fun CornerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier
                .padding(4.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

private class Tip(val icon: Int, val title: String, val body: String)

private val Tips = listOf(
    Tip(R.drawable.ic_move, "Move a point", "Hold + to pick up a point, then aim where it should go. Release to drop it."),
    Tip(R.drawable.ic_link, "Join points", "While moving a point, aim at another point and tap the link button."),
    Tip(R.drawable.ic_shape, "Measure area", "End a line on its starting point to close the shape and see its area."),
    Tip(R.drawable.ic_copy, "Copy", "Tap any measurement to copy it."),
    Tip(R.drawable.ic_delete, "Clear all", "Hold the delete button to clear everything. Undo brings it back."),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HelpSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val tipShapes = listOf(
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Pill,
        MaterialShapes.Pentagon,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Sunny,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Tips", style = MaterialTheme.typography.headlineSmallEmphasized)
            Tips.forEachIndexed { index, tip ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(tipShapes[index % tipShapes.size].toShape())
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(tip.icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tip.title, style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(
                            tip.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Got it") }
        }
    }
}

private data class ReadoutText(val value: String, val caption: String?)

/** Live length while measuring; otherwise the total of whatever group of lines is hovered. */
private fun readoutFor(state: ArFrameState, unit: LengthUnit): ReadoutText? {
    if (state.measuring) return state.live?.let { ReadoutText(unit.format(it.meters), null) }
    val total = state.hoverTotal ?: return null
    val caption = when {
        state.hoverClosed -> "Perimeter"
        state.hoverCount > 1 -> "Total of ${state.hoverCount} lines"
        else -> null
    }
    return ReadoutText(unit.format(total), caption)
}

private fun statusMessage(state: ArFrameState): String? = when (state.trackingIssue) {
    TrackingIssue.Initializing -> "Move your phone slowly to get started"
    TrackingIssue.ExcessiveMotion -> "Slow down"
    TrackingIssue.InsufficientLight -> "More light needed"
    TrackingIssue.InsufficientFeatures -> "Point at a surface with more detail"
    TrackingIssue.CameraUnavailable -> "Camera unavailable"
    null -> if (!state.hasSurface) "Point at a surface" else null
}

/**
 * A small circle in the corner showing the current unit. Tapping it grows it sideways into a
 * connected button group of every unit; picking one shrinks it back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UnitSwitcher(
    selected: LengthUnit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LengthUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = true) { _, _ -> motion.defaultSpatialSpec() })
            },
            contentAlignment = Alignment.CenterEnd,
            label = "units",
        ) { isExpanded ->
            if (isExpanded) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    val units = LengthUnit.entries
                    units.forEachIndexed { index, unit ->
                        ToggleButton(
                            checked = unit == selected,
                            onCheckedChange = {
                                onSelect(unit)
                                // Let the selection morph play before folding back up.
                                scope.launch {
                                    delay(220)
                                    onExpandedChange(false)
                                }
                            },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                units.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .semantics { role = Role.RadioButton },
                        ) {
                            Text(unit.label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClickLabel = "Change units") { onExpandedChange(true) }
                        .semantics { contentDescription = "Units: ${selected.label}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selected.label,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusPill(message: String?, loading: Boolean, modifier: Modifier = Modifier) {
    // Keep showing the last text while animating out.
    var last by remember { mutableStateOf(message) }
    if (message != null) last = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                    .padding(start = if (loading) 8.dp else 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) {
                    LoadingIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                AnimatedContent(targetState = last.orEmpty(), label = "status") { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Readout(readout: ReadoutText?, onCopy: (String) -> Unit) {
    // Keep showing the last text while animating out.
    var last by remember { mutableStateOf(readout) }
    if (readout != null) last = readout
    AnimatedVisibility(
        visible = readout != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        val shown = last ?: return@AnimatedVisibility
        Surface(
            onClick = { onCopy(shown.value) },
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                shown.caption?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Text(
                    text = shown.value,
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Tracks press-and-hold on a button. [onHold] runs once the press passes the long-press timeout and
 * returns whether it handled the hold; if so the button's click is swallowed and [onRelease] runs
 * when the finger lifts.
 */
private class HoldGesture(val interactionSource: MutableInteractionSource) {
    var suppressClick = false

    /** True if this click ends a handled hold and should be ignored. */
    fun consumeClick(): Boolean = suppressClick.also { suppressClick = false }
}

@Composable
private fun rememberHoldGesture(onHold: () -> Boolean, onRelease: () -> Unit = {}): HoldGesture {
    val gesture = remember { HoldGesture(MutableInteractionSource()) }
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentOnHold by rememberUpdatedState(onHold)
    val currentOnRelease by rememberUpdatedState(onRelease)
    LaunchedEffect(gesture) {
        var holdJob: Job? = null
        var held = false
        gesture.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    gesture.suppressClick = false
                    holdJob = launch {
                        delay(timeout)
                        if (currentOnHold()) {
                            held = true
                            gesture.suppressClick = true
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    holdJob?.cancel()
                    if (held) {
                        held = false
                        currentOnRelease()
                    }
                }
            }
        }
    }
    return gesture
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(
    state: ArFrameState,
    onAddPoint: () -> Unit,
    onBeginMove: () -> Unit,
    onEndMove: () -> Unit,
    onUndo: () -> Unit,
    onWeld: () -> Unit,
    onDelete: () -> Unit,
    onClearAll: () -> Unit,
) {
    // Holding add over an existing dot picks it up; releasing drops it.
    val addHold = rememberHoldGesture(
        onHold = { state.canPickUp.also { if (it) onBeginMove() } },
        onRelease = onEndMove,
    )
    // Holding delete clears everything (undoable).
    val deleteHold = rememberHoldGesture(
        onHold = { state.canClear.also { if (it) onClearAll() } },
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // Undo doubles as cancel (mid-measurement or mid-drag) and as weld (dragging onto a point).
        val undoAction = when {
            state.canWeld -> UndoAction.Weld
            state.undoCancels -> UndoAction.Cancel
            else -> UndoAction.Undo
        }
        FilledTonalIconButton(
            onClick = { if (undoAction == UndoAction.Weld) onWeld() else onUndo() },
            enabled = state.canUndo || state.canWeld,
            shapes = IconButtonDefaults.shapes(),
            colors = if (undoAction == UndoAction.Weld) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                )
            } else {
                IconButtonDefaults.filledTonalIconButtonColors()
            },
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
        ) {
            AnimatedContent(
                targetState = undoAction,
                transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
                label = "undoIcon",
            ) { action ->
                Icon(painterResource(action.drawable), contentDescription = action.description)
            }
        }

        FilledIconButton(
            onClick = { if (!addHold.consumeClick()) onAddPoint() },
            enabled = state.hasSurface || state.moving || state.canPickUp,
            shapes = IconButtonDefaults.shapes(),
            interactionSource = addHold.interactionSource,
            modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
        ) {
            val icon = when {
                state.moving -> AddIcon.Move
                state.measuring -> AddIcon.Finish
                else -> AddIcon.Add
            }
            AnimatedContent(
                targetState = icon,
                transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
                label = "addIcon",
            ) { target ->
                Icon(
                    painterResource(target.drawable),
                    contentDescription = target.description,
                    modifier = Modifier.size(IconButtonDefaults.largeIconSize),
                )
            }
        }

        FilledTonalIconButton(
            onClick = { if (!deleteHold.consumeClick()) onDelete() },
            enabled = (state.canDelete || state.canClear) && !state.measuring && !state.moving,
            shapes = IconButtonDefaults.shapes(),
            interactionSource = deleteHold.interactionSource,
            // Light up when a tap would delete something, the same way undo lights up for weld.
            colors = if (state.canDelete && !state.measuring && !state.moving) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledTonalIconButtonColors()
            },
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
        ) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = "Delete selected. Hold to clear all.",
            )
        }
    }
}

private enum class UndoAction(val drawable: Int, val description: String) {
    Undo(R.drawable.ic_undo, "Undo"),
    Cancel(R.drawable.ic_close, "Cancel"),
    Weld(R.drawable.ic_link, "Weld points together"),
}

private enum class AddIcon(val drawable: Int, val description: String) {
    Add(R.drawable.ic_add, "Add point"),
    Finish(R.drawable.ic_check, "Finish measurement"),
    Move(R.drawable.ic_move, "Release to drop point"),
}

// --- AR overlay -------------------------------------------------------------------------------

private val LineColor = Color.White
private val ShadowColor = Color.Black.copy(alpha = 0.35f)

@Composable
private fun MeasurementOverlay(state: ArFrameState, unit: LengthUnit, onCopy: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.tertiary
    val reticleSize by animateDpAsState(
        targetValue = when {
            state.snapped -> 14.dp
            state.hasSurface -> 26.dp
            else -> 34.dp
        },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "reticleSize",
    )
    val reticleAlpha by animateFloatAsState(
        targetValue = if (state.hasSurface) 1f else 0.5f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reticleAlpha",
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            state.polygons.forEach { polygon ->
                val outline = Path().apply {
                    polygon.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                    close()
                }
                drawPath(outline, fill, alpha = if (polygon.highlighted) 0.38f else 0.22f)
            }
            // Draw highlighted lines last so they sit on top of any they cross.
            state.segments.sortedBy { it.highlighted }.forEach { drawSegment(it, accent, dashed = false) }
            state.live?.let { drawSegment(it, accent, dashed = true) }
            state.pendingPoint?.let { drawEndpoint(it, accent) }

            val reticleCenter = state.reticle ?: center
            val ring = state.reticleShape.ellipsePath(reticleCenter, reticleSize.toPx())
            val dot = state.reticleShape.ellipsePath(reticleCenter, 4.dp.toPx())
            drawPath(ring, ShadowColor, style = Stroke(5.dp.toPx()), alpha = reticleAlpha)
            drawPath(ring, LineColor, style = Stroke(2.5.dp.toPx()), alpha = reticleAlpha)
            drawPath(dot, if (state.snapped) accent else LineColor, alpha = reticleAlpha)
        }

        val colors = MaterialTheme.colorScheme
        state.polygons.forEach {
            val area = unit.formatArea(it.squareMeters)
            PinnedLabel(
                at = it.center,
                text = area,
                onClick = { onCopy(area) },
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        }
        state.segments.forEach {
            val length = unit.format(it.meters)
            PinnedLabel(
                at = Offset((it.start.x + it.end.x) / 2f, (it.start.y + it.end.y) / 2f),
                text = length,
                onClick = { onCopy(length) },
                container = when {
                    it.selected -> colors.primary
                    it.highlighted -> colors.primaryContainer
                    else -> colors.surfaceContainerHighest
                },
                content = when {
                    it.selected -> colors.onPrimary
                    it.highlighted -> colors.onPrimaryContainer
                    else -> colors.onSurface
                },
            )
        }
    }
}

/** The reticle circle of [radius] px as it appears lying on the surface (see [ReticleShape]). */
private fun ReticleShape.ellipsePath(center: Offset, radius: Float): Path {
    // sqrt of the 2x2 SPD matrix M maps the unit circle onto the projected ellipse.
    val det = max(a * c - b * b, 0f)
    val s = sqrt(det)
    val t = sqrt(max(a + c + 2f * s, 1e-6f))
    val m00 = (a + s) / t
    val m01 = b / t
    val m11 = (c + s) / t
    return Path().apply {
        for (i in 0..ELLIPSE_STEPS) {
            val theta = 2f * PI.toFloat() * i / ELLIPSE_STEPS
            val x = cos(theta) * radius
            val y = sin(theta) * radius
            val px = center.x + m00 * x + m01 * y
            val py = center.y + m01 * x + m11 * y
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

private const val ELLIPSE_STEPS = 48

private fun DrawScope.drawSegment(segment: ScreenSegment, accent: Color, dashed: Boolean) {
    val effect = if (dashed) {
        androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
    } else null
    val width = if (segment.highlighted) 6.dp.toPx() else 4.dp.toPx()
    drawLine(ShadowColor, segment.start, segment.end, strokeWidth = width + 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = effect)
    drawLine(LineColor, segment.start, segment.end, strokeWidth = width, cap = StrokeCap.Round, pathEffect = effect)
    if (segment.highlighted) {
        drawLine(accent, segment.start, segment.end, strokeWidth = width - 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = effect)
    }
    if (segment.startVisible) drawEndpoint(segment.start, accent)
    if (segment.endVisible) drawEndpoint(segment.end, accent)
}

private fun DrawScope.drawEndpoint(at: Offset, accent: Color) {
    drawCircle(ShadowColor, radius = 9.dp.toPx(), center = at)
    drawCircle(LineColor, radius = 7.5.dp.toPx(), center = at)
    drawCircle(accent, radius = 4.5.dp.toPx(), center = at)
}

/** A tappable pill centered on a screen point (segment midpoints, shape centers). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PinnedLabel(
    at: Offset,
    text: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    style: TextStyle = MaterialTheme.typography.titleMediumEmphasized,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        shadowElevation = 4.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(placeable.width, placeable.height) {
                placeable.place(
                    (at.x - placeable.width / 2f).roundToInt(),
                    (at.y - placeable.height / 2f).roundToInt(),
                )
            }
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = style,
            color = content,
        )
    }
}
