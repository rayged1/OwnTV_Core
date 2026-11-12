package tv.own.owntv.player

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.stalker.ReconnectUrlProvider

/** Everything a live tune hands an engine besides the URL. */
data class LiveRequest(
    val meta: MediaMeta,
    val userAgent: String?,
    val prerollSecs: Int?,
    val liveBuffer: LiveBuffer.Override?,
    val httpHeaders: String?,
    val drmConfig: String?,
    val manifestType: String?,
    val directSource: String?,
    /**
     * ExoPlayer had this stream open, so it is known to carry video. A trimmed fast-zap probe that comes
     * back with audio and no video track on such a load missed the video PMT — [OwnTVPlayer] re-probes it
     * fully at once instead of letting the start ladder time out first.
     */
    val expectVideo: Boolean = false,
)

/** What mpv did with a channel it was just given: playback started, or an error. */
data class MpvOutcome(val opened: Boolean, val error: String?)

/**
 * mpv's outcome from its two signals, or null while it is still opening. An error wins. Nothing else
 * counts as "opened" — the spinner is not raised by a normal load, so "not buffering" was true from the
 * first instant and every mpv tune read as opened before a byte had arrived.
 */
internal fun mpvOutcomeOf(playbackStarted: Boolean, error: PlaybackFailure?): MpvOutcome? = when {
    error != null -> MpvOutcome(opened = false, error = error.toString())
    playbackStarted -> MpvOutcome(opened = true, error = null)
    else -> null
}

/**
 * The two live engines, as [LiveTuneController] drives them.
 *
 * Exactly the operations the controller uses and nothing more. It exists so the controller's
 * sequencing — superseding tunes, the ladder, the give-up alarm — can be unit-tested against a fake;
 * production is [EnginePair], a thin pass-through to the real engines.
 */
interface LiveEngines {
    /** What ExoPlayer holds right now, or null. */
    val exoUrl: String?
    val exoIsHls: Boolean
    val exoFailed: Boolean
    /** ExoPlayer is working through its own reconnects after having played (see [LivePreviewEngine.stillReconnecting]). */
    val exoStillReconnecting: Boolean get() = false
    /** ExoPlayer cannot play this stream's content (see [LivePreviewEngine.contentBroken]). */
    val exoContentBroken: Boolean get() = false
    /**
     * Whether ExoPlayer has shown a picture for the tune in flight.
     *
     * A failure that arrives after the picture is not a tuning failure, so the opening budget — which
     * exists to bound the black screen before a tune opens — must not refuse the ladder its next rung.
     * Measured on a channel that played for 8 minutes and then stalled: `advance` saw an expired
     * 60 s budget, found "no fallback left" and left an error on screen with mpv untried.
     */
    val exoHasPlayed: Boolean get() = false

    /**
     * Media buffered ahead of the playhead, or -1 when unknown.
     *
     * A saved copy is served from a local ring whose tail is the live edge: when the upstream stops,
     * the playhead reaches that tail and nothing is left to read. Then the failure belongs to the
     * feed, not to the engine, and handing the channel to mpv only makes a second engine wait on the
     * same drained ring (measured: 10 s timeout, then the same URL opened in 716 ms once data came
     * back). See [LiveTuneController]'s starved-copy branch.
     */
    val exoBufferedAheadMs: Long get() = -1L

    /**
     * The same measurement, taken at the stall's onset rather than when the ladder decides.
     *
     * Fifteen seconds pass between the two, and the engine's own reconnect usually re-primes the buffer
     * in that time: measured 2026-11-11 a stall began at `pos=163021 buffered=163094` (73 ms) and by the
     * hand-over the live number no longer looked starved, so nothing rewound.
     */
    val exoBufferedAheadAtStallMs: Long get() = -1L

    /**
     * Asked, before the engine reconnects a stalled stream, whether the saved copy can be rewound
     * instead. Answers true when it did; null/unset means "never mind", and the reconnect runs.
     */
    var starvedStallHook: ((String) -> Boolean)? get() = null set(_) {}

    /** Whether mpv holds a stream (full-screen live, a film, a catch-up). */
    val mpvHasStream: Boolean

    fun exoPlay(url: String, muted: Boolean, request: LiveRequest)
    fun exoSetMuted(muted: Boolean)
    fun exoStop()
    fun exoReleaseUhdDecoder()
    fun exoAbandon(reason: String)

    /** Run [LiveExoWatchdog] on ExoPlayer until the outcome is settled. */
    suspend fun watchExo(
        channelName: String,
        stillOurs: () -> Boolean,
        handOver: suspend (String) -> Unit,
        onOpened: () -> Unit,
        postponeDeadline: (Long) -> Unit,
        log: (String) -> Unit,
    )

    fun mpvPlay(url: String, request: LiveRequest)
    fun mpvStop()
    suspend fun mpvStopAndAwaitRelease()
    fun mpvAbandon(reason: String)

    /**
     * Wait up to [timeoutMs] for mpv to open or fail; null when it did neither.
     *
     * **"Opened" is playback moving — [OwnTVPlayer.playbackStarted], never `isPlaying` or the spinner.**
     * mpv seeds `isPlaying` true at load time and never raises the spinner on a normal load, so neither
     * says anything about whether the stream ever arrived.
     */
    suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome?

    /** The Stalker re-resolve hook, installed on both engines, or cleared with null. */
    fun setReconnectProvider(provider: ReconnectUrlProvider?)

    /** How far into its current stream the live engine on screen is — for a saved copy's clock. */
    fun positionMs(onExo: Boolean): Long = 0L
}

/** Start [url] on a live ExoPlayer engine with everything [request] carries — a tune, or a Multiview tile. */
internal fun LivePreviewEngine.play(url: String, muted: Boolean, request: LiveRequest) = play(
    url,
    muted = muted,
    meta = request.meta,
    userAgent = request.userAgent,
    prerollSecsOverride = request.prerollSecs,
    liveBufferOverride = request.liveBuffer,
    httpHeaders = request.httpHeaders,
    drmConfig = request.drmConfig,
    manifestType = request.manifestType,
    directSource = request.directSource,
)

/** The real engines: the live ExoPlayer engine and the process-wide mpv player. */
class EnginePair(private val exo: LivePreviewEngine, private val mpv: OwnTVPlayer) : LiveEngines {
    override val exoUrl: String? get() = exo.currentUrl
    override val exoIsHls: Boolean get() = exo.isHlsStream
    override val exoFailed: Boolean get() = exo.state.value == LivePreviewEngine.State.ERROR
    override val exoStillReconnecting: Boolean get() = exo.stillReconnecting
    override val exoContentBroken: Boolean get() = exo.contentBroken
    override val exoHasPlayed: Boolean get() = exo.hasPlayedThisTune
    override val exoBufferedAheadMs: Long get() = exo.bufferedAheadMs
    override val exoBufferedAheadAtStallMs: Long get() = exo.bufferedAheadAtStallMs
    override var starvedStallHook: ((String) -> Boolean)?
        get() = exo.starvedStallHook
        set(value) { exo.starvedStallHook = value }
    override val mpvHasStream: Boolean get() = mpv.hasActiveStream

    override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) = exo.play(url, muted, request)

    override fun exoSetMuted(muted: Boolean) = exo.setMuted(muted)
    override fun exoStop() = exo.stop()
    override fun exoReleaseUhdDecoder() = exo.releaseDecoderForUhd()
    override fun exoAbandon(reason: String) { exo.abandon(reason) }

    override suspend fun watchExo(
        channelName: String,
        stillOurs: () -> Boolean,
        handOver: suspend (String) -> Unit,
        onOpened: () -> Unit,
        postponeDeadline: (Long) -> Unit,
        log: (String) -> Unit,
    ) {
        LiveExoWatchdog(exo, stillOurs, handOver, onOpened, postponeDeadline, log).watch(channelName)
    }

    override fun mpvPlay(url: String, request: LiveRequest) = mpv.play(
        url = url,
        title = request.meta.title,
        subtitle = request.meta.subtitle,
        logoUrl = request.meta.logoUrl,
        isLive = true,
        muted = false,
        userAgent = request.userAgent,
        httpHeaders = request.httpHeaders,
        // The same stable key ExoPlayer files this channel under, so a zoom or volume set on one
        // engine is not forgotten when the channel falls back to the other.
        contentKey = request.meta.contentKey,
        livePrerollSecsOverride = request.prerollSecs,
        liveBufferOverride = request.liveBuffer,
        expectVideo = request.expectVideo,
    )

    override fun mpvStop() = mpv.stop()
    override suspend fun mpvStopAndAwaitRelease() { mpv.stopAndAwaitRelease() }
    override fun mpvAbandon(reason: String) = mpv.abandonLive(reason)

    override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? = withTimeoutOrNull(timeoutMs) {
        combine(mpv.playbackStarted, mpv.error, ::mpvOutcomeOf).first { it != null }
    }

    override fun setReconnectProvider(provider: ReconnectUrlProvider?) {
        exo.reconnectUrlProvider = provider
        mpv.reconnectUrlProvider = provider
    }

    override fun positionMs(onExo: Boolean): Long = if (onExo) exo.livePositionMs() else mpv.position.value
}
