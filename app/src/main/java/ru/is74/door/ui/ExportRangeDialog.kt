package ru.is74.door.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ExportSurface = Color(0xFF141E2B)
private val ExportAccent = Color(0xFF6AE3DA)
private val ExportInk = Color(0xFFEEF5F8)
private val ExportMuted = Color(0xFF8F9EAE)
private val ExportZone = ZoneId.of("Asia/Yekaterinburg")
private val ExportDateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))
private val ExportTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

/** Chooses a camera-local range; the caller owns the document destination and actual export. */
@Composable
fun ExportRangeDialog(
    initialEpochSeconds: Long,
    minEpochSeconds: Long,
    maxEpochSeconds: Long,
    onDismiss: () -> Unit,
    onExport: (start: Long, end: Long) -> Unit,
) {
    val upperBound = maxOf(minEpochSeconds, minOf(maxEpochSeconds, Instant.now().epochSecond))
    // The live player keeps ticking behind this dialog. Initialize once per opening.
    var start by rememberSaveable {
        val initial = initialEpochSeconds.coerceIn(minEpochSeconds, upperBound)
        mutableLongStateOf(if (initial == upperBound) maxOf(minEpochSeconds, initial - 60) else initial)
    }
    var end by rememberSaveable { mutableLongStateOf(minOf(start + 60, upperBound)) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val error = when {
        start >= end -> "Конец должен быть позже начала."
        start < minEpochSeconds || end > upperBound -> "Выберите диапазон в пределах доступного архива."
        else -> null
    }

    if (editing != null) {
        MomentPicker(
            initialEpochSeconds = if (editing == "start") start else end,
            minEpochSeconds = minEpochSeconds,
            maxEpochSeconds = upperBound,
            onDismiss = { editing = null },
            onConfirm = { selected ->
                if (editing == "start") start = selected else end = selected
                editing = null
            },
            zoneId = ExportZone,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExportSurface,
        titleContentColor = ExportInk,
        textContentColor = ExportMuted,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сохранить запись", fontWeight = FontWeight.SemiBold)
                Text("Выберите нужный отрезок", color = ExportMuted, fontSize = 13.sp, fontWeight = FontWeight.Normal)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExportMomentButton("Начало", start) { editing = "start" }
                ExportMomentButton("Конец", end) { editing = "end" }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60L to "1 мин", 300L to "5 мин", 900L to "15 мин").forEach { (seconds, label) ->
                        FilterChip(
                            selected = end - start == seconds,
                            onClick = { end = minOf(start + seconds, upperBound) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ExportAccent.copy(alpha = .15f),
                                selectedLabelColor = ExportAccent,
                                labelColor = ExportMuted,
                            ),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Длительность", style = MaterialTheme.typography.bodyMedium)
                    Text(exportDuration((end - start).coerceAtLeast(0)), color = ExportInk, fontWeight = FontWeight.Medium)
                }
                Text("Время камеры · UTC+5", color = ExportAccent, fontSize = 12.sp)
                if (error != null) Text(error, color = Color(0xFFFFB4AB), fontSize = 12.sp)
                Text(
                    "Сохраним целые видеофрагменты. Начало и конец могут отличаться на несколько секунд. Без перекодирования.",
                    color = ExportMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2837), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        },
        confirmButton = {
            Button(enabled = error == null, onClick = { if (error == null) onExport(start, end) }) {
                Text("Сохранить MP4")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ExportMomentButton(label: String, epochSeconds: Long, onClick: () -> Unit) {
    val time = Instant.ofEpochSecond(epochSeconds).atZone(ExportZone)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF354354)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = ExportAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(time.format(ExportTimeFormat), color = ExportInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(time.format(ExportDateFormat), color = ExportMuted, fontSize = 12.sp)
            }
            Text("›", color = ExportMuted, fontSize = 30.sp)
        }
    }
}

private fun exportDuration(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return buildList {
        if (days > 0) add("$days д")
        if (hours > 0) add("$hours ч")
        if (minutes > 0) add("$minutes мин")
        if (rest > 0 || isEmpty()) add("$rest с")
    }.joinToString(" ")
}
