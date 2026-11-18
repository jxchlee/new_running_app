package com.running2.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Where the sheet rests. COLLAPSED shows only the header, so the map gets almost the whole screen. */
enum class SheetStop { COLLAPSED, HALF, EXPANDED }

private const val FLING_VELOCITY = 1200f

/**
 * A map that fills the screen with a sheet on top of it. The user drags the sheet's header (or taps
 * its handle) to trade map area for numbers: header only → half screen → nearly full screen.
 * The body scrolls on its own, so dragging inside it never fights with moving the sheet.
 *
 * [onSheetSettled] reports the resting sheet height in pixels so the map can pad its camera and
 * keep the route centred in the part that is still visible.
 */
@Composable
fun MapSheetLayout(
    initialStop: SheetStop,
    onSheetSettled: (Int) -> Unit,
    map: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    header: @Composable ColumnScope.() -> Unit,
    body: (@Composable ColumnScope.() -> Unit)?
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullPx = with(density) { maxHeight.toPx() }
        val topReservePx = with(density) { 72.dp.toPx() }
        var headerPx by remember { mutableStateOf(0f) }
        val height = remember { Animatable(0f) }
        var stop by rememberSaveable { mutableStateOf(initialStop) }
        var initialized by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val expandedPx = (fullPx - topReservePx).coerceAtLeast(0f)
        val collapsedPx = headerPx.coerceAtMost(expandedPx)
        val halfPx = (fullPx * 0.5f).coerceIn(collapsedPx, expandedPx)
        fun anchor(s: SheetStop) = when (s) {
            SheetStop.COLLAPSED -> collapsedPx
            SheetStop.HALF -> halfPx
            SheetStop.EXPANDED -> expandedPx
        }
        fun settle(target: SheetStop) {
            stop = target
            scope.launch {
                height.animateTo(anchor(target), spring(stiffness = 500f))
                onSheetSettled(height.value.toInt())
            }
        }

        // Re-anchor when the header grows/shrinks (e.g. a paused banner appears) or the screen rotates.
        LaunchedEffect(collapsedPx, halfPx, expandedPx, body != null) {
            if (headerPx <= 0f) return@LaunchedEffect
            val target = if (body == null) collapsedPx else anchor(stop)
            if (!initialized) { height.snapTo(target); initialized = true } else height.animateTo(target)
            onSheetSettled(height.value.toInt())
        }

        Box(Modifier.fillMaxSize()) { map() }
        Box(Modifier.fillMaxSize(), content = overlay)

        val dragState = rememberDraggableState { delta ->
            if (body != null) scope.launch { height.snapTo((height.value - delta).coerceIn(collapsedPx, expandedPx)) }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (initialized && body != null) Modifier.height(with(density) { height.value.toDp() }) else Modifier),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 2.dp,
            shadowElevation = 12.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    // Measure the header at its natural height even while the sheet is still sized
                    // for different content (e.g. ready → running); otherwise the collapsed stop
                    // would lock onto a clipped height.
                    Modifier.fillMaxWidth()
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .onSizeChanged { headerPx = it.height.toFloat() }
                        .draggable(dragState, Orientation.Vertical, enabled = body != null,
                            onDragStopped = { velocity ->
                                val current = height.value
                                val stops = SheetStop.values().sortedBy { anchor(it) }
                                val target = when {
                                    velocity < -FLING_VELOCITY -> stops.firstOrNull { anchor(it) > current + 1f } ?: SheetStop.EXPANDED
                                    velocity > FLING_VELOCITY -> stops.lastOrNull { anchor(it) < current - 1f } ?: SheetStop.COLLAPSED
                                    else -> stops.minByOrNull { abs(anchor(it) - current) } ?: stop
                                }
                                settle(target)
                            })
                ) {
                    val handleInteraction = remember { MutableInteractionSource() }
                    if (body != null) Box(
                        Modifier.fillMaxWidth().clickable(handleInteraction, null) {
                            settle(when (stop) {
                                SheetStop.COLLAPSED -> SheetStop.HALF
                                SheetStop.HALF -> SheetStop.EXPANDED
                                SheetStop.EXPANDED -> SheetStop.HALF
                            })
                        }.padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    header()
                }
                if (body != null && initialized) Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .navigationBarsPadding(),
                    content = body
                )
            }
        }
    }
}
