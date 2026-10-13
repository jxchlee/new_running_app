package com.running2.app.core

import kotlin.math.*

/** Pure calculation shared by live updates and final replay. All times are elapsed-realtime millis. */
data class Fix(val id: Long, val elapsedMs: Long, val latitude: Double, val longitude: Double,
               val accuracyM: Float?, val speedMps: Float?)
data class GateConfig(val maxAccuracyM: Float = 35f, val maxGapMs: Long = 15_000,
                      val maxSpeedMps: Double = 9.0, val stationaryM: Double = 5.0)
data class Point(val fixId: Long, val segment: Int, val latitude: Double, val longitude: Double,
                 val elapsedMs: Long, val distanceM: Double)
enum class FixReason { DUPLICATE_TIME, OUTSIDE_RUN, PAUSED, INVALID_COORDINATES, POOR_ACCURACY,
    SEGMENT_START, FIX_TIME_GAP, AFTER_PAUSE, AFTER_REJECTION, IMPLAUSIBLE_JUMP, STATIONARY, MOVING }
data class FixDecision(val fixId: Long, val elapsedMs: Long, val reason: FixReason,
                       val segment: Int, val calculationVersion: Int,
                       val additionalReasons: Set<FixReason> = emptySet())
data class Result(val points: List<Point>, val distanceM: Double, val measuredMs: Long,
                  val gapCount: Int, val lastAcceptedMs: Long?, val recentPaceSecKm: Double?,
                  val decisions: List<FixDecision> = emptyList())

class TrackEngine(private val config: GateConfig = GateConfig()) {
    fun calculate(fixes: List<Fix>, startMs: Long, endMs: Long?, pauses: List<Pair<Long, Long?>>,
                  includeDecisions: Boolean = false): Result {
        val points = ArrayList<Point>()
        val decisions = ArrayList<FixDecision>()
        fun note(fix: Fix, reason: FixReason, segment: Int, additional: Set<FixReason> = emptySet()) {
            if (includeDecisions) decisions += FixDecision(fix.id, fix.elapsedMs, reason, segment, VERSION, additional)
        }
        var anchor: Fix? = null
        var segment = 0
        var distance = 0.0
        var measured = 0L
        var gaps = 0
        var lastAccepted: Long? = null
        var lastProcessed = Long.MIN_VALUE
        var pendingBreak: FixReason? = null
        val moving = ArrayDeque<Pair<Long, Double>>()
        for (fix in fixes.sortedWith(compareBy(Fix::elapsedMs, Fix::id))) {
            if (fix.elapsedMs <= lastProcessed) { note(fix, FixReason.DUPLICATE_TIME, segment); continue }
            lastProcessed = fix.elapsedMs
            if (fix.elapsedMs < startMs || (endMs != null && fix.elapsedMs > endMs)) {
                note(fix, FixReason.OUTSIDE_RUN, segment); continue
            }
            if (pauses.any { pause -> fix.elapsedMs >= pause.first && (pause.second?.let { fix.elapsedMs < it } ?: true) }) {
                anchor = null
                pendingBreak = FixReason.AFTER_PAUSE
                note(fix, FixReason.PAUSED, segment)
                continue
            }
            val coordinatesValid = fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
                fix.latitude.isFinite() && fix.longitude.isFinite()
            val accuracyValid = fix.accuracyM != null && fix.accuracyM.isFinite() && fix.accuracyM in 0f..config.maxAccuracyM
            val valid = coordinatesValid && accuracyValid
            if (!valid) {
                if (anchor != null) { gaps++; anchor = null }
                pendingBreak = FixReason.AFTER_REJECTION
                moving.clear()
                note(fix, if (!coordinatesValid) FixReason.INVALID_COORDINATES else FixReason.POOR_ACCURACY, segment)
                continue
            }
            val previous = anchor
            val timedOut = previous != null && fix.elapsedMs - previous.elapsedMs > config.maxGapMs
            val pauseBoundary = previous != null && pauses.any {
                it.first in (previous.elapsedMs + 1)..fix.elapsedMs ||
                    (it.second != null && it.second!! in (previous.elapsedMs + 1)..fix.elapsedMs)
            }
            if (previous == null || timedOut || pauseBoundary) {
                if (timedOut) gaps++
                segment++
                points += Point(fix.id, segment, fix.latitude, fix.longitude, fix.elapsedMs, 0.0)
                anchor = fix
                lastAccepted = fix.elapsedMs
                moving.clear()
                note(fix, when {
                    previous == null -> pendingBreak ?: FixReason.SEGMENT_START
                    pauseBoundary -> FixReason.AFTER_PAUSE
                    else -> FixReason.FIX_TIME_GAP
                }, segment, if (pauseBoundary && timedOut) setOf(FixReason.FIX_TIME_GAP) else emptySet())
                pendingBreak = null
                continue
            }
            val dt = fix.elapsedMs - previous.elapsedMs
            val delta = meters(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
            if (delta / (dt / 1000.0) > config.maxSpeedMps || (fix.speedMps != null && fix.speedMps > config.maxSpeedMps * 1.5)) {
                // Keep the last good anchor. A returning fix can establish continuity.
                moving.clear()
                note(fix, FixReason.IMPLAUSIBLE_JUMP, segment)
                continue
            }
            measured += dt
            lastAccepted = fix.elapsedMs
            if (delta < config.stationaryM) {
                // Valid stationary time counts, but GPS wander must not draw a line or add distance.
                anchor = fix.copy(latitude = previous.latitude, longitude = previous.longitude)
                moving.clear()
                note(fix, FixReason.STATIONARY, segment)
                continue
            }
            distance += delta
            points += Point(fix.id, segment, fix.latitude, fix.longitude, fix.elapsedMs, delta)
            moving += dt to delta
            while (moving.size > 8) moving.removeFirst()
            anchor = fix
            note(fix, FixReason.MOVING, segment)
        }
        val recentMs = moving.sumOf { it.first }
        val recentM = moving.sumOf { it.second }
        val pace = if (moving.size >= 3 && recentM >= 30) recentMs / recentM else null
        return Result(points, distance, measured, gaps, lastAccepted, pace, decisions)
    }

    fun diagnose(fixes: List<Fix>, startMs: Long, endMs: Long?, pauses: List<Pair<Long, Long?>>): Result =
        calculate(fixes, startMs, endMs, pauses, includeDecisions = true)

    companion object {
        const val VERSION = 1
        fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val rad = PI / 180.0
            val a = sin((lat2 - lat1) * rad / 2).pow(2) + cos(lat1 * rad) * cos(lat2 * rad) * sin((lon2 - lon1) * rad / 2).pow(2)
            return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
