package com.notiregx.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.notiregx.app.R
import com.notiregx.app.model.ChartRange
import com.notiregx.app.model.TimeSeries
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** One line on a chart. */
class ChartSeries(val name: String, val color: Color, val values: IntArray)

/**
 * Smooth line chart, stock-chart style: one or more series over the same points. Touch or drag
 * across it to see the point's label and every series' value in a tooltip. A single series gets
 * a soft fill under its line.
 */
@Composable
fun SmoothLineChart(
    series: List<ChartSeries>,
    pointLabel: (Int) -> String,
    axisLabels: List<String> = emptyList(),
    height: Dp = 180.dp,
    modifier: Modifier = Modifier,
) {
    val n = series.maxOfOrNull { it.values.size } ?: 0
    if (n == 0) return
    val max = (series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0 } ?: 0).coerceAtLeast(1)
    var selected by remember(n, series.size) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursor = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = series.size == 1

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val widthPx = constraints.maxWidth.toFloat()
            fun indexAt(x: Float) = if (n == 1) 0 else (x / widthPx * (n - 1)).roundToInt().coerceIn(0, n - 1)

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .pointerInput(n) { detectTapGestures { selected = indexAt(it.x) } }
                    .pointerInput(n) {
                        detectHorizontalDragGestures(
                            onDragStart = { selected = indexAt(it.x) },
                            onHorizontalDrag = { change, _ -> change.consume(); selected = indexAt(change.position.x) },
                        )
                    }
            ) {
                val top = 8.dp.toPx()
                val bottom = size.height - 2.dp.toPx()
                fun x(i: Int) = if (n == 1) size.width / 2 else i * size.width / (n - 1)
                fun y(v: Int) = bottom - (v.toFloat() / max) * (bottom - top)

                // Faint guide lines at a third and two thirds.
                listOf(1f / 3, 2f / 3, 1f).forEach { f ->
                    val gy = bottom - f * (bottom - top)
                    drawLine(grid, Offset(0f, gy), Offset(size.width, gy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                }

                series.forEach { s ->
                    val pts = s.values.indices.map { Offset(x(it), y(s.values[it])) }
                    val line = smoothPath(pts, top, bottom)
                    if (fill) {
                        val area = Path().apply {
                            addPath(line)
                            lineTo(pts.last().x, bottom)
                            lineTo(pts.first().x, bottom)
                            close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0f)), top, bottom))
                    }
                    drawPath(line, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }

                selected?.let { i ->
                    drawLine(cursor, Offset(x(i), top), Offset(x(i), bottom), 1.dp.toPx())
                    series.forEach { s ->
                        val v = s.values.getOrNull(i) ?: return@forEach
                        drawCircle(Color.White, 5.dp.toPx(), Offset(x(i), y(v)))
                        drawCircle(s.color, 3.5.dp.toPx(), Offset(x(i), y(v)))
                    }
                }
            }

            selected?.let { i ->
                val density = LocalDensity.current
                val tooltipWidth = 168.dp
                val xDp = with(density) { (if (n == 1) widthPx / 2 else i * widthPx / (n - 1)).toDp() }
                val left = (xDp - tooltipWidth / 2).coerceIn(0.dp, maxWidth - tooltipWidth)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.width(tooltipWidth).offset(x = left),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(pointLabel(i), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        series.sortedByDescending { it.values.getOrNull(i) ?: 0 }.take(6).forEach { s ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(s.color, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(s.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${s.values.getOrNull(i) ?: 0}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        if (axisLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                axisLabels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * A smooth curve through [pts] (Catmull-Rom as cubic Béziers), with control points kept inside
 * the chart so the line never dips below zero or above the top.
 */
private fun smoothPath(pts: List<Offset>, top: Float, bottom: Float): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(i - 1, 0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[minOf(i + 2, pts.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, (p1.y + (p2.y - p0.y) / 6f).coerceIn(top, bottom))
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, (p2.y - (p3.y - p1.y) / 6f).coerceIn(top, bottom))
        cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
}

/** 30 days / 3 months / 1 year / All time. */
@Composable
fun ChartRangeChips(range: ChartRange, onChange: (ChartRange) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ChartRange.entries) { r ->
            FilterChip(selected = range == r, onClick = { onChange(r) }, label = { Text(stringResource(r.label)) })
        }
    }
}

private val ChartRange.label: Int
    get() = when (this) {
        ChartRange.DAYS_30 -> R.string.chart_range_30_days
        ChartRange.MONTHS_3 -> R.string.chart_range_3_months
        ChartRange.YEAR -> R.string.chart_range_year
        ChartRange.ALL -> R.string.chart_range_all
    }

/** Tooltip label for point [i]: the day, or "Week of …". */
@Composable
fun timeSeriesPointLabel(points: TimeSeries.Points): (Int) -> String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val weekOf = stringResource(R.string.chart_week_of)
    return { i ->
        val date = LocalDate.ofEpochDay(points.startDays[i].toLong()).format(format)
        if (points.weekly) weekOf.format(date) else date
    }
}

/** Three dates along the bottom: start, middle, end. */
@Composable
fun timeSeriesAxisLabels(points: TimeSeries.Points): List<String> {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMd") }
    val format = remember(pattern) { DateTimeFormatter.ofPattern(pattern, locale) }
    val days = points.startDays
    if (days.isEmpty()) return emptyList()
    return listOf(days.first(), days[days.size / 2], days.last()).map { LocalDate.ofEpochDay(it.toLong()).format(format) }
}
