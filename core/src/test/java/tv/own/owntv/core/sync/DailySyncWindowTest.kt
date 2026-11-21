package tv.own.owntv.core.sync

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.DailySync

/**
 * The daily sync window: per-source 24h timers scatter four refreshes across a day, so the app reads as if
 * it never stops updating. These pin the properties that make it one event a day — every eligible source is
 * judged against the SAME instant, and nothing fires before the window has opened.
 */
class DailySyncWindowTest {

    private val zone = ZoneId.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `window start is today at the chosen hour`() {
        assertEquals(at(2026, 11, 21, 3), DailySyncWindow.startMs(at(2026, 11, 21, 9, 30), 3, zone))
    }

    @Test
    fun `before the hour the window has not opened`() {
        assertNull(DailySyncWindow.openStartMs(at(2026, 11, 21, 1, 15), DailySync(true, 3), zone))
    }

    @Test
    fun `after the hour the window is open at the day start`() {
        assertEquals(
            at(2026, 11, 21, 3),
            DailySyncWindow.openStartMs(at(2026, 11, 21, 3, 1), DailySync(true, 3), zone),
        )
    }

    @Test
    fun `off never opens`() {
        assertNull(DailySyncWindow.openStartMs(at(2026, 11, 21, 9), DailySync.OFF, zone))
    }

    @Test
    fun `every source that has not synced since the window is due in the same pass`() {
        val open = DailySyncWindow.openStartMs(at(2026, 11, 21, 3, 30), DailySync(true, 3), zone)
        val eternalLast = at(2026, 11, 20, 22, 40)
        val protoLast = at(2026, 11, 20, 18, 5)
        val guideLast = at(2026, 11, 20, 19, 20)
        assertTrue(DailySyncWindow.isDue(eternalLast, open))
        assertTrue(DailySyncWindow.isDue(protoLast, open))
        assertTrue(DailySyncWindow.isDue(guideLast, open))
    }

    @Test
    fun `a source that already synced since the window waits for tomorrow`() {
        val open = DailySyncWindow.openStartMs(at(2026, 11, 21, 6), DailySync(true, 3), zone)
        assertFalse(DailySyncWindow.isDue(at(2026, 11, 21, 3, 4), open))
    }

    @Test
    fun `never synced counts as due`() {
        val open = DailySyncWindow.openStartMs(at(2026, 11, 21, 6), DailySync(true, 3), zone)
        assertTrue(DailySyncWindow.isDue(null, open))
    }

    @Test
    fun `off is the default and parses to no window`() {
        assertEquals(DailySync.OFF, DailySync.parse(null))
        assertEquals(DailySync.OFF, DailySync.parse(""))
        assertEquals(DailySync.OFF, DailySync.parse("nonsense"))
        assertFalse(DailySync.parse("OFF").enabled)
    }

    @Test
    fun `round trips the hour and clamps it`() {
        assertEquals(DailySync(true, 3), DailySync.parse(DailySync(true, 3).serialize()))
        assertEquals(DailySync(true, 23), DailySync.parse(DailySync(true, 99).serialize()))
        assertEquals(DailySync(true, 0), DailySync.parse("ON:0"))
        assertEquals(DailySync.DEFAULT_HOUR, DailySync.parse("ON").hour)
    }
}
