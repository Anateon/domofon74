package ru.is74.door.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ExportRangeDialogTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Yekaterinburg")
    private val date = LocalDate.of(2026, 10, 31)

    @Test
    fun rangeCanCrossMidnightAndIsNotResetByParentClock() {
        val initial = epoch(23, 59, 30)
        var exported: Pair<Long, Long>? = null
        val clock = showDialog(initial) { start, end -> exported = start to end }

        compose.runOnIdle { clock.longValue += 120 }
        compose.onNodeWithText("Сохранить MP4").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(initial to initial + 60, exported) }
    }

    @Test
    fun endEqualToStartDisablesExportAndQuickDurationRestoresValidRange() {
        val initial = epoch(12, 30, 45)
        var exported: Pair<Long, Long>? = null
        showDialog(initial) { start, end -> exported = start to end }

        compose.onNodeWithText("Конец").performClick()
        compose.onNode(hasSetTextAction() and hasText("Минуты")).performTextReplacement("30")
        compose.onNode(hasSetTextAction() and hasText("Секунды")).performTextReplacement("45")
        compose.onNode(hasSetTextAction() and hasText("Секунды")).performImeAction()
        compose.onNodeWithText("Показать запись").performClick()

        compose.onNodeWithText("Конец должен быть позже начала.").assertExists()
        compose.onNodeWithText("Сохранить MP4").assertIsNotEnabled()
        compose.runOnIdle { assertNull(exported) }

        compose.onNodeWithText("5 мин").performClick()
        compose.onNodeWithText("Сохранить MP4").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(initial to initial + 300, exported) }
    }

    private fun showDialog(initial: Long, onExport: (Long, Long) -> Unit): MutableLongState {
        val clock = mutableLongStateOf(initial)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ExportRangeDialog(
                    initialEpochSeconds = clock.longValue,
                    minEpochSeconds = date.minusDays(1).atStartOfDay(zone).toEpochSecond(),
                    maxEpochSeconds = date.plusDays(2).atStartOfDay(zone).toEpochSecond() - 1,
                    onDismiss = {},
                    onExport = onExport,
                )
            }
        }
        return clock
    }

    private fun epoch(hour: Int, minute: Int, second: Int): Long = date.atTime(hour, minute, second).atZone(zone).toEpochSecond()
}
