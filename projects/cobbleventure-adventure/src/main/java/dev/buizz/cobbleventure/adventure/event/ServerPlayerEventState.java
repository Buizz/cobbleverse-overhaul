package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.buizz.cobbleventure.adventure.FieldMoveRidingAccess;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** V4-compatible Minecraft storage adapter for one CVES player execution. */
public final class ServerPlayerEventState implements EventStateAccess {
    private static final String INSTANCE_DEFEATED_FLAG =
        "cobbleventure:runtime/npc_instance_defeated";
    private static final String STARTER_RECEIVED_FLAG =
        "cobbleventure:flag/story/starter_received";
    private static final String INSTANCE_DEFEATED_OBJECTIVE = "cv_npc_defeated";
    private static final String STARTER_RECEIVED_OBJECTIVE = "cv_starter_recv";
    private static final String FEATURE_PREFIX = "cobbleventureFeature.";
    private static final String LEVEL_CAP_KEY = "cobbleventureCurrentLevelCap";
    private static final String VARIABLE_PREFIX = "cobbleventureVariable.";
    private static final int DEFAULT_LEVEL_CAP = 5;
    private static final Gson GSON = new Gson();

    private final ServerPlayer player;

    public ServerPlayerEventState(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public String playerName() {
        return player.getGameProfile().getName();
    }

    @Override
    public boolean flag(String resourceId) {
        String objectiveName = flagObjective(resourceId);
        Objective objective = player.getScoreboard().getObjective(objectiveName);
        return objective != null
            && player.getScoreboard().getOrCreatePlayerScore(player, objective).get() != 0;
    }

    @Override
    public boolean hasItem(String resourceId, int count) {
        ResourceLocation id = requireResourceId(resourceId);
        if (count < 1) {
            throw new EventRuntimeException("아이템 조건 수량은 1 이상이어야 합니다: " + count);
        }
        return BuiltInRegistries.ITEM.getOptional(id)
            .map(item -> player.getInventory().countItem(item) >= count)
            .orElse(false);
    }

    @Override
    public BigInteger money() {
        return PlayerExtensionKt.getCobbleDollars(player);
    }

    @Override
    public int levelCap() {
        int stored = player.getPersistentData().getInt(LEVEL_CAP_KEY);
        return stored <= 0 ? DEFAULT_LEVEL_CAP : Math.max(1, Math.min(100, stored));
    }

    @Override
    public void setFlag(String resourceId, boolean value) {
        String objectiveName = flagObjective(resourceId);
        Scoreboard scoreboard = player.getScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            objective = scoreboard.addObjective(
                objectiveName,
                ObjectiveCriteria.DUMMY,
                Component.literal(objectiveName),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null
            );
        }
        scoreboard.getOrCreatePlayerScore(player, objective).set(value ? 1 : 0);
    }

    @Override
    public void setPlayerVariable(String resourceId, JsonElement value) {
        requireResourceId(resourceId);
        Objects.requireNonNull(value, "value");
        player.getPersistentData().putString(VARIABLE_PREFIX + resourceId, GSON.toJson(value));
    }

    @Override
    public void unlockFeature(String resourceId) {
        ResourceLocation id = requireResourceId(resourceId);
        String path = id.getPath();
        String feature = path.startsWith("feature/")
            ? path.substring("feature/".length())
            : path;
        if (feature.isBlank()) {
            throw new EventRuntimeException("기능 ID에 경로가 없습니다: " + resourceId);
        }
        executeProgressionCommand("unlock " + playerName() + " " + feature);
        if (!player.getPersistentData().getBoolean(FEATURE_PREFIX + feature)) {
            throw new EventRuntimeException("알 수 없는 progression 기능입니다: " + resourceId);
        }
    }

    @Override
    public void setLevelCap(int level) {
        if (level < 1 || level > 100) {
            throw new EventRuntimeException("레벨 캡은 1 이상 100 이하여야 합니다: " + level);
        }
        executeProgressionCommand("level_cap " + playerName() + " " + level);
        if (levelCap() != level) {
            throw new EventRuntimeException("레벨 캡 변경이 반영되지 않았습니다: " + level);
        }
    }

    @Override
    public boolean changeMoney(
        String operationId, BigInteger delta, boolean notify, boolean allowDebt
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(delta, "delta");
        int result = executeServerCommand(
            "cobbleventure_money_transaction " + playerName() + " "
                + StringArgumentType.escapeIfRequired(operationId) + " " + delta
                + " " + allowDebt
        );
        boolean success = result != 0;
        if (success && notify) {
            String sign = delta.signum() > 0 ? "+" : "";
            player.displayClientMessage(
                Component.literal("[Cobbleventure] ₽" + sign + delta), true
            );
        }
        return success;
    }

    @Override
    public void grantBadge(String resourceId) {
        ResourceLocation id = requireResourceId(resourceId);
        if (!id.getPath().startsWith("badge/")) {
            throw new EventRuntimeException("배지 리소스 ID가 필요합니다: " + resourceId);
        }
        int result = executeServerCommand(
            "cobbleventure_badge grant " + playerName() + " "
                + StringArgumentType.escapeIfRequired(resourceId)
        );
        if (result == 0) {
            throw new EventRuntimeException("배지를 지급하지 못했습니다: " + resourceId);
        }
    }

    @Override
    public void grantFieldMove(String move) {
        if (!FieldMoveRidingAccess.isSupported(move)) {
            throw new EventRuntimeException("지원하지 않는 필드 이동입니다: " + move);
        }
        FieldMoveRidingAccess.setEnabled(player, move, true);
    }

    public static String flagObjective(String resourceId) {
        requireResourceId(resourceId);
        if (resourceId.equals(INSTANCE_DEFEATED_FLAG)) {
            return INSTANCE_DEFEATED_OBJECTIVE;
        }
        if (resourceId.equals(STARTER_RECEIVED_FLAG)) {
            return STARTER_RECEIVED_OBJECTIVE;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                resourceId.getBytes(StandardCharsets.UTF_8)
            );
            return "cvf_" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1을 사용할 수 없습니다.", error);
        }
    }

    private static ResourceLocation requireResourceId(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new EventRuntimeException("올바르지 않은 리소스 ID입니다: " + value);
        }
        return id;
    }

    private void executeProgressionCommand(String arguments) {
        executeServerCommand("cobbleventure_progress " + arguments);
    }

    private int executeServerCommand(String command) {
        try {
            return player.getServer().getCommands().getDispatcher().execute(
                command,
                player.getServer().createCommandSourceStack()
                    .withLevel(player.serverLevel())
                    .withPermission(4)
                    .withSuppressedOutput()
            );
        } catch (CommandSyntaxException error) {
            throw new EventRuntimeException(
                "player-menu 상태 브리지 명령을 실행하지 못했습니다: " + command, error
            );
        }
    }
}
