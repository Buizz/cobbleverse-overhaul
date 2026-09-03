package dev.buizz.cobbleventure.playermenu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Protects acquired story tools and restores them from their acquisition flags. */
public final class ImportantItemProtection {
    private static final String RESOURCE =
        "/data/cobbleventure_player_menu/progression/important-items.json";
    private static final List<Definition> DEFINITIONS = loadDefinitions();

    private ImportantItemProtection() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ImportantItemProtection::onItemToss);
        NeoForge.EVENT_BUS.addListener(ImportantItemProtection::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(ImportantItemProtection::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ImportantItemProtection::onServerTick);
    }

    public static boolean isProtected(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return DEFINITIONS.stream().anyMatch(definition -> definition.item().equals(itemId));
    }

    /** Acquisition is recorded by the reward grant, never by the boss-defeated flag. */
    public static String acquisitionFlag(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return DEFINITIONS.stream().filter(definition -> definition.item().equals(itemId))
            .map(Definition::acquisitionFlag).findFirst().orElse(null);
    }

    public static void notifyProtected(ServerPlayer player, ItemStack stack) {
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_player_menu.important_item.protected",
            stack.getHoverName()
        ), true);
    }

    private static void onItemToss(ItemTossEvent event) {
        ItemStack stack = event.getEntity().getItem();
        if (!(event.getPlayer() instanceof ServerPlayer player) || !isProtected(stack)) return;
        event.setCanceled(true);
        notifyProtected(player, stack);
    }

    private static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        event.getDrops().removeIf(entity -> isProtected(entity.getItem()));
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) restoreMissing(player);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            restoreMissing(player);
        }
    }

    static void restoreMissing(ServerPlayer player) {
        NonNullList<ItemStack> storage = BagStorage.load(player);
        boolean changed = false;
        for (Definition definition : DEFINITIONS) {
            if (!hasAcquired(player, definition.acquisitionFlag())) continue;
            int owned = count(player, storage, definition.item());
            int missing = definition.minimumCount() - owned;
            if (missing <= 0) continue;
            var item = BuiltInRegistries.ITEM.getOptional(definition.item());
            if (item.isEmpty()) continue;
            ItemStack restored = new ItemStack(item.orElseThrow(), missing);
            BagStorage.add(storage, restored);
            if (!restored.isEmpty()) continue;
            changed = true;
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_player_menu.important_item.restored",
                new ItemStack(item.orElseThrow()).getHoverName()
            ), false);
        }
        if (!changed) return;
        BagStorage.save(player, storage);
        BagNetwork.syncExternalMutation(player, storage);
    }

    private static int count(
        ServerPlayer player, List<ItemStack> storage, ResourceLocation itemId
    ) {
        int found = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) found += stack.getCount();
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) found += stack.getCount();
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (BuiltInRegistries.ITEM.getKey(carried.getItem()).equals(itemId)) found += carried.getCount();
        for (ItemStack stack : storage) {
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) found += stack.getCount();
        }
        return found;
    }

    private static boolean hasAcquired(ServerPlayer player, String flag) {
        Objective objective = player.getScoreboard().getObjective(flagObjective(flag));
        return objective != null
            && player.getScoreboard().getOrCreatePlayerScore(player, objective).get() != 0;
    }

    static String flagObjective(String resourceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                resourceId.getBytes(StandardCharsets.UTF_8)
            );
            return "cvf_" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1을 사용할 수 없습니다.", error);
        }
    }

    static List<Definition> loadDefinitions() {
        InputStream stream = ImportantItemProtection.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("중요 도구 카탈로그가 없습니다: " + RESOURCE);
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<Definition> definitions = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("items")) {
                JsonObject value = element.getAsJsonObject();
                ResourceLocation item = ResourceLocation.tryParse(value.get("item").getAsString());
                String flag = value.get("acquisition_flag").getAsString();
                int minimumCount = value.get("minimum_count").getAsInt();
                if (item == null || ResourceLocation.tryParse(flag) == null || minimumCount < 1) {
                    throw new IllegalStateException("올바르지 않은 중요 도구 정의입니다: " + value);
                }
                definitions.add(new Definition(item, flag, minimumCount));
            }
            if (definitions.isEmpty()) throw new IllegalStateException("중요 도구 정의가 비어 있습니다.");
            return List.copyOf(definitions);
        } catch (RuntimeException | java.io.IOException error) {
            throw new IllegalStateException("중요 도구 카탈로그를 읽을 수 없습니다.", error);
        }
    }

    record Definition(ResourceLocation item, String acquisitionFlag, int minimumCount) {}
}
