package com.robo.phonegateway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.robo.phonegateway.registry.RouteTrack
import com.robo.phonegateway.registry.Units
import kotlin.math.cos
import kotlin.math.max

/**
 * A route's shape coloured by speed, drawn straight from its points: no map tiles,
 * so a list of them costs nothing beyond the (cached) tracks.
 */
@Composable
fun RouteThumbnail(track: RouteTrack, units: Units, modifier: Modifier = Modifier) {
    val pts = track.points
    if (pts.size < 2) return
    val k = cos(Math.toRadians(pts.sumOf { it.lat } / pts.size))
    val minX = pts.minOf { it.lon * k }
    val maxX = pts.maxOf { it.lon * k }
    val minY = pts.minOf { it.lat }
    val maxY = pts.maxOf { it.lat }
    Canvas(modifier) {
        val pad = size.minDimension * 0.1f
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val scale = ((size.minDimension - 2 * pad) / span).toFloat()
        val offX = (size.width - ((maxX - minX) * scale).toFloat()) / 2
        val offY = (size.height - ((maxY - minY) * scale).toFloat()) / 2
        fun at(p: RouteTrack.Point) = Offset(
            offX + ((p.lon * k - minX) * scale).toFloat(),
            size.height - (offY + ((p.lat - minY) * scale).toFloat()),
        )
        val stroke = size.minDimension * 0.045f
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (b.tS - a.tS > RouteTrack.MAX_GAP_S) continue
            val band = RouteTrack.ColorBy.SPEED.band(b, units)
            val color = band?.let { Color(android.graphics.Color.parseColor(it.color)) } ?: Color(0xFF6E7681)
            drawLine(color, at(a), at(b), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
