package dev.buizz.cobbleventure.adventure.event;

/** Stable failure codes exposed through CVES {@code movement_result.failure_reason}. */
public final class EventMovementFailureReason {
    public static final String ANCHOR_NOT_FOUND = "anchor_not_found";
    public static final String ANCHOR_REQUIRED = "anchor_required";
    public static final String COLLISION = "collision";
    public static final String DESTINATION_DISABLED = "destination_disabled";
    public static final String DESTINATION_NOT_FOUND = "destination_not_found";
    public static final String DESTINATION_UNAVAILABLE = "destination_unavailable";
    public static final String FADE_UNAVAILABLE = "fade_unavailable";
    public static final String FALL_RISK = "fall_risk";
    public static final String LOCATION_PROVIDER_UNAVAILABLE = "location_provider_unavailable";
    public static final String LOCATION_RESOLUTION_FAILED = "location_resolution_failed";
    public static final String MOVEMENT_FAILED = "movement_failed";
    public static final String MOVEMENT_SUBJECT_UNAVAILABLE = "movement_subject_unavailable";
    public static final String MOVEMENT_TIMEOUT = "movement_timeout";
    public static final String NPC_SUBJECT_UNAVAILABLE = "npc_subject_unavailable";
    public static final String TELEPORT_FAILED = "teleport_failed";
    public static final String UNSAFE_LANDING = "unsafe_landing";
    public static final String WORLD_NOT_READY = "world_not_ready";

    private EventMovementFailureReason() {}
}
