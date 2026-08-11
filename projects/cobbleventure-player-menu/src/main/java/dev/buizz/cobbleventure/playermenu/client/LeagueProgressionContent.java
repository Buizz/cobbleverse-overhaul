package dev.buizz.cobbleventure.playermenu.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/** 관리 화면의 리그 카탈로그를 트레이너카드 페이지로 변환한다. */
final class LeagueProgressionContent {
    private static final String RESOURCE = "/data/cobbleventure_player_menu/league/league-progression.json";

    private LeagueProgressionContent() {
    }

    static List<TrainerCardProgress.LeaguePage> pages() {
        try (InputStream stream = LeagueProgressionContent.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) return List.of();
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<PageKey, List<Entry>> grouped = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("entries")) {
                JsonObject value = element.getAsJsonObject();
                if ("gym_leader".equals(value.get("role").getAsString())) {
                    JsonObject badge = value.getAsJsonObject("badge");
                    if (badge == null || !badge.get("trainer_card_visible").getAsBoolean()) continue;
                }
                int generation = value.get("generation").getAsInt();
                String region = value.get("region").getAsString();
                grouped.computeIfAbsent(new PageKey(generation, region), ignored -> new ArrayList<>()).add(new Entry(
                    value.get("order").getAsInt(), localized(value.getAsJsonObject("display_name")), kind(value.get("role").getAsString())
                ));
            }
            return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(page -> new TrainerCardProgress.LeaguePage(
                    Component.literal(page.getKey().generation() + "세대 · " + readable(page.getKey().region())),
                    page.getValue().stream().sorted(Comparator.comparingInt(Entry::order))
                        .map(entry -> new TrainerCardProgress.Challenge(Component.literal(entry.name()), false, entry.kind())).toList(),
                    false
                )).toList();
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
    }

    private static TrainerCardProgress.ChallengeKind kind(String role) {
        return switch (role) {
            case "gym_leader" -> TrainerCardProgress.ChallengeKind.GYM;
            case "champion" -> TrainerCardProgress.ChallengeKind.CHAMPION;
            default -> TrainerCardProgress.ChallengeKind.LEAGUE;
        };
    }

    private static String localized(JsonObject text) {
        if (text.has("ko_kr")) return text.get("ko_kr").getAsString();
        if (text.has("en_us")) return text.get("en_us").getAsString();
        return "—";
    }

    private static String readable(String id) {
        String value = id.substring(Math.max(id.lastIndexOf(':'), id.lastIndexOf('/')) + 1);
        return value.replace('_', ' ');
    }

    private record PageKey(int generation, String region) implements Comparable<PageKey> {
        @Override public int compareTo(PageKey other) {
            int generationOrder = Integer.compare(generation, other.generation);
            return generationOrder != 0 ? generationOrder : region.compareTo(other.region);
        }
    }

    private record Entry(int order, String name, TrainerCardProgress.ChallengeKind kind) {
    }
}
