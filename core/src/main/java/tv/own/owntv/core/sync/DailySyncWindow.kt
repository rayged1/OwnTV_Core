package tv.own.owntv.core.sync

import java.time.Instant
import java.time.ZoneId
import tv.own.owntv.core.settings.DailySync

/**
 * The arithmetic behind the daily sync window, kept out of [AutoRefresh] so it can be tested without the
 * whole DI graph behind it.
 *
 * The window exists because per-source 24-hour timers scatter refreshes across a day: two playlists and two
 * guides each come due 24h after their own last success, so the app shows four unrelated sync events and
 * reads as if it never stops updating. With a window, every interval source is judged against one instant
 * — the moment the window opened — and the ones that have not succeeded since all answer "due" in the same
 * pass, which is what makes it one event a day.
 */
object DailySyncWindow {

    /**
     * The start of [hour]:00 in [zone], on the day [nowMs] falls in — in epoch millis.
     *
     * Local rather than UTC because the window is a wall-clock promise ("refresh at 3 AM"), and recomputed
     * every pass, so a device that changes timezone or crosses a DST boundary lands on its own clock.
     */
    fun startMs(nowMs: Long, hour: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone)
            .withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
            .toInstant().toEpochMilli()

    /**
     * The moment the window opened for the day containing [nowMs], or null when it has not opened yet
     * (or the window is off). Null means "nothing is due through the window" — a window nobody has reached
     * must not refresh anything, and a source that synced before it opened is due once it does.
     */
    fun openStartMs(nowMs: Long, window: DailySync, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!window.enabled) return null
        val start = startMs(nowMs, window.hour, zone)
        return if (nowMs >= start) start else null
    }

    /** Whether [lastSyncAt] predates the open window — i.e. this source has not succeeded since it opened. */
    fun isDue(lastSyncAt: Long?, openStartMs: Long?): Boolean =
        openStartMs != null && (lastSyncAt ?: 0L) < openStartMs
}
