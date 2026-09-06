package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Runtime projection of an authored battle preset needed to launch TBCS. */
public record EventBattlePreset(
    String battleId,
    String trainerId,
    String format,
    String levelMode,
    int levelOffset,
    int fallbackLevel,
    boolean canForfeit,
    Integer maxItemUses,
    MoneyReward moneyReward
) {
    public EventBattlePreset(
        String battleId,
        String trainerId,
        String format,
        String levelMode,
        int levelOffset,
        int fallbackLevel,
        Integer maxItemUses,
        MoneyReward moneyReward
    ) {
        this(
            battleId, trainerId, format, levelMode, levelOffset, fallbackLevel,
            true, maxItemUses, moneyReward
        );
    }

    public EventBattlePreset {
        requireResource(battleId, "battleId");
        requireResource(trainerId, "trainerId");
        requireText(format, "format");
        requireText(levelMode, "levelMode");
        if (fallbackLevel < 1 || fallbackLevel > 100) {
            throw new IllegalArgumentException("fallbackLevel은 1..100 범위여야 합니다.");
        }
        if (levelOffset < -99 || levelOffset > 99) {
            throw new IllegalArgumentException("levelOffset은 -99..99 범위여야 합니다.");
        }
        if (maxItemUses != null && maxItemUses < 0) {
            throw new IllegalArgumentException("maxItemUses는 0 이상이어야 합니다.");
        }
    }

    public record MoneyReward(
        boolean enabled,
        String mode,
        int amount,
        int fallbackRegionLevel,
        int perLevel,
        int offset,
        boolean heldItemBonus,
        String heldItem,
        int heldItemMultiplier,
        List<RewardFlagCondition> conditions
    ) {
        public MoneyReward(boolean enabled, String mode, int amount, int fallbackRegionLevel,
                           int perLevel, int offset, boolean heldItemBonus, String heldItem,
                           int heldItemMultiplier) {
            this(enabled, mode, amount, fallbackRegionLevel, perLevel, offset,
                heldItemBonus, heldItem, heldItemMultiplier, List.of());
        }

        public MoneyReward {
            conditions = List.copyOf(conditions);
            requireText(mode, "moneyReward.mode");
            if (!mode.equals("fixed") && !mode.equals("regional_level")) {
                throw new IllegalArgumentException("moneyReward.mode는 fixed 또는 regional_level이어야 합니다.");
            }
            if (amount < 0 || fallbackRegionLevel < 1 || fallbackRegionLevel > 100
                || perLevel < 0 || heldItemMultiplier < 1) {
                throw new IllegalArgumentException("moneyReward 수치 범위가 올바르지 않습니다.");
            }
            if (heldItemBonus) requireResource(heldItem, "moneyReward.heldItem");
        }

        public String prepareCommand(String playerName) {
            if (!conditions.isEmpty()) {
                throw new IllegalStateException("조건부 상금은 플레이어 플래그 평가가 필요합니다.");
            }
            return prepareCommand(playerName, key -> false);
        }

        public String prepareCommand(String playerName, Predicate<String> flag) {
            if (!enabled) return null;
            if (conditions.stream().anyMatch(condition ->
                flag.test(condition.key()) != condition.value())) return null;
            String base = "cobbleventure_reward prepare " + playerName + " ";
            String calculation = mode.equals("fixed")
                ? "fixed " + amount
                : "regional " + fallbackRegionLevel + " " + perLevel + " " + offset;
            return base + calculation + " " + heldItemBonus + " "
                + (heldItemBonus ? heldItem : "minecraft:air") + " " + heldItemMultiplier;
        }
    }

    public record RewardFlagCondition(String key, boolean value) {
        public RewardFlagCondition {
            requireResource(key, "moneyReward.conditions.key");
        }
    }

    public String launchCommand(String playerName, UUID opponentId) {
        requireText(playerName, "playerName");
        Objects.requireNonNull(opponentId, "opponentId");
        String runtimeTrainerId = rctTrainerId();
        String nested = "tbcs battle " + format + " " + playerName
            + " vs @s as " + runtimeTrainerId;
        if (!canForfeit || maxItemUses != null) {
            StringBuilder rules = new StringBuilder(" rules {");
            if (!canForfeit) rules.append("canForfeit:false");
            if (maxItemUses != null) {
                if (!canForfeit) rules.append(',');
                rules.append("maxItemUses:").append(maxItemUses);
            }
            nested += rules.append('}');
        }
        if (levelMode.equals("map_scaling")) {
            return "cobbleventure_scaled_trainer_battle " + playerName + " "
                + opponentId + " " + battleId + " " + levelOffset + " "
                + fallbackLevel + " " + runtimeTrainerId + " " + nested;
        }
        return "cobbleventure_instanced_trainer_battle " + playerName + " "
            + opponentId + " " + battleId + " " + runtimeTrainerId + " " + nested;
    }

    public String rctTrainerId() {
        String path = trainerId.substring(trainerId.indexOf(':') + 1);
        int slash = path.lastIndexOf('/');
        String slug = slash < 0 ? path : path.substring(slash + 1);
        return "rctmod:" + slug;
    }

    private static void requireResource(String value, String name) {
        requireText(value, name);
        if (ResourceLocation.tryParse(value) == null) {
            throw new IllegalArgumentException(name + "는 리소스 ID여야 합니다: " + value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 필요합니다.");
        }
    }
}
