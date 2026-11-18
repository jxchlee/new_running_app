package com.running2.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.running2.app.data.*
import com.running2.app.core.*
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
        setContent { RunLiteTheme { Surface(Modifier.fillMaxSize()) { RunningApp(db, ::command) } } }
    }

    private fun command(action: String) {
        val intent = Intent(this, RunService::class.java).setAction(action)
        if (action == RunService.START) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }
}

private enum class Page { START, LIST, DETAIL, SUMMARY }

private data class AnalysisSnapshot(val liveSpeedKmh: Double?, val history: RunAnalysis?, val error: String?)

private fun pauseRanges(events: List<PauseRow>): List<Pair<Long, Long?>> {
    val ranges = ArrayList<Pair<Long, Long?>>()
    var start: Long? = null
    for (event in events) {
        if (event.kind == "pause") start = event.elapsedMs
        else if (event.kind == "resume" && start != null) { ranges += start to event.elapsedMs; start = null }
    }
    if (start != null) ranges += start to null
    return ranges
}

private fun analyzeSnapshot(run: RunRow, fixes: List<RawFixRow>, pauses: List<PauseRow>,
                            points: List<TrackPointRow>, nowMs: Long): AnalysisSnapshot {
    return runCatching {
        val live = run.state == "running" || run.state == "paused"
        val source = if (live) fixes.asReversed().takeWhile { it.fixElapsedMs >= nowMs - 40_000L }
            .asReversed() else fixes
        val replay = TrackEngine(version = run.calculationVersion).calculate(source.map {
            Fix(it.id, it.fixElapsedMs, it.latitude, it.longitude, it.accuracyM, it.speedMps)
        }, run.startedElapsedMs, run.endedElapsedMs, pauseRanges(pauses), includeDecisions = !live)
        if (live) AnalysisSnapshot(
            if (run.state == "running") RunAnalysisEngine.currentSpeedKmh(replay.intervals, nowMs) else null,
            null, null)
        else {
            require(replay.points.size == points.size && replay.points.zip(points).all { (a, b) ->
                a.fixId == b.fixId && a.segment == b.segment && a.elapsedMs == b.elapsedMs &&
                    kotlin.math.abs(a.latitude - b.latitude) < 1e-7 && kotlin.math.abs(a.longitude - b.longitude) < 1e-7
            }) { "저장된 경로와 분석 경로가 다릅니다" }
            val result = RunAnalysisEngine.analyze(replay, fixes.map {
                ObservedFix(it.id, it.fixElapsedMs, it.receivedWallMs)
            }, pauseRanges(pauses), run.distanceM, run.measuredMs, run.endedElapsedMs)
            AnalysisSnapshot(null, result, null)
        }
    }.getOrElse { AnalysisSnapshot(null, null, it.message ?: "분석할 수 없습니다") }
}

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
    val stateKey = listOf<Any?>(selected, fixes.size, fixes.lastOrNull()?.id,
        pauses.size, pauses.lastOrNull()?.id, if (active != null) tick else null)
    var state by remember(runId) { mutableStateOf<Pair<List<Any?>, RunUiState>?>(null) }
    LaunchedEffect(stateKey, fixes, pauses) {
        val run = selected
        if (run != null) {
            val calculated = withContext(Dispatchers.Default) { runUiState(run, fixes, pauses, tick) }
            state = stateKey to calculated
        }
    }

    if (finishPending) AlertDialog(
        onDismissRequest = { finishPending = false },
        title = { Text("달리기를 종료할까요?") },
        text = { Text("지금까지의 경로와 기록을 저장합니다.") },
        confirmButton = { TextButton(onClick = { finishPending = false; command(RunService.FINISH) }) { Text("종료") } },
        dismissButton = { TextButton(onClick = { finishPending = false }) { Text("계속 달리기") } }
    )

    // Keep the live map composed while the next one-second snapshot is calculated.
    // A finished record waits for the exact saved snapshot before showing its summary.
    val visibleState = if (active != null) state?.second else state?.takeIf { it.first == stateKey }?.second
    val analysisKey = listOf(runId, selected?.state, fixes.size, fixes.lastOrNull()?.id,
        pauses.size, pauses.lastOrNull()?.id, if (active == null) points.size else null,
        if (active == null) points.lastOrNull()?.fixId else null)
    var analysisState by remember(runId) { mutableStateOf<Pair<List<Any?>, AnalysisSnapshot>?>(null) }
    LaunchedEffect(analysisKey) {
        val run = selected ?: return@LaunchedEffect
        analysisState = analysisKey to withContext(Dispatchers.Default) {
            analyzeSnapshot(run, fixes, pauses, points, tick)
        }
    }
    val visibleAnalysis = analysisState?.takeIf { it.first == analysisKey }?.second

    val historyPage = active == null && selected != null &&
        (pageName == Page.DETAIL.name || pageName == Page.SUMMARY.name)
    val listPage = active == null && pageName == Page.LIST.name
    BackHandler(enabled = historyPage || listPage) {
        pageName = if (pageName == Page.DETAIL.name) Page.LIST.name else Page.START.name
        selectedId = null
    }
    if (!historyPage && !listPage) {
        // One call site for both "ready" and "running": the map (and the location it already
        // knows) survives pressing Start instead of being rebuilt from a blank world view.
        HomePage(active, if (active != null) visibleState else null,
            if (active != null) points else emptyList(), hasFine,
            if (visibleState?.gps == GpsUiState.RECEIVING) visibleAnalysis?.liveSpeedKmh else null,
            runCount = runs.size, requestPermission = requestPermission,
            onStart = { command(RunService.START) }, onList = { pageName = Page.LIST.name },
            command = command, onFinish = { finishPending = true })
    } else if (listPage) {
        ListPage(runs, onBack = { pageName = Page.START.name }, onSelect = {
            selectedId = it; pageName = Page.DETAIL.name
        })
    } else if (selected != null && visibleState != null) {
        val summary = pageName == Page.SUMMARY.name
        HistoryPage(selected, visibleState, points, visibleAnalysis, summary, onBack = {
            pageName = if (summary) Page.START.name else Page.LIST.name
            selectedId = null
        })
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

// ---------------------------------------------------------------- home (ready + running)

@Composable
private fun HomePage(run: RunRow?, state: RunUiState?, points: List<TrackPointRow>, hasFine: Boolean,
                     currentSpeedKmh: Double?, runCount: Int, requestPermission: () -> Unit,
                     onStart: () -> Unit, onList: () -> Unit, command: (String) -> Unit, onFinish: () -> Unit) {
    var sheetPx by remember { mutableStateOf(0) }
    val topPx = with(LocalDensity.current) { 64.dp.roundToPx() }
    MapSheetLayout(
        initialStop = SheetStop.COLLAPSED,
        onSheetSettled = { sheetPx = it },
        map = {
            RouteMap(if (run != null) RouteMapMode.LIVE else RouteMapMode.PREVIEW, points, hasFine,
                Modifier.fillMaxSize(), topPaddingPx = topPx, bottomPaddingPx = sheetPx, overlayTop = 68.dp)
        },
        overlay = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (run == null) {
                    Pill { Text("RunLite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = onList) { Text(if (runCount > 0) "기록 $runCount" else "기록") }
                } else {
                    val gps = state?.gps ?: GpsUiState.WAITING
                    Pill {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(gpsColor(gps)))
                        Spacer(Modifier.width(8.dp))
                        Text(gpsLabel(gps), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        header = {
            if (run == null) ReadyHeader(hasFine, requestPermission, onStart)
            else LiveHeader(run, state, currentSpeedKmh, command, onFinish)
        },
        body = if (run == null) null else ({ LiveBody(run, state, currentSpeedKmh) })
    )
}

@Composable
private fun ReadyHeader(hasFine: Boolean, requestPermission: () -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 16.dp).navigationBarsPadding()) {
        if (!hasFine) {
            Text("현재 위치와 경로를 기록하려면 정확한 위치 권한이 필요합니다",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = if (hasFine) onStart else requestPermission,
            modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) {
            Text(if (hasFine) "달리기 시작" else "위치 권한 허용", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LiveHeader(run: RunRow, state: RunUiState?, currentSpeedKmh: Double?,
                       command: (String) -> Unit, onFinish: () -> Unit) {
    val paused = run.state == "paused"
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        if (paused) Text("일시정지됨", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        HeroDistance(run.distanceM)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            StatCell("시간", duration(state?.elapsedMs ?: 0), Modifier.weight(1f))
            StatCell("현재 페이스", pace(paceFromSpeed(currentSpeedKmh)), Modifier.weight(1f))
            StatCell("평균 페이스", pace(averagePace(run)), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { command(if (paused) RunService.RESUME else RunService.PAUSE) },
                modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (paused) "재개" else "일시정지", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(18.dp)) {
                Text("종료", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun LiveBody(run: RunRow, state: RunUiState?, currentSpeedKmh: Double?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        SectionTitle("상세")
        InfoCard {
            InfoRow("운동 시간", duration(state?.exerciseMs ?: 0), "일시정지 제외")
            InfoRow("현재 속도", speed(currentSpeedKmh))
            InfoRow("평균 속도", speed(averageSpeed(run)))
        }
        SectionTitle("GPS")
        InfoCard {
            InfoRow("상태", gpsLabel(state?.gps ?: GpsUiState.WAITING))
            InfoRow("정확도", state?.latestAccuracyM?.let { "±${it.toInt()} m" } ?: "—")
            if (state != null) InfoRow("수신 공백 · 경로 단절", "${state.rawGapCount}회 · ${state.routeGapCount}회")
        }
    }
}

// ---------------------------------------------------------------- list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPage(runs: List<RunRow>, onBack: () -> Unit, onSelect: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("기록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (runs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("아직 저장된 달리기가 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(runs, key = { it.id }) { run ->
                Card(onClick = { onSelect(run.id) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dateTitle(run.startedWallMs), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            statusLabel(run)?.let { StatusTag(it) }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(distance(run.distanceM), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(" km", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text("${duration(totalElapsed(run))}  ·  평균 페이스 ${pace(averagePace(run))}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- result / record detail

@Composable
private fun HistoryPage(run: RunRow, state: RunUiState, points: List<TrackPointRow>,
                        analysis: AnalysisSnapshot?, summary: Boolean, onBack: () -> Unit) {
    var selectedBreak by remember(run.id) { mutableStateOf<Int?>(null) }
    var selectedBout by remember(run.id) { mutableStateOf<Int?>(null) }
    var focusRequest by remember(run.id) { mutableStateOf(0) }
    var activityColors by remember(run.id) { mutableStateOf(false) }
    var sheetPx by remember { mutableStateOf(0) }
    val topPx = with(LocalDensity.current) { 64.dp.roundToPx() }
    val result = analysis?.history
    MapSheetLayout(
        initialStop = SheetStop.HALF,
        onSheetSettled = { sheetPx = it },
        map = {
            key(run.id) {
                RouteMap(RouteMapMode.HISTORY, points, false, Modifier.fillMaxSize(),
                    breaks = result?.breaks.orEmpty(), startedWallMs = run.startedWallMs,
                    startedElapsedMs = run.startedElapsedMs, focusBreakId = selectedBreak,
                    focusRequest = focusRequest,
                    focusBout = result?.bouts?.firstOrNull { it.id == selectedBout },
                    activityBouts = if (activityColors) result?.bouts.orEmpty() else emptyList(),
                    topPaddingPx = topPx, bottomPaddingPx = sheetPx)
            }
        },
        overlay = {
            FilledTonalButton(onClick = onBack, modifier = Modifier.padding(12.dp)) {
                Text(if (summary) "← 홈" else "← 기록")
            }
        },
        header = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (summary) "달리기 완료" else dateTitle(run.startedWallMs),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    statusLabel(run)?.let { StatusTag(it) }
                }
                if (summary) Text(dateTitle(run.startedWallMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HeroDistance(run.distanceM)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatCell("시간", duration(state.exerciseMs), Modifier.weight(1f))
                    StatCell("평균 페이스", pace(averagePace(run)), Modifier.weight(1f))
                    StatCell("평균 속도", speed(averageSpeed(run)), Modifier.weight(1f))
                }
            }
        },
        body = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                SectionTitle("시간")
                InfoCard {
                    InfoRow("운동 시간", duration(state.exerciseMs), "일시정지 제외")
                    InfoRow("측정 시간", duration(run.measuredMs), "거리 계산에 쓰인 시간")
                    InfoRow("경과 시간", duration(state.elapsedMs))
                }

                SectionTitle("활동")
                when {
                    analysis == null -> Text("분석 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    analysis.error != null -> Text("활동 분석 불가: ${analysis.error}", color = MaterialTheme.colorScheme.error)
                    result != null -> ActivitySection(run, result, activityColors, onToggleColors = { activityColors = !activityColors },
                        onSelectBout = { selectedBout = it; selectedBreak = null; focusRequest++ })
                }

                if (result != null && result.breaks.isNotEmpty()) {
                    SectionTitle("끊긴 구간 ${result.breaks.size}곳")
                    Text("일시정지했거나 GPS가 오래 끊겨 경로를 잇지 않은 곳입니다. 네모는 끊긴 지점, 원은 다시 시작한 지점이고 회색은 일시정지, 빨간색은 GPS 문제입니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    InfoCard {
                        result.breaks.forEach { event ->
                            ClickableRow(onClick = { selectedBreak = event.id; selectedBout = null; focusRequest++ }) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(
                                    if (event.primaryCause == BreakCause.MANUAL_PAUSE) Color(0xff888888) else Color(0xffd44343)))
                                Spacer(Modifier.width(12.dp))
                                Text(breakName(event.primaryCause), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text("${clockTime(run, event.startMs)}–${clockTime(run, event.endMs)}",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                SectionTitle("GPS 기록 정보")
                Text("수신 공백 ${state.rawGapCount}회 · 전달 지연 ${state.deliveryGapCount}회 · 경로 단절 ${state.routeGapCount}회" +
                    if (run.calculationVersion < TrackEngine.VERSION) " · 이전 계산 방식(v${run.calculationVersion})" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (summary) {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp)) { Text("확인", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    )
}

@Composable
private fun ActivitySection(run: RunRow, result: RunAnalysis, colorsOn: Boolean,
                            onToggleColors: () -> Unit, onSelectBout: (Int) -> Unit) {
    val shown = ActivityKind.values().filter { kind ->
        val total = result.totals.getValue(kind)
        total.measuredMs > 0 || kind == ActivityKind.RUNNING || kind == ActivityKind.WALKING
    }
    val totalMs = result.totals.values.sumOf { it.measuredMs }.coerceAtLeast(1)
    // Proportion bar: how the measured time splits between activities.
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        shown.forEach { kind ->
            val ms = result.totals.getValue(kind).measuredMs
            if (ms > 0) Box(Modifier.weight(ms.toFloat() / totalMs).fillMaxHeight().background(activityColor(kind)))
        }
        val rest = totalMs - shown.sumOf { result.totals.getValue(it).measuredMs }
        if (rest > 0) Spacer(Modifier.weight(rest.toFloat() / totalMs))
    }
    Spacer(Modifier.height(10.dp))
    InfoCard {
        shown.forEach { kind ->
            val total = result.totals.getValue(kind)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(activityColor(kind)))
                Spacer(Modifier.width(12.dp))
                Text(activityName(kind), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${distance(total.distanceM)} km · ${duration(total.measuredMs)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggleColors).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("지도에 활동 색상 표시", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = colorsOn, onCheckedChange = { onToggleColors() })
        }
    }
    if (result.bouts.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("구간 · 눌러서 지도에서 보기", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        InfoCard {
            result.bouts.forEach { bout ->
                ClickableRow(onClick = { onSelectBout(bout.id) }) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(activityColor(bout.kind)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(activityName(bout.kind), style = MaterialTheme.typography.bodyLarge)
                        Text("${clockTime(run, bout.startMs)}–${clockTime(run, bout.endMs)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${distance(bout.distanceM)} km", style = MaterialTheme.typography.bodyLarge)
                        Text(pace(paceFromSpeed(bout.averageSpeedKmh)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun HeroDistance(meters: Double) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(distance(meters), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text(" km", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold)
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ClickableRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun Pill(content: @Composable RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(50), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}

@Composable
private fun StatusTag(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private fun statusLabel(run: RunRow): String? = when (run.state) {
    "recovered" -> "비정상 종료 후 복구"
    "failed" -> "위치 수집 실패"
    else -> null
}
private fun gpsLabel(state: GpsUiState): String = when (state) {
    GpsUiState.WAITING -> "위치 확인 중"
    GpsUiState.RECEIVING -> "GPS 양호"
    GpsUiState.EXCLUDED -> "GPS 약함"
    GpsUiState.NO_FIX -> "GPS 신호 없음"
    GpsUiState.PAUSED -> "일시정지 중"
}
private fun gpsColor(state: GpsUiState): Color = when (state) {
    GpsUiState.RECEIVING -> Color(0xff2d9d63)
    GpsUiState.WAITING, GpsUiState.EXCLUDED -> Color(0xffe0a020)
    GpsUiState.NO_FIX -> Color(0xffd44343)
    GpsUiState.PAUSED -> Color(0xff888888)
}
private fun activityColor(kind: ActivityKind): Color = when (kind) {
    ActivityKind.RUNNING -> Color(0xffe35b23)
    ActivityKind.WALKING -> Color(0xff2d9d63)
    ActivityKind.STOPPED -> Color(0xff888888)
    ActivityKind.UNKNOWN -> Color(0xffa47bc2)
}
private fun totalElapsed(run: RunRow): Long =
    ((run.endedElapsedMs ?: run.startedElapsedMs) - run.startedElapsedMs).coerceAtLeast(0)
/** Milliseconds per metre equals seconds per kilometre. Too short a distance gives a meaningless pace. */
private fun averagePace(run: RunRow): Double? = if (run.distanceM >= 10) run.measuredMs / run.distanceM else null
private fun averageSpeed(run: RunRow): Double? = if (run.measuredMs > 0) run.distanceM / run.measuredMs * 3600 else null
private fun paceFromSpeed(kmh: Double?): Double? = if (kmh == null || !kmh.isFinite() || kmh < 1.0) null else 3600 / kmh
private fun dateTitle(wallMs: Long): String = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA).format(Date(wallMs))
private fun distance(meters: Double): String = "%.2f".format(Locale.US, meters / 1000)
private fun speed(kmh: Double?): String = if (kmh == null || !kmh.isFinite()) "—" else "%.1f km/h".format(Locale.US, kmh)
private fun pace(secPerKm: Double?): String {
    if (secPerKm == null || !secPerKm.isFinite() || secPerKm <= 0 || secPerKm >= 60 * 60) return "—"
    val seconds = secPerKm.toLong()
    return "%d'%02d\"".format(Locale.US, seconds / 60, seconds % 60)
}
private fun clockTime(run: RunRow, elapsedMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    .format(Date(run.startedWallMs + elapsedMs - run.startedElapsedMs))
private fun activityName(kind: ActivityKind): String = when (kind) {
    ActivityKind.RUNNING -> "달리기"
    ActivityKind.WALKING -> "걷기"
    ActivityKind.STOPPED -> "정지"
    ActivityKind.UNKNOWN -> "판정 불가"
}
private fun breakName(cause: BreakCause): String = when (cause) {
    BreakCause.MANUAL_PAUSE -> "일시정지"
    BreakCause.RAW_GAP -> "GPS 수신 공백"
    BreakCause.POOR_ACCURACY -> "정확도 부족"
    BreakCause.INVALID_COORDINATES -> "위치값 오류"
    BreakCause.IMPLAUSIBLE_JUMP -> "GPS 튐"
    BreakCause.UNKNOWN -> "원인 불명"
}
private fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}
