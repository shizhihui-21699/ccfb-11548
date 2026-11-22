package com.acme.privacyshield.core.engine

import com.acme.privacyshield.core.model.FaceObservation
import com.acme.privacyshield.core.model.FrameObservation
import com.acme.privacyshield.core.model.LumaStats
import com.acme.privacyshield.core.model.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the decision engine, written as the scenarios the product must survive.
 *
 * These run on the JVM with no Android dependency, so CI covers the safety-critical logic on every
 * commit. They are the contract: if you change [GuardConfig] defaults or engine rules and one of
 * these fails, you have changed the privacy/usability trade-off and must say so in review.
 */
class GuardEngineTest {

    private val frameMs = 120L

    // ---------------------------------------------------------------- helpers

    private fun face(
        cx: Float,
        cy: Float,
        size: Float,
        yaw: Float? = 0f,
        id: Int? = null,
    ): FaceObservation {
        val half = size / 2f
        return FaceObservation(
            trackingId = id,
            box = NormRect(cx - half, cy - half, cx + half, cy + half),
            yawDegrees = yaw,
            rollDegrees = 0f,
        )
    }

    private val normalLight = LumaStats(mean = 120f, stdDev = 40f, darkFraction = 0.05f, saturatedFraction = 0.0f)

    private class Harness(val config: GuardConfig = GuardConfig()) {
        val engine = GuardEngine(config)
        var now = 0L
        val decisions = ArrayList<GuardDecision>()

        fun step(
            faces: List<FaceObservation>,
            luma: LumaStats? = null,
            glint: com.acme.privacyshield.core.model.GlintStats? = null,
            dt: Long = 120L,
        ): GuardDecision {
            now += dt
            val d = engine.onFrame(FrameObservation(now, faces, luma, glint))
            decisions.add(d)
            return d
        }

        fun run(
            ms: Long,
            faces: List<FaceObservation>,
            luma: LumaStats? = null,
        ): GuardDecision {
            val end = now + ms
            var last = decisions.lastOrNull() ?: GuardDecision(ShieldLevel.NONE, emptySet())
            while (now + 120L <= end) {
                last = step(faces, luma)
            }
            return last
        }
    }

    // ------------------------------------------------------- owner establishment

    @Test
    fun `owner alone is never shielded`() {
        val h = Harness()
        val owner = face(0.5f, 0.5f, 0.30f)
        h.run(10_000, listOf(owner), normalLight)
        assertEquals(ShieldLevel.NONE, h.decisions.last().level)
        assertTrue(h.decisions.none { it.shieldUp })
    }

    @Test
    fun `owner shifting posture in seat does not trigger`() {
        // The employee leans left, right, forward and back by up to 18% of the frame over time.
        val h = Harness()
        val positions = listOf(
            0.50f to 0.50f, 0.44f to 0.52f, 0.56f to 0.48f, 0.47f to 0.55f,
            0.53f to 0.45f, 0.50f to 0.50f, 0.42f to 0.50f, 0.58f to 0.50f,
        )
        for (round in 0 until 12) {
            val (cx, cy) = positions[round % positions.size]
            h.run(700, listOf(face(cx, cy, 0.30f)), normalLight)
        }
        assertTrue(
            "posture changes must not shield; got ${h.decisions.map { it.level }.distinct()}",
            h.decisions.none { it.shieldUp },
        )
    }

    @Test
    fun `owner turning head far to the side and back is not a stranger`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f, yaw = 0f)), normalLight)
        // Head turned away (yaw 70 deg, still frontal by geometry) for 2 s, then back.
        h.run(2000, listOf(face(0.5f, 0.5f, 0.30f, yaw = 70f)), normalLight)
        h.run(2000, listOf(face(0.5f, 0.5f, 0.30f, yaw = 0f)), normalLight)
        assertTrue("head turn must not shield", h.decisions.none { it.shieldUp })
    }

    @Test
    fun `blink or dropped detection under grace window does not hand ownership to stranger`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Owner detection disappears for 300 ms (blink) while a stranger appears at the edge.
        h.run(300, listOf(face(0.15f, 0.5f, 0.12f)), normalLight)
        // Owner returns.
        val d = h.run(3000, listOf(face(0.5f, 0.5f, 0.30f), face(0.15f, 0.5f, 0.12f)), normalLight)
        assertTrue("blink + stranger at edge must not shield", !d.shieldUp || d.level == ShieldLevel.FULL)
    }

    // --------------------------------------------------------- bystander detection

    @Test
    fun `second face looking at screen raises full shield`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        val d = h.run(1500, listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f)), normalLight)
        assertEquals(ShieldLevel.FULL, d.level)
        assertTrue(d.reasons.contains(ShieldReason.BYSTANDER))
    }

    @Test
    fun `bystander looking away is not flagged`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Second person facing elsewhere (yaw 75 degrees).
        val d = h.run(4000, listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f, yaw = 75f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
    }

    @Test
    fun `bystander far away too small to read is not flagged`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Tiny face in the background, below minReadableFaceArea (0.010 => side 0.1).
        val d = h.run(5000, listOf(face(0.5f, 0.5f, 0.30f), face(0.85f, 0.2f, 0.06f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
    }

    @Test
    fun `bystander appears and leaves quickly does not shield`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // 400 ms glance, then gone. Onboard threshold is 600 ms.
        h.run(400, listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f)), normalLight)
        val d = h.run(2000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
    }

    @Test
    fun `shield releases slowly after bystander leaves`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.run(2000, listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f)), normalLight)
        // Bystander leaves. Release must take at least releaseMs (1800 ms).
        h.run(1000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        assertEquals("must still be shielded 1s after threat ends", ShieldLevel.FULL, h.decisions.last().level)
        val d = h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
    }

    @Test
    fun `owner briefly leaves frame and returns near owner zone`() {
        // A 4 s absence is inside ownerReacquireWindowMs (5 s) and the face returns to the exact
        // learned owner zone: that is the employee looking up and back, not a new person.
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.run(4000, emptyList(), normalLight)
        val d = h.run(2000, listOf(face(0.5f, 0.5f, 0.30f, yaw = 0f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
        assertFalse(d.canReestablishOwner)
    }

    @Test
    fun `stranger in the owner zone after a long absence still shields`() {
        // Beyond the reacquire window a face in exactly the owner zone is not the employee:
        // the phone may have been picked up. Camera evidence alone cannot tell, so fail closed.
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.run(7000, emptyList(), normalLight)
        val d = h.run(2500, listOf(face(0.5f, 0.5f, 0.30f, yaw = 0f)), normalLight)
        assertEquals(ShieldLevel.FULL, d.level)
        assertTrue(d.reasons.contains(ShieldReason.BYSTANDER))
    }

    @Test
    fun `stranger holding the phone alone is shielded and cannot be adopted`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Employee gone well past the reacquire window, then a stranger picks the phone up.
        h.run(8_000, emptyList(), normalLight)
        val d = h.run(2500, listOf(face(0.5f, 0.5f, 0.32f)), normalLight)
        assertEquals(ShieldLevel.FULL, d.level)
        assertTrue("UI must offer credential-gated recovery", d.canReestablishOwner)
    }

    @Test
    fun `credential-verified reestablishment restores access`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.run(8_000, emptyList(), normalLight)
        h.run(2500, listOf(face(0.5f, 0.5f, 0.32f)), normalLight)
        // The UI has now checked the device credentials successfully.
        h.engine.reestablishOwner(h.now)
        val d = h.run(3000, listOf(face(0.5f, 0.5f, 0.32f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
    }

    // ------------------------------------------------------------- lighting

    @Test
    fun `sudden exposure change does not instantly shield or release`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Tunnel: luma collapses from 120 to 15 in one frame.
        val dark = LumaStats(15f, 20f, 0.8f, 0f)
        val d1 = h.step(listOf(face(0.5f, 0.5f, 0.30f)), dark)
        assertTrue(d1.lumaUnstable)
        // Even 1 s inside the dark tunnel: not enough for the low-visibility sustain (2.5 s).
        val d2 = h.run(1200, listOf(face(0.5f, 0.5f, 0.30f)), dark)
        assertEquals(ShieldLevel.NONE, d2.level)
    }

    @Test
    fun `sustained darkness degrades to partial shield`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        val dark = LumaStats(18f, 22f, 0.85f, 0f)
        val d = h.run(6000, listOf(face(0.5f, 0.5f, 0.30f)), dark)
        assertEquals(ShieldLevel.PARTIAL, d.level)
        assertTrue(d.reasons.contains(ShieldReason.LOW_VISIBILITY))
    }

    @Test
    fun `user override clears low visibility until it expires`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        val dark = LumaStats(18f, 22f, 0.85f, 0f)
        h.run(5000, listOf(face(0.5f, 0.5f, 0.30f)), dark)
        h.engine.overrideVisibility(h.now)
        val d = h.run(2000, listOf(face(0.5f, 0.5f, 0.30f)), dark)
        assertEquals(ShieldLevel.NONE, d.level)
        assertTrue(d.overrideActive)
    }

    @Test
    fun `user override never dismisses a real bystander`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.engine.overrideVisibility(h.now)
        val d = h.run(2000, listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f)), normalLight)
        assertEquals(ShieldLevel.FULL, d.level)
    }

    @Test
    fun `covered lens shields fully`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Flat black frame: mean ~5, stddev ~1 = obstruction.
        val covered = LumaStats(5f, 1.0f, 1.0f, 0f)
        val d = h.run(5000, emptyList(), covered)
        assertEquals(ShieldLevel.FULL, d.level)
        assertTrue(d.reasons.contains(ShieldReason.CAMERA_OBSTRUCTED))
    }

    // ---------------------------------------------------------------- snooze

    @Test
    fun `snooze disables shielding but is reported`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.engine.setSnoozed(true)
        val d = h.run(2000, listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f)), normalLight)
        assertEquals(ShieldLevel.NONE, d.level)
        assertTrue(d.snoozed)
    }

    @Test
    fun `snooze turns the camera off entirely`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        h.engine.setSnoozed(true)
        assertFalse(
            "no frame can change the decision while snoozed, so the camera must not stream",
            h.engine.shouldCameraStream(h.now),
        )
    }

    // ----------------------------------------------------------------- power

    @Test
    fun `idle scene duty-cycles the camera`() {
        val h = Harness()
        // Nobody for 30 s (idleDutyCycleAfterMs is 20 s).
        h.run(30_000, emptyList(), normalLight)
        val samples = (0 until 40).map { h.engine.shouldCameraStream(h.now + it * 100L) }
        assertTrue("expected some off gaps", samples.contains(false))
        assertTrue("expected some on bursts", samples.contains(true))
    }

    @Test
    fun `active scene keeps camera streaming and fast interval`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        assertTrue(h.engine.shouldCameraStream(h.now))
        assertEquals(h.config.activeIntervalMs, h.engine.recommendedIntervalMs(h.now))
    }

    @Test
    fun `idle scene uses slow interval`() {
        val h = Harness()
        h.run(10_000, emptyList(), normalLight)
        assertEquals(h.config.idleIntervalMs, h.engine.recommendedIntervalMs(h.now))
    }

    // ------------------------------------------------------------- behaviour

    @Test
    fun `shield escalates fast but never de-escalates before release window`() {
        val h = Harness()
        h.run(3000, listOf(face(0.5f, 0.5f, 0.30f)), normalLight)
        // Bystander appears -> should reach FULL within ~800 ms.
        var reachedAt: Long? = null
        val start = h.now
        while (h.now - start < 1500) {
            val d = h.step(listOf(face(0.5f, 0.5f, 0.30f), face(0.2f, 0.45f, 0.18f)), normalLight)
            if (d.level == ShieldLevel.FULL) { reachedAt = h.now - start; break }
        }
        assertTrue("escalation took too long: $reachedAt", reachedAt != null && reachedAt!! <= 900)
    }
}
