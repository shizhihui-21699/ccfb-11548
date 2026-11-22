package com.acme.privacyshield.core.engine

/** How much of the sensitive UI must be hidden right now. */
enum class ShieldLevel {
    /** Content visible. */
    NONE,

    /** Sensitive fields blurred, chrome stays usable, warning banner shown. */
    PARTIAL,

    /** Opaque cover. Only structural chrome (title, actions) is shown. */
    FULL,
}

/** Why the shield is up. Ordered by severity; a face always outranks lighting. */
enum class ShieldReason {
    LOW_VISIBILITY,
    CAMERA_OBSTRUCTED,
    LENS_AIMED_AT_SCREEN,
    BYSTANDER,
}

/**
 * Immutable per-frame output of the engine.
 *
 * Contains no imagery and no biometric data that outlives the frame — only counts and booleans —
 * so it is safe to log at debug level if you must.
 */
data class GuardDecision(
    val level: ShieldLevel,
    val reasons: Set<ShieldReason>,
    /** A visibility-only shield is currently overridden by the user. */
    val overrideActive: Boolean = false,
    /** User snoozed the whole shield (settings screen). */
    val snoozed: Boolean = false,
    /** Number of faces the detector reported on the last frame; for the debug HUD only. */
    val faceCount: Int = 0,
    /** True when the engine is currently ignoring evidence due to an exposure transient. */
    val lumaUnstable: Boolean = false,
    /**
     * The owner has been gone long enough that a face in front of the phone is not assumed to be
     * them. The UI should offer re-establishment **gated by device credentials**, never a bare tap:
     * the person holding the phone is exactly the attacker this feature exists to stop.
     */
    val canReestablishOwner: Boolean = false,
) {
    val shieldUp: Boolean get() = level != ShieldLevel.NONE

    /** The single reason to show in copy; the highest-severity one. */
    val primaryReason: ShieldReason?
        get() = reasons.maxByOrNull { it.ordinal }
}
