package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Applies Flash vision rules inside cave regions whose content definition requires it. */
final class FlashCaveEffects {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONTENT_NAMESPACE = "cobbleventure";
    private static final String APPLIED_EFFECT = "cobbleventureFlashAppliedEffect";
    private static final String BLINDNESS = "blindness";
    private static final String NIGHT_VISION = "night_vision";
    private static volatile List<FlashRegion> flashRegions = List.of();
    private static boolean registered;

    private FlashCaveEffects() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(FlashCaveEffects::onServerStarted);
        NeoForge.EVENT_BUS.addListener(FlashCaveEffects::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(FlashCaveEffects::onServerTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        reload(event.getServer());
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            reload(event.getPlayerList().getServer());
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            applyVisionRule(player);
        }
    }

    private static void applyVisionRule(ServerPlayer player) {
        boolean requiresFlash = flashRegions.stream().anyMatch(region -> region.contains(player));
        String desiredEffect = requiresFlash
            ? FieldMoveRidingAccess.isActive(player, "flash") ? NIGHT_VISION : BLINDNESS
            : "";
        String appliedEffect = player.getPersistentData().getString(APPLIED_EFFECT);

        if (!appliedEffect.equals(desiredEffect)) {
            removeAppliedEffect(player, appliedEffect);
            if (desiredEffect.isEmpty()) {
                player.getPersistentData().remove(APPLIED_EFFECT);
                return;
            }
            player.getPersistentData().putString(APPLIED_EFFECT, desiredEffect);
        }

        if (BLINDNESS.equals(desiredEffect)) {
            ensureInfiniteEffect(player, MobEffects.BLINDNESS);
        } else if (NIGHT_VISION.equals(desiredEffect)) {
            ensureInfiniteEffect(player, MobEffects.NIGHT_VISION);
        }
    }

    private static void ensureInfiniteEffect(
        ServerPlayer player,
        net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect
    ) {
        MobEffectInstance current = player.getEffect(effect);
        if (current == null || !current.isInfiniteDuration()) {
            player.addEffect(new MobEffectInstance(
                effect, MobEffectInstance.INFINITE_DURATION, 0, true, false, true
            ));
        }
    }

    private static void removeAppliedEffect(ServerPlayer player, String appliedEffect) {
        if (BLINDNESS.equals(appliedEffect)) {
            player.removeEffect(MobEffects.BLINDNESS);
        } else if (NIGHT_VISION.equals(appliedEffect)) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
    }

    private static void reload(MinecraftServer server) {
        flashRegions = loadFlashRegions(server.getResourceManager());
        LOGGER.info("Loaded {} Flash-required cave regions", flashRegions.size());
    }

    private static List<FlashRegion> loadFlashRegions(ResourceManager resources) {
        List<FlashRegion> regions = new ArrayList<>();
        resources.listResources(
            "caves",
            location -> location.getNamespace().equals(CONTENT_NAMESPACE)
                && location.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try (Reader reader = resource.openAsReader()) {
                JsonObject cave = JsonParser.parseReader(reader).getAsJsonObject();
                if (!cave.get("enabled").getAsBoolean()
                    || !cave.get("requires_flash").getAsBoolean()) {
                    return;
                }
                JsonObject dimension = cave.getAsJsonObject("dimension");
                ResourceLocation dimensionId = ResourceLocation.tryParse(
                    dimension.get("id").getAsString()
                );
                if (dimensionId == null) {
                    throw new IllegalArgumentException("Invalid cave dimension ID");
                }
                JsonObject bounds = dimension.getAsJsonObject("bounds");
                regions.add(new FlashRegion(
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId),
                    bounds.get("min_x").getAsInt(),
                    bounds.get("min_z").getAsInt(),
                    bounds.get("max_x").getAsInt(),
                    bounds.get("max_z").getAsInt()
                ));
            } catch (IOException | RuntimeException error) {
                LOGGER.error("Failed to load Flash cave definition {}", location, error);
            }
        });
        return List.copyOf(regions);
    }

    private record FlashRegion(
        ResourceKey<Level> dimension,
        int minX,
        int minZ,
        int maxX,
        int maxZ
    ) {
        boolean contains(ServerPlayer player) {
            return player.level().dimension().equals(dimension)
                && player.getX() >= minX && player.getX() <= maxX
                && player.getZ() >= minZ && player.getZ() <= maxZ;
        }
    }
}
