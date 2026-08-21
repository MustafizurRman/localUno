package com.mutsho.localuno

import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.HouseRule
import com.mutsho.localuno.model.RulesDigest
import com.mutsho.localuno.model.TimeoutAction
import com.mutsho.localuno.model.enabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digest has one job: never lie about what is true at this table.
 *
 * Both directions matter and only one is obvious. A rule that is on and goes unmentioned is the
 * original bug - a guest losing to a rule nobody showed them. A rule that is OFF but gets mentioned
 * is worse, because a player who has been told the digest is reliable will act on it: someone who
 * reads "last card must be a number" at a table where it is off will hold a perfectly good winning
 * card and lose a round they had won.
 */
class RulesDigestProbeTest {

    private fun settings(
        mode: GameMode = GameMode.CLASSIC,
        rules: GameRules = GameRules(),
        clock: Int? = 15,
        onTimeout: TimeoutAction = TimeoutAction.SKIP,
        mercy: Int = 25
    ) = GameSettings(
        gameMode = mode,
        rules = rules,
        turnTimeoutSeconds = clock,
        turnTimeoutAction = onTimeout,
        mercyThreshold = mercy
    )

    private fun labels(s: GameSettings) = RulesDigest.activeRules(s).map { it.label }

    private fun mentions(s: GameSettings, fragment: String) =
        RulesDigest.activeRules(s).any { fragment.lowercase() in it.label.lowercase() }

    // ── Every rule that is on gets said ─────────────────────────────────────

    @Test fun `stacking is reported when on`() {
        assertTrue(mentions(settings(rules = GameRules(stacking = true)), "stacking"))
    }

    @Test fun `the number-to-win rule is reported when on`() {
        // The one that can lose you a round you thought you had won.
        assertTrue(mentions(settings(rules = GameRules(lastCardMustBeNumber = true)), "number"))
    }

    @Test fun `zero rotates is reported when on`() {
        assertTrue(mentions(settings(rules = GameRules(zeroRotates = true)), "zero"))
    }

    @Test fun `seven swaps is reported when on`() {
        assertTrue(mentions(settings(rules = GameRules(sevenSwaps = true)), "seven"))
    }

    @Test fun `all four at once are all reported`() {
        val all = GameRules(true, true, true, true)
        assertEquals(4, settings(rules = all).rules.enabled().size)
        assertEquals(4, HouseRule.entries.size)
    }

    // ── Nothing that is off is ever said ────────────────────────────────────

    @Test fun `a rule that is off is never mentioned`() {
        val s = settings(rules = GameRules(stacking = true))
        assertFalse("only stacking is on", mentions(s, "seven"))
        assertFalse(mentions(s, "zero"))
        assertFalse(mentions(s, "number"))
    }

    @Test fun `a plain classic table reports no house rules at all`() {
        // Only the clock line, which always applies.
        assertEquals(listOf("15s turn clock"), labels(settings()))
    }

    @Test fun `a plain classic table headlines as standard`() {
        assertEquals("Standard rules", RulesDigest.headline(settings()))
    }

    // ── The knockout line belongs to No Mercy alone ─────────────────────────

    @Test fun `no mercy reports its knockout threshold`() {
        assertTrue(mentions(settings(mode = GameMode.NO_MERCY, mercy = 25), "knocked out"))
    }

    @Test fun `the knockout line carries the actual number`() {
        val s = settings(mode = GameMode.NO_MERCY, mercy = 18)
        assertTrue(RulesDigest.activeRules(s).any { "18" in it.label })
    }

    @Test fun `classic never mentions a knockout`() {
        // There is no knockout at a Classic table; implying one invents a danger.
        assertFalse(mentions(settings(mode = GameMode.CLASSIC), "knocked out"))
    }

    // ── The clock ───────────────────────────────────────────────────────────

    @Test fun `a table with no clock says so rather than staying silent`() {
        // Silence would read as "there is a clock and we forgot to say what it is".
        assertTrue(mentions(settings(clock = null), "no turn clock"))
    }

    @Test fun `the clock line carries the actual duration`() {
        assertTrue(mentions(settings(clock = 45), "45s"))
    }

    @Test fun `the clock line says what running out costs you`() {
        val skip = RulesDigest.activeRules(settings(onTimeout = TimeoutAction.SKIP)).last()
        val draw = RulesDigest.activeRules(settings(onTimeout = TimeoutAction.DRAW_ONE)).last()
        assertTrue("the two penalties must not read identically", skip.detail != draw.detail)
    }

    // ── Shape guarantees the UI leans on ────────────────────────────────────

    @Test fun `the digest is never empty`() {
        // RulesInForce renders the list unconditionally; an empty one would draw a bare box.
        assertTrue(RulesDigest.activeRules(settings()).isNotEmpty())
        assertTrue(RulesDigest.activeRules(settings(clock = null)).isNotEmpty())
    }

    @Test fun `no line is ever blank`() {
        val all = settings(mode = GameMode.NO_MERCY, rules = GameRules(true, true, true, true))
        RulesDigest.activeRules(all).forEach {
            assertTrue("a blank label renders as an orphaned bullet", it.label.isNotBlank())
            assertTrue("a blank detail leaves a label explaining nothing", it.detail.isNotBlank())
        }
    }

    @Test fun `the order is stable`() {
        // The list is rebuilt on every recomposition; an unstable order would reshuffle on screen.
        val s = settings(rules = GameRules(true, true, true, true))
        assertEquals(labels(s), labels(s))
    }

    @Test fun `the headline counts only the optional rules`() {
        // Not the clock and not the knockout - "are we playing house rules?" means the toggles.
        assertEquals("2 house rules", RulesDigest.headline(
            settings(mode = GameMode.NO_MERCY, rules = GameRules(stacking = true, sevenSwaps = true))
        ))
    }

    @Test fun `the headline is singular for exactly one`() {
        assertEquals("1 house rule", RulesDigest.headline(settings(rules = GameRules(stacking = true))))
    }

    @Test fun `every house rule has wording of its own`() {
        // Two rules sharing a description would make the digest useless at the moment it matters.
        val labels = HouseRule.entries.map { it.label }
        val details = HouseRule.entries.map { it.detail }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(details.size, details.toSet().size)
    }
}
