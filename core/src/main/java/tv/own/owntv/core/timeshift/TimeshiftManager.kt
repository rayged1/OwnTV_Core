package tv.own.owntv.core.timeshift

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/**
 * Which timeshift buffers exist and whether each is downloading. Process-wide: one
 * buffer downloading at most — the channel being watched — and at most one *parked*: a channel the user
 * left, kept for [TimeshiftRules.PARKED_GRACE_MS] so coming back offers "Resume from buffer".
 *
 * **Whether anyone is watching is read from the buffer itself.** A buffer with no player reading it for
 * [IDLE_PARK_MS] is parked — its download stops, so the provider connection is free again — and a player
 * that starts reading a parked buffer (the television restoring a channel after the screensaver) starts
 * the download again. That one rule covers every way playback can stop without the controller being
 * told: Home, the sleep timer, Multiview taking over, a phone put away with nothing playing. A paused
 * player keeps its connection open, so a pause is never mistaken for leaving.
 *
 * Every public call is safe from the main thread; file and network work happens on [scope].
 */
class TimeshiftManager(
    private val client: OkHttpClient,
    private val root: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleParkMs: Long = IDLE_PARK_MS,
) {
    constructor(context: Context, client: OkHttpClient) : this(client, { TimeshiftStorage.root(context) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private inner class Buffer(
        val session: TimeshiftSession,
        var target: TimeshiftDownloader.Target,
        var windowMs: Long,
    ) {
        var download: Job? = null
        var idleJob: Job? = null
        var closeJob: Job? = null
        /** The wall-clock instant on screen when the user left, for "Resume from buffer". */
        var leftAtWallMs: Long? = null
        /** When it was parked, on the wall clock — see [outlived]. */
        var parkedAtWallMs: Long? = null
        val parked: Boolean get() = download == null
    }

    /**
     * Parked past [TimeshiftRules.PARKED_GRACE_MS] on the wall clock. The close timer alone cannot be
     * trusted for that: it counts only while the CPU runs, and a television in standby sleeps it, so a
     * buffer parked for an hour is still there when the screen comes back.
     */
    private fun outlived(buffer: Buffer): Boolean =
        buffer.parkedAtWallMs?.let { clock() - it > TimeshiftRules.PARKED_GRACE_MS } == true

    private var active: Buffer? = null
    private var parked: Buffer? = null
    private var watchingKey: String? = null
    private var replaceJob: Job? = null

    private val _gaveUp = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)

    /** `token to reason`: a buffer that had been playing lost its connection for good. It is closed. */
    val gaveUp: SharedFlow<Pair<String, String>> = _gaveUp.asSharedFlow()

    /**
     * Why the last [open] could not make a copy, for the app's error log and the tune's own log line:
     * "only 412 MB free", "encrypted HLS", "no data in 12000ms"… Null when the last open succeeded.
     * Most TV users can't read logcat, and "no rewind on this channel" is otherwise unanswerable.
     */
    @Volatile var lastRefusal: String? = null
        private set

    private fun refusalOf(result: TimeshiftDownloader.Result): String = when (result) {
        is TimeshiftDownloader.Result.Unsupported -> result.why
        is TimeshiftDownloader.Result.Refused -> result.why
        is TimeshiftDownloader.Result.GaveUp -> result.why
    }

    /** What [open] hands back: the buffer, and where the user was when they last left it (a return). */
    class Opened(val session: TimeshiftSession, val resumeAtWallMs: Long?)

    /**
     * The buffer for [channelKey], downloading: the running one, the parked one woken up, or a new one.
     * Null when this stream cannot be buffered (unsupported, refused, too little space) — the caller
     * then plays the channel the ordinary way. Any other channel's download is parked first, so there is
     * never a second connection.
     */
    suspend fun open(
        channelKey: String,
        target: TimeshiftDownloader.Target,
        windowMinutes: Int,
        firstPieceTimeoutMs: Long,
        /** What was on screen of the channel being left, for its own "Resume from buffer" later. */
        leavingWallMs: Long? = null,
    ): Opened? {
        val windowMs = windowMinutes * 60_000L
        synchronized(lock) {
            watchingKey = channelKey
            val current = active
            if (current != null && current.session.channelKey == channelKey) {
                current.windowMs = windowMs
                lastRefusal = null
                return Opened(current.session, null)
            }
            // The parked buffer is taken out BEFORE the channel being left is parked: parking evicts
            // whatever is parked, and on a return (A → B → A) that is exactly the buffer wanted.
            val waiting = parked?.takeIf { it.session.channelKey == channelKey }
            val back = waiting?.takeUnless(::outlived)
            if (waiting != null && back == null) closeBuffer(waiting) // too old to resume: a new one below
            if (back != null) {
                parked = null
                back.closeJob?.cancel()
            }
            current?.let { parkLocked(it, leftAtWallMs = leavingWallMs) }
            if (back != null) {
                back.target = target
                back.windowMs = windowMs
                back.session.markGap()
                startDownloadLocked(back)
                active = back
                lastRefusal = null
                return Opened(back.session, back.leftAtWallMs)
            }
        }
        val folder = root()
        val space = withContext(Dispatchers.IO) { folder.mkdirs(); folder.usableSpace }
        if (!TimeshiftRules.canStart(space)) {
            val why = "only ${space / (1024 * 1024)} MB free"
            lastRefusal = why
            android.util.Log.i(TAG, "not buffering: $why")
            return null
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val session = TimeshiftSession(token, channelKey, File(folder, token), clock)
        val buffer = Buffer(session, target, windowMs)
        val result = kotlinx.coroutines.CompletableDeferred<TimeshiftDownloader.Result>()
        synchronized(lock) {
            active = buffer
            startDownloadLocked(buffer, result)
        }
        TimeshiftServer.register(session)
        session.onReadersChanged = { onReadersChanged(buffer) }
        // The first piece, or the download's refusal — whichever comes first.
        val deadline = clock() + firstPieceTimeoutMs
        while (clock() < deadline && !result.isCompleted) {
            if (withContext(Dispatchers.IO) { session.awaitFirstPiece(FIRST_PIECE_SLICE_MS) }) {
                // Idle-parking counts from here: the player is about to be told the address.
                onReadersChanged(buffer)
                lastRefusal = null
                return Opened(session, null)
            }
        }
        val why = if (result.isCompleted) refusalOf(result.await()) else "no data in ${firstPieceTimeoutMs}ms"
        lastRefusal = why
        android.util.Log.i(TAG, "not buffering: $why")
        closeBuffer(buffer)
        return null
    }

    /** The user is watching [channelKey] without a buffer (a catch-up channel, or buffering refused). */
    fun watching(channelKey: String?) = synchronized(lock) {
        watchingKey = channelKey
        active?.takeIf { it.session.channelKey != channelKey }?.let { parkLocked(it, leftAtWallMs = null) }
        scheduleReplaceLocked()
    }

    /** Stop downloading [session]'s channel but keep it for a return; [leftAtWallMs] is what was on screen. */
    fun park(session: TimeshiftSession, leftAtWallMs: Long?) = synchronized(lock) {
        active?.takeIf { it.session === session }?.let { parkLocked(it, leftAtWallMs) }
    }

    /**
     * True while a copy of a [sourceId] channel is downloading. That download is the user's own picture,
     * but it can run with no player screen open (the TV's preview pane, phone PiP), so background work
     * that asks `WatchSession` alone would not see it. Deliberately not an `OpenStreamRegistry` claim:
     * that is the Multiview/recording budget, which fullscreen playback never spends from.
     */
    fun isSaving(sourceId: Long): Boolean = synchronized(lock) {
        active?.let { it.download != null && it.target.sourceId == sourceId } == true
    }

    /** Everything stops and is deleted — timeshift was switched off. */
    fun closeAll() {
        val all = synchronized(lock) {
            listOfNotNull(active, parked).also { active = null; parked = null }
        }
        all.forEach { closeBuffer(it) }
    }

    // --- Internals ---------------------------------------------------------------------------------

    private fun startDownloadLocked(buffer: Buffer, result: kotlinx.coroutines.CompletableDeferred<TimeshiftDownloader.Result>? = null) {
        buffer.idleJob?.cancel()
        buffer.parkedAtWallMs = null
        val downloader = TimeshiftDownloader(
            client = client,
            session = buffer.session,
            request = buffer.target,
            windowMs = { buffer.windowMs },
            log = { android.util.Log.i(TAG, it) },
            clock = clock,
        )
        buffer.download = scope.launch {
            val outcome = downloader.run()
            result?.complete(outcome)
            if (!isActive) return@launch
            // A first download that never saved anything is [open]'s to handle (it plays the channel the
            // ordinary way). Any other end — a buffer that had been saving, or a woken one that could not
            // reconnect — means the channel is lost: the controller is told, and the buffer goes.
            if (result == null || downloader.started) {
                val why = when (outcome) {
                    is TimeshiftDownloader.Result.GaveUp -> outcome.why
                    is TimeshiftDownloader.Result.Refused -> outcome.why
                    is TimeshiftDownloader.Result.Unsupported -> outcome.why
                }
                // Closed before it is announced: the controller opens the channel again at once, and [open]
                // must not hand that tune this dying buffer as the channel's running one.
                closeBuffer(buffer)
                _gaveUp.tryEmit(buffer.session.token to why)
            }
        }
    }

    private fun parkLocked(buffer: Buffer, leftAtWallMs: Long?) {
        if (active === buffer) active = null
        buffer.download?.cancel()
        buffer.download = null
        buffer.idleJob?.cancel()
        buffer.session.endPiece(durationMs = null)
        // Whatever is saved next follows a hole, and a player opening the live edge meanwhile waits for it.
        buffer.session.markGap()
        buffer.parkedAtWallMs = clock()
        buffer.leftAtWallMs = leftAtWallMs ?: buffer.session.playableEdgeWallMs()
        // One parked buffer at most: the older one is gone.
        parked?.takeIf { it !== buffer }?.let { old -> scope.launch { closeBuffer(old) } }
        parked = buffer
        buffer.closeJob?.cancel()
        buffer.closeJob = scope.launch {
            delay(TimeshiftRules.PARKED_GRACE_MS)
            closeBuffer(buffer)
        }
        android.util.Log.i(TAG, "buffer parked")
        scheduleReplaceLocked()
    }

    /** Another channel watched for [TimeshiftRules.REPLACED_AFTER_MS] deletes the parked buffer at once. */
    private fun scheduleReplaceLocked() {
        replaceJob?.cancel()
        val waiting = parked ?: return
        val other = watchingKey ?: return
        if (other == waiting.session.channelKey) return
        replaceJob = scope.launch {
            delay(TimeshiftRules.REPLACED_AFTER_MS)
            val still = synchronized(lock) { parked === waiting && watchingKey == other }
            if (still) closeBuffer(waiting)
        }
    }

    private fun onReadersChanged(buffer: Buffer) {
        synchronized(lock) {
            val readers = buffer.session.readerCount
            when {
                buffer.session.isClosed -> Unit
                readers > 0 && parked === buffer -> {
                    // A player came back to a parked buffer (a restore after the screensaver): save again.
                    parked = null
                    buffer.closeJob?.cancel()
                    active?.let { parkLocked(it, leftAtWallMs = null) }
                    // Back after the grace (a standby): what was saved before is too old to keep. The
                    // player stays connected and is served from the first new piece.
                    if (outlived(buffer)) {
                        buffer.session.discardSaved()
                        buffer.leftAtWallMs = null
                    }
                    buffer.session.markGap()
                    startDownloadLocked(buffer)
                    active = buffer
                    watchingKey = buffer.session.channelKey
                    android.util.Log.i(TAG, "buffer resumed by a returning player")
                }
                readers > 0 -> buffer.idleJob?.cancel()
                active === buffer -> {
                    buffer.idleJob?.cancel()
                    buffer.idleJob = scope.launch {
                        delay(idleParkMs)
                        synchronized(lock) {
                            if (active === buffer && buffer.session.readerCount == 0) parkLocked(buffer, leftAtWallMs = null)
                        }
                    }
                }
            }
        }
    }

    private fun closeBuffer(buffer: Buffer) {
        synchronized(lock) {
            if (active === buffer) active = null
            if (parked === buffer) parked = null
            buffer.download?.cancel()
            buffer.download = null
            buffer.idleJob?.cancel()
            buffer.closeJob?.cancel()
        }
        TimeshiftServer.unregister(buffer.session)
        scope.launch { buffer.session.close() }
    }

    private companion object {
        const val TAG = "Timeshift"
        /** No player reading this long = nobody is watching; a handover between engines takes ≈1 s. */
        const val IDLE_PARK_MS = 4_000L
        const val FIRST_PIECE_SLICE_MS = 250L
    }
}
