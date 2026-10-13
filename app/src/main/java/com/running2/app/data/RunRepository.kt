package com.running2.app.data

import androidx.room.withTransaction
import com.running2.app.core.Fix
import com.running2.app.core.Result
import com.running2.app.core.TrackEngine

object RunRepository {
    suspend fun replay(db: RunDatabase, id: Long, finalState: RunRow? = null) {
        val run = finalState ?: db.dao().getRun(id) ?: return
        require(run.calculationVersion == TrackEngine.VERSION) { "Unsupported calculation version: ${run.calculationVersion}" }
        val intervals = pauseIntervals(db.dao().pauses(id))
        val fixes = db.dao().fixes(id).map { Fix(it.id, it.fixElapsedMs, it.latitude, it.longitude, it.accuracyM, it.speedMps) }
        val result = TrackEngine().calculate(fixes, run.startedElapsedMs, run.endedElapsedMs, intervals)
        db.withTransaction {
            db.dao().clearPoints(id)
            db.dao().insertPoints(result.points.map { TrackPointRow(runId = id, fixId = it.fixId,
                segment = it.segment, latitude = it.latitude, longitude = it.longitude,
                elapsedMs = it.elapsedMs, distanceM = it.distanceM) })
            db.dao().updateRun(run.copy(distanceM = result.distanceM, measuredMs = result.measuredMs,
                gapCount = result.gapCount, lastFixElapsedMs = result.lastAcceptedMs,
                currentPaceSecKm = if (run.state == "running") result.recentPaceSecKm else null))
        }
    }

    /** Read-only replay; a stored calculation is never replaced by diagnostics. */
    suspend fun diagnose(db: RunDatabase, id: Long): Result? {
        val run = db.dao().getRun(id) ?: return null
        require(run.calculationVersion == TrackEngine.VERSION) { "Unsupported calculation version: ${run.calculationVersion}" }
        val fixes = db.dao().fixes(id).map { Fix(it.id, it.fixElapsedMs, it.latitude, it.longitude, it.accuracyM, it.speedMps) }
        return TrackEngine().diagnose(fixes, run.startedElapsedMs, run.endedElapsedMs,
            pauseIntervals(db.dao().pauses(id)))
    }

    private fun pauseIntervals(events: List<PauseRow>): List<Pair<Long, Long?>> {
        val intervals = ArrayList<Pair<Long, Long?>>()
        var opened: Long? = null
        for (event in events) {
            if (event.kind == "pause") opened = event.elapsedMs
            else if (event.kind == "resume" && opened != null) {
                intervals += opened to event.elapsedMs
                opened = null
            }
        }
        if (opened != null) intervals += opened to null
        return intervals
    }

    suspend fun recover(db: RunDatabase, run: RunRow) {
        val fixes = db.dao().fixes(run.id)
        val events = db.dao().pauses(run.id)
        val lastFix = fixes.maxByOrNull { it.fixElapsedMs }
        val lastEvent = events.maxByOrNull { it.elapsedMs }
        val elapsed = maxOf(run.startedElapsedMs, lastFix?.fixElapsedMs ?: 0L, lastEvent?.elapsedMs ?: 0L)
        val wall = when {
            lastEvent != null && lastEvent.elapsedMs == elapsed -> lastEvent.wallMs
            lastFix != null && lastFix.fixElapsedMs == elapsed -> lastFix.fixWallMs
            else -> run.startedWallMs
        }
        replay(db, run.id, run.copy(state = "recovered", endedElapsedMs = elapsed,
            endedWallMs = wall, currentPaceSecKm = null))
    }
}
