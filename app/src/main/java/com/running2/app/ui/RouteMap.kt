package com.running2.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.running2.app.BuildConfig
import com.running2.app.data.RawFixRow
import com.running2.app.data.TrackPointRow

private const val ROUTE_COLOR = 0xff146ad2.toInt()
private const val ACCURACY_COLOR = 0x33509de6

private class DrawnRoute {
    val fixIds = ArrayList<Long>()
    val segments = ArrayList<Int>()
    var marker: Marker? = null
    var accuracy: Circle? = null
    var centered = false
    var defaultCentered = false
    fun reset(map: GoogleMap) {
        map.clear()
        fixIds.clear()
        segments.clear()
        marker = null
        accuracy = null
        centered = false
        defaultCentered = false
    }
}

/** Map state is display-only: no location collection, persistence or distance calculation lives here. */
@Composable
fun RouteMap(points: List<TrackPointRow>, currentFix: RawFixRow?, modifier: Modifier = Modifier) {
    if (!BuildConfig.MAPS_CONFIGURED) {
        Box(modifier) { Text("Google 지도 API 키 미설정 · 달리기 기록은 계속됩니다") }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var failed by remember { mutableStateOf(false) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var followLocation by remember { mutableStateOf(true) }
    val mapView = remember { runCatching { MapView(context) }.getOrNull() }
    val drawn = remember(mapView) { DrawnRoute() }
    if (mapView == null || failed) {
        Box(modifier) { Text("지도를 표시할 수 없습니다 · 달리기 기록은 계속됩니다") }
        return
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var disposed = false
        val started = runCatching {
            mapView.onCreate(null)
            mapView.getMapAsync { map ->
                if (!disposed) {
                    map.setOnCameraMoveStartedListener { reason ->
                        if (!disposed && reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) followLocation = false
                    }
                    googleMap = map
                }
            }
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        }.isSuccess
        if (!started) failed = true
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }.onFailure { failed = true }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { googleMap?.setOnCameraMoveStartedListener(null) }
            runCatching {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
            googleMap = null
        }
    }

    LaunchedEffect(googleMap, points, currentFix, followLocation) {
        val map = googleMap ?: return@LaunchedEffect
        runCatching {
            val prefixMatches = points.size >= drawn.fixIds.size && drawn.fixIds.indices.all { index ->
                points[index].fixId == drawn.fixIds[index] && points[index].segment == drawn.segments[index]
            }
            if (!prefixMatches) drawn.reset(map)
            // Each new edge belongs to exactly one segment; none crosses a GPS gap or pause.
            for (index in drawn.fixIds.size until points.size) {
                val point = points[index]
                val previous = points.getOrNull(index - 1)
                if (previous != null && previous.segment == point.segment) {
                    map.addPolyline(PolylineOptions()
                        .add(LatLng(previous.latitude, previous.longitude), LatLng(point.latitude, point.longitude))
                        .color(ROUTE_COLOR).width(8f))
                }
                drawn.fixIds += point.fixId
                drawn.segments += point.segment
            }
            val position = currentFix?.takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
                ?.let { LatLng(it.latitude, it.longitude) }
            if (position != null) {
                val marker = drawn.marker
                if (marker == null) drawn.marker = map.addMarker(MarkerOptions().position(position).title("현재 위치"))
                else marker.position = position
                val radius = currentFix.accuracyM?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
                if (radius == null) { drawn.accuracy?.remove(); drawn.accuracy = null }
                else {
                    val circle = drawn.accuracy
                    if (circle == null) drawn.accuracy = map.addCircle(CircleOptions().center(position).radius(radius)
                        .strokeColor(ROUTE_COLOR).strokeWidth(2f).fillColor(ACCURACY_COLOR))
                    else { circle.center = position; circle.radius = radius }
                }
                if (followLocation) { map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 16f)); drawn.centered = true }
            } else {
                drawn.marker?.remove(); drawn.marker = null
                drawn.accuracy?.remove(); drawn.accuracy = null
            }
            if (!drawn.centered && points.isNotEmpty()) {
                val last = points.last()
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 16f))
                drawn.centered = true
            } else if (position == null && points.isEmpty() && !drawn.defaultCentered) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.5665, 126.9780), 15f))
                drawn.defaultCentered = true
            }
        }.onFailure { failed = true }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize(), update = {})
        if (!followLocation && currentFix != null) {
            Button(onClick = { followLocation = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Text("현재 위치")
            }
        }
    }
}
