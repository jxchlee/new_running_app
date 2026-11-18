package com.running2.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ActivityKind { RUNNING, WALKING, STOPPED, UNKNOWN }
enum class BreakCause { MANUAL_PAUSE, RAW_GAP, POOR_ACCURACY, INVALID_COORDINATES, IMPLAUSIBLE_JUMP, UNKNOWN }

data class ObservedFix(val fixId: Long, val elapsedMs: Long, val receivedWallMs: Long)
data class ActivityTotal(val distanceM: Double, val measuredMs: Long) {
    val averageSpeedKmh: Double? get() = if (measuredMs > 0) distanceM / measuredMs * 3600 else null
}
data class ActivityBout(val id: Int, val kind: ActivityKind, val segment: Int,
                        val startMs: Long, val endMs: Long, val distanceM: Double,
                        val measuredMs: Long, val intervals: List<MeasuredInterval>) {
    val averageSpeedKmh: Double? get() = if (measuredMs > 0) distanceM / measuredMs * 3600 else null
}
data class BreakEvent(val id: Int, val before: Point, val after: Point?,
                      val causes: Set<BreakCause>, val startMs: Long, val endMs: Long,
                      val maxRawGapMs: Long, val maxDeliveryGapMs: Long,
                      val calculationVersion: Int) {
    val primaryCause: BreakCause get() = when {
        BreakCause.MANUAL_PAUSE in causes -> BreakCause.MANUAL_PAUSE
        BreakCause.RAW_GAP in causes -> BreakCause.RAW_GAP
        BreakCause.POOR_ACCURACY in causes -> BreakCause.POOR_ACCURACY
        BreakCause.INVALID_COORDINATES in causes -> BreakCause.INVALID_COORDINATES
        BreakCause.IMPLAUSIBLE_JUMP in causes -> BreakCause.IMPLAUSIBLE_JUMP
        else -> BreakCause.UNKNOWN
    }
}
data class RunAnalysis(val classificationVersion: Int, val calculationVersion: Int,
                       val intervals: List<MeasuredInterval>, val bouts: List<ActivityBout>,
                       val totals: Map<ActivityKind, ActivityTotal>, val breaks: List<BreakEvent>)

/** Derived, read-only v1 analysis. It never writes raw fixes, track points, or run totals. */
object RunAnalysisEngine {
    const val VERSION = 1
    private const val WINDOW_MS = 25_000L
    private const val MIN_WINDOW_MS = 20_000L
    private const val CONFIRM_MS = 20_000L
    private const val MAX_RAW_GAP_MS = 15_000L

    fun analyze(result: Result, raw: List<ObservedFix>, pauses: List<Pair<Long, Long?>>,
                storedDistanceM: Double, storedMeasuredMs: Long, runEndMs: Long?): RunAnalysis {
        require(abs(result.distanceM - storedDistanceM) <= 1e-4) { "Stored distance differs from replay" }
        require(result.measuredMs == storedMeasuredMs) { "Stored measured time differs from replay" }
        require(abs(result.intervals.sumOf { it.distanceM } - result.distanceM) <= 1e-4)
        require(result.intervals.sumOf { it.measuredMs } == result.measuredMs)
        val labels = classify(result.intervals)
        val bouts = groupBouts(result.intervals, labels)
        val totals = ActivityKind.values().associateWith { kind ->
            ActivityTotal(bouts.filter { it.kind == kind }.sumOf { it.distanceM },
                bouts.filter { it.kind == kind }.sumOf { it.measuredMs })
        }
        require(abs(totals.values.sumOf { it.distanceM } - storedDistanceM) <= 1e-4)
        require(totals.values.sumOf { it.measuredMs } == storedMeasuredMs)
        return RunAnalysis(VERSION, result.calculationVersion, result.intervals, bouts, totals,
            breaks(result, raw, pauses, runEndMs))
    }

    /** Live speed uses only recent measured intervals; no persisted pace field is changed. */
    fun currentSpeedKmh(intervals: List<MeasuredInterval>, nowMs: Long): Double? {
        val last = intervals.lastOrNull() ?: return null
        if (nowMs - last.endMs !in 0..MAX_RAW_GAP_MS || last.uncertainReasons.isNotEmpty()) return null
        val start = last.endMs - WINDOW_MS
        val block = ArrayList<MeasuredInterval>()
        for (interval in intervals.asReversed()) {
            if (interval.segment != last.segment || interval.uncertainReasons.isNotEmpty() ||
                (block.isNotEmpty() && interval.endMs != block.last().startMs)) break
            block += interval
            if (interval.startMs <= start) break
        }
        if (block.isEmpty()) return null
        val sample = speedSample(block.asReversed(), last.endMs - WINDOW_MS, last.endMs)
        if (sample.timeMs < MIN_WINDOW_MS) return null
        if (sample.speedKmh < 1.0 && sample.stationaryMs < sample.timeMs * 0.8) return null
        return if (sample.speedKmh < 1.0) 0.0 else sample.speedKmh
    }

    private data class SpeedSample(val distanceM: Double, val timeMs: Long, val stationaryMs: Long) {
        val speedKmh: Double get() = if (timeMs > 0) distanceM / timeMs * 3600 else 0.0
    }

    private fun speedSample(intervals: List<MeasuredInterval>, from: Long, to: Long): SpeedSample {
        var distance = 0.0
        var time = 0L
        var stationary = 0L
        for (interval in intervals) {
            val overlap = (min(interval.endMs, to) - max(interval.startMs, from)).coerceAtLeast(0)
            if (overlap == 0L) continue
            time += overlap
            distance += interval.distanceM * overlap / interval.measuredMs
            if (interval.distanceM == 0.0) stationary += overlap
        }
        return SpeedSample(distance, time, stationary)
    }

    private fun classify(intervals: List<MeasuredInterval>): List<ActivityKind> {
        val labels = MutableList(intervals.size) { ActivityKind.UNKNOWN }
        var blockStart = 0
        while (blockStart < intervals.size) {
            if (intervals[blockStart].uncertainReasons.isNotEmpty()) { blockStart++; continue }
            var blockEnd = blockStart + 1
            while (blockEnd < intervals.size && intervals[blockEnd].segment == intervals[blockStart].segment &&
                intervals[blockEnd].uncertainReasons.isEmpty() && intervals[blockEnd].startMs == intervals[blockEnd - 1].endMs) blockEnd++
            classifyBlock(intervals, labels, blockStart, blockEnd)
            blockStart = blockEnd
        }
        return labels
    }

    private fun classifyBlock(intervals: List<MeasuredInterval>, labels: MutableList<ActivityKind>, first: Int, end: Int) {
        val blockStart = intervals[first].startMs
        var current = ActivityKind.UNKNOWN
        var pending = ActivityKind.UNKNOWN
        var pendingStart = -1
        var pendingMs = 0L
        for (index in first until end) {
            val interval = intervals[index]
            val from = max(blockStart, interval.endMs - WINDOW_MS)
            var distance = 0.0
            var time = 0L
            var stationary = 0L
            var cursor = index
            while (cursor >= first && intervals[cursor].endMs > from) {
                val item = intervals[cursor]
                val overlap = (item.endMs - max(item.startMs, from)).coerceAtLeast(0)
                time += overlap
                distance += item.distanceM * overlap / item.measuredMs
                if (item.distanceM == 0.0) stationary += overlap
                cursor--
            }
            val sample = SpeedSample(distance, time, stationary)
            val wanted = when {
                sample.timeMs == 0L -> ActivityKind.UNKNOWN
                sample.speedKmh < 1.0 && sample.stationaryMs >= sample.timeMs * 0.8 &&
                    interval.distanceM == 0.0 -> ActivityKind.STOPPED
                sample.speedKmh < 1.0 -> ActivityKind.UNKNOWN
                sample.speedKmh >= 7.5 -> ActivityKind.RUNNING
                sample.speedKmh < 6.5 -> ActivityKind.WALKING
                current == ActivityKind.RUNNING -> ActivityKind.RUNNING
                current == ActivityKind.WALKING -> ActivityKind.WALKING
                else -> ActivityKind.UNKNOWN
            }
            if (wanted == ActivityKind.UNKNOWN) {
                if (pendingStart >= 0) for (i in pendingStart until index) labels[i] = current
                current = ActivityKind.UNKNOWN
                pending = ActivityKind.UNKNOWN
                pendingStart = -1
                pendingMs = 0
                continue
            }
            if (wanted == current) {
                if (pendingStart >= 0) for (i in pendingStart until index) labels[i] = current
                pendingStart = -1
                pending = ActivityKind.UNKNOWN
                pendingMs = 0
                labels[index] = current
                continue
            }
            if (pendingStart < 0 || wanted != pending) {
                if (pendingStart >= 0) for (i in pendingStart until index) labels[i] = current
                pending = wanted
                pendingStart = index
                pendingMs = 0
            }
            pendingMs += interval.measuredMs
            labels[index] = current
            if (pendingMs >= CONFIRM_MS) {
                // A sparse GPS interval can straddle the 20-second boundary. Keep that
                // whole interval UNKNOWN instead of assigning more than 20 seconds.
                for (i in pendingStart..index) labels[i] = ActivityKind.UNKNOWN
                var remaining = CONFIRM_MS
                for (i in index downTo pendingStart) {
                    val duration = intervals[i].measuredMs
                    if (duration > remaining) {
                        labels[i] = ActivityKind.UNKNOWN
                        break
                    }
                    labels[i] = wanted
                    remaining -= duration
                    if (remaining == 0L) break
                }
                current = wanted
                pendingStart = -1
                pending = ActivityKind.UNKNOWN
                pendingMs = 0
            }
        }
        // A short unconfirmed change remains in the previous state.
        if (pendingStart >= 0) for (i in pendingStart until end) labels[i] = current
    }

    private fun groupBouts(intervals: List<MeasuredInterval>, labels: List<ActivityKind>): List<ActivityBout> {
        val bouts = ArrayList<ActivityBout>()
        var start = 0
        while (start < intervals.size) {
            var end = start + 1
            while (end < intervals.size && labels[end] == labels[start] &&
                intervals[end].segment == intervals[start].segment && intervals[end].startMs == intervals[end - 1].endMs) end++
            val part = intervals.subList(start, end).toList()
            bouts += ActivityBout(bouts.size + 1, labels[start], part.first().segment,
                part.first().startMs, part.last().endMs, part.sumOf { it.distanceM },
                part.sumOf { it.measuredMs }, part)
            start = end
        }
        return bouts
    }

    private fun breaks(result: Result, raw: List<ObservedFix>, pauses: List<Pair<Long, Long?>>,
                       runEndMs: Long?): List<BreakEvent> {
        val events = ArrayList<BreakEvent>()
        val orderedRaw = raw.sortedWith(compareBy(ObservedFix::elapsedMs, ObservedFix::fixId))
        for ((before, after) in result.points.zipWithNext()) {
            if (before.segment == after.segment) continue
            events += breakEvent(events.size + 1, before, after, result, orderedRaw, pauses)
        }
        val lastPoint = result.points.lastOrNull()
        if (lastPoint != null && runEndMs != null && runEndMs > lastPoint.elapsedMs) {
            // v2 can accept a fix without adding a point (bridged or stationary), so the trailing
            // stretch starts at the last accepted fix, not at the last drawn point.
            val trailingFrom = if (result.calculationVersion >= 2) result.lastAcceptedMs ?: lastPoint.elapsedMs
                else lastPoint.elapsedMs
            val trailing = result.decisions.any { it.elapsedMs > trailingFrom &&
                (it.reason == FixReason.POOR_ACCURACY || it.reason == FixReason.INVALID_COORDINATES) }
            val lastRaw = orderedRaw.lastOrNull { it.elapsedMs <= runEndMs }
            val activeSilence = if (lastRaw != null) longestActiveSpan(lastRaw.elapsedMs, runEndMs, pauses) > MAX_RAW_GAP_MS else false
            // v2: a few rejected fixes just before finishing are not worth a break marker.
            val trailingCounts = trailing && (result.calculationVersion < 2 ||
                longestActiveSpan(trailingFrom, runEndMs, pauses) > MAX_RAW_GAP_MS)
            if (trailingCounts || activeSilence) events += breakEvent(events.size + 1, lastPoint, null,
                result, orderedRaw, pauses, runEndMs)
        }
        return events
    }

    private fun breakEvent(id: Int, before: Point, after: Point?, result: Result,
                           raw: List<ObservedFix>, pauses: List<Pair<Long, Long?>>, endMs: Long? = null): BreakEvent {
        val end = after?.elapsedMs ?: requireNotNull(endMs)
        val causes = LinkedHashSet<BreakCause>()
        if (pauses.any { it.first <= end && (it.second ?: Long.MAX_VALUE) >= before.elapsedMs }) causes += BreakCause.MANUAL_PAUSE
        val inside = raw.filter { it.elapsedMs in before.elapsedMs..end }
        var maxRawGap = 0L
        var maxDeliveryGap = 0L
        for ((left, right) in inside.zipWithNext()) {
            maxRawGap = max(maxRawGap, longestActiveSpan(left.elapsedMs, right.elapsedMs, pauses))
            maxDeliveryGap = max(maxDeliveryGap, (right.receivedWallMs - left.receivedWallMs).coerceAtLeast(0))
        }
        if (after == null && inside.isNotEmpty())
            maxRawGap = max(maxRawGap, longestActiveSpan(inside.last().elapsedMs, end, pauses))
        if (maxRawGap > MAX_RAW_GAP_MS) causes += BreakCause.RAW_GAP
        for (decision in result.decisions) {
            if (decision.elapsedMs <= before.elapsedMs || decision.elapsedMs > end) continue
            when (decision.reason) {
                FixReason.POOR_ACCURACY -> causes += BreakCause.POOR_ACCURACY
                FixReason.INVALID_COORDINATES -> causes += BreakCause.INVALID_COORDINATES
                FixReason.IMPLAUSIBLE_JUMP -> causes += BreakCause.IMPLAUSIBLE_JUMP
                else -> Unit
            }
        }
        if (causes.isEmpty()) causes += BreakCause.UNKNOWN
        return BreakEvent(id, before, after, causes, before.elapsedMs, end, maxRawGap,
            maxDeliveryGap, result.calculationVersion)
    }

    private fun longestActiveSpan(from: Long, to: Long, pauses: List<Pair<Long, Long?>>): Long {
        if (to <= from) return 0
        var cursor = from
        var longest = 0L
        for ((pauseStart, pauseEnd) in pauses.sortedBy { it.first }) {
            if (pauseStart >= to) break
            val resume = pauseEnd ?: to
            if (resume <= cursor) continue
            longest = max(longest, (min(pauseStart, to) - cursor).coerceAtLeast(0))
            cursor = max(cursor, min(resume, to))
        }
        return max(longest, (to - cursor).coerceAtLeast(0))
    }
}
