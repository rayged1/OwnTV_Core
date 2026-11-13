package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.playStreamUrl
import tv.own.owntv.core.database.entity.resolveStreamUrl
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.ForceMpvStore
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.timeshift.TimeshiftDownloader
import tv.own.owntv.core.timeshift.TimeshiftManager
import tv.own.owntv.core.timeshift.TimeshiftServer
import tv.own.owntv.core.timeshift.TimeshiftSession

/**
 * One live channel from "tune" to "playing" or "gave up", for both apps.
 *
 * This is the orchestration the television's `LiveViewModel` and the phone's `LiveTuner` each carried
 * in their own ~600 lines, and which had drifted apart: engine routing ([LiveRouting]), the fallback
 * [LiveLadder] and its "Give up after" alarm, the ExoPlayer ⇄ mpv handover with its decoder-release
 * wait, the Prefer-HLS `.ts` rung, HLS-redirect learning, the Stalker reconnect link and the
 * per-playlist overrides. Each app now keeps only presentation — which channel is on screen, the HUD,
 * history, casting.
 *
 * **Every tune is one job, and a newer one cancels it** ([launch]). Two quick picks can no longer
 * finish in the wrong order, and a catch-up started while a live tune is still resolving is not then
 * overtaken by it.
 *
 * Everything is main-thread: [scope] must be a Main-dispatched scope, as the engines require.
 */
class LiveTuneController(
    private val scope: CoroutineScope,
    private val engines: LiveEngines,
    private val host: Host,
) {

    /** What the controller needs from outside the engines. [CoreHost] is the one both apps use. */
    interface Host {
        suspend fun sourceOf(sourceId: Long): SourceEntity?
        /** A Stalker playlist stores a portal command, minted into a URL per play. */
        fun needsResolve(source: SourceEntity?): Boolean
        suspend fun resolve(source: SourceEntity, cmd: String): String?
        /** true = pinned to mpv, false = pinned to ExoPlayer, null = follow the setting. */
        suspend fun enginePin(channel: ChannelEntity): Boolean?
        suspend fun pin(channel: ChannelEntity, onMpv: Boolean)
        suspend fun globalPreference(): EnginePreference
        /** Settings → "Give up on a channel after", in seconds; 0 is Never. */
        suspend fun globalBudgetSecs(): Int
        fun meta(channel: ChannelEntity): MediaMeta
        /** Mirror a ladder decision into the user-visible playback error log. */
        fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String)
        fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
        /** An engine has just taken the stream (the phone publishes its media session here). */
        fun onEngineStarted() {}
        /** A handover is re-opening the channel at its live edge, so any rewind is over. */
        fun onBackToLiveEdge() {}
        /** The saved-copy buffers; null where there are none (tests). */
        val timeshift: TimeshiftManager? get() = null
        /** Settings → the window in minutes while local timeshift is on; null while it is off. */
        suspend fun timeshiftWindowMinutes(): Int? = null
        /** Settings → what coming back to a kept copy does ("Continue where you left off?"). */
        suspend fun timeshiftResumeMode(): SettingsRepository.ResumeMode = SettingsRepository.ResumeMode.ASK
        /** Settings → Maximum video quality, for the variant a buffer saves; null for none. */
        suspend fun maxVideoHeight(): Int? = null
        /** A download is using the only stream [sourceId] allows, so the preview pane must not take it. */
        fun downloadHoldsOnlyStream(sourceId: Long): Boolean = false
    }

    /**
     * The channel on screen is playing from its saved copy ([session]: how far back it reaches, its
     * gaps; [timeshiftWatchingWallMs]: what is on screen). [resumeAtWallMs] is set when the user came back
     * to a channel they had left: where they were, for "Resume from buffer / Go live".
     */
    class LocalTimeshift(
        val session: TimeshiftSession,
        val resumeAtWallMs: Long?,
    )

    private val _liveOnExo = MutableStateFlow(false)

    /**
     * Whether live is on ExoPlayer right now — false is mpv. The *actual* engine rather than the pin:
     * an automatic handover leaves a channel on mpv while still unpinned.
     */
    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()

    private val _previewBlocked = MutableStateFlow(false)

    /** True while the preview pane is held back because its panel allows one stream and mpv — or a
     *  download — has it. */
    val previewBlockedSingleSession: StateFlow<Boolean> = _previewBlocked.asStateFlow()

    private val recall = ChannelRecall()

    private var ts: TimeshiftSession? = null
    /** The piece the engine's stream starts at; null is the live edge. */
    private var tsFromIndex: Long? = null
    private var tsRequest: LiveRequest? = null
    private var tsWindowSec = 0
    private val _localTimeshift = MutableStateFlow<LocalTimeshift?>(null)

    /** Non-null while the channel plays from its saved copy (see [LocalTimeshift]). */
    val localTimeshift: StateFlow<LocalTimeshift?> = _localTimeshift.asStateFlow()

    init {
        host.timeshift?.let { manager ->
            scope.launch {
                manager.gaveUp.collect { (token, why) -> if (ts?.token == token) onTimeshiftLost(why) }
            }
        }
    }

    /** The channel watched before the one on screen — the "previous channel" key's target. The app
     *  still vets it (profile, playlists, adult filter) before tuning it. */
    val previousChannel: StateFlow<ChannelEntity?> = recall.previous

    /** A channel the user is watching that did not go through [start] — the phone's cast hand-off, where
     *  the receiver plays it. Without this, casting would leave "previous channel" pointing at the past. */
    fun noteWatched(channel: ChannelEntity) = recall.onWatched(channel)

    /** The channel this controller is tuning or playing full-screen; null when it is not driving one. */
    private var current: ChannelEntity? = null

    private val ladder = LiveLadder()
    private var armedBudgetMs: Long = LiveLadder.NO_BUDGET

    /**
     * The channel whose ExoPlayer rung is the explicit `.ts` one. Keyed by channel, not a bare flag: the
     * preview tunes through [exoUrlFor] too, so a flag left set by one channel's TS rung would send every
     * other channel's preview to `.ts` until the next tune.
     */
    private var forceTsFor: String? = null

    /** The Stalker command whose minted URL ExoPlayer holds, so a re-focus or a promote can tell "same
     *  channel" without minting again — the engine's own URL is the minted link, not the command. */
    private var exoStalkerCmd: String? = null

    /** When this controller last stopped ExoPlayer, for [awaitExoRelease]. */
    private var exoStoppedAtMs: Long? = null

    /** How many times this tune has rewound its saved copy because the feed ran dry. */
    private var starvedRewinds = 0

    /** A rewind that worked, waiting to see whether the link holds before rejoining the live edge. */
    private var catchUpJob: Job? = null

    private var tuneJob: Job? = null
    private var previewJob: Job? = null
    private var exoResolveJob: Job? = null
    private var exoWatchJob: Job? = null
    private var mpvOutcomeJob: Job? = null
    private var mpvHandoffJob: Job? = null
    private var deadlineJob: Job? = null

    // --- Entry points -----------------------------------------------------------------------------

    /**
     * Run [block] as the one tune in flight, cancelling whichever was running. Every playback-changing
     * entry point of an app goes through here — a tune, a catch-up, a retune at the live edge — so the
     * last thing asked for is the thing that happens.
     */
    fun launch(block: suspend LiveTuneController.() -> Unit): Job {
        tuneJob?.cancel()
        return scope.launch { block() }.also { tuneJob = it }
    }

    /** Tune [channel] full-screen, as the one tune in flight. */
    fun tune(channel: ChannelEntity): Job = launch { start(channel, host.sourceOf(channel.sourceId)) }

    /**
     * Route [channel], arm its ladder and start the first engine. Called from inside [launch].
     *
     * [resolved] is a URL already minted for a Stalker channel by this tune (the phone mints one before
     * offering the channel to a cast receiver): minting ends the portal's previous session, so the first
     * rung reuses it and only later rungs mint again.
     */
    suspend fun start(channel: ChannelEntity, source: SourceEntity?, resolved: String? = null) {
        previewJob?.cancel()
        cancelLadderJobs()
        current = channel
        recall.onWatched(channel)
        _previewBlocked.value = false
        val setting = source?.liveEnginePreference
            ?.let { name -> EnginePreference.entries.firstOrNull { it.name == name } }
            ?: host.globalPreference()
        val route = LiveRouting.decide(
            setting = setting,
            pin = host.enginePin(channel),
            drmProtected = channel.drmConfig != null,
            panelRefusesSegments = panelRefusesSegments(channel, source),
        )
        engineLog("tune '${channel.name}' -> ${route.why} [${route.preference.name}]")
        openTimeshift(channel, source, resolved)
        arm(channel, source, route.preference)
        when {
            route.onMpv -> startOnMpv(channel, source, route.why, resolved = resolved)
            // The preview already found that ExoPlayer can't open this channel. Promoting it put the
            // preview's error on screen for ~1 s while the ladder moved on to mpv: go there directly.
            previewedChannelId == channel.id && engines.exoFailed -> {
                engineLog("'${channel.name}' — its ExoPlayer preview had already failed; trying the next engine")
                advance(channel, source, "the ExoPlayer preview had already failed", aboutFormat = false)
            }
            else -> startOnExo(channel, source, resolved)
        }
    }

    /** The channel the preview pane last asked ExoPlayer for (see [expectPromotion]). */
    private var previewedChannelId: Long? = null

    /** The pane's last [preview] and its mute, to open it again directly when its saved copy is lost. */
    private var lastPreview: Pair<ChannelEntity, Boolean>? = null

    /**
     * The user is opening [channel] full screen. When ExoPlayer is already previewing it, say so at once,
     * before the full-screen player is first drawn: it then shows ExoPlayer's surface from the start
     * instead of building mpv's and swapping it out when [start] gets there. Measured on a 4K channel:
     * ~4 s of black on opening, with the engine pill reading "MPV" meanwhile. [start] still decides the
     * engine, and turns this off again if that is mpv.
     */
    fun expectPromotion(channel: ChannelEntity) {
        if (previewedChannelId == channel.id && engines.exoUrl != null && !engines.exoFailed) _liveOnExo.value = true
    }

    /**
     * The HUD's engine button: flip the channel on screen between the two engines and pin the choice.
     *
     * For THIS tune the ladder is armed "only" on the engine the user just named — bouncing them off it
     * seconds later makes the control look broken. The next tune of the channel reads the pin and gets
     * the full ladder back. The app refuses first while a catch-up or a rewind is on screen.
     */
    fun toggleEngine() {
        val channel = current ?: return
        // A protected channel has only one engine that can obtain its key.
        if (channel.drmConfig != null) return
        val goToMpv = _liveOnExo.value
        // On a saved copy the other engine continues at the moment on screen, not at the live edge.
        ts?.let { session ->
            if (behindLive()) timeshiftWatchingWallMs()?.let { tsFromIndex = session.pieceAt(it) }
        }
        engineLog("engine toggle '${channel.name}' -> ${if (goToMpv) "mpv" else "exoplayer"}")
        launch {
            host.pin(channel, goToMpv)
            val source = host.sourceOf(channel.sourceId)
            arm(channel, source, EnginePreference.onlyOn(goToMpv))
            if (goToMpv) startOnMpv(channel, source, "user chose compatibility mode")
            else switchToExo(channel, source)
        }
    }

    /**
     * The television's preview pane: play [channel] on ExoPlayer, muted or not, with no ladder.
     *
     * Never while promoted to full-screen — a late preview would re-mute the full-screen stream. On a
     * one-session panel a preview while mpv plays would lock the user's own stream out, so the pane
     * stays silent and [previewBlockedSingleSession] says why.
     */
    fun preview(channel: ChannelEntity, muted: Boolean) {
        lastPreview = channel to muted
        if (_liveOnExo.value) {
            if (current != null) return
            // An [expectPromotion] no tune followed (an external player, a refused channel): the pane is
            // still the pane, and must not stay frozen on the last channel.
            _liveOnExo.value = false
        }
        previewedChannelId = channel.id
        ts?.let { session ->
            // The pane already shows this channel from its saved copy (Back from full screen): it keeps
            // saving, because the user has not left the channel.
            if (session.channelKey == timeshiftKey(channel) && TimeshiftServer.isLocal(engines.exoUrl) && !engines.exoFailed) {
                previewJob?.cancel()
                engines.exoSetMuted(muted)
                return
            }
            // Browsing to another channel is leaving this one.
            parkTimeshift()
        }
        previewJob?.cancel()
        previewJob = scope.launch {
            val source = host.sourceOf(channel.sourceId)
            if (host.needsResolve(source)) {
                if (exoStalkerCmd == channel.streamUrl && !engines.exoFailed) {
                    engines.exoSetMuted(muted)
                    return@launch
                }
                val url = host.resolve(source!!, channel.streamUrl) ?: return@launch
                if (_liveOnExo.value) return@launch // promoted to full-screen while resolving
                if (blockedBySingleSession(url, channel)) return@launch
                exoStalkerCmd = channel.streamUrl
                setReconnect(channel, source)
                engines.exoPlay(url, muted, request(channel, source))
                return@launch
            }
            val url = exoUrlFor(channel, source)
            if (blockedBySingleSession(url, channel)) return@launch
            if (engines.exoUrl == url && !engines.exoFailed) {
                engines.exoSetMuted(muted) // already previewing it (a re-focus) — just re-apply the mute
                return@launch
            }
            exoStalkerCmd = null
            setReconnect(null, null)
            engines.exoPlay(url, muted, request(channel, source))
        }
    }

    /**
     * Tune [channel] into a Multiview tile's own [engine] — same URL, headers, overrides and Stalker
     * minting as a full-screen tune, so the grid can never drift from it. No ladder: a tile that fails
     * shows its failure.
     */
    suspend fun playTile(engine: LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        val source = host.sourceOf(channel.sourceId)
        val url = if (host.needsResolve(source)) {
            host.resolve(source!!, channel.streamUrl) ?: return
        } else {
            exoUrlFor(channel, source)
        }
        engine.play(url, muted, request(channel, source))
    }

    /**
     * Stop driving the channel but leave the stream playing — leaving full-screen for the Live screen,
     * where the pane re-takes ExoPlayer as a preview. Watchers and the alarm stand down: a preview has
     * no ladder.
     */
    fun detach() {
        tuneJob?.cancel()
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
    }

    /** Stop ExoPlayer (the preview, or a detached stream) without touching mpv. */
    fun stopExo() {
        previewJob?.cancel()
        exoResolveJob?.cancel()
        exoWatchJob?.cancel()
        exoStalkerCmd = null
        setReconnect(null, null)
        stopExoEngine()
    }

    /** Cancel the tune in flight, from outside [launch]. */
    fun cancelTune() {
        tuneJob?.cancel()
    }

    /**
     * Give playback to mpv for something that is not the live stream — a catch-up programme, a rewind
     * into the archive, a film. ExoPlayer lets go of its connection and decoder first, or the live
     * stream plays on underneath with a second sound. The whole ladder goes too: an alarm left armed
     * would stop a replay that plays perfectly well.
     *
     * Does not cancel the tune in flight, because a catch-up calls this from inside its own [launch] —
     * which is what cancels a live tune still resolving, so it cannot start over the archive. A caller
     * outside [launch] uses [cancelTune] first.
     */
    fun releaseForArchive() {
        parkTimeshift()
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
        stopExo()
    }

    /** Stop everything: both engines, every watcher. */
    fun stop() {
        engines.starvedStallHook = null
        catchUpJob?.cancel()
        cancelTune()
        releaseForArchive()
        engines.mpvStop()
        host.timeshift?.watching(null)
    }

    // --- Local timeshift ---------------------------------------------------------------------

    /**
     * Show the saved copy from wall-clock instant [wallMs], or from the live edge when null. The engine
     * on screen re-opens the copy at the piece holding that instant — the same "open the stream at a
     * point" a catch-up rewind does. No-op while the channel is not playing from a copy.
     */
    fun seekTimeshift(wallMs: Long?) {
        val session = ts ?: return
        val request = tsRequest ?: return
        if (current == null) return
        tsFromIndex = if (wallMs == null) null else session.pieceAt(wallMs) ?: return
        publishTimeshift(resumeAtWallMs = null)
        val url = localUrl() ?: return
        if (_liveOnExo.value) engines.exoPlay(url, muted = false, request) else engines.mpvPlay(url, request)
    }

    /**
     * The channel's saved copy as a rewind source for [tv.own.owntv.core.live.LiveTimeshift]: the
     * apps' existing rewind bar, counter, "Go back to…" list and "Go to live" work on it unchanged.
     */
    val localRewind: tv.own.owntv.core.live.LiveTimeshift.Local = object : tv.own.owntv.core.live.LiveTimeshift.Local {
        override fun windowSec(ch: ChannelEntity): Int? =
            ts?.takeIf { it.channelKey == timeshiftKey(ch) && current?.id == ch.id }?.let { tsWindowSec }
        override fun depthSec(): Int {
            val session = ts ?: return 0
            val oldest = session.oldestWallMs() ?: return 0
            val edge = session.playableEdgeWallMs() ?: return 0
            return ((edge - oldest) / 1000).toInt().coerceAtLeast(0)
        }
        override fun watchingWallMs(): Long? = if (current == null) null else timeshiftWatchingWallMs()
        override fun liveEdgeWallMs(): Long? = ts?.playableEdgeWallMs()
        override fun seek(wallMs: Long?) = seekTimeshift(wallMs)
    }

    /** The "Resume from buffer / Go live" question has been answered (or ignored). */
    fun dismissResumeOffer() = publishTimeshift(resumeAtWallMs = null)

    /** The wall-clock spans the saved copy has no picture for (the connection dropped); empty without a copy. */
    fun localGaps(): List<LongRange> = ts?.gaps().orEmpty()

    /** What is on screen on the wall clock, while playing from a copy. */
    fun timeshiftWatchingWallMs(): Long? {
        val session = ts ?: return null
        return session.readingFromWallMs?.plus(engines.positionMs(_liveOnExo.value))
    }

    /**
     * Start (or wake, or keep) [channel]'s saved copy when local timeshift applies: Settings on, a live
     * channel with no provider archive (catch-up channels rewind into that instead), not protected. The
     * buffer is the only connection to the provider from here on, so nothing of ours may still hold the
     * channel when it connects. Returns false — and the channel plays the ordinary way — whenever the
     * copy cannot be made.
     */
    private suspend fun openTimeshift(channel: ChannelEntity, source: SourceEntity?, resolved: String?): Boolean {
        val manager = host.timeshift ?: return false
        val key = timeshiftKey(channel)
        // The channel being left, parked by the manager with what was on screen — see [TimeshiftManager.open].
        val leaving = ts?.takeIf { it.channelKey != key }
        val leavingWallMs = leaving?.let { timeshiftWatchingWallMs() }
        val window = if (channel.catchup || channel.drmConfig != null) null else host.timeshiftWindowMinutes()
        if (window == null) {
            if (leaving != null) parkTimeshift()
            manager.watching(key)
            clearTimeshift()
            return false
        }
        if (engines.exoUrl != null && !TimeshiftServer.isLocal(engines.exoUrl)) stopExoEngine()
        if (engines.mpvHasStream) engines.mpvStopAndAwaitRelease()
        val stalker = host.needsResolve(source)
        var minted = resolved
        val headers = StreamHeaders.decode(SourceOverrides.headersWithReferer(channel.httpHeaders, source))
        val target = TimeshiftDownloader.Target(
            url = {
                val first = minted
                when {
                    !stalker -> channel.streamUrl
                    // The tune's own link first; every reconnect mints a fresh one (a link is single-use).
                    first != null -> first.also { minted = null }
                    else -> host.resolve(source!!, channel.streamUrl)
                }
            },
            userAgent = StreamHeaders.userAgentOf(headers)
                ?: source?.userAgent?.takeIf { it.isNotBlank() }
                ?: StalkerClient.DEFAULT_MAG_USER_AGENT.takeIf { stalker }
                ?: HttpClient.FALLBACK_USER_AGENT.takeIf { LiveStreamQuirks.blocksDefaultUserAgent(channel.streamUrl) }
                ?: HttpClient.DEFAULT_USER_AGENT,
            headers = headers,
            maxVideoHeight = host.maxVideoHeight(),
            sourceId = channel.sourceId,
        )
        val opened = manager.open(key, target, window, TIMESHIFT_FIRST_PIECE_MS, leavingWallMs)
        if (opened == null) {
            engineLog("timeshift: '${channel.name}' cannot be saved — playing it directly")
            clearTimeshift()
            return false
        }
        ts = opened.session
        val resumeAt = opened.resumeAtWallMs
        val mode = if (resumeAt == null) SettingsRepository.ResumeMode.ASK else host.timeshiftResumeMode()
        // AUTO opens the copy where the user was; NEVER plays it from the live edge. Neither asks.
        tsFromIndex = if (mode == SettingsRepository.ResumeMode.AUTO && resumeAt != null) opened.session.pieceAt(resumeAt) else null
        tsWindowSec = window * 60
        // The copy is plain TS or fragmented MP4 on loopback: no container hint, no fallback address,
        // no licence, and the provider's headers stay with the downloader.
        tsRequest = request(channel, source).copy(httpHeaders = null, drmConfig = null, manifestType = null, directSource = null)
        publishTimeshift(resumeAtWallMs = resumeAt.takeIf { mode == SettingsRepository.ResumeMode.ASK })
        engineLog("timeshift: '${channel.name}' plays from its saved copy")
        return true
    }

    /** The user left the channel: stop saving it, but keep it for a return. */
    private fun parkTimeshift() {
        val session = ts ?: return
        host.timeshift?.park(session, timeshiftWatchingWallMs())
        clearTimeshift()
    }

    private fun clearTimeshift() {
        ts = null
        tsRequest = null
        _localTimeshift.value = null
    }

    private fun publishTimeshift(resumeAtWallMs: Long?) {
        val session = ts ?: return
        _localTimeshift.value = LocalTimeshift(session = session, resumeAtWallMs = resumeAtWallMs)
    }

    /** The copy on screen is further behind its live edge than a player keeps buffered (a pause). */
    private fun behindLive(): Boolean {
        val watching = timeshiftWatchingWallMs() ?: return false
        val edge = ts?.playableEdgeWallMs() ?: return false
        return edge - watching > LIVE_SLACK_MS
    }

    private fun localUrl(): String? = ts?.takeIf { !it.isClosed }?.let { TimeshiftServer.urlFor(it, tsFromIndex) }

    /**
     * Rewind the saved copy when the stall is the feed running dry, and say whether it did.
     *
     * Both engines read this copy through the same loopback ring, so an engine swap cannot produce a
     * byte the ring does not have; re-opening the copy a little further back does. [MAX_STARVED_REWINDS]
     * caps how long one outage can be papered over (≈2 minutes at the default step), after which the
     * ladder behaves exactly as before.
     */
    private fun rewindStarvedCopy(channel: ChannelEntity, source: SourceEntity?, reason: String): Boolean {
        val session = ts?.takeIf { !it.isClosed }
        val local = TimeshiftServer.isLocal(engines.exoUrl)
        // Prefer the sample taken when it stalled: by now the engine's own reconnect may have re-primed
        // the buffer, which makes the live number say nothing about why it stopped.
        val ahead = engines.exoBufferedAheadAtStallMs.takeIf { it >= 0L } ?: engines.exoBufferedAheadMs
        // Where the playhead sits in the ring, or — when the engine has already dropped its reader — the
        // copy's own playable edge, which is where a starved playhead was sitting anyway.
        val watching = timeshiftWatchingWallMs() ?: session?.playableEdgeWallMs()
        val piece = watching?.let { at -> session?.pieceAt(at - STARVED_REWIND_MS) }
        // Re-opening the piece already on screen is not automatically a no-op: it restarts at that
        // piece's start, which is a real rewind once the playhead has moved on from there. Measured at a
        // cell hand-off — the first rewind lands on the copy's oldest piece, the feed stays dead, and
        // 20 s later "20 s back" is that same piece — so the guard declined the only move left to make.
        val pieceStart = piece?.let { session?.wallStartOf(it) }
        val pieceGainMs = if (watching != null && pieceStart != null) watching - pieceStart else 0L
        val edge = session?.playableEdgeWallMs()
        val behind = if (watching != null && edge != null) edge - watching else -1L
        val declined = when {
            session == null -> "the copy is closed"
            !local -> "not playing the copy"
            ahead < 0L -> "the engine reports no media"
            ahead > STARVED_AHEAD_MS -> "still ${ahead}ms ahead"
            starvedRewinds >= MAX_STARVED_REWINDS -> "already rewound $starvedRewinds times"
            watching == null -> "no playhead position"
            piece == null -> "nothing saved ${STARVED_REWIND_MS / 1000}s back"
            piece == tsFromIndex && pieceGainMs < STARVED_REWIND_MIN_GAIN_MS -> "already at that piece"
            else -> null
        }
        if (declined != null) {
            // Only near the starved signature: this is the line that says why a stall was not rewound.
            if (local && ahead in 0..STARVED_LOG_MS) {
                engineLog("'${channel.name}' is starved ($ahead ms ahead, ${behind}ms behind live) but not rewinding: $declined")
            }
            return false
        }
        starvedRewinds++
        tsFromIndex = piece
        engineLog(
            "'${channel.name}' has ${ahead}ms ahead of the playhead and is ${behind}ms behind live — the feed " +
                "stopped, not the engine; " +
                "rewinding its saved copy ${STARVED_REWIND_MS / 1000}s ($reason)",
        )
        // Detached: re-opening the copy re-arms the watchers, and the watcher that called this would
        // cancel itself at its first suspension point.
        scope.launch { startOnExo(channel, source) }
        scheduleCatchUp(channel)
        return true
    }

    /**
     * The saved copy has nothing older left to replay from where the picture is sitting.
     *
     * Both engines read this ring through the same loopback address, so a hand-off here cannot produce a
     * byte the ring does not have; it only costs the black screen of the swap. Measured at a cell
     * hand-off: ExoPlayer was refused a rewind ("already at that piece"), the ladder handed over 15 s
     * later, and mpv opened the very same piece — it looked like a rescue only because the feed returned
     * at that moment.
     */
    private fun copyHasNothingOlder(): Boolean {
        val session = ts?.takeIf { !it.isClosed } ?: return false
        if (!TimeshiftServer.isLocal(engines.exoUrl)) return false
        // Only the starved signature: a stall with picture still buffered ahead is the ladder's business.
        val ahead = engines.exoBufferedAheadAtStallMs.takeIf { it >= 0L } ?: engines.exoBufferedAheadMs
        if (ahead > STARVED_AHEAD_MS) return false
        val watching = timeshiftWatchingWallMs() ?: session.playableEdgeWallMs() ?: return false
        val piece = session.pieceAt(watching - STARVED_REWIND_MS) ?: return false
        if (piece != tsFromIndex) return false
        val start = session.wallStartOf(piece) ?: return false
        return watching - start < STARVED_REWIND_MIN_GAIN_MS
    }

    /**
     * Walk a rewound picture back toward the live edge once the link has proved it holds.
     *
     * A rewind always lands [STARVED_REWIND_MS] back and playback there runs at 1x, so one hand-off
     * leaves the channel permanently behind the broadcast — measured on a commute, `behind live` read
     * 2.5 s, then 4.1 s, then 29.4 s for the rest of the drive, because nothing ever seeks forward.
     * Wait [CATCH_UP_AFTER_MS] of steady playback first: a link that is still flapping must not be
     * thrown back to the edge, where there is no read-ahead at all. Then re-open [CATCH_UP_KEEP_MS]
     * behind the edge, which wipes out the accumulated delay and still leaves a cushion.
     *
     * One attempt per starved rewind, so a bad stretch cannot become a seek loop.
     */
    private fun scheduleCatchUp(channel: ChannelEntity) {
        catchUpJob?.cancel()
        catchUpJob = scope.launch {
            delay(CATCH_UP_AFTER_MS)
            if (!isStillExo(channel)) return@launch
            val session = ts?.takeIf { !it.isClosed } ?: return@launch
            if (engines.exoBufferedAheadMs < CATCH_UP_MIN_AHEAD_MS) return@launch
            val watching = timeshiftWatchingWallMs() ?: return@launch
            val edge = session.playableEdgeWallMs() ?: return@launch
            val behind = edge - watching
            if (behind <= CATCH_UP_BEHIND_MS) return@launch
            engineLog("'${channel.name}' has held ${CATCH_UP_AFTER_MS / 1000}s at ${behind / 1000}s behind live — rejoining ${CATCH_UP_KEEP_MS / 1000}s behind the edge")
            seekTimeshift(edge - CATCH_UP_KEEP_MS)
        }
    }

    /**
     * The copy could not reconnect (an outage longer than its reconnect budget): the channel is opened again
     * instead. Giving up here left the engine on the copy's closed loopback address, and its own "network
     * restored" retry asked that address for ever (404) — "Reconnecting…" that never ended. A fresh tune
     * starts a new copy when the provider answers, or plays the channel directly when it does not, and the
     * live engines' own outage handling takes it from there.
     */
    private fun onTimeshiftLost(why: String) {
        val lostKey = ts?.channelKey
        val channel = current
        clearTimeshift()
        if (channel != null && (isStillExo(channel) || isStillMpv(channel))) {
            engineLog("'${channel.name}' — its saved copy lost the connection ($why); opening it again")
            tune(channel)
            return
        }
        // The pane was showing the copy (Back from full screen keeps it saving): preview it directly.
        val pane = lastPreview?.takeIf { timeshiftKey(it.first) == lostKey && TimeshiftServer.isLocal(engines.exoUrl) } ?: return
        engineLog("'${pane.first.name}' — the pane's saved copy lost the connection ($why); previewing it directly")
        preview(pane.first, pane.second)
    }

    private fun timeshiftKey(channel: ChannelEntity): String = "${channel.sourceId}:${channel.id}"

    // --- Engines ----------------------------------------------------------------------------------

    /**
     * Open [channel] on ExoPlayer and watch it. If ExoPlayer already holds this channel — the preview
     * the user just pressed OK on — it is promoted (unmuted) instead of rebuilt.
     */
    private suspend fun startOnExo(channel: ChannelEntity, source: SourceEntity?, resolved: String? = null) {
        mpvOutcomeJob?.cancel() // ExoPlayer owns the channel now
        _liveOnExo.value = true
        if (engines.mpvHasStream && singleSessionPanel(channel, source)) {
            // mpv's stop is asynchronous: on a one-connection panel ExoPlayer opening while mpv still holds
            // the previous channel's socket is the app competing with itself for the only session (458).
            // Wait for mpv to let go first; bounded by mpv's release timeout, and only on such a panel.
            engines.mpvStopAndAwaitRelease()
            if (!isCurrent(channel)) return // zapped on while mpv released
        } else {
            engines.mpvStop() // mpv lets go of the connection and the decoder before ExoPlayer claims either
        }
        val local = localUrl()
        if (local != null) {
            // The same address is not the same picture after a pause: "live" then means re-opening it.
            if (engines.exoUrl == local && !behindLive()) {
                engines.exoSetMuted(false)
            } else {
                engines.exoReleaseUhdDecoder()
                exoStalkerCmd = null
                setReconnect(null, null)
                engines.exoPlay(local, muted = false, tsRequest ?: request(channel, source))
            }
            host.onEngineStarted()
            watchExo(channel, source)
            return
        }
        if (host.needsResolve(source)) {
            startOnExoStalker(channel, source!!, resolved)
            return
        }
        val url = exoUrlFor(channel, source)
        if (engines.exoUrl == url) {
            engines.exoSetMuted(false) // promote — instant if already playing, otherwise keeps loading
        } else {
            // Leaving a UHD channel: release its 4K decoder before the rebuild (no-op for SD/HD).
            engines.exoReleaseUhdDecoder()
            exoStalkerCmd = null
            setReconnect(null, null)
            engines.exoPlay(url, muted = false, request(channel, source))
        }
        host.onEngineStarted()
        watchExo(channel, source)
    }

    private suspend fun startOnExoStalker(channel: ChannelEntity, source: SourceEntity, resolved: String?) {
        if (exoStalkerCmd == channel.streamUrl && resolved == null) {
            engines.exoSetMuted(false) // promote the preview that already holds this command
            setReconnect(channel, source)
            host.onEngineStarted()
            watchExo(channel, source)
            return
        }
        exoResolveJob?.cancel()
        engines.exoReleaseUhdDecoder()
        if (resolved != null) {
            // Already minted by this tune: start now, so the caller's next line finds the stream there.
            playExoStalker(channel, source, resolved)
            return
        }
        // Detached: minting is a network call, and a ladder step that got here from a watcher must not
        // die with that watcher when [watchExo] replaces it.
        exoResolveJob = scope.launch {
            val url = host.resolve(source, channel.streamUrl) ?: return@launch
            if (!isCurrent(channel)) return@launch // zapped away while minting
            playExoStalker(channel, source, url)
        }
    }

    private fun playExoStalker(channel: ChannelEntity, source: SourceEntity, url: String) {
        exoStalkerCmd = channel.streamUrl
        setReconnect(channel, source)
        engines.exoPlay(url, muted = false, request(channel, source))
        host.onEngineStarted()
        watchExo(channel, source)
    }

    /** Put [channel] on ExoPlayer, releasing mpv first when it holds the stream — mpv's stop is
     *  asynchronous, and handing over too early makes the app its own competitor for a session. */
    private suspend fun switchToExo(channel: ChannelEntity, source: SourceEntity?) {
        host.onBackToLiveEdge()
        if (!_liveOnExo.value) {
            engines.mpvStopAndAwaitRelease()
            delay(OwnTVPlayer.SURFACE_HANDOFF_MS)
            if (!isCurrent(channel)) return
        }
        startOnExo(channel, source)
    }

    /**
     * Open [channel] on mpv — a pinned channel, "mpv first", or ExoPlayer having given up.
     *
     * [forceTs] marks the `.ts` rung: "Prefer HLS" must not rewrite the URL. [resolved] is the first
     * rung's already-minted Stalker link; later rungs mint afresh, because a spent link is not reusable.
     */
    private suspend fun startOnMpv(
        channel: ChannelEntity,
        source: SourceEntity?,
        reason: String,
        forceTs: Boolean = false,
        resolved: String? = null,
    ) {
        engineLog("starting mpv for '${channel.name}' — reason=$reason")
        host.onBackToLiveEdge()
        // What ExoPlayer discovered before it let go: some panels redirect their advertised `.ts` to HLS,
        // and handing mpv that misleading URL traps FFmpeg at the manifest EOF. "Discovered" strictly
        // means ExoPlayer asked for something that was NOT HLS and got HLS anyway — an `.m3u8` we
        // requested teaches nothing about the `.ts` endpoint, and recording it branded a whole panel.
        val exoTuned = engines.exoUrl
        val exoDiscoveredHls = _liveOnExo.value && engines.exoIsHls &&
            exoTuned != null && !LiveStreamQuirks.isExplicitHlsUrl(exoTuned)
        exoWatchJob?.cancel() // mpv owns the channel now
        exoResolveJob?.cancel()
        mpvOutcomeJob?.cancel()
        _liveOnExo.value = false
        exoStalkerCmd = null
        stopExoEngine()
        awaitExoRelease()
        if (!isCurrent(channel)) {
            // The only exit that leaves the screen on mpv's surface with nothing loaded. Normal when the
            // user zapped during the release wait; in a support log it tells "abandoned" from "vanished".
            engineLog("mpv handoff for '${channel.name}' abandoned — the channel changed while ExoPlayer released")
            return
        }
        localUrl()?.let { local ->
            setReconnect(null, null)
            engines.mpvPlay(local, (tsRequest ?: request(channel, source)).copy(expectVideo = true))
            host.onEngineStarted()
            watchMpv(channel, source)
            return
        }
        val stalker = host.needsResolve(source)
        val raw = when {
            !stalker -> channel.streamUrl
            resolved != null -> resolved
            else -> host.resolve(source!!, channel.streamUrl) ?: return
        }
        // Same "Prefer HLS" opt-out as the ExoPlayer URL, but keyed to mpv's OWN verdict: ExoPlayer
        // failing this channel's `.m3u8` says nothing about whether mpv can play it.
        val preferred = if (forceTs || LiveStreamQuirks.lacksHlsVariantMpv(channel.streamUrl)) raw
            else resolveStreamUrl(raw, source)
        // Against the PANEL: the redirect is the provider's, so every later channel starts out knowing.
        if (exoDiscoveredHls) LiveStreamQuirks.rememberHlsRedirect(preferred)
        val url = if (LiveStreamQuirks.isKnownHls(preferred)) LiveStreamQuirks.toHlsUrl(preferred) else preferred
        if (!isCurrent(channel)) return // zapped away while minting
        setReconnect(if (stalker) channel else null, source)
        engines.mpvPlay(url, request(channel, source).copy(expectVideo = true))
        host.onEngineStarted()
        watchMpv(channel, source)
    }

    /**
     * Let ExoPlayer's decoder go before mpv initialises. Nothing exposes "the MediaCodec is released",
     * so the only alternative to waiting is guessing, and guessing short reproduces the 0x80001000
     * codec-claim failure. Measured at ≈500 ms on a Realtek box.
     *
     * Only the part of the wait that has not already passed since ExoPlayer was last stopped: a tune
     * that starts on mpv with nothing on ExoPlayer for a while has no decoder to wait for, and half a
     * second of black on each of those would be a handover cost charged to a tune that never handed over.
     */
    private suspend fun awaitExoRelease() {
        val stoppedAt = exoStoppedAtMs ?: return
        val left = OwnTVPlayer.SURFACE_HANDOFF_MS - (host.nowMs() - stoppedAt)
        if (left > 0) delay(left)
    }

    private fun stopExoEngine() {
        if (engines.exoUrl != null) exoStoppedAtMs = host.nowMs()
        engines.exoStop()
    }

    // --- Watchers ---------------------------------------------------------------------------------

    private fun watchExo(channel: ChannelEntity, source: SourceEntity?) {
        exoWatchJob?.cancel()
        // A stall never reaches [advance] — the engine reconnects it itself — so the rewind has to be
        // offered from the engine's own watchdog. See [LivePreviewEngine.starvedStallHook].
        engines.starvedStallHook = { reason ->
            isStillExo(channel) && rewindStarvedCopy(channel, source, reason)
        }
        exoWatchJob = scope.launch {
            engines.watchExo(
                channelName = channel.name,
                // A watchdog outlives the tune that armed it; firing after that would stop a stream
                // nobody complained about.
                stillOurs = { isStillExo(channel) },
                // Into the ladder rather than straight to mpv: the ladder decides what "next" means —
                // except when the ring is dry and the engine is still reconnecting, where the next rung
                // reads the same drained copy and only costs the swap's black screen.
                handOver = { reason ->
                    if (engines.exoStillReconnecting && copyHasNothingOlder()) {
                        engineLog(
                            "'${channel.name}' stayed starved — the saved copy has nothing older " +
                                "to replay and mpv reads the same copy; leaving ExoPlayer to reconnect",
                        )
                    } else {
                        advance(channel, source, reason)
                    }
                },
                onOpened = ::standDownAlarm,
                // A provider back-off is a wait OwnTV agreed to; it is not charged to the budget.
                postponeDeadline = { ladder.postponeDeadline(it) },
                log = ::engineLog,
            )
        }
    }

    /** mpv runs its own retry and format ladder first, so its deadline is looser than ExoPlayer's. */
    private fun watchMpv(channel: ChannelEntity, source: SourceEntity?) {
        mpvOutcomeJob?.cancel()
        mpvOutcomeJob = scope.launch {
            val outcome = engines.awaitMpvOutcome(MPV_OPEN_TIMEOUT_MS)
            if (!isStillMpv(channel)) return@launch
            val reason = when {
                outcome == null -> "mpv never opened it (${MPV_OPEN_TIMEOUT_MS / 1000}s, no picture and no error)"
                outcome.opened -> {
                    engineLog("'${channel.name}' opened on mpv")
                    standDownAlarm()
                    return@launch
                }
                else -> "mpv couldn't play it: ${outcome.error}"
            }
            advance(channel, source, reason)
        }
    }

    // --- The ladder -------------------------------------------------------------------------------

    private suspend fun arm(channel: ChannelEntity, source: SourceEntity?, preference: EnginePreference) {
        forceTsFor = null
        starvedRewinds = 0
        val secs = SourceOverrides.liveTuneTimeoutSecsOf(source) ?: host.globalBudgetSecs()
        armedBudgetMs = if (secs <= 0) LiveLadder.NO_BUDGET else secs * 1000L
        ladder.arm(channel.streamUrl, preference, budgetMs = armedBudgetMs, nowMs = host.nowMs()) {
            // A saved copy is one address; the ladder is only the two engines.
            ts == null && hasHlsAlternative(channel, source)
        }
        startAlarm(channel)
    }

    /**
     * The alarm behind "Give up after", so the budget bounds the black screen rather than only the
     * decision to climb another rung: a rung entered at 24 s with a 35 s timeout of its own would
     * otherwise run to 59 s. The deadline is re-read on each pass, so a postponement moves the alarm.
     * A channel that opens stands the alarm down — later stalls belong to the watchdogs.
     */
    private fun startAlarm(channel: ChannelEntity) {
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            while (ladder.owns(channel.streamUrl)) {
                val left = (ladder.deadlineAt() ?: return@launch) - host.nowMs()
                if (left <= 0) break
                delay(left)
            }
            // Both gates matter: the ladder can still own a channel the user walked away from, and this
            // alarm stops an engine — on mpv's side the shared player, which may be showing a film.
            if (!ladder.owns(channel.streamUrl)) return@launch
            if (!isStillExo(channel) && !isStillMpv(channel)) return@launch
            val detail = "no picture within ${armedBudgetMs / 1000}s of tuning"
            engineLog("'${channel.name}' — giving up: $detail")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            exoWatchJob?.cancel()
            exoResolveJob?.cancel()
            mpvOutcomeJob?.cancel()
            mpvHandoffJob?.cancel()
            abandon(channel, detail)
        }
    }

    private fun standDownAlarm() {
        deadlineJob?.cancel()
        deadlineJob = null
    }

    /**
     * Move to the next untried rung after a failure, or give up when there is nothing left. The only
     * place the ladder is climbed, from either engine's watcher — which is what makes "each rung at most
     * once" hold, and that finiteness is what stops a channel bouncing between the engines for ever.
     */
    private suspend fun advance(
        channel: ChannelEntity,
        source: SourceEntity?,
        reason: String,
        /** Whether the failure says anything about the stream's format; by default, unless it was a refusal. */
        aboutFormat: Boolean = !isRequestRefusal(reason),
    ) {
        if (!ladder.owns(channel.streamUrl)) return // a newer tune owns the ladder now
        val nowMs = host.nowMs()
        // A saved copy ends where the ring ends: with the upstream silent the playhead reaches that
        // tail and there is nothing left to read. That is the feed failing, not the engine — handing
        // over to mpv only makes a second engine wait on the same drained ring (measured: 10 s timeout,
        // then the identical URL opened in 716 ms once data returned). Rewind the copy instead: a
        // picture from data already on disk, and time for the feed to come back.
        if (_liveOnExo.value && rewindStarvedCopy(channel, source, reason)) return
        // ExoPlayer played this channel, then found its content unplayable (not a connection problem).
        // The opening budget is long spent by then, which would refuse mpv, the engine most likely to
        // play it: give this handover a fresh one.
        // A picture has already been on screen, so this is not a tuning failure and the opening budget
        // does not apply: it bounds the black screen before a tune opens, and charging a mid-stream
        // failure to it refused the fallback (measured: a channel that played for 8 minutes, stalled,
        // flapped once, and was abandoned as "no fallback left" with mpv never tried).
        if (_liveOnExo.value && (engines.exoContentBroken || engines.exoHasPlayed)) ladder.restartDeadline(nowMs, armedBudgetMs)
        val outOfTime = ladder.expired(nowMs)
        // A panel refusing the *request* (a busy 458, a 403, a rate limit) says nothing about the format,
        // so nothing may be learned from it.
        val next = ladder.advance(failureWasAboutFormat = aboutFormat, nowMs = nowMs) ?: run {
            val detail = if (outOfTime) "$reason — gave up after ${armedBudgetMs / 1000}s" else reason
            // Nowhere to go, but ExoPlayer is still reconnecting a channel that played. Abandoning here
            // sets its gaveUp flag, which stops those reconnects: measured on an upstream outage of ~50s
            // with the link still up (so no "network restored" to resume on), the next connect failure
            // left a black screen until the user pressed Retry. The engine reports "Lost connection"
            // itself once its own ladder is spent.
            if (_liveOnExo.value && engines.exoStillReconnecting) {
                engineLog("'${channel.name}' — no fallback left ($detail); leaving ExoPlayer's own reconnects running")
                return
            }
            engineLog("'${channel.name}' — no fallback left ($detail)")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            abandon(channel, detail)
            return
        }
        val label = ladder.label(next)
        engineLog("'${channel.name}' falling back to $label ($reason)")
        // Before the switch, so the record still names the engine that failed.
        host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_FALLBACK, "'${channel.name}': $label — $reason")
        if (next.onMpv) {
            // Detached on purpose. Every automatic rung is dispatched from inside a watcher job, and the
            // handoff cancels those watchers the moment it takes over — run inline it would cancel itself
            // at its first suspension point: mpv's surface up, mpv never asked to load, a black screen.
            mpvHandoffJob?.cancel()
            mpvHandoffJob = scope.launch { startOnMpv(channel, source, reason, forceTs = !next.isHls) }
        } else {
            forceTsFor = if (next.isHls) null else channel.streamUrl
            switchToExo(channel, source)
        }
    }

    /** Put the failure on screen: a stream that delivers no segment produces no frame AND no error, so
     *  the honest answer is written by whoever decided to stop trying — on the engine the HUD reads. */
    private fun abandon(channel: ChannelEntity, detail: String) {
        val reason = "'${channel.name}': $detail"
        if (_liveOnExo.value) engines.exoAbandon(reason) else engines.mpvAbandon(reason)
    }

    private fun cancelLadderJobs() {
        exoResolveJob?.cancel()
        exoWatchJob?.cancel()
        mpvOutcomeJob?.cancel()
        mpvHandoffJob?.cancel()
        deadlineJob?.cancel()
    }

    // --- Small helpers ----------------------------------------------------------------------------

    private fun isCurrent(channel: ChannelEntity): Boolean = current?.id == channel.id

    private fun isStillExo(channel: ChannelEntity): Boolean = _liveOnExo.value && isCurrent(channel)

    private fun isStillMpv(channel: ChannelEntity): Boolean = !_liveOnExo.value && isCurrent(channel)

    /** The panel allows one stream at a time: it said so at sync (`max_connections = 1`) or was caught
     *  refusing a second session this run. */
    private fun singleSessionPanel(channel: ChannelEntity, source: SourceEntity?): Boolean =
        source?.maxConnections == 1 || LiveStreamQuirks.isSingleSession(channel.streamUrl)

    private fun blockedBySingleSession(url: String, channel: ChannelEntity): Boolean {
        val blocked = (engines.mpvHasStream && LiveStreamQuirks.isSingleSession(url)) ||
            host.downloadHoldsOnlyStream(channel.sourceId)
        _previewBlocked.value = blocked
        return blocked
    }

    /**
     * Which of the channel's two addresses ExoPlayer should ask for: the "Prefer HLS" `.m3u8`, except on
     * the ladder's `.ts` rung or a channel already caught this session having no working `.m3u8`.
     */
    private fun exoUrlFor(channel: ChannelEntity, source: SourceEntity?): String =
        if (forceTsFor == channel.streamUrl || LiveStreamQuirks.lacksHlsVariant(channel.streamUrl)) channel.streamUrl
        else channel.playStreamUrl(source)

    /** Whether "Prefer HLS" rewrites this channel's URL at all; if not, HLS and TS rungs are one
     *  attempt and the ladder drops the HLS ones. A Stalker command is not a URL. */
    private fun hasHlsAlternative(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !host.needsResolve(source) && channel.playStreamUrl(source) != channel.streamUrl

    /** Whether this channel's panel was caught refusing its own signed segment URLs. Stalker is excluded:
     *  its command has no host to key on until minted — a network call routing must not make. */
    private fun panelRefusesSegments(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !host.needsResolve(source) && LiveStreamQuirks.refusesSegments(channel.playStreamUrl(source))

    private fun isRequestRefusal(reason: String): Boolean =
        PlayerErrors.httpStatusIn(reason)?.let { LiveStreamQuirks.isRequestRefusal(it) } == true

    /**
     * Install the Stalker reconnect link on both engines while a Stalker channel plays, so a mid-session
     * stream death mints a fresh `create_link` instead of looping on the expired one; cleared otherwise.
     */
    private fun setReconnect(channel: ChannelEntity?, source: SourceEntity?) {
        if (channel == null || source == null || !host.needsResolve(source)) {
            engines.setReconnectProvider(null)
            return
        }
        engines.setReconnectProvider(
            ReconnectUrlProvider {
                runCatching { host.resolve(source, channel.streamUrl) }
                    .onFailure { android.util.Log.w(ENGINE_TAG, "stalker reconnect re-resolve failed '${channel.name}'", it) }
                    .getOrNull()
            },
        )
    }

    private fun request(channel: ChannelEntity, source: SourceEntity?) = LiveRequest(
        meta = host.meta(channel),
        userAgent = source?.userAgent,
        prerollSecs = source?.livePrerollSecs?.takeIf { it >= 0 },
        liveBuffer = source?.liveLatencyMode?.let { mode ->
            LiveBuffer.Override(LiveBuffer.effectiveSeconds(LiveLatency.fromName(mode), source.liveLatencyCustomSecs))
        },
        httpHeaders = SourceOverrides.headersWithReferer(channel.httpHeaders, source),
        drmConfig = channel.drmConfig,
        manifestType = channel.manifestType,
        directSource = channel.directSource,
    )

    /** Routing decisions go to logcat unconditionally (`adb logcat -s LiveEngine`) and to the
     *  diagnostics ring. Channel names only — never a stream URL. */
    private fun engineLog(message: String) {
        android.util.Log.i(ENGINE_TAG, message)
        LiveDiagnosticsLog.event("engine: $message")
    }

    /**
     * The [Host] both apps use: pins from [ForceMpvStore], the settings store, the playlist row, and
     * Stalker minting through [StreamUrlResolver]. [label] is the line under the channel name (the
     * television's "#123"); [sourceOf] lets the television read its warm playlist map.
     */
    class CoreHost(
        private val context: Context,
        private val settings: SettingsRepository,
        private val sourceDao: SourceDao,
        private val resolver: StreamUrlResolver,
        private val forceMpvStore: ForceMpvStore,
        private val label: (ChannelEntity) -> String? = { null },
        private val sourceLookup: (suspend (Long) -> SourceEntity?)? = null,
        private val engineStarted: () -> Unit = {},
        private val backToLiveEdge: () -> Unit = {},
        /** Core's `DownloadWatchGuard.holdsOnlyStream`; see [Host.downloadHoldsOnlyStream]. */
        private val downloadHolds: (Long) -> Boolean = { false },
    ) : Host {
        override suspend fun sourceOf(sourceId: Long): SourceEntity? =
            sourceLookup?.invoke(sourceId) ?: sourceDao.getById(sourceId)

        override fun needsResolve(source: SourceEntity?): Boolean = resolver.needsResolve(source)

        override fun downloadHoldsOnlyStream(sourceId: Long): Boolean = downloadHolds(sourceId)

        override suspend fun resolve(source: SourceEntity, cmd: String): String? =
            runCatching { resolver.resolve(source, cmd) }
                .onFailure { android.util.Log.w(ENGINE_TAG, "stalker resolve failed", it) }
                .getOrNull()

        override suspend fun enginePin(channel: ChannelEntity): Boolean? =
            forceMpvStore.pinFor(pinKey(channel), channel.streamUrl)

        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {
            // The stable key, with any legacy URL-keyed entry cleared so an older pin cannot contradict it.
            val key = pinKey(channel)
            forceMpvStore.pin(key ?: channel.streamUrl, onMpv)
            if (key != null) forceMpvStore.forget(channel.streamUrl)
        }

        // Both wait for the settings snapshot, so a tune started at a cold start — Last-channel
        // autoplay — is routed and budgeted by the stored settings, never by defaults.
        override suspend fun globalPreference(): EnginePreference =
            PlaybackSettings.await(settings).liveEnginePreference

        override suspend fun globalBudgetSecs(): Int = PlaybackSettings.await(settings).liveTuneTimeoutSecs

        override fun meta(channel: ChannelEntity): MediaMeta = MediaMeta(
            title = channel.name,
            subtitle = label(channel),
            logoUrl = channel.displayLogoUrl,
            contentKey = pinKey(channel),
        )

        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) =
            PlaybackErrorLog.event(
                context = context,
                engine = if (onExo) "ExoPlayer" else "mpv",
                live = true,
                reason = reason,
                detail = detail,
            )

        override fun onEngineStarted() = engineStarted()
        override fun onBackToLiveEdge() = backToLiveEdge()

        override val timeshift: TimeshiftManager? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<TimeshiftManager>()
        }

        override suspend fun timeshiftWindowMinutes(): Int? =
            if (settings.timeshiftEnabled.first()) settings.timeshiftWindowMinutes.first() else null

        override suspend fun timeshiftResumeMode(): SettingsRepository.ResumeMode = settings.timeshiftResumeMode.first()

        override suspend fun maxVideoHeight(): Int? =
            PlaybackSettings.await(settings).maxVideoHeight.takeIf { it > 0 }

        private fun pinKey(channel: ChannelEntity): String? =
            enginePinKey(channel.sourceId, tv.own.owntv.core.model.MediaType.LIVE.name, channel.remoteId)
    }

    companion object {
        /** Tag for the engine decisions, so a support log can be filtered to just them. */
        const val ENGINE_TAG = "LiveEngine"

        /**
         * How long mpv gets to produce a picture before the ladder moves on. Looser than ExoPlayer's:
         * mpv walks its own internal retry and format ladder first.
         */
        const val MPV_OPEN_TIMEOUT_MS = 35_000L

        /** How long a new saved copy gets to receive its first bytes before the channel plays directly. */
        const val TIMESHIFT_FIRST_PIECE_MS = 12_000L

        /** Behind the copy's edge by more than this is not live any more (the rewind counter's own slack). */
        const val LIVE_SLACK_MS = 8_000L

        /**
         * At or below this much buffered ahead of the playhead, a saved copy has nothing to read: the
         * ring's tail IS the live edge and the upstream stopped. Measured at a cell hand-off:
         * `pos=151822 buffered=151853` — 31 ms. Not zero because the playhead is never exactly on the
         * last decoded sample.
         */
        const val STARVED_AHEAD_MS = 750L

        /** How far back a starved copy is re-opened. Twenty seconds of picture per attempt. */
        const val STARVED_REWIND_MS = 20_000L

        /**
         * Re-opening the piece that is already on screen only counts as a rewind if that would move the
         * picture back at least this far. Below it the seek buys nothing and an immediate re-stall would
         * turn the recovery into a seek loop.
         */
        const val STARVED_REWIND_MIN_GAIN_MS = 4_000L

        /**
         * A stall this close to dry is worth a log line either way: it is the only place that says why a
         * starved copy was, or was not, rewound.
         */
        const val STARVED_LOG_MS = 2_000L

        /**
         * Attempts per tune, so an outage longer than the ring cannot loop for ever: six attempts is
         * about two minutes of cover, after which the ladder and its budget are back in charge.
         */
        const val MAX_STARVED_REWINDS = 6

        /** How long a rewound picture must hold before it is walked back toward the live edge. */
        const val CATCH_UP_AFTER_MS = 30_000L

        /** Leave this much picture between the rejoined playhead and the live edge. */
        const val CATCH_UP_KEEP_MS = 8_000L

        /** No point rejoining for a delay this small. */
        const val CATCH_UP_BEHIND_MS = 10_000L

        /** Only rejoin when the copy has refilled; a thin buffer is the stall's own signature. */
        const val CATCH_UP_MIN_AHEAD_MS = 6_000L
    }
}
