package com.mutsho.localuno

import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.network.LobbyAdvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a table announcement is allowed to claim about itself.
 *
 * mDNS is unauthenticated: anyone on the network can advertise a table with any name, any mode and
 * any numbers, and every field of it was rendered on the Join screen unchecked. The same values
 * arriving by QR code were already validated, which is the tell - one path was written carefully
 * and its sibling was written later.
 *
 * Nothing here is a crash. It is the difference between a list of tables and a list of whatever
 * anyone nearby felt like putting on your screen.
 */
class LobbyAdvertTest {

    // ── Names ───────────────────────────────────────────────────────────────

    @Test fun `an ordinary table name survives`() {
        assertEquals("Kitchen table", LobbyAdvert.name("Kitchen table", "LocalUno-Kitchen"))
    }

    @Test fun `an absurd name is cut down`() {
        val huge = "A".repeat(100_000)
        assertTrue(LobbyAdvert.name(huge, "svc").length <= LobbyAdvert.MAX_LOBBY_NAME)
    }

    @Test fun `a name cannot contain line breaks`() {
        // A list row that spans four lines pushes every other table off the screen.
        assertFalse(LobbyAdvert.name("Kitchen\n\n\nTable", "svc").contains('\n'))
    }

    @Test fun `a name cannot hide zero-width characters`() {
        assertEquals("Kitchen", LobbyAdvert.name("Kitch​en", "svc"))
    }

    @Test fun `an empty name falls back to the service name`() {
        assertEquals("LocalUno-Kitchen", LobbyAdvert.name("   ", "LocalUno-Kitchen"))
    }

    @Test fun `the service name is cleaned too`() {
        // It is structural, but it is still chosen by whoever advertised.
        assertFalse(LobbyAdvert.name(null, "Local\nUno").contains('\n'))
    }

    @Test fun `a name that cleans away entirely still labels the row`() {
        val label = LobbyAdvert.name("​", "​")
        assertEquals(LobbyAdvert.FALLBACK_LOBBY, label)
        assertTrue(label.isNotBlank())
    }

    // ── Mode ────────────────────────────────────────────────────────────────

    @Test fun `a known mode passes through`() {
        assertEquals(GameMode.NO_MERCY.name, LobbyAdvert.mode("NO_MERCY"))
    }

    @Test fun `an unknown mode reads as classic rather than as itself`() {
        // The Join screen used to render the raw string when it matched nothing, which turned an
        // attacker-chosen value into on-screen text.
        assertEquals(GameMode.CLASSIC.name, LobbyAdvert.mode("<script>everything</script>"))
        assertEquals(GameMode.CLASSIC.name, LobbyAdvert.mode(null))
        assertEquals(GameMode.CLASSIC.name, LobbyAdvert.mode(""))
    }

    @Test fun `the mode result is always a real mode`() {
        listOf("NO_MERCY", "CLASSIC", "FLIP", "junk", null).forEach {
            val m = LobbyAdvert.mode(it)
            assertTrue("$m must name a real mode", GameMode.entries.any { g -> g.name == m })
        }
    }

    // ── Seat counts ─────────────────────────────────────────────────────────

    @Test fun `a plausible seat count passes through`() {
        assertEquals(6, LobbyAdvert.seats(6, default = 4))
    }

    @Test fun `a missing seat count uses the default`() {
        assertEquals(4, LobbyAdvert.seats(null, default = 4))
    }

    @Test fun `an absurd seat count is clamped`() {
        assertTrue(LobbyAdvert.seats(2_000_000_000, default = 4) <= 32)
    }

    @Test fun `a negative seat count is clamped up`() {
        // "up to -1 players" is not a thing to put on screen.
        assertTrue(LobbyAdvert.seats(-5, default = 4) >= 1)
    }

    // ── Address ─────────────────────────────────────────────────────────────

    @Test fun `a normal port is accepted`() {
        assertEquals(41234, LobbyAdvert.port(41234))
    }

    @Test fun `port zero is refused`() {
        assertNull(LobbyAdvert.port(0))
    }

    @Test fun `a negative port is refused`() {
        assertNull(LobbyAdvert.port(-1))
    }

    @Test fun `a port past the range is refused`() {
        assertNull(LobbyAdvert.port(70000))
    }

    @Test fun `the port rule matches the one the QR path already used`() {
        // The whole point: discovery is the same data from a less trustworthy source, so it should
        // not be more permissive than a scanned code.
        assertNull(LobbyAdvert.port(0))
        assertEquals(1, LobbyAdvert.port(1))
        assertEquals(65535, LobbyAdvert.port(65535))
        assertNull(LobbyAdvert.port(65536))
    }

    @Test fun `a blank host is refused`() {
        assertNull(LobbyAdvert.host(""))
        assertNull(LobbyAdvert.host("   "))
        assertNull(LobbyAdvert.host(null))
    }

    @Test fun `a real host is kept`() {
        assertEquals("192.168.1.42", LobbyAdvert.host(" 192.168.1.42 "))
    }
}
