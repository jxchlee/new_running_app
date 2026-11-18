package com.running2.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.running2.app.BuildConfig
import com.running2.app.data.TrackPointRow
import com.running2.app.core.ActivityBout
import com.running2.app.core.ActivityKind
import com.running2.app.core.BreakCause
import com.running2.app.core.BreakEvent
import kotlinx.coroutines.delay

private const val ROUTE_COLOR = 0xff146ad2.toInt()

enum class RouteMapMode { PREVIEW, LIVE, HISTORY }

private class DrawnRoute {
    val fixIds = ArrayList<Long>()
    val segments = ArrayList<Int>()
    val coordinates = LinkedHashMap<Int, ArrayList<LatLng>>()
    val lines = HashMap<Int, Polyline>()
    var start: Marker? = null
    var end: Marker? = null
    val breakMarkers = ArrayList<Marker>()
    val breakMarkerEvents = HashMap<String, BreakEvent>()
    val activityLines = ArrayList<Polyline>()
    val selectionLines = ArrayList<Polyline>()
    fun reset(map: GoogleMap) {
        map.clear()
        fixIds.clear()
        segments.clear()
        coordinates.clear()
        lines.clear()
        start = null
        end = null
        breakMarkers.clear()
        breakMarkerEvents.clear()
        activityLines.clear()
        selectionLines.clear()
    }
}

/** This location subscription is for map preview only; RunService records the raw fixes. */
@Composable
fun RouteMap(mode: RouteMapMode, points: List<TrackPointRow>, hasFinePermission: Boolean,
             modifier: Modifier = Modifier, breaks: List<BreakEvent> = emptyList(),
             startedWallMs: Long = 0, startedElapsedMs: Long = 0,
             focusBreakId: Int? = null, focusBout: ActivityBout? = null,
             focusRequest: Int = 0,
             activityBouts: List<ActivityBout> = emptyList(),
             topPaddingPx: Int = 0, bottomPaddingPx: Int = 0, overlayTop: Dp = 12.dp) {
    if (!BuildConfig.MAPS_CONFIGURED) {
        MapMessage(if (mode == RouteMapMode.HISTORY) "Google 지도 API 키 미설정 · 기록은 저장되어 있습니다" else "Google 지도 API 키 미설정 · 위치 기록은 계속됩니다", modifier)
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Start the camera where the user last was (or at the route) so the first frame is never
    // Google's default (0, 0) world view while waiting for a GPS fix.
    val mapView = remember {
        val start = if (mode == RouteMapMode.HISTORY) points.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
            ?: LocationMemory.last(context) else LocationMemory.last(context)
        runCatching {
            if (start == null) MapView(context)
            else MapView(context, GoogleMapOptions().camera(CameraPosition.fromLatLngZoom(start, 16f)))
        }.getOrNull()
    }
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var follow by remember(mode) { mutableStateOf(mode != RouteMapMode.HISTORY) }
    var initialCenter by remember(mode) { mutableStateOf(false) }
    var fitRequest by remember(mode) { mutableStateOf(0) }
    val drawn = remember(mapView) { DrawnRoute() }
    var detailEvent by remember(mapView) { mutableStateOf<BreakEvent?>(null) }

    if (mapView == null || failed) {
        MapMessage(if (mode == RouteMapMode.HISTORY) "지도를 표시할 수 없습니다 · 기록은 저장되어 있습니다" else "지도를 표시할 수 없습니다 · 위치 기록은 계속됩니다", modifier)
        return
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var disposed = false
        val initialized = runCatching {
            mapView.onCreate(null)
            mapView.getMapAsync { ready ->
                if (!disposed) {
                    ready.setOnCameraMoveStartedListener { reason ->
                        if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                            follow = false
                            initialCenter = true
                        }
                    }
                    ready.setOnMyLocationButtonClickListener {
                        follow = true
                        false
                    }
                    ready.setOnMarkerClickListener { marker ->
                        val event = drawn.breakMarkerEvents[marker.id]
                        if (event != null) {
                            detailEvent = event
                            true
                        } else false
                    }
                    ready.setOnMapClickListener { detailEvent = null }
                    ready.setOnMapLoadedCallback { loaded = true }
                    map = ready
                }
            }
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        }.isSuccess
        if (!initialized) failed = true
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); LocationMemory.flush(context) }
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }.onFailure { failed = true }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                map?.setOnCameraMoveStartedListener(null)
                map?.setOnMyLocationButtonClickListener(null)
                map?.setOnMarkerClickListener(null)
                map?.setOnMapClickListener(null)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
            map = null
        }
    }

    DisposableEffect(mode, hasFinePermission, mapView) {
        if (mode == RouteMapMode.HISTORY || !hasFinePermission) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        var active = true
        fun accept(location: Location) {
            lastLocation = location
            LocationMemory.update(context, location.latitude, location.longitude)
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let(::accept) }
        }
        val started = runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // The device's cached fix is usually available instantly; a fresh GPS fix replaces it.
                client.lastLocation.addOnSuccessListener { cached ->
                    if (active && cached != null && lastLocation == null) accept(cached)
                }
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }
        }.isSuccess
        if (!started) failed = true
        onDispose {
            active = false
            client.removeLocationUpdates(callback)
            LocationMemory.flush(context)
        }
    }

    LaunchedEffect(map, hasFinePermission, mode) {
        val ready = map ?: return@LaunchedEffect
        runCatching {
            ready.isMyLocationEnabled = hasFinePermission && mode != RouteMapMode.HISTORY
            ready.uiSettings.isMyLocationButtonEnabled = hasFinePermission && mode != RouteMapMode.HISTORY
        }.onFailure { failed = true }
    }

    // The sheet covers the bottom of the map; padding keeps the camera target in the visible part.
    LaunchedEffect(map, topPaddingPx, bottomPaddingPx) {
        val ready = map ?: return@LaunchedEffect
        runCatching { ready.setPadding(0, topPaddingPx, 0, bottomPaddingPx) }
        if (mode == RouteMapMode.HISTORY && !initialCenter) fitRequest++
    }

    LaunchedEffect(map, lastLocation, follow, mode) {
        val ready = map ?: return@LaunchedEffect
        val location = lastLocation ?: return@LaunchedEffect
        if (mode != RouteMapMode.HISTORY && (follow || !initialCenter)) {
            runCatching { ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16f)) }
            initialCenter = true
        }
    }

    LaunchedEffect(map, points, mode) {
        val ready = map ?: return@LaunchedEffect
        runCatching {
            val prefixMatches = points.size >= drawn.fixIds.size && drawn.fixIds.indices.all {
                points[it].fixId == drawn.fixIds[it] && points[it].segment == drawn.segments[it]
            }
            if (!prefixMatches) drawn.reset(ready)
            if (mode != RouteMapMode.PREVIEW) {
                val changedSegments = LinkedHashSet<Int>()
                for (index in drawn.fixIds.size until points.size) {
                    val point = points[index]
                    drawn.coordinates.getOrPut(point.segment) { ArrayList() }
                        .add(LatLng(point.latitude, point.longitude))
                    changedSegments += point.segment
                    drawn.fixIds += point.fixId
                    drawn.segments += point.segment
                }
                for (segment in changedSegments) {
                    val coordinates = drawn.coordinates.getValue(segment)
                    if (coordinates.size < 2) continue
                    val line = drawn.lines[segment]
                    if (line == null) drawn.lines[segment] = ready.addPolyline(PolylineOptions()
                        .addAll(coordinates).color(ROUTE_COLOR).width(8f))
                    else line.points = coordinates.toList()
                }
                if (mode == RouteMapMode.HISTORY && points.isNotEmpty()) {
                    if (drawn.start == null) drawn.start = ready.addMarker(MarkerOptions().position(LatLng(points.first().latitude, points.first().longitude))
                        .title("출발").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
                    drawn.end?.remove()
                    drawn.end = if (points.size > 1) ready.addMarker(MarkerOptions().position(LatLng(points.last().latitude, points.last().longitude))
                        .title("마지막 기록 위치").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))) else null
                }
            }
        }.onFailure { failed = true }
        if (mode == RouteMapMode.HISTORY) fitRequest++
    }

    LaunchedEffect(map, points, breaks, activityBouts) {
        val ready = map ?: return@LaunchedEffect
        if (mode != RouteMapMode.HISTORY) return@LaunchedEffect
        runCatching {
            drawn.breakMarkers.forEach { it.remove() }
            drawn.breakMarkers.clear()
            drawn.breakMarkerEvents.clear()
            drawn.activityLines.forEach { it.remove() }
            drawn.activityLines.clear()
            for (event in breaks) {
                val title = when (event.primaryCause) {
                    BreakCause.MANUAL_PAUSE -> "일시정지"
                    BreakCause.RAW_GAP -> "GPS 수신 공백"
                    BreakCause.POOR_ACCURACY -> "정확도 부족"
                    BreakCause.INVALID_COORDINATES -> "위치값 오류"
                    BreakCause.IMPLAUSIBLE_JUMP -> "GPS 튐"
                    BreakCause.UNKNOWN -> "원인 불명"
                }
                val manual = event.primaryCause == BreakCause.MANUAL_PAUSE
                val detail = "${event.causes.joinToString(" · ") { breakCauseName(it) }} · " +
                    "${routeTime(startedWallMs, startedElapsedMs, event.startMs)}–${routeTime(startedWallMs, startedElapsedMs, event.endMs)} · " +
                    "표시 경로 사이의 시간 ${routeDuration(event.endMs - event.startMs)}"
                ready.addMarker(MarkerOptions()
                    .position(LatLng(event.before.latitude, event.before.longitude))
                    .title("끊김 시작 · $title").snippet(detail)
                    .icon(breakStartIcon(manual)))?.let {
                    drawn.breakMarkers += it
                    drawn.breakMarkerEvents[it.id] = event
                }
                event.after?.let { after ->
                    ready.addMarker(MarkerOptions()
                        .position(LatLng(after.latitude, after.longitude))
                        .title("끊김 끝 · $title").snippet(detail)
                        .icon(breakEndIcon(manual)))?.let {
                        drawn.breakMarkers += it
                        drawn.breakMarkerEvents[it.id] = event
                    }
                }
            }
            for (bout in activityBouts) {
                val color = when (bout.kind) {
                    ActivityKind.RUNNING -> 0xffe35b23.toInt()
                    ActivityKind.WALKING -> 0xff2d9d63.toInt()
                    ActivityKind.STOPPED -> 0xff888888.toInt()
                    ActivityKind.UNKNOWN -> 0xffa47bc2.toInt()
                }
                val coords = ArrayList<LatLng>()
                fun flush() {
                    if (coords.size >= 2) drawn.activityLines += ready.addPolyline(
                        PolylineOptions().addAll(coords).color(color).width(5f))
                    coords.clear()
                }
                for (interval in bout.intervals) {
                    if (interval.distanceM <= 0) { flush(); continue }
                    val start = LatLng(interval.startLatitude, interval.startLongitude)
                    val end = LatLng(interval.endLatitude, interval.endLongitude)
                    if (coords.isEmpty()) coords += start
                    coords += end
                }
                flush()
            }
        }.onFailure { failed = true }
    }

    LaunchedEffect(map, points, focusBreakId, focusBout, breaks, focusRequest) {
        val ready = map ?: return@LaunchedEffect
        if (mode != RouteMapMode.HISTORY) return@LaunchedEffect
        drawn.selectionLines.forEach { it.remove() }
        drawn.selectionLines.clear()
        val event = breaks.firstOrNull { it.id == focusBreakId }
        // An explicit focus counts like a gesture: later sheet resizes must not refit over it.
        if (event != null || focusBout != null) initialCenter = true
        if (event != null) {
            detailEvent = event
            val location = LatLng(event.before.latitude, event.before.longitude)
            runCatching { ready.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 17f)) }
        } else if (focusBout != null) {
            detailEvent = null
            val highlight = ArrayList<LatLng>()
            fun flushHighlight() {
                if (highlight.size >= 2) drawn.selectionLines += ready.addPolyline(
                    PolylineOptions().addAll(highlight).color(0xffffff00.toInt()).width(13f).zIndex(10f))
                highlight.clear()
            }
            for (interval in focusBout.intervals) {
                if (interval.distanceM <= 0) { flushHighlight(); continue }
                val start = LatLng(interval.startLatitude, interval.startLongitude)
                val end = LatLng(interval.endLatitude, interval.endLongitude)
                if (highlight.isNotEmpty() && highlight.last() != start) flushHighlight()
                if (highlight.isEmpty()) highlight += start
                highlight += end
            }
            flushHighlight()
            val coords = focusBout.intervals.flatMap { listOf(
                LatLng(it.startLatitude, it.startLongitude), LatLng(it.endLatitude, it.endLongitude)) }
            if (coords.isNotEmpty()) runCatching {
                if (coords.distinct().size == 1) ready.animateCamera(CameraUpdateFactory.newLatLngZoom(coords.first(), 17f))
                else ready.animateCamera(CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.builder().apply { coords.forEach(::include) }.build(), 100))
            }
        }
    }

    LaunchedEffect(map, fitRequest, mode, points) {
        val ready = map ?: return@LaunchedEffect
        if (mode != RouteMapMode.HISTORY || points.isEmpty()) return@LaunchedEffect
        repeat(20) {
            if (mapView.width > 0 && mapView.height > 0) return@repeat
            delay(100)
        }
        // With the sheet fully expanded almost nothing of the map is visible; fitting then would
        // throw "View size is too small after padding". Skip it; the next resize refits.
        val visible = mapView.height - topPaddingPx - bottomPaddingPx
        if (mapView.width > 0 && visible > 240) runCatching {
            val coordinates = points.map { LatLng(it.latitude, it.longitude) }
            if (coordinates.size == 1 || coordinates.all { it == coordinates.first() }) {
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinates.first(), 16f))
            } else {
                val bounds = LatLngBounds.builder().apply { coordinates.forEach(::include) }.build()
                ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
        }
    }

    var tileTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(loaded, map) {
        tileTimedOut = false
        if (map != null && !loaded) { delay(15_000); tileTimedOut = !loaded }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        // History keeps its own buttons on the top row, so its chips start one row lower.
        val chipsTop = if (mode == RouteMapMode.HISTORY) overlayTop + 56.dp else overlayTop
        // Live/preview maps show Google's my-location button at the top right; keep chips clear of it.
        val chipsSide = if (mode == RouteMapMode.HISTORY) 12.dp else 64.dp
        Column(Modifier.align(Alignment.TopCenter).padding(top = chipsTop, start = chipsSide, end = chipsSide),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (mode != RouteMapMode.HISTORY && hasFinePermission && lastLocation == null) MapChip("현재 위치 확인 중")
            if (tileTimedOut) MapChip(if (mode == RouteMapMode.HISTORY) "지도 타일을 불러오지 못했습니다 · 기록은 저장되어 있습니다" else "지도 타일을 불러오지 못했습니다 · 위치 기록은 계속됩니다")
            if (mode == RouteMapMode.HISTORY && points.isEmpty()) MapChip("저장된 경로가 없습니다")
            if (mode == RouteMapMode.HISTORY) detailEvent?.let { event ->
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
                        Text("끊긴 구간 · ${event.causes.joinToString(" · ") { breakCauseName(it) }}",
                            style = MaterialTheme.typography.titleSmall)
                        Text("마지막 ${routeTime(startedWallMs, startedElapsedMs, event.startMs)} · " +
                            (if (event.after == null) "재시작 지점 없음" else
                                "재시작 ${routeTime(startedWallMs, startedElapsedMs, event.endMs)}"),
                            style = MaterialTheme.typography.bodyMedium)
                        Text((if (event.after == null) "마지막 경로점 이후 종료까지 " else "끊긴 시간 ") +
                            routeDuration(event.endMs - event.startMs), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { detailEvent = null }, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
                    }
                }
            }
        }
        if (mode == RouteMapMode.HISTORY && points.isNotEmpty()) FilledTonalButton(onClick = { fitRequest++ },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = overlayTop, end = 12.dp)) { Text("전체 경로") }
    }
}

@Composable
private fun MapChip(text: String) {
    Surface(shape = RoundedCornerShape(50), tonalElevation = 3.dp, shadowElevation = 2.dp,
        modifier = Modifier.padding(bottom = 6.dp)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MapMessage(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(message, Modifier.padding(16.dp)) }
}

private fun routeTime(startedWallMs: Long, startedElapsedMs: Long, elapsedMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
        .format(java.util.Date(startedWallMs + elapsedMs - startedElapsedMs))

private fun breakEndIcon(manual: Boolean): com.google.android.gms.maps.model.BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (manual) 0xff888888.toInt() else 0xffd44343.toInt() }
    canvas.drawCircle(16f, 16f, 13f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(16f, 16f, 6f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun breakStartIcon(manual: Boolean): com.google.android.gms.maps.model.BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (manual) 0xff888888.toInt() else 0xffd44343.toInt() }
    canvas.drawRoundRect(3f, 3f, 29f, 29f, 3f, 3f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawRect(12f, 12f, 20f, 20f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun breakCauseName(cause: BreakCause): String = when (cause) {
    BreakCause.MANUAL_PAUSE -> "일시정지"
    BreakCause.RAW_GAP -> "원본 위치 미수신"
    BreakCause.POOR_ACCURACY -> "정확도 부족"
    BreakCause.INVALID_COORDINATES -> "위치값 오류"
    BreakCause.IMPLAUSIBLE_JUMP -> "GPS 튐"
    BreakCause.UNKNOWN -> "원인 불명"
}

private fun routeDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return "%02d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60)
}
