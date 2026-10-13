package com.running2.app.ui

import com.running2.app.data.PauseRow
import com.running2.app.data.FixTimeRow
import com.running2.app.data.RawFixRow
import com.running2.app.data.RunRow
import com.running2.app.core.Fix
import com.running2.app.core.FixReason
import com.running2.app.core.TrackEngine

private const val SIGNAL_TIMEOUT_MS = 15_000L

enum class GpsUiState { WAITING, RECEIVING, EXCLUDED, NO_FIX, PAUSED }

data class RunUiState(
    val elapsedMs: Long,
    val exerciseMs: Long,
    val averagePaceSecKm: Double?,
    val currentPaceSecKm: Double?,
    val gps: GpsUiState,
    val rawGapCount: Int,
    val deliveryGapCount: Int,
    val routeGapCount: Int,
    val incomplete: Boolean,
    val latestAccuracyM: Float?
)

/** Values on every screen come from the saved run and its versioned v1 track points. */
fun runUiState(run: RunRow, fixes: List<RawFixRow>, pauses: List<PauseRow>, nowMs: Long,
               excludedFixes: Boolean? = null): RunUiState {
    val live = run.state == "running" || run.state == "paused"
    val endMs = if (live) nowMs else run.endedElapsedMs ?: run.startedElapsedMs
    val elapsedMs = (endMs - run.startedElapsedMs).coerceAtLeast(0)
    var pauseStart: Long? = null
    var pausedMs = 0L
    for (event in pauses) {
        if (event.kind == "pause") pauseStart = event.elapsedMs
        else if (event.kind == "resume" && pauseStart != null) {
            pausedMs += (event.elapsedMs - pauseStart).coerceAtLeast(0)
            pauseStart = null
        }
    }
    if (pauseStart != null) pausedMs += (endMs - pauseStart).coerceAtLeast(0)

    val ordered = fixes.filter { it.fixElapsedMs >= run.startedElapsedMs && it.fixElapsedMs <= endMs }
        .sortedWith(compareBy(RawFixRow::fixElapsedMs, RawFixRow::id))
    val samples = ordered.map { FixTimeRow(it.id, it.fixElapsedMs, it.receivedWallMs, it.latitude, it.longitude, it.accuracyM, it.speedMps) }
    val (rawGaps, deliveryGaps) = gapCounts(run, samples, pauses, endMs)
    val rejected = excludedFixes ?: hasExcludedActiveFix(run, samples, pauses)
    val latest = ordered.lastOrNull()
    val rawFresh = latest != null && live && nowMs - latest.fixElapsedMs in 0..SIGNAL_TIMEOUT_MS
    val acceptedFresh = latest != null && run.lastFixElapsedMs != null &&
        run.lastFixElapsedMs >= latest.fixElapsedMs && live
    val gps = when {
        run.state == "paused" -> GpsUiState.PAUSED
        !live -> if (latest == null) GpsUiState.WAITING else GpsUiState.RECEIVING
        latest == null -> GpsUiState.WAITING
        !rawFresh -> GpsUiState.NO_FIX
        !acceptedFresh -> GpsUiState.EXCLUDED
        else -> GpsUiState.RECEIVING
    }
    return RunUiState(
        elapsedMs = elapsedMs,
        exerciseMs = (elapsedMs - pausedMs).coerceAtLeast(0),
        averagePaceSecKm = if (run.distanceM > 0) run.measuredMs / run.distanceM else null,
        currentPaceSecKm = if (gps == GpsUiState.RECEIVING && run.state == "running") run.currentPaceSecKm else null,
        gps = gps,
        rawGapCount = rawGaps,
        deliveryGapCount = deliveryGaps,
        routeGapCount = run.gapCount,
        incomplete = rawGaps > 0 || deliveryGaps > 0 || rejected,
        latestAccuracyM = latest?.accuracyM
    )
}

fun hasExcludedActiveFix(run: RunRow, samples: List<FixTimeRow>, pauses: List<PauseRow>): Boolean {
    if (run.calculationVersion != TrackEngine.VERSION) return true
    val intervals = ArrayList<Pair<Long, Long?>>()
    var opened: Long? = null
    for (event in pauses) {
        if (event.kind == "pause") opened = event.elapsedMs
        else if (event.kind == "resume" && opened != null) {
            intervals += opened to event.elapsedMs
            opened = null
        }
    }
    if (opened != null) intervals += opened to null
    val fixes = samples.map { Fix(it.id, it.fixElapsedMs, it.latitude, it.longitude, it.accuracyM, it.speedMps) }
    return TrackEngine().diagnose(fixes, run.startedElapsedMs, run.endedElapsedMs, intervals).decisions.any {
        it.reason == FixReason.POOR_ACCURACY || it.reason == FixReason.INVALID_COORDINATES ||
            it.reason == FixReason.IMPLAUSIBLE_JUMP
    }
}

fun gapCounts(run: RunRow, times: List<FixTimeRow>, pauses: List<PauseRow>, endMs: Long): Pair<Int, Int> {
    val relevant = times.filter { it.fixElapsedMs >= run.startedElapsedMs && it.fixElapsedMs <= endMs }
    val ordered = relevant.sortedWith(compareBy(FixTimeRow::fixElapsedMs, FixTimeRow::id))
    var rawGaps = 0
    var deliveryGaps = 0
    if (ordered.isNotEmpty()) rawGaps += activeGapCount(run.startedElapsedMs, ordered.first().fixElapsedMs, pauses) { it.elapsedMs }
    var previous: FixTimeRow? = null
    for (fix in ordered) {
        val before = previous
        if (before != null) rawGaps += activeGapCount(before.fixElapsedMs, fix.fixElapsedMs, pauses) { it.elapsedMs }
        previous = fix
    }
    val latest = ordered.lastOrNull()
    rawGaps += activeGapCount(latest?.fixElapsedMs ?: run.startedElapsedMs, endMs, pauses) { it.elapsedMs }
    previous = null
    for (fix in relevant.sortedWith(compareBy(FixTimeRow::receivedWallMs, FixTimeRow::id))) {
        val before = previous
        if (before != null) deliveryGaps += activeGapCount(before.receivedWallMs, fix.receivedWallMs, pauses) { it.wallMs }
        previous = fix
    }
    return rawGaps to deliveryGaps
}

private fun activeGapCount(from: Long, to: Long, pauses: List<PauseRow>, time: (PauseRow) -> Long): Int {
    if (to <= from) return 0
    var cursor = from
    var paused = false
    var gaps = 0
    for (event in pauses) {
        val at = time(event)
        if (event.kind == "pause" && !paused) {
            if (minOf(at, to) - cursor > SIGNAL_TIMEOUT_MS) gaps++
            cursor = maxOf(cursor, at)
            paused = true
        } else if (event.kind == "resume" && paused) {
            cursor = maxOf(cursor, at)
            paused = false
        }
    }
    if (!paused && to - cursor > SIGNAL_TIMEOUT_MS) gaps++
    return gaps
}
