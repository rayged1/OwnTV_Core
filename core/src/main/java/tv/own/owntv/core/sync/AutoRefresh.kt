package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * The playlist and guide "Auto refresh" settings, for both apps. It used to live in the TV app's
 * shell, so the phone stored the setting and never acted on it.
 *
 * - `includeStartup = true` (cold start): runs once per process, refreshes STARTUP sources
 *   unconditionally and interval sources whose data is at least as old as their threshold. Never
 *   time-throttled, so STARTUP always fires on a real cold start.
 * - `includeStartup = false` (app back in the foreground): skips STARTUP sources and refreshes
 *   interval sources past their threshold. Throttled to once per [RESUME_THROTTLE_MS].
 *
 * Enqueues use [ExistingWorkPolicy.KEEP], so a source already syncing or queued is left alone.
 * Manual re-syncs use REPLACE at their own call sites. A job enqueued here carries
 * [REASON], and the workers hold such a job back while something is playing ([tv.own.owntv.core.live.WatchSession]).
 *
 * A singleton, so the once-per-process rule holds even when the Activity is rebuilt.
 */
class AutoRefresh(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val profileDao: ProfileDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val epgSourceStore: EpgSourceStore,
    private val epgDao: EpgDao,
    private val importFinalizer: ImportFinalizer,
    private val vodResort: M3uVodResort,
) {
    private var coldStartCheckDone = false
    private var lastResumeCheckAtElapsed = 0L

    suspend fun check(includeStartup: Boolean) {
        synchronized(this) {
            if (includeStartup) {
                if (coldStartCheckDone) return
                coldStartCheckDone = true
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastResumeCheckAtElapsed < RESUME_THROTTLE_MS) return
                lastResumeCheckAtElapsed = now
            }
        }
        val nowMs = System.currentTimeMillis()
        // "Refresh everything at one time": when on, interval modes stop running on their own clocks and
        // become due together once the day's window has opened. Read before anything is enqueued so every
        // source in this pass is judged by the same instant.
        val window = settings.dailySync.first()
        val windowStart = DailySyncWindow.openStartMs(nowMs, window)
        val windowOpen = windowStart != null
        val pid = profileDao.resolveExistingProfileId(settings.activeProfileId.first()) ?: return
        // --- Playlist sources ---
        val playlistModes = settings.playlistAutoRefresh.first()
        if (playlistModes.isNotEmpty()) {
            sourceRepository.observeSources(pid).first().forEach { source ->
                val mode = playlistModes[source.id] ?: PlaylistRefresh.OFF
                if (shouldRefresh(mode, source.lastSyncAt, nowMs, includeStartup, windowOpen, windowStart ?: 0L)) {
                    val counts = importFinalizer.contentCounts(source.id)
                    Log.d(TAG, "checkAutoRefresh playlist sourceId=${source.id} mode=$mode — enqueuing")
                    catalogSyncScheduler.enqueueSync(
                        source.id,
                        reason = REASON,
                        contentTypes = SyncContentTypes.enabledOf(source),
                        baseItemCount = counts.channels + counts.movies + counts.series,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
        // --- EPG sources ---
        val epgModes = settings.epgAutoRefresh.first()
        if (epgModes.isNotEmpty()) {
            epgSourceStore.getAll().forEach { src ->
                val mode = epgModes[src.id] ?: EpgRefresh.OFF
                if (shouldRefreshEpg(mode, src.lastSyncAt, nowMs, includeStartup, windowOpen, windowStart ?: 0L)) {
                    val base = epgDao.countForSources(listOf(src.id))
                    Log.d(TAG, "checkAutoRefresh epg sourceId=${src.id} mode=$mode — enqueuing")
                    epgSyncScheduler.enqueueSync(
                        src.id,
                        reason = REASON,
                        baseProgrammes = base,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
        if (includeStartup) {
            refillGuideEmptiedByMigration()
            vodResort.runOnce()
        }
    }

    /**
     * Refill a guide that `MIGRATION_8_9` emptied.
     *
     * That migration deletes every `epg_programmes` row and nothing schedules a re-fetch, so an
     * upgrading user's Guide is simply blank until they think to re-sync EPG by hand. Runs **once per
     * install** (so it also catches users who passed through 8→9 in an earlier version) and only for
     * sources that had previously synced successfully but now hold zero programmes — that is the
     * exact signature of the wipe.
     *
     * EPG is opt-in by design, and this respects that: adding an EPG source *is* the opt-in, and a
     * source the user has never synced is left alone rather than silently downloaded. The enqueue is
     * an ordinary [EpgSyncScheduler] job, so it shows the standard EPG-syncing pill.
     *
     * The one-shot flag is read first and the DB is touched only when it is unset, so this adds no
     * work to a normal cold start.
     */
    private suspend fun refillGuideEmptiedByMigration() {
        if (settings.epgRefillChecked.first()) return
        runCatching {
            val sources = epgSourceStore.getAll().filter { (it.lastSyncAt ?: 0L) > 0L }
            for (src in sources) {
                if (epgDao.countForSources(listOf(src.id)) > 0) continue
                Log.i(TAG, "epgRefill sourceId=${src.id} — synced before but guide is empty, re-fetching")
                epgSyncScheduler.enqueueSync(
                    src.id,
                    reason = "migration_refill",
                    baseProgrammes = 0,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }.onFailure { Log.w(TAG, "epgRefill check failed", it) }
        // Marked regardless: a failed check must not retry on every launch forever, and a failed
        // *sync* is already retried by the scheduler's own policy.
        settings.markEpgRefillChecked()
    }

    /**
     * Whether a playlist source should auto-refresh now. OFF never; STARTUP only on cold start
     * ([includeStartup]); interval modes when `now - lastSyncAt >= threshold` (a null lastSyncAt — never
     * successfully synced — counts as infinitely stale so recovery happens).
     */
    private fun shouldRefresh(
        refresh: PlaylistRefresh,
        lastSyncAt: Long?,
        now: Long,
        includeStartup: Boolean,
        windowOpen: Boolean,
        windowStart: Long,
    ): Boolean = when (refresh.mode) {
        PlaylistAutoRefresh.OFF -> false
        PlaylistAutoRefresh.STARTUP -> includeStartup
        else -> if (windowOpen) {
            // The window replaces the timing, not the choice: an interval source is refreshed once the
            // day's window has opened and it has not succeeded since. Every such source answers yes in
            // this same pass, which is what makes it one event instead of four.
            (lastSyncAt ?: 0L) < windowStart
        } else {
            (now - (lastSyncAt ?: 0L)) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
        }
    }

    /** EPG equivalent of [shouldRefresh]. */
    private fun shouldRefreshEpg(
        refresh: EpgRefresh,
        lastSyncAt: Long?,
        now: Long,
        includeStartup: Boolean,
        windowOpen: Boolean,
        windowStart: Long,
    ): Boolean = when (refresh.mode) {
        EpgAutoRefresh.OFF -> false
        EpgAutoRefresh.STARTUP -> includeStartup
        // MANUAL's threshold is its day count; every other mode carries its own. Identical shape to
        // [shouldRefresh], which is the point of the parity.
        else -> if (windowOpen) {
            (lastSyncAt ?: 0L) < windowStart
        } else {
            (now - (lastSyncAt ?: 0L)) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
        }
    }

    companion object {
        /** The window arithmetic, in [DailySyncWindow] so it is testable without the DI graph. */
        internal fun windowStartMs(nowMs: Long, hour: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
            DailySyncWindow.startMs(nowMs, hour, zone)

        private const val TAG = "OwnTVHome"
        /** The work reason the sync workers recognise as automatic, and hold back during playback. */
        const val REASON = "auto_refresh"
        /** Minimum gap between resume-triggered checks (rotation, rapid background/foreground). */
        private const val RESUME_THROTTLE_MS = 60_000L
    }
}
