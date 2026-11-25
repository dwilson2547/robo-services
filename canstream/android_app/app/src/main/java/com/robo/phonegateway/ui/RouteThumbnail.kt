package com.robo.phonegateway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.robo.phonegateway.registry.RouteTrack
import com.robo.phonegateway.registry.Units
import kotlin.math.cos
import kotlin.math.max

private const val MIN_SPAN_DEG = 0.003

/** One drawn segment of a thumbnail: normalised 0..1 endpoints (y up) and its colour. */
private class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: Color)

/** Everything a thumbnail needs that doesn't depend on its pixel size, computed once per track. */
private class ThumbShape(val segs: List<Seg>, val spanFrac: Float, val wFrac: Float, val hFrac: Float)

/** At most this many segments per thumbnail: 72 dp can't show more, and a long drive has thousands of points. */
private const val MAX_SEGMENTS = 300
private val NO_VALUE_COLOR = Color(0xFF6E7681)
private val bandColors = HashMap<String, Color>()

private fun shapeOf(track: RouteTrack, units: Units): ThumbShape? {
    val all = track.points
    if (all.size < 2) return null
    val step = (all.size / MAX_SEGMENTS).coerceAtLeast(1)
    val pts = if (step == 1) all else all.filterIndexed { i, _ -> i % step == 0 || i == all.lastIndex }
    val k = cos(Math.toRadians(pts.sumOf { it.lat } / pts.size))
    val minX = pts.minOf { it.lon * k }
    val maxX = pts.maxOf { it.lon * k }
    val minY = pts.minOf { it.lat }
    val maxY = pts.maxOf { it.lat }
    // at least ~300 m across: a parked session's GPS drift stays a small cluster, not a "route"
    val span = max(maxX - minX, maxY - minY).coerceAtLeast(MIN_SPAN_DEG)
    fun nx(p: RouteTrack.Point) = ((p.lon * k - minX) / span).toFloat()
    fun ny(p: RouteTrack.Point) = ((p.lat - minY) / span).toFloat()
    val segs = ArrayList<Seg>(pts.size)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        if (b.tS - a.tS > RouteTrack.MAX_GAP_S * step.coerceAtLeast(1)) continue
        val color = RouteTrack.ColorBy.SPEED.band(b, units)?.let { band ->
            bandColors.getOrPut(band.color) { Color(android.graphics.Color.parseColor(band.color)) }
        } ?: NO_VALUE_COLOR
        segs += Seg(nx(a), ny(a), nx(b), ny(b), color)
    }
    return ThumbShape(segs, 1f, ((maxX - minX) / span).toFloat(), ((maxY - minY) / span).toFloat())
}

/**
 * A route's shape coloured by speed, drawn straight from its points: no map tiles,
 * so a list of them costs nothing beyond the (cached) tracks. The shape is worked out
 * once per track (and thinned to what 72 dp can show), not on every frame of a scroll.
 */
@Composable
fun RouteThumbnail(track: RouteTrack, units: Units, modifier: Modifier = Modifier) {
    val shape = remember(track, units) { shapeOf(track, units) } ?: return
    Canvas(modifier) {
        val pad = size.minDimension * 0.1f
        val scale = size.minDimension - 2 * pad
        val offX = (size.width - shape.wFrac * scale) / 2
        val offY = (size.height - shape.hFrac * scale) / 2
        val stroke = size.minDimension * 0.045f
        for (s in shape.segs) {
            drawLine(
                s.color,
                Offset(offX + s.x0 * scale, size.height - (offY + s.y0 * scale)),
                Offset(offX + s.x1 * scale, size.height - (offY + s.y1 * scale)),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
        }
    }
}

/** A track's outline (lines of lat, lon) in one colour, fitted to the box. */
@Composable
fun OutlineThumbnail(lines: List<List<Pair<Double, Double>>>, color: Color, modifier: Modifier = Modifier) {
    val pts = lines.flatten()
    if (pts.size < 2) return
    val k = cos(Math.toRadians(pts.sumOf { it.first } / pts.size))
    val minX = pts.minOf { it.second * k }
    val maxX = pts.maxOf { it.second * k }
    val minY = pts.minOf { it.first }
    val maxY = pts.maxOf { it.first }
    Canvas(modifier) {
        val pad = size.minDimension * 0.1f
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val scale = ((size.minDimension - 2 * pad) / span).toFloat()
        val offX = (size.width - ((maxX - minX) * scale).toFloat()) / 2
        val offY = (size.height - ((maxY - minY) * scale).toFloat()) / 2
        fun at(p: Pair<Double, Double>) = Offset(
            offX + ((p.second * k - minX) * scale).toFloat(),
            size.height - (offY + ((p.first - minY) * scale).toFloat()),
        )
        val stroke = size.minDimension * 0.045f
        lines.forEach { line -> line.zipWithNext { a, b -> drawLine(color, at(a), at(b), strokeWidth = stroke, cap = StrokeCap.Round) } }
    }
}
