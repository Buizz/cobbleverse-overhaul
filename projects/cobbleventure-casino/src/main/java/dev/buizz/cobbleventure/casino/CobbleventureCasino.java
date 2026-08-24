package dev.buizz.cobbleventure.casino;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.math.Transformation;
import dev.buizz.cobbleventure.casino.client.CasinoBalanceOverlay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CobbleventureCasino.MOD_ID)
public final class CobbleventureCasino {
    public static final String MOD_ID = "cobbleventure_casino";
    private static final Logger LOGGER = LoggerFactory.getLogger(CobbleventureCasino.class);
    private static final String DATA_FILE = "cobbleventure_gacha_players";
    private static final String MACHINE_TAG = "cobbleventure_gacha_machine";
    private static final String PROFILE_KEY = "cobbleventureGachaProfile";
    private static final String ANCHOR_KEY = "cobbleventureGachaAnchor";
    private static volatile GachaCatalog catalog = GachaCatalog.empty();

    public CobbleventureCasino(IEventBus modBus) {
        CasinoItems.register(modBus);
        CasinoCashier.register();
        CasinoHudNetwork.register(modBus);
        if (FMLEnvironment.dist.isClient()) {
            CasinoBalanceOverlay.register(modBus);
        }
        NeoForge.EVENT_BUS.addListener(CobbleventureCasino::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CobbleventureCasino::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(CobbleventureCasino::onEntityInteract);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        catalog = GachaCatalog.load(event.getServer(), LOGGER);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cvgacha")
            .then(Commands.literal("status")
                .then(Commands.argument("profile", StringArgumentType.string())
                    .suggests((context, builder) -> suggestProfiles(builder))
                    .executes(CobbleventureCasino::status)))
            .then(Commands.literal("select")
                .then(Commands.argument("profile", StringArgumentType.string())
                    .suggests((context, builder) -> suggestProfiles(builder))
                    .then(Commands.argument("reward", StringArgumentType.string())
                        .suggests((context, builder) -> suggestRewards(context, builder))
                        .executes(CobbleventureCasino::selectReward))))
            .then(Commands.literal("place").requires(source -> source.hasPermission(2))
                .then(Commands.argument("profile", StringArgumentType.string())
                    .suggests((context, builder) -> suggestProfiles(builder))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(CobbleventureCasino::placeCommand))))
            .then(Commands.literal("remove").requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(CobbleventureCasino::removeCommand)))
            .then(Commands.literal("ticket")
                .then(Commands.literal("give").requires(source -> source.hasPermission(2))
                    .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("profile", StringArgumentType.string())
                            .suggests((context, builder) -> suggestProfiles(builder))
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                .executes(CobbleventureCasino::giveTickets)))))
                .then(Commands.literal("buy")
                    .then(Commands.argument("profile", StringArgumentType.string())
                        .suggests((context, builder) -> suggestProfiles(builder))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                            .executes(CobbleventureCasino::buyTickets)))))
            .then(Commands.literal("reload").requires(source -> source.hasPermission(2))
                .executes(CobbleventureCasino::reloadCommand)));
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestProfiles(SuggestionsBuilder builder) {
        catalog.ids().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRewards(
        CommandContext<CommandSourceStack> context, SuggestionsBuilder builder
    ) {
        String profile = StringArgumentType.getString(context, "profile");
        catalog.machine(profile).ifPresent(machine -> machine.rarities.stream()
            .flatMap(rarity -> rarity.rewards.stream()).filter(reward -> reward.selectable)
            .forEach(reward -> builder.suggest(reward.id)));
        return builder.buildFuture();
    }

    private static int reloadCommand(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        catalog = GachaCatalog.load(server, LOGGER);
        int refreshed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Interaction> interactions = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) if (entity instanceof Interaction interaction && entity.getTags().contains(MACHINE_TAG)) interactions.add(interaction);
            for (Interaction interaction : interactions) {
                String profile = interaction.getPersistentData().getString(PROFILE_KEY);
                BlockPos pos = BlockPos.of(interaction.getPersistentData().getLong(ANCHOR_KEY));
                removeMachine(level, pos);
                if (placeMachine(level, pos, profile)) refreshed++;
            }
        }
        int count = refreshed;
        context.getSource().sendSuccess(() -> Component.literal("[가챠] 프로필을 다시 읽고 기계 " + count + "대를 갱신했습니다."), true);
        return refreshed;
    }

    private static int placeCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String profile = StringArgumentType.getString(context, "profile");
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        if (!catalog.machine(profile).isPresent()) {
            context.getSource().sendFailure(Component.literal("존재하지 않거나 비활성화된 기계 프로필입니다: " + profile));
            return 0;
        }
        removeMachine(context.getSource().getLevel(), pos);
        if (!placeMachine(context.getSource().getLevel(), pos, profile)) return 0;
        context.getSource().sendSuccess(() -> Component.literal("[가챠] " + profile + " 기계를 배치했습니다."), true);
        return 1;
    }

    private static int removeCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        int removed = removeMachine(context.getSource().getLevel(), pos);
        context.getSource().sendSuccess(() -> Component.literal("[가챠] 기계 구성 요소 " + removed + "개를 제거했습니다."), true);
        return removed;
    }

    private static boolean placeMachine(ServerLevel level, BlockPos pos, String profileId) {
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        if (machine == null) return false;
        long anchor = pos.asLong();
        Block base = block(machine.appearance.base_block, Blocks.IRON_BLOCK);
        Block accent = block(machine.appearance.accent_block, Blocks.GLASS);
        Display.BlockDisplay baseDisplay = EntityType.BLOCK_DISPLAY.create(level);
        Display.BlockDisplay accentDisplay = EntityType.BLOCK_DISPLAY.create(level);
        Interaction interaction = EntityType.INTERACTION.create(level);
        if (baseDisplay == null || accentDisplay == null || interaction == null) return false;
        configureDisplay(baseDisplay, pos, base, machine.appearance.scale, 0.0F, machine.appearance.rotation_degrees, anchor, profileId);
        configureDisplay(accentDisplay, pos, accent, machine.appearance.accent_scale, machine.appearance.accent_height, machine.appearance.rotation_degrees, anchor, profileId);
        interaction.setPos(pos.getX() + .5D, pos.getY(), pos.getZ() + .5D);
        CompoundTag interactionData = new CompoundTag();
        interaction.saveWithoutId(interactionData);
        interactionData.putFloat("width", Math.max(.5F, machine.appearance.scale));
        interactionData.putFloat("height", Math.max(1.2F, machine.appearance.scale + machine.appearance.accent_height));
        interactionData.putBoolean("response", true);
        interaction.load(interactionData);
        interaction.setInvulnerable(true);
        interaction.setCustomName(Component.literal(machine.display_name));
        interaction.setCustomNameVisible(machine.appearance.show_nameplate);
        markMachineEntity(interaction, anchor, profileId);
        return level.addFreshEntity(baseDisplay) && level.addFreshEntity(accentDisplay) && level.addFreshEntity(interaction);
    }

    /** Places a configured machine for authored building anchors. */
    public static boolean placeConfiguredMachine(ServerLevel level, BlockPos pos, String profileId) {
        removeMachine(level, pos);
        return placeMachine(level, pos, profileId);
    }

    private static void configureDisplay(
        Display.BlockDisplay display, BlockPos pos, Block block, float scale, float height,
        float rotation, long anchor, String profile
    ) {
        display.setPos(pos.getX() + .5D, pos.getY(), pos.getZ() + .5D);
        Quaternionf turn = new Quaternionf().rotateY((float)Math.toRadians(rotation));
        Transformation transformation = new Transformation(
            new Vector3f(-scale / 2.0F, height, -scale / 2.0F), turn,
            new Vector3f(scale, scale, scale), new Quaternionf()
        );
        CompoundTag displayData = new CompoundTag();
        display.saveWithoutId(displayData);
        displayData.put("block_state", NbtUtils.writeBlockState(block.defaultBlockState()));
        Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, transformation)
            .ifSuccess(encoded -> displayData.put("transformation", encoded));
        display.load(displayData);
        display.setInvulnerable(true);
        display.setNoGravity(true);
        markMachineEntity(display, anchor, profile);
    }

    private static void markMachineEntity(Entity entity, long anchor, String profile) {
        entity.addTag(MACHINE_TAG);
        entity.getPersistentData().putLong(ANCHOR_KEY, anchor);
        entity.getPersistentData().putString(PROFILE_KEY, profile);
    }

    private static Block block(String id, Block fallback) {
        try { return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(fallback); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int removeMachine(ServerLevel level, BlockPos pos) {
        int removed = 0;
        for (Entity entity : level.getEntities((Entity)null, new AABB(pos).inflate(2.0D), entity -> entity.getTags().contains(MACHINE_TAG)
            && entity.getPersistentData().getLong(ANCHOR_KEY) == pos.asLong())) {
            entity.discard();
            removed++;
        }
        return removed;
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getTarget() instanceof Interaction interaction) || !interaction.getTags().contains(MACHINE_TAG)) return;
        String profile = interaction.getPersistentData().getString(PROFILE_KEY);
        pull(player, profile);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void pull(ServerPlayer player, String profileId) {
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        if (machine == null) { player.sendSystemMessage(Component.literal("이 기계의 프로필을 찾을 수 없습니다.")); return; }
        if (!GachaTickets.take(player, machine, 1)) {
            player.sendSystemMessage(Component.literal(machine.display_name + ": " + machine.ticket.display_name + "이(가) 필요합니다."));
            return;
        }
        Progress progress = playerData(player.server).progress(player.getUUID(), machine.pity_group);
        GachaCatalog.Rarity rarity = chooseRarity(player.getRandom(), machine, progress.pullsSinceTarget);
        GachaCatalog.Reward reward = weighted(player.getRandom(), rarity.rewards, entry -> entry.weight);
        if (reward == null || !grant(player, reward)) {
            GachaTickets.give(player, machine, 1);
            player.sendSystemMessage(Component.literal("보상 지급에 실패해 티켓을 돌려드렸습니다."));
            return;
        }
        String resetTarget = machine.pity.hard.enabled ? machine.pity.hard.target_rarity : machine.pity.soft.target_rarity;
        progress.pullsSinceTarget = rarity.id.equals(resetTarget) ? 0 : progress.pullsSinceTarget + 1;
        if (machine.pity.selection.enabled) progress.selectionPoints += machine.pity.selection.points_per_pull;
        playerData(player.server).setDirty();
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, .8F, 1.1F);
        player.sendSystemMessage(Component.literal("[" + machine.display_name + "] " + rarity.display_name + " · " + reward.id + " 당첨!"));
        sendPityStatus(player, machine, progress);
    }

    private static int giveTickets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String profileId = StringArgumentType.getString(context, "profile");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        if (machine == null) {
            context.getSource().sendFailure(Component.literal("존재하지 않거나 비활성화된 기계 프로필입니다: " + profileId));
            return 0;
        }
        var players = EntityArgument.getPlayers(context, "players");
        players.forEach(player -> GachaTickets.give(player, machine, amount));
        context.getSource().sendSuccess(() -> Component.literal(
            "[가챠] " + machine.ticket.display_name + " ×" + amount + "을(를) " + players.size() + "명에게 지급했습니다."), true);
        return players.size();
    }

    private static int buyTickets(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try { player = context.getSource().getPlayerOrException(); }
        catch (Exception error) { context.getSource().sendFailure(Component.literal("플레이어만 사용할 수 있습니다.")); return 0; }
        String profileId = StringArgumentType.getString(context, "profile");
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        if (machine == null) { context.getSource().sendFailure(Component.literal("기계 프로필을 찾을 수 없습니다.")); return 0; }
        return GachaTicketVendor.buy(player, machine, IntegerArgumentType.getInteger(context, "amount"));
    }

    private static GachaCatalog.Rarity chooseRarity(RandomSource random, GachaCatalog.Machine machine, int misses) {
        int nextPull = misses + 1;
        if (machine.pity.hard.enabled && nextPull >= machine.pity.hard.count) {
            GachaCatalog.Rarity forced = machine.rarity(machine.pity.hard.target_rarity);
            if (forced != null) return forced;
        }
        Map<GachaCatalog.Rarity, Double> weights = new LinkedHashMap<>();
        for (GachaCatalog.Rarity rarity : machine.rarities) weights.put(rarity, Math.max(0.0D, rarity.weight));
        if (machine.pity.soft.enabled && nextPull >= machine.pity.soft.start) {
            GachaCatalog.Rarity target = machine.rarity(machine.pity.soft.target_rarity);
            if (target != null) {
                double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
                double baseChance = total <= 0 ? 0 : weights.get(target) / total;
                double span = Math.max(1, machine.pity.soft.max_at - machine.pity.soft.start);
                double ratio = Math.min(1.0D, (nextPull - machine.pity.soft.start) / span);
                double desired = baseChance + (machine.pity.soft.max_chance - baseChance) * ratio;
                double others = total - weights.get(target);
                if (others > 0 && desired > 0 && desired < 1) weights.put(target, desired * others / (1.0D - desired));
            }
        }
        return weighted(random, new ArrayList<>(weights.keySet()), weights::get);
    }

    private interface Weight<T> { double get(T value); }
    private static <T> T weighted(RandomSource random, List<T> values, Weight<T> weight) {
        double total = values.stream().mapToDouble(value -> Math.max(0.0D, weight.get(value))).sum();
        if (total <= 0) return null;
        double roll = random.nextDouble() * total;
        for (T value : values) { roll -= Math.max(0.0D, weight.get(value)); if (roll <= 0) return value; }
        return values.getLast();
    }

    private static Item item(String id) {
        try {
            Item found = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(null);
            return found == net.minecraft.world.item.Items.AIR ? null : found;
        } catch (RuntimeException ignored) { return null; }
    }

    private static boolean take(ServerPlayer player, Item item, int count) {
        int found = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (player.getInventory().getItem(slot).is(item)) found += player.getInventory().getItem(slot).getCount();
        if (found < count) return false;
        int remaining = count;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            int removed = Math.min(remaining, stack.getCount()); stack.shrink(removed); remaining -= removed;
        }
        player.getInventory().setChanged();
        return true;
    }

    private static boolean grant(ServerPlayer player, GachaCatalog.Reward reward) {
        try {
            if ("pokemon".equals(reward.kind)) {
                for (int index = 0; index < reward.count; index++) {
                    var pokemon = PokemonProperties.Companion.parse(reward.value).create(player);
                    if (!Cobblemon.INSTANCE.getStorage().getParty(player).add(pokemon)) return false;
                }
                return true;
            }
            Item rewardItem = item(reward.value);
            if (rewardItem == null) return false;
            ItemStack stack = new ItemStack(rewardItem, reward.count);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            return true;
        } catch (RuntimeException error) {
            LOGGER.error("가챠 보상 지급 실패: {}", reward.id, error);
            return false;
        }
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try { player = context.getSource().getPlayerOrException(); } catch (Exception error) { context.getSource().sendFailure(Component.literal("플레이어만 사용할 수 있습니다.")); return 0; }
        String profile = StringArgumentType.getString(context, "profile");
        GachaCatalog.Machine machine = catalog.machine(profile).orElse(null);
        if (machine == null) { context.getSource().sendFailure(Component.literal("기계 프로필을 찾을 수 없습니다.")); return 0; }
        sendPityStatus(player, machine, playerData(player.server).progress(player.getUUID(), machine.pity_group));
        return 1;
    }

    private static void sendPityStatus(ServerPlayer player, GachaCatalog.Machine machine, Progress progress) {
        String hard = machine.pity.hard.enabled ? "확정 " + progress.pullsSinceTarget + "/" + machine.pity.hard.count : "확정 꺼짐";
        String selection = machine.pity.selection.enabled ? "선택 " + progress.selectionPoints + "/" + machine.pity.selection.required_points : "선택 꺼짐";
        player.sendSystemMessage(Component.literal("[천장] " + hard + " · " + selection));
    }

    private static int selectReward(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try { player = context.getSource().getPlayerOrException(); } catch (Exception error) { context.getSource().sendFailure(Component.literal("플레이어만 사용할 수 있습니다.")); return 0; }
        String profileId = StringArgumentType.getString(context, "profile");
        String rewardId = StringArgumentType.getString(context, "reward");
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        GachaCatalog.Reward reward = machine == null ? null : machine.reward(rewardId);
        if (machine == null || !machine.pity.selection.enabled || reward == null || !reward.selectable) { context.getSource().sendFailure(Component.literal("선택할 수 없는 보상입니다.")); return 0; }
        PlayerData data = playerData(player.server);
        Progress progress = data.progress(player.getUUID(), machine.pity_group);
        if (progress.selectionPoints < machine.pity.selection.required_points) { context.getSource().sendFailure(Component.literal("선택 천장 포인트가 부족합니다: " + progress.selectionPoints + "/" + machine.pity.selection.required_points)); return 0; }
        if (!grant(player, reward)) { context.getSource().sendFailure(Component.literal("보상 지급에 실패했습니다.")); return 0; }
        progress.selectionPoints -= machine.pity.selection.required_points;
        data.setDirty();
        context.getSource().sendSuccess(() -> Component.literal("[선택 천장] " + reward.id + " 보상을 받았습니다."), false);
        return 1;
    }

    private static PlayerData playerData(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlayerData::new, PlayerData::load), DATA_FILE);
    }

    private static final class Progress { int pullsSinceTarget; int selectionPoints; }
    private static final class PlayerData extends SavedData {
        private final Map<UUID, Map<String, Progress>> values = new LinkedHashMap<>();
        static PlayerData load(CompoundTag tag, HolderLookup.Provider registries) {
            PlayerData data = new PlayerData();
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int index = 0; index < players.size(); index++) {
                CompoundTag playerTag = players.getCompound(index); UUID player = playerTag.getUUID("player");
                Map<String, Progress> groups = new LinkedHashMap<>(); ListTag entries = playerTag.getList("groups", Tag.TAG_COMPOUND);
                for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) { CompoundTag entry = entries.getCompound(entryIndex); Progress progress = new Progress(); progress.pullsSinceTarget = entry.getInt("pulls"); progress.selectionPoints = entry.getInt("points"); groups.put(entry.getString("id"), progress); }
                data.values.put(player, groups);
            }
            return data;
        }
        Progress progress(UUID player, String group) { return values.computeIfAbsent(player, ignored -> new LinkedHashMap<>()).computeIfAbsent(group, ignored -> new Progress()); }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag players = new ListTag();
            values.forEach((uuid, groups) -> { CompoundTag playerTag = new CompoundTag(); playerTag.putUUID("player", uuid); ListTag entries = new ListTag(); groups.forEach((id, progress) -> { CompoundTag entry = new CompoundTag(); entry.putString("id", id); entry.putInt("pulls", progress.pullsSinceTarget); entry.putInt("points", progress.selectionPoints); entries.add(entry); }); playerTag.put("groups", entries); players.add(playerTag); });
            tag.put("players", players); return tag;
        }
    }
}
