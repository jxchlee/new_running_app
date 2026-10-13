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
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.running2.app.data.*
import com.running2.app.service.RunService
import com.running2.app.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val db by lazy { RunDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (!RunService.isAlive) db.dao().active()?.let { RunRepository.recover(db, it) }
        }
        setContent { MaterialTheme { RunningApp(db, ::command) } }
    }

    private fun command(action: String) {
        val intent = Intent(this, RunService::class.java).setAction(action)
        if (action == RunService.START) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }
}

private enum class Page { START, LIST, DETAIL, SUMMARY }

@Composable
private fun RunningApp(db: RunDatabase, command: (String) -> Unit) {
    val runs by db.dao().observeRuns().collectAsState(initial = emptyList())
    val active = runs.firstOrNull { it.state == "running" || it.state == "paused" }
    var pageName by rememberSaveable { mutableStateOf(Page.START.name) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var previousActiveId by remember { mutableStateOf<Long?>(null) }
    var finishPending by remember { mutableStateOf(false) }
    LaunchedEffect(active?.id) {
        if (active == null && previousActiveId != null) {
            selectedId = previousActiveId
            pageName = Page.SUMMARY.name
        }
        previousActiveId = active?.id
    }
    val selected = active ?: selectedId?.let { id -> runs.firstOrNull { it.id == id } }
    val runId = selected?.id
    val points by remember(runId) { if (runId == null) flowOf(emptyList()) else db.dao().observePoints(runId) }
        .collectAsState(initial = emptyList())
    val fixes by remember(runId) { if (runId == null) flowOf(emptyList()) else db.dao().observeFixes(runId) }
        .collectAsState(initial = emptyList())
    val pauses by remember(runId) { if (runId == null) flowOf(emptyList()) else db.dao().observePauses(runId) }
        .collectAsState(initial = emptyList())
    var tick by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active?.id) {
        while (active != null) { tick = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val context = LocalContext.current
    var hasFine by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val requestPermission = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val diagnosticKey = listOf(runId, fixes.size.toLong(), fixes.lastOrNull()?.id,
        pauses.size.toLong(), pauses.lastOrNull()?.id, selected?.endedElapsedMs)
    var diagnostic by remember(runId) { mutableStateOf<Pair<List<Long?>, Boolean>?>(null) }
    LaunchedEffect(diagnosticKey, fixes, pauses) {
        val run = selected
        if (run != null) {
            val excluded = withContext(Dispatchers.Default) {
            hasExcludedActiveFix(run, fixes.map { FixTimeRow(it.id, it.fixElapsedMs,
                it.receivedWallMs, it.latitude, it.longitude, it.accuracyM, it.speedMps) }, pauses)
            }
            diagnostic = diagnosticKey to excluded
        }
    }
    val excludedFixes = diagnostic?.takeIf { it.first == diagnosticKey }?.second
    val stateKey = listOf<Any?>(selected, fixes.size, fixes.lastOrNull()?.id,
        pauses.size, pauses.lastOrNull()?.id, if (active != null) tick else null, excludedFixes)
    var state by remember(runId) { mutableStateOf<Pair<List<Any?>, RunUiState>?>(null) }
    LaunchedEffect(stateKey, fixes, pauses) {
        val run = selected
        val excluded = excludedFixes
        if (run != null && excluded != null) {
            val calculated = withContext(Dispatchers.Default) { runUiState(run, fixes, pauses, tick, excluded) }
            state = stateKey to calculated
        }
    }

    if (finishPending) AlertDialog(
        onDismissRequest = { finishPending = false },
        title = { Text("달리기를 종료할까요?") },
        text = { Text("지금까지 저장된 경로와 원본 GPS를 기록으로 남깁니다.") },
        confirmButton = { TextButton(onClick = { finishPending = false; command(RunService.FINISH) }) { Text("종료") } },
        dismissButton = { TextButton(onClick = { finishPending = false }) { Text("계속 달리기") } }
    )

    // Keep the live map composed while the next one-second snapshot is calculated.
    // A finished record waits for the exact saved snapshot before showing its summary.
    val visibleState = if (active != null) state?.second else state?.takeIf { it.first == stateKey }?.second
    when {
        active != null && visibleState == null -> LoadingPage()
        active != null && visibleState != null -> LivePage(active, visibleState, points, hasFine, command, { finishPending = true })
        pageName == Page.LIST.name -> ListPage(db, runs, onBack = { pageName = Page.START.name }, onSelect = {
            selectedId = it; pageName = Page.DETAIL.name
        })
        (pageName == Page.DETAIL.name || pageName == Page.SUMMARY.name) && selected != null && visibleState != null -> {
            val summary = pageName == Page.SUMMARY.name
            HistoryPage(selected, visibleState, points, summary, onBack = {
                pageName = if (summary) Page.START.name else Page.LIST.name
                selectedId = null
            }, onDetail = { pageName = Page.DETAIL.name })
        }
        (pageName == Page.DETAIL.name || pageName == Page.SUMMARY.name) && selected != null -> LoadingPage()
        else -> StartPage(runs.size, hasFine, requestPermission, onStart = { command(RunService.START) }, onList = { pageName = Page.LIST.name })
    }
}

@Composable
private fun LoadingPage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun StartPage(count: Int, hasFine: Boolean, requestPermission: () -> Unit, onStart: () -> Unit, onList: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Running2", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RouteMap(RouteMapMode.PREVIEW, emptyList(), hasFine, Modifier.matchParentSize())
            if (!hasFine) Text("현재 위치를 보려면 정확한 위치 권한이 필요합니다", Modifier.align(Alignment.TopStart)
                .padding(12.dp).background(MaterialTheme.colorScheme.surface).padding(8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasFine) OutlinedButton(onClick = requestPermission, modifier = Modifier.fillMaxWidth()) { Text("위치 권한 허용") }
            Button(onClick = if (hasFine) onStart else requestPermission, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("달리기 시작") }
            TextButton(onClick = onList, modifier = Modifier.fillMaxWidth()) { Text("기록 보기 ($count)") }
        }
    }
}

@Composable
private fun LivePage(run: RunRow, state: RunUiState, points: List<TrackPointRow>, hasFine: Boolean,
                     command: (String) -> Unit, onFinish: () -> Unit) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RouteMap(RouteMapMode.LIVE, points, hasFine, Modifier.matchParentSize())
            Column(Modifier.align(Alignment.TopStart).padding(12.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                if (run.state == "paused") Text("일시정지", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text("${distance(run.distanceM)} km  ·  ${duration(state.elapsedMs)}", style = MaterialTheme.typography.headlineSmall)
                Text("현재 페이스 ${pace(state.currentPaceSecKm)}  ·  ${gpsLabel(state.gps)}")
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "상세 접기 ⌄" else "상세 보기 ⌃")
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("운동 시간 ${duration(state.exerciseMs)}  ·  측정 구간 평균 ${pace(state.averagePaceSecKm)}")
            Text("원본 정확도 ±${state.latestAccuracyM?.toInt() ?: "—"} m  ·  ${gpsLabel(state.gps)}")
            if (state.incomplete) Text("관측 공백 ${state.rawGapCount}회 · 전달 공백 ${state.deliveryGapCount}회 · 경로 단절 ${state.routeGapCount}회")
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { command(if (run.state == "running") RunService.PAUSE else RunService.RESUME) }, modifier = Modifier.weight(1f).height(54.dp)) {
                Text(if (run.state == "running") "일시정지" else "재개")
            }
            OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f).height(54.dp)) { Text("종료") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(db: RunDatabase, runs: List<RunRow>, onBack: () -> Unit, onSelect: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 시작") }
            Text("기록", style = MaterialTheme.typography.titleLarge)
        }
        if (runs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("아직 저장된 달리기가 없습니다") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { run ->
                val incomplete by produceState<Boolean?>(null, run.id, run.endedElapsedMs, run.gapCount) {
                    value = withContext(Dispatchers.IO) {
                        val samples = db.dao().fixTimes(run.id)
                        val pauses = db.dao().pauses(run.id)
                        val gaps = gapCounts(run, samples, pauses,
                            run.endedElapsedMs ?: SystemClock.elapsedRealtime())
                        gaps.first > 0 || gaps.second > 0 || hasExcludedActiveFix(run, samples, pauses)
                    }
                }
                Card(onClick = { onSelect(run.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(run.startedWallMs)), fontWeight = FontWeight.Bold)
                        Text("${distance(run.distanceM)} km  ·  ${duration(((run.endedElapsedMs ?: run.startedElapsedMs) - run.startedElapsedMs).coerceAtLeast(0))}")
                        Text(runStatus(run) + if (incomplete == true) " · 측정 불완전" else "")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPage(run: RunRow, state: RunUiState, points: List<TrackPointRow>, summary: Boolean,
                        onBack: () -> Unit, onDetail: () -> Unit) {
    var expanded by rememberSaveable(run.id, summary) { mutableStateOf(summary) }
    val compact = LocalConfiguration.current.screenHeightDp < 500
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(if (summary) "← 시작" else "← 기록") }
            Text(if (summary) "달리기 요약" else "기록 상세", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(run.id, summary) {
                RouteMap(RouteMapMode.HISTORY, points, false, Modifier.matchParentSize())
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "수치 접기 ⌄" else "수치 보기 ⌃")
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = if (compact) 120.dp else 260.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("${distance(run.distanceM)} km", style = MaterialTheme.typography.headlineMedium)
            Text("경과 ${duration(state.elapsedMs)}  ·  운동 ${duration(state.exerciseMs)}")
            Text("측정 구간 평균 페이스 ${pace(state.averagePaceSecKm)}")
            Text(runStatus(run))
            Text("관측 공백 ${state.rawGapCount}회 · 전달 공백 ${state.deliveryGapCount}회 · 경로 단절 ${state.routeGapCount}회")
            if (state.incomplete) Text("측정 불완전: 일부 구간의 실제 이동은 포함되지 않을 수 있습니다", color = MaterialTheme.colorScheme.error)
            if (summary) TextButton(onClick = onDetail) { Text("기록 상세 보기") }
        }
    }
}

private fun runStatus(run: RunRow): String = when (run.state) {
    "recovered" -> "비정상 종료 후 복구"
    "failed" -> "위치 수집 시작 실패"
    "finished" -> "완료"
    "paused" -> "일시정지"
    else -> "측정 중"
}
private fun gpsLabel(state: GpsUiState): String = when (state) {
    GpsUiState.WAITING -> "위치 확인 중"
    GpsUiState.RECEIVING -> "GPS 원본 수신 중"
    GpsUiState.EXCLUDED -> "GPS 수신 중 · 경로점 미채택"
    GpsUiState.NO_FIX -> "GPS 원본 수신 없음"
    GpsUiState.PAUSED -> "일시정지 중"
}
private fun distance(meters: Double): String = "%.2f".format(Locale.US, meters / 1000)
private fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
private fun pace(secondsPerKm: Double?): String {
    if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0) return "—"
    val seconds = secondsPerKm.toInt()
    return "%d:%02d /km".format(Locale.US, seconds / 60, seconds % 60)
}
