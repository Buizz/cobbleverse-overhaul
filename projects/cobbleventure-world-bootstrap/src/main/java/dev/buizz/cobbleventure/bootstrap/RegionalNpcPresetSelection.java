package dev.buizz.cobbleventure.bootstrap;

import java.util.Set;

/** Selects the generated EasyNPC representation for a regional placement. */
final class RegionalNpcPresetSelection {
    private RegionalNpcPresetSelection() {}

    static String suffix(boolean cvesV5, String triggerOverride) {
        // V5 proximity trainers use a representation tag that enables their
        // two-stage CVES encounter; V4 keeps its generated action adapter.
        if (cvesV5) {
            return triggerOverride.equals("proximity")
                ? "__v5_proximity" : "__v5";
        }
        return switch (triggerOverride) {
            case "interact" -> "__interact";
            case "proximity" -> "__proximity";
            default -> "";
        };
    }

    static boolean matches(
        boolean cvesV5, String triggerOverride, String npcSlug, Set<String> entityTags
    ) {
        if (!cvesV5) {
            return entityTags.contains("cobbleventure_npc_preset_v4");
        }
        boolean proximity = entityTags.contains("cves_trigger/proximity");
        return sameNpc(true, npcSlug, entityTags)
            && proximity == triggerOverride.equals("proximity");
    }

    static boolean sameNpc(boolean cvesV5, String npcSlug, Set<String> entityTags) {
        if (!cvesV5) {
            return entityTags.contains("cobbleventure_npc_preset_v4");
        }
        String suffix = "/" + npcSlug;
        return entityTags.stream().anyMatch(tag ->
            tag.startsWith("cves_binding/") && tag.endsWith(suffix)
        );
    }
}
