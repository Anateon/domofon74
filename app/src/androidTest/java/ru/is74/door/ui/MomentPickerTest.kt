package ru.is74.door.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Isolated dialog tests: no app activity, saved session, player or network requests. */
@RunWith(AndroidJUnit4::class)
class MomentPickerTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Yekaterinburg")
    private val date = LocalDate.of(2026, 11, 4)

    @Test
    fun editsSurviveParentClockTicksAndConfirmExactCameraTime() {
        var confirmed: Long? = null
        val clock = showPicker(epoch(18, 45, 50)) { confirmed = it }

        field("Часы").performTextReplacement("12")
        tick(clock)
        assertField("Часы", "12")

        field("Минуты").performTextReplacement("08")
        tick(clock)
        assertField("Часы", "12")
        assertField("Минуты", "08")

        field("Секунды").performTextReplacement("37")
        tick(clock)
        assertField("Часы", "12")
        assertField("Минуты", "08")
        assertField("Секунды", "37")

        field("Секунды").performImeAction()
        compose.onNodeWithText("Показать запись").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(epoch(12, 8, 37), confirmed) }
    }

    @Test
    fun crossingMidnightInParentKeepsTheDateBeingEdited() {
        var confirmed: Long? = null
        val clock = showPicker(epoch(23, 59, 59)) { confirmed = it }

        field("Часы").performTextReplacement("12")
        field("Минуты").performTextReplacement("30")
        field("Секунды").performTextReplacement("05")
        tick(clock) // Playback moves to 5 November; the open picker still edits 4 November.

        compose.onNodeWithText("4 ноября 2026").assertExists()
        assertField("Часы", "12")
        assertField("Минуты", "30")
        assertField("Секунды", "05")
        field("Секунды").performImeAction()
        compose.onNodeWithText("Показать запись").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(epoch(12, 30, 5), confirmed) }
    }

    @Test
    fun invalidHourStaysInvalidAcrossTicksAndDisablesConfirmation() {
        var confirmed: Long? = null
        val clock = showPicker(epoch(18, 45, 50)) { confirmed = it }

        field("Часы").performTextReplacement("24")
        tick(clock)
        assertField("Часы", "24")
        compose.onNodeWithText("Показать запись").assertIsNotEnabled()
        compose.runOnIdle { assertNull(confirmed) }

        field("Часы").performTextReplacement("12")
        tick(clock)
        assertField("Часы", "12")
        compose.onNodeWithText("Показать запись").assertIsEnabled()
    }

    private fun showPicker(initial: Long, onConfirm: (Long) -> Unit): MutableLongState {
        val clock = mutableLongStateOf(initial)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MomentPicker(
                    initialEpochSeconds = clock.longValue,
                    minEpochSeconds = date.minusDays(1).atStartOfDay(zone).toEpochSecond(),
                    maxEpochSeconds = date.plusDays(2).atStartOfDay(zone).toEpochSecond() - 1,
                    onDismiss = {},
                    onConfirm = onConfirm,
                    zoneId = zone,
                )
            }
        }
        return clock
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun assertField(label: String, expected: String) {
        field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
    }

    private fun tick(clock: MutableLongState) {
        // Inject the same parent state update as the player's timer, deterministically between edits.
        compose.runOnIdle { clock.longValue += 1 }
        compose.waitForIdle()
    }

    private fun epoch(hour: Int, minute: Int, second: Int): Long = date.atTime(hour, minute, second).atZone(zone).toEpochSecond()
}
