package com.robo.phonegateway

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** A track's outline (GeoJSON Feature or geometry) and its start line, if set, on the OSM map. */
@Composable
fun TrackMap(geometry: JSONObject, outline: List<List<Pair<Double, Double>>>, startLine: JSONObject?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(geometry) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(Bundle())
            getMapAsync { map ->
                map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                    val feature = if (geometry.has("geometry")) geometry else JSONObject().put("type", "Feature").put("geometry", geometry)
                    style.addSource(GeoJsonSource("track", JSONObject().put("type", "FeatureCollection")
                        .put("features", JSONArray().put(feature)).toString()))
                    style.addLayer(LineLayer("track-line", "track").withProperties(
                        PropertyFactory.lineColor("#4FC3F7"), PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"),
                    ))
                    if (startLine != null) {
                        style.addSource(GeoJsonSource("start", startLine.toString()))
                        style.addLayer(CircleLayer("start-dot", "start").withProperties(
                            PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleStrokeColor("#0D1117"), PropertyFactory.circleStrokeWidth(3f),
                        ))
                    }
                    val pts = outline.flatten()
                    if (pts.isNotEmpty()) {
                        val b = LatLngBounds.Builder()
                        pts.forEach { b.include(LatLng(it.first, it.second)) }
                        if (pts.size == 1) b.include(LatLng(pts[0].first + 0.001, pts[0].second + 0.001))
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 60))
                    }
                }
            }
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}
