package ru.is74.door.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HlsExportTest {
    private val base = "https://cdn.cams.is74.ru/hls/playlists/test.m3u8?token=synthetic"
    @Test fun `parser resolves relative TS URIs and infers clock from program time`() {
        val result = parseExportPlaylist(base, """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:15.467,
            #EXT-X-PROGRAM-DATE-TIME:2026-01-02T15:00:00.1234567+0500
            ../media/first.ts?token=synthetic
            #EXTINF:13.5,
            /hls/media/second.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        assertTrue(result.ended)
        assertEquals(2, result.segments.size)
        assertEquals("https://cdn.cams.is74.ru/hls/media/first.ts?token=synthetic", result.segments[0].url)
        assertEquals(15_467_000L, result.segments[0].durationUs)
        assertEquals(Instant.parse("2026-01-02T10:00:00.1234567Z"), result.segments[0].start)
        assertEquals(Instant.parse("2026-01-02T10:00:15.5904567Z"), result.segments[1].start)
    }
    @Test fun `whole segments are selected deduplicated across signed URLs and gaps reported`() {
        val start = Instant.parse("2026-01-02T10:00:00Z")
        val segments = listOf(
            HlsSegment("https://cdn.cams.is74.ru/one.ts?token=old", 15_000_000, start.minusSeconds(5), false),
            HlsSegment("https://cdn.cams.is74.ru/one.ts?token=new", 15_000_000, start.minusSeconds(5), false),
            HlsSegment("https://cdn.cams.is74.ru/two.ts", 15_000_000, start.plusSeconds(20), false),
            HlsSegment("https://cdn.cams.is74.ru/after.ts", 15_000_000, start.plusSeconds(40), false),
        )
        val plan = makeExportPlan(segments, start, start.plusSeconds(30))
        assertEquals(2, plan.segments.size)
        assertEquals(start.minusSeconds(5), plan.effectiveStart)
        assertEquals(start.plusSeconds(35), plan.effectiveEnd)
        assertEquals(30_000_000L, plan.sourceDurationUs)
        assertEquals(10_000_000L, plan.gapDurationUs)
        assertTrue(plan.segments[1].discontinuity)
    }
    @Test fun `explicit discontinuity is retained`() {
        val parsed = parseExportPlaylist(base, """
            #EXTM3U
            #EXT-X-PROGRAM-DATE-TIME:2026-01-02T10:00:00Z
            #EXTINF:10,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-PROGRAM-DATE-TIME:2026-01-02T10:00:10Z
            #EXTINF:10,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        assertFalse(parsed.segments[0].discontinuity)
        assertTrue(parsed.segments[1].discontinuity)
    }
    @Test fun `master variant uses the permitted same-host relative URI`() {
        val parsed = parseExportPlaylist(base, """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            ts.m3u8?quality=main&token=synthetic
        """.trimIndent())
        assertTrue(parsed.segments.isEmpty())
        assertEquals("https://cdn.cams.is74.ru/hls/playlists/ts.m3u8?quality=main&token=synthetic", parsed.variants.single())
    }
    @Test fun `sensitive signatures never appear in model text`() {
        val segment = HlsSegment("https://cdn.cams.is74.ru/a.ts?token=private-example", 1_000_000, Instant.EPOCH, false)
        assertFalse(segment.toString().contains("private-example"))
        assertFalse(makeExportPlan(listOf(segment), Instant.EPOCH, Instant.EPOCH.plusSeconds(1)).toString().contains("private-example"))
    }
    @Test fun `encrypted byte-range init-map and foreign-host media are rejected`() {
        val unsupported = listOf(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"",
            "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"key\"",
            "#EXT-X-BYTERANGE:100@0",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXT-X-PART:DURATION=1,URI=\"part.ts\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"",
        )
        for (tag in unsupported) {
            assertThrows(ApiException::class.java) { parseExportPlaylist(base, "#EXTM3U\n$tag\n") }
        }
        for (url in listOf("https://example.org/a.ts", "http://cdn.cams.is74.ru/a.ts", "https://cdn.cams.is74.ru.evil.org/a.ts", "https://user@cdn.cams.is74.ru/a.ts", "https://cdn.cams.is74.ru:8443/a.ts")) {
            assertThrows(ApiException::class.java) { parseExportPlaylist(base, "#EXTM3U\n#EXTINF:10,\n$url\n#EXT-X-ENDLIST") }
        }
    }
    @Test fun `missing absolute archive timestamps are not fabricated`() {
        val parsed = parseExportPlaylist(base, "#EXTM3U\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST")
        assertNull(parsed.segments.single().start)
        assertThrows(ApiException::class.java) { makeExportPlan(parsed.segments, Instant.EPOCH, Instant.EPOCH.plusSeconds(20)) }
    }
    @Test fun `incomplete manifests and invalid durations fail instead of saving partial video`() {
        for (text in listOf("#EXTM3U\n#EXTINF:15,", "#EXTM3U\n#EXTINF:NaN,\na.ts", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000", "{\"error\":\"expired\"}")) {
            assertThrows(ApiException::class.java) { parseExportPlaylist(base, text) }
        }
    }
}
