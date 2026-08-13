package dev.buizz.cobbleventure.playermenu.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.playermenu.BadgeProgressNetwork;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Converts the authored league, Gym, and Badge catalogs into trainer-card pages. */
final class LeagueProgressionContent {
    private static final String ROOT = "/data/cobbleventure_player_menu/league/";

    private LeagueProgressionContent() {}

    static List<TrainerCardProgress.LeaguePage> pages() {
        try {
            JsonObject progression = read("league-progression.json");
            JsonObject badgeCatalog = read("badges.json");
            JsonObject gymCatalog = read("gyms.json");
            Map<String, Badge> badges = badges(badgeCatalog);
            Map<String, String> badgeByLeagueEntry = badgeByLeagueEntry(gymCatalog);
            Map<PageKey, List<Entry>> grouped = new LinkedHashMap<>();
            for (JsonElement element : progression.getAsJsonArray("entries")) {
                JsonObject value = element.getAsJsonObject();
                if (!optionalBoolean(value, "trainer_card_visible", true)) continue;
                String role = value.get("role").getAsString();
                String badgeId = "gym_leader".equals(role) ? badgeByLeagueEntry.get(value.get("id").getAsString()) : null;
                if ("gym_leader".equals(role) && badgeId == null) continue;
                Badge badge = badgeId == null ? null : badges.get(badgeId);
                int generation = value.get("generation").getAsInt();
                String region = value.get("region").getAsString();
                int order = value.has("trainer_card_order") ? value.get("trainer_card_order").getAsInt() : value.get("order").getAsInt();
                grouped.computeIfAbsent(new PageKey(generation, region), ignored -> new ArrayList<>()).add(new Entry(
                    order, localized(value.getAsJsonObject("display_name")), kind(role), badge
                ));
            }
            return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(page -> {
                List<TrainerCardProgress.Challenge> challenges = page.getValue().stream()
                    .sorted(Comparator.comparingInt(Entry::order)).map(entry -> challenge(entry)).toList();
                return new TrainerCardProgress.LeaguePage(
                    Component.literal(page.getKey().generation() + "세대 · " + readable(page.getKey().region())),
                    challenges,
                    challenges.stream().anyMatch(challenge -> challenge.kind() != TrainerCardProgress.ChallengeKind.GYM)
                        && challenges.stream().filter(challenge -> challenge.kind() != TrainerCardProgress.ChallengeKind.GYM).allMatch(TrainerCardProgress.Challenge::completed)
                );
            }).toList();
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
    }

    private static TrainerCardProgress.Challenge challenge(Entry entry) {
        Badge badge = entry.badge();
        String badgeId = badge == null ? "" : badge.id();
        boolean completed = !badgeId.isEmpty() && BadgeProgressNetwork.clientBadges().contains(badgeId);
        return new TrainerCardProgress.Challenge(
            Component.literal(entry.name()), completed, entry.kind(), badgeId,
            Component.literal(badge == null ? entry.name() : badge.name()),
            badge == null ? entry.name() : badge.tooltip(),
            badge == null ? null : badge.texture(), badge == null ? 0 : badge.u(), badge == null ? 0 : badge.v(),
            badge == null ? 32 : badge.size(), 256, 288
        );
    }

    private static Map<String, Badge> badges(JsonObject root) {
        Map<String, Badge> result = new HashMap<>();
        int width = root.getAsJsonObject("atlas").get("width").getAsInt();
        int height = root.getAsJsonObject("atlas").get("height").getAsInt();
        for (JsonElement element : root.getAsJsonArray("badges")) {
            JsonObject value = element.getAsJsonObject(); JsonObject icon = value.getAsJsonObject("icon");
            String id = value.get("id").getAsString();
            result.put(id, new Badge(id, localized(value.getAsJsonObject("display_name")), localized(value.getAsJsonObject("tooltip")),
                ResourceLocation.parse(icon.get("texture").getAsString()), icon.get("u").getAsInt(), icon.get("v").getAsInt(), icon.get("size").getAsInt(), width, height));
        }
        return result;
    }

    private static Map<String, String> badgeByLeagueEntry(JsonObject root) {
        Map<String, String> result = new HashMap<>();
        for (JsonElement element : root.getAsJsonArray("gyms")) {
            JsonObject leader = element.getAsJsonObject().getAsJsonObject("staff").getAsJsonObject("leader");
            if (leader.has("league_entry_id") && leader.has("badge_id")) result.put(leader.get("league_entry_id").getAsString(), leader.get("badge_id").getAsString());
        }
        return result;
    }

    private static JsonObject read(String name) throws IOException {
        try (InputStream stream = LeagueProgressionContent.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) throw new IOException("Missing trainer-card resource: " + name);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static boolean optionalBoolean(JsonObject value, String key, boolean fallback) { return value.has(key) ? value.get(key).getAsBoolean() : fallback; }
    private static TrainerCardProgress.ChallengeKind kind(String role) { return switch (role) { case "gym_leader" -> TrainerCardProgress.ChallengeKind.GYM; case "champion" -> TrainerCardProgress.ChallengeKind.CHAMPION; default -> TrainerCardProgress.ChallengeKind.LEAGUE; }; }
    private static String localized(JsonObject text) { if (text.has("ko_kr")) return text.get("ko_kr").getAsString(); if (text.has("en_us")) return text.get("en_us").getAsString(); return "—"; }
    private static String readable(String id) { String value = id.substring(Math.max(id.lastIndexOf(':'), id.lastIndexOf('/')) + 1); return value.replace('_', ' '); }

    private record PageKey(int generation, String region) implements Comparable<PageKey> { @Override public int compareTo(PageKey other) { int order = Integer.compare(generation, other.generation); return order != 0 ? order : region.compareTo(other.region); } }
    private record Entry(int order, String name, TrainerCardProgress.ChallengeKind kind, Badge badge) {}
    private record Badge(String id, String name, String tooltip, ResourceLocation texture, int u, int v, int size, int atlasWidth, int atlasHeight) {}
}
