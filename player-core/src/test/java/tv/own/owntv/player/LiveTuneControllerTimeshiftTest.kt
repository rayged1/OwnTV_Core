package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.timeshift.TimeshiftManager
import tv.own.owntv.core.timeshift.TimeshiftServer
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * The controller with local timeshift on: which channels play from a saved copy, that both
 * engines of the ladder are handed the copy (never the provider), and that leaving parks it. Real
 * buffers against a fake provider on loopback, so this runs on real time.
 */
class LiveTuneControllerTimeshiftTest {

    private val provider = ServerSocket(0)
    @Volatile private var open = true
    /** An outage: the provider drops every connection at once, so a copy spends its reconnect budget. */
    @Volatile private var outage = false
    private val dir = Files.createTempDirectory("ts-controller").toFile()
    private val manager = TimeshiftManager(OkHttpClient(), { dir })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        thread(isDaemon = true) {
            while (open) {
                val s = runCatching { provider.accept() }.getOrNull() ?: break
                if (outage) { runCatching { s.close() }; continue }
                thread(isDaemon = true) {
                    runCatching {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            while (r.readLine()?.isNotEmpty() == true) {}
                            val out = it.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                            while (open && !outage) {
                                out.write(nullPacket)
                                out.flush()
                                Thread.sleep(1)
                            }
                        }
                    }
                }
            }
        }
    }

    @After
    fun cleanUp() {
        open = false
        scope.cancel()
        manager.closeAll()
        provider.close()
        runCatching { dir.deleteRecursively() }
    }

    private class Engines : LiveEngines {
        val log = mutableListOf<String>()
        override var exoUrl: String? = null
        override val exoIsHls = false
        override var exoFailed = false
        override var mpvHasStream = false
        override var exoBufferedAheadMs = -1L
        var exoWatch: CompletableDeferred<String?>? = null

        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) { exoUrl = url; log += "exo:$url" }
        override fun exoSetMuted(muted: Boolean) { log += if (muted) "exo-mute" else "exo-unmute" }
        override fun exoStop() { exoUrl = null }
        override fun exoReleaseUhdDecoder() {}
        override fun exoAbandon(reason: String) { log += "exo-abandon" }
        override suspend fun watchExo(
            channelName: String,
            stillOurs: () -> Boolean,
            handOver: suspend (String) -> Unit,
            onOpened: () -> Unit,
            postponeDeadline: (Long) -> Unit,
            log: (String) -> Unit,
        ) {
            val reason = CompletableDeferred<String?>().also { exoWatch = it }.await()
            if (reason == null) onOpened() else if (stillOurs()) handOver(reason)
        }
        override fun mpvPlay(url: String, request: LiveRequest) { mpvHasStream = true; log += "mpv:$url" }
        override fun mpvStop() { mpvHasStream = false }
        override suspend fun mpvStopAndAwaitRelease() { mpvHasStream = false }
        override fun mpvAbandon(reason: String) { log += "mpv-abandon" }
        override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? = withTimeoutOrNull(timeoutMs) {
            CompletableDeferred<MpvOutcome>().await()
        }
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) {}
    }

    private inner class Host(
        val resumeMode: tv.own.owntv.core.settings.SettingsRepository.ResumeMode =
            tv.own.owntv.core.settings.SettingsRepository.ResumeMode.ASK,
    ) : LiveTuneController.Host {
        override suspend fun sourceOf(sourceId: Long): SourceEntity? = null
        override fun needsResolve(source: SourceEntity?) = false
        override suspend fun resolve(source: SourceEntity, cmd: String): String? = null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {}
        override suspend fun globalPreference() = EnginePreference.EXO_FIRST
        override suspend fun globalBudgetSecs() = 0
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {}
        override val timeshift: TimeshiftManager get() = manager
        override suspend fun timeshiftWindowMinutes(): Int? = 15
        override suspend fun timeshiftResumeMode() = resumeMode
    }

    private fun channel(id: Long, catchup: Boolean = false) = ChannelEntity(
        id = id,
        sourceId = 1,
        name = "ch$id",
        streamUrl = "http://127.0.0.1:${provider.localPort}/live/$id.ts",
        catchup = catchup,
    )

    private suspend fun awaitLog(engines: Engines, what: (List<String>) -> Boolean) {
        repeat(100) { if (what(engines.log.toList())) return; delay(50) }
    }

    @Test
    fun `a channel without catch-up plays from its saved copy, on both engines of the ladder`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(1))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        val exo = engines.log.first { it.startsWith("exo:") }.removePrefix("exo:")
        assertTrue("ExoPlayer is given the copy, at the live edge: $exo", TimeshiftServer.isLocal(exo) && exo.contains("/live."))
        assertNotNull(c.localTimeshift.value)

        // ExoPlayer fails: mpv is handed the same copy, never the provider.
        engines.exoWatch!!.complete("decoder error")
        awaitLog(engines) { log -> log.any { it.startsWith("mpv:") } }
        val mpv = engines.log.first { it.startsWith("mpv:") }.removePrefix("mpv:")
        assertEquals(exo, mpv)
    }

    @Test
    fun `a catch-up channel keeps the provider's archive and is not saved`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(2, catchup = true))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        assertEquals("exo:${channel(2).streamUrl}", engines.log.first { it.startsWith("exo:") })
        assertNull(c.localTimeshift.value)
    }

    @Test
    fun `browsing to another channel in the pane leaves this one, and its copy is parked`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(3))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        val session = c.localTimeshift.value!!.session
        c.detach()
        c.preview(channel(4), muted = true)
        assertNull(c.localTimeshift.value)
        assertFalse("parked, not deleted", session.isClosed)
    }

    /** Watch [first], zap to another channel, come straight back: what the return offers under [mode]. */
    private suspend fun comeBack(mode: tv.own.owntv.core.settings.SettingsRepository.ResumeMode, first: Long): Pair<LiveTuneController, Engines> {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host(mode))
        c.tune(channel(first))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        engines.exoWatch!!.complete(null)
        delay(300)
        c.tune(channel(first + 1))
        awaitLog(engines) { log -> log.count { it.startsWith("exo:") } >= 2 }
        engines.exoWatch!!.complete(null)
        c.tune(channel(first))
        awaitLog(engines) { log -> log.count { it.startsWith("exo:") } >= 3 }
        return c to engines
    }

    @Test
    fun `coming back to a kept copy asks by default`() = runBlocking {
        val (c, _) = comeBack(tv.own.owntv.core.settings.SettingsRepository.ResumeMode.ASK, first = 10)
        assertNotNull("Continue where you left off? is offered", c.localTimeshift.value!!.resumeAtWallMs)
    }

    @Test
    fun `Never resume goes to the live edge without asking`() = runBlocking {
        val (c, engines) = comeBack(tv.own.owntv.core.settings.SettingsRepository.ResumeMode.NEVER, first = 20)
        assertNull(c.localTimeshift.value!!.resumeAtWallMs)
        assertTrue(engines.log.last { it.startsWith("exo:") }.contains("/live."))
    }

    @Test
    fun `Always resume reopens where the user was without asking`() = runBlocking {
        val (c, engines) = comeBack(tv.own.owntv.core.settings.SettingsRepository.ResumeMode.AUTO, first = 30)
        assertNull(c.localTimeshift.value!!.resumeAtWallMs)
        assertFalse("opened inside the copy, not at its live edge", engines.log.last { it.startsWith("exo:") }.contains("/live."))
    }

    @Test
    fun `a stall with nothing buffered ahead rewinds the saved copy instead of handing over`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(60))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        val copyUrl = engines.exoUrl!!
        assertTrue("playing the copy", TimeshiftServer.isLocal(copyUrl))
        readLikeAPlayer(copyUrl)
        // The ring has to hold something before a rewind can point at it.
        val session = c.localTimeshift.value!!.session
        withTimeoutOrNull(10_000) {
            while (session.playableEdgeWallMs() == null) delay(50)
        }
        assertNotNull("the copy received pieces", session.playableEdgeWallMs())

        // The feed stops: the playhead has reached the tail of the ring.
        engines.exoBufferedAheadMs = 31L
        engines.exoWatch!!.complete("played, then stalled for 15s without recovering")

        awaitLog(engines) { log -> log.count { it.startsWith("exo:") } >= 2 }
        val urls = engines.log.filter { it.startsWith("exo:") }.map { it.removePrefix("exo:") }
        assertTrue("re-opened the copy: $urls", urls.size >= 2 && urls.drop(1).all { TimeshiftServer.isLocal(it) })
        assertFalse("rewound inside the copy, not back to its live edge: $urls", urls.last().endsWith("/live.ts"))
        assertTrue("mpv was not given a drained ring: ${engines.log}", engines.log.none { it.startsWith("mpv:") })
        assertFalse("the tune was not abandoned", engines.log.contains("exo-abandon"))
    }

    @Test
    fun `a stall with media still buffered ahead is left to the ladder`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(61))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        engines.exoBufferedAheadMs = 4_000L
        engines.exoWatch!!.complete("played, then stalled for 15s without recovering")
        awaitLog(engines) { log -> log.any { it.startsWith("mpv:") } }
        assertTrue("the ladder still hands over: ${engines.log}", engines.log.any { it.startsWith("mpv:") })
        assertEquals("one ExoPlayer load only — no rewind", 1, engines.log.count { it.startsWith("exo:") })
    }

    /** Reads the copy at [url] as a player would: without a reader a copy parks itself after 4 s and stops saving. */
    private fun readLikeAPlayer(url: String) = thread(isDaemon = true) {
        runCatching {
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (input.read(buffer) >= 0) Unit
            }
        }
    }

    /** Waits out a copy's whole reconnect budget (1 + 2 + 4 + 8 + 8 s) and then some. */
    private suspend fun awaitGiveUp(engines: Engines, what: (List<String>) -> Boolean) {
        repeat(800) { if (what(engines.log.toList())) return; delay(50) }
    }

    @Test
    fun `a copy that loses the connection opens the channel again instead of giving up`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        val ch = channel(40)
        c.tune(ch)
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        engines.exoWatch!!.complete(null)
        readLikeAPlayer(engines.exoUrl!!)
        outage = true
        // Re-tuned during the outage: the new copy is refused, so the channel plays directly.
        awaitGiveUp(engines) { log -> log.contains("exo:${ch.streamUrl}") }
        assertTrue("re-opened on its direct address: ${engines.log}", engines.log.contains("exo:${ch.streamUrl}"))
        assertFalse("never given up on screen", engines.log.contains("exo-abandon"))
        assertNull(c.localTimeshift.value)
    }

    @Test
    fun `a pane showing a copy that loses the connection previews the channel directly`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        val ch = channel(50)
        c.tune(ch)
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        engines.exoWatch!!.complete(null)
        // Back from full screen: the pane keeps showing (and saving) the copy.
        c.detach()
        c.preview(ch, muted = true)
        assertNotNull(c.localTimeshift.value)
        readLikeAPlayer(engines.exoUrl!!)
        outage = true
        awaitGiveUp(engines) { log -> log.contains("exo:${ch.streamUrl}") }
        assertTrue("previewed on its direct address: ${engines.log}", engines.log.contains("exo:${ch.streamUrl}"))
        assertNull(c.localTimeshift.value)
    }

    private companion object {
        /** A null TS packet: valid MPEG-TS the cutter syncs on, and enough to open a buffer. */
        val nullPacket = ByteArray(188).also {
            it[0] = 0x47; it[1] = 0x1F; it[2] = 0xFF.toByte(); it[3] = 0x10
        }
    }
}
