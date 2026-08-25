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
                if (document == null || (document.schema_version < 4 || document.schema_version > 6) || document.machines == null
                    || document.tickets == null || document.casino_sets == null) {
                    throw new JsonParseException("schema_version=4~6과 tickets, casino_sets, machines가 필요합니다.");
                }
                Map<String, RewardTemplate> rewards = new LinkedHashMap<>();
                for (RewardTemplate reward : document.reward_catalog == null ? List.<RewardTemplate>of() : document.reward_catalog) {
                    if (reward == null || reward.id == null || reward.id.isBlank()) continue;
                    rewards.put(reward.id, reward);
                }
                for (Machine machine : document.machines) {
                    if (machine == null || machine.id == null || machine.id.isBlank() || !machine.enabled) continue;
                    machine.machine_type = machine.machine_type == null || machine.machine_type.isBlank()
                        ? "item" : machine.machine_type;
                    Ticket ticket = document.tickets.get(machine.machine_type);
                    if (ticket == null) throw new JsonParseException("티켓 종류가 없습니다: " + machine.machine_type);
                    normalize(machine, ticket, rewards, document.schema_version);
                    loaded.put(machine.id, machine);
                }
            }
        } catch (IOException | JsonParseException | IllegalArgumentException error) {
            logger.error("가챠 기계 카탈로그를 불러오지 못했습니다: {}", RESOURCE, error);
        }
        logger.info("가챠 기계 프로필 {}개를 불러왔습니다.", loaded.size());
        return new GachaCatalog(loaded);
    }

    private static void normalize(
        Machine machine, Ticket ticket, Map<String, RewardTemplate> catalog, int schemaVersion
    ) {
        machine.display_name = machine.display_name == null || machine.display_name.isBlank() ? machine.id : machine.display_name;
        machine.ticket = ticket;
        machine.ticket.display_name = machine.ticket.display_name == null || machine.ticket.display_name.isBlank()
            ? machine.display_name + " 티켓" : machine.ticket.display_name;
        machine.ticket.price = Math.max(1L, machine.ticket.price);
        machine.ticket.purchase_min = Math.max(1, machine.ticket.purchase_min);
        machine.ticket.purchase_max = Math.max(machine.ticket.purchase_min, machine.ticket.purchase_max);
        if (machine.themes == null || machine.themes.isEmpty()) {
            Theme legacy = new Theme();
            legacy.id = "default";
            legacy.display_name = machine.display_name;
            legacy.ticket_cost = 1;
            legacy.pity_group = machine.pity_group;
            legacy.rarities = machine.rarities;
            legacy.pity = machine.pity;
            machine.themes = new ArrayList<>(List.of(legacy));
        }
        for (Theme theme : machine.themes) normalizeTheme(machine, theme, catalog, schemaVersion);
    }

    private static void normalizeTheme(
        Machine machine, Theme theme, Map<String, RewardTemplate> catalog, int schemaVersion
    ) {
        theme.id = theme.id == null || theme.id.isBlank() ? "default" : theme.id;
        theme.display_name = theme.display_name == null || theme.display_name.isBlank()
            ? machine.display_name : theme.display_name;
        theme.ticket_cost = Math.max(1, theme.ticket_cost);
        theme.pity_group = theme.pity_group == null || theme.pity_group.isBlank()
            ? machine.id + "/" + theme.id : theme.pity_group;
        theme.rarities = theme.rarities == null ? new ArrayList<>() : theme.rarities;
        for (Rarity rarity : theme.rarities) {
            rarity.rewards = rarity.rewards == null ? new ArrayList<>() : rarity.rewards;
            for (Reward reward : rarity.rewards) {
                if (schemaVersion <= 5) {
                    RewardTemplate template = catalog.get(reward.catalog_id);
                    if (template == null) {
                        throw new JsonParseException("가챠 상품 카탈로그 참조가 없습니다: " + reward.catalog_id);
                    }
                    if (!machine.machine_type.equals(template.machine_type)) {
                        throw new JsonParseException("기계 종류와 상품 종류가 다릅니다: " + reward.catalog_id);
                    }
                    reward.id = template.id;
                    reward.display_name = template.display_name;
                    reward.kind = template.kind;
                    reward.value = template.value;
                    reward.count = Math.max(1, template.count);
                }
                if (reward.id == null || reward.id.isBlank() || reward.value == null || reward.value.isBlank()) {
                    throw new JsonParseException("가챠 직접 보상 ID와 값이 필요합니다.");
                }
                reward.kind = "pokemon".equals(machine.machine_type) ? "pokemon" : "item";
                reward.count = "pokemon".equals(reward.kind) ? 1 : Math.max(1, reward.count);
                reward.display_name = reward.display_name == null || reward.display_name.isBlank()
                    ? reward.id : reward.display_name;
            }
        }
    }

    Optional<Machine> machine(String id) {
        return Optional.ofNullable(machines.get(id));
    }

    List<String> ids() {
        return List.copyOf(machines.keySet());
    }

    List<Machine> machines() {
        return List.copyOf(machines.values());
    }

    static final class Document {
        int schema_version; Map<String, Ticket> tickets; List<RewardTemplate> reward_catalog;
        List<CasinoSet> casino_sets; List<Machine> machines;
    }
    static final class CasinoSet { String id; String display_name; Map<String, String> machines; }
    static final class Machine {
        String id; String display_name; String machine_type; boolean enabled; String pity_group;
        Appearance appearance; Ticket ticket; List<Theme> themes;
        List<Rarity> rarities; Pity pity;
        Theme theme(String id) { return themes.stream().filter(entry -> entry.id.equals(id)).findFirst().orElse(null); }
        Theme defaultTheme() { return themes.isEmpty() ? null : themes.getFirst(); }
        Theme themeForReward(String id) {
            return themes.stream().filter(entry -> entry.reward(id) != null).findFirst().orElse(null);
        }
        Reward reward(String id) {
            Theme theme = themeForReward(id);
            return theme == null ? null : theme.reward(id);
        }
    }
    static final class Theme {
        String id; String display_name; int ticket_cost; String pity_group;
        List<Rarity> rarities; Pity pity;
        Rarity rarity(String id) { return rarities.stream().filter(entry -> entry.id.equals(id)).findFirst().orElse(null); }
        Reward reward(String id) { return rarities.stream().flatMap(entry -> entry.rewards.stream()).filter(entry -> entry.id.equals(id)).findFirst().orElse(null); }
    }
    static final class Appearance {
        String model_block; String facing; boolean show_nameplate;
    }
    static final class Ticket { String display_name; long price; int purchase_min; int purchase_max; }
    static final class Rarity { String id; String display_name; double weight; List<Reward> rewards; }
    static final class RewardTemplate {
        String id; String display_name; String machine_type; String kind; String value; int count;
    }
    static final class Reward {
        String catalog_id; String id; String display_name; String kind; String value; int count;
        double weight; boolean selectable;
    }
    static final class Pity { Soft soft; Hard hard; Selection selection; }
    static final class Soft { boolean enabled; int start; int max_at; String target_rarity; double max_chance; }
    static final class Hard { boolean enabled; int count; String target_rarity; }
    static final class Selection { boolean enabled; int points_per_pull; int required_points; }
}
