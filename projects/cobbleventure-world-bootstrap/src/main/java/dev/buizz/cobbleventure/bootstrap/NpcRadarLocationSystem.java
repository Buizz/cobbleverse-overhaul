package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.event.ServerPlayerEventState;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/** Resolves loaded EasyNPC entities into player-specific radar locations. */
final class NpcRadarLocationSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BINDING_PREFIX = "cves_binding/";
    private static final String STARTER_RECEIVED_FLAG =
        "cobbleventure:flag/story/starter_received";
    private static final double RANGE = 64.0D;
    private static volatile CachedProfiles cachedProfiles;
    private static volatile boolean catalogFailureLogged;

    private NpcRadarLocationSystem() {}

    static List<RadarLocationCatalog.NpcLocation> locations(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Set<String> trainerSlugs = trainerSlugs(level.getServer().getResourceManager());
        ServerPlayerEventState state = new ServerPlayerEventState(player);
        List<RadarLocationCatalog.NpcLocation> result = new ArrayList<>();
        double rangeSquared = RANGE * RANGE;
        for (Entity entity : level.getEntitiesOfClass(
            Entity.class, player.getBoundingBox().inflate(RANGE),
            candidate -> isEasyNpc(candidate)
                && candidate.distanceToSqr(player) <= rangeSquared
        )) {
            String binding = binding(entity.getTags());
            RadarLocationCatalog.NpcKind kind = kind(binding, trainerSlugs);
            if (kind == null) continue;
            String slug = slug(binding);
            result.add(new RadarLocationCatalog.NpcLocation(
                "npc/" + entity.getUUID(), kind, level.dimension().location(),
                entity.getX(), entity.getY(), entity.getZ(), entity.getName().getString(),
                "", radarState(kind, slug, state)
            ));
        }
        result.sort(java.util.Comparator.comparing(RadarLocationCatalog.NpcLocation::id));
        return List.copyOf(result);
    }

    static List<RadarLocationCatalog.ObjectiveLocation> objectives(ServerPlayer player) {
        ServerPlayerEventState state = new ServerPlayerEventState(player);
        if (state.flag(STARTER_RECEIVED_FLAG)) return List.of();
        ServerLevel level = player.serverLevel();
        double rangeSquared = RANGE * RANGE;
        return level.getEntitiesOfClass(
            Entity.class, player.getBoundingBox().inflate(RANGE),
            candidate -> isEasyNpc(candidate)
                && candidate.distanceToSqr(player) <= rangeSquared
                && "professor_oak".equals(slug(binding(candidate.getTags())))
        ).stream().sorted(java.util.Comparator.comparing(Entity::getUUID))
            .findFirst()
            .map(entity -> List.of(new RadarLocationCatalog.ObjectiveLocation(
                "objective/story/professor_oak", "OBJECTIVE",
                level.dimension().location(), entity.getX(), entity.getY(), entity.getZ(),
                "오박사에게서 스타터 받기", "", "PRIMARY"
            )))
            .orElseGet(List::of);
    }

    static RadarLocationCatalog.NpcKind kind(String binding, Set<String> trainerSlugs) {
        if (binding == null) return null;
        String normalized = binding.toLowerCase(Locale.ROOT);
        if (normalized.contains("/gym_leaders/")) {
            return RadarLocationCatalog.NpcKind.GYM_LEADER;
        }
        if (trainerSlugs.contains(slug(normalized))) {
            return RadarLocationCatalog.NpcKind.TRAINER;
        }
        if (normalized.contains("/rewards/")) {
            return RadarLocationCatalog.NpcKind.IMPORTANT_NPC;
        }
        if (normalized.endsWith("/professor_oak")) {
            return RadarLocationCatalog.NpcKind.IMPORTANT_NPC;
        }
        return null;
    }

    static String trainerFlag(String slug) {
        return "cobbleventure:flag/trainer/" + slug + "/defeated";
    }

    static String gymFlag(String slug) {
        return "cobbleventure:flag/gym/kanto/" + slug + "/defeated";
    }

    static String rewardFlag(String slug) {
        if (slug.startsWith("field_move_") && slug.endsWith("_instructor")) {
            String move = slug.substring("field_move_".length(),
                slug.length() - "_instructor".length());
            return "cobbleventure:flag/rewards/field_move/" + move;
        }
        if (slug.startsWith("item_") && slug.endsWith("_supplier")) {
            String item = slug.substring("item_".length());
            return "cobbleventure:flag/rewards/item/" + item;
        }
        if (slug.equals("feature_map_guide")) {
            return "cobbleventure:flag/rewards/feature/map";
        }
        if (slug.equals("feature_pc_technician")) {
            return "cobbleventure:flag/rewards/feature/pc";
        }
        if (slug.equals("feature_teleport_guide")) {
            return "cobbleventure:flag/rewards/feature/settlement_teleport";
        }
        return null;
    }

    private static String radarState(
        RadarLocationCatalog.NpcKind kind, String slug, ServerPlayerEventState state
    ) {
        String flag = switch (kind) {
            case TRAINER -> trainerFlag(slug);
            case GYM_LEADER -> gymFlag(slug);
            case IMPORTANT_NPC -> rewardFlag(slug);
        };
        if (flag == null || !state.flag(flag)) return "AVAILABLE";
        return kind == RadarLocationCatalog.NpcKind.IMPORTANT_NPC
            ? "COMPLETED" : "DEFEATED";
    }

    private static boolean isEasyNpc(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
            .getNamespace().equals("easy_npc");
    }

    private static String binding(Set<String> tags) {
        return tags.stream()
            .filter(tag -> tag.startsWith(BINDING_PREFIX))
            .map(tag -> tag.substring(BINDING_PREFIX.length()))
            .sorted()
            .findFirst()
            .orElse(null);
    }

    private static String slug(String binding) {
        if (binding == null) return "unknown";
        int separator = Math.max(binding.lastIndexOf('/'), binding.lastIndexOf(':'));
        return binding.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> trainerSlugs(ResourceManager resources) {
        CachedProfiles cached = cachedProfiles;
        if (cached != null && cached.resources == resources) return cached.trainerSlugs;
        synchronized (NpcRadarLocationSystem.class) {
            cached = cachedProfiles;
            if (cached != null && cached.resources == resources) return cached.trainerSlugs;
            Set<String> loaded = loadTrainerSlugs(resources);
            cachedProfiles = new CachedProfiles(resources, loaded);
            return loaded;
        }
    }

    private static Set<String> loadTrainerSlugs(ResourceManager resources) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "catalogs/npc-placement-profiles.json"
        );
        try {
            Resource resource = resources.getResource(id).orElseThrow();
            try (Reader reader = resource.openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                Set<String> trainers = new HashSet<>();
                for (JsonElement element : root.getAsJsonArray("profiles")) {
                    JsonObject profile = element.getAsJsonObject();
                    if ("trainer".equals(profile.get("classification").getAsString())) {
                        trainers.add(slug(profile.get("npc").getAsString()));
                    }
                }
                return Set.copyOf(trainers);
            }
        } catch (IOException | RuntimeException error) {
            if (!catalogFailureLogged) {
                catalogFailureLogged = true;
                LOGGER.error("NPC radar could not load placement profiles", error);
            }
            return Set.of();
        }
    }

    private record CachedProfiles(ResourceManager resources, Set<String> trainerSlugs) {}
}
