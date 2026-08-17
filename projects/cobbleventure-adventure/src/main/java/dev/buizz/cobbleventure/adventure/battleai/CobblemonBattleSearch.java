package dev.buizz.cobbleventure.adventure.battleai;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BagItemActionResponse;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.item.battle.BagItem;
import com.gitlab.srcmc.rctapi.api.ai.utils.PokeMath;
import com.gitlab.srcmc.rctapi.api.battle.BattleManager.TrainerEntityBattleActor;
import dev.buizz.cobbleventure.ai.core.BattleValueSide;
import dev.buizz.cobbleventure.ai.core.BattleValueState;
import dev.buizz.cobbleventure.ai.core.SearchAction;
import dev.buizz.cobbleventure.ai.core.SearchDecision;
import dev.buizz.cobbleventure.ai.core.SearchRuntime;
import dev.buizz.cobbleventure.ai.core.SharedAiCore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** Cobblemon 공개 전투 상태를 웹 실험실과 동일한 멀티플랫폼 코어에 투영한다. */
final class CobblemonBattleSearch implements SearchRuntime {
    private static final String MOVE_PREFIX = "move:";
    private static final String SWITCH_PREFIX = "switch:";
    private static final String GIMMICK_PREFIX = "gimmick:";
    private static final String ITEM_PREFIX = "item:";

    private final Team[] teams;
    private final List<RootMove> rootMoves;
    private final List<String> rootGimmicks;
    private final String strategy;
    private final String stateNamespace = UUID.randomUUID().toString();
    private final Map<String, State> states = new HashMap<>();

    private CobblemonBattleSearch(Team[] teams, List<RootMove> rootMoves, List<String> rootGimmicks, String strategy) {
        this.teams = teams;
        this.rootMoves = rootMoves;
        this.rootGimmicks = rootGimmicks;
        this.strategy = strategy;
    }

    static PlannedResponse plan(
            ActiveBattlePokemon active,
            BattleSide side,
            ShowdownMoveset moveset,
            String difficulty,
            String strategy,
            boolean forceSwitch
    ) {
        Team own = team(side, active);
        BattleSide oppositeSide = side.getOppositeSide();
        ActiveBattlePokemon opposite = oppositeSide.getActivePokemon().stream()
                .filter(ActiveBattlePokemon::hasPokemon).findFirst().orElse(null);
        if (opposite == null || own.members.isEmpty()) return null;
        Team enemy = team(oppositeSide, opposite);
        List<RootMove> rootMoves = rootMoves(active, moveset);
        List<String> gimmicks = moveset.getGimmicks().stream().map(ShowdownMoveset.Gimmick::getId).toList();
        CobblemonBattleSearch model = new CobblemonBattleSearch(new Team[]{own, enemy}, rootMoves, gimmicks, strategy);
        State state = model.initialState();
        String stateId = model.remember(state);

        SearchDecision decision;
        if (forceSwitch) {
            SearchAction selected = model.candidates(stateId, 0).stream()
                    .filter(action -> "switch".equals(action.getKind()))
                    .max(Comparator.comparingDouble(SearchAction::getScore)).orElse(null);
            decision = selected == null ? null : new SearchDecision(selected, false, List.of(), 0, 0, false, 1);
        } else if ("expert_winrate".equals(difficulty)) {
            decision = SharedAiCore.INSTANCE.decideWinRate(stateId, 0, 8, model);
        } else {
            decision = SharedAiCore.INSTANCE.decideTwoTurn(stateId, 0, 10, model, null);
        }
        if (decision == null || decision.getSelected() == null) return null;
        ShowdownActionResponse response = model.toResponse(decision.getSelected(), opposite);
        return response == null ? null : new PlannedResponse(response, decision);
    }

    private ShowdownActionResponse toResponse(SearchAction selected, ActiveBattlePokemon opponent) {
        String id = selected.getId();
        if (id.startsWith(SWITCH_PREFIX)) {
            return new SwitchActionResponse(UUID.fromString(id.substring(SWITCH_PREFIX.length())));
        }
        if (id.startsWith(ITEM_PREFIX)) {
            int[] selection = itemSelection(id);
            BattleItem item = teams[0].items.get(selection[0]);
            BattlePokemon target = teams[0].members.get(selection[1]);
            return new BagItemActionResponse(item.item, target, null);
        }
        String gimmick = null;
        String moveId = id;
        if (id.startsWith(GIMMICK_PREFIX)) {
            int separator = id.indexOf(':', GIMMICK_PREFIX.length());
            gimmick = id.substring(GIMMICK_PREFIX.length(), separator);
            moveId = id.substring(separator + 1);
        }
        if (!moveId.startsWith(MOVE_PREFIX)) return null;
        String showdownMove = moveId.substring(MOVE_PREFIX.length());
        RootMove root = rootMoves.stream().filter(move -> move.showdownId.equals(showdownMove)).findFirst().orElse(null);
        if (root == null) return null;
        String target = root.target instanceof ActiveBattlePokemon targetPokemon
                ? targetPokemon.getPNX() : opponent.getPNX();
        return new MoveActionResponse(root.showdownId, target, gimmick);
    }

    @Override
    public List<SearchAction> candidates(String stateId, int sideIndex) {
        State state = requireState(stateId);
        Team team = teams[sideIndex];
        int activeIndex = state.active[sideIndex];
        BattlePokemon attacker = team.members.get(activeIndex);
        BattlePokemon defender = teams[1 - sideIndex].members.get(state.active[1 - sideIndex]);
        int defenderHp = state.hp[1 - sideIndex][state.active[1 - sideIndex]];
        List<SearchAction> result = new ArrayList<>();

        if (sideIndex == 0 && state.turn == 0) {
            for (RootMove root : rootMoves) {
                result.add(moveAction(root.showdownId, root.move, attacker, defender, defenderHp, null));
                for (String gimmick : rootGimmicks) {
                    result.add(moveAction(root.showdownId, root.move, attacker, defender, defenderHp, gimmick));
                }
            }
        } else {
            for (Move move : attacker.getMoveSet()) {
                if (move.getCurrentPp() > 0) result.add(moveAction(move.getName(), move, attacker, defender, defenderHp, null));
            }
        }
        for (int index = 0; index < team.members.size(); index++) {
            if (index == activeIndex || state.hp[sideIndex][index] <= 0) continue;
            BattlePokemon candidate = team.members.get(index);
            double hpRatio = ratio(state.hp[sideIndex][index], candidate.getMaxHealth());
            double currentRatio = ratio(state.hp[sideIndex][activeIndex], attacker.getMaxHealth());
            double score = (20.0 + hpRatio * 45.0 - currentRatio * 15.0) * switchBias();
            result.add(action(SWITCH_PREFIX + candidate.getUuid(), "switch", score));
        }
        for (int itemIndex = 0; itemIndex < team.items.size(); itemIndex++) {
            if (state.itemCounts[sideIndex][itemIndex] <= 0) continue;
            BattleItem item = team.items.get(itemIndex);
            for (int targetIndex = 0; targetIndex < team.members.size(); targetIndex++) {
                if (state.hp[sideIndex][targetIndex] <= 0) continue;
                BattlePokemon target = team.members.get(targetIndex);
                int healing = healingAmount(item.item.getItemName(), target.getMaxHealth());
                int missing = target.getMaxHealth() - state.hp[sideIndex][targetIndex];
                boolean currentlyUsable = item.item.canUse(
                        new ItemStack(item.item.getReturnItem()), target.getActor().getBattle(), target);
                if (!currentlyUsable && !(state.turn > 0 && healing > 0 && missing > 0)) continue;
                double restored = Math.min(missing, healing);
                double score = healing > 0 ? restored / Math.max(1, target.getMaxHealth()) * 120.0 : 22.0;
                result.add(action(ITEM_PREFIX + itemIndex + ':' + targetIndex, "item", score));
            }
        }
        return result;
    }

    private SearchAction moveAction(
            String moveId, Move move, BattlePokemon attacker, BattlePokemon defender,
            int defenderHp, String gimmick
    ) {
        int baseDamage = Math.max(0, PokeMath.damage(attacker, defender, move));
        double multiplier = gimmickMultiplier(gimmick);
        double damage = Math.min(defenderHp, baseDamage * multiplier);
        boolean status = move.getPower() <= 0.0;
        double accuracy = move.getAccuracy() <= 0.0 ? 1.0 : Math.min(1.0, move.getAccuracy() / 100.0);
        double score = status ? 18.0 : damage / Math.max(1, defenderHp) * 100.0 + (damage >= defenderHp ? 80.0 : 0.0);
        score *= status ? statusBias() : moveBias();
        if (gimmick != null) score += 8.0 * gimmickBias();
        String baseId = MOVE_PREFIX + moveId;
        String actionId = gimmick == null ? baseId : GIMMICK_PREFIX + gimmick + ':' + baseId;
        String normalized = moveId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        boolean nonConsecutive = normalized.equals("gigaimpact") || normalized.equals("hyperbeam")
                || normalized.equals("blastburn") || normalized.equals("frenzyplant") || normalized.equals("hydrocannon");
        return new SearchAction(actionId, gimmick == null ? "move" : "gimmick", score, accuracy,
                damage, nonConsecutive, status, damage >= defenderHp, 0.0, false);
    }

    @Override
    public String transition(String stateId, String sideZeroActionId, String sideOneActionId) {
        State state = requireState(stateId);
        SearchAction sideZeroAction = findAction(stateId, 0, sideZeroActionId);
        SearchAction sideOneAction = findAction(stateId, 1, sideOneActionId);
        State next = transitionState(state, sideZeroAction, sideOneAction);
        return remember(next);
    }

    private State transitionState(State state, SearchAction sideZeroAction, SearchAction sideOneAction) {
        int[] nextActive = state.active.clone();
        int[][] nextHp = copy(state.hp);
        int[][] nextItemCounts = copy(state.itemCounts);
        SearchAction[] actions = {sideZeroAction, sideOneAction};
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            if ("switch".equals(actions[sideIndex].getKind())) {
                UUID uuid = UUID.fromString(actions[sideIndex].getId().substring(SWITCH_PREFIX.length()));
                nextActive[sideIndex] = indexOf(teams[sideIndex], uuid);
            }
        }
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            SearchAction action = actions[sideIndex];
            if ("item".equals(action.getKind())) {
                int[] selection = itemSelection(action.getId());
                BattlePokemon target = teams[sideIndex].members.get(selection[1]);
                int healing = healingAmount(teams[sideIndex].items.get(selection[0]).item.getItemName(), target.getMaxHealth());
                nextHp[sideIndex][selection[1]] = Math.min(
                        target.getMaxHealth(), nextHp[sideIndex][selection[1]] + healing);
                nextItemCounts[sideIndex][selection[0]] = Math.max(0, nextItemCounts[sideIndex][selection[0]] - 1);
                continue;
            }
            if (!"move".equals(action.getKind()) && !"gimmick".equals(action.getKind())) continue;
            int targetSide = 1 - sideIndex;
            int attackerIndex = nextActive[sideIndex];
            int defenderIndex = nextActive[targetSide];
            BattlePokemon attacker = teams[sideIndex].members.get(attackerIndex);
            BattlePokemon defender = teams[targetSide].members.get(defenderIndex);
            String moveId = underlyingMoveId(action.getId());
            Move move = attacker.getMoveSet().getMoves().stream()
                    .filter(entry -> entry.getName().equalsIgnoreCase(moveId)).findFirst().orElse(null);
            double damage = move == null ? action.getExpectedDamage() : PokeMath.damage(attacker, defender, move);
            if ("gimmick".equals(action.getKind())) damage *= gimmickMultiplier(gimmickId(action.getId()));
            nextHp[targetSide][defenderIndex] = Math.max(0, nextHp[targetSide][defenderIndex] - (int) Math.round(damage));
        }
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            if (nextHp[sideIndex][nextActive[sideIndex]] <= 0) {
                nextActive[sideIndex] = firstLiving(nextHp[sideIndex], nextActive[sideIndex]);
            }
        }
        boolean[] gimmicks = state.gimmicksRemaining.clone();
        if ("gimmick".equals(sideZeroAction.getKind())) gimmicks[0] = false;
        if ("gimmick".equals(sideOneAction.getKind())) gimmicks[1] = false;
        return new State(state.turn + 1, nextActive, nextHp, gimmicks, nextItemCounts);
    }

    @Override
    public double winProbability(String stateId, int sideIndex) {
        State state = requireState(stateId);
        int opponent = 1 - sideIndex;
        int ownLiving = living(state.hp[sideIndex]);
        int opponentLiving = living(state.hp[opponent]);
        String terminal = ownLiving == 0 ? "loss" : opponentLiving == 0 ? "win" : null;
        BattleValueState value = new BattleValueState(
                valueSide(state, sideIndex), valueSide(state, opponent), 0.0, 0.9, terminal);
        return SharedAiCore.INSTANCE.estimateWinProbability(value, 0.0, 1.0).getProbability();
    }

    @Override
    public boolean terminal(String stateId) {
        State state = requireState(stateId);
        return living(state.hp[0]) == 0 || living(state.hp[1]) == 0;
    }

    private BattleValueSide valueSide(State state, int sideIndex) {
        Team team = teams[sideIndex];
        int living = living(state.hp[sideIndex]);
        double totalHp = 0.0;
        for (int index = 0; index < team.members.size(); index++) {
            totalHp += ratio(state.hp[sideIndex][index], team.members.get(index).getMaxHealth());
        }
        int ace = team.aceIndex;
        boolean aceAlive = state.hp[sideIndex][ace] > 0;
        double aceHp = ratio(state.hp[sideIndex][ace], team.members.get(ace).getMaxHealth());
        double coverage = matchupCoverage(state, sideIndex);
        double readiness = living <= 1 ? 0.0 : totalHp / team.members.size();
        return new BattleValueSide(team.members.size(), living, totalHp, 1, aceAlive ? 1 : 0, aceHp,
                0.0, 0.0, 0.0, 0.0, state.gimmicksRemaining[sideIndex] ? 1.0 : 0.0,
                coverage, coverage, readiness, coverage * aceHp);
    }

    private double matchupCoverage(State state, int sideIndex) {
        BattlePokemon attacker = teams[sideIndex].members.get(state.active[sideIndex]);
        BattlePokemon defender = teams[1 - sideIndex].members.get(state.active[1 - sideIndex]);
        int hp = state.hp[1 - sideIndex][state.active[1 - sideIndex]];
        int best = attacker.getMoveSet().getMoves().stream().filter(move -> move.getCurrentPp() > 0)
                .mapToInt(move -> Math.max(0, PokeMath.damage(attacker, defender, move))).max().orElse(0);
        return Math.min(1.0, ratio(best, hp));
    }

    private static SearchAction action(String id, String kind, double score) {
        return new SearchAction(id, kind, score, 1.0, 0.0, false, false, false, 0.0, false);
    }

    private SearchAction findAction(String stateId, int sideIndex, String actionId) {
        return candidates(stateId, sideIndex).stream()
                .filter(action -> action.getId().equals(actionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 전투 AI 행동: " + actionId));
    }

    private String remember(State state) {
        String key = stateNamespace + ':' + state.turn + ':' + Arrays.toString(state.active) + ':'
                + Arrays.deepToString(state.hp) + ':' + Arrays.toString(state.gimmicksRemaining) + ':'
                + Arrays.deepToString(state.itemCounts);
        states.putIfAbsent(key, state);
        return key;
    }

    private State requireState(String stateId) {
        State state = states.get(stateId);
        if (state == null) throw new IllegalArgumentException("알 수 없는 전투 AI 상태: " + stateId);
        return state;
    }

    private State initialState() {
        int[][] hp = new int[2][];
        int[][] itemCounts = new int[2][];
        int[] active = new int[2];
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            Team team = teams[sideIndex];
            hp[sideIndex] = team.members.stream().mapToInt(BattlePokemon::getHealth).toArray();
            itemCounts[sideIndex] = team.items.stream().mapToInt(BattleItem::quantity).toArray();
            active[sideIndex] = team.activeIndex;
        }
        return new State(0, active, hp, new boolean[]{!rootGimmicks.isEmpty(), false}, itemCounts);
    }

    private static Team team(BattleSide side, ActiveBattlePokemon active) {
        BattleActor actor = active.getActor();
        List<BattlePokemon> members = actor.getPokemonList();
        int activeIndex = Math.max(0, members.indexOf(active.getBattlePokemon()));
        int aceIndex = 0;
        for (int index = 1; index < members.size(); index++) {
            if (members.get(index).getMaxHealth() > members.get(aceIndex).getMaxHealth()) aceIndex = index;
        }
        List<BattleItem> items = actor instanceof TrainerEntityBattleActor trainer
                ? trainer.getBag().getItems().stream()
                        .map(item -> new BattleItem(item, trainer.getBag().getQuanity(item)))
                        .filter(item -> item.quantity > 0)
                        .toList()
                : List.of();
        return new Team(List.copyOf(members), activeIndex, aceIndex, items);
    }

    private static List<RootMove> rootMoves(ActiveBattlePokemon active, ShowdownMoveset moveset) {
        List<RootMove> result = new ArrayList<>();
        for (int index = 0; index < moveset.getMoves().size(); index++) {
            InBattleMove move = moveset.getMoves().get(index);
            if (!move.canBeUsed()) continue;
            Move source = active.getBattlePokemon().getMoveSet().getMoves().stream()
                    .filter(entry -> entry.getName().equalsIgnoreCase(move.getId())).findFirst().orElse(null);
            if (source == null && index < active.getBattlePokemon().getMoveSet().getMoves().size()) {
                source = active.getBattlePokemon().getMoveSet().getMoves().get(index);
            }
            if (source == null) continue;
            Targetable target = move.getTargets(active).stream().findFirst().orElse(active.getOppositeOpponent());
            result.add(new RootMove(move.getId(), source, target));
        }
        return result;
    }

    private static String underlyingMoveId(String actionId) {
        int move = actionId.indexOf(MOVE_PREFIX);
        return move < 0 ? actionId : actionId.substring(move + MOVE_PREFIX.length());
    }

    private static String gimmickId(String actionId) {
        if (!actionId.startsWith(GIMMICK_PREFIX)) return null;
        int separator = actionId.indexOf(':', GIMMICK_PREFIX.length());
        return actionId.substring(GIMMICK_PREFIX.length(), separator);
    }

    private static int[] itemSelection(String actionId) {
        String[] parts = actionId.substring(ITEM_PREFIX.length()).split(":", 2);
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static int healingAmount(String itemName, int maxHealth) {
        String id = itemName.toLowerCase(Locale.ROOT).replace('-', '_');
        if (id.contains("full_restore") || id.contains("max_potion")) return maxHealth;
        if (id.contains("hyper_potion")) return 120;
        if (id.contains("super_potion")) return 60;
        if (id.endsWith("potion") || id.contains(":potion")) return 20;
        if (id.contains("berry_juice")) return 20;
        return 0;
    }

    private static double gimmickMultiplier(String gimmick) {
        if (gimmick == null) return 1.0;
        return switch (gimmick.toLowerCase(Locale.ROOT)) {
            case "zmove", "max" -> 1.35;
            case "mega", "ultra" -> 1.18;
            case "terastal" -> 1.20;
            default -> 1.12;
        };
    }

    private double moveBias() {
        return switch (strategy) {
            case "aggressive", "reckless_ace", "ace_check" -> 1.15;
            case "defensive", "hazard", "setup" -> 0.92;
            default -> 1.0;
        };
    }

    private double statusBias() {
        return switch (strategy) {
            case "setup", "hazard" -> 1.35;
            case "defensive" -> 1.18;
            case "aggressive", "reckless_ace" -> 0.72;
            default -> 1.0;
        };
    }

    private double switchBias() {
        return switch (strategy) {
            case "defensive", "tempo", "unpredictable" -> 1.22;
            case "aggressive", "reckless_ace", "setup" -> 0.78;
            default -> 1.0;
        };
    }

    private double gimmickBias() {
        return switch (strategy) {
            case "aggressive", "reckless_ace", "ace_check" -> 1.25;
            case "defensive" -> 0.85;
            default -> 1.0;
        };
    }

    private static int indexOf(Team team, UUID uuid) {
        for (int index = 0; index < team.members.size(); index++) {
            if (team.members.get(index).getUuid().equals(uuid)) return index;
        }
        return team.activeIndex;
    }

    private static int firstLiving(int[] hp, int fallback) {
        for (int index = 0; index < hp.length; index++) if (hp[index] > 0) return index;
        return fallback;
    }

    private static int living(int[] hp) {
        return (int) Arrays.stream(hp).filter(value -> value > 0).count();
    }

    private static int[][] copy(int[][] source) {
        return Arrays.stream(source).map(int[]::clone).toArray(int[][]::new);
    }

    private static double ratio(double numerator, double denominator) {
        return numerator / Math.max(1.0, denominator);
    }

    record PlannedResponse(ShowdownActionResponse response, SearchDecision decision) {}
    record State(int turn, int[] active, int[][] hp, boolean[] gimmicksRemaining, int[][] itemCounts) {}
    private record Team(List<BattlePokemon> members, int activeIndex, int aceIndex, List<BattleItem> items) {}
    private record BattleItem(BagItem item, int quantity) {}
    private record RootMove(String showdownId, Move move, Targetable target) {}
}
