package com.running2.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "runs")
data class RunRow(@PrimaryKey(autoGenerate = true) val id: Long = 0,
                  val startedWallMs: Long, val startedElapsedMs: Long,
                  val endedWallMs: Long? = null, val endedElapsedMs: Long? = null,
                  val state: String = "running", val distanceM: Double = 0.0,
                  val measuredMs: Long = 0, val gapCount: Int = 0,
                  val currentPaceSecKm: Double? = null, val lastFixElapsedMs: Long? = null,
                  val calculationVersion: Int = 1)

@Entity(tableName = "raw_fixes", indices = [Index("runId")])
data class RawFixRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long,
                     val latitude: Double, val longitude: Double, val fixWallMs: Long,
                     val fixElapsedMs: Long, val receivedWallMs: Long,
                     val accuracyM: Float?, val speedMps: Float?, val provider: String?)

@Entity(tableName = "pause_events", indices = [Index("runId")])
data class PauseRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long,
                    val elapsedMs: Long, val wallMs: Long, val kind: String)

@Entity(tableName = "track_points", indices = [Index("runId")])
data class TrackPointRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long,
                         val fixId: Long, val segment: Int, val latitude: Double,
                         val longitude: Double, val elapsedMs: Long, val distanceM: Double)

@Dao interface RunDao {
    @Query("SELECT * FROM runs ORDER BY id DESC") fun observeRuns(): Flow<List<RunRow>>
    @Query("SELECT * FROM runs WHERE id=:id") fun observeRun(id: Long): Flow<RunRow?>
    @Query("SELECT * FROM track_points WHERE runId=:id ORDER BY segment, elapsedMs, id") fun observePoints(id: Long): Flow<List<TrackPointRow>>
    @Query("SELECT * FROM raw_fixes WHERE runId=:id ORDER BY fixElapsedMs DESC, id DESC LIMIT 1") fun observeLatestFix(id: Long): Flow<RawFixRow?>
    @Query("SELECT * FROM pause_events WHERE runId=:id ORDER BY elapsedMs, id") fun observePauses(id: Long): Flow<List<PauseRow>>
    @Query("SELECT * FROM runs WHERE state IN ('running','paused') ORDER BY id DESC LIMIT 1") suspend fun active(): RunRow?
    @Query("SELECT * FROM runs WHERE id=:id") suspend fun getRun(id: Long): RunRow?
    @Query("SELECT * FROM raw_fixes WHERE runId=:id ORDER BY fixElapsedMs, id") suspend fun fixes(id: Long): List<RawFixRow>
    @Query("SELECT * FROM pause_events WHERE runId=:id ORDER BY elapsedMs, id") suspend fun pauses(id: Long): List<PauseRow>
    @Insert suspend fun insertRun(row: RunRow): Long
    @Insert suspend fun insertFixes(rows: List<RawFixRow>)
    @Insert suspend fun insertPause(row: PauseRow)
    @Insert suspend fun insertPoints(rows: List<TrackPointRow>)
    @Update suspend fun updateRun(row: RunRow)
    @Query("DELETE FROM track_points WHERE runId=:id") suspend fun clearPoints(id: Long)
}

@Database(entities = [RunRow::class, RawFixRow::class, PauseRow::class, TrackPointRow::class], version = 1)
abstract class RunDatabase : RoomDatabase() {
    abstract fun dao(): RunDao
    companion object {
        @Volatile private var instance: RunDatabase? = null
        fun get(context: Context): RunDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RunDatabase::class.java, "running2.db")
                .build().also { instance = it }
        }
    }
}
