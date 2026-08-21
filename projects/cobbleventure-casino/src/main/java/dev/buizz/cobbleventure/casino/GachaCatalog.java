package dev.buizz.cobbleventure.casino;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

final class GachaCatalog {
    private static final ResourceLocation RESOURCE = ResourceLocation.fromNamespaceAndPath(
        CobbleventureCasino.MOD_ID, "gacha/machines.json"
    );
    private final Map<String, Machine> machines;

    private GachaCatalog(Map<String, Machine> machines) {
        this.machines = Map.copyOf(machines);
    }

    static GachaCatalog empty() {
        return new GachaCatalog(Map.of());
    }

    static GachaCatalog load(MinecraftServer server, Logger logger) {
        Map<String, Machine> loaded = new LinkedHashMap<>();
        try {
            var resource = server.getResourceManager().getResourceOrThrow(RESOURCE);
            try (Reader reader = resource.openAsReader()) {
                Document document = new Gson().fromJson(reader, Document.class);
                if (document == null || document.schema_version != 1 || document.machines == null) {
                    throw new JsonParseException("schema_version=1 machines 배열이 필요합니다.");
                }
                for (Machine machine : document.machines) {
                    if (machine == null || machine.id == null || machine.id.isBlank() || !machine.enabled) continue;
                    normalize(machine);
                    loaded.put(machine.id, machine);
                }
            }
        } catch (IOException | JsonParseException | IllegalArgumentException error) {
            logger.error("가챠 기계 카탈로그를 불러오지 못했습니다: {}", RESOURCE, error);
        }
        logger.info("가챠 기계 프로필 {}개를 불러왔습니다.", loaded.size());
        return new GachaCatalog(loaded);
    }

    private static void normalize(Machine machine) {
        machine.display_name = machine.display_name == null || machine.display_name.isBlank() ? machine.id : machine.display_name;
        machine.pity_group = machine.pity_group == null || machine.pity_group.isBlank() ? machine.id : machine.pity_group;
        machine.rarities = machine.rarities == null ? new ArrayList<>() : machine.rarities;
        for (Rarity rarity : machine.rarities) rarity.rewards = rarity.rewards == null ? new ArrayList<>() : rarity.rewards;
    }

    Optional<Machine> machine(String id) {
        return Optional.ofNullable(machines.get(id));
    }

    List<String> ids() {
        return List.copyOf(machines.keySet());
    }

    static final class Document { int schema_version; List<Machine> machines; }
    static final class Machine {
        String id; String display_name; boolean enabled; String pity_group;
        Appearance appearance; Currency currency; List<Rarity> rarities; Pity pity;
        Rarity rarity(String id) { return rarities.stream().filter(entry -> entry.id.equals(id)).findFirst().orElse(null); }
        Reward reward(String id) { return rarities.stream().flatMap(entry -> entry.rewards.stream()).filter(entry -> entry.id.equals(id)).findFirst().orElse(null); }
    }
    static final class Appearance {
        String base_block; String accent_block; float scale; float accent_scale; float accent_height; float rotation_degrees; boolean show_nameplate;
    }
    static final class Currency { String item; int count; }
    static final class Rarity { String id; String display_name; double weight; List<Reward> rewards; }
    static final class Reward { String id; String kind; String value; int count; double weight; boolean selectable; }
    static final class Pity { Soft soft; Hard hard; Selection selection; }
    static final class Soft { boolean enabled; int start; int max_at; String target_rarity; double max_chance; }
    static final class Hard { boolean enabled; int count; String target_rarity; }
    static final class Selection { boolean enabled; int points_per_pull; int required_points; }
}
