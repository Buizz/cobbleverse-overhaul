package dev.buizz.cobbleventure.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** A resettable one-block obstacle for the Rock Smash field move. */
final class RockSmashPuzzleBlocks {
    static final String ROCK_ID = "rock_smash_rock";
    private static final String DATA_FILE = "cobbleventure_rock_smash_rocks";
    private static final long RESPAWN_TICKS = 5L * 60L * 20L;
    private static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(CobbleventureBootstrap.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CobbleventureBootstrap.MOD_ID);

    static final DeferredBlock<RockSmashBlock> ROCK = BLOCKS.register(
        ROCK_ID,
        () -> new RockSmashBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.STONE)
            .pushReaction(PushReaction.BLOCK))
    );
    static final DeferredItem<BlockItem> ROCK_ITEM = ITEMS.register(
        ROCK_ID, () -> new BlockItem(ROCK.get(), new Item.Properties())
    );

    private RockSmashPuzzleBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(RockSmashPuzzleBlocks::addCreativeTabItems);
        NeoForge.EVENT_BUS.addListener(RockSmashPuzzleBlocks::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(RockSmashPuzzleBlocks::onServerTick);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ROCK_ITEM);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cobbleventure_rock_smash")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("reset").executes(context -> {
                ServerLevel level = context.getSource().getLevel();
                int reset = data(level).reset(level);
                context.getSource().sendSuccess(
                    () -> Component.literal("[Cobbleventure] 바위깨기 바위 " + reset + "개를 복원했습니다."),
                    true
                );
                return reset;
            })));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            data(level).restoreDue(level, level.getGameTime());
        }
    }

    private static BrokenRockData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BrokenRockData::new, BrokenRockData::load), DATA_FILE
        );
    }

    static final class RockSmashBlock extends Block {
        RockSmashBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit
        ) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            ServerPlayer serverPlayer = (ServerPlayer) player;
            if (!FieldMoveRidingAccess.isActive(serverPlayer, "rock_smash")) {
                serverPlayer.displayClientMessage(
                    Component.literal("바위깨기를 보유하고 ON으로 설정해야 부술 수 있습니다."), true
                );
                return InteractionResult.CONSUME;
            }
            ServerLevel serverLevel = (ServerLevel) level;
            data(serverLevel).markBroken(position, serverLevel.getGameTime());
            serverLevel.levelEvent(2001, position, Block.getId(state));
            serverLevel.removeBlock(position, false);
            return InteractionResult.CONSUME;
        }
    }

    private static final class BrokenRockData extends SavedData {
        private final Map<Long, Long> brokenAt = new LinkedHashMap<>();

        static BrokenRockData load(CompoundTag tag, HolderLookup.Provider registries) {
            BrokenRockData data = new BrokenRockData();
            ListTag entries = tag.getList("broken", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                data.brokenAt.put(entry.getLong("position"), entry.getLong("brokenAt"));
            }
            return data;
        }

        void markBroken(BlockPos position, long gameTime) {
            brokenAt.put(position.asLong(), gameTime);
            setDirty();
        }

        int reset(ServerLevel level) {
            int reset = 0;
            var iterator = brokenAt.entrySet().iterator();
            while (iterator.hasNext()) {
                BlockPos position = BlockPos.of(iterator.next().getKey());
                if (!canRestore(level, position)) {
                    continue;
                }
                level.setBlock(position, ROCK.get().defaultBlockState(), Block.UPDATE_ALL);
                iterator.remove();
                reset++;
            }
            if (reset > 0) {
                setDirty();
            }
            return reset;
        }

        void restoreDue(ServerLevel level, long gameTime) {
            int restored = 0;
            var iterator = brokenAt.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Long> entry = iterator.next();
                if (gameTime - entry.getValue() < RESPAWN_TICKS) {
                    continue;
                }
                BlockPos position = BlockPos.of(entry.getKey());
                if (!canRestore(level, position)) {
                    continue;
                }
                level.setBlock(position, ROCK.get().defaultBlockState(), Block.UPDATE_ALL);
                iterator.remove();
                restored++;
            }
            if (restored > 0) {
                setDirty();
            }
        }

        private static boolean canRestore(ServerLevel level, BlockPos position) {
            if (!level.hasChunkAt(position) || !level.getBlockState(position).canBeReplaced()) {
                return false;
            }
            AABB bounds = new AABB(
                position.getX(), position.getY(), position.getZ(),
                position.getX() + 1.0D, position.getY() + 1.0D, position.getZ() + 1.0D
            );
            return level.getEntities(
                (net.minecraft.world.entity.Entity) null, bounds, entity -> entity.isAlive()
            ).isEmpty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag entries = new ListTag();
            for (Map.Entry<Long, Long> brokenRock : brokenAt.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putLong("position", brokenRock.getKey());
                entry.putLong("brokenAt", brokenRock.getValue());
                entries.add(entry);
            }
            tag.put("broken", entries);
            return tag;
        }
    }
}
