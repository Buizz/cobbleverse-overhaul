package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Turns a learned Headbutt into a route-authored tree encounter. */
final class HeadbuttEncounters {
    private static final String PLAYER_COOLDOWN = "cobbleventureHeadbuttCooldown";
    private static final long PLAYER_COOLDOWN_TICKS = 40L;
    private static final long TREE_COOLDOWN_TICKS = 200L;
    private static final Map<TreeKey, Long> TREE_COOLDOWNS = new ConcurrentHashMap<>();
    private static boolean registered;

    private HeadbuttEncounters() {}

    static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(HeadbuttEncounters::onRightClickBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || event.getLevel().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !player.isShiftKeyDown()
            || !player.getMainHandItem().isEmpty()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos tree = event.getPos();
        BlockState treeState = level.getBlockState(tree);
        if (!treeState.is(BlockTags.LOGS)) return;

        AdventureWorldContext.WildSpawnRule rule =
            CobbleventureAdventure.authoredEncounterRule(
                level, tree.getX() + 0.5D, tree.getZ() + 0.5D,
                AdventureWorldContext.WildEncounterMethod.HEADBUTT
            );
        if (rule == null) return;
        consumeInteraction(event);

        long now = level.getGameTime();
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            message(player, "전투 중에는 박치기를 사용할 수 없다.");
            return;
        }
        if (player.getPersistentData().getLong(PLAYER_COOLDOWN) > now) return;
        if (!knowsHeadbutt(player)) {
            message(player, "박치기를 배운 건강한 포켓몬이 필요하다.");
            return;
        }
        if (!rule.enabled()) {
            message(player, "이 나무에서는 아무런 기척도 느껴지지 않는다.");
            return;
        }
        TreeKey treeKey = new TreeKey(level.dimension(), tree);
        if (TREE_COOLDOWNS.getOrDefault(treeKey, 0L) > now) {
            message(player, "이 나무는 아직 크게 흔들리고 있다.");
            return;
        }

        player.getPersistentData().putLong(PLAYER_COOLDOWN, now + PLAYER_COOLDOWN_TICKS);
        TREE_COOLDOWNS.put(treeKey, now + TREE_COOLDOWN_TICKS);
        playTreeHit(level, player, tree, treeState);
        if (level.getRandom().nextDouble() > rule.triggerChance()
            || rule.additions().isEmpty()) {
            message(player, "아무것도 떨어지지 않았다.");
            return;
        }
        BlockPos spawnAt = findSpawnPosition(level, player.blockPosition(), tree);
        if (spawnAt == null) {
            message(player, "포켓몬이 내려올 공간이 없다.");
            return;
        }
        spawnEncounter(level, player, spawnAt, rule);
    }

    private static void spawnEncounter(
        ServerLevel level, ServerPlayer player, BlockPos spawnAt,
        AdventureWorldContext.WildSpawnRule rule
    ) {
        AdventureWorldContext.WildSpawnAddition addition =
            selectAddition(level.getRandom(), rule.additions());
        int encounterLevel = levelFor(level.getRandom(), rule, addition.species(),
            CobbleventureAdventure.averageWildSpawnLevel(
                level, spawnAt.getX() + 0.5D, spawnAt.getZ() + 0.5D
            ));
        PokemonProperties properties = PokemonProperties.Companion.parse(
            addition.species().toString()
        );
        properties.setLevel(encounterLevel);
        PokemonEntity entity = properties.createEntity(level);
        if (entity == null) {
            message(player, "포켓몬을 불러오지 못했다.");
            return;
        }
        if (!addition.spawnAsEvolved()) {
            WildSpawnLeveling.normalizeLevelEvolution(entity.getPokemon());
        }
        entity.addTag(WildSpawnLeveling.AUTHORED_METHOD_ENCOUNTER_TAG);
        entity.moveTo(
            spawnAt.getX() + 0.5D, spawnAt.getY(), spawnAt.getZ() + 0.5D,
            player.getYRot() + 180.0F, 0.0F
        );
        if (!level.addFreshEntity(entity)) {
            message(player, "포켓몬이 내려올 수 없었다.");
            return;
        }
        level.getServer().execute(() -> {
            if (entity.isAlive() && player.isAlive()) entity.forceBattle(player);
        });
    }

    private static boolean knowsHeadbutt(ServerPlayer player) {
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon.getCurrentHealth() <= 0) continue;
            for (Move move : pokemon.getMoveSet()) {
                if ("headbutt".equals(move.getTemplate().getName().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos findSpawnPosition(
        ServerLevel level, BlockPos playerPosition, BlockPos tree
    ) {
        Direction awayFromTree = Direction.getNearest(
            playerPosition.getX() - tree.getX(), 0.0D,
            playerPosition.getZ() - tree.getZ()
        );
        if (awayFromTree.getAxis().isVertical()) awayFromTree = Direction.NORTH;
        List<Direction> directions = List.of(
            awayFromTree, awayFromTree.getClockWise(), awayFromTree.getCounterClockWise(),
            awayFromTree.getOpposite()
        );
        for (Direction direction : directions) {
            BlockPos column = tree.relative(direction, 2);
            for (int offset = 3; offset >= -3; offset--) {
                BlockPos feet = new BlockPos(column.getX(), playerPosition.getY() + offset, column.getZ());
                BlockPos floor = feet.below();
                if (level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                    && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                    return feet;
                }
            }
        }
        return null;
    }

    private static AdventureWorldContext.WildSpawnAddition selectAddition(
        RandomSource random, List<AdventureWorldContext.WildSpawnAddition> additions
    ) {
        int total = additions.stream()
            .mapToInt(AdventureWorldContext.WildSpawnAddition::weight).sum();
        int choice = random.nextInt(total);
        for (AdventureWorldContext.WildSpawnAddition addition : additions) {
            choice -= addition.weight();
            if (choice < 0) return addition;
        }
        return additions.get(additions.size() - 1);
    }

    private static int levelFor(
        RandomSource random, AdventureWorldContext.WildSpawnRule rule,
        net.minecraft.resources.ResourceLocation species, Integer averageLevel
    ) {
        AdventureWorldContext.WildSpawnLevelRange override = rule.levelOverrides().get(species);
        int minimum;
        int maximum;
        if (override != null) {
            minimum = override.minLevel();
            maximum = override.maxLevel();
        } else if (averageLevel != null) {
            minimum = averageLevel - 2;
            maximum = averageLevel + 2;
        } else {
            minimum = 2;
            maximum = 5;
        }
        minimum = Math.max(1, Math.min(100, minimum));
        maximum = Math.max(minimum, Math.min(100, maximum));
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static void playTreeHit(
        ServerLevel level, ServerPlayer player, BlockPos tree, BlockState state
    ) {
        player.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(
            null, tree, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 1.0F, 0.8F
        );
        level.sendParticles(
            new BlockParticleOption(ParticleTypes.BLOCK, state),
            tree.getX() + 0.5D, tree.getY() + 0.7D, tree.getZ() + 0.5D,
            18, 0.35D, 0.5D, 0.35D, 0.08D
        );
    }

    private static void consumeInteraction(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }

    private record TreeKey(ResourceKey<Level> dimension, BlockPos position) {}
}
