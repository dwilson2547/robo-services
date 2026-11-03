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
import com.robo.phonegateway.registry.RouteTrack
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * OpenStreetMap raster tiles: free and keyless, fine for light personal use under
 * the OSM tile usage policy (MapLibre identifies itself in its user agent, and the
 * attribution is shown). A self-hosted tile server can replace the URL if use grows.
 */
private val OSM_STYLE = """
{"version": 8,
 "sources": {"osm": {"type": "raster", "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
   "tileSize": 256, "maxzoom": 19, "attribution": "© OpenStreetMap contributors"}},
 "layers": [{"id": "osm", "type": "raster", "source": "osm"}]}
""".trimIndent()

/** A session's route on a map, coloured by speed band, with start and end dots. */
@Composable
fun RouteMap(track: RouteTrack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(track) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(Bundle())
            showRoute(this, track)
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // catch up with the lifecycle the screen is already in
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Style, route layers and camera, set once per map. */
private fun showRoute(mapView: MapView, track: RouteTrack) {
    mapView.getMapAsync { map ->
        map.setStyle(Style.Builder().fromJson(OSM_STYLE)) { style ->
            style.addSource(GeoJsonSource("route", track.geoJson()))
            style.addLayer(
                LineLayer("route-line", "route").withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("route"))),
            )
            style.addLayer(
                CircleLayer("route-ends", "route").withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2f),
                ).withFilter(Expression.neq(Expression.get("kind"), Expression.literal("route"))),
            )
            track.bounds()?.let { (sw, ne) ->
                val bounds = LatLngBounds.Builder()
                    .include(LatLng(sw.first, sw.second))
                    .include(LatLng(ne.first, ne.second))
                    .build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
            }
        }
    }
}
