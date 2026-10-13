package com.running2.app.ui

import com.running2.app.data.PauseRow
import com.running2.app.data.RawFixRow
import com.running2.app.data.RunRow
import org.junit.Assert.*
import org.junit.Test

class RunUiStateTest {
    private fun fix(id: Long, time: Long, received: Long = time) = RawFixRow(
        id = id, runId = 1, latitude = 37.0, longitude = 127.0,
        fixWallMs = time, fixElapsedMs = time, receivedWallMs = received,
        accuracyM = 5f, speedMps = null, provider = "fused"
    )

    @Test fun rawSignalAndRejectedTrackAreDifferentStates() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0, lastFixElapsedMs = 1_000)
        val state = runUiState(run, listOf(fix(1, 1_000), fix(2, 20_000)), emptyList(), 20_100)
        assertEquals(GpsUiState.EXCLUDED, state.gps)
        assertEquals(1, state.rawGapCount)
        assertNull(state.currentPaceSecKm)
        assertEquals(GpsUiState.NO_FIX, runUiState(run, listOf(fix(1, 1_000)), emptyList(), 20_100).gps)
    }

    @Test fun pausedTimeIsExcludedAndDoesNotCountAsSignalGap() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0, endedElapsedMs = 60_000, state = "finished")
        val pauses = listOf(PauseRow(runId = 1, elapsedMs = 10_000, wallMs = 10_000, kind = "pause"),
            PauseRow(runId = 1, elapsedMs = 50_000, wallMs = 50_000, kind = "resume"))
        val state = runUiState(run, listOf(fix(1, 5_000), fix(2, 55_000)), pauses, 60_000)
        assertEquals(60_000, state.elapsedMs)
        assertEquals(20_000, state.exerciseMs)
        assertEquals(0, state.rawGapCount)
        assertFalse(state.incomplete)
    }

    @Test fun shortGapsOnBothSidesOfPauseAreNotMerged() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0,
            endedElapsedMs = 36_000, state = "finished", gapCount = 1)
        val pauses = listOf(PauseRow(runId = 1, elapsedMs = 8_000, wallMs = 8_000, kind = "pause"),
            PauseRow(runId = 1, elapsedMs = 28_000, wallMs = 28_000, kind = "resume"))
        val state = runUiState(run, listOf(fix(1, 0), fix(2, 36_000)), pauses, 36_000)
        assertEquals(0, state.rawGapCount)
        assertEquals(0, state.deliveryGapCount)
        assertFalse(state.incomplete)
    }

    @Test fun deliveryGapIsSeparateFromObservationGap() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0, endedElapsedMs = 10_000, state = "finished")
        val state = runUiState(run, listOf(fix(1, 1_000, 1_000), fix(2, 2_000, 20_000)), emptyList(), 20_000)
        assertEquals(0, state.rawGapCount)
        assertEquals(1, state.deliveryGapCount)
        assertTrue(state.incomplete)
    }

    @Test fun missingTailAfterLastRawFixMarksFinishedRunIncomplete() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0,
            endedElapsedMs = 30_000, state = "finished")
        val state = runUiState(run, listOf(fix(1, 1_000)), emptyList(), 30_000)
        assertEquals(1, state.rawGapCount)
        assertTrue(state.incomplete)
    }

    @Test fun missingInitialFixMarksRunIncomplete() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0,
            endedElapsedMs = 40_000, state = "finished")
        val state = runUiState(run, listOf(fix(1, 30_000)), emptyList(), 40_000)
        assertEquals(1, state.rawGapCount)
        assertTrue(state.incomplete)
    }

    @Test fun ignoredDuplicateBadFixDoesNotMarkIncomplete() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0,
            endedElapsedMs = 10_000, state = "finished")
        val badDuplicate = fix(3, 5_000).copy(accuracyM = 100f)
        val state = runUiState(run, listOf(fix(1, 0), fix(2, 5_000), badDuplicate, fix(4, 10_000)), emptyList(), 10_000)
        assertFalse(state.incomplete)
    }

    @Test fun rejectedJumpMarksIncomplete() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 0,
            endedElapsedMs = 10_000, state = "finished")
        val jump = fix(2, 5_000).copy(longitude = 127.1)
        val state = runUiState(run, listOf(fix(1, 0), jump, fix(3, 10_000)), emptyList(), 10_000)
        assertTrue(state.incomplete)
    }

    @Test fun fixBeforeRunStartDoesNotCreateFalseGap() {
        val run = RunRow(id = 1, startedWallMs = 0, startedElapsedMs = 100_000,
            endedElapsedMs = 110_000, state = "finished")
        val beforeStart = fix(1, 0).copy(accuracyM = 100f)
        val state = runUiState(run, listOf(beforeStart, fix(2, 101_000), fix(3, 110_000)), emptyList(), 110_000)
        assertEquals(0, state.rawGapCount)
        assertEquals(0, state.deliveryGapCount)
        assertFalse(state.incomplete)
    }
}
