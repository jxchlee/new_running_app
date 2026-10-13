package com.running2.app.core

import org.junit.Assert.*
import org.junit.Test

class TrackEngineTest {
    private fun fix(id: Long, second: Long, longitude: Double, accuracy: Float? = 5f) =
        Fix(id, second * 1000, 37.0, longitude, accuracy, null)

    @Test fun gapAndPauseNeverAddBridgingDistance() {
        val fixes = listOf(fix(1, 0, 127.0), fix(2, 10, 127.0001),
            fix(3, 40, 127.01), fix(4, 50, 127.0101),
            fix(5, 60, 127.02), fix(6, 70, 127.0201))
        val result = TrackEngine().calculate(fixes, 0, 70_000, listOf(55_000L to 65_000L))
        assertEquals(3, result.points.map { it.segment }.distinct().size)
        assertTrue(result.distanceM in 15.0..20.0)
        assertEquals(20_000, result.measuredMs)
        assertEquals(0.0, result.points.filter { it.distanceM == 0.0 }.sumOf { it.distanceM }, 0.0)
    }

    @Test fun stationaryWanderDoesNotIncreaseDistanceButCountsTime() {
        val fixes = (0L..10L).map { fix(it, it * 5, 127.0 + (it % 2) * 0.00001) }
        val result = TrackEngine().calculate(fixes, 0, null, emptyList())
        assertEquals(0.0, result.distanceM, 0.001)
        assertEquals(50_000, result.measuredMs)
    }

    @Test fun badAccuracyBreaksSegmentAndRawCanBeReplayed() {
        val fixes = listOf(fix(1, 0, 127.0), fix(2, 5, 127.0001),
            fix(3, 10, 127.001, 100f), fix(4, 15, 127.01), fix(5, 20, 127.0101))
        val result = TrackEngine().calculate(fixes, 0, null, emptyList())
        assertEquals(2, result.points.map { it.segment }.distinct().size)
        assertEquals(1, result.gapCount)
        assertEquals(result, TrackEngine().calculate(fixes.reversed(), 0, null, emptyList()))
    }

    @Test fun duplicateTimeDoesNotSplitRoute() {
        val fixes = listOf(fix(1, 0, 127.0), fix(2, 5, 127.0001),
            fix(3, 5, 127.03), fix(4, 10, 127.0002))
        val result = TrackEngine().calculate(fixes, 0, null, emptyList())
        assertEquals(1, result.points.map { it.segment }.distinct().size)
        assertTrue(result.distanceM in 15.0..20.0)
    }

    @Test fun stationaryFixClearsCurrentPace() {
        val moving = listOf(fix(1, 0, 127.0), fix(2, 5, 127.00015),
            fix(3, 10, 127.00030), fix(4, 15, 127.00045))
        assertNotNull(TrackEngine().calculate(moving, 0, null, emptyList()).recentPaceSecKm)
        val stopped = moving + fix(5, 20, 127.00045)
        assertNull(TrackEngine().calculate(stopped, 0, null, emptyList()).recentPaceSecKm)
    }

    @Test fun diagnosticReasonsDoNotChangeSavedCalculation() {
        val fixes = listOf(fix(1, 0, 127.0), fix(2, 5, 127.0001),
            fix(3, 10, 127.001, 100f), fix(4, 15, 127.01),
            fix(5, 20, 127.0101), fix(6, 20, 127.5))
        val engine = TrackEngine()
        val normal = engine.calculate(fixes, 0, null, emptyList())
        val diagnostic = engine.diagnose(fixes, 0, null, emptyList())
        assertEquals(normal.points, diagnostic.points)
        assertEquals(normal.distanceM, diagnostic.distanceM, 0.0001)
        assertEquals(normal.measuredMs, diagnostic.measuredMs)
        assertEquals(normal.gapCount, diagnostic.gapCount)
        assertEquals(FixReason.POOR_ACCURACY, diagnostic.decisions.first { it.fixId == 3L }.reason)
        assertEquals(FixReason.AFTER_REJECTION, diagnostic.decisions.first { it.fixId == 4L }.reason)
        assertEquals(FixReason.DUPLICATE_TIME, diagnostic.decisions.first { it.fixId == 6L }.reason)
        assertTrue(diagnostic.decisions.all { it.calculationVersion == TrackEngine.VERSION })
    }

    @Test fun diagnosticPreservesPauseAndTimeGapReasons() {
        val fixes = listOf(fix(1, 0, 127.0), fix(2, 30, 127.001))
        val result = TrackEngine().diagnose(fixes, 0, null, listOf(5_000L to 25_000L))
        val second = result.decisions.first { it.fixId == 2L }
        assertEquals(FixReason.AFTER_PAUSE, second.reason)
        assertTrue(FixReason.FIX_TIME_GAP in second.additionalReasons)
    }
}
