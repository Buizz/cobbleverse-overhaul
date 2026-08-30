package dev.buizz.cobbleventure.casino;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Links a functional dealer NPC to a nearby authored Playing Cards blackjack table. */
@EventBusSubscriber(modid = CobbleventureCasino.MOD_ID)
public final class BlackjackTableFacade {
    private static final ResourceLocation FACADE = ResourceLocation.fromNamespaceAndPath(
        "playingcards", "poker_table"
    );
    private static final ResourceLocation BACKEND = ResourceLocation.fromNamespaceAndPath(
        "cobblemoncasino", "blackjack_table"
    );
    private static final int SEARCH_RADIUS = 8;
    private static final int LINK_RADIUS = 6;
    private static final int DEALER_SCAN_RADIUS = 12;
    private static final String FUNCTION_TAG = "cobbleventure_npc_function_blackjack";
    private static final String DEALER_ANCHOR = "cobbleventureBlackjackAnchor";
    private static final String DECORATION_TAG = "cobbleventure_blackjack_decoration";
    private static final String LOCKED_DECORATION_TAG =
        "cobbleventure_blackjack_locked_playingcards";
    private static final String DECORATION_ANCHOR = "cobbleventureBlackjackAnchor";
    private static final String CASINO_TABLE_MARKER = "cobbleventureBlackjackFacade";
    private BlackjackTableFacade() {
    }

    /**
     * Functional dealer interaction must run before the generic CVES binding
     * handler claims the same right click. Once the hidden backend table has
     * handled it, cancellation prevents the guidance dialogue from replacing
     * the blackjack screen.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDealerInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)
            || !event.getTarget().getTags().contains(FUNCTION_TAG)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos backend = ensureDealerLink(level, event.getTarget());
        if (backend == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "딜러 주변에서 블랙잭 테이블을 찾을 수 없습니다."
            ));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        BlockState backendState = level.getBlockState(backend);
        BlockHitResult backendHit = new BlockHitResult(
            Vec3.atCenterOf(backend), player.getDirection().getOpposite(), backend, false
        );
        InteractionResult result = backendState.useWithoutItem(level, player, backendHit);
        event.setCanceled(true);
        event.setCancellationResult(result == InteractionResult.PASS
            ? InteractionResult.SUCCESS : result);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || player.tickCount % 100 != 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        AABB scan = new AABB(player.blockPosition()).inflate(DEALER_SCAN_RADIUS);
        level.getEntities(
            (Entity)null, scan, entity -> entity.getTags().contains(FUNCTION_TAG)
        ).forEach(dealer -> ensureDealerLink(level, dealer));
    }

    private static BlockPos ensureDealerLink(ServerLevel level, Entity dealer) {
        BlockPos backend = BlockPos.of(dealer.getPersistentData().getLong(DEALER_ANCHOR));
        if (!blockId(level.getBlockState(backend)).equals(BACKEND)) {
            backend = nearestBackend(level, dealer.blockPosition());
        }
        if (backend == null) {
            BlockPos facade = nearestFacade(level, dealer.blockPosition());
            Block backendBlock = registeredBlock(BACKEND);
            if (facade == null || backendBlock == null) {
                return null;
            }
            backend = facade.below();
            level.setBlock(backend, backendBlock.defaultBlockState(), 3);
        }
        dealer.getPersistentData().putLong(DEALER_ANCHOR, backend.asLong());
        ensureDecorations(level, backend);
        lockPlayingCardsDecorations(level, backend);
        return backend;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFacadeRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !isProtectedCasinoPosition(player.serverLevel(), event.getPos())) {
            return;
        }
        boolean facade = blockId(event.getLevel().getBlockState(event.getPos())).equals(FACADE);
        boolean playingCardsItem = isPlayingCardsItem(event.getItemStack());
        if (!facade && !playingCardsItem) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        if (playingCardsItem) {
            showDecorationOnlyMessage(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayingCardsItemUse(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !isPlayingCardsItem(event.getItemStack())
            || !isProtectedCasinoPosition(player.serverLevel(), player.blockPosition())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        showDecorationOnlyMessage(player);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFacadeLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !blockId(event.getLevel().getBlockState(event.getPos())).equals(FACADE)
            || !isProtectedCasinoPosition(player.serverLevel(), event.getPos())) {
            return;
        }
        event.setCanceled(true);
        showDecorationOnlyMessage(player);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFacadeBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)
            || !blockId(event.getState()).equals(FACADE)
            || !isProtectedCasinoPosition(player.serverLevel(), event.getPos())) {
            return;
        }
        event.setCanceled(true);
        showDecorationOnlyMessage(player);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayingCardsEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!shouldProtectPlayingCardsEntity(event, event.getTarget())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        showDecorationOnlyMessage((ServerPlayer)event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayingCardsEntityInteractSpecific(
        PlayerInteractEvent.EntityInteractSpecific event
    ) {
        if (!shouldProtectPlayingCardsEntity(event, event.getTarget())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        showDecorationOnlyMessage((ServerPlayer)event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayingCardsEntityAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !isPlayingCardsEntity(event.getTarget())
            || !isProtectedCasinoPosition(
                player.serverLevel(), event.getTarget().blockPosition()
            )) {
            return;
        }
        event.setCanceled(true);
        showDecorationOnlyMessage(player);
    }

    private static boolean shouldProtectPlayingCardsEntity(
        PlayerInteractEvent event, Entity target
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !isPlayingCardsEntity(target)
            || !isProtectedCasinoPosition(player.serverLevel(), target.blockPosition())) {
            return false;
        }
        return true;
    }

    public static void showDecorationOnlyMessage(ServerPlayer player) {
        player.displayClientMessage(Component.literal(
            "카지노의 카드·칩·테이블은 장식 전용입니다. 딜러에게 말을 걸어주세요."
        ), true);
    }

    private static void lockPlayingCardsDecorations(ServerLevel level, BlockPos backend) {
        long anchor = backend.asLong();
        for (Entity entity : level.getEntities(
            (Entity)null, new AABB(backend).inflate(SEARCH_RADIUS),
            BlackjackTableFacade::isPlayingCardsEntity
        )) {
            entity.setInvulnerable(true);
            entity.setNoGravity(true);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.addTag(LOCKED_DECORATION_TAG);
            entity.getPersistentData().putLong(DECORATION_ANCHOR, anchor);
        }
    }

    private static void ensureDecorations(ServerLevel level, BlockPos backend) {
        long anchor = backend.asLong();
        // Earlier builds added one synthetic card and chip display per table block.
        // Authored Playing Cards entities are the only decorations we keep now.
        level.getEntities(
            (Entity)null, new AABB(backend).inflate(SEARCH_RADIUS),
            entity -> entity.getTags().contains(DECORATION_TAG)
                && entity.getPersistentData().getLong(DECORATION_ANCHOR) == anchor
        ).forEach(Entity::discard);
    }

    private static BlockPos nearestFacade(Level level, BlockPos center) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos cursor : BlockPos.betweenClosed(
            center.offset(-LINK_RADIUS, -2, -LINK_RADIUS),
            center.offset(LINK_RADIUS, 2, LINK_RADIUS)
        )) {
            if (!blockId(level.getBlockState(cursor)).equals(FACADE)) {
                continue;
            }
            double distance = cursor.distSqr(center);
            if (distance < nearestDistance) {
                nearest = cursor.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static BlockPos nearestBackend(Level level, BlockPos center) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos cursor : BlockPos.betweenClosed(
            center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
            center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)
        )) {
            if (!blockId(level.getBlockState(cursor)).equals(BACKEND)) {
                continue;
            }
            double distance = cursor.distSqr(center);
            if (distance <= SEARCH_RADIUS * SEARCH_RADIUS && distance < nearestDistance) {
                nearest = cursor.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean isProtectedCasinoPosition(Level level, BlockPos position) {
        if (nearestBackend(level, position) != null) {
            return true;
        }
        if (nearestManagedFacade(level, position) != null) {
            return true;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        for (Entity dealer : serverLevel.getEntities(
            (Entity)null, new AABB(position).inflate(DEALER_SCAN_RADIUS),
            entity -> entity.getTags().contains(FUNCTION_TAG)
        )) {
            BlockPos backend = ensureDealerLink(serverLevel, dealer);
            if (backend != null
                && backend.distSqr(position) <= SEARCH_RADIUS * SEARCH_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos nearestManagedFacade(Level level, BlockPos center) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos cursor : BlockPos.betweenClosed(
            center.offset(-LINK_RADIUS, -2, -LINK_RADIUS),
            center.offset(LINK_RADIUS, 2, LINK_RADIUS)
        )) {
            if (!blockId(level.getBlockState(cursor)).equals(FACADE)
                || level.getBlockEntity(cursor) == null
                || !level.getBlockEntity(cursor).getPersistentData()
                    .getBoolean(CASINO_TABLE_MARKER)) {
                continue;
            }
            double distance = cursor.distSqr(center);
            if (distance < nearestDistance) {
                nearest = cursor.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean isPlayingCardsItem(ItemStack stack) {
        return !stack.isEmpty()
            && isPlayingCardsId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static boolean isPlayingCardsEntity(Entity entity) {
        return isPlayingCardsId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static boolean isLockedPlayingCardsDecoration(Entity entity) {
        return isPlayingCardsEntity(entity)
            && (entity.getTags().contains(LOCKED_DECORATION_TAG)
                || isProtectedCasinoPosition(entity.level(), entity.blockPosition()));
    }

    static boolean isPlayingCardsId(ResourceLocation id) {
        return id != null && id.getNamespace().equals("playingcards");
    }

    private static Block registeredBlock(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        return BuiltInRegistries.BLOCK.getKey(block).equals(id) ? block : null;
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

}
