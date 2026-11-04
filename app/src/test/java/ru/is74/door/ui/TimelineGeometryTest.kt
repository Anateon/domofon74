package ru.is74.door.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimelineGeometryTest {
    @Test fun `today stops at current time and an older day stops before midnight`() {
        assertEquals(1_600L, TimelineGeometry.lastSecond(1_000, 2_000, 1_600))
        assertEquals(1_999L, TimelineGeometry.lastSecond(1_000, 2_000, 3_000))
    }

    @Test fun `drag right goes earlier and stops at start`() {
        assertEquals(1_300.0, TimelineGeometry.drag(1_600.0, 100f, 300f, 900, 1_000, 2_000), .001)
        assertEquals(1_000.0, TimelineGeometry.drag(1_600.0, 500f, 300f, 900, 1_000, 2_000), .001)
    }

    @Test fun `drag and tap cannot pass the available end`() {
        assertEquals(1_700.0, TimelineGeometry.drag(1_600.0, -100f, 300f, 900, 1_000, 1_700), .001)
        assertEquals(1_700L, TimelineGeometry.tap(1_600, 300f, 300f, 900, 1_000, 1_700))
        assertEquals(1_600L, TimelineGeometry.tap(1_600, 150f, 300f, 900, 1_000, 1_700))
    }

    @Test fun `zero size does not divide by zero`() {
        assertEquals(1_600.0, TimelineGeometry.drag(1_600.0, 20f, 0f, 900, 1_000, 2_000), .001)
        assertEquals(1_600L, TimelineGeometry.tap(1_600, 20f, 0f, 900, 1_000, 2_000))
    }

    @Test fun `moment is interpreted in camera zone and preserves seconds`() {
        val date = LocalDate.of(2026, 11, 4)
        val expected = date.atTime(19, 8, 37).atZone(ZoneId.of("Asia/Yekaterinburg")).toEpochSecond()
        assertEquals(expected, cameraMomentEpoch(date, "19", "08", "37", ZoneId.of("Asia/Yekaterinburg")))
    }

    @Test fun `invalid hours seconds and DST gap are rejected`() {
        val date = LocalDate.of(2026, 11, 4)
        val zone = ZoneId.of("Asia/Yekaterinburg")
        assertNull(cameraMomentEpoch(date, "24", "00", "00", zone))
        assertNull(cameraMomentEpoch(date, "12", "00", "60", zone))
        assertNull(cameraMomentEpoch(date, "", "00", "00", zone))
        assertNull(cameraMomentEpoch(LocalDate.of(2026, 3, 29), "02", "30", "00", ZoneId.of("Europe/Berlin")))
    }
}
