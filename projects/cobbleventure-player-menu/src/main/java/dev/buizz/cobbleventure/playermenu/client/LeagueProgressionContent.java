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
            Map<String, LeaderCard> leaderByLeagueEntry = leaderByLeagueEntry(gymCatalog);
            Map<PageKey, List<Entry>> grouped = new LinkedHashMap<>();
            for (JsonElement element : progression.getAsJsonArray("entries")) {
                JsonObject value = element.getAsJsonObject();
                if (!optionalBoolean(value, "trainer_card_visible", true)) continue;
                String role = value.get("role").getAsString();
                LeaderCard leader = "gym_leader".equals(role) ? leaderByLeagueEntry.get(value.get("id").getAsString()) : null;
                String badgeId = leader == null ? null : leader.badgeId();
                if ("gym_leader".equals(role) && badgeId == null) continue;
                Badge badge = badgeId == null ? null : badges.get(badgeId);
                int generation = value.get("generation").getAsInt();
                String region = value.get("region").getAsString();
                grouped.computeIfAbsent(new PageKey(generation, region), ignored -> new ArrayList<>()).add(new Entry(
                    localized(value.getAsJsonObject("display_name")), kind(role), badge,
                    leader == null ? null : leader.skin(), leader != null && leader.slimModel(),
                    value.has("level_cap") ? value.get("level_cap").getAsInt() : 100
                ));
            }
            List<TrainerCardProgress.LeaguePage> pages = new ArrayList<>();
            for (Map.Entry<PageKey, List<Entry>> page : grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                List<Entry> entries = page.getValue();
                int sheetCount = Math.max(1, (entries.size() + 7) / 8);
                for (int offset = 0; offset < entries.size(); offset += 8) {
                    List<TrainerCardProgress.Challenge> challenges = entries.subList(offset, Math.min(offset + 8, entries.size())).stream()
                        .map(LeagueProgressionContent::challenge).toList();
                    String sheet = sheetCount > 1 ? " · " + (offset / 8 + 1) + "/" + sheetCount : "";
                    pages.add(new TrainerCardProgress.LeaguePage(
                        Component.literal(page.getKey().generation() + "세대 · " + readable(page.getKey().region()) + sheet),
                        challenges,
                        challenges.stream().anyMatch(challenge -> challenge.kind() != TrainerCardProgress.ChallengeKind.GYM)
                            && challenges.stream().filter(challenge -> challenge.kind() != TrainerCardProgress.ChallengeKind.GYM).allMatch(TrainerCardProgress.Challenge::completed)
                    ));
                }
            }
            return pages;
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
            badge == null ? 32 : badge.size(), 256, 288, entry.leaderSkin(), entry.slimModel(), entry.levelCap()
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

    private static Map<String, LeaderCard> leaderByLeagueEntry(JsonObject root) {
        Map<String, LeaderCard> result = new HashMap<>();
        for (JsonElement element : root.getAsJsonArray("gyms")) {
            JsonObject leader = element.getAsJsonObject().getAsJsonObject("staff").getAsJsonObject("leader");
            if (leader.has("league_entry_id") && leader.has("badge_id")) {
                LeaderAppearance appearance = leaderAppearance(leader.get("trainer_id").getAsString());
                result.put(leader.get("league_entry_id").getAsString(), new LeaderCard(
                    leader.get("badge_id").getAsString(), appearance.texture(), appearance.slimModel()
                ));
            }
        }
        return result;
    }

    private static LeaderAppearance leaderAppearance(String trainerId) {
        int separator = trainerId.lastIndexOf('/');
        if (separator < 0 || separator == trainerId.length() - 1) return new LeaderAppearance(null, false);
        try {
            JsonObject npc = read("npcs/" + trainerId.substring(separator + 1) + ".json");
            JsonObject appearance = npc.getAsJsonObject("npc").getAsJsonObject("appearance");
            ResourceLocation texture = appearance.has("texture") && !appearance.get("texture").getAsString().isBlank()
                ? ResourceLocation.parse(appearance.get("texture").getAsString()) : null;
            return new LeaderAppearance(texture, appearance.has("arm_model") && "slim".equals(appearance.get("arm_model").getAsString()));
        } catch (IOException | RuntimeException error) {
            return new LeaderAppearance(null, false);
        }
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
    private record Entry(
        String name, TrainerCardProgress.ChallengeKind kind, Badge badge,
        ResourceLocation leaderSkin, boolean slimModel, int levelCap
    ) {}
    private record LeaderCard(String badgeId, ResourceLocation skin, boolean slimModel) {}
    private record LeaderAppearance(ResourceLocation texture, boolean slimModel) {}
    private record Badge(String id, String name, String tooltip, ResourceLocation texture, int u, int v, int size, int atlasWidth, int atlasHeight) {}
}
