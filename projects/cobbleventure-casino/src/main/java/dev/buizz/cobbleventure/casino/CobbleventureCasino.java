package dev.buizz.cobbleventure.casino;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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
    private static final ResourceLocation WORLD_NAME_FONT =
        ResourceLocation.withDefaultNamespace("uniform");
    private static volatile GachaCatalog catalog = GachaCatalog.empty();

    static GachaCatalog catalog() {
        return catalog;
    }

    public CobbleventureCasino(IEventBus modBus) {
        CasinoItems.register(modBus);
        CasinoCashier.register();
        CasinoHudNetwork.register(modBus);
        GachaMachineNetwork.register(modBus);
        if (FMLEnvironment.dist.isClient()) {
            CasinoBalanceOverlay.register(modBus);
        }
        NeoForge.EVENT_BUS.addListener(CobbleventureCasino::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CobbleventureCasino::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(CobbleventureCasino::onEntityInteract);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        catalog = GachaCatalog.load(event.getServer(), LOGGER);
        int refreshed = refreshConfiguredMachines(event.getServer());
        if (refreshed > 0) LOGGER.info("기존 가챠 기계 {}대를 실제 카지노 모델로 갱신했습니다.", refreshed);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cvgacha")
            .then(Commands.literal("status")
                .then(Commands.argument("profile", ResourceLocationArgument.id())
                    .suggests((context, builder) -> suggestProfiles(builder))
                    .executes(CobbleventureCasino::status)))
            .then(Commands.literal("select")
                .then(Commands.argument("profile", ResourceLocationArgument.id())
                    .suggests((context, builder) -> suggestProfiles(builder))
                    .then(Commands.argument("reward", StringArgumentType.string())
                        .suggests((context, builder) -> suggestRewards(context, builder))
                        .executes(CobbleventureCasino::selectReward))))
            .then(Commands.literal("place").requires(source -> source.hasPermission(2))
                .then(Commands.argument("profile", ResourceLocationArgument.id())
                    .suggests((context, builder) -> suggestProfiles(builder))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(CobbleventureCasino::placeCommand))))
            .then(Commands.literal("remove").requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(CobbleventureCasino::removeCommand)))
            .then(Commands.literal("ticket")
                .then(Commands.literal("give").requires(source -> source.hasPermission(2))
                    .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("profile", ResourceLocationArgument.id())
                            .suggests((context, builder) -> suggestProfiles(builder))
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                .executes(CobbleventureCasino::giveTickets)))))
                .then(Commands.literal("buy")
                    .then(Commands.argument("profile", ResourceLocationArgument.id())
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
        String profile = ResourceLocationArgument.getId(context, "profile").toString();
        catalog.machine(profile).ifPresent(machine -> machine.themes.stream()
            .flatMap(theme -> theme.rarities.stream())
            .flatMap(rarity -> rarity.rewards.stream()).filter(reward -> reward.selectable)
            .forEach(reward -> builder.suggest(reward.id)));
        return builder.buildFuture();
    }

    private static int reloadCommand(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        catalog = GachaCatalog.load(server, LOGGER);
        int refreshed = refreshConfiguredMachines(server);
        context.getSource().sendSuccess(() -> Component.literal("[가챠] 프로필을 다시 읽고 기계 " + refreshed + "대를 갱신했습니다."), true);
        return refreshed;
    }

    private static int refreshConfiguredMachines(MinecraftServer server) {
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
        return refreshed;
    }

    private static int placeCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String profile = ResourceLocationArgument.getId(context, "profile").toString();
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
        Block model = block(machine.appearance.model_block, defaultMachineBlock(machine.machine_type));
        Interaction interaction = EntityType.INTERACTION.create(level);
        if (interaction == null) return false;
        BlockState lower = machineState(model, machine.appearance.facing, DoubleBlockHalf.LOWER);
        BlockState upper = machineState(model, machine.appearance.facing, DoubleBlockHalf.UPPER);
        if (!lower.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            || !upper.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            LOGGER.error("가챠 외형은 상·하단 구조의 카지노 가챠 블록이어야 합니다: {}", machine.appearance.model_block);
            return false;
        }
        level.setBlock(pos, lower, Block.UPDATE_ALL);
        level.setBlock(pos.above(), upper, Block.UPDATE_ALL);
        interaction.setPos(pos.getX() + .5D, pos.getY(), pos.getZ() + .5D);
        CompoundTag interactionData = new CompoundTag();
        interaction.saveWithoutId(interactionData);
        interactionData.putFloat("width", .8F);
        interactionData.putFloat("height", 2.0F);
        interactionData.putBoolean("response", true);
        interaction.load(interactionData);
        interaction.setInvulnerable(true);
        interaction.setCustomName(
            Component.literal(machine.display_name)
                .withStyle(style -> style.withFont(WORLD_NAME_FONT))
        );
        interaction.setCustomNameVisible(machine.appearance.show_nameplate);
        markMachineEntity(interaction, anchor, profileId);
        return level.addFreshEntity(interaction);
    }

    /** Places a configured machine for authored building anchors. */
    public static boolean placeConfiguredMachine(ServerLevel level, BlockPos pos, String profileId) {
        removeMachine(level, pos);
        return placeMachine(level, pos, profileId);
    }

    private static BlockState machineState(Block block, String facing, DoubleBlockHalf half) {
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, switch (facing) {
                case "east" -> net.minecraft.core.Direction.EAST;
                case "south" -> net.minecraft.core.Direction.SOUTH;
                case "west" -> net.minecraft.core.Direction.WEST;
                default -> net.minecraft.core.Direction.NORTH;
            });
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            state = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half);
        }
        return state;
    }

    private static Block defaultMachineBlock(String machineType) {
        String id = switch (machineType) {
            case "pokemon" -> "cobblemoncasino:pokemon_gacha_machine";
            case "technical_machine" -> "cobblemoncasino:event_gacha_machine";
            default -> "cobblemoncasino:gacha_machine";
        };
        return block(id, Blocks.IRON_BLOCK);
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
        boolean foundMarker = false;
        for (Entity entity : level.getEntities((Entity)null, new AABB(pos).inflate(2.0D), entity -> entity.getTags().contains(MACHINE_TAG)
            && entity.getPersistentData().getLong(ANCHOR_KEY) == pos.asLong())) {
            entity.discard();
            removed++;
            foundMarker = true;
        }
        if (foundMarker) {
            if (isCasinoGachaBlock(level.getBlockState(pos).getBlock())) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                removed++;
            }
            if (isCasinoGachaBlock(level.getBlockState(pos.above()).getBlock())) {
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                removed++;
            }
        }
        return removed;
    }

    private static boolean isCasinoGachaBlock(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && "cobblemoncasino".equals(id.getNamespace())
            && ("gacha_machine".equals(id.getPath())
                || "pokemon_gacha_machine".equals(id.getPath())
                || "event_gacha_machine".equals(id.getPath())
                || "plushies_gacha_machine".equals(id.getPath()));
    }

    /** Routes a real Cobblemon Casino gacha block into the configured ticket system. */
    public static boolean useConfiguredMachine(ServerPlayer player, BlockPos clickedPos) {
        Interaction marker = configuredMachineMarker(player.serverLevel(), clickedPos);
        if (marker == null) return false;
        openGachaScreen(
            player,
            marker.getPersistentData().getString(PROFILE_KEY),
            BlockPos.of(marker.getPersistentData().getLong(ANCHOR_KEY))
        );
        return true;
    }

    private static Interaction configuredMachineMarker(ServerLevel level, BlockPos clickedPos) {
        return level.getEntitiesOfClass(
            Interaction.class,
            new AABB(clickedPos).inflate(1.25D),
            interaction -> interaction.getTags().contains(MACHINE_TAG)
                && configuredAnchorMatches(interaction, clickedPos)
        ).stream().findFirst().orElse(null);
    }

    private static boolean configuredAnchorMatches(Interaction interaction, BlockPos clickedPos) {
        BlockPos anchor = BlockPos.of(interaction.getPersistentData().getLong(ANCHOR_KEY));
        return anchor.equals(clickedPos) || anchor.above().equals(clickedPos);
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getTarget() instanceof Interaction interaction) || !interaction.getTags().contains(MACHINE_TAG)) return;
        String profile = interaction.getPersistentData().getString(PROFILE_KEY);
        openGachaScreen(
            player, profile,
            BlockPos.of(interaction.getPersistentData().getLong(ANCHOR_KEY))
        );
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void openGachaScreen(ServerPlayer player, String profileId, BlockPos anchor) {
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        if (machine == null) {
            player.sendSystemMessage(Component.literal("이 기계의 프로필을 찾을 수 없습니다."));
            return;
        }
        GachaMachineNetwork.open(player, anchor, machine);
    }

    static GachaUiState uiState(
        ServerPlayer player, GachaCatalog.Machine machine, GachaCatalog.Theme theme
    ) {
        Progress progress = playerData(player.server).progress(player.getUUID(), theme.pity_group);
        return new GachaUiState(
            GachaTickets.count(player, machine),
            progress.pullsSinceTarget,
            theme.pity.hard.enabled ? theme.pity.hard.count : 0,
            progress.selectionPoints,
            theme.pity.selection.enabled ? theme.pity.selection.required_points : 0
        );
    }

    static GachaCatalog.Machine configuredMachine(String profileId) {
        return catalog.machine(profileId).orElse(null);
    }

    static PullOutcome pullForScreen(ServerPlayer player, String profileId, String themeId) {
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        GachaCatalog.Theme theme = machine == null ? null : machine.theme(themeId);
        if (machine == null || theme == null) {
            return PullOutcome.failure("screen.cobbleventure_casino.gacha.invalid", 0, themeId, 1);
        }
        if (!GachaTickets.take(player, machine, theme.ticket_cost)) {
            return PullOutcome.failure(
                "screen.cobbleventure_casino.gacha.no_ticket",
                GachaTickets.count(player, machine), theme.id, theme.ticket_cost
            );
        }
        Progress progress = playerData(player.server).progress(player.getUUID(), theme.pity_group);
        GachaCatalog.Rarity rarity = chooseRarity(player.getRandom(), theme, progress.pullsSinceTarget);
        GachaCatalog.Reward reward = weighted(player.getRandom(), rarity.rewards, entry -> entry.weight);
        if (reward == null || !grant(player, reward)) {
            GachaTickets.give(player, machine, theme.ticket_cost);
            return PullOutcome.failure(
                "screen.cobbleventure_casino.gacha.grant_failed",
                GachaTickets.count(player, machine), theme.id, theme.ticket_cost
            );
        }
        String resetTarget = theme.pity.hard.enabled ? theme.pity.hard.target_rarity : theme.pity.soft.target_rarity;
        progress.pullsSinceTarget = rarity.id.equals(resetTarget) ? 0 : progress.pullsSinceTarget + 1;
        if (theme.pity.selection.enabled) progress.selectionPoints += theme.pity.selection.points_per_pull;
        playerData(player.server).setDirty();
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, .8F, 1.1F);
        return new PullOutcome(
            true, "screen.cobbleventure_casino.gacha.received",
            GachaTickets.count(player, machine), theme.id, theme.ticket_cost,
            rarity.id, rarity.display_name,
            reward.id, reward.kind, reward.value, reward.count,
            progress.pullsSinceTarget,
            theme.pity.hard.enabled ? theme.pity.hard.count : 0,
            progress.selectionPoints,
            theme.pity.selection.enabled ? theme.pity.selection.required_points : 0
        );
    }

    private static int giveTickets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String profileId = ResourceLocationArgument.getId(context, "profile").toString();
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
        String profileId = ResourceLocationArgument.getId(context, "profile").toString();
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        if (machine == null) { context.getSource().sendFailure(Component.literal("기계 프로필을 찾을 수 없습니다.")); return 0; }
        return GachaTicketVendor.buy(player, machine, IntegerArgumentType.getInteger(context, "amount"));
    }

    private static GachaCatalog.Rarity chooseRarity(RandomSource random, GachaCatalog.Theme theme, int misses) {
        Map<GachaCatalog.Rarity, Double> weights = rarityWeights(theme, misses);
        return weighted(random, new ArrayList<>(weights.keySet()), weights::get);
    }

    static Map<GachaCatalog.Rarity, Double> rarityWeights(GachaCatalog.Theme theme, int misses) {
        int nextPull = misses + 1;
        if (theme.pity.hard.enabled && nextPull >= theme.pity.hard.count) {
            GachaCatalog.Rarity forced = theme.rarity(theme.pity.hard.target_rarity);
            if (forced != null) return Map.of(forced, 1.0D);
        }
        Map<GachaCatalog.Rarity, Double> weights = new LinkedHashMap<>();
        for (GachaCatalog.Rarity rarity : theme.rarities) weights.put(rarity, Math.max(0.0D, rarity.weight));
        if (theme.pity.soft.enabled && nextPull >= theme.pity.soft.start) {
            GachaCatalog.Rarity target = theme.rarity(theme.pity.soft.target_rarity);
            if (target != null) {
                double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
                double baseChance = total <= 0 ? 0 : weights.get(target) / total;
                double span = Math.max(1, theme.pity.soft.max_at - theme.pity.soft.start);
                double ratio = Math.min(1.0D, (nextPull - theme.pity.soft.start) / span);
                double desired = baseChance + (theme.pity.soft.max_chance - baseChance) * ratio;
                double others = total - weights.get(target);
                if (others > 0 && desired > 0 && desired < 1) weights.put(target, desired * others / (1.0D - desired));
            }
        }
        return Map.copyOf(weights);
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
        String profile = ResourceLocationArgument.getId(context, "profile").toString();
        GachaCatalog.Machine machine = catalog.machine(profile).orElse(null);
        if (machine == null) { context.getSource().sendFailure(Component.literal("기계 프로필을 찾을 수 없습니다.")); return 0; }
        GachaCatalog.Theme theme = machine.defaultTheme();
        if (theme == null) { context.getSource().sendFailure(Component.literal("가챠 테마가 없습니다.")); return 0; }
        sendPityStatus(player, theme, playerData(player.server).progress(player.getUUID(), theme.pity_group));
        return 1;
    }

    private static void sendPityStatus(ServerPlayer player, GachaCatalog.Theme theme, Progress progress) {
        String hard = theme.pity.hard.enabled ? "확정 " + progress.pullsSinceTarget + "/" + theme.pity.hard.count : "확정 꺼짐";
        String selection = theme.pity.selection.enabled ? "선택 " + progress.selectionPoints + "/" + theme.pity.selection.required_points : "선택 꺼짐";
        player.sendSystemMessage(Component.literal("[천장 · " + theme.display_name + "] " + hard + " · " + selection));
    }

    private static int selectReward(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try { player = context.getSource().getPlayerOrException(); } catch (Exception error) { context.getSource().sendFailure(Component.literal("플레이어만 사용할 수 있습니다.")); return 0; }
        String profileId = ResourceLocationArgument.getId(context, "profile").toString();
        String rewardId = StringArgumentType.getString(context, "reward");
        GachaCatalog.Machine machine = catalog.machine(profileId).orElse(null);
        GachaCatalog.Theme theme = machine == null ? null : machine.themeForReward(rewardId);
        GachaCatalog.Reward reward = theme == null ? null : theme.reward(rewardId);
        if (theme == null || !theme.pity.selection.enabled || reward == null || !reward.selectable) { context.getSource().sendFailure(Component.literal("선택할 수 없는 보상입니다.")); return 0; }
        PlayerData data = playerData(player.server);
        Progress progress = data.progress(player.getUUID(), theme.pity_group);
        if (progress.selectionPoints < theme.pity.selection.required_points) { context.getSource().sendFailure(Component.literal("선택 천장 포인트가 부족합니다: " + progress.selectionPoints + "/" + theme.pity.selection.required_points)); return 0; }
        if (!grant(player, reward)) { context.getSource().sendFailure(Component.literal("보상 지급에 실패했습니다.")); return 0; }
        progress.selectionPoints -= theme.pity.selection.required_points;
        data.setDirty();
        context.getSource().sendSuccess(() -> Component.literal("[선택 천장] " + reward.id + " 보상을 받았습니다."), false);
        return 1;
    }

    private static PlayerData playerData(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlayerData::new, PlayerData::load), DATA_FILE);
    }

    private static final class Progress { int pullsSinceTarget; int selectionPoints; }
    record GachaUiState(
        int tickets, int pullsSinceTarget, int hardPityCount,
        int selectionPoints, int selectionRequired
    ) {}

    record PullOutcome(
        boolean success, String messageKey, int tickets, String themeId, int ticketCost,
        String rarityId, String rarityName,
        String rewardId, String rewardKind, String rewardValue, int rewardCount,
        int pullsSinceTarget, int hardPityCount,
        int selectionPoints, int selectionRequired
    ) {
        static PullOutcome failure(String messageKey, int tickets, String themeId, int ticketCost) {
            return new PullOutcome(
                false, messageKey, tickets, themeId, ticketCost,
                "", "", "", "", "", 0,
                0, 0, 0, 0
            );
        }
    }
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
