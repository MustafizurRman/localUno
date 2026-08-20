package com.mutsho.localuno

import com.mutsho.localuno.network.JoinLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The QR payload. Every failure here is silent on a phone - a wrong port reads as "the host isn't
 * responding", a dropped PIN flag as a rejected join - so the round trip is checked rather than
 * eyeballed against a scanner.
 */
class JoinLinkTest {

    @Test
    fun `round trips`() {
        val original = JoinLink("192.168.1.42", 45231, "Kitchen Table", hasPin = true)
        assertEquals(original, JoinLink.decode(original.encode()))
    }

    @Test
    fun `round trips a name needing escaping`() {
        // Lobby names are free text, and '&' would otherwise end the parameter early - taking the
        // port and PIN flag with it.
        for (name in listOf("Sam & Ali", "50% off", "a=b", "café ☕", "", "?&=#")) {
            val original = JoinLink("10.0.0.7", 8080, name, hasPin = false)
            val decoded = JoinLink.decode(original.encode())
            assertEquals("name <$name> did not survive", original, decoded)
        }
    }

    @Test
    fun `pin flag survives both ways`() {
        assertEquals(true, JoinLink.decode(JoinLink("1.2.3.4", 90, "x", true).encode())?.hasPin)
        assertEquals(false, JoinLink.decode(JoinLink("1.2.3.4", 90, "x", false).encode())?.hasPin)
    }

    @Test
    fun `the pin travels in the code and a scan needs no typing`() {
        // The point of the change: scanning the host's screen already proves you are in the room,
        // so the code carries the PIN rather than a flag saying one exists.
        val original = JoinLink("192.168.0.5", 41621, "Kitchen", hasPin = true, pin = "3592")
        val back = JoinLink.decode(original.encode())
        assertEquals("3592", back?.pin)
        assertEquals(true, back?.hasPin)
    }

    @Test
    fun `a code carrying a pin is treated as locked even without the old flag`() {
        // Belt and braces: `k` present but `pin=0` still means a locked table, so a guest can never
        // end up trying to join a PINned lobby with no PIN in hand.
        val raw = "localuno://join?h=10.0.0.4&p=5000&n=T&pin=0&k=1234"
        val link = JoinLink.decode(raw)
        assertEquals("1234", link?.pin)
        assertEquals(true, link?.hasPin)
    }

    @Test
    fun `an older code with only the flag still asks for the pin`() {
        // A host on the previous build emits no `k`. That must stay joinable the old way rather
        // than looking like an open table - the two phones at a table are often on two versions.
        val raw = "localuno://join?h=10.0.0.4&p=5000&n=T&pin=1"
        val link = JoinLink.decode(raw)
        assertEquals(null, link?.pin)
        assertEquals(true, link?.hasPin)
    }

    @Test
    fun `an open table carries no pin at all`() {
        val link = JoinLink.decode(JoinLink("1.2.3.4", 90, "x", hasPin = false).encode())
        assertEquals(null, link?.pin)
        assertEquals(false, link?.hasPin)
    }

    @Test
    fun `rejects codes that are not ours`() {
        // A scanner sees every QR in the world; none of these should start a connection.
        for (junk in listOf(
            "https://example.com",
            "WIFI:S=home;T=WPA;P=hunter2;;",
            "",
            "   ",
            "localuno://",
            "localuno://join",
            "localuno://something?h=1.2.3.4&p=90",
            "1.2.3.4:9000"
        )) {
            assertNull("accepted <$junk>", JoinLink.decode(junk))
        }
    }

    @Test
    fun `rejects a missing or impossible port`() {
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4"))
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p="))
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p=notanumber"))
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p=0"))
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p=70000"))
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p=-1"))
    }

    @Test
    fun `rejects a missing host`() {
        assertNull(JoinLink.decode("localuno://join?p=9000"))
        assertNull(JoinLink.decode("localuno://join?h=&p=9000"))
    }

    @Test
    fun `tolerates a code from a different build`() {
        // Forwards compatible: unknown keys ignored, absent optional keys defaulted. Two phones at
        // one table are regularly on different versions.
        val decoded = JoinLink.decode("localuno://join?h=192.168.0.9&p=41000&future=xyz")
        assertEquals("192.168.0.9", decoded?.host)
        assertEquals(41000, decoded?.port)
        assertEquals("", decoded?.lobbyName)
        assertEquals(false, decoded?.hasPin)
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(
            JoinLink("1.2.3.4", 90, "x", false),
            JoinLink.decode("  " + JoinLink("1.2.3.4", 90, "x", false).encode() + "\n")
        )
    }
}
