package dev.buizz.cobbleventure.playermenu;

import java.util.regex.Pattern;

/** Builds the cross-mod CVES callback without exposing an arbitrary command argument. */
final class StarterRouletteEventCallback {
    private static final Pattern SAFE_ARGUMENT = Pattern.compile("[A-Za-z0-9_.:-]+");

    private StarterRouletteEventCallback() {}

    static String command(String token, String species, String reason) {
        String safeToken = requireSafe(token, "token");
        return species == null
            ? "cobbleventure_event_starter_cancel " + safeToken + " "
                + requireSafe(reason, "reason")
            : "cobbleventure_event_starter_result " + safeToken + " "
                + requireSafe(species, "species");
    }

    private static String requireSafe(String value, String name) {
        if (value == null || !SAFE_ARGUMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid starter callback " + name);
        }
        return value;
    }
}
