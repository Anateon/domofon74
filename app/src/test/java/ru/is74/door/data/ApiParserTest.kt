package ru.is74.door.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ApiParserTest {
    @Test fun `backend query timestamps never contain rejected fractional seconds`() {
        val start = Instant.parse("2026-01-02T10:00:00.123456Z")
        val end = Instant.parse("2026-01-02T11:00:00.987654Z")
        assertEquals("2026-01-02T10:00:00Z", apiTimestamp(start))
        assertEquals("2026-01-02T11:00:00Z", apiTimestamp(end))
        val camera = Camera("1", "test", "Test", "", "https://cdn.cams.is74.ru/hls/playlists/multivariant.m3u8?uuid=test&token=synthetic", null, null, true, true, "")
        val url = archivePlaybackUrl(camera, start, end)
        assertTrue(url.contains("start-date=2026-01-02T10%3A00%3A00Z"))
        assertTrue(url.contains("stop-date=2026-01-02T11%3A00%3A00Z"))
        assertFalse(url.contains(".123456"))
        assertFalse(url.contains(".987654"))
    }
    @Test fun `camera local date and HLS offset resolve to same UTC instant`() {
        val expected = Instant.parse("2026-01-02T10:30:00Z")
        assertEquals(expected, parseCameraInstant("02.01.2026 15:30"))
        assertEquals(expected, parseCameraInstant("2026-01-02T15:30:00+0500"))
        assertEquals(expected, parseCameraInstant("2026-01-02T10:30:00Z"))
        assertNull(parseCameraInstant("not a date"))
    }
    @Test fun `archive URL preserves signed fields but replaces stale window and live flag`() {
        val c = Camera("1", "test", "Test", "", "https://cdn.cams.is74.ru/hls/playlists/multivariant.m3u8?uuid=test&token=synthetic%2Bsignature&start-date=old&stop-date=old&realtime=true", null, null, true, true, "")
        val result = archivePlaybackUrl(c, Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T11:00:00Z"))
        assertTrue(result.contains("token=synthetic%2Bsignature"))
        assertTrue(result.contains("start-date=2026-01-02T10%3A00%3A00Z"))
        assertFalse(result.contains("realtime"))
        assertFalse(result.contains("old"))
        assertEquals(1, result.split("start-date=").size - 1)
    }
    @Test(expected = IllegalArgumentException::class) fun `archive rejects credential forwarding to a foreign host`() {
        archivePlaybackUrl(Camera("1", "test", "Test", "", "https://example.org/video", null, null, true, true, ""), Instant.EPOCH, Instant.EPOCH.plusSeconds(1))
    }
    @Test fun `recording ranges are clipped and merged without filling gaps`() {
        val input = JSONArray("""[
          {"START_DATE":"2026-01-02T10:05:00Z","STOP_DATE":"2026-01-02T10:09:00Z"},
          {"START_DATE":"2026-01-02T09:59:00Z","STOP_DATE":"2026-01-02T10:02:00Z"},
          {"START_DATE":"2026-01-02T10:01:00Z","STOP_DATE":"2026-01-02T10:03:00Z"},
          {"START_DATE":"invalid","STOP_DATE":"2026-01-02T10:03:00Z"}
        ]""")
        val actual = ApiParser.ranges(input, Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T10:07:00Z"))
        assertEquals(listOf(
            RecordingRange(Instant.parse("2026-01-02T10:00:00Z"), Instant.parse("2026-01-02T10:03:00Z")),
            RecordingRange(Instant.parse("2026-01-02T10:05:00Z"), Instant.parse("2026-01-02T10:07:00Z"))
        ), actual)
    }
    @Test fun `multiple account choices and unknown events survive parsing`() {
        val confirmed = ApiParser.confirmed(JSONObject("""{"authId":"synthetic","addresses":[{"USER_ID":"1","ADDRESS":"Договор А"},{"USER_ID":"2","ADDRESS":"Договор Б"}]}"""))
        assertEquals(2, confirmed.accounts.size)
        val event = ApiParser.event(JSONObject("""{"create_date":"2026-01-02T10:00:00Z","type":"NEW_EVENT","params":{"mac":"00:00:00:00:00:01"},"image_link":""}"""))!!
        assertEquals("Событие домофона", event.title)
        assertEquals("NEW_EVENT", event.type)
        assertNull(event.imageUrl)
    }
    @Test fun `null JSON fields do not become literal null URLs`() {
        val c = ApiParser.camera(JSONObject("""{"ID":1,"UUID":"test","NAME":"Тест","MEDIA":{"HLS":{"LIVE":{"MAIN":"https://cdn.cams.is74.ru/test.m3u8","LOW_LATENCY":null},"ARCHIVE":null}},"ACCESS":{"LIVE":{"STATUS":true},"ARCHIVE":{"STATUS":false,"REASON":"Нет доступа"}}}"""))
        assertEquals("https://cdn.cams.is74.ru/test.m3u8", c.liveUrl)
        assertEquals("", c.archiveUrl)
        assertFalse(c.canArchive)
        assertEquals("Нет доступа", c.archiveReason)
    }
    @Test fun `Russian phone input accepts common formatting`() {
        assertEquals("9000000000", DoorRepository.normalizePhone("+7 (900) 000-00-00"))
        assertEquals("9000000000", DoorRepository.normalizePhone("8 900 000 00 00"))
    }
}
