package ru.is74.door.ui

import android.app.DatePickerDialog
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

data class TimelineMarker(val epochSeconds: Long, val kind: String, val label: String)
data class TimelineRange(val start: Long, val end: Long)

internal object TimelineGeometry {
    fun lastSecond(dayStart: Long, dayEnd: Long, now: Long): Long = maxOf(dayStart, minOf(dayEnd - 1, now))

    fun drag(center: Double, distancePx: Float, widthPx: Float, span: Long, start: Long, end: Long): Double {
        if (widthPx <= 0f) return center.coerceIn(start.toDouble(), end.toDouble())
        return (center - distancePx / widthPx * span).coerceIn(start.toDouble(), end.toDouble())
    }

    fun tap(center: Long, offsetPx: Float, widthPx: Float, span: Long, start: Long, end: Long): Long {
        if (widthPx <= 0f) return center.coerceIn(start, end)
        return (center + ((offsetPx / widthPx - .5f) * span).roundToLong()).coerceIn(start, end)
    }
}

internal fun cameraMomentEpoch(date: LocalDate, hour: String, minute: String, second: String, zoneId: ZoneId): Long? =
    runCatching {
        val local = date.atTime(LocalTime.of(hour.toInt(), minute.toInt(), second.toInt()))
        val zoned = local.atZone(zoneId)
        require(zoned.toLocalDateTime() == local)
        zoned.toEpochSecond()
    }.getOrNull()

private val TimelineAccent = Color(0xFF6AE3DA)
private val TimelineInk = Color(0xFFEEF5F8)
private val TimelineMuted = Color(0xFF8F9EAE)
private val TimelineBackground = Color(0xFF141E2B)
private val TimelineTrack = Color(0xFF2B3746)
private val TimelineEvent = Color(0xFFFFC078)
private val Ru = Locale.forLanguageTag("ru-RU")
private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Ru)
private val TickFormatter = DateTimeFormatter.ofPattern("HH:mm", Ru)
private val DateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Ru)

/** Range ends are exclusive; no backend request is emitted during a drag. */
@Composable
fun CameraTimeline(
    selectedEpochSeconds: Long,
    dayStartEpochSeconds: Long,
    dayEndEpochSeconds: Long,
    events: List<TimelineMarker>,
    ranges: List<TimelineRange>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.of("Asia/Yekaterinburg"),
) {
    val maxSecond = TimelineGeometry.lastSecond(dayStartEpochSeconds, dayEndEpochSeconds, Instant.now().epochSecond)
    val boundedSelected = selectedEpochSeconds.coerceIn(dayStartEpochSeconds, maxSecond)
    var scaleIndex by rememberSaveable { mutableIntStateOf(1) }
    val span = listOf(900L, 3_600L, 21_600L)[scaleIndex]
    var dragging by remember { mutableStateOf(false) }
    var preview by remember { mutableLongStateOf(boundedSelected) }
    var dragCenter by remember { mutableDoubleStateOf(boundedSelected.toDouble()) }
    val currentSelected by rememberUpdatedState(boundedSelected)
    val currentMaxSecond by rememberUpdatedState(maxSecond)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentEvents by rememberUpdatedState(events)
    val center = if (dragging) preview else boundedSelected
    val time = Instant.ofEpochSecond(center).atZone(zoneId)
    val density = LocalDensity.current
    val tickPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TimelineMuted.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 11.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    LaunchedEffect(boundedSelected, dragging) {
        if (!dragging) preview = boundedSelected
    }

    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(TimelineBackground).padding(horizontal=16.dp,vertical=8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Column {
                Text(
                    time.format(ClockFormatter),
                    color = TimelineInk,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier,
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(if (dragging) "Отпустите для перехода" else "Листайте шкалу пальцем", color = TimelineMuted, fontSize = 10.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("15 мин", "1 час", "6 часов").forEachIndexed { index, label ->
                FilterChip(
                    selected = index == scaleIndex,
                    onClick = { scaleIndex = index },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TimelineAccent.copy(alpha = .15f),
                        selectedLabelColor = TimelineAccent,
                        labelColor = TimelineMuted,
                    ),
                )
            }
        }

        Canvas(
            Modifier.fillMaxWidth().height(100.dp)
                .semantics {
                    contentDescription = "Временная шкала. ${time.format(DateFormatter)}, ${time.format(ClockFormatter)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        (center - dayStartEpochSeconds).toFloat(),
                        0f..maxOf(1L, maxSecond - dayStartEpochSeconds).toFloat(),
                    )
                    setProgress { relativeSeconds ->
                        currentSeek((dayStartEpochSeconds + relativeSeconds.roundToLong()).coerceIn(dayStartEpochSeconds, maxSecond))
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction("На минуту назад") {
                            currentSeek((center - 60).coerceIn(dayStartEpochSeconds, maxSecond)); true
                        },
                        CustomAccessibilityAction("На минуту вперёд") {
                            currentSeek((center + 60).coerceIn(dayStartEpochSeconds, maxSecond)); true
                        },
                    )
                }
                .pointerInput(span, dayStartEpochSeconds) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragCenter = currentSelected.toDouble()
                            preview = currentSelected
                            dragging = true
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragCenter = TimelineGeometry.drag(dragCenter, amount, size.width.toFloat(), span, dayStartEpochSeconds, currentMaxSecond)
                            preview = dragCenter.roundToLong()
                        },
                        onDragEnd = { currentSeek(preview); dragging = false },
                        onDragCancel = { dragging = false; preview = currentSelected },
                    )
                }
                .pointerInput(span, dayStartEpochSeconds) {
                    detectTapGestures { point ->
                        val target = TimelineGeometry.tap(currentSelected, point.x, size.width.toFloat(), span, dayStartEpochSeconds, currentMaxSecond)
                        val tapTolerance = span * 18.dp.toPx() / size.width
                        val marker = currentEvents.minByOrNull { abs(it.epochSeconds - target) }
                            ?.takeIf { abs(it.epochSeconds - target) <= tapTolerance }
                        currentSeek((marker?.epochSeconds ?: target).coerceIn(dayStartEpochSeconds, currentMaxSecond))
                    }
                },
        ) {
            val left = center - span / 2.0
            val right = center + span / 2.0
            fun x(seconds: Double): Float = ((seconds - left) / span * size.width).toFloat()
            val trackTop = 49.dp.toPx()
            val trackHeight = 25.dp.toPx()
            val trackBottom = trackTop + trackHeight
            val dayLeft = x(dayStartEpochSeconds.toDouble()).coerceIn(0f, size.width)
            val dayRight = x((maxSecond + 1).toDouble()).coerceIn(0f, size.width)

            // The muted strip is the day; cyan segments are confirmed archive intervals.
            drawRoundRect(TimelineTrack, Offset(dayLeft, trackTop), Size(maxOf(0f, dayRight - dayLeft), trackHeight), CornerRadius(6.dp.toPx()))
            ranges.forEach { range ->
                val start = maxOf(range.start.toDouble(), left, dayStartEpochSeconds.toDouble())
                val end = minOf(range.end.toDouble(), right, (maxSecond + 1).toDouble())
                if (end > start) {
                    drawRoundRect(
                        TimelineAccent.copy(alpha = .28f),
                        Offset(x(start), trackTop), Size(x(end) - x(start), trackHeight), CornerRadius(3.dp.toPx()),
                    )
                    drawLine(TimelineAccent.copy(alpha = .75f), Offset(x(start), trackTop + 1.dp.toPx()), Offset(x(end), trackTop + 1.dp.toPx()), 2.dp.toPx())
                }
            }

            val minorStep = listOf(60L, 300L, 900L)[scaleIndex]
            val majorStep = listOf(300L, 900L, 3_600L)[scaleIndex]
            val firstTick = floor(left / minorStep).toLong() * minorStep
            val count = ceil(span.toDouble() / minorStep).toInt() + 2
            repeat(count) { index ->
                val tick = firstTick + index * minorStep
                val tickX = x(tick.toDouble())
                if (tickX in 0f..size.width && tick in dayStartEpochSeconds..dayEndEpochSeconds) {
                    val major = tick % majorStep == 0L
                    drawLine(
                        TimelineMuted.copy(alpha = if (major) .7f else .35f),
                        Offset(tickX, if (major) 29.dp.toPx() else 35.dp.toPx()),
                        Offset(tickX, 42.dp.toPx()), 1.dp.toPx(),
                    )
                    if (major) drawContext.canvas.nativeCanvas.drawText(
                        Instant.ofEpochSecond(tick).atZone(zoneId).format(TickFormatter), tickX, 18.dp.toPx(), tickPaint,
                    )
                }
            }

            events.asSequence().filter { it.epochSeconds >= left && it.epochSeconds <= right && it.epochSeconds in dayStartEpochSeconds..maxSecond }
                .forEach { event ->
                    val markerX = x(event.epochSeconds.toDouble())
                    val color = markerColor(event.kind)
                    drawLine(color.copy(alpha = .8f), Offset(markerX, trackTop + 4.dp.toPx()), Offset(markerX, trackBottom - 4.dp.toPx()), 2.dp.toPx())
                    drawCircle(TimelineBackground, 5.dp.toPx(), Offset(markerX, trackBottom + 10.dp.toPx()))
                    drawCircle(color, 3.dp.toPx(), Offset(markerX, trackBottom + 10.dp.toPx()))
                }

            val centerX = size.width / 2
            drawLine(TimelineBackground, Offset(centerX, 24.dp.toPx()), Offset(centerX, 96.dp.toPx()), 6.dp.toPx())
            drawLine(TimelineAccent, Offset(centerX, 24.dp.toPx()), Offset(centerX, 96.dp.toPx()), 2.dp.toPx())
            drawPath(Path().apply {
                moveTo(centerX - 5.dp.toPx(), 20.dp.toPx())
                lineTo(centerX + 5.dp.toPx(), 20.dp.toPx())
                lineTo(centerX, 26.dp.toPx())
                close()
            }, TimelineAccent)
            drawCircle(TimelineBackground, 4.dp.toPx(), Offset(centerX, 96.dp.toPx()))
            drawCircle(TimelineAccent, 4.dp.toPx(), Offset(centerX, 96.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TimelineLegend(TimelineAccent, "Запись")
            TimelineLegend(TimelineEvent, "Звонок")
            TimelineLegend(Color(0xFFB8A3FF), "Открытие")
        }
    }
}

@Composable
private fun TimelineLegend(color: Color, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = TimelineMuted, fontSize = 10.sp)
    }
}

private fun markerColor(kind: String): Color = when {
    kind.contains("call", ignoreCase = true) || kind.contains("звон", ignoreCase = true) -> TimelineEvent
    kind.contains("open", ignoreCase = true) || kind.contains("key", ignoreCase = true) -> Color(0xFFB8A3FF)
    else -> TimelineEvent
}

/** A camera-local date and time; both bounds are inclusive. */
@Composable
fun MomentPicker(
    initialEpochSeconds: Long,
    minEpochSeconds: Long,
    maxEpochSeconds: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    zoneId: ZoneId = ZoneId.of("Asia/Yekaterinburg"),
) {
    val upperBound = maxOf(minEpochSeconds, maxEpochSeconds)
    val initial = remember(initialEpochSeconds, minEpochSeconds, upperBound, zoneId) {
        Instant.ofEpochSecond(initialEpochSeconds.coerceIn(minEpochSeconds, upperBound)).atZone(zoneId)
    }
    // Keep the user's edits while the live camera clock continues to advance.
    var dateText by rememberSaveable { mutableStateOf(initial.toLocalDate().toString()) }
    var hour by rememberSaveable { mutableStateOf("%02d".format(initial.hour)) }
    var minute by rememberSaveable { mutableStateOf("%02d".format(initial.minute)) }
    var second by rememberSaveable { mutableStateOf("%02d".format(initial.second)) }
    val date = LocalDate.parse(dateText)
    val minDate = Instant.ofEpochSecond(minEpochSeconds).atZone(zoneId).toLocalDate()
    val maxDate = Instant.ofEpochSecond(upperBound).atZone(zoneId).toLocalDate()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val candidate = cameraMomentEpoch(date, hour, minute, second, zoneId)
    val valid = candidate != null && candidate in minEpochSeconds..upperBound
    val cameraZoneLabel = if (zoneId.id == "Asia/Yekaterinburg") "Время камеры · UTC+5" else "${zoneId.id} · UTC${initial.offset.id}"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TimelineBackground,
        titleContentColor = TimelineInk,
        textContentColor = TimelineMuted,
        title = { Text("Перейти к моменту", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Выберите дату и точное время записи.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        focus.clearFocus()
                        DatePickerDialog(context, { _, year, month, day ->
                            dateText = LocalDate.of(year, month + 1, day).toString()
                        }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                            datePicker.minDate = minDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            datePicker.maxDate = maxDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.show()
                    },
                ) { Text(date.format(DateFormatter), color = TimelineInk) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeNumberField(hour, { hour = it }, "Часы", 23, Modifier.weight(1f))
                    TimeNumberField(minute, { minute = it }, "Минуты", 59, Modifier.weight(1f))
                    TimeNumberField(second, { second = it }, "Секунды", 59, Modifier.weight(1f), last = true)
                }
                Text(cameraZoneLabel, color = TimelineAccent, fontSize = 12.sp)
                if (!valid) {
                    val error = if (candidate == null) "Введите время в формате 00–23 : 00–59 : 00–59."
                    else "Этот момент вне доступного периода. Выберите другое время."
                    Text(error, color = Color(0xFFFFB4AB), fontSize = 12.sp)
                }
                Text(
                    "Период: ${Instant.ofEpochSecond(minEpochSeconds).atZone(zoneId).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Ru))} — " +
                        Instant.ofEpochSecond(upperBound).atZone(zoneId).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Ru)),
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { candidate?.let(onConfirm) }) { Text("Показать запись") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun TimeNumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    maximum: Int,
    modifier: Modifier,
    last: Boolean = false,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 2 && it.all { char -> char in '0'..'9' }) onChange(it) },
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center, color = TimelineInk),
        isError = value.toIntOrNull()?.let { it !in 0..maximum } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
    )
}
