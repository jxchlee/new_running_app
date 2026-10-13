package com.running2.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Set RUNNING2_AUDIT_FIXTURE to a private export made by tools/export_replay_fixture.py. */
class ReplayAuditTest {
    @Test fun savedRunMatchesReadOnlyReplay() {
        val paths = System.getenv("RUNNING2_AUDIT_FIXTURE")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }
        assumeTrue(!paths.isNullOrEmpty())
        paths!!.forEach(::audit)
    }

    private fun audit(path: String) {
        val rows = File(path).readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val run = rows.single { it[0] == "R" }
        val fixes = rows.filter { it[0] == "F" }.map {
            Fix(it[1].toLong(), it[2].toLong(), it[3].toDouble(), it[4].toDouble(),
                it[5].optionalFloat(), it[6].optionalFloat())
        }
        val pauses = rows.filter { it[0] == "P" }.map { it[1].toLong() to it[2].optionalLong() }
        val stored = rows.filter { it[0] == "T" }.map {
            Point(it[1].toLong(), it[2].toInt(), it[3].toDouble(), it[4].toDouble(),
                it[5].toLong(), it[6].toDouble())
        }
        val version = run[7].toInt()
        assertEquals(TrackEngine.VERSION, version)
        val replay = TrackEngine().diagnose(fixes, run[2].toLong(), run[3].optionalLong(), pauses)
        assertEquals(fixes.size, replay.decisions.size)
        assertEquals(stored.size, replay.points.size)
        for ((saved, calculated) in stored.zip(replay.points)) {
            assertEquals(saved.fixId, calculated.fixId)
            assertEquals(saved.segment, calculated.segment)
            assertEquals(saved.elapsedMs, calculated.elapsedMs)
            assertEquals(saved.latitude, calculated.latitude, 1e-10)
            assertEquals(saved.longitude, calculated.longitude, 1e-10)
            assertEquals(saved.distanceM, calculated.distanceM, 1e-5)
        }
        val storedDistance = run[4].toDouble()
        val storedTime = run[5].toLong()
        assertEquals(storedDistance, replay.distanceM, 1e-5)
        assertEquals(storedTime, replay.measuredMs)
        assertEquals(run[6].toInt(), replay.gapCount)
        val lineLength = stored.zipWithNext().sumOf { (a, b) ->
            if (a.segment == b.segment) TrackEngine.meters(a.latitude, a.longitude, b.latitude, b.longitude) else 0.0
        }
        assertEquals(storedDistance, lineLength, 1e-4)
        assertEquals(storedDistance, stored.sumOf { it.distanceM }, 1e-5)
        assertTrue(storedTime >= 0)
        if (storedDistance > 0) assertEquals(storedTime / storedDistance,
            replay.measuredMs / replay.distanceM, 1e-6)
        val reasons = replay.decisions.groupingBy { it.reason }.eachCount().toSortedMap()
        println("Replay audit run=${run[1]} version=$version raw=${fixes.size} points=${stored.size} segments=${stored.map { it.segment }.distinct().size}")
        println("distanceM=$storedDistance measuredMs=$storedTime paceSecKm=${if (storedDistance > 0) storedTime / storedDistance else "undefined"}")
        println("fixDecisions=$reasons")
    }

    private fun String.optionalLong(): Long? = if (this == "null") null else toLong()
    private fun String.optionalFloat(): Float? = if (this == "null") null else toFloat()
}
