package com.running2.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.running2.app.MainActivity
import com.running2.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val db by lazy { RunDatabase.get(this) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var runId: Long? = null
    private var callback: LocationCallback? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() { super.onCreate(); isAlive = true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            foreground()
            scope.launch { lock.withLock { startRun() } }
        } else {
            scope.launch { lock.withLock {
                when (intent?.action) {
                    PAUSE -> changePause("pause")
                    RESUME -> changePause("resume")
                    FINISH -> finishRun()
                }
            } }
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("running", "달리기 기록", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Running2 기록 중")
            .setContentText("달리기 기록을 저장하고 있습니다").setContentIntent(pending).setOngoing(true).build()
        startForeground(10, notification)
    }

    private suspend fun startRun() {
        if (runId != null) return
        db.dao().active()?.let { RunRepository.recover(db, it) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val now = SystemClock.elapsedRealtime()
        runId = db.dao().insertRun(RunRow(startedWallMs = System.currentTimeMillis(), startedElapsedMs = now))
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L).setMaxUpdateDelayMillis(0L).build()
        val id = runId ?: return
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val received = System.currentTimeMillis()
                val batch = result.locations.map { it.toRaw(id, received) }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { lock.withLock { saveBatch(id, batch) } }
            }
        }
        try {
            fused.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
                .addOnFailureListener {
                    scope.launch { lock.withLock { if (runId == id) finishRun("failed") } }
                }
        } catch (_: SecurityException) { finishRun("failed") }
    }

    private fun Location.toRaw(id: Long, received: Long) = RawFixRow(runId = id, latitude = latitude,
        longitude = longitude, fixWallMs = time, fixElapsedMs = elapsedRealtimeNanos / 1_000_000,
        receivedWallMs = received, accuracyM = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null, provider = provider)

    private suspend fun saveBatch(id: Long, batch: List<RawFixRow>) {
        if (batch.isEmpty()) return
        if (runId != id) return
        val run = db.dao().getRun(id) ?: return
        if (run.state != "running" && run.state != "paused") return
        // Raw observations commit before any derived result is produced.
        db.dao().insertFixes(batch)
        RunRepository.replay(db, id)
    }

    private suspend fun changePause(kind: String) {
        val id = runId ?: return
        val run = db.dao().getRun(id) ?: return
        if ((kind == "pause" && run.state != "running") || (kind == "resume" && run.state != "paused")) return
        db.dao().insertPause(PauseRow(runId = id, elapsedMs = SystemClock.elapsedRealtime(), wallMs = System.currentTimeMillis(), kind = kind))
        db.dao().updateRun(run.copy(state = if (kind == "pause") "paused" else "running", currentPaceSecKm = null))
        RunRepository.replay(db, id)
    }

    private suspend fun finishRun(state: String = "finished") {
        val id = runId ?: return
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        val run = db.dao().getRun(id) ?: return
        val end = SystemClock.elapsedRealtime()
        RunRepository.replay(db, id, run.copy(state = state, endedWallMs = System.currentTimeMillis(),
            endedElapsedMs = end, currentPaceSecKm = null))
        runId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { isAlive = false; callback?.let { fused.removeLocationUpdates(it) }; scope.cancel(); super.onDestroy() }

    companion object {
        @Volatile var isAlive = false
        const val START = "com.running2.START"
        const val PAUSE = "com.running2.PAUSE"
        const val RESUME = "com.running2.RESUME"
        const val FINISH = "com.running2.FINISH"
    }
}
