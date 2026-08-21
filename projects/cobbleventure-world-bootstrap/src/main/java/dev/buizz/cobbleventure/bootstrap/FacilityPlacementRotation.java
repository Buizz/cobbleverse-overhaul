package dev.buizz.cobbleventure.bootstrap;

final class FacilityPlacementRotation {
    private FacilityPlacementRotation() {}

    static String resolve(
        String facilityId, String compiledRotation, String legacyGymRotation
    ) {
        if (compiledRotation != null && !compiledRotation.isBlank()) {
            return compiledRotation;
        }
        return facilityId.contains("gym") ? legacyGymRotation : "none";
    }
}
