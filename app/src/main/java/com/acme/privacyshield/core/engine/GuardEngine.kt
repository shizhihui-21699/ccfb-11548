package com.acme.privacyshield.core.engine

import com.acme.privacyshield.core.model.FaceObservation
import com.acme.privacyshield.core.model.FrameObservation
import com.acme.privacyshield.core.model.GlintStats
import com.acme.privacyshield.core.model.LumaStats
import com.acme.privacyshield.core.model.NormRect
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole privacy decision, as a pure state machine over [FrameObservation]s.
 *
 * No Android types, no camera, no bitmaps — the entire logic is unit-testable on the JVM, and
 * there is no way for an image to enter this class even by accident.
 *
 * ## Design principles
 *
 * 1. **Only positive evidence blocks.** The shield goes up for a concrete reason: a second face
 *    looking at the screen, or a covered lens. It never goes up merely because evidence is
 *    missing — with one deliberate exception, the low-light fallback, which is PARTIAL and
 *    user-overridable so someone in a dark taxi is not locked out of their work.
 * 2. **Asymmetric timing.** Escalation is fast; release is slow. A missed peek lasts as long as
 *    the peek; a spurious block interrupts real work.
 * 3. **The owner is tracked, not re-identified.** Track association gives continuity across
 *    posture changes; a slow EMA of the owner's box absorbs the employee shifting in their seat.
 * 4. **Exposure transients freeze decisions.** Metro tunnels, passing shadows and auto-exposure
 *    steps move mean luma fast. While that happens, nothing escalates and nothing releases.
 * 5. **Fail soft, never fail open.** If the camera is covered or the detector stalls, the host
 *    collapses the UI rather than showing data with no protection.
 *
 * Not thread-safe: call [onFrame] from one analysis thread.
 */
class GuardEngine(private val config: GuardConfig = GuardConfig()) {

    private class Track(val id: Int, var box: NormRect, val firstSeenMs: Long) {
        var lastSeenMs: Long = firstSeenMs
        var hits: Int = 1
        var evidenceMs: Float = 0f
        var lastYaw: Float? = null
        var seenThisFrame: Boolean = false
    }

    // --- Track / owner state ----------------------------------------------
    private val tracks = ArrayList<Track>(4)
    private var nextTrackId = 1
    private var ownerTrackId: Int = -1
    private var lastOwnerSeenMs: Long = Long.MIN_VALUE
    private var ownerZone: NormRect? = null
    private var ownerEverEstablished = false

    // --- Frame history -----------------------------------------------------
    private var lastFrameMs: Long = Long.MIN_VALUE
    private var firstFrameMs: Long = Long.MIN_VALUE
    private var lastLuma: LumaStats? = null
    private var smoothedLuma: LumaStats? = null
    private var suppressUntilMs: Long = Long.MIN_VALUE
    private var lastFaceSeenMs: Long = Long.MIN_VALUE
    private var lastFaceCount: Int = 0

    // --- Degradation state -------------------------------------------------
    private var obstructedSinceMs: Long = Long.MIN_VALUE
    private var lowVisibilitySinceMs: Long = Long.MIN_VALUE
    private var overrideUntilMs: Long = Long.MIN_VALUE
    private var overrideActive: Boolean = false

    // --- Lens-glint heuristic ---------------------------------------------
    private var glintStreak: Int = 0

    // --- Output state (hysteresis) ----------------------------------------
    private var currentLevel: ShieldLevel = ShieldLevel.NONE
    private var releasePendingSinceMs: Long = Long.MIN_VALUE
    private var snoozed: Boolean = false
    private var canReestablishOwner: Boolean = false

    // =======================================================================
    // Public API
    // =======================================================================

    fun setSnoozed(value: Boolean) {
        snoozed = value
        if (value) {
            currentLevel = ShieldLevel.NONE
            releasePendingSinceMs = Long.MIN_VALUE
        }
    }

    /** User pressed "continue viewing" on a visibility-only shield. Never dismisses a face. */
    fun overrideVisibility(nowMs: Long) {
        overrideUntilMs = nowMs + config.visibilityOverrideMs
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
        ownerTrackId = -1
        ownerEverEstablished = false
        ownerZone = null
        lastFrameMs = Long.MIN_VALUE
        firstFrameMs = Long.MIN_VALUE
        lastLuma = null
        smoothedLuma = null
        lastFaceSeenMs = Long.MIN_VALUE
        lastFaceCount = 0
        obstructedSinceMs = Long.MIN_VALUE
        lowVisibilitySinceMs = Long.MIN_VALUE
        overrideUntilMs = Long.MIN_VALUE
        overrideActive = false
        suppressUntilMs = Long.MIN_VALUE
        glintStreak = 0
        currentLevel = ShieldLevel.NONE
        releasePendingSinceMs = Long.MIN_VALUE
        canReestablishOwner = false
    }

    fun onFrame(obs: FrameObservation): GuardDecision {
        val now = obs.timestampMs
        if (firstFrameMs == Long.MIN_VALUE) firstFrameMs = now
        val dt = frameDeltaMs(now)

        observeLuma(obs.luma, now)
        observeGlint(obs.glint, now)
        updateTracks(obs.faces, now)
        val owner = resolveOwner(now)
        val (bystander, lens) = accumulateThreats(now, dt, owner, obs.glint)
        lastFaceCount = obs.faces.size
        return decide(now, obs.faces.size, bystander, lens)
    }

    /** Numbers only — safe for the debug HUD and for audit logs. Never imagery. */
    fun snapshot(): EngineSnapshot = EngineSnapshot(
        presentTracks = tracks.count { it.seenThisFrame },
        ownerTrackId = ownerTrackId.takeIf { it != -1 },
        maxEvidenceMs = tracks.maxOfOrNull { it.evidenceMs } ?: 0f,
        level = currentLevel,
        ownerZone = ownerZone,
    )

    // =======================================================================
    // Frame bookkeeping
    // =======================================================================

    private fun frameDeltaMs(now: Long): Float {
        val d = if (lastFrameMs == Long.MIN_VALUE) 0L else now - lastFrameMs
        lastFrameMs = now
        return d.coerceIn(0L, 2000L).toFloat()
    }

    private fun observeLuma(luma: LumaStats?, now: Long) {
        if (luma == null) return
        if (luma.mean.isNaN() || luma.stdDev.isNaN()) return
        val prev = lastLuma
        smoothedLuma = luma.smoothed(smoothedLuma, 0.35f)

        if (prev != null && abs(luma.mean - prev.mean) > config.unstableLumaDelta) {
            // Auto-exposure / tunnel / shadow transient. Freeze escalation *and* release, and
            // discount bystander evidence measured through the change.
            suppressUntilMs = now + config.unstableSuppressMs
            for (t in tracks) t.evidenceMs = max(0f, t.evidenceMs - config.bystanderOnboardMs * 0.5f)
        }
        lastLuma = luma
    }

    private fun observeGlint(glint: GlintStats?, now: Long) {
        if (!config.lensDetectionEnabled || glint == null) {
            glintStreak = 0
            return
        }
        val plausible = glint.bestScore >= config.glintMinScore && glint.litCellCount in 1..2
        glintStreak = if (plausible && now >= suppressUntilMs) glintStreak + 1 else 0
    }

    // =======================================================================
    // Tracking
    // =======================================================================

    private fun updateTracks(faces: List<FaceObservation>, now: Long) {
        for (t in tracks) t.seenThisFrame = false

        val orphans = ArrayList<FaceObservation>(faces.size)
        for (face in faces) {
            val track = bestMatch(face.box)
            if (track == null) {
                orphans += face
            } else {
                track.box = face.box
                track.lastSeenMs = now
                track.seenThisFrame = true
                track.hits++
                track.lastYaw = face.yawDegrees
            }
        }
        for (face in orphans) {
            tracks += Track(nextTrackId++, face.box, now).also {
                it.seenThisFrame = true
                it.lastYaw = face.yawDegrees
            }
        }

        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (!t.seenThisFrame && now - t.lastSeenMs > config.trackMaxMissMs) it.remove()
        }

        if (faces.isNotEmpty()) lastFaceSeenMs = now
    }

    private fun bestMatch(box: NormRect): Track? {
        var best: Track? = null
        var bestScore = -1f
        for (t in tracks) {
            if (t.seenThisFrame) continue
            val iou = t.box.iou(box)
            val dist = t.box.centerDistanceTo(box)
            if (iou >= config.matchIou || dist <= config.matchCenterDistance) {
                val score = iou + (1f - dist) * 0.01f
                if (score > bestScore) {
                    bestScore = score
                    best = t
                }
            }
        }
        return best
    }

    // =======================================================================
    // Owner resolution
    // =======================================================================

    /**
     * Returns the track currently considered "the employee", or null.
     *
     * The incumbent survives a short absence ([GuardConfig.ownerLostGraceMs]) so a blink, a
     * head-turn or one dropped detection does not hand ownership to a stranger standing nearby.
     *
     * Beyond that window, ownership is **not** re-established automatically. If nobody was seen
     * for longer than [GuardConfig.ownerReacquireWindowMs], the phone may have been put down and
     * picked up by someone else — indistinguishable from the camera's point of view, and exactly
     * the threat this feature exists for. Such a scene is handled as "unknown person in front of
     * the screen" (see [accumulateThreats]) and the UI offers credential-gated re-establishment.
     */
    private fun resolveOwner(now: Long): Track? {
        val incumbent = tracks.firstOrNull { it.id == ownerTrackId }
        if (incumbent != null) {
            if (incumbent.seenThisFrame) {
                lastOwnerSeenMs = now
                learnOwnerZone(incumbent)
                return incumbent
            }
            if (now - lastOwnerSeenMs <= config.ownerLostGraceMs) return incumbent
        }

        val ownerGoneTooLong = ownerEverEstablished &&
            lastOwnerSeenMs != Long.MIN_VALUE &&
            now - lastOwnerSeenMs > config.ownerReacquireWindowMs
        if (ownerGoneTooLong) {
            canReestablishOwner = true
            ownerTrackId = -1
            return null
        }

        val candidates = tracks.filter {
            it.seenThisFrame && it.hits >= config.ownerMinHits && looksAtScreen(it.lastYaw)
        }
        if (candidates.isEmpty()) return null

        val chosen: Track = when {
            !ownerEverEstablished ->
                // Bootstrap: the biggest, most central, most persistent frontal face.
                candidates.maxByOrNull { ownerScore(it, null) }

            candidates.size == 1 && isNearOwnerZone(candidates[0].box, config.ownerZoneTolerance * 2.5f) ->
                // Employee turned away and back — only if they are where they were.
                candidates[0]

            else -> {
                val best = candidates.maxByOrNull { ownerScore(it, ownerZone) }
                best?.takeIf { ownerZone == null || isNearOwnerZone(it.box, config.ownerZoneTolerance * 1.6f) }
            }
        } ?: return null

        if (chosen.id != ownerTrackId) chosen.evidenceMs = 0f
        ownerTrackId = chosen.id
        lastOwnerSeenMs = now
        ownerEverEstablished = true
        canReestablishOwner = false
        learnOwnerZone(chosen)
        return chosen
    }

    /**
     * Called after the UI has verified the person with device credentials. Re-adopts whatever
     * frontal face is in front of the phone right now as the owner and clears accumulated evidence.
     */
    fun reestablishOwner(nowMs: Long) {
        val frontal = tracks.filter {
            it.seenThisFrame && it.hits >= config.ownerMinHits && looksAtScreen(it.lastYaw)
        }.maxByOrNull { ownerScore(it, ownerZone) }

        for (t in tracks) t.evidenceMs = 0f
        canReestablishOwner = false
        currentLevel = ShieldLevel.NONE
        releasePendingSinceMs = Long.MIN_VALUE
        lastOwnerSeenMs = nowMs

        if (frontal != null) {
            ownerTrackId = frontal.id
            ownerEverEstablished = true
            ownerZone = frontal.box
        } else {
            // No usable face at the moment of verification (e.g. the user looked away to type the
            // PIN). Forget the old owner model so the next face is adopted as a fresh bootstrap
            // rather than being mistaken for a stranger.
            ownerTrackId = -1
            ownerEverEstablished = false
            ownerZone = null
        }
    }

    private fun ownerScore(t: Track, zone: NormRect?): Float {
        var score = t.hits.toFloat() * 2f + t.box.area * 4f - t.box.centerOffset() * 1.5f
        if (zone != null) score -= zone.centerDistanceTo(t.box) * 12f
        return score
    }

    private fun learnOwnerZone(track: Track) {
        val box = track.box
        val prev = ownerZone
        val a = config.ownerZoneAlpha
        ownerZone = if (prev == null) box else NormRect(
            left = prev.left + (box.left - prev.left) * a,
            top = prev.top + (box.top - prev.top) * a,
            right = prev.right + (box.right - prev.right) * a,
            bottom = prev.bottom + (box.bottom - prev.bottom) * a,
        )
    }

    private fun isNearOwnerZone(box: NormRect, tolerance: Float): Boolean {
        val zone = ownerZone ?: return false
        return zone.centerDistanceTo(box) <= tolerance
    }

    // =======================================================================
    // Threats
    // =======================================================================

    private fun looksAtScreen(yaw: Float?): Boolean =
        yaw == null || abs(yaw) <= config.gazeMaxYawDegrees

    private fun accumulateThreats(
        now: Long,
        dtMs: Float,
        owner: Track?,
        glint: GlintStats?,
    ): Pair<Boolean, Boolean> {
        overrideActive = now < overrideUntilMs
        val suppressing = now < suppressUntilMs

        // While the owner is in the grace window but not actually visible, hold off: the face on
        // screen is most likely the employee mid-blink or mid-turn, not a stranger.
        val ownerInGrace = owner != null && !owner.seenThisFrame

        // Ownership was lost long ago (phone put down, or handed over). Every frontal face is a
        // potential stranger; there is no "owner" to exempt.
        val ownerUnknown = owner == null && canReestablishOwner

        var bystander = false
        for (t in tracks) {
            if (owner != null && t.id == owner.id) {
                t.evidenceMs = 0f
                continue
            }
            if (!t.seenThisFrame) {
                t.evidenceMs = max(0f, t.evidenceMs - dtMs * config.bystanderDecayRate)
                continue
            }

            val eligible = !ownerInGrace &&
                t.hits >= config.bystanderMinHits &&
                t.box.area >= config.minReadableFaceArea &&
                looksAtScreen(t.lastYaw)

            // The yaw cone for an unknown owner is wide, but a face turned away is not reading text.
            t.evidenceMs = if (eligible && !suppressing) {
                t.evidenceMs + dtMs
            } else {
                max(0f, t.evidenceMs - dtMs * config.bystanderDecayRate)
            }

            if (t.evidenceMs >= config.bystanderOnboardMs) bystander = true
        }

        // Unknown owner with a clear view of the screen: shield even before a face is "confirmed",
        // because the phone was left unattended and someone is now holding/looking at it.
        if (!bystander && ownerUnknown && !suppressing) {
            val anyFrontal = tracks.any {
                it.seenThisFrame && it.box.area >= config.minReadableFaceArea && looksAtScreen(it.lastYaw)
            }
            if (anyFrontal) bystander = true
        }

        val lens = config.lensDetectionEnabled &&
            glint != null &&
            glint.bestScore >= config.glintMinScore &&
            glintStreak >= config.glintSustainFrames

        return bystander to lens
    }

    // =======================================================================
    // Decision & hysteresis
    // =======================================================================

    private fun decide(
        now: Long,
        faceCount: Int,
        bystander: Boolean,
        lens: Boolean,
    ): GuardDecision {
        if (snoozed) {
            currentLevel = ShieldLevel.NONE
            releasePendingSinceMs = Long.MIN_VALUE
            return GuardDecision(
                level = ShieldLevel.NONE,
                reasons = emptySet(),
                overrideActive = overrideActive,
                snoozed = true,
                faceCount = faceCount,
                lumaUnstable = now < suppressUntilMs,
                canReestablishOwner = false,
            )
        }

        val reasons = linkedSetOf<ShieldReason>()
        var obstructed = false
        var lowVis = false

        val s = smoothedLuma
        if (s != null) {
            obstructed = updateObstruction(now, s)
            lowVis = updateLowVisibility(now, s)
        }

        if (bystander) reasons += ShieldReason.BYSTANDER
        if (lens) reasons += ShieldReason.LENS_AIMED_AT_SCREEN
        if (obstructed) reasons += ShieldReason.CAMERA_OBSTRUCTED
        // Only a *sustained* low-light / blown-out scene degrades usable content, and only when
        // the user has not explicitly chosen to keep looking in this environment.
        if (lowVis && !obstructed && !overrideActive) reasons += ShieldReason.LOW_VISIBILITY

        val target = targetLevel(reasons)
        applyHysteresis(now, target)

        return GuardDecision(
            level = currentLevel,
            reasons = reasons,
            overrideActive = overrideActive,
            snoozed = false,
            faceCount = faceCount,
            lumaUnstable = now < suppressUntilMs,
            canReestablishOwner = canReestablishOwner,
        )
    }

    private fun updateObstruction(now: Long, s: LumaStats): Boolean {
        val obstructed = s.mean <= config.obstructionMean && s.stdDev <= config.obstructionStdDev
        if (obstructed) {
            if (obstructedSinceMs == Long.MIN_VALUE) obstructedSinceMs = now
        } else {
            obstructedSinceMs = Long.MIN_VALUE
        }
        return obstructed && now - obstructedSinceMs >= config.degradedSustainMs
    }

    private fun updateLowVisibility(now: Long, s: LumaStats): Boolean {
        val low = s.mean < config.lowLightMean || s.mean > config.blownOutMean
        if (low) {
            if (lowVisibilitySinceMs == Long.MIN_VALUE) lowVisibilitySinceMs = now
        } else {
            lowVisibilitySinceMs = Long.MIN_VALUE
        }
        return low && now - lowVisibilitySinceMs >= config.degradedSustainMs
    }

    private fun applyHysteresis(now: Long, target: ShieldLevel) {
        if (target.severity() >= currentLevel.severity()) {
            currentLevel = target
            releasePendingSinceMs = Long.MIN_VALUE
            return
        }
        if (releasePendingSinceMs == Long.MIN_VALUE) releasePendingSinceMs = now
        if (now - releasePendingSinceMs >= config.releaseMs) {
            currentLevel = target
            releasePendingSinceMs = Long.MIN_VALUE
        }
    }

    private fun targetLevel(reasons: Set<ShieldReason>): ShieldLevel {
        var level = ShieldLevel.NONE
        for (r in reasons) {
            val l = when (r) {
                ShieldReason.BYSTANDER,
                ShieldReason.LENS_AIMED_AT_SCREEN -> ShieldLevel.FULL
                ShieldReason.CAMERA_OBSTRUCTED -> config.obstructedLevel
                ShieldReason.LOW_VISIBILITY -> config.lowVisibilityLevel
            }
            if (l.severity() > level.severity()) level = l
        }
        return level
    }

    // =======================================================================
    // Power scheduling
    // =======================================================================

    private fun sinceLastFaceMs(now: Long): Long {
        val ref = if (lastFaceSeenMs == Long.MIN_VALUE) firstFrameMs else lastFaceSeenMs
        if (ref == Long.MIN_VALUE) return 0L
        return (now - ref).coerceAtLeast(0L)
    }

    /** Suggested gap between analysed frames. */
    fun recommendedIntervalMs(nowMs: Long): Long = when {
        currentLevel != ShieldLevel.NONE -> config.fastIntervalMs
        lastFaceCount >= 2 -> config.fastIntervalMs // there is someone to watch besides the owner
        sinceLastFaceMs(nowMs) < 2000L -> config.activeIntervalMs
        else -> config.idleIntervalMs
    }

    /**
     * Whether the camera should be streaming right now.
     *
     * After [GuardConfig.idleDutyCycleAfterMs] with nobody in front, stream in short bursts so a
     * phone lying on a desk is not running the camera and the detector continuously.
     */
    fun shouldCameraStream(nowMs: Long): Boolean {
        // Snoozed means the user has deliberately turned protection off: analysing frames cannot
        // change the decision, so running the camera would only burn power.
        if (snoozed) return false
        if (currentLevel != ShieldLevel.NONE) return true
        val since = sinceLastFaceMs(nowMs)
        if (since < config.idleDutyCycleAfterMs) return true
        val period = config.idleBurstOnMs + config.idleBurstOffMs
        return (since - config.idleDutyCycleAfterMs) % period < config.idleBurstOnMs
    }
}

data class EngineSnapshot(
    val presentTracks: Int,
    val ownerTrackId: Int?,
    val maxEvidenceMs: Float,
    val level: ShieldLevel,
    val ownerZone: NormRect?,
)

fun ShieldLevel.severity(): Int = when (this) {
    ShieldLevel.NONE -> 0
    ShieldLevel.PARTIAL -> 1
    ShieldLevel.FULL -> 2
}
