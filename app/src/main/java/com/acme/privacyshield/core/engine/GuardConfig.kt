package com.acme.privacyshield.core.engine

/**
 * Tunables for [GuardEngine].
 *
 * Every default below was chosen with one rule in mind: **a wrong block is worse than a wrong
 * pass for usability, but a wrong pass is worse for confidentiality.** The middle ground taken
 * here is asymmetric timing (escalate in ~0.6 s, release in ~1.8 s) plus a fail-safe that
 * degrades the UI to PARTIAL rather than hard-blocking.
 *
 * These values are a starting point, not a validated calibration. Real deployments must run the
 * scenario suite in `docs/TUNING.md` on the actual device models in use, because front-camera
 * FOV, sensor size and auto-exposure behaviour vary a lot between handsets.
 */
data class GuardConfig(
    // ---- Track association ----
    /** Minimum IoU or center distance for a detection to be matched to an existing track. */
    val matchIou: Float = 0.20f,
    val matchCenterDistance: Float = 0.14f,
    /** A track unseen for this long is dropped. */
    val trackMaxMissMs: Long = 800L,

    // ---- Owner identification ----
    /** Consecutive hits before a track may be elected owner. */
    val ownerMinHits: Int = 3,
    /** How long a previously known owner keeps its "owner" claim after leaving frame. */
    val ownerLostGraceMs: Long = 1500L,
    /**
     * Window after which a lone face is no longer assumed to be the returning employee.
     *
     * Inside this window a single frontal face near the learned owner zone is the employee who
     * looked away or blinked — requiring a PIN for every head-turn would get the feature disabled.
     * Beyond it, a face in front of the phone is treated as an unknown person and the UI offers
     * credential-gated re-establishment.
     *
     * 5 s is a judgement call: the OS screen timeout (typically 30 s+) is the real backstop for a
     * phone genuinely left unattended. Camera-only evidence cannot separate "employee leaned out of
     * frame" from "someone picked the phone up", so this window is a usability/risk trade-off that
     * must be reviewed with your security team — see docs/DESIGN.md "Residual risks".
     */
    val ownerReacquireWindowMs: Long = 5_000L,
    /** EMA factor for the learned owner zone. Small = very tolerant of posture changes. */
    val ownerZoneAlpha: Float = 0.06f,
    /** Owner zone tolerance floor, in normalized units, around the learned center. */
    val ownerZoneTolerance: Float = 0.13f,

    // ---- Bystander evidence ----
    /** Consecutive observations before a track can accumulate bystander evidence at all. */
    val bystanderMinHits: Int = 3,
    /** Sustained evidence needed to raise the shield (ms). */
    val bystanderOnboardMs: Long = 600L,
    /** Evidence decay rate when a track stops looking at the screen (multiplier of dt). */
    val bystanderDecayRate: Float = 2.0f,
    /** Max |yaw| (degrees) for a face to count as looking toward the screen. */
    val gazeMaxYawDegrees: Float = 38f,
    /** Ignore faces smaller than this fraction of the frame: too far to resolve text. */
    val minReadableFaceArea: Float = 0.010f,

    // ---- Lighting / visibility ----
    /** Mean luma below this counts as "too dark to verify". */
    val lowLightMean: Float = 30f,
    /** Mean luma above this counts as "blown out". */
    val blownOutMean: Float = 232f,
    /** A frame this flat *and* dark is treated as a covered lens, not a dark room. */
    val obstructionMean: Float = 26f,
    val obstructionStdDev: Float = 4.0f,
    /** Sustained duration before degraded visibility affects the shield. */
    val degradedSustainMs: Long = 2500L,
    /** Large frame-to-frame mean jump: suppress decisions during auto-exposure transients. */
    val unstableLumaDelta: Float = 22f,
    val unstableSuppressMs: Long = 400L,

    // ---- Lens-aimed-at-screen (experimental) ----
    val lensDetectionEnabled: Boolean = false,
    /** Peak/ring luminance ratio required to consider a bright spot lens-like. */
    val glintMinScore: Float = 2.6f,
    val glintMinPeakLuma: Float = 170f,
    val glintSustainFrames: Int = 3,

    // ---- Output shaping ----
    /** Hysteresis before leaving a shielded state (ms). */
    val releaseMs: Long = 1800L,
    /** What to do when the camera is obstructed: shield fully or only partially. */
    val obstructedLevel: ShieldLevel = ShieldLevel.FULL,
    /** What to do in sustained low light / blown-out scenes. */
    val lowVisibilityLevel: ShieldLevel = ShieldLevel.PARTIAL,
    /** How long a user override of a *visibility* reason lasts. Never applies to a face. */
    val visibilityOverrideMs: Long = 120_000L,

    // ---- Power ----
    /** Analysis interval while a threat or degraded state is active. */
    val fastIntervalMs: Long = 120L,
    /** Analysis interval while faces are visible but nothing is wrong. */
    val activeIntervalMs: Long = 300L,
    /** Analysis interval when the scene looks empty. */
    val idleIntervalMs: Long = 1200L,
    /** After this long with no faces, start duty-cycling the camera. */
    val idleDutyCycleAfterMs: Long = 20_000L,
    /** Camera-on burst length during idle duty cycling. */
    val idleBurstOnMs: Long = 1500L,
    /** Camera-off gap during idle duty cycling. */
    val idleBurstOffMs: Long = 3500L,
) {
    init {
        require(matchIou in 0f..1f)
        require(bystanderOnboardMs > 0)
        require(releaseMs >= 0)
        require(lowLightMean < blownOutMean)
        require(fastIntervalMs > 0 && idleIntervalMs >= fastIntervalMs)
    }
}
