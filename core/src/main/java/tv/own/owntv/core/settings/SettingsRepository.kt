package tv.own.owntv.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES
import tv.own.owntv.core.live.MAX_MULTIVIEW_TILES
import tv.own.owntv.core.live.MIN_MULTIVIEW_TILES
import tv.own.owntv.core.recording.RecordingSchedule
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.menu.MenuLayout
import tv.own.owntv.core.model.ContentMenu
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.PopupFontScale
import tv.own.owntv.core.theme.PopupSizeScale
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.core.theme.UiFontScale
import tv.own.owntv.core.theme.UiZoom
import tv.own.owntv.core.timeshift.TimeshiftRules

/** Per-profile startup landing. LAST_CHANNEL also covers "auto-play my channel" since
 *  it's always the one you last watched. LAST_CHANNEL_GUIDE and LAST_CHANNEL_LIVE open the TV Guide
 *  or Live TV instead, on that channel's category with the channel focused in the preview.
 *  Saved by name, so the order here is only the order the choices are listed in. */
enum class StartupMode {
    HOME, LAST_CHANNEL, LAST_CHANNEL_GUIDE, LAST_CHANNEL_LIVE, FAVORITES, SPECIFIC_CHANNEL
}

data class StartupChannelRef(
    val sourceId: Long,
    val remoteId: String?,
    val name: String,
    val itemId: Long,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("sourceId", sourceId)
        .putOpt("remoteId", remoteId)
        .put("name", name)
        .put("itemId", itemId)

    companion object {
        fun fromJson(raw: String?): StartupChannelRef? = runCatching {
            val o = org.json.JSONObject(raw ?: return null)
            StartupChannelRef(
                sourceId = o.getLong("sourceId").takeIf { it > 0L } ?: return null,
                remoteId = o.optString("remoteId").takeIf { it.isNotBlank() },
                name = o.getString("name").takeIf { it.isNotBlank() } ?: return null,
                itemId = o.optLong("itemId", -1L),
            )
        }.getOrNull()
    }
}

/**
 * Per-source playlist auto-refresh mode. The interval entries are **staleness thresholds**, not strict
 * timers: a source is refreshed when `now - lastSyncAt >= thresholdMs`. OFF disables auto-refresh;
 * STARTUP refreshes on cold app start only; interval modes are checked on cold start and on resume.
 * [thresholdMs] has a default of null so OFF/STARTUP can be declared without an explicit value.
 *
 * Anything longer than 12 hours is [MANUAL], where the user names the number of days — the fixed
 * 24h/48h/7-day entries it replaced are still understood on read (see [PlaylistRefresh.parse]) so an
 * existing selection or an old backup keeps working.
 */
enum class PlaylistAutoRefresh(val thresholdMs: Long? = null) {
    OFF,
    STARTUP,
    HOURS_6(6 * 3600_000L),
    HOURS_12(12 * 3600_000L),

    /** A whole number of days chosen by the user; the count lives in [PlaylistRefresh.manualDays]. */
    MANUAL,
}

/**
 * A playlist's auto-refresh selection: the mode, plus the day count that only [PlaylistAutoRefresh.MANUAL]
 * uses. Stored as one string per source so nothing else — backup, restore, the companion form — has to
 * learn about a second value: `"OFF"`, `"HOURS_6"`, or `"MANUAL:14"`.
 */
data class PlaylistRefresh(
    val mode: PlaylistAutoRefresh = PlaylistAutoRefresh.OFF,
    val manualDays: Int = DEFAULT_MANUAL_DAYS,
) {
    /** How stale the source may get before it is refreshed; null when it never auto-refreshes on a timer. */
    val thresholdMs: Long?
        get() = if (mode == PlaylistAutoRefresh.MANUAL) {
            manualDays.coerceIn(MIN_MANUAL_DAYS, MAX_MANUAL_DAYS) * 24 * 3600_000L
        } else {
            mode.thresholdMs
        }

    fun serialize(): String =
        if (mode == PlaylistAutoRefresh.MANUAL) "${mode.name}$SEPARATOR$manualDays" else mode.name

    companion object {
        const val MIN_MANUAL_DAYS = 1
        const val MAX_MANUAL_DAYS = 99
        const val DEFAULT_MANUAL_DAYS = 7

        /** Day counts offered directly where a stepper is not the right control — a phone's list. */
        val PRESET_MANUAL_DAYS = listOf(1, 2, 3, 7, 14, 30)

        private const val SEPARATOR = ':'

        val OFF = PlaylistRefresh(PlaylistAutoRefresh.OFF)

        /**
         * Reads a stored value, translating the retired fixed entries into the day counts that mean the
         * same thing. Nothing rewrites the stored string for them — the translation happens on every read,
         * so a user who never opens the picker again keeps the schedule they chose, forever.
         */
        fun parse(raw: String?): PlaylistRefresh = when (val value = raw?.trim().orEmpty()) {
            "HOURS_24" -> PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 1)
            "HOURS_48" -> PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 2)
            "DAYS_7" -> PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 7)
            else -> {
                val mode = runCatching {
                    PlaylistAutoRefresh.valueOf(value.substringBefore(SEPARATOR))
                }.getOrDefault(PlaylistAutoRefresh.OFF)
                val days = value.substringAfter(SEPARATOR, "").toIntOrNull()
                    ?.coerceIn(MIN_MANUAL_DAYS, MAX_MANUAL_DAYS)
                    ?: DEFAULT_MANUAL_DAYS
                PlaylistRefresh(mode, days)
            }
        }
    }
}

/**
 * Per-EPG-source auto-refresh mode. Same staleness-threshold semantics as [PlaylistAutoRefresh].
 *
 * [MANUAL] is the same addition playlists already had: there was no reason a user could say "refresh
 * this playlist every 10 days" but not "refresh this guide every 10 days", and 48 hours — the longest
 * fixed option — used to be the storage horizon too, so even the best setting could run dry exactly at
 * the boundary.
 *
 * **Every existing entry is kept.** A stored `HOURS_48` still parses to `HOURS_48` and behaves
 * exactly as before; nothing rewrites a selection the user already made.
 */
enum class EpgAutoRefresh(val thresholdMs: Long? = null) {
    OFF,
    STARTUP,
    HOURS_1(1 * 3600_000L),
    HOURS_3(3 * 3600_000L),
    HOURS_6(6 * 3600_000L),
    HOURS_12(12 * 3600_000L),
    HOURS_24(24 * 3600_000L),
    HOURS_48(48 * 3600_000L),

    /** A whole number of days chosen by the user; the count lives in [EpgRefresh.manualDays]. */
    MANUAL,
}

/**
 * An EPG source's auto-refresh selection: the mode, plus the day count only [EpgAutoRefresh.MANUAL]
 * uses. The shape, the serialization and the bounds are [PlaylistRefresh]'s, deliberately — the two
 * screens ask the same question and should not answer it differently.
 *
 * Stored as one string per source, so backup, restore and the pickers need learn nothing about a
 * second value: `"OFF"`, `"HOURS_48"`, or `"MANUAL:10"`.
 */
data class EpgRefresh(
    val mode: EpgAutoRefresh = EpgAutoRefresh.OFF,
    val manualDays: Int = PlaylistRefresh.DEFAULT_MANUAL_DAYS,
) {
    /** How stale the guide may get before it is refreshed; null when it never refreshes on a timer. */
    val thresholdMs: Long?
        get() = if (mode == EpgAutoRefresh.MANUAL) {
            manualDays.coerceIn(PlaylistRefresh.MIN_MANUAL_DAYS, PlaylistRefresh.MAX_MANUAL_DAYS) * 24 * 3600_000L
        } else {
            mode.thresholdMs
        }

    fun serialize(): String =
        if (mode == EpgAutoRefresh.MANUAL) "${mode.name}$SEPARATOR$manualDays" else mode.name

    companion object {
        private const val SEPARATOR = ':'

        val OFF = EpgRefresh(EpgAutoRefresh.OFF)

        /**
         * Reads a stored value.
         *
         * The old format was a bare enum name and the new one is a superset of it, so every existing
         * selection parses to exactly what it meant before and **nothing is ever rewritten**. An
         * unrecognised value falls back to OFF, which is also what a source that has never been
         * configured gets — new sources do not auto-refresh until the user asks them to.
         */
        fun parse(raw: String?): EpgRefresh {
            val value = raw?.trim().orEmpty()
            val mode = runCatching {
                EpgAutoRefresh.valueOf(value.substringBefore(SEPARATOR))
            }.getOrDefault(EpgAutoRefresh.OFF)
            val days = value.substringAfter(SEPARATOR, "").toIntOrNull()
                ?.coerceIn(PlaylistRefresh.MIN_MANUAL_DAYS, PlaylistRefresh.MAX_MANUAL_DAYS)
                ?: PlaylistRefresh.DEFAULT_MANUAL_DAYS
            return EpgRefresh(mode, days)
        }
    }
}

/**
 * "Refresh everything at one time" — the daily sync window.
 *
 * Without it every auto-refreshing source keeps its **own** 24-hour timer, measured from whenever that
 * source last succeeded, so two playlists and two guides refresh at four unrelated times of day and the
 * app reads as if it is always updating (Ray, 2026-11-20). With it on, every auto-refreshing source
 * becomes due **once a day at [hour]**, and all of them together: a source is refreshed when the most
 * recent window has opened and that source has not succeeded since it did.
 *
 * A source's own interval still decides *whether* it auto-refreshes at all (OFF never does), but the
 * window replaces the timing — the point of the setting is one visible event a day instead of four.
 * Off by default, which is exactly the behaviour every existing build has.
 */
data class DailySync(val enabled: Boolean = false, val hour: Int = DEFAULT_HOUR) {

    fun serialize(): String = if (enabled) "ON$SEPARATOR${hour.coerceIn(0, 23)}" else "OFF"

    companion object {
        const val DEFAULT_HOUR = 3
        private const val SEPARATOR = ':'

        val OFF = DailySync()

        /** Any unrecognised value is OFF: a window nobody asked for must never start refreshing sources. */
        fun parse(raw: String?): DailySync {
            val value = raw?.trim().orEmpty()
            if (value.substringBefore(SEPARATOR).uppercase() != "ON") return OFF
            val hour = value.substringAfter(SEPARATOR, "").toIntOrNull()?.coerceIn(0, 23) ?: DEFAULT_HOUR
            return DailySync(enabled = true, hour = hour)
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_settings")

/**
 * The positions written while the user browses — last channel, last category per section — kept apart
 * from the settings file. Each one used to be a write to `owntv_settings`, and every write there
 * re-runs every settings collector in both engines and every screen. Copied over once, on first open.
 */
private val Context.positionStore: DataStore<Preferences> by preferencesDataStore(
    name = "owntv_positions",
    produceMigrations = { context -> listOf(PositionsFromSettings(context)) },
)

private class PositionsFromSettings(private val context: Context) : androidx.datastore.core.DataMigration<Preferences> {
    private val moved = booleanPreferencesKey("moved_from_settings")

    override suspend fun shouldMigrate(currentData: Preferences) = currentData[moved] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = context.dataStore.data.first()
        return currentData.toMutablePreferences().apply {
            for (key in SettingsRepository.Keys.POSITIONS) {
                @Suppress("UNCHECKED_CAST")
                val k = key as Preferences.Key<Any>
                old[k]?.let { this[k] = it }
            }
            this[moved] = true
        }.toPreferences()
    }

    // The old values stay in the settings file, unread; nothing is deleted on the way.
    override suspend fun cleanUp() = Unit
}

/** CH+- key paging limits. Top-level so any caller (VM, UI) can reference them via the class. */
object ChNavLimits {
    /** Hard cap for the CH+- skip counts — protects against typos (e.g. 999999) overloading slow TVs. */
    const val HARD_MAX = 1000
    /** Above this value the settings UI shows an advisory warning (high skips overshoot short lists). */
    const val WARN_THRESHOLD = 50
    /** Default per-direction skip (single CH+/- press moves this many items). */
    const val DEFAULT_SKIP = 10
}

/** Seek/rewind step choices. Top-level so the settings UI and the defaults share one list. */
object SeekSteps {
    /** Rewind/forward inside a movie or episode — the player's long-standing 10 s. */
    const val DEFAULT_SEEK_STEP_SEC = 10
    val SEEK_CHOICES = listOf(5, 10, 15, 30, 60)

    /** Stepping through a live channel's catch-up archive — the long-standing 30 s. */
    const val DEFAULT_LIVE_REWIND_STEP_SEC = 30
    val LIVE_REWIND_CHOICES = listOf(10, 15, 30, 60, 120)
}

/**
 * Persists app-level preferences: global ones in the settings file, per-profile ones under keys that
 * carry the profile id.
 */
class SettingsRepository(private val context: Context, private val localeStore: LocaleStore) :
    CustomizePinSettings by CustomizePinStore(context.dataStore) {

    /**
     * Scope for the warm settings snapshots below. Lives as long as this singleton — deliberately never
     * cancelled, because the alternative is paying a DataStore read on every metadata resolve.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Result of importing the optional locale field without allowing bad backup data to abort the restore. */
    data class SettingsImportResult(
        val localePresent: Boolean = false,
        val localeTag: String? = null,
        val invalidLocale: Boolean = false,
    )

    /**
     * Every settings flow below is derived through this.
     *
     * DataStore emits the **whole** `Preferences` object to **every** collector on **every** write,
     * so without the `distinctUntilChanged` a single toggle re-ran ~100 `map { }` lambdas and pushed
     * ~100 "new" StateFlow values app-wide — each one a potential recomposition — even though only
     * one key had actually changed. One operator here stops that propagation for all of them at
     * once, which is why this is a helper rather than 74 hand-edited call sites.
     */
    private fun <T> prefsFlow(transform: (Preferences) -> T): Flow<T> =
        context.dataStore.data.map(transform).distinctUntilChanged()

    /** One stored string, parsed only when *it* changes — not on every write to the settings file, which
     *  is what [prefsFlow] costs a JSON or list parser. */
    private fun <V, T> parsedFlow(key: Preferences.Key<V>, parse: (V?) -> T): Flow<T> =
        context.dataStore.data.map { it[key] }.distinctUntilChanged().map(parse)

    private fun <T> positionFlow(key: Preferences.Key<T>): Flow<T?> =
        context.positionStore.data.map { it[key] }.distinctUntilChanged()

    /**
     * A remembered position of the profile in use, following profile switches. Each profile keeps its
     * own under `<name>_<profileId>`; one that has not saved one yet reads the value the whole device
     * shared before positions were kept per profile, so nobody loses their place on upgrade.
     */
    @Suppress("UNCHECKED_CAST") // the stored value has the type it was written with: [shared]'s
    private fun <T> profilePositionFlow(shared: Preferences.Key<T>): Flow<T?> =
        combine(prefsFlow { it[Keys.ACTIVE_PROFILE] ?: -1L }, context.positionStore.data) { pid, p ->
            ProfilePositions.read(p.asMap().mapKeys { it.key.name }, shared.name, pid) as T?
        }.distinctUntilChanged()

    private suspend fun <T> setProfilePosition(shared: Preferences.Key<T>, value: T) {
        val key = profilePositionKey(shared, activeProfileIdNow())
        context.positionStore.edit { it[key] = value }
    }

    /** [shared]'s own copy for [profileId]: the same name with `_<profileId>`, and the same value type. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> profilePositionKey(shared: Preferences.Key<T>, profileId: Long): Preferences.Key<T> {
        val name = ProfilePositions.name(shared.name, profileId)
        return (if (shared == Keys.LAST_LIVE_CHANNEL) longPreferencesKey(name) else stringPreferencesKey(name)) as Preferences.Key<T>
    }

    /** Drops [profileId]'s remembered positions, for a profile being deleted. */
    suspend fun forgetProfilePositions(profileId: Long) {
        context.positionStore.edit { p -> Keys.POSITIONS.forEach { p.remove(profilePositionKey(it, profileId)) } }
    }

    // Glass is off until it is set (a fresh install's Stage defaults turn it on).
    private val GLASS_SCOPE_DEFAULT_BITS: Int = 0
    private val NAV_WIDEN_OFF = "OFF"

    // Internal (not private) only so SettingsBackupCoverageTest can check every key has a backup decision.
    internal object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UI_ZOOM_PCT = intPreferencesKey("ui_zoom_percent")
        val FONT_SIZE_PCT = intPreferencesKey("font_size_percent")
        val POPUP_FONT_SIZE_PCT = intPreferencesKey("popup_font_size_percent")
        val POPUP_SIZE_PCT = intPreferencesKey("popup_size_percent")
        val MAIN_FONT_FAMILY = stringPreferencesKey("main_font_family")
        val POPUP_FONT_FAMILY = stringPreferencesKey("popup_font_family")
        val ACCENT = stringPreferencesKey("accent_color")
        val ACCENT_CUSTOM = stringPreferencesKey("accent_custom")
        val FOCUS_HIGHLIGHT = stringPreferencesKey("focus_highlight_color")
        val FOCUS_HIGHLIGHT_WIDTH = intPreferencesKey("focus_highlight_width")
        val ACTIVE_PROFILE = longPreferencesKey("active_profile_id")
        val DEFAULT_SOURCE = longPreferencesKey("default_source_id")
        val DOWNLOAD_ROOT = stringPreferencesKey("download_root")
        val REFRESH_SOURCE_IDS = stringSetPreferencesKey("refresh_source_ids")
        // Per-source auto-refresh selections (JSON maps: { "<sourceId>": "<EnumName>" }). Replace the
        // binary refresh-on-startup set with Off/Startup + staleness thresholds. Migration-safe: the legacy
        // REFRESH_SOURCE_IDS set is read once (see migrateLegacyRefreshFlags) then ignored.
        val PLAYLIST_AUTO_REFRESH = stringPreferencesKey("playlist_auto_refresh")
        val EPG_AUTO_REFRESH = stringPreferencesKey("epg_auto_refresh")
        val DAILY_SYNC = stringPreferencesKey("daily_sync")
        // Per-EPG-source: use that feed's own <icon src> channel logos instead of the playlist's.
        val EPG_USE_LOGOS = stringPreferencesKey("epg_use_logos")
        val M3U_VOD_RESORT_CHECKED = booleanPreferencesKey("m3u_vod_resort_checked")
        val REFRESH_MIGRATED = booleanPreferencesKey("refresh_migration_done")
        val EPG_REFILL_CHECKED = booleanPreferencesKey("epg_refill_checked")
        // Set while a backup restore is applying, cleared only when it completes. A value that
        // survives to the next launch means the restore was interrupted and may be half-applied.
        val RESTORE_IN_PROGRESS = stringPreferencesKey("restore_in_progress")
        val LIVE_PREVIEW = booleanPreferencesKey("live_preview")
        val LIVE_PREVIEW_AUDIO = booleanPreferencesKey("live_preview_audio")
        // OK on a channel first plays it in the preview pane; OK again goes full screen. One switch
        // per screen: the TV Guide (on by default, its behaviour before the switch existed) and
        // Live TV (off by default — focus auto-previews there instead).
        val GUIDE_PREVIEW_ON_OK = booleanPreferencesKey("guide_preview_on_ok")
        val LIVE_PREVIEW_ON_OK = booleanPreferencesKey("live_preview_on_ok")
        // Whether the expanded Home hero plays its video. Unset means "device default" — see
        // heroPreviewEnabled.
        val HERO_PREVIEW = booleanPreferencesKey("hero_preview")
        // Docked mini-player: size (% of screen width) and screen corner/edge.
        val MINI_PLAYER_SIZE_PCT = intPreferencesKey("mini_player_size_pct")
        val MINI_PLAYER_POSITION = stringPreferencesKey("mini_player_position")
        // Live TV latency: preset name + the custom seconds used when the preset is CUSTOM.
        val LIVE_LATENCY_MODE = stringPreferencesKey("live_latency_mode")
        val LIVE_LATENCY_CUSTOM_SECS = intPreferencesKey("live_latency_custom_secs")
        val LIVE_PREROLL_SECS = intPreferencesKey("live_preroll_secs")
        // How long a live channel may take to produce a picture before it is called dead. 0 = never.
        val LIVE_TUNE_TIMEOUT_SECS = intPreferencesKey("live_tune_timeout_secs")
        // v4.1.6 one-shot: reset live latency to the safe Balanced preset. Subsequent user changes are
        // preserved across every later update.
        val LIVE_LATENCY_RESET_416 = booleanPreferencesKey("live_latency_reset_416")
        // Stage (2026-09-30): moved a saved System Sans to Plus Jakarta Sans once. Device-local, never backed up.
        val FONT_JAKARTA_MIGRATED = booleanPreferencesKey("font_jakarta_migrated")
        val LIST_ORDERS_RESET = booleanPreferencesKey("list_orders_reset")
        // Stage (2026-10-02): the TV app's new-UI defaults written once (applyStageDefaults). Device-local.
        val STAGE_DEFAULTS_APPLIED = booleanPreferencesKey("stage_defaults_applied")
        val PREFER_SDR = booleanPreferencesKey("prefer_sdr")
        // Films and series. Live TV has its own switch below, which reads this one until it is set.
        val AUTO_FRAME_RATE = booleanPreferencesKey("auto_frame_rate")
        val AUTO_FRAME_RATE_LIVE = booleanPreferencesKey("auto_frame_rate_live")
        val AFR_PAUSE_SECS = intPreferencesKey("afr_pause_secs")
        val AFR_MATCH_RESOLUTION = booleanPreferencesKey("afr_match_resolution")
        val VOD_BUFFER_SECS = intPreferencesKey("vod_buffer_secs")
        val VOD_NETWORK_TIMEOUT_SECS = intPreferencesKey("vod_network_timeout_secs")
        val VOD_RECONNECTS = intPreferencesKey("vod_reconnects")
        val AUDIO_PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
        val NIGHT_MODE = booleanPreferencesKey("night_mode")
        val VOLUME_LEVELLING = booleanPreferencesKey("volume_levelling")
        val MAX_VIDEO_HEIGHT = intPreferencesKey("max_video_height")
        val MOBILE_DATA_MAX_VIDEO_HEIGHT = intPreferencesKey("mobile_data_max_video_height")
        val TUNNELED_PLAYBACK = booleanPreferencesKey("tunneled_playback")
        val TUNNELING_FAILED = booleanPreferencesKey("tunneling_failed")
        // v4.1.6 one-shot: AFR caused visible HDMI re-handshakes on some TVs. Existing installs are
        // forced Off once; subsequent user changes are preserved across every later update.
        val AUTO_FRAME_RATE_RESET_416 = booleanPreferencesKey("auto_frame_rate_reset_416")
        // v4.2.0 one-shot: below Android 12 the app cannot tell a seamless refresh-rate switch from one
        // that blanks the panel, so AFR is forced Off once on those devices. Set on every device (so the
        // migration and the [autoFrameRateFilms] gate below both settle), but only clears AFR on pre-12.
        val AUTO_FRAME_RATE_RESET_PRE12 = booleanPreferencesKey("auto_frame_rate_reset_pre12")

        /** The one-time "this stream judders at your TV's refresh rate" suggestion has been answered. */
        val AUTO_FRAME_RATE_PROMPTED = booleanPreferencesKey("auto_frame_rate_prompted")
        val ANDROID_TV_HOME = booleanPreferencesKey("android_tv_home")

        // Multiview: watching up to four live channels at once.
        val MULTIVIEW_ENABLED = booleanPreferencesKey("multiview_enabled")
        val MULTIVIEW_TILES = intPreferencesKey("multiview_tiles")
        /** The "3 or 4 tiles may be more than this device or your provider can do" warning was accepted. */
        val MULTIVIEW_WARNING_ACCEPTED = booleanPreferencesKey("multiview_warning_accepted")
        // Recording. Recordings and multiview spend the same connections.
        val RECORDING_RESERVE_CONNECTION = booleanPreferencesKey("recording_reserve_connection")
        val RECORDING_PRE_ROLL_MINUTES = intPreferencesKey("recording_pre_roll_minutes")
        val RECORDING_POST_ROLL_MINUTES = intPreferencesKey("recording_post_roll_minutes")
        val RECORDING_OVER_MOBILE_DATA = booleanPreferencesKey("recording_over_mobile_data")
        val RECORD_WHAT_IM_WATCHING = booleanPreferencesKey("record_what_im_watching")
        // Video Player Settings
        val HW_DECODING = booleanPreferencesKey("hw_decoding")
        val SOFTWARE_DECODE_ABOVE_1080P = booleanPreferencesKey("software_decode_above_1080p")
        val VOD_PREFER_EXO = booleanPreferencesKey("vod_prefer_exo") // legacy; read for migration only
        val LIVE_ENGINE = stringPreferencesKey("live_engine")
        val VOD_ENGINE = stringPreferencesKey("vod_engine")
        val MEASURED_STREAM_STATS = booleanPreferencesKey("measured_stream_stats")
        val DETAILED_DIAGNOSTICS = booleanPreferencesKey("detailed_diagnostics")
        val DIRECT_TUNE = booleanPreferencesKey("direct_tune")
        val LIVE_LEFT_RIGHT_REWINDS = booleanPreferencesKey("live_left_right_rewinds")
        val TIMESHIFT_ENABLED = booleanPreferencesKey("timeshift_enabled")
        val TIMESHIFT_WINDOW_MINUTES = intPreferencesKey("timeshift_window_minutes")
        val TIMESHIFT_RESUME_MODE = stringPreferencesKey("timeshift_resume_mode")
        val SURROUND_SOUND = booleanPreferencesKey("surround_sound")
        val SURROUND_MODE = stringPreferencesKey("surround_mode")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        /** Legacy single external-player toggle (movies + series + downloads). Superseded by the three
         *  per-section keys below but still read as their default, so an existing setting survives. */
        val EXTERNAL_PLAYER = booleanPreferencesKey("external_player")
        val EXTERNAL_PLAYER_LIVE = booleanPreferencesKey("external_player_live")
        val EXTERNAL_PLAYER_MOVIES = booleanPreferencesKey("external_player_movies")
        val EXTERNAL_PLAYER_SERIES = booleanPreferencesKey("external_player_series")
        val DEFAULT_ZOOM = stringPreferencesKey("default_zoom")
        val DEFAULT_VOLUME = intPreferencesKey("default_volume")
        val SEEK_STEP_SEC = intPreferencesKey("seek_step_sec")
        val LIVE_REWIND_STEP_SEC = intPreferencesKey("live_rewind_step_sec")
        /** Legacy single subtitle size, superseded by the two per-engine keys below but still read as
         *  their default so an existing size survives an upgrade — and still written by nothing. */
        val SUB_SCALE = floatPreferencesKey("sub_scale")
        val SUB_SCALE_MPV = floatPreferencesKey("sub_scale_mpv")
        val SUB_SCALE_EXO = floatPreferencesKey("sub_scale_exo")
        // Subtitle appearance: off by default so every renderer keeps its stock look —
        // notably the embedded broadcaster styling of Live TV CEA-608/teletext cues.
        val SUB_STYLE_ENABLED = booleanPreferencesKey("sub_style_enabled")
        val SUB_FONT = stringPreferencesKey("sub_font")
        val SUB_COLOR = stringPreferencesKey("sub_color")
        val SUB_POSITION = stringPreferencesKey("sub_position")
        val SUB_BG_OPACITY = intPreferencesKey("sub_bg_opacity")
        val AUDIO_DELAY_MS = intPreferencesKey("audio_delay_ms")
        val AUDIO_DELAY_BY_OUTPUT = stringPreferencesKey("audio_delay_by_output")
        val PREF_AUDIO_LANG = stringPreferencesKey("pref_audio_lang")
        val PREF_SUB_LANG = stringPreferencesKey("pref_sub_lang")
        // OpenSubtitles online-search language filter. Separate from PREF_SUB_LANG (embedded tracks) —
        // off by default, so a search returns every language OpenSubtitles has for the title.
        val SUB_SEARCH_FILTER = booleanPreferencesKey("sub_search_filter")
        val SUB_SEARCH_LANGS = stringPreferencesKey("sub_search_langs")
        // Settings rows pinned to the Quick group, comma-joined, in display order.
        val QUICK_PINNED = stringPreferencesKey("settings_quick_pinned")
        // Per-section list sorting ("PLAYLIST" or "ALPHA")
        val SORT_LIVE = stringPreferencesKey("sort_live")
        val SORT_GUIDE = stringPreferencesKey("sort_guide")
        val GUIDE_SHOW_EMPTY = booleanPreferencesKey("guide_show_empty")
        val GUIDE_PREVIEW = booleanPreferencesKey("guide_preview")
        val SORT_MOVIES = stringPreferencesKey("sort_movies")
        val SORT_SERIES = stringPreferencesKey("sort_series")
        val RESUME_MODE = stringPreferencesKey("resume_mode")
        val UPDATE_CHECK_ON_START = booleanPreferencesKey("update_check_on_start")
        val CATCHUP_TZ = stringPreferencesKey("catchup_timezone")
        val CATCHUP_PLAYER = stringPreferencesKey("catchup_player")
        val CATCHUP_OFFSET_MIN = intPreferencesKey("catchup_offset_minutes")
        val EPG_OFFSET_MIN = intPreferencesKey("epg_offset_minutes")
        val GUIDE_DAYS_TO_KEEP = intPreferencesKey("guide_days_to_keep")
        val ANIMATION_LEVEL = stringPreferencesKey("animation_level")
        val AMBIENT_GLOW_ENABLED = booleanPreferencesKey("ambient_glow_enabled")
        val AMBIENT_GLOW_PULSE = booleanPreferencesKey("ambient_glow_pulse")
        val RESUME_LAST_CHANNEL = booleanPreferencesKey("resume_last_channel")
        val LAST_LIVE_CATEGORY = stringPreferencesKey("last_live_category")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val LAST_LIVE_CHANNEL = androidx.datastore.preferences.core.longPreferencesKey("last_live_channel")
        val VOD_GRID_COLUMNS = intPreferencesKey("vod_grid_columns")
        val VOD_VIEW_MODE = stringPreferencesKey("vod_view_mode")
        val VOD_LAYOUT = stringPreferencesKey("vod_layout")
        val LIVE_LAYOUT = stringPreferencesKey("live_layout")
        val LIVE_VIEW = stringPreferencesKey("live_view")
        val REMINDER_MODE = stringPreferencesKey("reminder_mode")
        val REMINDER_LEAD_MIN = intPreferencesKey("reminder_lead_min")
        val GUIDE_VIEW = stringPreferencesKey("guide_view")
        val GUIDE_DENSITY_PCT = intPreferencesKey("guide_density_pct")
        val EPISODE_VIEW_MODE = stringPreferencesKey("episode_view_mode")
        // Touch-host settings. A television has no home button to shrink a video into, no metered
        // connection to save, and no swipe to calibrate — so all four default to the behaviour the TV
        // app already has, and only the phone app ever shows a row for them.
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val GESTURE_SENSITIVITY_PCT = intPreferencesKey("gesture_sensitivity_pct")
        val DOWNLOADS_WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        val DOWNLOAD_WATCH_CHOICE = stringPreferencesKey("download_watch_choice")
        // The mobility layer, all of it touch-host only: a window that floats over another app, a
        // screen that switches itself off, and a data plan that gets billed. A television has none of
        // the three, so every default below is what a TV does today — nothing.
        val MINI_PLAYER_STYLE = stringPreferencesKey("mini_player_style")
        val PIP_ON_BACK = booleanPreferencesKey("pip_on_back")
        val PIP_SIZE = stringPreferencesKey("pip_size")
        val PIP_SNAP = booleanPreferencesKey("pip_snap")
        val AUDIO_ON_SCREEN_OFF = booleanPreferencesKey("audio_on_screen_off")
        val AUDIO_ON_MOBILE_DATA = booleanPreferencesKey("audio_on_mobile_data")
        val AUDIO_PER_CHANNEL = booleanPreferencesKey("audio_per_channel")
        // Global proxy (Approach 1 — one app-wide HTTP proxy). HTTP only; no per-source override yet.
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USER = stringPreferencesKey("proxy_user")
        val PROXY_PASS = stringPreferencesKey("proxy_pass")
        // Global custom DNS — one app-wide DNS server (plain UDP or DoH). Sibling to global proxy.
        val DNS_ENABLED = booleanPreferencesKey("dns_enabled")
        val DNS_HOST = stringPreferencesKey("dns_host")
        val DNS_PORT = intPreferencesKey("dns_port")
        val DNS_DOH_URL = stringPreferencesKey("dns_doh_url")
        // Weather chip: show/hide + manual location override (blank = auto-detect from public IP).
        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val WEATHER_LOCATION = stringPreferencesKey("weather_location")
        val WEATHER_FAHRENHEIT = booleanPreferencesKey("weather_fahrenheit")
        val CLOCK_TIME_COLOR = stringPreferencesKey("clock_time_color")
        val CLOCK_DATE_COLOR = stringPreferencesKey("clock_date_color")
        val CLOCK_WEATHER_COLOR = stringPreferencesKey("clock_weather_color")
        // TMDB metadata enrichment. Master toggle + the two
        // advanced tiers (own key / self-host URL). Blank tier fields = use the default caching Worker.
        val METADATA_ENABLED = booleanPreferencesKey("metadata_enabled") // legacy; migrated to METADATA_MODE
        val METADATA_MODE = stringPreferencesKey("metadata_mode")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val METADATA_SERVER_URL = stringPreferencesKey("metadata_server_url")
        val OPEN_SUBTITLES_API_KEY = stringPreferencesKey("open_subtitles_api_key")
        val OPEN_SUBTITLES_SERVER_URL = stringPreferencesKey("open_subtitles_server_url")
        // TMDB content language (ISO 639-1, optionally with region — e.g. "el", "pt-BR"). Blank = the
        // TMDB default (en-US), which is what every install used before this setting existed, so leaving
        // it blank keeps existing users' metadata exactly as it was. "auto" = follow the device locale.
        val METADATA_LANGUAGE = stringPreferencesKey("metadata_language")
        // Bumped whenever a matcher fix invalidates previously cached "no match" rows, so existing
        // installs drop them once instead of waiting out the 7-day negative TTL. See
        // MetadataRepository.MATCH_HEURISTICS_VERSION.
        val METADATA_MATCH_HEAL_VERSION = intPreferencesKey("metadata_match_heal_version")
        // Nav menu customization (v4.3.0): DYNAMIC auto-adapts the side icons to what the active playlist
        // offers; STATIC lets the user hide specific icons. NAV_HIDDEN holds MainSection.name values the
        // user has hidden (STATIC mode only — DYNAMIC ignores it).
        val NAV_MENU_MODE = stringPreferencesKey("nav_menu_mode")
        // Chosen flip-card icon colour (AppIcon.name). What the launcher shows is AppIconSwitcher.applied.
        val APP_ICON = stringPreferencesKey("app_icon")
        val NAV_MENU_HIDDEN = stringSetPreferencesKey("nav_menu_hidden")
        // Stage navigation (TV): where the rail sits (NavStyle.name), what it shows (NavSize.name), its
        // length (NavLength.name), and how long the floating rail waits before hiding.
        val NAV_STYLE = stringPreferencesKey("nav_style")
        val NAV_SIZE = stringPreferencesKey("nav_size")
        val NAV_LENGTH = stringPreferencesKey("nav_length")
        val NAV_HIDE_AFTER_MS = intPreferencesKey("nav_hide_after_ms")
        // Docked + Compact: the size the rail opens to while it holds focus (NavSize.name), or "OFF".
        val NAV_WIDEN = stringPreferencesKey("nav_widen")
        // In-app logos: the play triangle in the user's accent instead of the icon's own colour.
        val BRAND_ACCENT_TRIANGLE = booleanPreferencesKey("brand_accent_triangle")
        // Phone bottom bar (not the TV rail keys — a different control): names under the icons, and
        // whether it slides away while a list scrolls down. Plus counts in the browse sheets.
        val PHONE_NAV_LABELS = booleanPreferencesKey("phone_nav_labels")
        val PHONE_NAV_HIDE_ON_SCROLL = booleanPreferencesKey("phone_nav_hide_on_scroll")
        val SHEET_COUNTS = booleanPreferencesKey("sheet_counts")
        // CH+- key paging for browse panels (Live/Movies/Series: category rail + item list/grid).
        // Master toggle + a per-direction skip count (CH+ toward first, CH− toward last). Counts are
        // clamped to [1, CH_NAV_HARD_MAX] on write; the UI warns above CH_NAV_WARN_THRESHOLD.
        val CH_NAV_ENABLED = booleanPreferencesKey("ch_nav_enabled")
        val CH_NAV_UP_SKIP = intPreferencesKey("ch_nav_up_skip")
        val CH_NAV_DOWN_SKIP = intPreferencesKey("ch_nav_down_skip")
        val REMOTE_SHORTCUT_BINDINGS = stringSetPreferencesKey("remote_shortcut_bindings")
        // Player buttons (PlayerRemoteOptions): unset means the player's own behaviour.
        val PLAYER_HOLD_OK = stringPreferencesKey("player_hold_ok")
        val PLAYER_SHORT_OK = stringPreferencesKey("player_short_ok")
        val PLAYER_SWAP_CHANNEL_KEYS = booleanPreferencesKey("player_swap_channel_keys")
        val PLAYER_NUMBER_KEYS_TUNE = booleanPreferencesKey("player_number_keys_tune")
        // Manual panel-width adjustment (v4.3.x): per section (Live/Movies/Series) a master toggle plus
        // one percentage per panel (category rail · item list/grid · preview). 100 = stock width; the
        // three are normalized across the row, so they always fill the screen. See PanelWidths.kt.
        val PANEL_W_LIVE_ON = booleanPreferencesKey("panel_w_live_on")
        val PANEL_W_LIVE_CAT = intPreferencesKey("panel_w_live_cat")
        val PANEL_W_LIVE_LIST = intPreferencesKey("panel_w_live_list")
        val PANEL_W_LIVE_PREVIEW = intPreferencesKey("panel_w_live_preview")
        val LIVE_STAGE_SHEET = intPreferencesKey("live_stage_sheet")
        val LIVE_STAGE_LIST = intPreferencesKey("live_stage_list")
        val LIVE_STAGE_PREVIEW = intPreferencesKey("live_stage_preview")
        val PANEL_W_MOVIES_ON = booleanPreferencesKey("panel_w_movies_on")
        val PANEL_W_MOVIES_CAT = intPreferencesKey("panel_w_movies_cat")
        val PANEL_W_MOVIES_LIST = intPreferencesKey("panel_w_movies_list")
        val PANEL_W_MOVIES_PREVIEW = intPreferencesKey("panel_w_movies_preview")
        val PANEL_W_SERIES_ON = booleanPreferencesKey("panel_w_series_on")
        val PANEL_W_SERIES_CAT = intPreferencesKey("panel_w_series_cat")
        val PANEL_W_SERIES_LIST = intPreferencesKey("panel_w_series_list")
        val PANEL_W_SERIES_PREVIEW = intPreferencesKey("panel_w_series_preview")
        // Cinematic only: how tall the detail block is, as a percentage of the screen. Deliberately
        // NOT part of the three panel-width shares — those add up to 100 across one row, and a
        // height has no business competing with two widths for the same budget.
        val CINEMATIC_DETAILS_MOVIES = intPreferencesKey("cinematic_details_movies")
        val CINEMATIC_DETAILS_SERIES = intPreferencesKey("cinematic_details_series")
        val CINEMATIC_SHEET_MOVIES = intPreferencesKey("cinematic_sheet_movies")
        val CINEMATIC_SHEET_SERIES = intPreferencesKey("cinematic_sheet_series")
        // Guide's two-column split (pinned channels · scrollable EPG timeline).
        val GUIDE_WIDTH_ON = booleanPreferencesKey("guide_width_on")
        val GUIDE_WIDTH_CHANNELS = intPreferencesKey("guide_width_channels")
        val GUIDE_WIDTH_EPG = intPreferencesKey("guide_width_epg")
        // "Browsing & lists" — two independent per-section toggles (Live TV / Movies / Series).
        //
        // REMEMBER_LAST_*  = remember last ITEM. OFF (default) = switching category resets the browse list
        //                    to the top; ON = each category keeps its own scroll position. The Live one
        //                    also gates lastLiveChannelId (the focused-channel restore on re-entry).
        // REMEMBER_CAT_*   = remember last CATEGORY. ON (default) = reopening the section lands on the
        //                    category you left; OFF = always start on All. Live TV has always behaved this
        //                    way; Movies/Series gained the same persistence alongside the toggle.
        val REMEMBER_LAST_LIVE = booleanPreferencesKey("remember_last_live")
        val REMEMBER_LAST_MOVIES = booleanPreferencesKey("remember_last_movies")
        val REMEMBER_LAST_SERIES = booleanPreferencesKey("remember_last_series")
        val REMEMBER_CAT_LIVE = booleanPreferencesKey("remember_cat_live")
        val REMEMBER_CAT_MOVIES = booleanPreferencesKey("remember_cat_movies")
        val REMEMBER_CAT_SERIES = booleanPreferencesKey("remember_cat_series")
        // Live TV categories: show the built-in All channels / Catch-up entries. Default ON = the full list.
        val LIVE_SHOW_ALL_RAIL = booleanPreferencesKey("live_show_all_rail")
        val LIVE_SHOW_CATCHUP_RAIL = booleanPreferencesKey("live_show_catchup_rail")
        val LAST_MOVIES_CATEGORY = stringPreferencesKey("last_movies_category")
        val LAST_SERIES_CATEGORY = stringPreferencesKey("last_series_category")
        val GUIDE_FOLLOWS_LIVE_CATEGORY = booleanPreferencesKey("guide_follows_live_category")

        /**
         * Written while browsing, so kept in their own store rather than the settings file. These four
         * are the values the whole device shared; each profile now keeps its own copy beside them
         * (see profilePositionFlow), and these are read only by a profile that has none yet.
         */
        val POSITIONS: List<Preferences.Key<*>> = listOf(LAST_LIVE_CHANNEL, LAST_LIVE_CATEGORY, LAST_MOVIES_CATEGORY, LAST_SERIES_CATEGORY)
        // Background picture (Glass & background). bg_image_path holds the absolute path of the image we
        // COPIED into app-private storage (so a USB unplug or source-folder delete never blanks it);
        // blank = no background (feature off, panels stay solid). glass_scope is Glass on (any bit set,
        // see GlassConfig.ON_BITS) or off (0); glass_alpha is the fill alpha in 0..100.
        val BG_IMAGE_PATH = stringPreferencesKey("bg_image_path")
        val GLASS_SCOPE = intPreferencesKey("glass_scope")
        val GLASS_ALPHA = intPreferencesKey("glass_alpha")
        // Glass & background (2 Oct): what sits behind every screen. Unset = the user's look before
        // this existed (see BackgroundConfig.resolve). Darken / Blur / Accent light are overrides of the
        // picture look's own defaults and are cleared when the look changes.
        val BG_STYLE = stringPreferencesKey("bg_style")
        val BG_LOOK = stringPreferencesKey("bg_look")
        val BG_DIM = intPreferencesKey("bg_dim")
        val BG_BLUR = intPreferencesKey("bg_blur")
        val BG_ACCENT_LIGHT = booleanPreferencesKey("bg_accent_light")

        // When the last successful backup was written, how big it was, and whether it was encrypted.
        // Nothing recorded this before: the Backup screen could say what a backup *would* contain but
        // never when one last happened, so "am I backed up?" had no answer anywhere in either app.
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_BYTES = longPreferencesKey("last_backup_bytes")
        val LAST_BACKUP_ENCRYPTED = booleanPreferencesKey("last_backup_encrypted")
        val LAST_BACKUP_PATH = stringPreferencesKey("last_backup_path")

        // When an update check last got an answer from GitHub (About's "Checked today, 20:12").
        val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
    }

    /**
     * The last successful backup — `null` until one has been written on this device.
     *
     * Deliberately not derived from the file on disk: the file lives wherever the user chose, often a
     * USB drive that is not plugged in, so its absence says nothing about whether a backup was taken.
     */
    data class LastBackup(val at: Long, val bytes: Long, val encrypted: Boolean, val path: String)

    val lastBackup: Flow<LastBackup?> = prefsFlow { prefs ->
        prefs[Keys.LAST_BACKUP_AT]?.takeIf { it > 0 }?.let { at ->
            LastBackup(
                at = at,
                bytes = prefs[Keys.LAST_BACKUP_BYTES] ?: 0L,
                encrypted = prefs[Keys.LAST_BACKUP_ENCRYPTED] ?: false,
                // Blank for a backup written before the path was recorded — the date is still true,
                // so the row is simply left out rather than the whole record thrown away.
                path = prefs[Keys.LAST_BACKUP_PATH].orEmpty(),
            )
        }
    }

    /** Called by [tv.own.owntv.core.backup.BackupManager] only after an export has actually landed. */
    suspend fun recordBackup(at: Long, bytes: Long, encrypted: Boolean, path: String) {
        context.dataStore.edit {
            it[Keys.LAST_BACKUP_AT] = at
            it[Keys.LAST_BACKUP_BYTES] = bytes
            it[Keys.LAST_BACKUP_ENCRYPTED] = encrypted
            it[Keys.LAST_BACKUP_PATH] = path
        }
    }

    /** The last update check that got an answer (up to date or a new version); `null` until one has. */
    val lastUpdateCheckAt: Flow<Long?> = prefsFlow { it[Keys.LAST_UPDATE_CHECK_AT]?.takeIf { at -> at > 0 } }

    /** Called by [tv.own.owntv.core.update.UpdateManager] only when a check succeeded. */
    suspend fun recordUpdateCheck(at: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK_AT] = at }
    }

    // --- Live TV: remember the last focused channel so reopening lands focus back on it (per profile) ---
    val lastLiveChannelId: Flow<Long> = profilePositionFlow(Keys.LAST_LIVE_CHANNEL).map { it ?: -1L }
    suspend fun setLastLiveChannelId(id: Long) = setProfilePosition(Keys.LAST_LIVE_CHANNEL, id)

    // --- Startup: per-profile landing (v4.0.0). Falls back to the legacy global resume toggle for existing
    //     users (so "Resume last channel = On" keeps working until they pick a per-profile mode). ---
    fun startupMode(profileId: Long): Flow<StartupMode> = prefsFlow { prefs ->
        prefs[stringPreferencesKey("startup_mode_$profileId")]?.let { runCatching { StartupMode.valueOf(it) }.getOrNull() }
            ?: if (prefs[Keys.RESUME_LAST_CHANNEL] == true) StartupMode.LAST_CHANNEL else StartupMode.HOME
    }
    suspend fun setStartupMode(profileId: Long, mode: StartupMode) {
        context.dataStore.edit { it[stringPreferencesKey("startup_mode_$profileId")] = mode.name }
    }

    fun startupChannel(profileId: Long): Flow<StartupChannelRef?> =
        parsedFlow(stringPreferencesKey("startup_channel_$profileId")) { StartupChannelRef.fromJson(it) }

    suspend fun setStartupChannel(profileId: Long, channel: StartupChannelRef?) {
        context.dataStore.edit { prefs ->
            val key = stringPreferencesKey("startup_channel_$profileId")
            if (channel == null) prefs.remove(key) else prefs[key] = channel.toJson().toString()
        }
    }

    suspend fun setSpecificStartupChannel(profileId: Long, channel: StartupChannelRef) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("startup_channel_$profileId")] = channel.toJson().toString()
            prefs[stringPreferencesKey("startup_mode_$profileId")] = StartupMode.SPECIFIC_CHANNEL.name
        }
    }

    /** Whether a category the provider adds on a later resync is hidden automatically. Same across
     *  Live/Movies/Series for a profile — there's no reason to want it to differ by section. */
    fun hideNewCategoriesDefault(profileId: Long): Flow<Boolean> = prefsFlow { prefs ->
        prefs[booleanPreferencesKey("hide_new_categories_$profileId")] ?: false
    }

    suspend fun setHideNewCategoriesDefault(profileId: Long, hidden: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("hide_new_categories_$profileId")] = hidden }
    }

    /**
     * Whether this profile may download at all. Off takes every Download action away from the
     * profile's menus and pages, and [tv.own.owntv.core.download.DownloadManager.enqueue] refuses
     * it too, so no path is left that still fills the box.
     */
    fun downloadsAllowed(profileId: Long): Flow<Boolean> = prefsFlow { it[downloadsAllowedKey(profileId)] ?: true }

    suspend fun setDownloadsAllowed(profileId: Long, allowed: Boolean) {
        context.dataStore.edit { it[downloadsAllowedKey(profileId)] = allowed }
    }

    /** [downloadsAllowed] for the profile in use, following profile switches. */
    val activeDownloadsAllowed: Flow<Boolean> = context.dataStore.data
        .map { it[downloadsAllowedKey(it[Keys.ACTIVE_PROFILE] ?: -1L)] ?: true }
        .distinctUntilChanged()

    private fun downloadsAllowedKey(profileId: Long) = booleanPreferencesKey("$DOWNLOADS_ALLOWED_PREFIX$profileId")

    // --- Home: per-profile row order / visibility / hero filters. ---
    private fun homeConfigKey(profileId: Long) = stringPreferencesKey("home_config_$profileId")

    fun homeConfig(profileId: Long): Flow<HomeConfig> = parsedFlow(homeConfigKey(profileId)) { HomeConfig.fromJson(it) }

    suspend fun updateHomeConfig(profileId: Long, transform: (HomeConfig) -> HomeConfig) {
        context.dataStore.edit { prefs ->
            val key = homeConfigKey(profileId)
            val next = transform(HomeConfig.fromJson(prefs[key]))
            if (next == HomeConfig()) prefs.remove(key) else prefs[key] = next.toJson().toString()
        }
    }

    // --- Sports: one blob per profile, like Home's (see [tv.own.owntv.core.sports.SportsConfig]). ---
    private fun sportsConfigKey(profileId: Long) = stringPreferencesKey("$SPORTS_CONFIG_PREFIX$profileId")

    fun sportsConfig(profileId: Long): Flow<tv.own.owntv.core.sports.SportsConfig> =
        parsedFlow(sportsConfigKey(profileId)) { tv.own.owntv.core.sports.SportsConfig.fromJson(it) }

    /** The active profile's sports settings, following profile switches. */
    val activeSportsConfig: Flow<tv.own.owntv.core.sports.SportsConfig> = context.dataStore.data
        .map { it[sportsConfigKey(it[Keys.ACTIVE_PROFILE] ?: -1L)] }
        .distinctUntilChanged()
        .map { tv.own.owntv.core.sports.SportsConfig.fromJson(it) }

    suspend fun updateSportsConfig(profileId: Long, transform: (tv.own.owntv.core.sports.SportsConfig) -> tv.own.owntv.core.sports.SportsConfig) {
        context.dataStore.edit { prefs ->
            val key = sportsConfigKey(profileId)
            val next = transform(tv.own.owntv.core.sports.SportsConfig.fromJson(prefs[key]))
            if (next == tv.own.owntv.core.sports.SportsConfig()) prefs.remove(key) else prefs[key] = next.toJson().toString()
        }
    }

    // --- Startup: auto-open the last-watched live channel (default OFF) — legacy, now migrated to startupMode ---
    val resumeLastChannel: Flow<Boolean> = prefsFlow { it[Keys.RESUME_LAST_CHANNEL] ?: false }
    suspend fun setResumeLastChannel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RESUME_LAST_CHANNEL] = enabled }
    }

    // --- Remember the last selected category so reopening a section lands where you left off — each
    //     profile its own. Written by each section's view model (debounced), read once on restore. ---
    val lastLiveCategory: Flow<String> = profilePositionFlow(Keys.LAST_LIVE_CATEGORY).map { it ?: "" }
    suspend fun setLastLiveCategory(key: String) = setProfilePosition(Keys.LAST_LIVE_CATEGORY, key)
    val lastMoviesCategory: Flow<String> = profilePositionFlow(Keys.LAST_MOVIES_CATEGORY).map { it ?: "" }
    suspend fun setLastMoviesCategory(key: String) = setProfilePosition(Keys.LAST_MOVIES_CATEGORY, key)
    val lastSeriesCategory: Flow<String> = profilePositionFlow(Keys.LAST_SERIES_CATEGORY).map { it ?: "" }
    suspend fun setLastSeriesCategory(key: String) = setProfilePosition(Keys.LAST_SERIES_CATEGORY, key)

    // --- "TV Guide follows Live TV's category" (default off: the Guide keeps a category of its own). On,
    //     the Guide opens on Live TV's remembered category and a category picked there becomes it. ---
    val guideFollowsLiveCategory: Flow<Boolean> = prefsFlow { it[Keys.GUIDE_FOLLOWS_LIVE_CATEGORY] ?: false }
    suspend fun setGuideFollowsLiveCategory(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GUIDE_FOLLOWS_LIVE_CATEGORY] = enabled }
    }

    // --- Per-section "remember last CATEGORY" (default ON each — Live TV's long-standing behaviour,
    //     now also available for Movies/Series). OFF = the section always opens on All. ---
    val rememberCategoryLive: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_CAT_LIVE] ?: true }
    suspend fun setRememberCategoryLive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_CAT_LIVE] = enabled }
    }
    val rememberCategoryMovies: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_CAT_MOVIES] ?: true }
    suspend fun setRememberCategoryMovies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_CAT_MOVIES] = enabled }
    }
    val rememberCategorySeries: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_CAT_SERIES] ?: true }
    suspend fun setRememberCategorySeries(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_CAT_SERIES] = enabled }
    }

    // --- Built-in Live TV categories (default ON each — the full list). ---
    val liveBuiltIns: Flow<tv.own.owntv.core.live.LiveBuiltIns> = prefsFlow {
        tv.own.owntv.core.live.LiveBuiltIns(showAll = it[Keys.LIVE_SHOW_ALL_RAIL] ?: true, showCatchup = it[Keys.LIVE_SHOW_CATCHUP_RAIL] ?: true)
    }
    suspend fun setLiveShowAllRail(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_SHOW_ALL_RAIL] = enabled }
    }
    suspend fun setLiveShowCatchupRail(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_SHOW_CATCHUP_RAIL] = enabled }
    }

    // --- Per-section "remember last ITEM per category" (default OFF each).
    //     OFF = switching category resets the browse list to the top; ON = each category keeps its own
    //     scroll position. The Live toggle additionally gates the lastLiveChannelId restore on re-entry. ---
    val rememberLastLive: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_LAST_LIVE] ?: false }
    suspend fun setRememberLastLive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_LAST_LIVE] = enabled }
    }
    val rememberLastMovies: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_LAST_MOVIES] ?: false }
    suspend fun setRememberLastMovies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_LAST_MOVIES] = enabled }
    }
    val rememberLastSeries: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_LAST_SERIES] ?: false }
    suspend fun setRememberLastSeries(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_LAST_SERIES] = enabled }
    }

    // --- Search: recent search terms (most-recent first, capped). Stored as one newline-joined string
    //     so no schema/table is needed; blank entries are ignored on read. ---
    val recentSearches: Flow<List<String>> = parsedFlow(Keys.RECENT_SEARCHES) { raw ->
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** Push a query to the top of the recents (case-insensitive dedup), capped at 12 entries. */
    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_SEARCHES]?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val next = (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(12)
            prefs[Keys.RECENT_SEARCHES] = next.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        context.dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }
    }

    // --- Appearance: animation level (perf control for low-end boxes) ---
    val animationLevel: Flow<tv.own.owntv.core.theme.AnimationLevel> = prefsFlow { prefs ->
        prefs[Keys.ANIMATION_LEVEL]?.let { runCatching { tv.own.owntv.core.theme.AnimationLevel.valueOf(it) }.getOrNull() }
            ?: tv.own.owntv.core.theme.AnimationLevel.FULL
    }

    suspend fun setAnimationLevel(level: tv.own.owntv.core.theme.AnimationLevel) {
        context.dataStore.edit { it[Keys.ANIMATION_LEVEL] = level.name }
    }

    // --- Solid UI ambient radiance (the setup-wizard-style aura) ---
    // Opt-in: upgrading users keep the familiar solid appearance until they choose this effect.
    val ambientGlowEnabled: Flow<Boolean> = prefsFlow { it[Keys.AMBIENT_GLOW_ENABLED] ?: false }

    suspend fun setAmbientGlowEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMBIENT_GLOW_ENABLED] = enabled }
    }

    val ambientGlowPulse: Flow<Boolean> = prefsFlow { it[Keys.AMBIENT_GLOW_PULSE] ?: true }

    suspend fun setAmbientGlowPulse(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMBIENT_GLOW_PULSE] = enabled }
    }

    // --- Weather chip (top bar): show/hide + manual location override for VPN users ---

    /** Show the weather chip in the top bar (default ON). */
    val weatherEnabled: Flow<Boolean> = prefsFlow { it[Keys.WEATHER_ENABLED] ?: true }

    suspend fun setWeatherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WEATHER_ENABLED] = enabled }
    }

    /**
     * Manual weather location. Blank (default) = auto-detect from public IP. Otherwise a city name
     * (geocoded via Open-Meteo) or a raw "lat,lon" pair. Lets users fix the wrong-city behaviour
     * they see behind a VPN, where IP geolocation resolves to the VPN server's city.
     */
    val weatherLocation: Flow<String> = prefsFlow { it[Keys.WEATHER_LOCATION] ?: "" }

    suspend fun setWeatherLocation(location: String) {
        context.dataStore.edit { it[Keys.WEATHER_LOCATION] = location.trim() }
    }

    /** Show the weather temperature in Fahrenheit instead of Celsius (default °C). */
    val weatherFahrenheit: Flow<Boolean> = prefsFlow { it[Keys.WEATHER_FAHRENHEIT] ?: false }

    suspend fun setWeatherFahrenheit(fahrenheit: Boolean) {
        context.dataStore.edit { it[Keys.WEATHER_FAHRENHEIT] = fahrenheit }
    }

    /**
     * Colours of the top-right clock's three parts (TV Settings › Appearance › Date, time & weather).
     * Each is blank (the shipped look), [ClockColors.ACCENT] (follows the accent) or a "#RRGGBB" hex.
     */
    val clockColors: Flow<ClockColors> = prefsFlow {
        ClockColors(it[Keys.CLOCK_TIME_COLOR] ?: "", it[Keys.CLOCK_DATE_COLOR] ?: "", it[Keys.CLOCK_WEATHER_COLOR] ?: "")
    }

    suspend fun setClockColor(part: ClockPart, value: String) {
        val key = when (part) {
            ClockPart.TIME -> Keys.CLOCK_TIME_COLOR
            ClockPart.DATE -> Keys.CLOCK_DATE_COLOR
            ClockPart.WEATHER -> Keys.CLOCK_WEATHER_COLOR
        }
        context.dataStore.edit { it[key] = value.trim() }
    }

    // --- TMDB metadata enrichment ---
    // One provider, three configs. Enrichment is opt-outable via the master toggle; the two advanced
    // fields (own key / self-host URL) override the default caching Worker when set.

    /** Metadata source mode. Defaults to Provider+TMDB; back-compat: an old boolean master
     *  toggle maps false→Provider, true→Provider+TMDB when no explicit mode is stored yet. */
    val metadataMode: Flow<tv.own.owntv.core.metadata.MetadataMode> = prefsFlow { p ->
        parseMetadataMode(p)
    }

    private fun parseMetadataMode(p: Preferences): tv.own.owntv.core.metadata.MetadataMode {
        p[Keys.METADATA_MODE]?.let { raw ->
            runCatching { tv.own.owntv.core.metadata.MetadataMode.valueOf(raw) }.getOrNull()?.let { return it }
        }
        // No explicit mode yet — derive from the legacy boolean toggle.
        return if (p[Keys.METADATA_ENABLED] == false) tv.own.owntv.core.metadata.MetadataMode.PROVIDER
        else tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB
    }

    suspend fun setMetadataMode(mode: tv.own.owntv.core.metadata.MetadataMode) {
        context.dataStore.edit {
            it[Keys.METADATA_MODE] = mode.name
            it[Keys.METADATA_ENABLED] = mode.enrich // keep legacy key coherent for older readers
        }
    }

    /** Tier 2 — the user's own TMDB v3 API key; blank = don't call TMDB directly. */
    val tmdbApiKey: Flow<String> = prefsFlow { it[Keys.TMDB_API_KEY] ?: "" }

    suspend fun setTmdbApiKey(key: String) {
        context.dataStore.edit { it[Keys.TMDB_API_KEY] = key.trim() }
    }

    /** Tier 3 — a custom TMDB-shaped metadata server base URL; blank = don't self-host. */
    val metadataServerUrl: Flow<String> = prefsFlow { it[Keys.METADATA_SERVER_URL] ?: "" }

    suspend fun setMetadataServerUrl(url: String) {
        context.dataStore.edit { it[Keys.METADATA_SERVER_URL] = url.trim() }
    }

    val openSubtitlesApiKey: Flow<String> = prefsFlow { it[Keys.OPEN_SUBTITLES_API_KEY] ?: "" }
    suspend fun setOpenSubtitlesApiKey(key: String) { context.dataStore.edit { it[Keys.OPEN_SUBTITLES_API_KEY] = key.trim() } }

    val openSubtitlesServerUrl: Flow<String> = prefsFlow { it[Keys.OPEN_SUBTITLES_SERVER_URL] ?: "" }
    suspend fun setOpenSubtitlesServerUrl(url: String) { context.dataStore.edit { it[Keys.OPEN_SUBTITLES_SERVER_URL] = url.trim() } }

    suspend fun currentOpenSubtitlesApiKey(): String = context.dataStore.data.first()[Keys.OPEN_SUBTITLES_API_KEY] ?: ""
    suspend fun currentOpenSubtitlesServerUrl(): String = context.dataStore.data.first()[Keys.OPEN_SUBTITLES_SERVER_URL] ?: ""

    /**
     * TMDB content language. Blank = TMDB's own default (en-US) — the pre-existing behaviour, so an
     * upgrade never silently changes anyone's metadata. "auto" = follow the device locale, resolved at
     * call time by [tv.own.owntv.core.metadata.MetadataConfig.resolvedLanguage].
     */
    val metadataLanguage: Flow<String> = prefsFlow { it[Keys.METADATA_LANGUAGE] ?: "" }

    suspend fun setMetadataLanguage(code: String) {
        context.dataStore.edit { it[Keys.METADATA_LANGUAGE] = code.trim() }
    }

    /** Matcher generation the cached "no match" rows were written under (0 = never healed). */
    suspend fun metadataMatchHealVersion(): Int =
        context.dataStore.data.first()[Keys.METADATA_MATCH_HEAL_VERSION] ?: 0

    suspend fun setMetadataMatchHealVersion(version: Int) {
        context.dataStore.edit { it[Keys.METADATA_MATCH_HEAL_VERSION] = version }
    }

    /** Live snapshot of the metadata settings as one object (consumed by TmdbProvider). */
    val metadataConfigFlow: Flow<tv.own.owntv.core.metadata.MetadataConfig> = prefsFlow { p ->
        tv.own.owntv.core.metadata.MetadataConfig(
            mode = parseMetadataMode(p),
            tmdbApiKey = p[Keys.TMDB_API_KEY] ?: "",
            customServerUrl = p[Keys.METADATA_SERVER_URL] ?: "",
            language = p[Keys.METADATA_LANGUAGE] ?: "",
        )
    }

    /**
     * Hot snapshot of [metadataConfigFlow], kept warm by one long-lived collector.
     *
     * Measured on a real TV: a fresh `first()` on a DataStore flow costs **74–128 ms**, and the
     * metadata layer reads this on every resolve — once per episode focus, once per season switch,
     * once per browsed title. That dominated everything else in the path, database queries included.
     * One collector turns each of those into an in-memory field read.
     */
    private val metadataConfigState: StateFlow<tv.own.owntv.core.metadata.MetadataConfig?> =
        metadataConfigFlow.stateIn(repoScope, SharingStarted.Eagerly, null)

    /** Same treatment, for the other value the metadata path reads on every resolve. */
    private val activeProfileIdState: StateFlow<Long?> =
        prefsFlow { it[Keys.ACTIVE_PROFILE] ?: -1L }.stateIn(repoScope, SharingStarted.Eagerly, null)

    /**
     * One-shot read of the current metadata config (used by TmdbProvider per call). Served from the
     * warm snapshot; falls back to a direct read only before the collector's first emission.
     */
    suspend fun metadataConfig(): tv.own.owntv.core.metadata.MetadataConfig =
        metadataConfigState.value ?: metadataConfigFlow.first()

    /** Cheap counterpart to collecting [activeProfileId], for the same per-resolve hot path. */
    suspend fun activeProfileIdNow(): Long = activeProfileIdState.value ?: activeProfileId.first()


    // --- Catch-up (archive) playback ---

    /** Which timezone to format Xtream timeshift URLs in. Panels run on the server's local time, so
     *  **Automatic** — the zone the panel reports for itself at sync, else the device's — is the default
     *  (and what an unset key reads as); Device and a manual UTC offset stay as explicit choices. */
    enum class CatchupTimezone { AUTOMATIC, DEVICE, MANUAL }

    /** Manual UTC offset bounds, in minutes. */
    val catchupOffsetRangeMinutes: IntRange = -12 * 60..14 * 60

    /** One press of the manual offset's − / +: a quarter hour, so +05:30 and +05:45 are reachable. */
    val catchupOffsetStepMinutes: Int = 15

    /**
     * The offsets a list picker offers (the per-playlist choice): every whole hour plus the half- and
     * quarter-hour offsets real zones use. A plain 15-minute sweep would be 105 rows, mostly offsets no
     * clock anywhere keeps.
     */
    val catchupOffsetChoicesMinutes: List<Int> = (
        (catchupOffsetRangeMinutes step 60).toList() +
            listOf(-9 * 60 - 30, -3 * 60 - 30, 3 * 60 + 30, 4 * 60 + 30, 5 * 60 + 30, 5 * 60 + 45,
                6 * 60 + 30, 8 * 60 + 45, 9 * 60 + 30, 10 * 60 + 30, 12 * 60 + 45, 13 * 60 + 45)
        ).sorted()

    val catchupTimezone: Flow<CatchupTimezone> = prefsFlow { prefs ->
        prefs[Keys.CATCHUP_TZ]?.let { runCatching { CatchupTimezone.valueOf(it) }.getOrNull() } ?: CatchupTimezone.AUTOMATIC
    }

    /** Manual mode's offset from UTC, in minutes (0 = UTC, the previous default). */
    val catchupOffsetMinutes: Flow<Int> = prefsFlow { it[Keys.CATCHUP_OFFSET_MIN] ?: 0 }

    suspend fun setCatchupTimezone(mode: CatchupTimezone) {
        context.dataStore.edit { it[Keys.CATCHUP_TZ] = mode.name }
    }

    /** Which player takes a catch-up archive programme. Archives are mid-GOP MPEG-TS, the hardest thing
     *  the in-app engines have to swallow, so handing them to VLC/MX is a genuine escape hatch — but the
     *  in-app player keeps the HUD, resume and engine toggle, so **INTERNAL stays the default**. */
    enum class CatchupPlayer { ASK, INTERNAL, EXTERNAL }

    val catchupPlayer: Flow<CatchupPlayer> = prefsFlow { prefs ->
        prefs[Keys.CATCHUP_PLAYER]?.let { runCatching { CatchupPlayer.valueOf(it) }.getOrNull() } ?: CatchupPlayer.INTERNAL
    }

    suspend fun setCatchupPlayer(mode: CatchupPlayer) {
        context.dataStore.edit { it[Keys.CATCHUP_PLAYER] = mode.name }
    }

    suspend fun setCatchupOffsetMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.CATCHUP_OFFSET_MIN] = minutes.coerceIn(catchupOffsetRangeMinutes) }
    }

    // --- EPG time offset ---

    /** How far the guide may be shifted, in minutes (±12/14 h, in 30-minute steps from the UI). */
    val epgOffsetRangeMinutes: IntRange = -12 * 60..14 * 60

    /**
     * Global guide shift, in minutes (0 = off). Some feeds publish their XMLTV in a timezone that
     * isn't the one the channels actually air in; this moves every programme by a fixed amount.
     * A per-channel override in the channel's long-press menu wins over this — that's what a
     * mixed East/West lineup on a single guide needs, since one global shift can only fix one half.
     */
    val epgOffsetMinutes: Flow<Int> = prefsFlow { it[Keys.EPG_OFFSET_MIN] ?: 0 }

    /**
     * How many days of guide to keep, forward from now.
     *
     * Replaces a hard-coded 48-hour horizon that threw away everything beyond two days even when the
     * provider had sent a fortnight — so the grid could only ever scroll two days, and two days after
     * the last sync the guide was simply empty. That is the "EPG stopped working" report, with
     * nothing actually broken.
     *
     * One value, read by three things that must agree: what the sync stores, what the prune keeps,
     * and how far the grid can scroll. Costs roughly 200,000 rows a day on a large lineup, which is
     * why the retention split landed first.
     */
    val guideDaysToKeep: Flow<Int> = prefsFlow {
        (it[Keys.GUIDE_DAYS_TO_KEEP] ?: GuideRetention.DEFAULT_DAYS)
            .coerceIn(GuideRetention.MIN_DAYS, GuideRetention.MAX_DAYS)
    }

    suspend fun setGuideDaysToKeep(days: Int) {
        context.dataStore.edit { it[Keys.GUIDE_DAYS_TO_KEEP] = days.coerceIn(GuideRetention.MIN_DAYS, GuideRetention.MAX_DAYS) }
    }

    suspend fun setEpgOffsetMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.EPG_OFFSET_MIN] = minutes.coerceIn(epgOffsetRangeMinutes) }
    }

    /**
     * The timezone [source]'s catch-up/timeshift URLs are formatted in: its own choice when it has one
     * (v44), else the global one — the panel's reported zone (v49) or the device's for Automatic, the
     * device's for Device, a fixed UTC offset for Manual.
     */
    suspend fun resolveCatchupTimeZone(source: tv.own.owntv.core.database.entity.SourceEntity?): java.util.TimeZone =
        SourceOverrides.catchupTimeZoneOf(source) ?: when (catchupTimezone.first()) {
            CatchupTimezone.AUTOMATIC -> SourceOverrides.panelTimeZoneOf(source) ?: java.util.TimeZone.getDefault()
            CatchupTimezone.DEVICE -> java.util.TimeZone.getDefault()
            CatchupTimezone.MANUAL -> java.util.SimpleTimeZone(catchupOffsetMinutes.first() * 60_000, "catchup")
        }

    /** Automatically check GitHub Releases for a newer version shortly after launch. */
    val updateCheckOnStart: Flow<Boolean> = prefsFlow { it[Keys.UPDATE_CHECK_ON_START] ?: true }

    suspend fun setUpdateCheckOnStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UPDATE_CHECK_ON_START] = enabled }
    }

    // --- Resume behavior for movies/episodes with a saved position ---

    enum class ResumeMode { AUTO, ASK, NEVER }

    val resumeMode: Flow<ResumeMode> = prefsFlow { prefs ->
        prefs[Keys.RESUME_MODE]?.let { runCatching { ResumeMode.valueOf(it) }.getOrNull() } ?: ResumeMode.ASK
    }

    suspend fun setResumeMode(mode: ResumeMode) {
        context.dataStore.edit { it[Keys.RESUME_MODE] = mode.name }
    }

    /** Coming back to a channel whose saved copy was kept: ask, resume where you were, or go live. */
    val timeshiftResumeMode: Flow<ResumeMode> = prefsFlow { prefs ->
        prefs[Keys.TIMESHIFT_RESUME_MODE]?.let { runCatching { ResumeMode.valueOf(it) }.getOrNull() } ?: ResumeMode.ASK
    }

    suspend fun setTimeshiftResumeMode(mode: ResumeMode) {
        context.dataStore.edit { it[Keys.TIMESHIFT_RESUME_MODE] = mode.name }
    }

    // --- Nav menu customization (v4.3.0) ---
    // DYNAMIC: the side icons adapt to what the active playlist actually contains (Home & Settings
    // always show; Live/Guide show when there are channels; Movies/Series show when their content
    // exists; Downloads shows when Movies OR Series exist since Live has no download). STATIC: the
    // user picks exactly which icons to hide. Default STATIC (all visible) → existing users see no
    // change until they opt into Dynamic.

    enum class NavMenuMode { DYNAMIC, STATIC }

    val navMenuMode: Flow<NavMenuMode> = prefsFlow { prefs ->
        prefs[Keys.NAV_MENU_MODE]?.let { runCatching { NavMenuMode.valueOf(it) }.getOrNull() } ?: NavMenuMode.STATIC
    }

    suspend fun setNavMenuMode(mode: NavMenuMode) {
        context.dataStore.edit { it[Keys.NAV_MENU_MODE] = mode.name }
    }

    /** The icon and logo colour the user chose. It reaches the launcher on the next restart. */
    val appIcon: Flow<AppIcon> = prefsFlow { prefs -> AppIcon.fromStored(prefs[Keys.APP_ICON]) }

    suspend fun setAppIcon(icon: AppIcon) {
        context.dataStore.edit { it[Keys.APP_ICON] = icon.name }
    }

    /** Names of the `MainSection` browse items the user has hidden (STATIC mode). */
    val navMenuHidden: Flow<Set<String>> = prefsFlow { it[Keys.NAV_MENU_HIDDEN] ?: emptySet() }

    /** Replace the whole hidden set. Empty = all visible. */
    suspend fun setNavMenuHidden(hidden: Set<String>) {
        context.dataStore.edit { prefs ->
            if (hidden.isEmpty()) prefs.remove(Keys.NAV_MENU_HIDDEN) else prefs[Keys.NAV_MENU_HIDDEN] = hidden
        }
    }

    /**
     * Where the navigation rail sits. FLOATING floats over the content and always hides itself; DOCKED
     * (the default: Docked · Compact · Fit) stays on screen and the content makes room for it.
     */
    enum class NavStyle { FLOATING, DOCKED }

    /** What the rail shows: icons only, + names, + counts, + a details line under each name. */
    enum class NavSize { COMPACT, NORMAL, WIDE, EXTRA_WIDE }

    /** Whether the rail fits its items or runs the full height of the screen. */
    enum class NavLength { FIT, FULL }

    /** The floating rail's hide delays, in milliseconds, as the Navigation popup offers them. */
    object NavHideAfter {
        val CHOICES_MS = listOf(500, 1000, 2000, 3000, 4000, 5000)
        const val DEFAULT_MS = 2000
    }

    val navStyle: Flow<NavStyle> = prefsFlow { prefs ->
        prefs[Keys.NAV_STYLE]?.let { runCatching { NavStyle.valueOf(it) }.getOrNull() } ?: NavStyle.DOCKED
    }

    suspend fun setNavStyle(style: NavStyle) {
        context.dataStore.edit { it[Keys.NAV_STYLE] = style.name }
    }

    val navSize: Flow<NavSize> = prefsFlow { prefs ->
        prefs[Keys.NAV_SIZE]?.let { runCatching { NavSize.valueOf(it) }.getOrNull() } ?: NavSize.COMPACT
    }

    suspend fun setNavSize(size: NavSize) {
        context.dataStore.edit { it[Keys.NAV_SIZE] = size.name }
    }

    /**
     * Docked + Compact only: the size the rail opens to over the content while it holds focus, so the
     * names show without the content giving up room. null = Off (it stays icons only). Default Off
     * (a fresh install or an update gets the plain Docked rail).
     */
    val navWiden: Flow<NavSize?> = prefsFlow { prefs ->
        prefs[Keys.NAV_WIDEN]?.let { runCatching { NavSize.valueOf(it) }.getOrNull() }?.takeIf { it != NavSize.COMPACT }
    }

    suspend fun setNavWiden(size: NavSize?) {
        context.dataStore.edit { it[Keys.NAV_WIDEN] = size?.name ?: NAV_WIDEN_OFF }
    }

    /** Phone bottom bar: names under the icons (default) or icons only. */
    val phoneNavLabels: Flow<Boolean> = prefsFlow { it[Keys.PHONE_NAV_LABELS] ?: true }

    suspend fun setPhoneNavLabels(on: Boolean) {
        context.dataStore.edit { it[Keys.PHONE_NAV_LABELS] = on }
    }

    /** Phone bottom bar: slides away while a list scrolls down, back on the way up (default on). */
    val phoneNavHideOnScroll: Flow<Boolean> = prefsFlow { it[Keys.PHONE_NAV_HIDE_ON_SCROLL] ?: true }

    suspend fun setPhoneNavHideOnScroll(on: Boolean) {
        context.dataStore.edit { it[Keys.PHONE_NAV_HIDE_ON_SCROLL] = on }
    }

    /** Channel and title counts in the phone's browse sheets: playlists, categories (default on). */
    val sheetCounts: Flow<Boolean> = prefsFlow { it[Keys.SHEET_COUNTS] ?: true }

    suspend fun setSheetCounts(on: Boolean) {
        context.dataStore.edit { it[Keys.SHEET_COUNTS] = on }
    }

    /** In-app logos: the play triangle in the accent (default) or in the chosen icon's own colour. */
    val brandAccentTriangle: Flow<Boolean> = prefsFlow { it[Keys.BRAND_ACCENT_TRIANGLE] ?: true }

    suspend fun setBrandAccentTriangle(on: Boolean) {
        context.dataStore.edit { it[Keys.BRAND_ACCENT_TRIANGLE] = on }
    }

    val navLength: Flow<NavLength> = prefsFlow { prefs ->
        prefs[Keys.NAV_LENGTH]?.let { runCatching { NavLength.valueOf(it) }.getOrNull() } ?: NavLength.FIT
    }

    suspend fun setNavLength(length: NavLength) {
        context.dataStore.edit { it[Keys.NAV_LENGTH] = length.name }
    }

    /** How long the floating rail waits after focus leaves it before it hides; one of [NavHideAfter.CHOICES_MS]. */
    val navHideAfterMs: Flow<Int> = prefsFlow { prefs ->
        prefs[Keys.NAV_HIDE_AFTER_MS]?.takeIf { it in NavHideAfter.CHOICES_MS } ?: NavHideAfter.DEFAULT_MS
    }

    suspend fun setNavHideAfterMs(ms: Int) {
        if (ms in NavHideAfter.CHOICES_MS) context.dataStore.edit { it[Keys.NAV_HIDE_AFTER_MS] = ms }
    }

    // --- List sorting (per browse section) ---

    /** How a browse section's lists are ordered. RATING (highest provider rating first) applies to
     *  Movies/Series only; Live/EPG never select it. */
    enum class SortMode { PLAYLIST, ALPHA, RATING, DATE_ADDED }

    /** All three browse sections (Live/Movies/Series) default to the playlist/provider's own order — the
     *  natural grouping a user expects right after a sync. A–Z is one tap away (toggleSort). */
    val sortLive: Flow<SortMode> = prefsFlow { parseSort(it[Keys.SORT_LIVE], SortMode.PLAYLIST) }
    val sortMovies: Flow<SortMode> = prefsFlow { parseSort(it[Keys.SORT_MOVIES], SortMode.PLAYLIST) }
    val sortSeries: Flow<SortMode> = prefsFlow { parseSort(it[Keys.SORT_SERIES], SortMode.PLAYLIST) }

    suspend fun setSortLive(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_LIVE] = mode.name }
    }

    suspend fun setSortMovies(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_MOVIES] = mode.name }
    }

    suspend fun setSortSeries(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_SERIES] = mode.name }
    }

    private fun parseSort(raw: String?, default: SortMode): SortMode =
        raw?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: default

    /** The TV Guide's own ordering. LIVE_TV mirrors the Live TV sort; CATCHUP floats archive channels up. */
    enum class GuideSort { ALPHA, PROVIDER, LIVE_TV, CATCHUP, FAVORITES }

    /**
     * How the guide is drawn on a touch screen: the time grid, the "on now" list, or one channel's
     * schedule down the page. Unset means "decide from the screen" — a two-dimensional grid is
     * unusable on a portrait phone and the natural choice on a tablet, so a device that has never
     * been told otherwise picks per orientation rather than being locked to one answer.
     */
    enum class GuideView { GRID, ON_NOW, TIMELINE }
    val guideView: Flow<GuideView?> = prefsFlow { prefs ->
        prefs[Keys.GUIDE_VIEW]?.let { runCatching { GuideView.valueOf(it) }.getOrNull() }
    }
    suspend fun setGuideView(view: GuideView) {
        context.dataStore.edit { it[Keys.GUIDE_VIEW] = view.name }
    }

    /** Guide row height, as a percentage of the standard one. The phone's answer to the TV app's
     *  Guide Column Widths, which has no meaning where the channel column is a fixed strip. */
    val guideDensityPct: Flow<Int> = prefsFlow { it[Keys.GUIDE_DENSITY_PCT] ?: 100 }
    suspend fun setGuideDensityPct(pct: Int) {
        context.dataStore.edit { it[Keys.GUIDE_DENSITY_PCT] = pct.coerceIn(70, 130) }
    }

    /** How Movies & Series browse: the poster wall, or a compact list (more titles at once). */
    enum class VodViewMode { GRID, LIST }
    val vodViewMode: Flow<VodViewMode> = prefsFlow { prefs ->
        prefs[Keys.VOD_VIEW_MODE]?.let { runCatching { VodViewMode.valueOf(it) }.getOrNull() } ?: VodViewMode.GRID
    }
    suspend fun setVodViewMode(mode: VodViewMode) {
        context.dataStore.edit { it[Keys.VOD_VIEW_MODE] = mode.name }
    }

    /**
     * How the Movies & Series screens are framed. SEPARATE is the long-standing three-panel layout
     * (categories, list/grid, preview). CINEMATIC is the default of a fresh install;
     * an update keeps its layout, because [applyStageDefaults] writes SEPARATE where none was stored.
     * CINEMATIC draws the focused title's backdrop behind everything and is grid-only — [vodViewMode]
     * is left untouched while it is on, so switching back restores the user's List choice.
     * One setting for both sections: a layout preference is about how the user likes to browse.
     */
    enum class VodLayout { SEPARATE, CINEMATIC }
    val vodLayout: Flow<VodLayout> = prefsFlow { prefs ->
        prefs[Keys.VOD_LAYOUT]?.let { runCatching { VodLayout.valueOf(it) }.getOrNull() } ?: VodLayout.CINEMATIC
    }
    suspend fun setVodLayout(layout: VodLayout) {
        context.dataStore.edit { it[Keys.VOD_LAYOUT] = layout.name }
    }

    /**
     * Live TV layout. STAGE (default for everyone, updates included) opens the categories as a sheet
     * with Left; SEPARATE keeps a category column on screen beside the channel list and preview.
     */
    enum class LiveLayout { STAGE, SEPARATE }
    val liveLayout: Flow<LiveLayout> = prefsFlow { prefs ->
        prefs[Keys.LIVE_LAYOUT]?.let { runCatching { LiveLayout.valueOf(it) }.getOrNull() } ?: LiveLayout.STAGE
    }
    suspend fun setLiveLayout(layout: LiveLayout) {
        context.dataStore.edit { it[Keys.LIVE_LAYOUT] = layout.name }
    }

    /** What Live TV opens in: the channel list, or the guide grid. Remembered from its toggle. */
    enum class LiveView { LIST, GUIDE }
    val liveView: Flow<LiveView> = prefsFlow { prefs ->
        prefs[Keys.LIVE_VIEW]?.let { runCatching { LiveView.valueOf(it) }.getOrNull() } ?: LiveView.LIST
    }
    suspend fun setLiveView(view: LiveView) {
        context.dataStore.edit { it[Keys.LIVE_VIEW] = view.name }
    }

    /**
     * What a programme reminder does when its moment comes: ask whether to switch, switch
     * straight away, or only say so. [reminderLeadMinutes] is how early that moment is.
     */
    enum class ReminderMode { ASK, SWITCH, NOTIFY }
    val reminderMode: Flow<ReminderMode> = prefsFlow { prefs ->
        prefs[Keys.REMINDER_MODE]?.let { runCatching { ReminderMode.valueOf(it) }.getOrNull() } ?: ReminderMode.ASK
    }
    suspend fun setReminderMode(mode: ReminderMode) {
        context.dataStore.edit { it[Keys.REMINDER_MODE] = mode.name }
    }
    val reminderLeadMinutes: Flow<Int> = prefsFlow { prefs ->
        prefs[Keys.REMINDER_LEAD_MIN]?.takeIf { it in tv.own.owntv.core.reminder.ReminderSchedule.LEAD_CHOICES } ?: tv.own.owntv.core.reminder.ReminderSchedule.DEFAULT_LEAD_MINUTES
    }
    suspend fun setReminderLeadMinutes(minutes: Int) {
        if (minutes in tv.own.owntv.core.reminder.ReminderSchedule.LEAD_CHOICES) context.dataStore.edit { it[Keys.REMINDER_LEAD_MIN] = minutes }
    }

    /**
     * How many posters a row of the Movies/Series grid holds. 0 means "decide from the screen", which
     * is what a device that has never been pinched reports — a phone in portrait, the same phone in
     * landscape and a tablet all want a different number, and one stored count cannot serve all three.
     * A pinch stores the user's answer for the width they pinched at.
     */
    val vodGridColumns: Flow<Int> = prefsFlow { it[Keys.VOD_GRID_COLUMNS] ?: 0 }

    suspend fun setVodGridColumns(columns: Int) {
        context.dataStore.edit { it[Keys.VOD_GRID_COLUMNS] = columns.coerceIn(0, 12) }
    }

    /**
     * How the episode list inside a show is drawn. LIST (default) is the text rows; GRID is a wall of
     * 16:9 episode stills. Global rather than per-series: a layout preference is about how the user
     * likes to browse, and storing it per show would mean setting it again for every show they open.
     */
    val episodeViewMode: Flow<VodViewMode> = prefsFlow { prefs ->
        prefs[Keys.EPISODE_VIEW_MODE]?.let { runCatching { VodViewMode.valueOf(it) }.getOrNull() } ?: VodViewMode.LIST
    }
    suspend fun setEpisodeViewMode(mode: VodViewMode) {
        context.dataStore.edit { it[Keys.EPISODE_VIEW_MODE] = mode.name }
    }

    /** Keep the sound going after the app is left. On by default — it is why the service exists. */
    val backgroundPlayback: Flow<Boolean> = prefsFlow { it[Keys.BACKGROUND_PLAYBACK] ?: true }
    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_PLAYBACK] = enabled }
    }

    /** Shrink the video into a floating window on home. On by default. */
    val pipEnabled: Flow<Boolean> = prefsFlow { it[Keys.PIP_ENABLED] ?: true }
    suspend fun setPipEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP_ENABLED] = enabled }
    }

    /**
     * Refuse to stream over a metered connection. Off by default: a user who has not asked for it
     * must never find their film blocked on a network that was working a moment ago.
     */
    val dataSaver: Flow<Boolean> = prefsFlow { it[Keys.DATA_SAVER] ?: false }
    suspend fun setDataSaver(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DATA_SAVER] = enabled }
    }

    /** How far a swipe travels before it has moved a slider, as a percentage of the default. */
    val gestureSensitivityPct: Flow<Int> = prefsFlow { it[Keys.GESTURE_SENSITIVITY_PCT] ?: 100 }
    suspend fun setGestureSensitivityPct(pct: Int) {
        context.dataStore.edit { it[Keys.GESTURE_SENSITIVITY_PCT] = pct.coerceIn(50, 200) }
    }

    /** Hold downloads until Wi-Fi. Off by default, for the same reason the data saver is. */
    val downloadsWifiOnly: Flow<Boolean> = prefsFlow { it[Keys.DOWNLOADS_WIFI_ONLY] ?: false }
    suspend fun setDownloadsWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DOWNLOADS_WIFI_ONLY] = enabled }
    }

    /**
     * What playing something does to a running download when the playlist allows one stream: ask
     * (the default), pause the download, or keep downloading. "Don't ask again" in that question
     * writes the other two.
     */
    enum class DownloadWatchChoice { ASK, PAUSE, KEEP }

    val downloadWatchChoice: Flow<DownloadWatchChoice> = prefsFlow { prefs ->
        prefs[Keys.DOWNLOAD_WATCH_CHOICE]?.let { runCatching { DownloadWatchChoice.valueOf(it) }.getOrNull() } ?: DownloadWatchChoice.ASK
    }

    suspend fun setDownloadWatchChoice(choice: DownloadWatchChoice) {
        context.dataStore.edit { it[Keys.DOWNLOAD_WATCH_CHOICE] = choice.name }
    }

    /** Read once, for a caller that has to answer before it can start — the queue and the player. */
    suspend fun downloadsWifiOnlyNow(): Boolean = downloadsWifiOnly.first()
    suspend fun dataSaverNow(): Boolean = dataSaver.first()

    // --- The mobility layer (touch hosts only) ---------------------------------------------------

    /** What keeps playing while the user browses: a window they drag, a bar docked above the tabs, or
     *  nothing at all. */
    enum class MiniPlayerStyle { FLOATING, DOCKED, OFF }

    val miniPlayerStyle: Flow<MiniPlayerStyle> = prefsFlow { prefs ->
        prefs[Keys.MINI_PLAYER_STYLE]?.let { runCatching { MiniPlayerStyle.valueOf(it) }.getOrNull() }
            ?: MiniPlayerStyle.FLOATING
    }
    suspend fun setMiniPlayerStyle(style: MiniPlayerStyle) {
        context.dataStore.edit { it[Keys.MINI_PLAYER_STYLE] = style.name }
    }

    /** How big the floating window starts. The user pinches it from there. */
    enum class PipSize { SMALL, MEDIUM, LARGE }

    val pipSize: Flow<PipSize> = prefsFlow { prefs ->
        prefs[Keys.PIP_SIZE]?.let { runCatching { PipSize.valueOf(it) }.getOrNull() } ?: PipSize.MEDIUM
    }
    suspend fun setPipSize(size: PipSize) {
        context.dataStore.edit { it[Keys.PIP_SIZE] = size.name }
    }

    /** Let go of the floating window and it settles against the nearest edge. On by default — a window
     *  left mid-screen covers what the user is browsing. */
    val pipSnap: Flow<Boolean> = prefsFlow { it[Keys.PIP_SNAP] ?: true }
    suspend fun setPipSnap(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP_SNAP] = enabled }
    }

    /**
     * Drop the video decoder and keep the sound when the screen goes off. On by default, because it is
     * the single biggest saving this app can make on a phone battery, and turning it off only means
     * decoding frames nobody can see.
     */
    val audioOnScreenOff: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_ON_SCREEN_OFF] ?: true }
    suspend fun setAudioOnScreenOff(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_ON_SCREEN_OFF] = enabled }
    }

    /** Start in sound-only when there is no Wi-Fi. Off by default: it changes what the user gets from
     *  a tap, and that is only ever their own choice. */
    val audioOnMobileData: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_ON_MOBILE_DATA] ?: false }
    suspend fun setAudioOnMobileData(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_ON_MOBILE_DATA] = enabled }
    }

    /** Remember sound-only per channel, so a radio station opens without a picture next time and a
     *  television channel does not. On by default — the whole point is not having to ask twice. */
    val audioPerChannel: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_PER_CHANNEL] ?: true }
    suspend fun setAudioPerChannel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_PER_CHANNEL] = enabled }
    }

    /** Both read once, on the path that starts a stream and cannot wait on a flow. */
    suspend fun audioOnMobileDataNow(): Boolean = audioOnMobileData.first()
    suspend fun audioPerChannelNow(): Boolean = audioPerChannel.first()

    val sortGuide: Flow<GuideSort> = prefsFlow { prefs ->
        prefs[Keys.SORT_GUIDE]?.let { runCatching { GuideSort.valueOf(it) }.getOrNull() } ?: GuideSort.LIVE_TV
    }

    suspend fun setSortGuide(mode: GuideSort) {
        context.dataStore.edit { it[Keys.SORT_GUIDE] = mode.name }
    }

    /** The TV Guide also lists channels with no guide data (Order ▾ › Show), so they can be matched from there. */
    val guideShowEmpty: Flow<Boolean> = prefsFlow { it[Keys.GUIDE_SHOW_EMPTY] ?: false }
    suspend fun setGuideShowEmpty(show: Boolean) {
        context.dataStore.edit { it[Keys.GUIDE_SHOW_EMPTY] = show }
    }

    /** The TV Guide plays the focused channel in its preview pane (Order ▾ › Show); off shows the logo only. */
    val guidePreview: Flow<Boolean> = prefsFlow { it[Keys.GUIDE_PREVIEW] ?: true }
    suspend fun setGuidePreview(show: Boolean) {
        context.dataStore.edit { it[Keys.GUIDE_PREVIEW] = show }
    }

    // --- Video Player Settings ---

    /** Hardware decoding: mpv's `hwdec=mediacodec`, and hardware decoders first on ExoPlayer. Off = software
     *  decoders first on both engines, for streams a vendor decoder mishandles. */
    val hwDecoding: Flow<Boolean> = prefsFlow { it[Keys.HW_DECODING] ?: true }

    suspend fun setHwDecoding(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HW_DECODING] = enabled }
    }

    /**
     * Let software decoding play video above 1080p. Off by default: a TV-class CPU usually cannot keep
     * up with 4K in software, so playback above 1080p is stopped there with an honest error instead.
     * On, for devices with the CPU for it, the software rescue and the "Hardware decoding = Off"
     * path are allowed at any resolution.
     */
    val softwareDecodeAbove1080p: Flow<Boolean> = prefsFlow { it[Keys.SOFTWARE_DECODE_ABOVE_1080P] ?: false }

    suspend fun setSoftwareDecodeAbove1080p(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOFTWARE_DECODE_ABOVE_1080P] = enabled }
    }

    /**
     * Which engine Live TV starts a channel on, and whether it may hand over to the other one.
     *
     * Default is [EnginePreference.EXO_FIRST] — ExoPlayer opens far faster, which is what channel
     * surfing is made of, and it is the only engine with live closed captions; mpv catches what it
     * cannot play. The other three exist because that automatic handover is not always wanted:
     *
     *  - **mpv first** for a panel or a TV where ExoPlayer is the one that usually loses.
     *  - **The two "only" modes** for anyone who has established that the second engine never works for
     *    them. A handover costs a stop, a surface release and a re-open — several seconds of black on
     *    every unplayable channel — so paying it for an engine that was never going to help is pure loss.
     *    "Only" still keeps that engine's own `.m3u8` → `.ts` step; what it drops is the other engine.
     *
     * A channel pinned with the HUD "compatibility mode" toggle ignores this setting — see [ForceMpvStore].
     */
    val liveEnginePreference: Flow<tv.own.owntv.core.player.EnginePreference> = prefsFlow { prefs ->
        prefs[Keys.LIVE_ENGINE]?.let { runCatching { tv.own.owntv.core.player.EnginePreference.valueOf(it) }.getOrNull() }
            ?: tv.own.owntv.core.player.EnginePreference.EXO_FIRST
    }

    suspend fun setLiveEnginePreference(preference: tv.own.owntv.core.player.EnginePreference) {
        context.dataStore.edit { it[Keys.LIVE_ENGINE] = preference.name }
    }

    /**
     * Which engine Movies & Series start on, and whether it may hand over to the other one.
     *
     * Default is [EnginePreference.MPV_FIRST] — the opposite of Live TV's, deliberately: a film is one
     * long open where breadth of codec support beats speed of opening, and mpv has the wider set
     * (DTS/TrueHD audio, odd containers) plus the A/V-sync nudge. ExoPlayer-first is for devices and
     * providers where mpv's path can't open files ExoPlayer plays fine.
     *
     * The two "only" modes drop the automatic handover for anyone whose second engine never works —
     * see [liveEnginePreference] for the reasoning, which is identical. Two caveats specific to VOD:
     * ExoPlayer cannot decode DTS/TrueHD at all (the handoff is refused rather than attempted, so
     * "only ExoPlayer" means those files simply don't play), and the image-subtitle handoff to
     * ExoPlayer is not a fallback — it stays available in both "only" modes, since it is the only way
     * PGS/VOBSUB subtitles are ever rendered.
     *
     * Migrated in place from the older `vod_prefer_exo` switch, which is still read when the new key
     * has never been written: on → [EnginePreference.EXO_FIRST], off → [EnginePreference.MPV_FIRST].
     * Nobody's playback changes on upgrade.
     */
    val vodEnginePreference: Flow<tv.own.owntv.core.player.EnginePreference> = prefsFlow { prefs ->
        prefs[Keys.VOD_ENGINE]?.let { runCatching { tv.own.owntv.core.player.EnginePreference.valueOf(it) }.getOrNull() }
            ?: if (prefs[Keys.VOD_PREFER_EXO] == true) tv.own.owntv.core.player.EnginePreference.EXO_FIRST
            else tv.own.owntv.core.player.EnginePreference.MPV_FIRST
    }

    suspend fun setVodEnginePreference(preference: tv.own.owntv.core.player.EnginePreference) {
        context.dataStore.edit { it[Keys.VOD_ENGINE] = preference.name }
    }

    /** Measure live fps / bitrate / dropped frames for the stream-info overlay. On (default) = the
     *  overlay shows measured values that ExoPlayer doesn't declare for raw MPEG-TS. Off = a hard
     *  escape hatch: no live measuring runs at all (declared values only), for any low-end TV where
     *  the measuring is ever suspected of causing stutter. Never affects the actual playback pipeline.
     *  Defaults off on a low-RAM device, like [heroPreviewDefault]; auto frame rate measures fps on
     *  its own, so it does not depend on this. */
    val measuredStreamStats: Flow<Boolean> = prefsFlow { it[Keys.MEASURED_STREAM_STATS] ?: measuredStreamStatsDefault }

    /** What [measuredStreamStats] reports until the user picks; also the settings row's first value. */
    val measuredStreamStatsDefault: Boolean get() = !lowSpecDevice

    suspend fun setMeasuredStreamStats(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEASURED_STREAM_STATS] = enabled }
    }

    /** Detailed playback logging. Off (default): the live engine's trace is kept in memory only, as it
     *  always was in a release build. On: the same trace is written to Logcat and to a bounded file, and
     *  it rides along with an exported report — the only way a normal user can produce a live trace at
     *  all, since a release build's diagnostics were previously compile-time off. */
    val detailedDiagnostics: Flow<Boolean> = prefsFlow { it[Keys.DETAILED_DIAGNOSTICS] ?: false }

    suspend fun setDetailedDiagnostics(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DETAILED_DIAGNOSTICS] = enabled }
    }

    /** Type a provider channel number on the remote during full-screen live playback to jump straight
     *  to that channel. On (default). Off = number keys are ignored during playback, for anyone whose
     *  remote sends digits accidentally or who doesn't want the keys captured. */
    val directTune: Flow<Boolean> = prefsFlow { it[Keys.DIRECT_TUNE] ?: true }

    suspend fun setDirectTune(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DIRECT_TUNE] = enabled }
    }

    /** With the TV player's controls hidden, Left/Right on a catch-up channel rewind and go
     *  forward instead of opening the channel list and history. Off (default) keeps the lists; a
     *  channel with no archive has nothing to rewind and always keeps them. Films always seek. */
    val liveLeftRightRewinds: Flow<Boolean> = prefsFlow { it[Keys.LIVE_LEFT_RIGHT_REWINDS] ?: false }

    suspend fun setLiveLeftRightRewinds(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_LEFT_RIGHT_REWINDS] = enabled }
    }

    /** Pause and rewind live channels that have no catch-up, from a copy saved while watching
     *  full-screen. Off by default: it writes to the device's storage continuously. */
    val timeshiftEnabled: Flow<Boolean> = prefsFlow { it[Keys.TIMESHIFT_ENABLED] ?: false }

    suspend fun setTimeshiftEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TIMESHIFT_ENABLED] = enabled }
    }

    /** How many minutes the saved copy keeps; one of [TimeshiftRules.WINDOW_CHOICES_MINUTES]. */
    val timeshiftWindowMinutes: Flow<Int> =
        prefsFlow { TimeshiftRules.windowMinutesOf(it[Keys.TIMESHIFT_WINDOW_MINUTES] ?: TimeshiftRules.DEFAULT_WINDOW_MINUTES) }

    suspend fun setTimeshiftWindowMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.TIMESHIFT_WINDOW_MINUTES] = TimeshiftRules.windowMinutesOf(minutes) }
    }

    /** Which section a stream belongs to when deciding whether it goes to an external player. */
    enum class ExternalPlayerSection { LIVE_TV, MOVIES, SERIES }

    /** Hand this section's streams to an external player (VLC, MX Player) instead of the in-app engine.
     *  Off by default everywhere.
     *
     *  Movies and Series fall back to [Keys.EXTERNAL_PLAYER], the single global toggle these three keys
     *  replaced — that's the upgrade path, so a user who had it on keeps external playback for exactly
     *  the sections it used to cover. Live TV has no such fallback: the old toggle never routed live
     *  streams out, so inheriting it would silently start sending channels to VLC after an update. */
    val externalPlayerMovies: Flow<Boolean> = prefsFlow { it[Keys.EXTERNAL_PLAYER_MOVIES] ?: it[Keys.EXTERNAL_PLAYER] ?: false }
    val externalPlayerSeries: Flow<Boolean> = prefsFlow { it[Keys.EXTERNAL_PLAYER_SERIES] ?: it[Keys.EXTERNAL_PLAYER] ?: false }
    val externalPlayerLive: Flow<Boolean> = prefsFlow { it[Keys.EXTERNAL_PLAYER_LIVE] ?: false }

    /** A download is a movie or an episode, so it follows that section's setting. */
    fun externalPlayerFor(mediaType: tv.own.owntv.core.model.MediaType): Flow<Boolean> =
        if (mediaType == tv.own.owntv.core.model.MediaType.SERIES) externalPlayerSeries else externalPlayerMovies

    suspend fun setExternalPlayer(section: ExternalPlayerSection, enabled: Boolean) {
        val key = when (section) {
            ExternalPlayerSection.LIVE_TV -> Keys.EXTERNAL_PLAYER_LIVE
            ExternalPlayerSection.MOVIES -> Keys.EXTERNAL_PLAYER_MOVIES
            ExternalPlayerSection.SERIES -> Keys.EXTERNAL_PLAYER_SERIES
        }
        context.dataStore.edit { it[key] = enabled }
    }

    /**
     * The three-state replacement for the old [Keys.SURROUND_SOUND] boolean (Auto / Stereo only / Surround).
     *
     * Multichannel output fails in two known ways. Flaky vendor audio HALs and HDMI-ARC chips claim 5.1
     * then mis-play it (drained 2× → "fast video, no sound"). And even when multichannel LPCM plays
     * correctly, the wider HDMI/ARC buffer adds latency the TV/soundbar doesn't report back, so audio lags
     * video (lip-sync drift) on VODs. mpv never bitstreams (its passthrough AudioTrack reports no clock and
     * stutters video to a slideshow); ExoPlayer may, under the separate [audioPassthrough] switch.
     *
     * The old boolean was a poor fit for two reasons. It only ever reached **mpv** — Live TV's default
     * engine is ExoPlayer, where "off" changed nothing at all, so a TV that mis-plays multichannel kept
     * mis-playing it however the switch was set. And "off" is the wrong default to *have* to choose: a
     * user with a real receiver should get surround without reading a changelog, and a user whose TV
     * lies about its capabilities should get sound back without knowing why it went.
     *
     * Hence Auto by default — try multichannel, but fall back the instant the output is caught failing
     * (see `AudioOutputPolicy`). The fallback watchdog runs in **all three** modes,
     * including Surround: "no sound" is never what the user picked. Reading falls back to the old
     * boolean so nobody's explicit choice is lost, and nothing is rewritten on upgrade.
     */
    val surroundMode: Flow<SurroundMode> = prefsFlow {
        SurroundMode.of(it[Keys.SURROUND_MODE], it[Keys.SURROUND_SOUND])
    }

    suspend fun setSurroundMode(mode: SurroundMode) {
        context.dataStore.edit {
            it[Keys.SURROUND_MODE] = mode.name
            // Keep the legacy key consistent so a downgrade to 4.1.6 lands somewhere sane.
            it[Keys.SURROUND_SOUND] = mode == SurroundMode.SURROUND
        }
    }

    /** ExoPlayer may hand Dolby/DTS to the TV or receiver undecoded when the output claims it can
     *  play it (on, as before). Off: decoded in the app. mpv always decodes. */
    val audioPassthrough: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_PASSTHROUGH] ?: true }

    suspend fun setAudioPassthrough(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_PASSTHROUGH] = enabled }
    }

    /** Night mode: loud passages are turned down and quiet ones up. Off by default. */
    val nightMode: Flow<Boolean> = prefsFlow { it[Keys.NIGHT_MODE] ?: false }

    suspend fun setNightMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NIGHT_MODE] = enabled }
    }

    /** Volume levelling: every channel and film is brought slowly to one loudness. Off by default. */
    val volumeLevelling: Flow<Boolean> = prefsFlow { it[Keys.VOLUME_LEVELLING] ?: false }

    suspend fun setVolumeLevelling(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_LEVELLING] = enabled }
    }

    /** The choices for the highest picture played (lines; 0 = Auto, no limit). */
    val maxVideoHeightChoices: List<Int> = listOf(0, 2160, 1080, 720, 480)

    /** The highest picture played when a stream offers several; 0 = Auto. */
    val maxVideoHeight: Flow<Int> = prefsFlow { (it[Keys.MAX_VIDEO_HEIGHT] ?: 0).takeIf { v -> v in maxVideoHeightChoices } ?: 0 }

    suspend fun setMaxVideoHeight(height: Int) {
        context.dataStore.edit { it[Keys.MAX_VIDEO_HEIGHT] = height.takeIf { v -> v in maxVideoHeightChoices } ?: 0 }
    }

    /** The choices for the mobile-data limit (lines; 0 = Off, the same as on Wi-Fi). */
    val mobileDataMaxVideoHeightChoices: List<Int> = listOf(0, 1080, 720, 480)

    /** The highest picture played on a metered connection (the phone's row); 0 = Off. */
    val mobileDataMaxVideoHeight: Flow<Int> =
        prefsFlow { (it[Keys.MOBILE_DATA_MAX_VIDEO_HEIGHT] ?: 0).takeIf { v -> v in mobileDataMaxVideoHeightChoices } ?: 0 }

    suspend fun setMobileDataMaxVideoHeight(height: Int) {
        context.dataStore.edit { it[Keys.MOBILE_DATA_MAX_VIDEO_HEIGHT] = height.takeIf { v -> v in mobileDataMaxVideoHeightChoices } ?: 0 }
    }

    /** Tunneled playback for live TV on ExoPlayer (experimental, off by default). */
    val tunneledPlayback: Flow<Boolean> = prefsFlow { it[Keys.TUNNELED_PLAYBACK] ?: false }

    /** True once tunneled playback failed on this device and switched itself off; the row says so until
     *  the user turns it on again. */
    val tunnelingFailed: Flow<Boolean> = prefsFlow { it[Keys.TUNNELING_FAILED] ?: false }

    suspend fun setTunneledPlayback(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.TUNNELED_PLAYBACK] = enabled
            if (enabled) it[Keys.TUNNELING_FAILED] = false
        }
    }

    /** The engine's first-failure switch-off. */
    suspend fun disableTunnelingAfterFailure() {
        context.dataStore.edit {
            it[Keys.TUNNELED_PLAYBACK] = false
            it[Keys.TUNNELING_FAILED] = true
        }
    }

    /** Auto-play the next episode (and roll into the next season) when one finishes. On by default. */
    val autoPlayNext: Flow<Boolean> = prefsFlow { it[Keys.AUTO_PLAY_NEXT] ?: true }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    /** Default zoom/aspect mode applied when playback starts (a `ZoomMode` name). */
    val defaultZoom: Flow<String> = prefsFlow { it[Keys.DEFAULT_ZOOM] ?: "FIT" }

    suspend fun setDefaultZoom(name: String) {
        context.dataStore.edit { it[Keys.DEFAULT_ZOOM] = name }
    }

    /**
     * Volume percent every item starts at (100 = untouched, up to the 150 boost ceiling). A film the
     * user has individually turned up overrides this — see
     * [tv.own.owntv.core.player.PlaybackPrefsStore].
     */
    val defaultVolume: Flow<Int> = prefsFlow { (it[Keys.DEFAULT_VOLUME] ?: 100).coerceIn(0, 150) }

    suspend fun setDefaultVolume(percent: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_VOLUME] = percent.coerceIn(0, 150) }
    }

    /**
     * How far the player's rewind/forward buttons and the seek bar's ◀/▶ jump in a movie or episode.
     *
     * Separate from [liveRewindStepSec] on purpose: skipping an ad break in a film and stepping back
     * through a live channel's archive are different journeys, and one number can't be right for both
     * — which is why the two have always had different hardcoded values (10 s and 30 s).
     */
    val seekStepSec: Flow<Int> = prefsFlow { it[Keys.SEEK_STEP_SEC] ?: SeekSteps.DEFAULT_SEEK_STEP_SEC }

    suspend fun setSeekStepSec(seconds: Int) {
        context.dataStore.edit { it[Keys.SEEK_STEP_SEC] = seconds }
    }

    /** How far one press of Rewind/Forward moves inside a live channel's catch-up archive. */
    val liveRewindStepSec: Flow<Int> = prefsFlow { it[Keys.LIVE_REWIND_STEP_SEC] ?: SeekSteps.DEFAULT_LIVE_REWIND_STEP_SEC }

    suspend fun setLiveRewindStepSec(seconds: Int) {
        context.dataStore.edit { it[Keys.LIVE_REWIND_STEP_SEC] = seconds }
    }

    // --- Subtitle appearance: size, text color, screen position, background transparency ---
    // Two levels of opt-in. The master toggle gates everything: while it's off NOTHING here is
    // applied and every renderer keeps its stock look (mpv defaults, the overlay's hardcoded 45%
    // box, and — the case that matters most — SubtitleView's embedded broadcaster styling).
    // Each option then has its own "Default" value, so turning the toggle ON still changes nothing
    // until the user picks something: only the options actually set reach a renderer.

    // Subtitle size is per engine: mpv and ExoPlayer draw the same multiplier at visibly different
    // sizes, so one shared value cannot be right for both. Each new key falls back to the legacy
    // single [Keys.SUB_SCALE] until it is set, so an upgrade keeps the size the user already chose on
    // both engines and nothing changes until they move one of them.

    /** Subtitle scale multiplier for mpv (sub-scale); [SubtitleStyle.SCALE_DEFAULT] = untouched. */
    val subtitleScaleMpv: Flow<Float> = prefsFlow {
        it[Keys.SUB_SCALE_MPV] ?: it[Keys.SUB_SCALE] ?: SubtitleStyle.SCALE_DEFAULT
    }

    suspend fun setSubtitleScaleMpv(scale: Float) {
        context.dataStore.edit { it[Keys.SUB_SCALE_MPV] = scale }
    }

    /** Subtitle scale multiplier for the Media3 SubtitleView; [SubtitleStyle.SCALE_DEFAULT] = untouched. */
    val subtitleScaleExo: Flow<Float> = prefsFlow {
        it[Keys.SUB_SCALE_EXO] ?: it[Keys.SUB_SCALE] ?: SubtitleStyle.SCALE_DEFAULT
    }

    suspend fun setSubtitleScaleExo(scale: Float) {
        context.dataStore.edit { it[Keys.SUB_SCALE_EXO] = scale }
    }

    /**
     * Master toggle for the custom subtitle look; off = stock rendering everywhere.
     *
     * Unset defaults to *on* for anyone who had already changed the subtitle size back when it was
     * a standalone setting — it lives under this toggle now, so defaulting to off would silently
     * revert their size on upgrade.
     */
    val subtitleStyleEnabled: Flow<Boolean> = prefsFlow { prefs ->
        prefs[Keys.SUB_STYLE_ENABLED] ?: SubtitleStyle.hasScale(prefs[Keys.SUB_SCALE] ?: SubtitleStyle.SCALE_DEFAULT)
    }

    suspend fun setSubtitleStyleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUB_STYLE_ENABLED] = enabled }
    }

    /** Null leaves the renderer's own or embedded subtitle font untouched. */
    val subtitleFont: Flow<AppFontFamily?> = prefsFlow { prefs ->
        prefs[Keys.SUB_FONT]?.let { stored -> AppFontFamily.entries.firstOrNull { it.name == stored } }
    }

    suspend fun setSubtitleFont(font: AppFontFamily?) {
        context.dataStore.edit { prefs ->
            if (font == null) prefs.remove(Keys.SUB_FONT) else prefs[Keys.SUB_FONT] = font.name
        }
    }

    /** Subtitle text color as "#RRGGBB"; blank ([SubtitleStyle.COLOR_DEFAULT]) = untouched. */
    val subtitleColor: Flow<String> = prefsFlow { it[Keys.SUB_COLOR] ?: SubtitleStyle.COLOR_DEFAULT }

    suspend fun setSubtitleColor(hex: String) {
        context.dataStore.edit { it[Keys.SUB_COLOR] = hex.trim() }
    }

    /** One of six fixed screen anchors, or [SubtitleStyle.Position.DEFAULT] = untouched. */
    val subtitlePosition: Flow<SubtitleStyle.Position> =
        prefsFlow { SubtitleStyle.Position.fromKey(it[Keys.SUB_POSITION]) }

    suspend fun setSubtitlePosition(position: SubtitleStyle.Position) {
        context.dataStore.edit { it[Keys.SUB_POSITION] = position.key }
    }

    /** Subtitle background opacity 0..100 (0 = no box, 100 = solid); negative = untouched. */
    val subtitleBgOpacity: Flow<Int> = prefsFlow { it[Keys.SUB_BG_OPACITY] ?: SubtitleStyle.OPACITY_DEFAULT }

    suspend fun setSubtitleBgOpacity(pct: Int) {
        val value = if (pct < SubtitleStyle.OPACITY_MIN) SubtitleStyle.OPACITY_DEFAULT else SubtitleStyle.clampOpacity(pct)
        context.dataStore.edit { it[Keys.SUB_BG_OPACITY] = value }
    }

    /** Audio sync offset in milliseconds, on both engines (mpv `audio-delay`, ExoPlayer's audio clock); +ve delays audio. */
    val audioDelayMs: Flow<Int> = prefsFlow { it[Keys.AUDIO_DELAY_MS] ?: 0 }

    suspend fun setAudioDelayMs(ms: Int) {
        context.dataStore.edit { it[Keys.AUDIO_DELAY_MS] = ms }
    }

    /** Extra audio delay per kind of output, on top of [audioDelayMs] — see [tv.own.owntv.core.player.OutputAudioDelays]. */
    val outputAudioDelays: Flow<tv.own.owntv.core.player.OutputAudioDelays> =
        parsedFlow(Keys.AUDIO_DELAY_BY_OUTPUT, tv.own.owntv.core.player.OutputAudioDelays::parse)

    suspend fun setOutputAudioDelay(kind: tv.own.owntv.core.player.AudioOutputKind, ms: Int) {
        context.dataStore.edit {
            val next = tv.own.owntv.core.player.OutputAudioDelays.parse(it[Keys.AUDIO_DELAY_BY_OUTPUT]).with(kind, ms)
            it[Keys.AUDIO_DELAY_BY_OUTPUT] = next.encode()
        }
    }

    // --- CH+- key paging (browse panels): master toggle + per-direction skip counts ---
    // Clamped to [1, ChNavLimits.HARD_MAX] on write so an accidental huge value can never persist.
    val chNavEnabled: Flow<Boolean> = prefsFlow { it[Keys.CH_NAV_ENABLED] ?: true }
    suspend fun setChNavEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CH_NAV_ENABLED] = enabled }
    }
    /** CH+ skip count (jumps this many items toward the first item). */
    val chNavUpSkip: Flow<Int> = prefsFlow {
        (it[Keys.CH_NAV_UP_SKIP] ?: ChNavLimits.DEFAULT_SKIP).coerceIn(1, ChNavLimits.HARD_MAX)
    }
    suspend fun setChNavUpSkip(n: Int) {
        context.dataStore.edit { it[Keys.CH_NAV_UP_SKIP] = n.coerceIn(1, ChNavLimits.HARD_MAX) }
    }
    /** CH− skip count (jumps this many items toward the last item). */
    val chNavDownSkip: Flow<Int> = prefsFlow {
        (it[Keys.CH_NAV_DOWN_SKIP] ?: ChNavLimits.DEFAULT_SKIP).coerceIn(1, ChNavLimits.HARD_MAX)
    }
    suspend fun setChNavDownSkip(n: Int) {
        context.dataStore.edit { it[Keys.CH_NAV_DOWN_SKIP] = n.coerceIn(1, ChNavLimits.HARD_MAX) }
    }

    /** The player's remote-button options; see [PlayerRemoteOptions]. */
    val playerRemoteOptions: Flow<PlayerRemoteOptions> = prefsFlow(::readPlayerRemoteOptions)

    /** Changes the options from their stored values in one write, so two quick changes cannot undo each other. */
    suspend fun updatePlayerRemoteOptions(change: (PlayerRemoteOptions) -> PlayerRemoteOptions) {
        context.dataStore.edit { writePlayerRemoteOptions(it, change(readPlayerRemoteOptions(it))) }
    }

    /** Configurable remote shortcuts. An absent key means factory defaults; an empty set means none. */
    val remoteShortcutBindings: Flow<List<RemoteShortcutBinding>> = parsedFlow(Keys.REMOTE_SHORTCUT_BINDINGS) { raw ->
        if (raw != null) {
            RemoteShortcutBindings.decode(raw)
        } else {
            RemoteShortcutBindings.defaults
        }
    }

    suspend fun setRemoteShortcutBinding(binding: RemoteShortcutBinding) {
        context.dataStore.edit { prefs ->
            val current = if (Keys.REMOTE_SHORTCUT_BINDINGS in prefs) {
                RemoteShortcutBindings.decode(prefs[Keys.REMOTE_SHORTCUT_BINDINGS].orEmpty())
            } else {
                RemoteShortcutBindings.defaults
            }
            prefs[Keys.REMOTE_SHORTCUT_BINDINGS] =
                RemoteShortcutBindings.encode(RemoteShortcutBindings.replace(current, binding))
        }
    }

    suspend fun removeRemoteShortcutBinding(keyCode: Int, press: RemoteShortcutPress) {
        context.dataStore.edit { prefs ->
            val current = if (Keys.REMOTE_SHORTCUT_BINDINGS in prefs) {
                RemoteShortcutBindings.decode(prefs[Keys.REMOTE_SHORTCUT_BINDINGS].orEmpty())
            } else {
                RemoteShortcutBindings.defaults
            }
            prefs[Keys.REMOTE_SHORTCUT_BINDINGS] =
                RemoteShortcutBindings.encode(current.filterNot { it.keyCode == keyCode && it.press == press })
        }
    }

    suspend fun resetRemoteShortcutBindings() {
        context.dataStore.edit { it.remove(Keys.REMOTE_SHORTCUT_BINDINGS) }
    }

    // --- Manual panel widths: per browse section, a master toggle + one percentage per panel ---
    // While the toggle is off the screens keep their stock layout code path entirely, so the feature
    // can't affect anyone who never opens it. Percentages are clamped on both read and write.
    private fun panelOnKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_ON
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_ON
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_ON
    }
    private fun panelCategoryKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_CAT
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_CAT
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_CAT
    }
    private fun panelListKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_LIST
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_LIST
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_LIST
    }
    private fun panelPreviewKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_PREVIEW
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_PREVIEW
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_PREVIEW
    }

    private fun livePreviewPanelHidden(p: Preferences): Boolean =
        (p[Keys.PANEL_W_LIVE_ON] ?: false) &&
            (p[Keys.PANEL_W_LIVE_CAT] ?: 0) > 0 &&
            (p[Keys.PANEL_W_LIVE_LIST] ?: 0) > 0 &&
            p[Keys.PANEL_W_LIVE_PREVIEW] == 0

    fun panelWidthEnabled(s: PanelSection): Flow<Boolean> = prefsFlow { it[panelOnKey(s)] ?: false }

    /**
     * The section's three shares, or null when nothing has been saved yet (the dialog then seeds
     * itself from the stock layout). Read back through [balanceToTotal] so a value written by a
     * different build can never leave the row over- or under-filled.
     */
    fun panelShares(s: PanelSection): Flow<PanelShares?> = prefsFlow { p ->
        val category = p[panelCategoryKey(s)]
        val list = p[panelListKey(s)]
        val preview = p[panelPreviewKey(s)]
        if (category == null || list == null || preview == null || category <= 0 || list <= 0 || preview < 0) {
            null
        } else {
            val snappedPreview = PanelWidthLimits.snapPreview(preview)
            balanceToTotal(
                PanelShares(
                    PanelWidthLimits.snap(category),
                    PanelWidthLimits.snap(list, PanelWidthLimits.listMax(snappedPreview)),
                    snappedPreview,
                ),
            )
        }
    }

    /** "Okay" in the panel-width dialog — the toggle and all three shares land in one write. */
    suspend fun setPanelWidths(s: PanelSection, enabled: Boolean, shares: PanelShares) {
        val safe = balanceToTotal(shares)
        context.dataStore.edit {
            it[panelOnKey(s)] = enabled
            it[panelCategoryKey(s)] = safe.category
            it[panelListKey(s)] = safe.list
            it[panelPreviewKey(s)] = safe.preview
            if (s == PanelSection.LIVE && enabled && safe.preview == 0) {
                it[Keys.LIVE_PREVIEW] = false
            }
        }
    }

    /**
     * How tall the Cinematic detail block is, as a percentage of the screen height, per section.
     *
     * Its own value, independent of [panelShares]: those three are one row's widths and must total
     * 100, while this is a height. Sharing that budget meant a taller detail block could only be
     * bought by narrowing the posters, and a stored 0 could never mean 0.
     *
     * Live TV is never Cinematic and never reads this.
     */
    fun cinematicDetailsHeight(s: PanelSection): Flow<Int> =
        prefsFlow { it[cinematicDetailsKey(s)]?.coerceIn(0, CINEMATIC_DETAILS_MAX) ?: CINEMATIC_DETAILS_DEFAULT }

    suspend fun setCinematicDetailsHeight(s: PanelSection, percent: Int) {
        context.dataStore.edit { it[cinematicDetailsKey(s)] = percent.coerceIn(0, CINEMATIC_DETAILS_MAX) }
    }

    /**
     * The Cinematic categories sheet's width, % of the screen — the sheet slides over the titles, so,
     * like Live TV's Stage sheet, it is its own value on its own scale and leaves the Separate
     * layout's [panelShares] untouched.
     */
    fun cinematicSheetWidth(s: PanelSection): Flow<Int> =
        prefsFlow { it[cinematicSheetKey(s)]?.coerceIn(LiveStageWidths.LIVE_SHEET_MIN, LiveStageWidths.LIVE_SHEET_MAX) ?: LiveStageWidths.DEFAULT.sheet }

    suspend fun setCinematicSheetWidth(s: PanelSection, percent: Int) {
        context.dataStore.edit { it[cinematicSheetKey(s)] = percent.coerceIn(LiveStageWidths.LIVE_SHEET_MIN, LiveStageWidths.LIVE_SHEET_MAX) }
    }

    /**
     * Live TV's Stage-layout widths ([LiveStageWidths]), or null when never saved. They share the
     * section's on/off toggle with [panelShares], which stays the Separate-panels layout's row.
     */
    val liveStageWidths: Flow<LiveStageWidths?> = prefsFlow { p ->
        val sheet = p[Keys.LIVE_STAGE_SHEET]
        val list = p[Keys.LIVE_STAGE_LIST]
        val preview = p[Keys.LIVE_STAGE_PREVIEW]
        if (sheet == null || list == null || preview == null) null
        else LiveStageWidths(sheet, list, preview).takeIf { it.isValid }
    }

    /** "Okay" in the Live TV panel-width dialog while the layout is Stage. An invalid set is ignored. */
    suspend fun setLiveStageWidths(enabled: Boolean, widths: LiveStageWidths) {
        if (!widths.isValid) return
        context.dataStore.edit {
            it[Keys.PANEL_W_LIVE_ON] = enabled
            it[Keys.LIVE_STAGE_SHEET] = widths.sheet
            it[Keys.LIVE_STAGE_LIST] = widths.list
            it[Keys.LIVE_STAGE_PREVIEW] = widths.preview
            // As for the Separate row: a hidden preview pane means no preview playback either.
            if (enabled && widths.preview == 0) it[Keys.LIVE_PREVIEW] = false
        }
    }

    private fun cinematicDetailsKey(s: PanelSection) =
        if (s == PanelSection.MOVIES) Keys.CINEMATIC_DETAILS_MOVIES else Keys.CINEMATIC_DETAILS_SERIES

    private fun cinematicSheetKey(s: PanelSection) =
        if (s == PanelSection.MOVIES) Keys.CINEMATIC_SHEET_MOVIES else Keys.CINEMATIC_SHEET_SERIES

    // --- Guide column widths: toggle + two percentages that must total exactly 100 ---
    val guideWidthEnabled: Flow<Boolean> = prefsFlow { it[Keys.GUIDE_WIDTH_ON] ?: false }

    val guideWidthShares: Flow<GuideWidthShares?> = prefsFlow { prefs ->
        val channels = prefs[Keys.GUIDE_WIDTH_CHANNELS]
        val epg = prefs[Keys.GUIDE_WIDTH_EPG]
        if (channels == null || epg == null) null
        else normalizeGuideWidths(GuideWidthShares(channels, epg))
    }

    suspend fun setGuideWidths(enabled: Boolean, shares: GuideWidthShares) {
        if (!shares.isValid) return
        context.dataStore.edit {
            it[Keys.GUIDE_WIDTH_ON] = enabled
            it[Keys.GUIDE_WIDTH_CHANNELS] = shares.channels
            it[Keys.GUIDE_WIDTH_EPG] = shares.epg
        }
    }

    // Preferred languages are per profile: "pref_audio_lang_<id>" / "pref_sub_lang_<id>". A profile
    // that never chose one follows the old shared key, so nobody's language changed with the upgrade.
    // Both flows follow the active profile, so a profile switch reaches the engines like any other change.

    /** Preferred audio language of the active profile (ISO code, mpv alang); blank = no preference. */
    val preferredAudioLang: Flow<String> = prefsFlow { profileLang(it, PREF_AUDIO_LANG_PREFIX, Keys.PREF_AUDIO_LANG) }

    suspend fun setPreferredAudioLang(lang: String) {
        context.dataStore.edit { it[activeProfileLangKey(it, PREF_AUDIO_LANG_PREFIX)] = lang }
    }

    /** Preferred subtitle language of the active profile (ISO code, mpv slang); blank = no preference. */
    val preferredSubLang: Flow<String> = prefsFlow { profileLang(it, PREF_SUB_LANG_PREFIX, Keys.PREF_SUB_LANG) }

    suspend fun setPreferredSubLang(lang: String) {
        context.dataStore.edit { it[activeProfileLangKey(it, PREF_SUB_LANG_PREFIX)] = lang }
    }

    // --- OpenSubtitles search language filter ---
    // Deliberately its own setting rather than reusing [preferredSubLang]: that one picks an EMBEDDED
    // track inside the stream (a 15-language mpv list that has no Greek, among others), which is a
    // different question from "which languages should an online search return". Default OFF = show
    // everything OpenSubtitles has for the title and let the user choose.

    /** Whether OpenSubtitles results are restricted to [subSearchLanguages]. Off = all languages. */
    val subSearchFilterEnabled: Flow<Boolean> = prefsFlow { it[Keys.SUB_SEARCH_FILTER] ?: false }

    suspend fun setSubSearchFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUB_SEARCH_FILTER] = enabled }
    }

    /** Chosen OpenSubtitles language codes (ISO 639-1, e.g. "el,en"). Only applied when the filter is on. */
    val subSearchLanguages: Flow<String> = prefsFlow { it[Keys.SUB_SEARCH_LANGS] ?: "" }

    suspend fun setSubSearchLanguages(codes: String) {
        context.dataStore.edit { it[Keys.SUB_SEARCH_LANGS] = codes.trim() }
    }

    /**
     * The Settings rows the user pinned to the Quick group, in the order they should appear.
     * Stored as a comma-joined list of row keys. Unknown keys are kept here but ignored when the list
     * is drawn, so a pin that belongs to a row hidden by the current theme/profile survives.
     */
    val quickPinnedKeys: Flow<List<String>> = parsedFlow(Keys.QUICK_PINNED) { raw ->
        (raw ?: DEFAULT_QUICK_PINNED.joinToString(","))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun setQuickPinnedKeys(keys: List<String>) {
        context.dataStore.edit { it[Keys.QUICK_PINNED] = keys.joinToString(",") }
    }

    /**
     * How the user arranged one long-press content menu: the order, as a comma-joined list of action
     * keys (empty means "as shipped"), and the actions they hid, the same way. Stored per menu — Live,
     * Movies, Series and Episodes are four independent lists. Keys that no longer exist are ignored on
     * read and actions the order has never heard of are appended and shown, which is what lets a later
     * release add an action without it vanishing. [MenuLayout.downloads] follows the profile in use.
     */
    fun menuLayout(menu: ContentMenu): Flow<MenuLayout> = combine(
        context.dataStore.data.map { it[menuOrderKey(menu)] to it[menuHiddenKey(menu)] }.distinctUntilChanged(),
        activeDownloadsAllowed,
    ) { (order, hidden), downloads ->
        MenuLayout(splitKeys(order), splitKeys(hidden).toSet(), downloads)
    }

    /** Saves [layout]'s order and hidden actions; an empty layout is the shipped menu again. */
    suspend fun setMenuLayout(menu: ContentMenu, layout: MenuLayout) {
        context.dataStore.edit {
            it[menuOrderKey(menu)] = layout.order.joinToString(",")
            it[menuHiddenKey(menu)] = layout.hidden.joinToString(",")
        }
    }

    private fun splitKeys(raw: String?) = (raw ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun menuOrderKey(menu: ContentMenu) = stringPreferencesKey("settings_menu_order_${menu.name.lowercase()}")
    private fun menuHiddenKey(menu: ContentMenu) = stringPreferencesKey("settings_menu_hidden_${menu.name.lowercase()}")

    // --- Per-source auto-refresh (Off / Startup / staleness threshold) ---
    // Stored as a JSON map { "<sourceId>": "<EnumName>" } in the owntv_settings DataStore — migration-safe
    // (Room uses destructive migrations, so anything that must survive a schema bump lives here). Reuses the
    // existing lastSyncAt columns (SourceEntity.lastSyncAt for playlists, EpgSource.lastSyncAt for EPG) as the
    // "last successful sync" timestamp; nothing new is stored for that.

    /** Per-source playlist auto-refresh selection. Missing ids default to [PlaylistRefresh.OFF]. */
    val playlistAutoRefresh: Flow<Map<Long, PlaylistRefresh>> =
        prefsFlow { prefs ->
            readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]).entries.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { it to PlaylistRefresh.parse(value) }
            }.toMap()
        }

    /** Per-source EPG auto-refresh selection. Missing ids default to [EpgAutoRefresh.OFF]. */
    val epgAutoRefresh: Flow<Map<Long, EpgRefresh>> =
        prefsFlow { prefs ->
            readRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]).entries.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { it to EpgRefresh.parse(value) }
            }.toMap()
        }

    /** The daily sync window ("refresh everything at one time"). Off by default. */
    val dailySync: Flow<DailySync> = prefsFlow { prefs -> DailySync.parse(prefs[Keys.DAILY_SYNC]) }

    suspend fun setDailySync(value: DailySync) {
        context.dataStore.edit { prefs -> prefs[Keys.DAILY_SYNC] = value.serialize() }
    }

    /**
     * EPG sources whose own `<icon src>` channel logos should replace the playlist's logos. Per source,
     * so one feed can supply logos while another only supplies programmes. Missing ids default to off.
     */
    val epgUseLogos: Flow<Set<Long>> = parsedFlow(Keys.EPG_USE_LOGOS) { raw ->
        parseRefreshMap(raw)
            .filterValues { it.toBoolean() }
            .keys.mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }
    }

    suspend fun setEpgUseLogos(sourceId: Long, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_USE_LOGOS] = writeRefreshMap(readRefreshMap(prefs[Keys.EPG_USE_LOGOS]), sourceId, enabled.toString())
        }
    }

    suspend fun setPlaylistAutoRefresh(sourceId: Long, refresh: PlaylistRefresh) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PLAYLIST_AUTO_REFRESH] = writeRefreshMap(readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]), sourceId, refresh.serialize())
        }
    }

    suspend fun setEpgAutoRefresh(sourceId: Long, refresh: EpgRefresh) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_AUTO_REFRESH] = writeRefreshMap(readRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]), sourceId, refresh.serialize())
        }
    }

    /**
     * One-time migration of the legacy binary `refresh_source_ids` set → per-source `STARTUP` entries.
     * Idempotent (guarded by [Keys.REFRESH_MIGRATED]) and non-overwriting: if the new
     * [playlistAutoRefresh] map is already non-empty (user picked a mode in the new UI, or a prior migration
     * ran), we only flip the flag and return — never clobbering existing selections.
     */
    suspend fun migrateLegacyRefreshFlags() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.REFRESH_MIGRATED] == true) return@edit
            val existing = readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH])
            val legacyIds = prefs[Keys.REFRESH_SOURCE_IDS].orEmpty()
            // Only migrate into an empty map — never overwrite selections already made in the new UI.
            if (existing.isEmpty() && legacyIds.isNotEmpty()) {
                val migrated = legacyIds.associate { it to PlaylistAutoRefresh.STARTUP.name }
                prefs[Keys.PLAYLIST_AUTO_REFRESH] =
                    org.json.JSONObject(migrated).toString()
            }
            prefs[Keys.REFRESH_MIGRATED] = true
        }
    }

    /**
     * One-shot guard for the post-migration EPG refill.
     *
     * `MIGRATION_8_9` deletes every row in `epg_programmes` (it adds `contentHash` and a natural-key
     * unique index, which the old rows can't satisfy) and nothing schedules a re-fetch — so an
     * upgrading user was left with an empty guide until they happened to re-sync EPG by hand. This
     * runs the detection exactly once per install, which also covers users who passed through 8→9
     * long ago and are still sitting on an empty guide.
     */
    val epgRefillChecked: Flow<Boolean> = prefsFlow { it[Keys.EPG_REFILL_CHECKED] == true }

    suspend fun markEpgRefillChecked() {
        context.dataStore.edit { prefs -> prefs[Keys.EPG_REFILL_CHECKED] = true }
    }

    /** Whether the one-time re-sort of playlists saved before video files went to Movies ran ([tv.own.owntv.core.sync.M3uVodResort]). */
    val m3uVodResortChecked: Flow<Boolean> = prefsFlow { it[Keys.M3U_VOD_RESORT_CHECKED] == true }

    suspend fun markM3uVodResortChecked() {
        context.dataStore.edit { prefs -> prefs[Keys.M3U_VOD_RESORT_CHECKED] = true }
    }

    /**
     * Interrupted-restore marker. A restore writes to the database *and* to several DataStore
     * files; only the row writes can share a transaction, so a crash or a pulled plug part-way
     * through leaves a half-applied merge that nothing would otherwise notice. The marker is written
     * before the first write and removed after the last one, so a value still present at the next
     * launch means "that restore didn't finish". The value is the backup file name plus the sections
     * that were being applied — enough to tell the user what to re-run, and never a secret.
     */
    val restoreInProgress: Flow<String?> = prefsFlow { it[Keys.RESTORE_IN_PROGRESS] }

    suspend fun markRestoreStarted(description: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RESTORE_IN_PROGRESS] = description }
    }

    suspend fun clearRestoreMarker() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.RESTORE_IN_PROGRESS) }
    }

    private inline fun <reified E : Enum<E>> parseRefreshMap(raw: String?, valueOf: (String) -> E): Map<Long, E> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Long, E>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toLongOrNull() ?: continue
            val name = obj.optString(key)
            val mode = runCatching { valueOf(name) }.getOrNull() ?: continue
            out[id] = mode
        }
        return out
    }

    private fun readRefreshMap(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return LinkedHashMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }

    private fun writeRefreshMap(map: MutableMap<String, String>, sourceId: Long, value: String): String {
        map[sourceId.toString()] = value
        return org.json.JSONObject(map.toMap()).toString()
    }

    /** Whether focusing a channel auto-plays it in the Live preview pane. */
    val livePreviewEnabled: Flow<Boolean> = prefsFlow { it[Keys.LIVE_PREVIEW] ?: true }

    /** False only while a saved, enabled Live layout has intentionally hidden its preview panel. */
    val livePreviewPanelActive: Flow<Boolean> = prefsFlow { !livePreviewPanelHidden(it) }

    suspend fun setLivePreviewEnabled(enabled: Boolean) {
        context.dataStore.edit {
            if (!enabled || !livePreviewPanelHidden(it)) it[Keys.LIVE_PREVIEW] = enabled
        }
    }

    /**
     * Whether the expanded Home hero plays its video. The hero preview holds a live decoder for as
     * long as the user browses Home, which is the one piece of background playback that had no off
     * switch. Defaults on, but off on a low-RAM device — the same test [PlayerBudget] already uses to
     * decide the player's memory budget, and the devices where a second video pipeline hurts most.
     */
    val heroPreviewEnabled: Flow<Boolean> = prefsFlow { it[Keys.HERO_PREVIEW] ?: heroPreviewDefault }

    /** The value [heroPreviewEnabled] reports until the user picks one; also the settings row's
     *  initial value, so the chip does not read "On" for a frame on a device where it is off. */
    val heroPreviewDefault: Boolean get() = !lowSpecDevice

    suspend fun setHeroPreviewEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HERO_PREVIEW] = enabled }
    }

    private val lowSpecDevice: Boolean by lazy { tv.own.owntv.core.player.PlayerBudget.of(context).lowSpec }

    /** Whether the Live preview plays audio (off by default so browsing stays quiet). */
    val livePreviewAudio: Flow<Boolean> = prefsFlow { it[Keys.LIVE_PREVIEW_AUDIO] ?: false }

    suspend fun setLivePreviewAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_PREVIEW_AUDIO] = enabled }
    }

    /** TV Guide: OK on a channel first plays it in the preview, OK again goes full screen. */
    val guidePreviewOnOk: Flow<Boolean> = prefsFlow { it[Keys.GUIDE_PREVIEW_ON_OK] ?: true }

    suspend fun setGuidePreviewOnOk(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GUIDE_PREVIEW_ON_OK] = enabled }
    }

    /** Live TV: focus no longer auto-previews; OK previews first, OK again goes full screen. */
    val livePreviewOnOk: Flow<Boolean> = prefsFlow { it[Keys.LIVE_PREVIEW_ON_OK] ?: false }

    suspend fun setLivePreviewOnOk(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_PREVIEW_ON_OK] = enabled }
    }

    /**
     * An HLS stream offering both an SDR and an HDR picture plays the SDR one, for a TV whose HDR looks
     * washed out. Streams with one kind only are untouched. ExoPlayer only: mpv's HLS reader does not see
     * which variant is which.
     */
    val preferSdr: Flow<Boolean> = prefsFlow { it[Keys.PREFER_SDR] ?: false }

    suspend fun setPreferSdr(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PREFER_SDR] = enabled }
    }

    /**
     * Multiview — up to four live channels on screen at once. Off by default: it costs one provider
     * connection per tile, so it is never something the app starts doing on its own.
     */
    val multiviewEnabled: Flow<Boolean> = prefsFlow { it[Keys.MULTIVIEW_ENABLED] ?: false }

    suspend fun setMultiviewEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MULTIVIEW_ENABLED] = enabled }
    }

    /**
     * How many tiles the grid has, 1..4. Never reduced behind the user's back — a device or a provider
     * that cannot manage the number chosen says so on the tile that fails.
     */
    val multiviewTiles: Flow<Int> = prefsFlow {
        (it[Keys.MULTIVIEW_TILES] ?: DEFAULT_MULTIVIEW_TILES).coerceIn(MIN_MULTIVIEW_TILES, MAX_MULTIVIEW_TILES)
    }

    suspend fun setMultiviewTiles(tiles: Int) {
        context.dataStore.edit {
            it[Keys.MULTIVIEW_TILES] = tiles.coerceIn(MIN_MULTIVIEW_TILES, MAX_MULTIVIEW_TILES)
        }
    }

    // --- Recording ------------------------------------------------------------------------------

    /**
     * Keep one of the playlist's connections back so the user can always watch something, rather
     * than letting recordings spend every one. **On by default**, and the one the user turns
     * off when they would rather record on all of them.
     *
     * It makes no difference on a one-connection account: there, a recording is allowed the only
     * stream there is, because a live programme is gone forever and a picture is not.
     */
    val recordingReserveConnection: Flow<Boolean> = prefsFlow {
        it[Keys.RECORDING_RESERVE_CONNECTION] ?: true
    }

    suspend fun setRecordingReserveConnection(reserve: Boolean) {
        context.dataStore.edit { it[Keys.RECORDING_RESERVE_CONNECTION] = reserve }
    }

    /** Read once, for the recorder, which has to answer before it may open a connection. */
    suspend fun recordingReserveConnection(): Boolean = recordingReserveConnection.first()

    /**
     * Minutes recorded before a programme starts and after it ends.
     *
     * Both exist because EPG times drift: providers round to five minutes, programmes overrun, and a
     * guide that is ninety seconds out is entirely ordinary. The padding is what turns "the guide
     * said 21:00" into "the programme is actually on the recording".
     */
    val recordingPreRollMinutes: Flow<Int> = prefsFlow {
        (it[Keys.RECORDING_PRE_ROLL_MINUTES] ?: RecordingSchedule.DEFAULT_PRE_ROLL_MINUTES)
            .coerceIn(0, RecordingSchedule.MAX_ROLL_MINUTES)
    }

    suspend fun setRecordingPreRollMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.RECORDING_PRE_ROLL_MINUTES] = minutes.coerceIn(0, RecordingSchedule.MAX_ROLL_MINUTES)
        }
    }

    val recordingPostRollMinutes: Flow<Int> = prefsFlow {
        (it[Keys.RECORDING_POST_ROLL_MINUTES] ?: RecordingSchedule.DEFAULT_POST_ROLL_MINUTES)
            .coerceIn(0, RecordingSchedule.MAX_ROLL_MINUTES)
    }

    suspend fun setRecordingPostRollMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.RECORDING_POST_ROLL_MINUTES] = minutes.coerceIn(0, RecordingSchedule.MAX_ROLL_MINUTES)
        }
    }

    /** Read once, when a recording's window is being worked out. */
    suspend fun recordingRollMinutes(): Pair<Int, Int> =
        recordingPreRollMinutes.first() to recordingPostRollMinutes.first()

    /**
     * Whether a recording may run on a metered connection. **On by default**, because a live
     * programme does not come round again and a recording that silently did not happen is worse than
     * one that cost some data.
     *
     * Deliberately *not* a WorkManager `UNMETERED` constraint, which is how downloads do it: that
     * would make the work wait for Wi-Fi, and waiting means the programme is over by the time it
     * runs. The recorder checks this at start time instead and records a **MISSED** row with a reason
     * the user can read.
     */
    val recordingOverMobileData: Flow<Boolean> = prefsFlow {
        it[Keys.RECORDING_OVER_MOBILE_DATA] ?: true
    }

    suspend fun setRecordingOverMobileData(allowed: Boolean) {
        context.dataStore.edit { it[Keys.RECORDING_OVER_MOBILE_DATA] = allowed }
    }

    suspend fun recordingOverMobileData(): Boolean = recordingOverMobileData.first()

    /**
     * "Record what I'm watching" — the opt-in that puts a record button in the player.
     *
     * **Off until the user turns it on, and it is never offered automatically**, not even when the
     * playlist allows exactly one stream. Turning it on is where the one-connection warning is shown
     * and accepted, which is why the HUD button needs no further nagging.
     */
    val recordWhatImWatching: Flow<Boolean> = prefsFlow { it[Keys.RECORD_WHAT_IM_WATCHING] ?: false }

    suspend fun setRecordWhatImWatching(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RECORD_WHAT_IM_WATCHING] = enabled }
    }

    /** Whether the >2-tile warning has been accepted, so it is asked once and not on every change. */
    val multiviewWarningAccepted: Flow<Boolean> = prefsFlow { it[Keys.MULTIVIEW_WARNING_ACCEPTED] ?: false }

    suspend fun setMultiviewWarningAccepted(accepted: Boolean) {
        context.dataStore.edit { it[Keys.MULTIVIEW_WARNING_ACCEPTED] = accepted }
    }

    /**
     * Switch the display's refresh rate to match the video frame rate (24/25/30/50/60 fps and the
     * 23.976/29.97/59.94 variants) during full-screen playback, and restore it on exit. Applies to both
     * engines. Films and series here, Live TV in [autoFrameRateLive]: a 24 Hz switch can be a nuisance
     * on films on one TV while 25/50 fps broadcast judders without it. Default off; users whose display
     * switches cleanly can opt in.
     */
    val autoFrameRateFilms: Flow<Boolean> = prefsFlow { prefs ->
        if (afrMigrated(prefs)) prefs[Keys.AUTO_FRAME_RATE] ?: false else false
    }

    /** [autoFrameRateFilms] for Live TV. Until it is set it follows the one switch there used to be. */
    val autoFrameRateLive: Flow<Boolean> = prefsFlow { prefs ->
        if (afrMigrated(prefs)) prefs[Keys.AUTO_FRAME_RATE_LIVE] ?: prefs[Keys.AUTO_FRAME_RATE] ?: false else false
    }

    // Also report Off before the startup migration coroutines complete, so an auto-resumed channel
    // cannot briefly request a display-mode switch on the first launch after either safety reset.
    private fun afrMigrated(prefs: Preferences): Boolean =
        prefs[Keys.AUTO_FRAME_RATE_RESET_416] == true && prefs[Keys.AUTO_FRAME_RATE_RESET_PRE12] == true

    /**
     * Whether the one-time Auto-frame-rate suggestion has already been answered.
     *
     * The suggestion only appears when a 24/25/50 fps stream is playing full-screen on a display whose
     * refresh rate is not a multiple of it *and* a matching mode exists — the case where mpv's direct
     * `mediacodec_embed` path judders and AFR is the only cure. Answering it either way (or turning AFR
     * on by hand) sets this for good; it is never shown twice.
     */
    val autoFrameRatePrompted: Flow<Boolean> = prefsFlow { it[Keys.AUTO_FRAME_RATE_PROMPTED] ?: false }

    /** The choices for films' buffer (seconds; 0 = Auto, the device tier). */
    val vodBufferChoicesSecs: List<Int> = listOf(0, 30, 60, 120, 300)

    /** How far ahead a film is loaded, in seconds; 0 = Auto (the device tier, as before). The
     *  memory ceiling is the tier's either way, so this is "up to". */
    val vodBufferSecs: Flow<Int> = prefsFlow { (it[Keys.VOD_BUFFER_SECS] ?: 0).takeIf { v -> v in vodBufferChoicesSecs } ?: 0 }

    suspend fun setVodBufferSecs(secs: Int) {
        context.dataStore.edit { it[Keys.VOD_BUFFER_SECS] = secs.takeIf { v -> v in vodBufferChoicesSecs } ?: 0 }
    }

    /** The choices for films' network timeout (seconds; 0 = Auto, each engine's own). */
    val vodNetworkTimeoutChoicesSecs: List<Int> = listOf(0, 10, 20, 30, 60)

    /** How long a film waits on a silent server; 0 = Auto (mpv 60 s, ExoPlayer 20 s, as before). */
    val vodNetworkTimeoutSecs: Flow<Int> =
        prefsFlow { (it[Keys.VOD_NETWORK_TIMEOUT_SECS] ?: 0).takeIf { v -> v in vodNetworkTimeoutChoicesSecs } ?: 0 }

    suspend fun setVodNetworkTimeoutSecs(secs: Int) {
        context.dataStore.edit { it[Keys.VOD_NETWORK_TIMEOUT_SECS] = secs.takeIf { v -> v in vodNetworkTimeoutChoicesSecs } ?: 0 }
    }

    /** The choices for films' reconnect attempts; 1 is what films always had. */
    val vodReconnectChoices: List<Int> = listOf(1, 3, 5, 10)

    /** Times a film that loses its connection mid-play is reopened where it stopped before the error. */
    val vodReconnects: Flow<Int> = prefsFlow { (it[Keys.VOD_RECONNECTS] ?: 1).takeIf { v -> v in vodReconnectChoices } ?: 1 }

    suspend fun setVodReconnects(count: Int) {
        context.dataStore.edit { it[Keys.VOD_RECONNECTS] = count.takeIf { v -> v in vodReconnectChoices } ?: 1 }
    }

    /** The longest pause [afrPauseSecs] offers; the TV's picker lists 0 (off) to this. */
    val afrPauseMaxSecs: Int = 5

    /** Seconds a FILM stays paused after Auto frame rate makes the TV re-sync its display mode
     *  (0 = off, the default; at most [afrPauseMaxSecs]). A seamless switch never pauses. */
    val afrPauseSecs: Flow<Int> = prefsFlow { (it[Keys.AFR_PAUSE_SECS] ?: 0).coerceIn(0, afrPauseMaxSecs) }

    suspend fun setAfrPauseSecs(secs: Int) {
        context.dataStore.edit { it[Keys.AFR_PAUSE_SECS] = secs.coerceIn(0, afrPauseMaxSecs) }
    }

    /** Auto frame rate also switches the display to a FILM's resolution, never above the one the
     *  TV was on before playback. Off (default): the resolution is left alone and the TV upscales. */
    val afrMatchResolution: Flow<Boolean> = prefsFlow { it[Keys.AFR_MATCH_RESOLUTION] ?: false }

    suspend fun setAfrMatchResolution(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AFR_MATCH_RESOLUTION] = enabled }
    }

    suspend fun setAutoFrameRatePrompted() {
        context.dataStore.edit { it[Keys.AUTO_FRAME_RATE_PROMPTED] = true }
    }

    suspend fun setAutoFrameRateFilms(enabled: Boolean) = setAutoFrameRate(Keys.AUTO_FRAME_RATE, enabled)

    suspend fun setAutoFrameRateLive(enabled: Boolean) = setAutoFrameRate(Keys.AUTO_FRAME_RATE_LIVE, enabled)

    private suspend fun setAutoFrameRate(key: Preferences.Key<Boolean>, enabled: Boolean) {
        context.dataStore.edit {
            // Live TV still reading the films value: give it that value of its own first, so changing
            // films does not change Live TV with it.
            if (it[Keys.AUTO_FRAME_RATE_LIVE] == null) it[Keys.AUTO_FRAME_RATE_LIVE] = it[Keys.AUTO_FRAME_RATE] ?: false
            it[key] = enabled
            it[Keys.AUTO_FRAME_RATE_RESET_416] = true
            it[Keys.AUTO_FRAME_RATE_RESET_PRE12] = true
            // Someone who has found the setting doesn't need to be told it exists.
            if (enabled) it[Keys.AUTO_FRAME_RATE_PROMPTED] = true
        }
    }

    /** v4.1.6 only: force AFR Off exactly once, including for users who previously enabled it. */
    suspend fun migrateAutoFrameRate416() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.AUTO_FRAME_RATE_RESET_416] == true) return@edit
            prefs[Keys.AUTO_FRAME_RATE] = false
            prefs.remove(Keys.AUTO_FRAME_RATE_LIVE)
            prefs[Keys.AUTO_FRAME_RATE_RESET_416] = true
        }
    }

    /**
     * v4.2.0 one-shot: force AFR Off exactly once on devices below Android 12.
     *
     * Only from API 31 does a display report [android.view.Display.Mode.getAlternativeRefreshRates] —
     * the set of rates it can switch to *without* blanking. Below that the app is switching blind, so
     * every mode change risks an HDMI re-handshake that blacks the picture out mid-programme; this is
     * what users on older TV boxes report as "the stream pauses when the frame rate changes". Frame-rate
     * snapping and the change cooldown in `FrameRateController` make it rarer, but
     * nothing in the platform can make it seamless there.
     *
     * So the default is corrected once, silently, rather than left on from an earlier install. Turning it
     * back on afterwards is a deliberate choice (Settings warns first on these devices) and is never
     * overridden again — the flag is written on every device, including Android 12+ where nothing is
     * reset, so this can only ever run once.
     */
    suspend fun migrateAutoFrameRatePre12() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.AUTO_FRAME_RATE_RESET_PRE12] == true) return@edit
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                prefs[Keys.AUTO_FRAME_RATE] = false
                prefs.remove(Keys.AUTO_FRAME_RATE_LIVE)
            }
            prefs[Keys.AUTO_FRAME_RATE_RESET_PRE12] = true
        }
    }

    /**
     * Mirror continue-watching rows into Android TV home surfaces.
     *
     * Every publish path in `TvHomeRepository` reads this one flow, so it is also where a host that
     * has no business on a TV home screen is stopped: a false [CoreBuildInfo.tvHome] pins it off and
     * the stored preference is never consulted. Without that the phone app published Watch Next rows
     * after every sync — into a content provider that does not exist and permissions it explicitly
     * removes — and only a `runCatching` in the sync worker kept it quiet.
     */
    val androidTvHomeEnabled: Flow<Boolean> =
        if (!CoreBuildInfo.tvHome) flowOf(false) else prefsFlow { it[Keys.ANDROID_TV_HOME] ?: true }

    suspend fun setAndroidTvHomeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANDROID_TV_HOME] = enabled }
    }

    /** The source shown as "active" in the sidebar; -1 = none chosen (fall back to the first source). */
    val defaultSourceId: Flow<Long> = prefsFlow { it[Keys.DEFAULT_SOURCE] ?: -1L }

    suspend fun setDefaultSource(id: Long) {
        context.dataStore.edit { it[Keys.DEFAULT_SOURCE] = id }
    }

    /** User-chosen download base folder; blank = app-specific storage. */
    val downloadRoot: Flow<String> = prefsFlow { it[Keys.DOWNLOAD_ROOT] ?: "" }

    suspend fun setDownloadRoot(path: String) {
        context.dataStore.edit { it[Keys.DOWNLOAD_ROOT] = path }
    }

    val themeMode: Flow<ThemeMode> = prefsFlow { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    val uiZoomPercent: Flow<Int> = prefsFlow { prefs ->
        UiZoom.clamp(prefs[Keys.UI_ZOOM_PCT] ?: UiZoom.DEFAULT)
    }

    suspend fun setUiZoomPercent(percent: Int) {
        context.dataStore.edit { it[Keys.UI_ZOOM_PCT] = UiZoom.clamp(percent) }
    }

    val fontCustomization: Flow<FontCustomization> = prefsFlow { prefs ->
        FontCustomization(
            sizePercent = UiFontScale.clamp(prefs[Keys.FONT_SIZE_PCT] ?: UiFontScale.DEFAULT),
            mainFamily = AppFontFamily.fromStored(
                prefs[Keys.MAIN_FONT_FAMILY],
                AppFontFamily.PLUS_JAKARTA_SANS,
            ),
            popupFamily = AppFontFamily.fromStored(
                prefs[Keys.POPUP_FONT_FAMILY],
                AppFontFamily.PLUS_JAKARTA_SANS,
            ),
            popupFontSizePercent = PopupFontScale.clamp(
                prefs[Keys.POPUP_FONT_SIZE_PCT] ?: PopupFontScale.DEFAULT,
            ),
            popupSizePercent = PopupSizeScale.clamp(
                prefs[Keys.POPUP_SIZE_PCT] ?: PopupSizeScale.DEFAULT,
            ),
        )
    }

    /** Saves the staged font dialog in one DataStore transaction, so the app never sees half a preset. */
    suspend fun setFontCustomization(value: FontCustomization) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE_PCT] = UiFontScale.clamp(value.sizePercent)
            prefs[Keys.POPUP_FONT_SIZE_PCT] = PopupFontScale.clamp(value.popupFontSizePercent)
            prefs[Keys.POPUP_SIZE_PCT] = PopupSizeScale.clamp(value.popupSizePercent)
            prefs[Keys.MAIN_FONT_FAMILY] = value.mainFamily.name
            prefs[Keys.POPUP_FONT_FAMILY] = value.popupFamily.name
        }
    }

    /** Docked mini-player size as a percentage of screen width (clamped to the allowed range). */
    val miniPlayerSizePct: Flow<Int> = prefsFlow { prefs ->
        tv.own.owntv.core.player.MiniPlayerSize.clamp(prefs[Keys.MINI_PLAYER_SIZE_PCT] ?: tv.own.owntv.core.player.MiniPlayerSize.DEFAULT)
    }

    suspend fun setMiniPlayerSizePct(percent: Int) {
        context.dataStore.edit { it[Keys.MINI_PLAYER_SIZE_PCT] = tv.own.owntv.core.player.MiniPlayerSize.clamp(percent) }
    }

    /** Docked mini-player screen position (a [tv.own.owntv.core.player.MiniPlayerPosition] name). */
    val miniPlayerPosition: Flow<String> = prefsFlow { prefs ->
        prefs[Keys.MINI_PLAYER_POSITION] ?: tv.own.owntv.core.player.MiniPlayerPosition.DEFAULT.name
    }

    suspend fun setMiniPlayerPosition(name: String) {
        context.dataStore.edit { it[Keys.MINI_PLAYER_POSITION] = name }
    }

    /** Live TV latency preset (a [LiveLatency] name). */
    val liveLatencyMode: Flow<String> = prefsFlow { prefs ->
        // Also report Balanced before the startup migration coroutine completes, so an auto-resumed
        // channel cannot briefly reuse an unsafe low/custom latency on the first 4.1.6 launch.
        if (prefs[Keys.LIVE_LATENCY_RESET_416] == true) {
            prefs[Keys.LIVE_LATENCY_MODE] ?: LiveLatency.DEFAULT.name
        } else {
            LiveLatency.BALANCED.name
        }
    }

    suspend fun setLiveLatencyMode(name: String) {
        context.dataStore.edit {
            it[Keys.LIVE_LATENCY_MODE] = name
            it[Keys.LIVE_LATENCY_RESET_416] = true
        }
    }

    /**
     * Every one-shot settings migration, for both apps at start. They used to run from the TV shell
     * only, so on the phone [liveLatencyMode] and [autoFrameRateFilms] / [autoFrameRateLive] — which read as Balanced / Off until
     * their migration has run — ignored a restored or synced choice. Each is idempotent and flag-guarded,
     * and one failing must not stop the rest.
     */
    suspend fun runOneTimeMigrations() {
        runCatching { migrateLegacyRefreshFlags() }
        runCatching { migrateAutoFrameRate416() }
        runCatching { migrateAutoFrameRatePre12() }
        runCatching { migrateLiveLatency416() }
        runCatching { migrateSystemSansToJakarta() }
    }

    /** Whether the one-time drop of old list orders (see `ContentOrderDao.dropListOrdersExcept`) has run. */
    suspend fun listOrdersReset(): Boolean = context.dataStore.data.first()[Keys.LIST_ORDERS_RESET] == true

    suspend fun markListOrdersReset() {
        context.dataStore.edit { it[Keys.LIST_ORDERS_RESET] = true }
    }

    /**
     * Plus Jakarta Sans became the default main and popup font, but the font dialog stores every field
     * when it is saved, so anyone who ever pressed Apply there kept System Sans — the old default — and
     * never got the new one. Owner's decision (2026-09-30): move them once. Only System Sans is moved;
     * any other font was a real choice and stays. A System Sans picked after this has run is kept too.
     */
    suspend fun migrateSystemSansToJakarta() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.FONT_JAKARTA_MIGRATED] == true) return@edit
            for (key in listOf(Keys.MAIN_FONT_FAMILY, Keys.POPUP_FONT_FAMILY)) {
                if (prefs[key] == AppFontFamily.SYSTEM_SANS.name) prefs[key] = AppFontFamily.PLUS_JAKARTA_SANS.name
            }
            prefs[Keys.FONT_JAKARTA_MIGRATED] = true
        }
    }

    /**
     * The TV app's Stage UI starts everyone on its own defaults, once: the rail
     * Docked · Compact · Fit (navigation choices stored by older versions do not apply), glass on
     * every surface at 50%, and the Stage background — a stored picture is removed. Movies & Series keep
     * the layout an update had (SEPARATE, the old default, where none was stored); a fresh install —
     * no active profile yet — gets the CINEMATIC default. TV only: the phone has its own look.
     */
    suspend fun applyStageDefaults() {
        var oldPicture: String? = null
        context.dataStore.edit { prefs ->
            if (prefs[Keys.STAGE_DEFAULTS_APPLIED] == true) return@edit
            stageDefaults(prefs)
            oldPicture = prefs[Keys.BG_IMAGE_PATH]
            prefs.remove(Keys.BG_IMAGE_PATH)
        }
        oldPicture?.takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).delete() } }
    }

    /**
     * The phone app's start for a new user: the Stage background and glass on every surface
     * at [alphaPct]. Written only on a fresh install — no profile yet and neither glass nor background ever set —
     * so an existing install keeps whatever it has. The TV uses [applyStageDefaults].
     */
    suspend fun applyPhoneStageDefaults(alphaPct: Int) {
        context.dataStore.edit { phoneStageDefaults(it, alphaPct) }
    }

    /** v4.1.6 only: force live latency to Balanced exactly once, including existing custom choices. */
    suspend fun migrateLiveLatency416() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.LIVE_LATENCY_RESET_416] == true) return@edit
            prefs[Keys.LIVE_LATENCY_MODE] = LiveLatency.BALANCED.name
            prefs[Keys.LIVE_LATENCY_RESET_416] = true
        }
    }

    /** Custom live buffer seconds, used when the preset is [LiveLatency.CUSTOM]. */
    val liveLatencyCustomSecs: Flow<Int> = prefsFlow { prefs ->
        LiveBuffer.clampCustom(prefs[Keys.LIVE_LATENCY_CUSTOM_SECS] ?: LiveBuffer.CUSTOM_DEFAULT)
    }

    suspend fun setLiveLatencyCustomSecs(secs: Int) {
        context.dataStore.edit { it[Keys.LIVE_LATENCY_CUSTOM_SECS] = LiveBuffer.clampCustom(secs) }
    }

    /**
     * "Pre-buffer": how many seconds to fill before a live channel starts playing,
     * and how many to refill after a rebuffer. 0 = Off, which keeps the engines' existing 1 s / 2 s
     * start thresholds. A per-playlist override lives on `SourceEntity.livePrerollSecs`.
     */
    val livePrerollSecs: Flow<Int> = prefsFlow { prefs ->
        (prefs[Keys.LIVE_PREROLL_SECS] ?: LiveBuffer.PREROLL_OFF).coerceIn(0, 30)
    }

    suspend fun setLivePrerollSecs(secs: Int) {
        context.dataStore.edit { it[Keys.LIVE_PREROLL_SECS] = secs.coerceIn(0, 30) }
    }

    /**
     * "Give up after": how long a live channel may take to produce a picture before OwnTV stops trying
     * and shows the error, however many engine/format combinations the fallback ladder has left.
     *
     * 0 = Never, the behaviour before this setting existed: each rung keeps its own timeout and nothing
     * bounds their sum, which on a removed channel is about a minute and a half of black screen. Kept as
     * an escape hatch for a genuinely slow panel. A provider's own `Retry-After` countdown is never
     * charged against this.
     */
    val liveTuneTimeoutSecs: Flow<Int> = prefsFlow { prefs ->
        (prefs[Keys.LIVE_TUNE_TIMEOUT_SECS] ?: tv.own.owntv.core.player.PlaybackDefaults.LIVE_TUNE_BUDGET_SECS)
            .coerceIn(0, 60)
    }

    suspend fun setLiveTuneTimeoutSecs(secs: Int) {
        context.dataStore.edit { it[Keys.LIVE_TUNE_TIMEOUT_SECS] = secs.coerceIn(0, 60) }
    }

    /** Effective live buffer in seconds the engines apply (null = keep engine defaults, i.e. Balanced). */
    val liveBufferSeconds: Flow<Int?> = combine(liveLatencyMode, liveLatencyCustomSecs) { mode, custom ->
        LiveBuffer.effectiveSeconds(LiveLatency.fromName(mode), custom)
    }

    val accent: Flow<AccentColor> = prefsFlow { prefs ->
        prefs[Keys.ACCENT]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
            ?: AccentColor.TEAL
    }

    /** Picking a preset clears any custom accent so the preset takes effect. */
    suspend fun setAccent(accent: AccentColor) {
        context.dataStore.edit {
            it[Keys.ACCENT] = accent.name
            it[Keys.ACCENT_CUSTOM] = ""
        }
    }

    /** Custom accent as a hex string ("#52DBC8"); blank = use the [accent] preset. */
    val customAccent: Flow<String> = prefsFlow { it[Keys.ACCENT_CUSTOM] ?: "" }

    suspend fun setCustomAccent(hex: String) {
        context.dataStore.edit { it[Keys.ACCENT_CUSTOM] = hex.trim() }
    }

    // --- Focus highlight: the ring around whatever the remote is pointing at ---
    /** Focus ring color as a hex string; blank = follow the accent (the shipped behaviour). */
    val focusHighlight: Flow<String> = prefsFlow { it[Keys.FOCUS_HIGHLIGHT] ?: "" }

    /** Focus ring width in dp; 2 dp is the shipped default. */
    val focusHighlightWidth: Flow<Int> = prefsFlow { it[Keys.FOCUS_HIGHLIGHT_WIDTH] ?: 2 }

    suspend fun setFocusHighlight(hex: String) {
        context.dataStore.edit { it[Keys.FOCUS_HIGHLIGHT] = hex.trim() }
    }

    suspend fun setFocusHighlightWidth(dp: Int) {
        context.dataStore.edit { it[Keys.FOCUS_HIGHLIGHT_WIDTH] = dp }
    }

    // --- Glass & background: background picture, Glass on/off and opacity ---
    /** Absolute path to the user's background image (copied into app-private storage); blank = off. */
    val bgImagePath: Flow<String> = prefsFlow { it[Keys.BG_IMAGE_PATH] ?: "" }

    /** Glass on or off, and its opacity. */
    val glassConfig: Flow<tv.own.owntv.core.theme.GlassConfig> = prefsFlow { p ->
        tv.own.owntv.core.theme.GlassConfig(
            enabled = (p[Keys.GLASS_SCOPE] ?: GLASS_SCOPE_DEFAULT_BITS) != 0,
            alpha = ((p[Keys.GLASS_ALPHA] ?: GLASS_ALPHA_DEFAULT_PCT) / 100f).coerceIn(0f, 1f),
        )
    }

    /** The resolved background (Glass & background page). */
    val backgroundConfig: Flow<tv.own.owntv.core.theme.BackgroundConfig> = prefsFlow { p ->
        tv.own.owntv.core.theme.BackgroundConfig.resolve(
            style = p[Keys.BG_STYLE],
            look = p[Keys.BG_LOOK],
            dimPct = p[Keys.BG_DIM],
            blurPct = p[Keys.BG_BLUR],
            accentLight = p[Keys.BG_ACCENT_LIGHT],
            imagePath = p[Keys.BG_IMAGE_PATH] ?: "",
            glassOn = (p[Keys.GLASS_SCOPE] ?: GLASS_SCOPE_DEFAULT_BITS) != 0,
        )
    }

    /** Background: Stage colours, Picture or Plain. Choosing Picture with no look yet starts on Soft. */
    suspend fun setBackgroundStyle(style: tv.own.owntv.core.theme.BackgroundStyle) {
        context.dataStore.edit {
            it[Keys.BG_STYLE] = style.name
            if (style == tv.own.owntv.core.theme.BackgroundStyle.PICTURE && it[Keys.BG_LOOK] == null) {
                it[Keys.BG_LOOK] = tv.own.owntv.core.theme.PictureLook.SOFT.name
            }
            it.remove(Keys.BG_ACCENT_LIGHT)
        }
    }

    /** Picture look; Darken, Blur and Accent light go back to that look's own values. */
    suspend fun setPictureLook(look: tv.own.owntv.core.theme.PictureLook) {
        context.dataStore.edit {
            it[Keys.BG_LOOK] = look.name
            it.remove(Keys.BG_DIM); it.remove(Keys.BG_BLUR); it.remove(Keys.BG_ACCENT_LIGHT)
        }
    }

    /**
     * A newly chosen picture: Background becomes Picture and starts on Soft, with
     * that look's own values.
     */
    suspend fun setNewBackgroundPicture(path: String) {
        context.dataStore.edit {
            it[Keys.BG_IMAGE_PATH] = path.trim()
            it[Keys.BG_STYLE] = tv.own.owntv.core.theme.BackgroundStyle.PICTURE.name
            it[Keys.BG_LOOK] = tv.own.owntv.core.theme.PictureLook.SOFT.name
            it.remove(Keys.BG_DIM); it.remove(Keys.BG_BLUR); it.remove(Keys.BG_ACCENT_LIGHT)
        }
    }

    suspend fun setBackgroundDim(pct: Int) {
        context.dataStore.edit { it[Keys.BG_DIM] = pct.coerceIn(0, tv.own.owntv.core.theme.BackgroundConfig.DIM_MAX) }
    }

    suspend fun setBackgroundBlur(pct: Int) {
        context.dataStore.edit { it[Keys.BG_BLUR] = pct.coerceIn(0, tv.own.owntv.core.theme.BackgroundConfig.BLUR_MAX) }
    }

    suspend fun setBackgroundAccentLight(on: Boolean) {
        context.dataStore.edit { it[Keys.BG_ACCENT_LIGHT] = on }
    }

    /**
     * Reset (Glass & background): Stage colours, glass on every surface, opacity 50%. The picture file
     * is kept so Background › Picture can return to it.
     */
    suspend fun resetGlassAndBackground(alphaPct: Int = GLASS_ALPHA_DEFAULT_PCT) {
        context.dataStore.edit {
            it[Keys.BG_STYLE] = tv.own.owntv.core.theme.BackgroundStyle.STAGE.name
            it.remove(Keys.BG_LOOK); it.remove(Keys.BG_DIM); it.remove(Keys.BG_BLUR); it.remove(Keys.BG_ACCENT_LIGHT)
            it[Keys.GLASS_SCOPE] = tv.own.owntv.core.theme.GlassConfig.ON_BITS
            it[Keys.GLASS_ALPHA] = alphaPct
        }
    }

    /** Persist the background image path. Pass "" to clear (turn glass off). */
    suspend fun setBgImagePath(path: String) {
        context.dataStore.edit { it[Keys.BG_IMAGE_PATH] = path.trim() }
    }

    /** Glass on ([tv.own.owntv.core.theme.GlassConfig.ON_BITS]) or off (0). */
    suspend fun setGlassScopeBitmask(bits: Int) {
        context.dataStore.edit { it[Keys.GLASS_SCOPE] = bits }
    }

    /** Persist glass alpha as an integer 0..100. */
    suspend fun setGlassAlphaPercent(pct: Int) {
        context.dataStore.edit { it[Keys.GLASS_ALPHA] = pct.coerceIn(0, 100) }
    }

    /** Active profile id; -1 means first-run / setup not yet completed. */
    val activeProfileId: Flow<Long> = prefsFlow { it[Keys.ACTIVE_PROFILE] ?: -1L }

    suspend fun setActiveProfile(id: Long) {
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }
    }

    // --- Global proxy (Approach 1 — one app-wide HTTP proxy) ---
    // Covers all OkHttp traffic (playlist/API/EPG/images/downloads/updates/weather + ExoPlayer) and mpv
    // playback via its http-proxy option. Per-source overrides and SOCKS are future work; the proxy
    // password is intentionally NOT part of settings backup/export — see extras/PROXY_SUPPORT_PLAN.md.

    /** Live snapshot of the proxy settings as a single object (consumed by ProxyConfigHolder). */
    val proxyConfig: Flow<tv.own.owntv.core.network.ProxyConfig> = prefsFlow { p ->
        tv.own.owntv.core.network.ProxyConfig(
            enabled = p[Keys.PROXY_ENABLED] ?: false,
            host = p[Keys.PROXY_HOST] ?: "",
            port = p[Keys.PROXY_PORT] ?: 0,
            username = p[Keys.PROXY_USER] ?: "",
            password = p[Keys.PROXY_PASS] ?: "",
        )
    }

    /** Persist the proxy form in one write (enabled + host/port/user/pass). Blank user/pass = no auth.
     *  Port is clamped to a valid range; 0 means "unset". */
    suspend fun saveProxy(enabled: Boolean, host: String, port: Int, username: String, password: String) {
        context.dataStore.edit {
            it[Keys.PROXY_ENABLED] = enabled
            it[Keys.PROXY_HOST] = host.trim()
            it[Keys.PROXY_PORT] = port.coerceIn(0, 65535)
            it[Keys.PROXY_USER] = username.trim()
            it[Keys.PROXY_PASS] = password
        }
    }

    // --- Backup / restore of pure UI/player preferences (device-agnostic) ---
    // Deliberately EXCLUDES the download folder (a device-specific path) and the profile/source-coupled
    // keys (active profile, default source, refresh-on-startup) — those ride with the sources backup.

    // A new key belongs in one of these lists, or in SettingsBackupCoverageTest's exclusions with a reason.
    internal object BackupKeys {
        val strings = listOf(
            Keys.THEME_MODE, Keys.ACCENT, Keys.ACCENT_CUSTOM, Keys.FOCUS_HIGHLIGHT, Keys.DEFAULT_ZOOM,
            // Current global engine choices. VOD_PREFER_EXO below is migration-only and cannot represent
            // all four EnginePreference modes, so the two string values must travel themselves.
            Keys.LIVE_ENGINE, Keys.VOD_ENGINE,
            Keys.MAIN_FONT_FAMILY, Keys.POPUP_FONT_FAMILY,
            Keys.PREF_AUDIO_LANG, Keys.PREF_SUB_LANG, Keys.SUB_SEARCH_LANGS, Keys.SORT_LIVE, Keys.SORT_GUIDE, Keys.SORT_MOVIES,
            Keys.SORT_SERIES, Keys.RESUME_MODE, Keys.TIMESHIFT_RESUME_MODE, Keys.DOWNLOAD_WATCH_CHOICE, Keys.CATCHUP_TZ, Keys.CATCHUP_PLAYER, Keys.ANIMATION_LEVEL, Keys.VOD_VIEW_MODE, Keys.GUIDE_VIEW,
            Keys.EPISODE_VIEW_MODE, Keys.VOD_LAYOUT, Keys.LIVE_LAYOUT, Keys.LIVE_VIEW, Keys.REMINDER_MODE, Keys.PLAYER_HOLD_OK, Keys.PLAYER_SHORT_OK,
            Keys.WEATHER_LOCATION, Keys.RECENT_SEARCHES,
            Keys.CLOCK_TIME_COLOR, Keys.CLOCK_DATE_COLOR, Keys.CLOCK_WEATHER_COLOR,
            // Global proxy — non-secret fields only. The proxy password (Keys.PROXY_PASS) is NEVER part of
            // this whitelist; it is handled separately by BackupManager (encrypted or omitted).
            Keys.PROXY_HOST, Keys.PROXY_USER,
            // TMDB metadata: source mode + self-host URL. The user's own TMDB API key (Keys.TMDB_API_KEY) is a
            // secret and is deliberately NOT backed up in plaintext (same policy as the proxy password).
            Keys.METADATA_SERVER_URL, Keys.METADATA_MODE, Keys.METADATA_LANGUAGE,
            Keys.OPEN_SUBTITLES_SERVER_URL,
            // Download folder. Backed up so a same-device reinstall keeps the chosen folder; on a different
            // device a path that no longer exists is harmless — StorageAccess.resolveRoot falls back to app
            // storage, so a stale restore never breaks downloads.
            Keys.DOWNLOAD_ROOT,
            // Nav menu mode rides with settings backup so a reinstall keeps the user's DYNAMIC/STATIC choice.
            Keys.NAV_MENU_MODE,
            // Daily sync window: whether it is on and which hour it opens. A global user choice like any
            // other preference — the source ids it refreshes are the device's own (playlist_auto_refresh /
            // epg_auto_refresh stay out of the whitelist), so the stored "ON<hh>" / "OFF" travels as-is.
            Keys.DAILY_SYNC,
            // Stage navigation style, size and length (the hide delay is an int key).
            Keys.NAV_STYLE, Keys.NAV_SIZE, Keys.NAV_LENGTH, Keys.NAV_WIDEN,
            // The chosen icon colour. After a restore the app compares it with the applied icon and offers a restart.
            Keys.APP_ICON,
            // Docked mini-player position rides with settings backup (size is an int key, see backupIntKeys).
            Keys.MINI_PLAYER_POSITION,
            // Live TV latency preset (custom seconds is an int key, see backupIntKeys).
            Keys.LIVE_LATENCY_MODE,
            // Glass & background's picture path (Glass on/off and opacity are int keys). Only the path string
            // travels — the image bytes live in app-private storage which is wiped on uninstall, so on a new
            // device a stale path is ignored gracefully (falls back to none).
            Keys.BG_IMAGE_PATH,
            // Glass & background: Background and Picture look (Darken / Blur are int keys, Accent light a bool).
            Keys.BG_STYLE, Keys.BG_LOOK,
            // Subtitle appearance: text color and screen position (toggle is a bool key, size a float
            // key, background transparency an int key).
            Keys.SUB_COLOR,
            Keys.SUB_FONT,
            Keys.SUB_POSITION,
            // Custom DNS — not secret, backed up alongside proxy
            Keys.DNS_HOST, Keys.DNS_DOH_URL,
            // Surround mode (Auto/Stereo only/Surround). The legacy boolean is in backupBoolKeys and stays
            // in sync, but the string is what is read first, so it has to travel too.
            Keys.SURROUND_MODE,
            // The mobility layer's two choices. Same reasoning as the touch-host booleans below: a phone
            // restored from a phone keeps them, a television never reads them.
            Keys.MINI_PLAYER_STYLE, Keys.PIP_SIZE,
            // Settings personalization: Quick pins (including their order) and the independently arranged
            // action order for each of the four long-press content menus.
            Keys.QUICK_PINNED,
        )
        val stringSets = listOf(
            // The STATIC-mode hidden set rides with backup so a reinstall keeps the user's hidden icons.
            Keys.NAV_MENU_HIDDEN,
            Keys.REMOTE_SHORTCUT_BINDINGS,
        )
        val ints = listOf(Keys.GUIDE_DAYS_TO_KEEP, Keys.FOCUS_HIGHLIGHT_WIDTH, Keys.DEFAULT_VOLUME, Keys.SEEK_STEP_SEC, Keys.LIVE_REWIND_STEP_SEC, Keys.UI_ZOOM_PCT, Keys.FONT_SIZE_PCT, Keys.AUDIO_DELAY_MS, Keys.CATCHUP_OFFSET_MIN, Keys.EPG_OFFSET_MIN, Keys.PROXY_PORT, Keys.DNS_PORT, Keys.CH_NAV_UP_SKIP, Keys.CH_NAV_DOWN_SKIP, Keys.MINI_PLAYER_SIZE_PCT, Keys.LIVE_LATENCY_CUSTOM_SECS, Keys.LIVE_PREROLL_SECS, Keys.LIVE_TUNE_TIMEOUT_SECS, Keys.GLASS_SCOPE, Keys.GLASS_ALPHA, Keys.BG_DIM, Keys.BG_BLUR, Keys.SUB_BG_OPACITY,
            Keys.PANEL_W_LIVE_CAT, Keys.PANEL_W_LIVE_LIST, Keys.PANEL_W_LIVE_PREVIEW,
            Keys.LIVE_STAGE_SHEET, Keys.LIVE_STAGE_LIST, Keys.LIVE_STAGE_PREVIEW,
            Keys.PANEL_W_MOVIES_CAT, Keys.PANEL_W_MOVIES_LIST, Keys.PANEL_W_MOVIES_PREVIEW,
                Keys.PANEL_W_SERIES_CAT, Keys.PANEL_W_SERIES_LIST, Keys.PANEL_W_SERIES_PREVIEW,
            Keys.CINEMATIC_DETAILS_MOVIES, Keys.CINEMATIC_DETAILS_SERIES,
            Keys.CINEMATIC_SHEET_MOVIES, Keys.CINEMATIC_SHEET_SERIES,
            Keys.GUIDE_WIDTH_CHANNELS, Keys.GUIDE_WIDTH_EPG,
            Keys.POPUP_FONT_SIZE_PCT, Keys.POPUP_SIZE_PCT, Keys.VOD_GRID_COLUMNS, Keys.GUIDE_DENSITY_PCT,
            Keys.GESTURE_SENSITIVITY_PCT,
            Keys.NAV_HIDE_AFTER_MS, Keys.REMINDER_LEAD_MIN,
            // Multiview's tile count and recording's start-early / finish-late minutes (readers clamp them).
            Keys.MULTIVIEW_TILES, Keys.RECORDING_PRE_ROLL_MINUTES, Keys.RECORDING_POST_ROLL_MINUTES,
            // Auto frame rate's pause and films' buffer / timeout / reconnects; the readers
            // accept only their own choices.
            Keys.AFR_PAUSE_SECS, Keys.VOD_BUFFER_SECS, Keys.VOD_NETWORK_TIMEOUT_SECS, Keys.VOD_RECONNECTS,
            // The two quality limits; the readers accept only their own choices.
            Keys.MAX_VIDEO_HEIGHT, Keys.MOBILE_DATA_MAX_VIDEO_HEIGHT,
            // The saved-copy length; the reader accepts only its own choices.
            Keys.TIMESHIFT_WINDOW_MINUTES)
        val bools = listOf(
            Keys.GUIDE_SHOW_EMPTY, Keys.GUIDE_PREVIEW,
            Keys.LIVE_PREVIEW, Keys.LIVE_PREVIEW_AUDIO, Keys.GUIDE_PREVIEW_ON_OK, Keys.LIVE_PREVIEW_ON_OK, Keys.HERO_PREVIEW, Keys.PREFER_SDR, Keys.AUTO_FRAME_RATE, Keys.AUTO_FRAME_RATE_LIVE, Keys.AFR_MATCH_RESOLUTION, Keys.AUTO_FRAME_RATE_PROMPTED, Keys.ANDROID_TV_HOME, Keys.HW_DECODING, Keys.SOFTWARE_DECODE_ABOVE_1080P,
            Keys.VOD_PREFER_EXO, Keys.MEASURED_STREAM_STATS, Keys.DETAILED_DIAGNOSTICS, Keys.DIRECT_TUNE, Keys.LIVE_LEFT_RIGHT_REWINDS, Keys.EXTERNAL_PLAYER,
            Keys.EXTERNAL_PLAYER_LIVE, Keys.EXTERNAL_PLAYER_MOVIES, Keys.EXTERNAL_PLAYER_SERIES, Keys.UPDATE_CHECK_ON_START, Keys.SURROUND_SOUND, Keys.AUTO_PLAY_NEXT, Keys.PROXY_ENABLED,
            Keys.WEATHER_ENABLED, Keys.WEATHER_FAHRENHEIT, Keys.RESUME_LAST_CHANNEL, Keys.METADATA_ENABLED, Keys.CH_NAV_ENABLED, Keys.PLAYER_SWAP_CHANNEL_KEYS, Keys.PLAYER_NUMBER_KEYS_TUNE,
            Keys.DNS_ENABLED,
            Keys.REMEMBER_LAST_LIVE, Keys.REMEMBER_LAST_MOVIES, Keys.REMEMBER_LAST_SERIES,
            Keys.REMEMBER_CAT_LIVE, Keys.REMEMBER_CAT_MOVIES, Keys.REMEMBER_CAT_SERIES, Keys.GUIDE_FOLLOWS_LIVE_CATEGORY,
            Keys.LIVE_SHOW_ALL_RAIL, Keys.LIVE_SHOW_CATCHUP_RAIL,
            Keys.SUB_STYLE_ENABLED, Keys.SUB_SEARCH_FILTER,
                Keys.PANEL_W_LIVE_ON, Keys.PANEL_W_MOVIES_ON, Keys.PANEL_W_SERIES_ON, Keys.GUIDE_WIDTH_ON,
            Keys.AMBIENT_GLOW_ENABLED, Keys.AMBIENT_GLOW_PULSE,
            Keys.BG_ACCENT_LIGHT, Keys.BRAND_ACCENT_TRIANGLE,
            // Touch-host settings. They travel even though a television has no row for them: a phone
            // restored from a phone must keep them, and a television simply ignores what it never reads.
            Keys.BACKGROUND_PLAYBACK, Keys.PIP_ENABLED, Keys.DATA_SAVER, Keys.DOWNLOADS_WIFI_ONLY,
            Keys.PHONE_NAV_LABELS, Keys.PHONE_NAV_HIDE_ON_SCROLL, Keys.SHEET_COUNTS,
            Keys.PIP_ON_BACK, Keys.PIP_SNAP, Keys.AUDIO_ON_SCREEN_OFF, Keys.AUDIO_ON_MOBILE_DATA,
            Keys.AUDIO_PER_CHANNEL,
            Keys.MULTIVIEW_ENABLED, Keys.MULTIVIEW_WARNING_ACCEPTED,
            Keys.RECORDING_RESERVE_CONNECTION, Keys.RECORD_WHAT_IM_WATCHING, Keys.RECORDING_OVER_MOBILE_DATA,
            Keys.AUDIO_PASSTHROUGH, Keys.NIGHT_MODE, Keys.VOLUME_LEVELLING, Keys.TUNNELED_PLAYBACK,
            Keys.TIMESHIFT_ENABLED,
        )
        val floats = listOf(Keys.SUB_SCALE, Keys.SUB_SCALE_MPV, Keys.SUB_SCALE_EXO)

        /**
         * Old keys that are still *imported* (older backups carry them) but exported only while they
         * still decide something — each is read as the fallback for the keys listed with it, until those
         * are written. `pip_on_back` has no reader at all, so it is never exported.
         */
        val legacySuccessors: Map<Preferences.Key<*>, List<Preferences.Key<*>>> = mapOf(
            Keys.VOD_PREFER_EXO to listOf(Keys.VOD_ENGINE),
            Keys.SURROUND_SOUND to listOf(Keys.SURROUND_MODE),
            Keys.EXTERNAL_PLAYER to listOf(Keys.EXTERNAL_PLAYER_MOVIES, Keys.EXTERNAL_PLAYER_SERIES),
            Keys.SUB_SCALE to listOf(Keys.SUB_SCALE_MPV, Keys.SUB_SCALE_EXO, Keys.SUB_STYLE_ENABLED),
        )

        /** Whether [k] goes into a new backup, given this device's stored [p]. */
        fun exports(k: Preferences.Key<*>, p: Preferences): Boolean = when (k) {
            Keys.PIP_ON_BACK -> false
            // The fallback for every profile that never picked a startup mode; only "on" changes anything.
            Keys.RESUME_LAST_CHANNEL -> p[Keys.RESUME_LAST_CHANNEL] == true
            else -> legacySuccessors[k]?.any { p[it] == null } ?: true
        }
    }

    private val backupStringKeys = BackupKeys.strings + ContentMenu.entries.flatMap { listOf(menuOrderKey(it), menuHiddenKey(it)) }
    private val backupStringSetKeys = BackupKeys.stringSets
    private val backupIntKeys = BackupKeys.ints
    private val backupBoolKeys = BackupKeys.bools
    private val backupFloatKeys = BackupKeys.floats

    /**
     * "Remember last category" values (see the REMEMBER_CAT_* toggles, which are backed up as plain
     * booleans above). These need a filter rather than a straight whitelist entry, so they live apart:
     * a provider folder is stored as "FOLDER:<Room category id>", and Room content ids are recreated
     * by every sync (the catalog is clear-then-insert), so that value means nothing on another device
     * — restoring it would land the user in an arbitrary category. The stable forms travel:
     * "ALL" / "FAV" / "HIST" / "CATCHUP" and "CUSTOM:<uuid>", a user-created category whose id really
     * is portable. Each profile's travel in [exportLastCategories]; [importSettings] still reads the
     * single device-wide values older files carry.
     *
     * `last_live_channel` is deliberately absent for the same reason and has no stable form at all:
     * it is a Room channel id, so there is nothing here worth carrying.
     */
    private val backupLastCategoryKeys = listOf(
        Keys.LAST_LIVE_CATEGORY, Keys.LAST_MOVIES_CATEGORY, Keys.LAST_SERIES_CATEGORY,
    )

    private fun isPortableCategoryKey(value: String): Boolean =
        value == "ALL" || value == "FAV" || value == "HIST" || value == "CATCHUP" || value.startsWith("CUSTOM:")

    /** Each profile's portable remembered categories, as `{ "<profileId>": { "last_live_category": … } }`. */
    suspend fun exportLastCategories(): org.json.JSONObject = ProfilePositions.export(
        context.positionStore.data.first().asMap().mapKeys { it.key.name },
        backupLastCategoryKeys.map { it.name },
        ::isPortableCategoryKey,
    )

    /** Restores [exportLastCategories] only for profile ids in [existingProfileIds], portable values only. */
    suspend fun importLastCategories(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        val values = ProfilePositions.import(o, backupLastCategoryKeys.map { it.name }, existingProfileIds, ::isPortableCategoryKey)
        context.positionStore.edit { positions -> values.forEach { (name, value) -> positions[stringPreferencesKey(name)] = value } }
    }

    suspend fun exportSettings(): org.json.JSONObject {
        val p = context.dataStore.data.first()
        return org.json.JSONObject().apply {
            backupStringKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupStringSetKeys.forEach { k -> p[k]?.let { put(k.name, org.json.JSONArray(it)) } }
            backupIntKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupBoolKeys.filter { BackupKeys.exports(it, p) }.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupFloatKeys.filter { BackupKeys.exports(it, p) }.forEach { k -> p[k]?.let { put(k.name, it.toDouble()) } }
            // The UI language lives in SharedPreferences (LocaleStore), not DataStore, so it carries as
            // solate, explicitly serialised field rather than pretending it is a DataStore key. `""` means
            // follow system (see docs/internationalization.md 0b, "Backup interaction").
            put(UI_LANGUAGE_KEY, localeStore.currentTag.value)
        }
    }

    /** This device, as a backup records it (see [tv.own.owntv.core.backup.DeviceIdentity]). */
    fun deviceIdentity(): tv.own.owntv.core.backup.DeviceIdentity = tv.own.owntv.core.backup.DeviceIdentity.of(context)

    /**
     * The settings that describe this device's hardware rather than the user's taste: which player
     * engine copes with its decoders, whether hardware decoding works on it, whether its display can
     * switch frame rate, HDR, what its audio output accepts. Restored from another
     * device only when the user says so ([importSettings]'s `keepDeviceSettings`) — a phone's or an
     * Android 11 box's values were simply copied onto whatever the backup was restored to.
     */
    private val deviceSpecificSettings: Set<String> = listOf(
        Keys.LIVE_ENGINE, Keys.VOD_ENGINE, Keys.VOD_PREFER_EXO, Keys.HW_DECODING, Keys.SOFTWARE_DECODE_ABOVE_1080P, Keys.AUTO_FRAME_RATE, Keys.AUTO_FRAME_RATE_LIVE,
        Keys.AFR_PAUSE_SECS, Keys.AFR_MATCH_RESOLUTION,
        Keys.PREFER_SDR, Keys.SURROUND_MODE, Keys.SURROUND_SOUND, Keys.AUDIO_PASSTHROUGH,
        Keys.TUNNELED_PLAYBACK,
    ).map { it.name }.toSet()

    /** Whether a backup's settings block holds any of the [deviceSpecificSettings]. */
    fun carriesDeviceSettings(o: org.json.JSONObject): Boolean = deviceSpecificSettings.any { o.has(it) }

    /**
     * [keepDeviceSettings]: leave this device's [deviceSpecificSettings] as they are, because the file
     * came from another device and the user did not ask for them.
     */
    suspend fun importSettings(o: org.json.JSONObject, keepDeviceSettings: Boolean = false): SettingsImportResult {
        context.dataStore.edit { prefs ->
            // Every value on its own: one wrong-typed or out-of-range entry (another build, a hand edit)
            // is skipped and this device keeps its own value, instead of aborting the whole restore.
            // Values the UI can only ever write in range are checked here too — see [SettingsImportRules].
            fun each(k: Preferences.Key<*>, apply: () -> Unit) {
                if (keepDeviceSettings && k.name in deviceSpecificSettings) return
                if (o.has(k.name)) runCatching(apply).onFailure { android.util.Log.w("OwnTV-Settings", "settings import: skipped ${k.name}") }
            }
            backupStringKeys.forEach { k ->
                each(k) { SettingsImportRules.string(k.name, o.getString(k.name))?.let { prefs[k] = it } }
            }
            backupStringSetKeys.forEach { k ->
                each(k) { prefs[k] = o.getJSONArray(k.name).let { arr -> buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) } } }
            }
            backupIntKeys.forEach { k -> each(k) { SettingsImportRules.int(k.name, o.getInt(k.name))?.let { prefs[k] = it } } }
            backupBoolKeys.forEach { k -> each(k) { prefs[k] = o.getBoolean(k.name) } }
            backupFloatKeys.forEach { k -> each(k) { SettingsImportRules.float(o.getDouble(k.name))?.let { prefs[k] = it } } }
            if (livePreviewPanelHidden(prefs)) {
                prefs[Keys.LIVE_PREVIEW] = false
            }
            // A restored latency / frame-rate choice is the user's own, exactly like one made in
            // Settings, so the v4.1.6 one-shot resets must neither hide it nor overwrite it later. The
            // pre-Android-12 AFR reset is deliberately left to run: it is a fact about this device.
            if (o.has(Keys.LIVE_LATENCY_MODE.name)) prefs[Keys.LIVE_LATENCY_RESET_416] = true
            if (o.has(Keys.AUTO_FRAME_RATE.name) && !keepDeviceSettings) {
                prefs[Keys.AUTO_FRAME_RATE_RESET_416] = true
                // A backup from before Live TV had its own switch: Live TV follows the restored one.
                if (!o.has(Keys.AUTO_FRAME_RATE_LIVE.name)) prefs.remove(Keys.AUTO_FRAME_RATE_LIVE)
            }
        }
        // The last categories live in the position store. Guarded on read as well as on write: a file
        // written by another build (or edited by hand) must not be able to restore a "FOLDER:<id>" that
        // points at whatever this device's sync happens to have put behind that number.
        // Only files from before the values were kept per profile carry them here (one for the whole
        // device): they go back to the shared value that every profile without its own falls back to.
        // Newer files carry them per profile, see importLastCategories.
        context.positionStore.edit { positions ->
            backupLastCategoryKeys.forEach { k ->
                if (o.has(k.name)) runCatching { o.getString(k.name).takeIf(::isPortableCategoryKey)?.let { positions[k] = it } }
            }
        }
        // Do not publish a locale while the restore is still applying database/DataStore sections.
        // The caller applies this validated value after the restore marker is cleared. Invalid data
        // is reported but ignored so a malformed optional field cannot abort an otherwise valid import.
        if (!o.has(UI_LANGUAGE_KEY)) return SettingsImportResult()
        val raw = o.opt(UI_LANGUAGE_KEY) as? String
        val normalized = raw?.let(localeStore::normalize)
        return SettingsImportResult(
            localePresent = true,
            localeTag = normalized,
            // Only a JSON string is a valid locale field. In particular, JSONObject.NULL and
            // numbers must not silently turn into the empty system-default tag.
            invalidLocale = raw == null || normalized == null,
        )
    }

    /** Applies a locale deferred until a complete backup restore has cleared its marker. */
    suspend fun applyImportedLocale(tag: String) {
        localeStore.set(tag)
    }

    companion object {
        /** Glass opacity when glass is on: the Stage default and Reset. */
        const val GLASS_ALPHA_DEFAULT_PCT = 50

        /** The writes of [applyStageDefaults] (except the picture file), apart so a unit test can check them. */
        internal fun stageDefaults(prefs: MutablePreferences) {
            val update = prefs[Keys.ACTIVE_PROFILE] != null
            prefs[Keys.NAV_STYLE] = NavStyle.DOCKED.name
            prefs[Keys.NAV_SIZE] = NavSize.COMPACT.name
            prefs[Keys.NAV_LENGTH] = NavLength.FIT.name
            prefs[Keys.GLASS_SCOPE] = tv.own.owntv.core.theme.GlassConfig.ON_BITS
            prefs[Keys.GLASS_ALPHA] = GLASS_ALPHA_DEFAULT_PCT
            prefs[Keys.BG_STYLE] = tv.own.owntv.core.theme.BackgroundStyle.STAGE.name
            prefs.remove(Keys.BG_LOOK); prefs.remove(Keys.BG_DIM); prefs.remove(Keys.BG_BLUR); prefs.remove(Keys.BG_ACCENT_LIGHT)
            if (update && prefs[Keys.VOD_LAYOUT] == null) prefs[Keys.VOD_LAYOUT] = VodLayout.SEPARATE.name
            prefs[Keys.STAGE_DEFAULTS_APPLIED] = true
        }

        /** The writes of [applyPhoneStageDefaults]; nothing at all unless this is a fresh install. */
        internal fun phoneStageDefaults(prefs: MutablePreferences, alphaPct: Int) {
            val fresh = prefs[Keys.ACTIVE_PROFILE] == null && prefs[Keys.GLASS_SCOPE] == null && prefs[Keys.BG_STYLE] == null
            if (!fresh) return
            prefs[Keys.GLASS_SCOPE] = tv.own.owntv.core.theme.GlassConfig.ON_BITS
            prefs[Keys.GLASS_ALPHA] = alphaPct
            prefs[Keys.BG_STYLE] = tv.own.owntv.core.theme.BackgroundStyle.STAGE.name
        }

        /** Backup payload field name for the UI locale tag (read from / written to [LocaleStore]). */
        private const val UI_LANGUAGE_KEY = "ui_language"


        /** The six toggles Quick started life with, kept as the out-of-the-box pin list. */
        private val DEFAULT_QUICK_PINNED = listOf(
            "quick_live_preview", "quick_preview_sound", "quick_channel_numbers",
            "quick_hdr", "quick_autoplay", "quick_check_update",
        )
    }

    // --- Backup: per-profile startup landing (dynamic "startup_mode_<id>" keys) ---

    /** Exports all per-profile startup-mode keys as { "<profileId>": "<MODE>" }. */
    suspend fun exportStartupModes(): org.json.JSONObject {
        val prefix = "startup_mode_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    /** Exports stable per-profile Live channel startup targets. */
    suspend fun exportStartupChannels(): org.json.JSONObject {
        val prefix = "startup_channel_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                StartupChannelRef.fromJson(v)?.let { out.put(k.name.removePrefix(prefix), it.toJson()) }
            }
        }
        return out
    }

    /** Exports all per-profile Home config blobs as { "<profileId>": { ... } }. */
    suspend fun exportHomeConfigs(): org.json.JSONObject {
        val prefix = "home_config_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                val blob = runCatching { org.json.JSONObject(v) }.getOrNull() ?: return@forEach
                out.put(k.name.removePrefix(prefix), blob)
            }
        }
        return out
    }

    /** Restores startup modes only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importStartupModes(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val mode = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                if (runCatching { StartupMode.valueOf(mode) }.isSuccess) {
                    prefs[stringPreferencesKey("startup_mode_$pid")] = mode
                }
            }
        }
    }

    /** Restores startup targets after profile/source ids have been remapped by backup import. */
    suspend fun importStartupChannels(
        o: org.json.JSONObject,
        existingProfileIds: Set<Long>,
        sourceIdMap: Map<Long, Long>,
    ) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val raw = o.optJSONObject(key)?.toString() ?: return@forEach
                val ref = StartupChannelRef.fromJson(raw) ?: return@forEach
                val mappedSourceId = sourceIdMap[ref.sourceId] ?: return@forEach
                prefs[stringPreferencesKey("startup_channel_$pid")] =
                    ref.copy(sourceId = mappedSourceId, itemId = -1L).toJson().toString()
            }
        }
    }

    /** A restored specific-channel mode must never point at an absent or unmapped source target. */
    suspend fun repairSpecificStartupModes(profileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            profileIds.forEach { profileId ->
                val modeKey = stringPreferencesKey("startup_mode_$profileId")
                if (prefs[modeKey] == StartupMode.SPECIFIC_CHANNEL.name) {
                    val channel = StartupChannelRef.fromJson(prefs[stringPreferencesKey("startup_channel_$profileId")])
                    if (channel == null) prefs[modeKey] = StartupMode.HOME.name
                }
            }
        }
    }

    /** Restores Home configs only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importHomeConfigs(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val blob = o.optJSONObject(key) ?: return@forEach
                prefs[homeConfigKey(pid)] = blob.toString()
            }
        }
    }

    // --- Backup: per-profile "allow downloads" (dynamic "downloads_allowed_<id>" keys) ---

    /** Exports every profile's download switch as { "<profileId>": true/false }. */
    suspend fun exportDownloadsAllowed(): org.json.JSONObject {
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(DOWNLOADS_ALLOWED_PREFIX) && v is Boolean) out.put(k.name.removePrefix(DOWNLOADS_ALLOWED_PREFIX), v)
        }
        return out
    }

    /** Restores the switch only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importDownloadsAllowed(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull()?.takeIf { it in existingProfileIds } ?: return@forEach
                prefs[downloadsAllowedKey(pid)] = o.optBoolean(key, true)
            }
        }
    }

    // --- Backup: per-profile sports settings (dynamic "sports_config_<id>" keys) ---

    /** Exports all per-profile sports blobs as { "<profileId>": { ... } }. */
    suspend fun exportSportsConfigs(): org.json.JSONObject {
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(SPORTS_CONFIG_PREFIX) && v is String) {
                val blob = runCatching { org.json.JSONObject(v) }.getOrNull() ?: return@forEach
                out.put(k.name.removePrefix(SPORTS_CONFIG_PREFIX), blob)
            }
        }
        return out
    }

    /**
     * Restores sports blobs only for profile ids in [existingProfileIds] (others are dropped safely).
     * Each blob is read through [tv.own.owntv.core.sports.SportsConfig.fromJson] and written back, so
     * the import rules apply and a value this build does not know is left out.
     */
    suspend fun importSportsConfigs(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull()?.takeIf { it in existingProfileIds } ?: return@forEach
                val blob = o.optJSONObject(key) ?: return@forEach
                prefs[sportsConfigKey(pid)] = tv.own.owntv.core.sports.SportsConfig.fromJson(blob.toString()).toJson().toString()
            }
        }
    }

    // --- Backup: per-profile preferred languages (dynamic "pref_audio_lang_<id>" / "pref_sub_lang_<id>") ---

    /** Exports { "<profileId>": { "a": "<audio>", "s": "<subtitle>" } }; a side the profile never chose is absent. */
    suspend fun exportPreferredLanguages(): org.json.JSONObject {
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (v !is String) return@forEach
            val (pid, field) = when {
                k.name.startsWith(PREF_AUDIO_LANG_PREFIX) -> k.name.removePrefix(PREF_AUDIO_LANG_PREFIX) to "a"
                k.name.startsWith(PREF_SUB_LANG_PREFIX) -> k.name.removePrefix(PREF_SUB_LANG_PREFIX) to "s"
                else -> return@forEach
            }
            (out.optJSONObject(pid) ?: org.json.JSONObject().also { out.put(pid, it) }).put(field, v)
        }
        return out
    }

    /** Restores the languages only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importPreferredLanguages(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull()?.takeIf { it in existingProfileIds } ?: return@forEach
                val langs = o.optJSONObject(key) ?: return@forEach
                if (langs.has("a")) prefs[stringPreferencesKey(PREF_AUDIO_LANG_PREFIX + pid)] = langs.optString("a")
                if (langs.has("s")) prefs[stringPreferencesKey(PREF_SUB_LANG_PREFIX + pid)] = langs.optString("s")
            }
        }
    }

    // --- Backup: per-profile "hide new categories" preference (dynamic "hide_new_categories_<id>" keys) ---

    /** Exports all per-profile "hide new categories" preferences as { "<profileId>": true/false }. */
    suspend fun exportHideNewCategories(): org.json.JSONObject {
        val prefix = "hide_new_categories_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is Boolean) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    /** Restores the preference only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importHideNewCategories(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                prefs[booleanPreferencesKey("hide_new_categories_$pid")] = o.getBoolean(key)
            }
        }
    }

    // --- Backup: per-source auto-refresh maps (ride with the SOURCES section, since source/EPG ids
    //     are preserved on restore). Exported as the raw { "<id>": "<EnumName>" } JSON maps. ---

    /** Exports the per-source playlist auto-refresh map as { "<sourceId>": "<mode>" }. */
    suspend fun exportPlaylistAutoRefresh(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.PLAYLIST_AUTO_REFRESH]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /** Exports the per-EPG-source auto-refresh map as { "<epgSourceId>": "<mode>" }. */
    suspend fun exportEpgAutoRefresh(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.EPG_AUTO_REFRESH]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /**
     * Restores the playlist auto-refresh map. Ids not in [existingSourceIds] are dropped; unknown
     * enum values fall back to OFF. Replaces the whole map (SOURCES restore wipes+recreates sources,
     * so pre-restore selections refer to deleted ids). Also marks the legacy refresh migration done
     * so it can never clobber the restored selections.
     */
    suspend fun importPlaylistAutoRefresh(o: org.json.JSONObject, existingSourceIds: Set<Long>) {
        // parse() also carries a backup written before the fixed 24h/48h/7-day entries were replaced
        // over to the equivalent day count, so restoring an old device does not silently reset it to Off.
        val cleaned = sanitizeRefreshMap(o, existingSourceIds) { PlaylistRefresh.parse(it).serialize() }
        context.dataStore.edit { prefs ->
            // Merge-restore: keep the device's existing per-source choices, backup entries win per key.
            val merged = parseRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]) + cleaned
            prefs[Keys.PLAYLIST_AUTO_REFRESH] = org.json.JSONObject(merged as Map<*, *>).toString()
            prefs[Keys.REFRESH_MIGRATED] = true
        }
    }

    /** Restores the EPG auto-refresh map; same semantics as [importPlaylistAutoRefresh]. */
    suspend fun importEpgAutoRefresh(o: org.json.JSONObject, existingEpgSourceIds: Set<Long>) {
        // Round-trips "MANUAL:10" as well as the bare enum names older backups carry, because
        // [EpgRefresh.parse] accepts both and re-serializes whatever it understood.
        val cleaned = sanitizeRefreshMap(o, existingEpgSourceIds) { EpgRefresh.parse(it).serialize() }
        context.dataStore.edit { prefs ->
            val merged = parseRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]) + cleaned
            prefs[Keys.EPG_AUTO_REFRESH] = org.json.JSONObject(merged as Map<*, *>).toString()
        }
    }

    /** Exports the per-EPG-source "use this feed's logos" map as { "<epgSourceId>": "true" }. */
    suspend fun exportEpgUseLogos(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.EPG_USE_LOGOS]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /** Restores the EPG logo-preference map; same merge semantics as [importEpgAutoRefresh]. */
    suspend fun importEpgUseLogos(o: org.json.JSONObject, existingEpgSourceIds: Set<Long>) {
        val cleaned = sanitizeRefreshMap(o, existingEpgSourceIds) { it.toBoolean().toString() }
        context.dataStore.edit { prefs ->
            val merged = parseRefreshMap(prefs[Keys.EPG_USE_LOGOS]) + cleaned
            prefs[Keys.EPG_USE_LOGOS] = org.json.JSONObject(merged as Map<*, *>).toString()
        }
    }

    private fun parseRefreshMap(raw: String?): Map<String, String> {
        val o = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        return out
    }

    private inline fun sanitizeRefreshMap(
        o: org.json.JSONObject,
        existingIds: Set<Long>,
        sanitize: (String) -> String,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { key ->
            val id = key.toLongOrNull() ?: return@forEach
            if (id !in existingIds) return@forEach
            out[key] = sanitize(o.optString(key))
        }
        return out
    }

    // --- Backup: default source (SOURCES section — ids are preserved on restore) ---

    /** Currently selected default source id, or null when none chosen. */
    suspend fun currentDefaultSourceId(): Long? =
        context.dataStore.data.first()[Keys.DEFAULT_SOURCE]?.takeIf { it > 0 }

    /** Restores the default source only when that id survived the restore. */
    suspend fun importDefaultSource(id: Long, existingSourceIds: Set<Long>) {
        if (id in existingSourceIds) setDefaultSource(id)
    }

    // --- Backup: proxy password (handled out-of-band by BackupManager: encrypted or omitted) ---

    /** Current proxy password, for the backup layer to encrypt. Never logged. */
    suspend fun currentProxyPassword(): String = context.dataStore.data.first()[Keys.PROXY_PASS] ?: ""

    /** The user's own TMDB API key — a secret; BackupManager exports it encrypted-only (like the proxy password). */
    suspend fun currentTmdbApiKey(): String = context.dataStore.data.first()[Keys.TMDB_API_KEY] ?: ""

    /** Sets only the proxy password (used on restore once decrypted). Blank clears it. */
    suspend fun setProxyPassword(password: String) {
        context.dataStore.edit { it[Keys.PROXY_PASS] = password }
    }

    // --- Global custom DNS — sibling to the global proxy. Supports plain DNS-over-UDP (host + port)
    //     and DNS-over-HTTPS (DoH) via a URL. No auth is needed for DNS; the server field is not a
    //     secret (it's the DNS server the user wants to use). Backed by DnsConfigHolder, same pattern
    //     as ProxyConfigHolder. ---

    /** Live snapshot of the DNS config as a single object (consumed by DnsConfigHolder). */
    val dnsConfig: Flow<tv.own.owntv.core.network.DnsConfig> = prefsFlow { p ->
        tv.own.owntv.core.network.DnsConfig(
            enabled = p[Keys.DNS_ENABLED] ?: false,
            host = p[Keys.DNS_HOST] ?: "",
            port = p[Keys.DNS_PORT] ?: 53,
            dohUrl = p[Keys.DNS_DOH_URL] ?: "",
        )
    }

    suspend fun saveDns(enabled: Boolean, host: String, port: Int, dohUrl: String) {
        context.dataStore.edit {
            it[Keys.DNS_ENABLED] = enabled
            it[Keys.DNS_HOST] = host.trim()
            it[Keys.DNS_PORT] = port.coerceIn(1, 65535)
            it[Keys.DNS_DOH_URL] = dohUrl.trim()
        }
    }
}

/** Per-profile sports settings blob; the profile id follows. */
internal const val SPORTS_CONFIG_PREFIX = "sports_config_"
internal const val DOWNLOADS_ALLOWED_PREFIX = "downloads_allowed_"

/** Per-profile preferred-language keys; the profile id follows. */
internal const val PREF_AUDIO_LANG_PREFIX = "pref_audio_lang_"
internal const val PREF_SUB_LANG_PREFIX = "pref_sub_lang_"

internal fun activeProfileLangKey(prefs: Preferences, prefix: String): Preferences.Key<String> =
    stringPreferencesKey(prefix + (prefs[SettingsRepository.Keys.ACTIVE_PROFILE] ?: -1L))

/** The active profile's own choice, else the shared setting, else no preference. */
internal fun profileLang(prefs: Preferences, prefix: String, shared: Preferences.Key<String>): String =
    prefs[activeProfileLangKey(prefs, prefix)] ?: prefs[shared] ?: ""
