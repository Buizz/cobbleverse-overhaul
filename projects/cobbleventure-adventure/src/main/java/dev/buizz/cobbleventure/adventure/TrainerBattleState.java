package dev.buizz.cobbleventure.adventure;

import com.mojang.brigadier.CommandDispatcher;
import dev.buizz.cobbleventure.adventure.event.ServerPlayerEventState;
import java.util.Collection;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Stores trainer completion per player and per spawned EasyNPC entity. */
public final class TrainerBattleState {
    static final String DIALOG_OBJECTIVE = "cv_npc_defeated";
    private static final String DATA_FILE = "cobbleventure_trainer_battles";
    private static final String REGIONAL_NPC_TAG = "cobbleventure_regional_npc";
    private static final String EVENT_BINDING_PREFIX = "cves_binding/";
    private static final String GYM_LEADER_BINDING_PREFIX =
        "cves_binding/cobbleventure/gym_leaders/";
    private static final String PROXIMITY_TRIGGER_TAG = "cves_trigger/proximity";
    private static final double GYM_STAFF_RADIUS = 64.0D;

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
        boolean defeated = isDefeated(player, npc);
        setDialogScore(player, defeated ? 1 : 0);
        return defeated ? 1 : 0;
    }

    private static int complete(UUID npc, ServerPlayer player) {
        setDefeated(player, npc, true);
        setDialogScore(player, 1);
        return 1;
    }

    private static void completeGymTrainers(
        ServerPlayer player, UUID defeatedNpc, BattleData battleData
    ) {
        Entity leader = null;
        ServerLevel gymLevel = null;
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity candidate = level.getEntity(defeatedNpc);
            if (candidate != null) {
                leader = candidate;
                gymLevel = level;
                break;
            }
        }
        if (leader == null || !isGymLeader(leader.getTags())) {
            return;
        }

        UUID playerId = player.getUUID();
        ServerPlayerEventState eventState = new ServerPlayerEventState(player);
        Entity defeatedLeader = leader;
        for (Entity trainer : gymLevel.getEntities(
            (Entity) null,
            leader.getBoundingBox().inflate(GYM_STAFF_RADIUS),
            candidate -> candidate != defeatedLeader
                && candidate.distanceToSqr(defeatedLeader) <= GYM_STAFF_RADIUS * GYM_STAFF_RADIUS
                && isGymTrainer(candidate.getTags())
        )) {
            battleData.setDefeated(playerId, trainer.getUUID(), true);
            String victoryFlag = trainerVictoryFlag(trainer.getTags());
            if (victoryFlag != null) {
                eventState.setFlag(victoryFlag, true);
            }
        }
    }

    static boolean isGymLeader(Set<String> tags) {
        return tags.stream().anyMatch(tag -> tag.startsWith(GYM_LEADER_BINDING_PREFIX));
    }

    static boolean isGymTrainer(Set<String> tags) {
        return tags.contains(REGIONAL_NPC_TAG)
            && tags.contains(PROXIMITY_TRIGGER_TAG)
            && trainerVictoryFlag(tags) != null;
    }

    static String trainerVictoryFlag(Set<String> tags) {
        return tags.stream()
            .filter(tag -> tag.startsWith(EVENT_BINDING_PREFIX))
            .map(tag -> tag.substring(tag.lastIndexOf('/') + 1))
            .filter(slug -> !slug.isBlank())
            .map(slug -> "cobbleventure:flag/trainer/" + slug + "/defeated")
            .findFirst()
            .orElse(null);
    }

    static String gymLeaderVictoryFlag(Set<String> tags) {
        return tags.stream()
            .filter(tag -> tag.startsWith(GYM_LEADER_BINDING_PREFIX))
            .map(tag -> tag.substring(tag.lastIndexOf('/') + 1))
            .filter(slug -> !slug.isBlank())
            .map(slug -> "cobbleventure:flag/gym/kanto/" + slug + "/defeated")
            .findFirst()
            .orElse(null);
    }

    private static BattleData data(ServerPlayer player) {
        return player.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BattleData::new, BattleData::load),
            DATA_FILE
        );
    }

    public static boolean isDefeated(ServerPlayer player, UUID npc) {
        BattleData battleData = data(player);
        UUID playerId = player.getUUID();
        if (battleData.isDefeated(playerId, npc)) {
            return true;
        }
        migrateClearedGymTrainers(player, npc, battleData);
        return battleData.isDefeated(playerId, npc);
    }

    private static void migrateClearedGymTrainers(
        ServerPlayer player, UUID trainerId, BattleData battleData
    ) {
        Entity trainer = player.serverLevel().getEntity(trainerId);
        if (trainer == null || !isGymTrainer(trainer.getTags())) {
            return;
        }
        Entity leader = player.serverLevel().getEntities(
            (Entity) null,
            trainer.getBoundingBox().inflate(GYM_STAFF_RADIUS),
            candidate -> candidate.distanceToSqr(trainer) <= GYM_STAFF_RADIUS * GYM_STAFF_RADIUS
                && isGymLeader(candidate.getTags())
        ).stream().findFirst().orElse(null);
        String leaderVictoryFlag = leader == null
            ? null : gymLeaderVictoryFlag(leader.getTags());
        if (leaderVictoryFlag != null
            && new ServerPlayerEventState(player).flag(leaderVictoryFlag)) {
            completeGymTrainers(player, leader.getUUID(), battleData);
        }
    }

    public static void setDefeated(ServerPlayer player, UUID npc, boolean defeated) {
        BattleData battleData = data(player);
        battleData.setDefeated(player.getUUID(), npc, defeated);
        if (defeated) {
            completeGymTrainers(player, npc, battleData);
        }
    }

    /** Preserves every player's result when duplicate persisted NPC entities are collapsed. */
    public static void mergeNpcInstances(
        MinecraftServer server, UUID retainedNpc, Collection<UUID> removedNpcs
    ) {
        if (removedNpcs.isEmpty()) return;
        BattleData battleData = server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BattleData::new, BattleData::load), DATA_FILE
        );
        battleData.mergeNpcInstances(retainedNpc, Set.copyOf(removedNpcs));
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

        void setDefeated(UUID player, UUID npc, boolean value) {
            boolean changed = value
                ? defeated.add(new BattleKey(player, npc))
                : defeated.remove(new BattleKey(player, npc));
            if (changed) {
                setDirty();
            }
        }

        void mergeNpcInstances(UUID retainedNpc, Set<UUID> removedNpcs) {
            Set<BattleKey> replacements = new HashSet<>();
            boolean changed = defeated.removeIf(key -> {
                if (!removedNpcs.contains(key.npc())) return false;
                replacements.add(new BattleKey(key.player(), retainedNpc));
                return true;
            });
            if (defeated.addAll(replacements) || changed) setDirty();
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
