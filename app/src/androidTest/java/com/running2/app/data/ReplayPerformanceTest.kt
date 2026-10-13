package com.running2.app.data

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the real Room read, full calculation, and point replacement on device. */
@RunWith(AndroidJUnit4::class)
class ReplayPerformanceTest {
    @Test fun longRunReplay() {
        runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDatabaseName = "replay-performance-${SystemClock.elapsedRealtimeNanos()}.db"
        val db = Room.databaseBuilder(context, RunDatabase::class.java, testDatabaseName).build()
        try {
            val started = 10_000L
            val count = 1_285
            val runId = db.dao().insertRun(RunRow(startedWallMs = 0, startedElapsedMs = started,
                endedElapsedMs = started + (count - 1) * 1000L, state = "finished"))
            db.dao().insertFixes((0 until count).map { index ->
                RawFixRow(runId = runId, latitude = 37.0 + index * 0.00005,
                    longitude = 127.0, fixWallMs = 0, fixElapsedMs = started + index * 1000L,
                    receivedWallMs = 0, accuracyM = 5f, speedMps = 5.5f, provider = "test")
            })
            val elapsedMs = (1..3).map {
                val before = SystemClock.elapsedRealtimeNanos()
                RunRepository.replay(db, runId)
                (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0
            }
            val result = db.dao().getRun(runId)!!
            assertEquals(count, db.dao().observePoints(runId).first().size)
            assertEquals((count - 1).toLong(), result.measuredMs / 1000L)
            assertTrue(result.distanceM > 6_000)
            assertEquals(0, result.gapCount)
            Log.i("Running2Perf", "storage=file raw=$count fullReplayMs=$elapsedMs distanceM=${result.distanceM}")
        } finally {
            db.close()
            context.deleteDatabase(testDatabaseName)
        }
        }
    }
}
