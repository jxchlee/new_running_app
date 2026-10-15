package com.running2.app.core

import org.junit.Assert.*
import org.junit.Test

class RunAnalysisTest {
    private fun syntheticIntervals(): List<MeasuredInterval> = (0 until 120).map { second ->
        val distance = if (second < 60) 1.5 else 2.5
        MeasuredInterval(second.toLong(), second + 1L, second * 1000L, (second + 1) * 1000L,
            1, distance, 1000, 37.0, 127.0, 37.0, 127.0)
    }

    @Test fun sustainedChangeIsBackfilledAndTotalsRemainExact() {
        val intervals = syntheticIntervals()
        val total = intervals.sumOf { it.distanceM }
        val result = Result(emptyList(), total, 120_000, 0, null, null, intervals = intervals)
        val analysis = RunAnalysisEngine.analyze(result, emptyList(), emptyList(), total, 120_000, 120_000)
        assertEquals(120_000, analysis.totals.values.sumOf { it.measuredMs })
        assertEquals(total, analysis.totals.values.sumOf { it.distanceM }, 0.000001)
        val firstRun = analysis.bouts.first { it.kind == ActivityKind.RUNNING }
        assertTrue("20-second confirmation must relabel the candidate intervals", firstRun.startMs < 80_000)
        assertTrue(analysis.bouts.any { it.kind == ActivityKind.WALKING })
    }

    @Test fun uncertainIntervalKeepsItsDistanceButCannotBeClassified() {
        val base = syntheticIntervals()
        val intervals = base.toMutableList()
        intervals[70] = intervals[70].copy(uncertainReasons = setOf(FixReason.IMPLAUSIBLE_JUMP))
        val total = intervals.sumOf { it.distanceM }
        val result = Result(emptyList(), total, 120_000, 0, null, null, intervals = intervals)
        val analysis = RunAnalysisEngine.analyze(result, emptyList(), emptyList(), total, 120_000, 120_000)
        assertTrue(analysis.bouts.any { it.kind == ActivityKind.UNKNOWN && it.startMs == 70_000L })
        assertEquals(total, analysis.totals.values.sumOf { it.distanceM }, 0.000001)
    }

    @Test fun breakMarkerOnlyAppearsAtActualSegmentBoundary() {
        val fixes = listOf(
            Fix(1, 0, 37.0, 127.0, 5f, null),
            Fix(2, 1000, 37.00007, 127.0, 5f, null),
            Fix(3, 2000, 38.0, 127.0, 5f, null), // impossible jump, discarded
            Fix(4, 3000, 37.00014, 127.0, 5f, null),
            Fix(5, 25_000, 37.00021, 127.0, 5f, null))
        val result = TrackEngine().diagnose(fixes, 0, 25_000, emptyList())
        val analysis = RunAnalysisEngine.analyze(result,
            fixes.map { ObservedFix(it.id, it.elapsedMs, it.elapsedMs) }, emptyList(),
            result.distanceM, result.measuredMs, 25_000)
        assertEquals(1, analysis.breaks.size)
        assertEquals(BreakCause.RAW_GAP, analysis.breaks.single().primaryCause)
        assertEquals(4L, analysis.breaks.single().before.fixId)
        assertEquals(5L, analysis.breaks.single().after?.fixId)
        assertTrue(result.intervals.any { FixReason.IMPLAUSIBLE_JUMP in it.uncertainReasons })
    }

    @Test fun manualPauseTakesPrecedenceAndSinglePointIsSafe() {
        val fixes = listOf(Fix(1, 0, 37.0, 127.0, 5f, null), Fix(2, 30_000, 37.0001, 127.0, 5f, null))
        val pauses = listOf(5_000L to 25_000L)
        val result = TrackEngine().diagnose(fixes, 0, 30_000, pauses)
        val analysis = RunAnalysisEngine.analyze(result,
            fixes.map { ObservedFix(it.id, it.elapsedMs, it.elapsedMs) }, pauses,
            result.distanceM, result.measuredMs, 30_000)
        assertEquals(BreakCause.MANUAL_PAUSE, analysis.breaks.single().primaryCause)
        val one = TrackEngine().diagnose(fixes.take(1), 0, 0, emptyList())
        val singleton = RunAnalysisEngine.analyze(one, listOf(ObservedFix(1, 0, 0)),
            emptyList(), 0.0, 0, 0)
        assertTrue(singleton.breaks.isEmpty())
        assertTrue(singleton.bouts.isEmpty())
    }

    @Test fun stationaryTimeIsCountedAndBorderlineSpeedIsUnknown() {
        val intervals = (0 until 90).map { second ->
            val distance = when {
                second < 35 -> 0.0
                second < 65 -> 6.8 / 3.6
                else -> 2.5
            }
            MeasuredInterval(second.toLong(), second + 1L, second * 1000L,
                (second + 1) * 1000L, 1, distance, 1000, 37.0, 127.0, 37.0, 127.0)
        }
        val total = intervals.sumOf { it.distanceM }
        val result = Result(emptyList(), total, 90_000, 0, null, null, intervals = intervals)
        val analysis = RunAnalysisEngine.analyze(result, emptyList(), emptyList(), total, 90_000, 90_000)
        assertTrue(analysis.totals.getValue(ActivityKind.STOPPED).measuredMs > 0)
        assertEquals(0.0, analysis.totals.getValue(ActivityKind.STOPPED).distanceM, 0.0)
        assertTrue(analysis.totals.getValue(ActivityKind.UNKNOWN).measuredMs > 0)
        assertEquals(90_000, analysis.totals.values.sumOf { it.measuredMs })
    }

    @Test fun poorAccuracyCreatesBoundaryButDoesNotChangeSavedTotals() {
        val fixes = listOf(Fix(1, 0, 37.0, 127.0, 5f, null),
            Fix(2, 1000, 37.00007, 127.0, 5f, null),
            Fix(3, 2000, 37.00014, 127.0, 150f, null),
            Fix(4, 3000, 37.00021, 127.0, 5f, null))
        val result = TrackEngine().diagnose(fixes, 0, 3000, emptyList())
        val analysis = RunAnalysisEngine.analyze(result,
            fixes.map { ObservedFix(it.id, it.elapsedMs, it.elapsedMs) }, emptyList(),
            result.distanceM, result.measuredMs, 3000)
        assertEquals(1, analysis.breaks.size)
        assertEquals(BreakCause.POOR_ACCURACY, analysis.breaks.single().primaryCause)
        assertEquals(result.distanceM, analysis.totals.values.sumOf { it.distanceM }, 0.000001)
        assertEquals(result.measuredMs, analysis.totals.values.sumOf { it.measuredMs })
    }

    @Test fun currentSpeedRequiresRecentContinuousEvidence() {
        val stationary = (0 until 25).map { second ->
            MeasuredInterval(second.toLong(), second + 1L, second * 1000L,
                (second + 1) * 1000L, 1, 0.0, 1000, 37.0, 127.0, 37.0, 127.0)
        }
        assertEquals(0.0, RunAnalysisEngine.currentSpeedKmh(stationary, 25_000)!!, 0.0)
        assertNull(RunAnalysisEngine.currentSpeedKmh(stationary, 41_000))
        assertNull(RunAnalysisEngine.currentSpeedKmh(stationary.take(19), 19_000))
        assertNull(RunAnalysisEngine.currentSpeedKmh(stationary.dropLast(1) +
            stationary.last().copy(uncertainReasons = setOf(FixReason.IMPLAUSIBLE_JUMP)), 25_000))
    }

    @Test fun trailingPoorAccuracyHasOnlyOneEndpoint() {
        val fixes = listOf(Fix(1, 0, 37.0, 127.0, 5f, null),
            Fix(2, 1000, 37.00007, 127.0, 5f, null),
            Fix(3, 2000, 37.00014, 127.0, 150f, null))
        val result = TrackEngine().diagnose(fixes, 0, 3000, emptyList())
        val analysis = RunAnalysisEngine.analyze(result,
            fixes.map { ObservedFix(it.id, it.elapsedMs, it.elapsedMs) }, emptyList(),
            result.distanceM, result.measuredMs, 3000)
        assertEquals(1, analysis.breaks.size)
        assertEquals(2L, analysis.breaks.single().before.fixId)
        assertNull(analysis.breaks.single().after)
        assertEquals(BreakCause.POOR_ACCURACY, analysis.breaks.single().primaryCause)
    }

    @Test fun sameActivityCanResumeAfterUnknownWithoutStaleCandidate() {
        val intervals = (0 until 140).map { second ->
            // Low positive derived intervals exercise the ambiguous-state transition.
            val distance = if (second in 40 until 90) 0.2 else 1.5
            MeasuredInterval(second.toLong(), second + 1L, second * 1000L,
                (second + 1) * 1000L, 1, distance, 1000, 37.0, 127.0, 37.0, 127.0)
        }
        val total = intervals.sumOf { it.distanceM }
        val result = Result(emptyList(), total, 140_000, 0, null, null, intervals = intervals)
        val analysis = RunAnalysisEngine.analyze(result, emptyList(), emptyList(), total, 140_000, 140_000)
        assertTrue(analysis.bouts.any { it.kind == ActivityKind.UNKNOWN && it.startMs > 40_000 })
        assertEquals(ActivityKind.WALKING, analysis.bouts.last().kind)
        assertEquals(140_000, analysis.totals.values.sumOf { it.measuredMs })
    }

    @Test fun sparseFixesDoNotBackfillMoreThanTwentySeconds() {
        val intervals = listOf(
            MeasuredInterval(1, 2, 0, 15_000, 1, 37.5, 15_000, 37.0, 127.0, 37.0003, 127.0),
            MeasuredInterval(2, 3, 15_000, 30_000, 1, 37.5, 15_000, 37.0003, 127.0, 37.0006, 127.0))
        val result = Result(emptyList(), 75.0, 30_000, 0, null, null, intervals = intervals)
        val analysis = RunAnalysisEngine.analyze(result, emptyList(), emptyList(), 75.0, 30_000, 30_000)
        assertEquals(ActivityKind.UNKNOWN, analysis.bouts.first().kind)
        assertEquals(15_000, analysis.bouts.first().measuredMs)
        assertEquals(ActivityKind.RUNNING, analysis.bouts.last().kind)
        assertEquals(30_000, analysis.totals.values.sumOf { it.measuredMs })
    }

    @Test fun sparseWalkingCandidateBeforeBackfillIsNotLabeledRunning() {
        val initial = (0 until 40).map { second ->
            MeasuredInterval(second.toLong(), second + 1L, second * 1000L,
                (second + 1) * 1000L, 1, 2.8, 1000, 37.0, 127.0, 37.0, 127.0)
        }
        val walking = (0 until 4).map { index ->
            val start = 40_000L + index * 15_000L
            MeasuredInterval(40L + index, 41L + index, start, start + 15_000L,
                1, 25.0, 15_000L, 37.0, 127.0, 37.0, 127.0)
        }
        val intervals = initial + walking
        val total = intervals.sumOf { it.distanceM }
        val result = Result(emptyList(), total, 100_000, 0, null, null, intervals = intervals)
        val analysis = RunAnalysisEngine.analyze(result, emptyList(), emptyList(), total, 100_000, 100_000)
        assertTrue(analysis.bouts.any { it.kind == ActivityKind.RUNNING })
        assertTrue(analysis.bouts.any { it.kind == ActivityKind.WALKING })
        assertTrue(analysis.bouts.any { it.kind == ActivityKind.UNKNOWN && it.startMs >= 40_000 })
        assertEquals(ActivityKind.WALKING, analysis.bouts.last().kind)
    }
}
