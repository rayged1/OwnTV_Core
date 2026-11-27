package tv.own.owntv.core.timeshift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storage rules: at least the window, never under 1 GB free, never the newest pieces. */
class TimeshiftRulesTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `keeps at least the window, dropping only what is beyond it`() {
        // Ten 2-minute pieces = 20 min; window 15 min: dropping two leaves 16 min, a third would leave 14.
        val durations = List(10) { 120_000L }
        val sizes = List(10) { 100 * mb }
        assertEquals(2, TimeshiftRules.piecesToDrop(durations, sizes, windowMs = 15 * 60_000L, usableBytes = 50 * gb))
    }

    @Test
    fun `drops for the free-space floor even inside the window`() {
        val durations = List(10) { 60_000L }
        val sizes = List(10) { 100 * mb }
        // 300 MB free, floor 384 MB: needs 84 MB more -> one piece of 100 MB.
        assertEquals(1, TimeshiftRules.piecesToDrop(durations, sizes, windowMs = 60 * 60_000L, usableBytes = 300 * mb))
    }

    @Test
    fun `never drops the newest pieces, whatever the space`() {
        val durations = List(5) { 60_000L }
        val sizes = List(5) { 100 * mb }
        assertEquals(2, TimeshiftRules.piecesToDrop(durations, sizes, windowMs = 60 * 60_000L, usableBytes = 0))
        assertEquals(0, TimeshiftRules.piecesToDrop(List(2) { 60_000L }, List(2) { mb }, 1_000L, 0))
    }

    @Test
    fun `starting needs the floor plus a margin, and the window follows the choices`() {
        assertFalse(TimeshiftRules.canStart(TimeshiftRules.FREE_FLOOR_BYTES - mb))
        assertTrue(TimeshiftRules.canStart(TimeshiftRules.FREE_FLOOR_BYTES + TimeshiftRules.START_MARGIN_BYTES))
        assertEquals(30, TimeshiftRules.windowMinutesOf(30))
        assertEquals(15, TimeshiftRules.windowMinutesOf(0))
        assertEquals(15, TimeshiftRules.windowMinutesOf(120))
    }

    @Test
    fun `reconnect waits double up to eight seconds`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { TimeshiftRules.reconnectDelayMs(it) })
    }

    @Test
    fun `no network waits the outage out, a provider that drops the stream spends the budget`() {
        assertTrue(TimeshiftRules.isOffline(java.net.UnknownHostException("cf.example")))
        assertTrue(TimeshiftRules.isOffline(java.io.IOException("call failed", java.net.NoRouteToHostException())))
        assertTrue(TimeshiftRules.isOffline(java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertFalse(TimeshiftRules.isOffline(java.net.SocketException("Software caused connection abort")))
        assertFalse(TimeshiftRules.isOffline(java.net.ConnectException("Connection refused")))
        assertFalse(TimeshiftRules.isOffline(java.io.IOException("unexpected end of stream")))
    }
}
