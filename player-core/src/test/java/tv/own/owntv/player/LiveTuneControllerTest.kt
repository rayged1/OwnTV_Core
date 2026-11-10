package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.stalker.ReconnectUrlProvider

/**
 * The sequencing both apps used to hand-copy: superseding tunes, the ladder across engines, and the
 * "Give up after" alarm. Driven on virtual time against a fake engine pair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTuneControllerTest {

    private class FakeEngines : LiveEngines {
        val log = mutableListOf<String>()
        /** Stream opens and mpv releases in the order they happened, for the session-ordering tests. */
        val order = mutableListOf<String>()
        override var exoUrl: String? = null
        override var exoIsHls = false
        override var exoFailed = false
        override var exoStillReconnecting = false
        override var exoContentBroken = false
        override var exoHasPlayed = false
        override var mpvHasStream = false

        /** Complete with a reason to fail the ExoPlayer watch, or with null for "opened". */
        var exoWatch: CompletableDeferred<String?>? = null
        var mpvWatch: CompletableDeferred<MpvOutcome>? = null
        /** Set before the watch opens to fail the channel later, after it has played. */
        var exoLaterFailure: CompletableDeferred<String>? = null

        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) {
            exoUrl = url
            log += "exo:$url"
            order += "exo-open"
        }
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
            val d = CompletableDeferred<String?>().also { exoWatch = it }
            val reason = d.await()
            if (reason == null) onOpened() else if (stillOurs()) handOver(reason)
            if (reason == null) exoLaterFailure?.let { val r = it.await(); if (stillOurs()) handOver(r) }
        }

        override fun mpvPlay(url: String, request: LiveRequest) {
            mpvHasStream = true
            log += "mpv:$url"
        }
        override fun mpvStop() {
            if (mpvHasStream) order += "mpv-stop"
            mpvHasStream = false
        }
        override suspend fun mpvStopAndAwaitRelease() {
            if (mpvHasStream) order += "mpv-released"
            mpvHasStream = false
        }
        override fun mpvAbandon(reason: String) { log += "mpv-abandon" }
        override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? {
            val d = CompletableDeferred<MpvOutcome>().also { mpvWatch = it }
            return withTimeoutOrNull(timeoutMs) { d.await() }
        }
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) {}
    }

    private class FakeHost(private val scope: TestScope) : LiveTuneController.Host {
        var preference = EnginePreference.EXO_FIRST
        var budgetSecs = 30
        var sourceDelayMs = 0L
        val pins = mutableListOf<Boolean>()
        val events = mutableListOf<PlayerFailureReason>()

        override suspend fun sourceOf(sourceId: Long): SourceEntity? {
            if (sourceDelayMs > 0) delay(sourceDelayMs)
            return null
        }
        override fun needsResolve(source: SourceEntity?) = false
        override suspend fun resolve(source: SourceEntity, cmd: String): String? = null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) { pins += onMpv }
        override suspend fun globalPreference() = preference
        override suspend fun globalBudgetSecs() = budgetSecs
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {
            events += reason
        }
        override fun nowMs(): Long = scope.testScheduler.currentTime
    }

    private fun channel(id: Long) = ChannelEntity(
        id = id,
        sourceId = 1,
        name = "ch$id",
        streamUrl = "http://tune-controller-test.invalid/live/$id.ts",
    )

    private fun TestScope.controller(engines: FakeEngines, host: FakeHost) =
        LiveTuneController(backgroundScope, engines, host)

    @Test
    fun `a newer tune supersedes one still waiting out the decoder release`() = runTest {
        val engines = FakeEngines().apply { exoUrl = "http://preview.invalid/x" }
        val host = FakeHost(this).apply { preference = EnginePreference.MPV_FIRST }
        val c = controller(engines, host)
        c.tune(channel(1))
        runCurrent() // channel 1 is now waiting for ExoPlayer's decoder before mpv
        host.preference = EnginePreference.EXO_FIRST
        c.tune(channel(2))
        advanceTimeBy(5_000)
        assertFalse("the superseded tune must never reach mpv", engines.log.any { it.startsWith("mpv:") })
        assertEquals(listOf("exo:${channel(2).streamUrl}"), engines.log)
    }

    @Test
    fun `an ExoPlayer failure climbs to mpv, and mpv failing too ends the tune`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.tune(channel(3))
        runCurrent()
        assertTrue(c.liveOnExo.value)
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertFalse(c.liveOnExo.value)
        assertTrue(engines.log.contains("mpv:${channel(3).streamUrl}"))
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        assertEquals("mpv-abandon", engines.log.last())
        assertEquals(
            listOf(PlayerFailureReason.LIVE_FALLBACK, PlayerFailureReason.LIVE_NO_FALLBACK),
            host.events,
        )
    }

    @Test
    fun `the give-up alarm ends a tune that never opens`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { budgetSecs = 10 }
        val c = controller(engines, host)
        c.tune(channel(4))
        advanceTimeBy(9_000)
        assertFalse(engines.log.contains("exo-abandon"))
        advanceTimeBy(1_001)
        assertEquals("exo-abandon", engines.log.last())
        assertEquals(listOf(PlayerFailureReason.LIVE_NO_FALLBACK), host.events)
    }

    @Test
    fun `a channel that opens stands the alarm down`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { budgetSecs = 10 }
        val c = controller(engines, host)
        c.tune(channel(5))
        runCurrent()
        engines.exoWatch!!.complete(null)
        advanceTimeBy(60_000)
        assertFalse(engines.log.contains("exo-abandon"))
        assertTrue(host.events.isEmpty())
    }

    /** A channel plays, its ladder budget runs out, then it stalls for good with nothing left to try. */
    private fun TestScope.stallAfterPlayingWithNoFallback(engines: FakeEngines) {
        val host = FakeHost(this).apply { budgetSecs = 10 }
        val c = controller(engines, host)
        c.tune(channel(6))
        runCurrent()
        val later = CompletableDeferred<String>().also { engines.exoLaterFailure = it }
        engines.exoWatch!!.complete(null)
        advanceTimeBy(60_000)
        later.complete("played, then stalled for 18s without recovering")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
    }

    @Test
    fun `with nothing left to try, ExoPlayer's own reconnects are left running`() = runTest {
        val engines = FakeEngines().apply { exoStillReconnecting = true }
        stallAfterPlayingWithNoFallback(engines)
        assertFalse(engines.log.contains("exo-abandon"))
    }

    @Test
    fun `with nothing left to try and ExoPlayer out of reconnects, the tune is abandoned`() = runTest {
        val engines = FakeEngines()
        stallAfterPlayingWithNoFallback(engines)
        assertEquals("exo-abandon", engines.log.last())
    }

    @Test
    fun `a stream ExoPlayer cannot play after it opened goes to mpv, budget or not`() = runTest {
        val engines = FakeEngines().apply { exoContentBroken = true }
        stallAfterPlayingWithNoFallback(engines)
        assertTrue(engines.log.any { it.startsWith("mpv:") })
        assertFalse(engines.log.contains("exo-abandon"))
    }

    @Test
    fun `a mid-stream failure after the budget expired still reaches mpv when a picture had played`() = runTest {
        // The budget bounds the black screen before a tune opens. A channel that played for minutes and
        // then lost its stream is not a tuning failure: the ladder must still be allowed to climb.
        val engines = FakeEngines().apply { exoHasPlayed = true }
        stallAfterPlayingWithNoFallback(engines)
        assertTrue(engines.log.any { it.startsWith("mpv:") })
        assertFalse(engines.log.contains("exo-abandon"))
    }

    @Test
    fun `opening the channel ExoPlayer is previewing shows ExoPlayer at once`() = runTest {
        val engines = FakeEngines()
        val c = controller(engines, FakeHost(this))
        c.preview(channel(9), muted = true)
        runCurrent()
        assertFalse(c.liveOnExo.value)
        c.expectPromotion(channel(9))
        assertTrue("before the tune has run", c.liveOnExo.value)
    }

    @Test
    fun `an expected promotion no tune followed does not freeze the preview pane`() = runTest {
        val engines = FakeEngines()
        val c = controller(engines, FakeHost(this))
        c.preview(channel(9), muted = true)
        runCurrent()
        c.expectPromotion(channel(9))
        assertTrue(c.liveOnExo.value)
        c.preview(channel(11), muted = true)
        runCurrent()
        assertFalse(c.liveOnExo.value)
        assertTrue(engines.log.last().startsWith("exo:") && engines.log.last().endsWith("/11.ts"))
    }

    @Test
    fun `a channel whose ExoPlayer preview failed goes to the next engine without showing it`() = runTest {
        val engines = FakeEngines()
        val c = controller(engines, FakeHost(this))
        c.preview(channel(10), muted = true)
        runCurrent()
        engines.exoFailed = true
        c.expectPromotion(channel(10))
        assertFalse(c.liveOnExo.value)
        c.tune(channel(10))
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1_000)
        assertTrue(engines.log.any { it.startsWith("mpv:") })
        assertFalse("the failed preview is not promoted", engines.log.contains("exo-unmute"))
    }

    @Test
    fun `a late failure of a replaced tune changes nothing`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.tune(channel(6))
        runCurrent()
        val oldWatch = engines.exoWatch!!
        c.tune(channel(7))
        runCurrent()
        oldWatch.complete("late failure")
        advanceTimeBy(5_000)
        assertFalse(engines.log.any { it.startsWith("mpv:") })
        assertTrue(host.events.isEmpty())
    }

    @Test
    fun `handing the player to an archive cancels a live tune still in flight`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { sourceDelayMs = 100 }
        val c = controller(engines, host)
        c.tune(channel(8))
        advanceTimeBy(50)
        var archiveStarted = false
        c.launch {
            releaseForArchive()
            delay(10) // a catch-up resolving its archive URL
            archiveStarted = true
        }
        advanceTimeBy(5_000)
        assertTrue("the catch-up must survive its own release", archiveStarted)
        assertTrue("the live stream must not start over the archive", engines.log.isEmpty())
    }

    @Test
    fun `the engine button pins the choice and stays on that engine`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.tune(channel(9))
        runCurrent()
        c.toggleEngine()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertEquals(listOf(true), host.pins)
        assertTrue(engines.log.contains("mpv:${channel(9).streamUrl}"))
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        advanceTimeBy(5_000)
        // "mpv only" for this tune: no hand-back to ExoPlayer, the failure stays on screen.
        assertEquals("mpv-abandon", engines.log.last())
        assertEquals(1, engines.log.count { it.startsWith("exo:") })
    }

    @Test
    fun `a tune on mpv with nothing on ExoPlayer does not wait for a decoder`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { preference = EnginePreference.MPV_FIRST }
        val c = controller(engines, host)
        c.tune(channel(10))
        runCurrent()
        assertTrue(engines.log.contains("mpv:${channel(10).streamUrl}"))
    }

    @Test
    fun `a tune started before the settings are read waits for them instead of using defaults`() = runTest {
        val engines = FakeEngines()
        val stored = CompletableDeferred<EnginePreference>()
        val host = object : LiveTuneController.Host by FakeHost(this) {
            override suspend fun globalPreference() = stored.await()
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel(12))
        advanceTimeBy(1_000)
        assertTrue("nothing may open on a default while the store is unread", engines.log.isEmpty())
        stored.complete(EnginePreference.MPV_ONLY) // the store's first read lands: the user chose mpv
        runCurrent()
        assertEquals(listOf("mpv:${channel(12).streamUrl}"), engines.log)
    }

    @Test
    fun `pressing OK on the channel being previewed promotes it instead of rebuilding`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.preview(channel(11), muted = true)
        runCurrent()
        c.tune(channel(11))
        runCurrent()
        assertEquals(listOf("exo:${channel(11).streamUrl}", "exo-unmute"), engines.log)
    }

    @Test
    fun `on a one-session panel ExoPlayer opens only after mpv has released the previous channel`() = runTest {
        LiveStreamQuirks.clearForTest()
        try {
            val engines = FakeEngines().apply { mpvHasStream = true } // the previous channel plays on mpv
            LiveStreamQuirks.rememberSessionLimit(channel(20).streamUrl)
            val c = controller(engines, FakeHost(this))
            c.tune(channel(20))
            runCurrent()
            assertEquals(listOf("mpv-released", "exo-open"), engines.order)
        } finally {
            LiveStreamQuirks.clearForTest()
        }
    }

    @Test
    fun `on other panels the handover from mpv keeps its asynchronous stop`() = runTest {
        LiveStreamQuirks.clearForTest()
        val engines = FakeEngines().apply { mpvHasStream = true }
        val c = controller(engines, FakeHost(this))
        c.tune(channel(21))
        runCurrent()
        assertEquals(listOf("mpv-stop", "exo-open"), engines.order)
    }
}
