package dev.buizz.cobbleventure.adventure;

import com.mojang.brigadier.CommandDispatcher;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Stores trainer completion per player and per spawned EasyNPC entity. */
final class TrainerBattleState {
    static final String DIALOG_OBJECTIVE = "cv_npc_defeated";
    private static final String DATA_FILE = "cobbleventure_trainer_battles";

    private TrainerBattleState() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(TrainerBattleState::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cobbleventure_trainer_state")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("prepare")
                    .then(Commands.argument("npc", UuidArgument.uuid())
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(context -> prepare(
                                UuidArgument.getUuid(context, "npc"),
                                EntityArgument.getPlayer(context, "player")
                            )))))
                .then(Commands.literal("complete")
                    .then(Commands.argument("npc", UuidArgument.uuid())
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(context -> complete(
                                UuidArgument.getUuid(context, "npc"),
                                EntityArgument.getPlayer(context, "player")
                            )))))
        );
    }

    private static int prepare(UUID npc, ServerPlayer player) {
        boolean defeated = data(player).isDefeated(player.getUUID(), npc);
        setDialogScore(player, defeated ? 1 : 0);
        return defeated ? 1 : 0;
    }

    private static int complete(UUID npc, ServerPlayer player) {
        data(player).markDefeated(player.getUUID(), npc);
        setDialogScore(player, 1);
        return 1;
    }

    private static BattleData data(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BattleData::new, BattleData::load),
            DATA_FILE
        );
    }

    private static void setDialogScore(ServerPlayer player, int value) {
        Scoreboard scoreboard = player.getScoreboard();
        Objective objective = scoreboard.getObjective(DIALOG_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(
                DIALOG_OBJECTIVE,
                ObjectiveCriteria.DUMMY,
                Component.literal(DIALOG_OBJECTIVE),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null
            );
        }
        scoreboard.getOrCreatePlayerScore(player, objective).set(value);
    }

    record BattleKey(UUID player, UUID npc) {
    }

    static final class BattleData extends SavedData {
        private final Set<BattleKey> defeated = new HashSet<>();

        static BattleData load(CompoundTag tag, HolderLookup.Provider registries) {
            BattleData data = new BattleData();
            ListTag entries = tag.getList("defeated", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                if (entry.hasUUID("player") && entry.hasUUID("npc")) {
                    data.defeated.add(new BattleKey(
                        entry.getUUID("player"), entry.getUUID("npc")
                    ));
                }
            }
            return data;
        }

        boolean isDefeated(UUID player, UUID npc) {
            return defeated.contains(new BattleKey(player, npc));
        }

        void markDefeated(UUID player, UUID npc) {
            if (defeated.add(new BattleKey(player, npc))) {
                setDirty();
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag entries = new ListTag();
            for (BattleKey key : defeated) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("player", key.player());
                entry.putUUID("npc", key.npc());
                entries.add(entry);
            }
            tag.put("defeated", entries);
            return tag;
        }
    }
}
