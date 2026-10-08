package com.running2.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.running2.app.data.*
import com.running2.app.service.RunService
import com.running2.app.ui.RouteMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val db by lazy { RunDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // A live service survives Activity recreation; only an abandoned session is recovered.
            if (!RunService.isAlive) {
                val run = db.dao().active()
                if (run != null) RunRepository.recover(db, run)
            }
        }
        setContent { MaterialTheme { RunningApp(db, ::command) } }
    }

    private fun command(action: String) {
        val intent = Intent(this, RunService::class.java).setAction(action)
        if (action == RunService.START) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }
}

@Composable
private fun RunningApp(db: RunDatabase, command: (String) -> Unit) {
    val runs by db.dao().observeRuns().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<Long?>(null) }
    val active = runs.firstOrNull { it.state == "running" || it.state == "paused" }
    var previousActiveId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(active?.id) {
        if (active == null && previousActiveId != null) selected = previousActiveId
        previousActiveId = active?.id
    }
    val shown = selected?.let { id -> runs.firstOrNull { it.id == id } } ?: active
    val points by (if (shown != null) db.dao().observePoints(shown.id) else kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val latestFix by (if (shown != null) db.dao().observeLatestFix(shown.id) else kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)
    val pauses by (if (shown != null) db.dao().observePauses(shown.id) else kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    var tick by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active?.id) { while (true) { tick = SystemClock.elapsedRealtime(); delay(1000) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasFine by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine) command(RunService.START)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Text("Running2", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        if (shown != null) {
            Text(if (shown.state == "recovered") "비정상 종료 · 복구된 기록" else when (shown.state) {
                "running" -> "측정 중"; "paused" -> "일시정지"; else -> "완료"
            }, style = MaterialTheme.typography.titleMedium)
            val end = if (shown.state == "running" || shown.state == "paused") tick else shown.endedElapsedMs ?: shown.startedElapsedMs
            val elapsed = (end - shown.startedElapsedMs).coerceAtLeast(0)
            val pauseMs = pauses.fold(0L to null as Long?) { acc, item ->
                if (item.kind == "pause") acc.first to item.elapsedMs
                else (acc.first + ((item.elapsedMs - (acc.second ?: item.elapsedMs)).coerceAtLeast(0))) to null
            }.let { (total, open) -> total + if (open != null) (end - open).coerceAtLeast(0) else 0 }
            Text("측정 거리  ${"%.2f".format(Locale.US, shown.distanceM / 1000)} km", style = MaterialTheme.typography.headlineSmall)
            Text("경과 ${duration(elapsed)}  ·  운동 ${duration(elapsed - pauseMs)}")
            val avg = if (shown.distanceM > 0) shown.measuredMs / shown.distanceM else null
            Text("측정 구간 평균 페이스  ${pace(avg)}")
            val fresh = shown.lastFixElapsedMs != null && tick - shown.lastFixElapsedMs < 15_000
            Text("현재 페이스  ${if (shown.state == "running" && fresh) pace(shown.currentPaceSecKm) else "—"}")
            if (shown.gapCount > 0) Text("GPS 미측정 구간 있음 (${shown.gapCount}회)", color = MaterialTheme.colorScheme.error)
            if (shown.state == "running" && !fresh) Text("GPS 불안정 · 위치 측정 중", color = MaterialTheme.colorScheme.error)
            val liveFix = latestFix?.takeIf { shown.state == "running" && tick - it.fixElapsedMs in 0..15_000 }
            if (liveFix != null) Text("현재 위치 정확도  ±${liveFix.accuracyM?.toInt() ?: "—"} m")
            RouteMap(points, liveFix, Modifier.fillMaxWidth().weight(1f))
            if (shown.state == "running" || shown.state == "paused") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { command(if (shown.state == "running") RunService.PAUSE else RunService.RESUME) }) {
                        Text(if (shown.state == "running") "일시정지" else "재개")
                    }
                    OutlinedButton(onClick = { command(RunService.FINISH) }) { Text("종료") }
                }
            } else Button(onClick = { selected = null }) { Text("기록 목록") }
        } else {
            Text(if (hasFine) "달리기를 시작할 수 있습니다" else "정확한 위치 권한이 필요합니다")
            Button(onClick = {
                if (hasFine) command(RunService.START)
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("달리기 시작") }
            Text("기록", style = MaterialTheme.typography.titleLarge)
            LazyColumn {
                items(runs) { run ->
                    TextButton(onClick = { selected = run.id }) {
                        Text("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(run.startedWallMs))}  ·  ${"%.2f".format(Locale.US, run.distanceM / 1000)} km")
                    }
                }
            }
        }
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
}
private fun pace(secondsPerKm: Double?): String {
    if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0) return "—"
    val s = secondsPerKm.toInt()
    return "%d:%02d /km".format(Locale.US, s / 60, s % 60)
}
