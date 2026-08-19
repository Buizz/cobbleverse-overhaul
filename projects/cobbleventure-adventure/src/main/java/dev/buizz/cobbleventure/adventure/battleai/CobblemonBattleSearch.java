package dev.buizz.cobbleventure.adventure.battleai;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
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
import dev.buizz.cobbleventure.ai.core.BatonPassFactInput;
import dev.buizz.cobbleventure.ai.core.BatonPassFactResult;
import dev.buizz.cobbleventure.ai.core.BatonPassTargetObservation;
import dev.buizz.cobbleventure.ai.core.BattleValueMemberInput;
import dev.buizz.cobbleventure.ai.core.BattleValueSideInput;
import dev.buizz.cobbleventure.ai.core.BattleValueState;
import dev.buizz.cobbleventure.ai.core.ActionReachabilityInput;
import dev.buizz.cobbleventure.ai.core.ActionReachabilityResult;
import dev.buizz.cobbleventure.ai.core.CandidateAdjustment;
import dev.buizz.cobbleventure.ai.core.CandidateScoreFacts;
import dev.buizz.cobbleventure.ai.core.CounterMatchupInput;
import dev.buizz.cobbleventure.ai.core.EntryHazardObservation;
import dev.buizz.cobbleventure.ai.core.EntryHazardResult;
import dev.buizz.cobbleventure.ai.core.PreservedResourceFact;
import dev.buizz.cobbleventure.ai.core.ProjectedGimmickInput;
import dev.buizz.cobbleventure.ai.core.HazardLayerInput;
import dev.buizz.cobbleventure.ai.core.HazardLayerResult;
import dev.buizz.cobbleventure.ai.core.RecoveryFactInput;
import dev.buizz.cobbleventure.ai.core.RecoveryFactResult;
import dev.buizz.cobbleventure.ai.core.ResidualPressureInput;
import dev.buizz.cobbleventure.ai.core.ResidualPressureResult;
import dev.buizz.cobbleventure.ai.core.RoleProgressInput;
import dev.buizz.cobbleventure.ai.core.RoleProgressResult;
import dev.buizz.cobbleventure.ai.core.RuleFactBag;
import dev.buizz.cobbleventure.ai.core.SearchAction;
import dev.buizz.cobbleventure.ai.core.SearchDecision;
import dev.buizz.cobbleventure.ai.core.SearchRuntime;
import dev.buizz.cobbleventure.ai.core.SharedProjectedSearchAction;
import dev.buizz.cobbleventure.ai.core.SharedSearchCandidateGenerator;
import dev.buizz.cobbleventure.ai.core.SharedSearchCandidateObservation;
import dev.buizz.cobbleventure.ai.core.SharedSearchCombatProfile;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldMoveCatalog;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldMoveEffect;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldCombatEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldCombatInput;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldCombatResult;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldState;
import dev.buizz.cobbleventure.ai.core.SharedSearchPressure;
import dev.buizz.cobbleventure.ai.core.SharedSearchProjectionRuntime;
import dev.buizz.cobbleventure.ai.core.SharedSearchProjectionState;
import dev.buizz.cobbleventure.ai.core.SharedSearchTimedEffect;
import dev.buizz.cobbleventure.ai.core.SharedCandidateEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedBattleObservation;
import dev.buizz.cobbleventure.ai.core.SharedBattleRankProjection;
import dev.buizz.cobbleventure.ai.core.SharedBattleStats;
import dev.buizz.cobbleventure.ai.core.SharedDamageCalculator;
import dev.buizz.cobbleventure.ai.core.SharedDamageFactorsEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedDamageFactorsInput;
import dev.buizz.cobbleventure.ai.core.SharedDamageInput;
import dev.buizz.cobbleventure.ai.core.SharedDamageModifierInput;
import dev.buizz.cobbleventure.ai.core.SharedDamageModifierMove;
import dev.buizz.cobbleventure.ai.core.SharedDamageModifierPokemon;
import dev.buizz.cobbleventure.ai.core.SharedDamageStatEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedDamageStatInput;
import dev.buizz.cobbleventure.ai.core.SharedDamageTypeInput;
import dev.buizz.cobbleventure.ai.core.SharedDamageTypeMove;
import dev.buizz.cobbleventure.ai.core.SharedDamageTypePokemon;
import dev.buizz.cobbleventure.ai.core.SharedDamageTypeEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedEffectiveStatPokemon;
import dev.buizz.cobbleventure.ai.core.SharedBatonPassFactDeriver;
import dev.buizz.cobbleventure.ai.core.SharedActionReachabilityEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedGimmickEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedEntryMoveObservation;
import dev.buizz.cobbleventure.ai.core.SharedHitReaction;
import dev.buizz.cobbleventure.ai.core.SharedHitReactionEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedHitReactionInput;
import dev.buizz.cobbleventure.ai.core.SharedMoveFactEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedPostHitEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedPostHitInstruction;
import dev.buizz.cobbleventure.ai.core.SharedPreservationEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedRoleProgressEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedSetupThreatEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedSustainmentFactDeriver;
import dev.buizz.cobbleventure.ai.core.SharedSwitchFactEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedSwitchPhaseEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedSwitchPhaseInput;
import dev.buizz.cobbleventure.ai.core.SharedSwitchPhaseResult;
import dev.buizz.cobbleventure.ai.core.SharedSwitchMatchupEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedTeamRoleEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedAiCore;
import dev.buizz.cobbleventure.ai.core.SetupThreatResult;
import dev.buizz.cobbleventure.ai.core.SaltCureDamageInput;
import dev.buizz.cobbleventure.ai.core.ThreatCounterInput;
import dev.buizz.cobbleventure.ai.core.ThreatCounterResult;
import dev.buizz.cobbleventure.ai.core.ThreatObservationInput;
import dev.buizz.cobbleventure.ai.core.TrainerItemCandidateFacts;
import dev.buizz.cobbleventure.ai.core.SwitchMatchupFacts;
import dev.buizz.cobbleventure.ai.core.SwitchMatchupEvaluation;
import dev.buizz.cobbleventure.ai.core.SwitchMatchupObservation;
import dev.buizz.cobbleventure.ai.core.SwitchMatchupResult;
import dev.buizz.cobbleventure.ai.core.TeamMemberRoleResult;
import dev.buizz.cobbleventure.ai.core.TeamRoleInput;
import dev.buizz.cobbleventure.ai.core.TeamRoleMemberInput;
import dev.buizz.cobbleventure.ai.core.TeamRoleResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Cobblemon 공개 전투 상태를 웹 실험실과 동일한 멀티플랫폼 코어에 투영한다. */
final class CobblemonBattleSearch implements SearchRuntime {
    private static final String MOVE_PREFIX = "move:";
    private static final String SWITCH_PREFIX = "switch:";
    private static final String GIMMICK_PREFIX = "gimmick:";
    private static final String ITEM_PREFIX = "item:";
    private static final int HAZARD_STEALTH_ROCK = 0;
    private static final int HAZARD_SPIKES = 1;
    private static final int HAZARD_TOXIC_SPIKES = 2;
    private static final int HAZARD_STICKY_WEB = 3;
    private static final int HAZARD_COUNT = 4;
    private static final int RANK_ATTACK = SharedBattleRankProjection.ATTACK;
    private static final int RANK_SPECIAL_ATTACK = SharedBattleRankProjection.SPECIAL_ATTACK;
    private static final int RANK_DEFENCE = SharedBattleRankProjection.DEFENCE;
    private static final int RANK_SPECIAL_DEFENCE = SharedBattleRankProjection.SPECIAL_DEFENCE;
    private static final int RANK_SPEED = SharedBattleRankProjection.SPEED;
    private static final int RANK_COUNT = SharedBattleRankProjection.COUNT;

    private final Team[] teams;
    private final List<RootMove> rootMoves;
    private final List<String> rootGimmicks;
    private final String difficulty;
    private final String strategy;
    private final boolean forceSwitch;
    private final ShowdownBattleLogObservation initialObservation;
    private final String stateNamespace = UUID.randomUUID().toString();
    private final Map<String, State> states = new HashMap<>();

    private CobblemonBattleSearch(
            Team[] teams,
            List<RootMove> rootMoves,
            List<String> rootGimmicks,
            String difficulty,
            String strategy,
            boolean forceSwitch,
            ShowdownBattleLogObservation initialObservation
    ) {
        this.teams = teams;
        this.rootMoves = rootMoves;
        this.rootGimmicks = rootGimmicks;
        this.difficulty = difficulty;
        this.strategy = strategy;
        this.forceSwitch = forceSwitch;
        this.initialObservation = initialObservation;
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
        CobblemonBattleSearch model = new CobblemonBattleSearch(
                new Team[]{own, enemy}, rootMoves, gimmicks, difficulty, strategy, forceSwitch,
                ShowdownBattleLogObservation.parse(active.getBattle().getBattleLog()));
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
            decision = SharedAiCore.INSTANCE.decideTwoTurn(
                    stateId, 0, 10, model, null, null, model.stateNamespace);
        }
        if (decision == null || decision.getSelected() == null) return null;
        ShowdownActionResponse response = model.toResponse(decision.getSelected(), opposite);
        UUID batonTarget = null;
        String selectedMove = underlyingMoveId(decision.getSelected().getId())
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (selectedMove.equals("batonpass")) {
            int targetIndex = model.batonPassTargetIndex(state, 0, state.active[0]);
            if (targetIndex >= 0) batonTarget = own.members.get(targetIndex).getUuid();
        }
        return response == null ? null : new PlannedResponse(response, decision, batonTarget);
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
        AttackProfile currentAttack = bestAttackProfile(
                state, sideIndex, activeIndex, 1 - sideIndex, state.active[1 - sideIndex]);
        AttackProfile currentIncoming = bestAttackProfile(
                state, 1 - sideIndex, state.active[1 - sideIndex], sideIndex, activeIndex);
        double currentBestDamage = currentAttack.damage;
        double currentIncomingDamage = currentIncoming.damage;
        SetupThreatResult setupThreat = setupThreat(
                state, sideIndex, attacker, defender, defenderHp, currentBestDamage, state.turn + 1);
        ThreatCounterResult preservation = preservationFacts(state, sideIndex);
        List<RoleProgressResult> roleProgress = roleProgressFacts(state, sideIndex, preservation);
        RoleProgressResult activeRoleProgress = roleProgress.get(activeIndex);
        boolean activeMustPreserve = preservedResource(preservation, activeIndex + 1) != null;
        PressureState activePressure = state.pressures[sideIndex][activeIndex];
        ResidualPressureResult residualPressure = SharedSustainmentFactDeriver.INSTANCE.residualPressure(
                new ResidualPressureInput(
                        state.hp[sideIndex][activeIndex], attacker.getMaxHealth(),
                        activePressure.yawn, activePressure.yawnTurns, false,
                        activePressure.saltCure,
                        SharedSustainmentFactDeriver.INSTANCE.saltCureDamage(
                                new SaltCureDamageInput(attacker.getMaxHealth(), isWaterOrSteel(attacker))),
                        activePressure.toxicCounter, false));
        boolean actualForceSwitch = sideIndex == 0 && forceSwitch;
        List<SearchAction> result = new ArrayList<>();

        if (sideIndex == 0 && state.turn == 0) {
            for (RootMove root : rootMoves) {
                SearchAction baseAction = moveAction(
                        root.showdownId, root.move, attacker, state.hp[sideIndex][activeIndex],
                        defender, defenderHp, setupThreat, activeMustPreserve, residualPressure,
                        activeRoleProgress, null, state, sideIndex);
                result.add(baseAction);
                for (String gimmick : rootGimmicks) {
                    SearchAction gimmickAction = moveAction(
                            root.showdownId, root.move, attacker, state.hp[sideIndex][activeIndex],
                            defender, defenderHp, setupThreat, activeMustPreserve, residualPressure,
                            activeRoleProgress, gimmick, state, sideIndex);
                    boolean viable = SharedGimmickEvaluator.INSTANCE.score(new ProjectedGimmickInput(
                            gimmick,
                            gimmickAction.getScore(),
                            baseAction.getScore(),
                            null,
                            null,
                            false,
                            null)).getViable();
                    if (viable) result.add(gimmickAction);
                }
            }
        } else {
            for (Move move : movesFor(state, sideIndex, activeIndex)) {
                if (move.getCurrentPp() > 0) result.add(moveAction(
                        move.getName(), move, attacker, state.hp[sideIndex][activeIndex],
                        defender, defenderHp, setupThreat, activeMustPreserve, residualPressure,
                        activeRoleProgress, null, state, sideIndex));
            }
        }
        for (int index = 0; index < team.members.size(); index++) {
            if (index == activeIndex || state.hp[sideIndex][index] <= 0) continue;
            final int candidateIndex = index;
            BattlePokemon candidate = team.members.get(index);
            double hpRatio = ratio(state.hp[sideIndex][index], candidate.getMaxHealth());
            AttackProfile targetAttack = bestAttackProfile(
                    state, sideIndex, candidateIndex, 1 - sideIndex, state.active[1 - sideIndex]);
            AttackProfile targetIncoming = bestAttackProfile(
                    state, 1 - sideIndex, state.active[1 - sideIndex], sideIndex, candidateIndex);
            double projectedDamage = targetAttack.damage;
            double incomingDamage = targetIncoming.damage;
            EntryHazardResult entryHazards = SharedSwitchMatchupEvaluator.INSTANCE.entryHazardDamage(
                    new EntryHazardObservation(
                            state.hp[sideIndex][index], candidate.getMaxHealth(),
                            state.hazards[sideIndex][HAZARD_STEALTH_ROCK],
                            state.hazards[sideIndex][HAZARD_SPIKES],
                            1.0, pokemonTypes(candidate), pokemonAbility(candidate), pokemonItem(candidate),
                            null, null));
            hpRatio = ratio(entryHazards.getHpAfterHazards(), candidate.getMaxHealth());
            SwitchMatchupEvaluation switchEvaluation = SharedSwitchMatchupEvaluator.INSTANCE.derive(
                    new SwitchMatchupObservation(
                            state.hp[sideIndex][activeIndex], attacker.getMaxHealth(),
                            state.hp[sideIndex][index], candidate.getMaxHealth(), defenderHp,
                            currentIncomingDamage,
                            incomingDamage,
                            currentBestDamage,
                            projectedDamage,
                            entryHazards.getDamage(),
                            currentAttack.priority,
                            currentIncoming.priority,
                            projectedSpeed(state, sideIndex, activeIndex),
                            projectedSpeed(state, 1 - sideIndex, state.active[1 - sideIndex]),
                            state.field.getPseudoWeather().containsKey("trickroom"), false, false));
            SwitchMatchupFacts switchFacts = switchEvaluation.getFacts();
            SwitchMatchupResult switchMatchup = switchEvaluation.getResult();
            double incomingRatio = switchFacts.getTargetIncomingDamageRatio();
            double outgoingRatio = switchFacts.getTargetOutgoingDamageRatio();
            double currentIncomingRatio = switchFacts.getCurrentIncomingDamageRatio();
            double currentOutgoingRatio = switchFacts.getCurrentOutgoingDamageRatio();
            boolean currentCanReachAction = switchFacts.getCurrentCanReachAction();
            List<CandidateAdjustment> switchRules = SharedSwitchFactEvaluator.INSTANCE.adjustments(
                    new RuleFactBag(
                            "switch",
                            Map.ofEntries(
                                    Map.entry("hpPercent", hpRatio),
                                    Map.entry("targetIncomingDamageRatio", incomingRatio),
                                    Map.entry("targetOutgoingDamageRatio", outgoingRatio),
                                    Map.entry("switchInDamageRatio", incomingRatio),
                                    Map.entry("currentIncomingDamageRatio", currentIncomingRatio),
                                    Map.entry("currentOutgoingDamageRatio", currentOutgoingRatio),
                                    Map.entry("currentBestMoveScore", currentBestDamage),
                                    Map.entry("stayPressurePenalty", residualPressure.getStayPressurePenalty()),
                                    Map.entry("setupThreatTier", (double) setupThreat.getRiskTier())),
                            Map.ofEntries(
                                    Map.entry("forceSwitch", actualForceSwitch),
                                    Map.entry("safeTwoHitHold", incomingRatio * 2.0 < hpRatio),
                                    Map.entry("safeImmediateKoAvailable", currentBestDamage >= defenderHp),
                                    Map.entry("currentCanReachAction", currentCanReachAction),
                                    Map.entry("emergencyEscape", switchMatchup.getEmergencyEscape()),
                                    Map.entry("canReachNextAction", incomingDamage < entryHazards.getHpAfterHazards()),
                                    Map.entry("survivesSwitchIn", incomingDamage < entryHazards.getHpAfterHazards()),
                                    Map.entry("canKoOnNextAction", projectedDamage >= defenderHp),
                                    Map.entry("speedAdvantage",
                                            projectedSpeed(state, sideIndex, index)
                                                    > projectedSpeed(state, 1 - sideIndex,
                                                            state.active[1 - sideIndex])),
                                    Map.entry("priorityKo", false),
                                    Map.entry("mustPreserveResource",
                                            preservedResource(preservation, index + 1) != null),
                                    Map.entry("preservationTargetIsCurrent",
                                            preservesCurrentThreat(preservation, index + 1,
                                                    state.active[1 - sideIndex] + 1)),
                                    Map.entry("targetAceQualified", team.roleAnalysis.getRoles().get(index)
                                            .getAceProfile().getQualifies()),
                                    Map.entry("targetRoleComplete", roleProgress.get(index).getRoleComplete()),
                                    Map.entry("targetExpendableResource",
                                            roleProgress.get(index).getExpendableResource())),
                            Map.of("strategy", strategy),
                            java.util.Set.of(),
                            Map.of()));
            double score = sharedScore(new CandidateScoreFacts(
                    "switch", difficulty, strategy, projectedDamage, 0.0, null, true, 0.0,
                    false, 0.0, 0.0, "none", hpRatio, switchMatchup.getMatchupValue(), 0.0, switchRules));
            result.add(action(SWITCH_PREFIX + candidate.getUuid(), "switch", score));
        }
        int remainingItemUses = Arrays.stream(state.itemCounts[sideIndex]).sum();
        double itemResourceCost = remainingItemUses <= 1 ? 18.0 : remainingItemUses == 2 ? 10.0 : 6.0;
        boolean strongMoveAvailable = result.stream()
                .filter(candidate -> "move".equals(candidate.getKind()) || "gimmick".equals(candidate.getKind()))
                .anyMatch(candidate -> candidate.getScore() >= 180.0);
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
                double incomingDamage = targetIndex == activeIndex ? currentIncomingDamage : 0.0;
                double hpAfter = Math.min(target.getMaxHealth(), state.hp[sideIndex][targetIndex] + restored);
                boolean preventsImmediateKnockout = incomingDamage >= state.hp[sideIndex][targetIndex]
                        && incomingDamage < hpAfter;
                String itemId = item.item.getItemName().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                double score = SharedCandidateEvaluator.INSTANCE.scoreTrainerItem(
                        new TrainerItemCandidateFacts(
                                restored,
                                0.0,
                                preventsImmediateKnockout,
                                incomingDamage,
                                0.0,
                                itemResourceCost,
                                hpAfter - incomingDamage <= 0.0
                                        && incomingDamage >= state.hp[sideIndex][targetIndex],
                                itemId.equals("potion") && restored < 20.0,
                                strongMoveAvailable,
                                healing > 0 ? 0.0 : 22.0)).getScore();
                result.add(action(ITEM_PREFIX + itemIndex + ':' + targetIndex, "item", score));
            }
        }
        List<SearchAction> generated = SharedSearchCandidateGenerator.INSTANCE.generate(
                result.stream().map(CobblemonBattleSearch::candidateObservation).toList());
        List<SharedProjectedSearchAction> projected = generated.stream()
                .map(action -> projectedAction(state, sideIndex, action))
                .toList();
        return SharedSearchProjectionRuntime.INSTANCE
                .legalCandidates(toProjection(state), sideIndex, projected).stream()
                .map(SharedProjectedSearchAction::getAction)
                .toList();
    }

    private static SharedSearchCandidateObservation candidateObservation(SearchAction action) {
        return new SharedSearchCandidateObservation(
                action.getId(), action.getKind(), action.getScore(), action.getSuccessProbability(),
                action.getExpectedDamage(), action.getNonConsecutive(), action.getStatusMove(),
                action.getGuaranteedKnockout(), action.getOpponentKnockoutBeforeActionProbability(),
                action.getHeuristicSelected(), true, false);
    }

    private SearchAction moveAction(
            String moveId, Move move, BattlePokemon attacker, int attackerHp, BattlePokemon defender,
            int defenderHp, SetupThreatResult setupThreat, boolean mustPreserveResource,
            ResidualPressureResult residualPressure, RoleProgressResult roleProgress,
            String gimmick, State state, int sideIndex
    ) {
        int attackerIndex = state.active[sideIndex];
        int defenderIndex = state.active[1 - sideIndex];
        double baseDamage = projectedDamage(
                state, sideIndex, attackerIndex, 1 - sideIndex, defenderIndex, move);
        double multiplier = gimmickMultiplier(gimmick);
        double damage = Math.min(defenderHp, baseDamage * multiplier);
        boolean status = move.getPower() <= 0.0;
        double accuracy = move.getAccuracy() <= 0.0 ? 1.0 : Math.min(1.0, move.getAccuracy() / 100.0);
        String normalized = moveId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        BatonPassFactResult batonPass = batonPassFacts(state, sideIndex, attacker, normalized);
        double incomingDamage = movesFor(state, 1 - sideIndex, defenderIndex).stream()
                .filter(entry -> entry.getCurrentPp() > 0)
                .mapToDouble(entry -> projectedDamage(
                        state, 1 - sideIndex, defenderIndex, sideIndex, attackerIndex, entry))
                .max().orElse(0.0);
        double incomingRatio = ratio(incomingDamage, attacker.getMaxHealth());
        double opponentPriority = movesFor(state, 1 - sideIndex, defenderIndex).stream()
                .filter(entry -> entry.getCurrentPp() > 0)
                .mapToDouble(entry -> entry.getTemplate().getPriority())
                .max().orElse(0.0);
        ActionReachabilityResult reachability = SharedActionReachabilityEvaluator.INSTANCE.evaluate(
                new ActionReachabilityInput(
                        move.getTemplate().getPriority(), opponentPriority,
                        projectedSpeed(state, sideIndex, attackerIndex)
                                > projectedSpeed(state, 1 - sideIndex, defenderIndex), null,
                        (double) attackerHp, incomingDamage, null, null, null, false));
        boolean guaranteedKo = damage >= defenderHp;
        boolean safeImmediateKo = movesFor(state, sideIndex, attackerIndex).stream()
                .filter(entry -> entry.getCurrentPp() > 0)
                .filter(entry -> entry.getAccuracy() <= 0.0 || entry.getAccuracy() >= 100.0)
                .anyMatch(entry -> projectedDamage(
                        state, sideIndex, attackerIndex, 1 - sideIndex, defenderIndex, entry) >= defenderHp);
        boolean recoveryMove = isRecoveryMove(normalized);
        boolean pivotMove = isPivotMove(normalized);
        boolean restMove = normalized.equals("rest");
        RecoveryFactResult recovery = SharedSustainmentFactDeriver.INSTANCE.recovery(
                new RecoveryFactInput(
                        attackerHp,
                        attacker.getMaxHealth(),
                        recoveryMove && !restMove ? 0.5 : 0.0,
                        restMove,
                        restMove ? 3 : 1,
                        incomingDamage));
        int hazardIndex = hazardIndex(normalized);
        Map<String, Double> projectedSelfBoosts = normalized.equals("curse") && isGhost(attacker)
                ? Map.of()
                : SharedSetupThreatEvaluator.INSTANCE.projectedSelfBoosts(normalized);
        double effectiveSelfBoostTotal = projectedSelfBoosts.values().stream()
                .mapToDouble(value -> Math.max(0.0, value)).sum();
        int existingHazardLayers = hazardIndex < 0 ? 0 : state.hazards[1 - sideIndex][hazardIndex];
        HazardLayerResult hazard = SharedSustainmentFactDeriver.INSTANCE.hazard(
                new HazardLayerInput(normalized, existingHazardLayers));
        PressureState defenderPressure = state.pressures[1 - sideIndex][state.active[1 - sideIndex]];
        double saltCureDamage = SharedSustainmentFactDeriver.INSTANCE.saltCureDamage(
                new SaltCureDamageInput(defender.getMaxHealth(), isWaterOrSteel(defender)));
        Set<String> tags = hazardIndex >= 0 && !projectedSelfBoosts.isEmpty()
                ? Set.of("hazardset", "setupboost")
                : hazardIndex >= 0
                        ? Set.of("hazardset")
                        : !projectedSelfBoosts.isEmpty() ? Set.of("setupboost") : Set.of();
        List<CandidateAdjustment> moveRules = SharedMoveFactEvaluator.INSTANCE.adjustments(
                new RuleFactBag(
                        "move",
                        Map.ofEntries(
                                Map.entry("hpPercent", ratio(attackerHp, attacker.getMaxHealth())),
                                Map.entry("healthRatio", ratio(attackerHp, attacker.getMaxHealth())),
                                Map.entry("incomingDamageRatio", incomingRatio),
                                Map.entry("stayPressurePenalty", residualPressure.getStayPressurePenalty()),
                                Map.entry("yawnSwitchPressure", residualPressure.getYawnSwitchPressure()),
                                Map.entry("saltCureSwitchPressure", residualPressure.getSaltCureSwitchPressure()),
                                Map.entry("toxicSwitchPressure", residualPressure.getToxicSwitchPressure()),
                                Map.entry("currentIncomingDamageRatio", incomingRatio),
                                Map.entry("opponentMaxDamageToCurrentHealthRatio", incomingRatio),
                                Map.entry("opponentKnockoutBeforeActionProbability",
                                        reachability.getKnockoutBeforeActionProbability()),
                                Map.entry("opponentHp", (double) defenderHp),
                                Map.entry("opponentMaxHp", (double) defender.getMaxHealth()),
                                Map.entry("expectedDamage", damage),
                                Map.entry("priority", (double) move.getTemplate().getPriority()),
                                Map.entry("computed.setupThreatTier", (double) setupThreat.getRiskTier()),
                                Map.entry("computed.opponentSetupLikelihood", setupThreat.getSetupLikelihood()),
                                Map.entry("setupThreatEvaluation.sweepRiskAfterSetup",
                                        setupThreat.getSweepRiskAfterSetup()),
                                Map.entry("setupThreatEvaluation.freeTurnPenalty",
                                        setupThreat.getFreeTurnPenalty()),
                                Map.entry("livingOpponents", (double) living(state.hp[1 - sideIndex])),
                                Map.entry("existingHazardLayers", (double) existingHazardLayers),
                                Map.entry("hazardLayerDelta", (double) hazard.getLayerDelta()),
                                Map.entry("batonPassCurrentBoostTotal", batonPass.getBatonPassCurrentBoostTotal()),
                                Map.entry("batonPassCurrentSweepBoostTotal",
                                        batonPass.getBatonPassCurrentSweepBoostTotal()),
                                Map.entry("batonPassCurrentDefensiveBoostTotal",
                                        batonPass.getBatonPassCurrentDefensiveBoostTotal()),
                                Map.entry("batonPassBoostTotal", batonPass.getBatonPassBoostTotal()),
                                Map.entry("batonPassAdditionalBoostTotal",
                                        batonPass.getBatonPassAdditionalBoostTotal()),
                                Map.entry("batonPassNewKoTargets", (double) batonPass.getBatonPassNewKoTargets()),
                                Map.entry("batonPassSafeKoTargets", (double) batonPass.getBatonPassSafeKoTargets()),
                                Map.entry("batonPassPressureGain", batonPass.getBatonPassPressureGain()),
                                Map.entry("batonPassTransferValue", batonPass.getBatonPassTransferValue()),
                                Map.entry("setupFollowupSurvivalProbability",
                                        reachability.getSurvivalProbability()),
                                Map.entry("effectiveSelfBoostTotal", effectiveSelfBoostTotal),
                                Map.entry("opponentHazards.stealthrock",
                                        (double) state.hazards[1 - sideIndex][HAZARD_STEALTH_ROCK]),
                                Map.entry("saltCureResidualDamage", saltCureDamage),
                                Map.entry("recoveryExposureTurns", (double) recovery.getRecoveryExposureTurns()),
                                Map.entry("recoveryBeforeActionKoRisk",
                                        incomingDamage >= attackerHp && move.getTemplate().getPriority() <= 0
                                                ? 1.0 : 0.0),
                                Map.entry("recoveryAmount", recovery.getRecoveryAmount()),
                                Map.entry("recoveryExpectedIncomingDamage",
                                        recovery.getRecoveryExpectedIncomingDamage()),
                                Map.entry("recoveryNetHpChange", recovery.getRecoveryNetHpChange()),
                                Map.entry("survivalProbability", reachability.getSurvivalProbability())),
                        Map.ofEntries(
                                Map.entry("computed.hasSafeImmediateKo", safeImmediateKo),
                                Map.entry("computed.safeFinisher", guaranteedKo && accuracy >= 1.0),
                                Map.entry("computed.isDamage", !status),
                                Map.entry("urgentSwitchPressure", residualPressure.getUrgentSwitchPressure()),
                                Map.entry("computed.highValueHazard",
                                        hazardIndex >= 0 && hazard.getLayerDelta() > 0
                                                && living(state.hp[1 - sideIndex]) > 1),
                                Map.entry("computed.saltCureActive", defenderPressure.saltCure),
                                Map.entry("batonPassTargetAvailable", batonPass.getBatonPassTargetAvailable()),
                                Map.entry("batonPassTargetAce", batonPass.getBatonPassTargetAce()),
                                Map.entry("batonPassCanRaiseSweepFurther",
                                        batonPass.getBatonPassCanRaiseSweepFurther()),
                                Map.entry("batonPassCanRaiseDefenseFurther",
                                        batonPass.getBatonPassCanRaiseDefenseFurther()),
                                Map.entry("computed.actsBefore", reachability.getActsBefore()),
                                Map.entry("setupCanSurviveIncoming", reachability.getCanReachNextAction()),
                                Map.entry("computed.knockoutBoostAlternative",
                                        !status && !projectedSelfBoosts.isEmpty() && guaranteedKo),
                                Map.entry("computed.recoveryMove", recoveryMove),
                                Map.entry("computed.opponentLikelyToSetup",
                                        setupThreat.getOpponentCanSetup()
                                                && (setupThreat.getSetupLikelihood() >= 0.55
                                                || setupThreat.getRiskTier() >= 2)),
                                Map.entry("setupThreatEvaluation.opponentCanSetup",
                                        setupThreat.getOpponentCanSetup()),
                                Map.entry("oneMoreTurnUnmanageable",
                                        setupThreat.getOneMoreTurnUnmanageable()),
                                Map.entry("computed.pivotMove", pivotMove),
                                Map.entry("computed.hasLivingBench", hasLivingBench(attacker)),
                                Map.entry("computed.selfSacrifice", isSelfSacrificeMove(normalized)),
                                Map.entry("meaningfulSacrificeDamage", damage >= defenderHp * 0.6),
                                Map.entry("mustPreserveResource", mustPreserveResource),
                                Map.entry("expendableResource", roleProgress.getExpendableResource()),
                                Map.entry("roleComplete", roleProgress.getRoleComplete()),
                                Map.entry("canReachNextAction", reachability.getCanReachNextAction()),
                                Map.entry("safePivot", reachability.getSafePivot()),
                                Map.entry("forceSwitch", false)),
                        Map.ofEntries(
                                Map.entry("id", normalized),
                                Map.entry("moveId", normalized),
                                Map.entry("category", status ? "Status" : "Damage"),
                                Map.entry("koChance", guaranteedKo ? "guaranteed" : "none"),
                                Map.entry("strategy", strategy)),
                        tags,
                        Map.of()));
        double score = sharedScore(new CandidateScoreFacts(
                "move", difficulty, strategy, damage, move.getPower(), move.getAccuracy(),
                move.getAccuracy() <= 0.0, move.getTemplate().getPriority(), status, 0.0, 0.0,
                guaranteedKo ? "guaranteed" : "none",
                0.0, 0.0, 0.0, moveRules));
        String baseId = MOVE_PREFIX + moveId;
        String actionId = gimmick == null ? baseId : GIMMICK_PREFIX + gimmick + ':' + baseId;
        boolean nonConsecutive = normalized.equals("gigaimpact") || normalized.equals("hyperbeam")
                || normalized.equals("blastburn") || normalized.equals("frenzyplant") || normalized.equals("hydrocannon");
        return new SearchAction(actionId, gimmick == null ? "move" : "gimmick", score, accuracy,
                damage, nonConsecutive, status, damage >= defenderHp, 0.0, false);
    }

    private SetupThreatResult setupThreat(
            State state,
            int sideIndex,
            BattlePokemon attacker,
            BattlePokemon defender,
            int defenderHp,
            double bestImmediateDamage,
            int turn
    ) {
        List<String> setupMoveIds = movesFor(state, 1 - sideIndex, state.active[1 - sideIndex]).stream()
                .filter(move -> move.getCurrentPp() > 0)
                .map(move -> move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .filter(SharedSetupThreatEvaluator.INSTANCE::isKnownSetupMove)
                .toList();
        double damageRatio = ratio(bestImmediateDamage, defenderHp);
        double hpRatio = ratio(defenderHp, defender.getMaxHealth());
        double likelihood = setupMoveIds.isEmpty() ? 0.0
                : SharedSetupThreatEvaluator.INSTANCE.likelihood(
                        turn, damageRatio, hpRatio, 0.0, false);
        List<String> punishOptions = movesFor(state, sideIndex, state.active[sideIndex]).stream()
                .filter(move -> move.getCurrentPp() > 0)
                .map(move -> move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .filter(id -> List.of("haze", "clearsmog", "roar", "whirlwind", "dragontail",
                        "circlethrow", "taunt", "encore").contains(id))
                .toList();
        return SharedSetupThreatEvaluator.INSTANCE.evaluateObserved(
                setupMoveIds,
                likelihood,
                0.0,
                0.0,
                false,
                hpRatio,
                damageRatio,
                0.0,
                0.0,
                0.0,
                punishOptions);
    }

    private BatonPassFactResult batonPassFacts(
            State state,
            int sideIndex,
            BattlePokemon attacker,
            String projectedMoveId
    ) {
        boolean available = movesFor(state, sideIndex, state.active[sideIndex]).stream()
                .filter(move -> move.getCurrentPp() > 0)
                .map(move -> move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .anyMatch("batonpass"::equals);
        if (!available) return SharedBatonPassFactDeriver.INSTANCE.derive(new BatonPassFactInput(
                false, false, 0, "", false, Map.of(), Map.of(), false, false, List.of()));

        Team team = teams[sideIndex];
        int activeIndex = state.active[sideIndex];
        int targetIndex = batonPassTargetIndex(state, sideIndex, activeIndex);
        if (targetIndex < 0) {
            return SharedBatonPassFactDeriver.INSTANCE.derive(new BatonPassFactInput(
                    true, false, 0, "", false, Map.of(), Map.of(), false, false, List.of()));
        }

        int[] currentRanks = state.ranks[sideIndex][activeIndex];
        int[] passedRanks = currentRanks.clone();
        Map<String, Double> projectedChanges = projectedMoveId.equals("curse") && isGhost(attacker)
                ? Map.of()
                : SharedSetupThreatEvaluator.INSTANCE.projectedSelfBoosts(projectedMoveId);
        SharedBattleRankProjection.INSTANCE.apply(passedRanks, projectedChanges);
        Map<String, Double> boosts = transferableBoosts(currentRanks);
        Map<String, Double> passedBoosts = transferableBoosts(passedRanks);
        BattlePokemon target = team.members.get(targetIndex);
        List<BatonPassTargetObservation> projections = new ArrayList<>();
        for (int enemyIndex = 0; enemyIndex < teams[1 - sideIndex].members.size(); enemyIndex++) {
            if (state.hp[1 - sideIndex][enemyIndex] <= 0) continue;
            BattlePokemon enemy = teams[1 - sideIndex].members.get(enemyIndex);
            double baseline = 0.0;
            double boosted = 0.0;
            for (Move move : target.getMoveSet().getMoves()) {
                if (move.getCurrentPp() <= 0 || move.getPower() <= 0.0) continue;
                double damage = projectedDamage(
                        state, sideIndex, targetIndex, 1 - sideIndex, enemyIndex, move);
                String category = move.getDamageCategory().getName().toLowerCase(Locale.ROOT);
                double passedStage = category.equals("physical")
                        ? passedBoosts.getOrDefault("attack", 0.0)
                        : category.equals("special")
                                ? passedBoosts.getOrDefault("specialAttack", 0.0)
                                : 0.0;
                double targetStage = category.equals("physical")
                        ? state.ranks[sideIndex][targetIndex][RANK_ATTACK]
                        : category.equals("special")
                                ? state.ranks[sideIndex][targetIndex][RANK_SPECIAL_ATTACK]
                                : 0.0;
                baseline = Math.max(baseline, damage);
                boosted = Math.max(boosted,
                        damage * SharedBattleRankProjection.INSTANCE.multiplier(passedStage)
                                / SharedBattleRankProjection.INSTANCE.multiplier(targetStage));
            }
            projections.add(new BatonPassTargetObservation(
                    state.hp[1 - sideIndex][enemyIndex], baseline, boosted));
        }
        return SharedBatonPassFactDeriver.INSTANCE.derive(new BatonPassFactInput(
                true,
                true,
                targetIndex + 1,
                target.getUuid().toString(),
                targetIndex == team.aceIndex,
                boosts,
                passedBoosts,
                canRaiseFurther(attacker, passedRanks, "attack", "specialAttack", "speed"),
                canRaiseFurther(attacker, passedRanks, "defence", "specialDefence"),
                projections));
    }

    private int batonPassTargetIndex(State state, int sideIndex, int activeIndex) {
        Team team = teams[sideIndex];
        int targetIndex = team.aceIndex;
        if (targetIndex != activeIndex && state.hp[sideIndex][targetIndex] > 0) return targetIndex;
        targetIndex = -1;
        int bestHealth = -1;
        for (int index = 0; index < team.members.size(); index++) {
            if (index == activeIndex || state.hp[sideIndex][index] <= bestHealth) continue;
            targetIndex = index;
            bestHealth = state.hp[sideIndex][index];
        }
        return targetIndex;
    }

    private static Map<String, Double> transferableBoosts(int[] ranks) {
        return Map.of(
                "attack", (double) ranks[RANK_ATTACK],
                "specialAttack", (double) ranks[RANK_SPECIAL_ATTACK],
                "defence", (double) ranks[RANK_DEFENCE],
                "specialDefence", (double) ranks[RANK_SPECIAL_DEFENCE],
                "speed", (double) ranks[RANK_SPEED]);
    }

    private static boolean canRaiseFurther(BattlePokemon pokemon, int[] ranks, String... stats) {
        Set<String> requested = Set.of(stats);
        for (Move move : pokemon.getMoveSet().getMoves()) {
            if (move.getCurrentPp() <= 0) continue;
            String id = move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (id.equals("curse") && isGhost(pokemon)) continue;
            for (Map.Entry<String, Double> change
                    : SharedSetupThreatEvaluator.INSTANCE.projectedSelfBoosts(id).entrySet()) {
                if (change.getValue() <= 0.0 || !requested.contains(change.getKey())) continue;
                int rank = switch (change.getKey()) {
                    case "attack" -> ranks[RANK_ATTACK];
                    case "specialAttack" -> ranks[RANK_SPECIAL_ATTACK];
                    case "defence" -> ranks[RANK_DEFENCE];
                    case "specialDefence" -> ranks[RANK_SPECIAL_DEFENCE];
                    case "speed" -> ranks[RANK_SPEED];
                    default -> 6;
                };
                if (rank < 6) return true;
            }
        }
        return false;
    }

    private ThreatCounterResult preservationFacts(State state, int sideIndex) {
        Team allies = teams[sideIndex];
        Team enemies = teams[1 - sideIndex];
        List<ThreatObservationInput> threats = new ArrayList<>();
        for (int enemyIndex = 0; enemyIndex < enemies.members.size(); enemyIndex++) {
            BattlePokemon enemy = enemies.members.get(enemyIndex);
            int enemyHp = state.hp[1 - sideIndex][enemyIndex];
            List<CounterMatchupInput> resources = new ArrayList<>();
            for (int allyIndex = 0; allyIndex < allies.members.size(); allyIndex++) {
                final int resourceIndex = allyIndex;
                final int threatIndex = enemyIndex;
                BattlePokemon ally = allies.members.get(allyIndex);
                int allyHp = state.hp[sideIndex][allyIndex];
                double incoming = movesFor(state, 1 - sideIndex, threatIndex).stream()
                        .filter(move -> move.getCurrentPp() > 0)
                        .mapToDouble(move -> projectedDamage(
                                state, 1 - sideIndex, threatIndex, sideIndex, resourceIndex, move))
                        .max().orElse(0.0);
                double outgoing = movesFor(state, sideIndex, resourceIndex).stream()
                        .filter(move -> move.getCurrentPp() > 0)
                        .mapToDouble(move -> projectedDamage(
                                state, sideIndex, resourceIndex, 1 - sideIndex, threatIndex, move))
                        .max().orElse(0.0);
                boolean priorityKo = movesFor(state, sideIndex, resourceIndex).stream()
                        .filter(move -> move.getCurrentPp() > 0)
                        .filter(move -> move.getTemplate().getPriority() > 0)
                        .anyMatch(move -> projectedDamage(
                                state, sideIndex, resourceIndex, 1 - sideIndex, threatIndex, move) >= enemyHp);
                resources.add(new CounterMatchupInput(
                        allyIndex + 1,
                        ally.getUuid().toString(),
                        ally.getUuid().toString(),
                        allyHp > 0,
                        ratio(allyHp, ally.getMaxHealth()),
                        ratio(incoming, ally.getMaxHealth()),
                        ratio(outgoing, enemyHp),
                        incoming < allyHp,
                        projectedSpeed(state, sideIndex, allyIndex)
                                > projectedSpeed(state, 1 - sideIndex, enemyIndex),
                        priorityKo,
                        allies.roleAnalysis.getRoles().get(allyIndex).getAceProfile().getQualifies()));
            }
            boolean setupThreat = movesFor(state, 1 - sideIndex, enemyIndex).stream()
                    .filter(move -> move.getCurrentPp() > 0)
                    .map(move -> move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                    .anyMatch(SharedSetupThreatEvaluator.INSTANCE::isKnownSetupMove);
            threats.add(new ThreatObservationInput(
                    enemyIndex + 1,
                    enemy.getUuid().toString(),
                    enemy.getUuid().toString(),
                    enemyHp > 0,
                    enemies.roleAnalysis.getRoles().get(enemyIndex).getAceScore(),
                    enemies.roleAnalysis.getRoles().get(enemyIndex).getRoleScores()
                            .getOrDefault("setupSweeper", setupThreat ? 5.0 : 0.0),
                    Math.max(enemy.getEffectedPokemon().getAttack(),
                            enemy.getEffectedPokemon().getSpecialAttack()),
                    ratio(enemyHp, enemy.getMaxHealth()),
                    resources));
        }
        return SharedPreservationEvaluator.INSTANCE.evaluate(new ThreatCounterInput(threats));
    }

    private List<RoleProgressResult> roleProgressFacts(
            State state,
            int sideIndex,
            ThreatCounterResult preservation
    ) {
        Team allies = teams[sideIndex];
        Team enemies = teams[1 - sideIndex];
        int opponentLiving = living(state.hp[1 - sideIndex]);
        int highThreats = (int) preservation.getThreats().stream()
                .filter(threat -> threat.getThreatLevel().equals("critical")
                        || threat.getThreatLevel().equals("high"))
                .filter(threat -> state.hp[1 - sideIndex][threat.getEnemySlot() - 1] > 0)
                .count();
        int setupThreats = (int) enemies.roleAnalysis.getSetupThreats().stream()
                .filter(role -> state.hp[1 - sideIndex][role.getSlot() - 1] > 0).count();
        boolean enemySetterAlive = enemies.roleAnalysis.getHazardPlan().getSetters().stream()
                .anyMatch(role -> state.hp[1 - sideIndex][role.getSlot() - 1] > 0);
        double ownHazards = Arrays.stream(state.hazards[sideIndex]).sum();
        List<RoleProgressResult> result = new ArrayList<>();
        for (int index = 0; index < allies.members.size(); index++) {
            TeamMemberRoleResult role = allies.roleAnalysis.getRoles().get(index);
            TeamRoleMemberInput observation = allies.roleObservations.get(index);
            List<String> setConditions = observation.getMoveIds().stream()
                    .map(CobblemonBattleSearch::hazardCondition).filter(id -> !id.isEmpty()).distinct().toList();
            Map<String, Double> maximumLayers = new HashMap<>();
            Map<String, Double> opponentLayers = new HashMap<>();
            for (String condition : setConditions) {
                maximumLayers.put(condition, condition.equals("spikes") ? 3.0
                        : condition.equals("toxicspikes") ? 2.0 : 1.0);
                opponentLayers.put(condition, (double) state.hazards[1 - sideIndex][hazardIndex(condition)]);
            }
            int slot = index + 1;
            List<String> assignedThreats = preservation.getThreats().stream()
                    .filter(threat -> java.util.stream.Stream.of(
                                    threat.getCounters(), threat.getSoftChecks(), threat.getRevengeKillers())
                            .flatMap(List::stream).anyMatch(resource -> resource.getSlot() == slot))
                    .map(threat -> threat.getSpecies()).toList();
            boolean removal = observation.getCatalogTags().contains("hazardremove");
            result.add(SharedRoleProgressEvaluator.INSTANCE.evaluate(new RoleProgressInput(
                    role.getRoleScores(), role.getPrimaryRole(), role.getAceProfile().getQualifies(),
                    setConditions, maximumLayers, opponentLayers, removal, ownHazards,
                    enemySetterAlive, opponentLiving, highThreats, setupThreats, assignedThreats,
                    preservedResource(preservation, slot) != null,
                    index == state.active[sideIndex] ? state.turn : 0)));
        }
        return result;
    }

    private static String hazardCondition(String moveId) {
        return switch (moveId) {
            case "ceaselessedge", "spikes" -> "spikes";
            case "stealthrock", "stoneaxe" -> "stealthrock";
            case "stickyweb" -> "stickyweb";
            case "toxicspikes" -> "toxicspikes";
            default -> "";
        };
    }

    private static PreservedResourceFact preservedResource(ThreatCounterResult result, int slot) {
        return result.getMustPreserveResources().stream()
                .filter(resource -> resource.getSlot() == slot)
                .findFirst().orElse(null);
    }

    private static boolean preservesCurrentThreat(
            ThreatCounterResult result,
            int resourceSlot,
            int enemySlot
    ) {
        PreservedResourceFact resource = preservedResource(result, resourceSlot);
        return resource != null && resource.getThreats().stream()
                .anyMatch(threat -> threat.getEnemySlot() == enemySlot);
    }

    private double projectedDamage(
            State state,
            int attackerSide,
            int attackerIndex,
            int defenderSide,
            int defenderIndex,
            Move move
    ) {
        BattlePokemon attacker = teams[attackerSide].members.get(attackerIndex);
        BattlePokemon defender = teams[defenderSide].members.get(defenderIndex);
        SharedSearchCombatProfile attackerProfile = profile(state, attackerSide, attackerIndex);
        SharedSearchCombatProfile defenderProfile = profile(state, defenderSide, defenderIndex);
        boolean profileChanged = !attackerProfile.equals(state.baseProfiles.get(attackerSide).get(attackerIndex))
                || !defenderProfile.equals(state.baseProfiles.get(defenderSide).get(defenderIndex));
        double damage = Math.max(0, PokeMath.damage(attacker, defender, move));
        String category = move.getDamageCategory().getName().toLowerCase(Locale.ROOT);
        int attackRank;
        int defenceRank;
        Stats attackStat;
        Stats defenceStat;
        if (category.equals("physical")) {
            attackRank = RANK_ATTACK;
            defenceRank = RANK_DEFENCE;
            attackStat = Stats.ATTACK;
            defenceStat = Stats.DEFENCE;
        } else if (category.equals("special")) {
            attackRank = RANK_SPECIAL_ATTACK;
            defenceRank = RANK_SPECIAL_DEFENCE;
            attackStat = Stats.SPECIAL_ATTACK;
            defenceStat = Stats.SPECIAL_DEFENCE;
        } else {
            return damage;
        }
        if (profileChanged) {
            String moveType = move.getType().getName();
            String normalizedMove = move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            var stats = SharedDamageStatEvaluator.INSTANCE.evaluate(new SharedDamageStatInput(
                    effectivePokemon(state, attackerSide, attackerIndex),
                    effectivePokemon(state, defenderSide, defenderIndex),
                    category.equals("physical") ? "Physical" : "Special",
                    state.field.getWeather() == null ? "" : state.field.getWeather().getId(),
                    state.field.getTerrain() == null ? "" : state.field.getTerrain().getId(),
                    false));
            var typeInput = new SharedDamageTypeInput(
                    damageTypePokemon(state, attackerSide, attackerIndex),
                    damageTypePokemon(state, defenderSide, defenderIndex),
                    new SharedDamageTypeMove(normalizedMove, moveType, BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "sound")),
                    state.field.getWeather() == null ? "" : state.field.getWeather().getId());
            var modifiers = new SharedDamageModifierInput(
                    damageModifierPokemon(state, attackerSide, attackerIndex),
                    damageModifierPokemon(state, defenderSide, defenderIndex),
                    new SharedDamageModifierMove(
                            normalizedMove, moveType, move.getDamageCategory().getName(), move.getPower(),
                            BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "contact"),
                            BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "punch"),
                            BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "bite"),
                            BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "slicing"),
                            BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "recoil"),
                            BattleAiRoleCatalog.hasMoveFlag(normalizedMove, "secondary")),
                    1.0,
                    state.field.getWeather() == null ? "" : state.field.getWeather().getId(),
                    state.field.getTerrain() == null ? "" : state.field.getTerrain().getId(),
                    List.of(),
                    List.copyOf(state.sideConditions.get(defenderSide).keySet()),
                    false,
                    teams[attackerSide].members.size() - living(state.hp[attackerSide]),
                    false);
            var factors = SharedDamageFactorsEvaluator.INSTANCE.evaluate(
                    new SharedDamageFactorsInput(typeInput, modifiers));
            damage = SharedDamageCalculator.INSTANCE.range(new SharedDamageInput(
                    attacker.getOriginalPokemon().getLevel(), move.getPower(), stats.getAttack(), stats.getDefence(),
                    factors.getStab(), factors.getEffectiveness(), factors.getItemModifier(),
                    factors.getAbilityModifier(), factors.getFieldModifier(), 0.85)).getMaximum();
        }
        double rankedDamage = SharedBattleRankProjection.INSTANCE.adjustDamage(
                damage,
                attacker.getStatChanges().getOrDefault(attackStat, 0),
                state.ranks[attackerSide][attackerIndex][attackRank],
                defender.getStatChanges().getOrDefault(defenceStat, 0),
                state.ranks[defenderSide][defenderIndex][defenceRank]);
        SharedSearchFieldCombatResult baseline = fieldCombat(
                initialObservation.fieldState(), initialObservation.sideConditions(attackerSideId(attackerSide)),
                initialObservation.sideConditions(attackerSideId(defenderSide)), attacker, defender, move);
        SharedSearchFieldCombatResult projected = fieldCombat(
                state.field, state.sideConditions.get(attackerSide), state.sideConditions.get(defenderSide),
                attacker, defender, move, attackerProfile, defenderProfile);
        double weatherRatio = ratioMultiplier(
                projected.getWeatherDamageMultiplier(), baseline.getWeatherDamageMultiplier());
        double screenRatio = ratioMultiplier(
                projected.getScreenDamageMultiplier(), baseline.getScreenDamageMultiplier());
        return rankedDamage * weatherRatio * screenRatio * projected.getTerrainDamageMultiplier();
    }

    private AttackProfile bestAttackProfile(
            State state,
            int attackerSide,
            int attackerIndex,
            int defenderSide,
            int defenderIndex
    ) {
        AttackProfile best = AttackProfile.NONE;
        for (Move move : movesFor(state, attackerSide, attackerIndex)) {
            if (move.getCurrentPp() <= 0) continue;
            double damage = projectedDamage(
                    state, attackerSide, attackerIndex, defenderSide, defenderIndex, move);
            if (damage > best.damage) {
                best = new AttackProfile(damage, move.getTemplate().getPriority());
            }
        }
        return best;
    }

    private double projectedSpeed(State state, int sideIndex, int memberIndex) {
        BattlePokemon pokemon = teams[sideIndex].members.get(memberIndex);
        double speed = profile(state, sideIndex, memberIndex).getStats().getSpeed()
                * SharedBattleRankProjection.INSTANCE.multiplier(
                        state.ranks[sideIndex][memberIndex][RANK_SPEED]);
        SharedSearchFieldCombatResult field = fieldCombat(
                state.field, state.sideConditions.get(sideIndex), Map.of(), pokemon, pokemon, null,
                profile(state, sideIndex, memberIndex), profile(state, sideIndex, memberIndex));
        return speed * field.getSpeedMultiplier();
    }

    private SharedSearchCombatProfile profile(State state, int side, int slot) {
        return state.profiles.get(side).get(slot);
    }

    private BattlePokemon moveSource(State state, int side, int slot) {
        SharedSearchCombatProfile profile = profile(state, side, slot);
        int sourceSide = profile.getMoveSourceSide();
        int sourceSlot = profile.getMoveSourceSlot();
        if (sourceSide < 0 || sourceSide >= teams.length
                || sourceSlot < 0 || sourceSlot >= teams[sourceSide].members.size()) {
            return teams[side].members.get(slot);
        }
        return teams[sourceSide].members.get(sourceSlot);
    }

    private List<Move> movesFor(State state, int side, int slot) {
        return moveSource(state, side, slot).getMoveSet().getMoves();
    }

    private String profileAbility(State state, int side, int slot) {
        return profile(state, side, slot).getAbility();
    }

    private List<String> profileTypes(State state, int side, int slot) {
        return profile(state, side, slot).getTypes();
    }

    private SharedEffectiveStatPokemon effectivePokemon(State state, int side, int slot) {
        SharedSearchCombatProfile profile = profile(state, side, slot);
        String paradoxStat = "";
        String paradoxSource = "";
        for (String marker : state.abilityStates.get(side).get(slot)) {
            if (!marker.startsWith("paradox:")) continue;
            String[] parts = marker.split(":", -1);
            if (parts.length > 1) paradoxStat = parts[1];
            if (parts.length > 2) paradoxSource = parts[2];
        }
        return new SharedEffectiveStatPokemon(
                profile.getId(), profile.getId(), profile.getTypes(), profile.getAbility(),
                state.heldItems.get(side).get(slot), "", state.hp[side][slot],
                profile.getStats().getHp(), profile.getStats(), rankMap(state.ranks[side][slot]),
                0, false, false, false, paradoxSource, paradoxStat);
    }

    private SharedDamageTypePokemon damageTypePokemon(State state, int side, int slot) {
        SharedSearchCombatProfile profile = profile(state, side, slot);
        return new SharedDamageTypePokemon(
                profile.getId(), profile.getTypes(), profile.getTypes(), profile.getAbility(),
                state.heldItems.get(side).get(slot), state.hp[side][slot], profile.getStats().getHp(),
                false, "", List.of(), false, false, false);
    }

    private SharedDamageModifierPokemon damageModifierPokemon(State state, int side, int slot) {
        SharedSearchCombatProfile profile = profile(state, side, slot);
        return new SharedDamageModifierPokemon(
                profile.getId(), profile.getId(), profile.getTypes(), profile.getAbility(),
                state.heldItems.get(side).get(slot), "", state.hp[side][slot], profile.getStats().getHp(),
                false, false, false, false, false, "", 0);
    }

    private static Map<String, Integer> rankMap(int[] ranks) {
        return Map.of(
                "attack", ranks[RANK_ATTACK],
                "specialAttack", ranks[RANK_SPECIAL_ATTACK],
                "defence", ranks[RANK_DEFENCE],
                "specialDefence", ranks[RANK_SPECIAL_DEFENCE],
                "speed", ranks[RANK_SPEED]);
    }

    private SharedSearchFieldCombatResult fieldCombat(
            SharedSearchFieldState field,
            Map<String, SharedSearchTimedEffect> attackerConditions,
            Map<String, SharedSearchTimedEffect> defenderConditions,
            BattlePokemon attacker,
            BattlePokemon defender,
            Move move
    ) {
        return fieldCombat(field, attackerConditions, defenderConditions, attacker, defender, move, null, null);
    }

    private SharedSearchFieldCombatResult fieldCombat(
            SharedSearchFieldState field,
            Map<String, SharedSearchTimedEffect> attackerConditions,
            Map<String, SharedSearchTimedEffect> defenderConditions,
            BattlePokemon attacker,
            BattlePokemon defender,
            Move move,
            SharedSearchCombatProfile attackerProfile,
            SharedSearchCombatProfile defenderProfile
    ) {
        return SharedSearchFieldCombatEvaluator.INSTANCE.evaluate(new SharedSearchFieldCombatInput(
                field,
                attackerConditions,
                defenderConditions,
                move == null ? "" : move.getType().getName(),
                move == null ? "" : move.getDamageCategory().getName(),
                attackerProfile == null ? pokemonTypes(attacker) : attackerProfile.getTypes(),
                attackerProfile == null ? pokemonAbility(attacker) : attackerProfile.getAbility(), pokemonItem(attacker),
                defenderProfile == null ? pokemonTypes(defender) : defenderProfile.getTypes(),
                defenderProfile == null ? pokemonAbility(defender) : defenderProfile.getAbility(), pokemonItem(defender)));
    }

    private String attackerSideId(int sideIndex) {
        return teams[sideIndex].sideId;
    }

    private static double ratioMultiplier(double projected, double baseline) {
        if (baseline <= 0.0) return projected <= 0.0 ? 1.0 : projected;
        return projected / baseline;
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
        SharedSearchProjectionState next = SharedSearchProjectionRuntime.INSTANCE.transition(
                toProjection(state),
                projectedAction(state, 0, sideZeroAction),
                projectedAction(state, 1, sideOneAction));
        return fromProjection(next);
    }

    private SharedProjectedSearchAction projectedAction(State state, int sideIndex, SearchAction action) {
        int switchSlot = -1;
        int itemIndex = -1;
        int itemTargetSlot = -1;
        int healing = 0;
        double damage = 0.0;
        int projectedHazard = -1;
        String pressure = "";
        Map<String, Double> selfBoosts = Map.of();
        int batonPassTarget = -1;
        SharedSearchFieldMoveEffect fieldEffect = new SharedSearchFieldMoveEffect("", "", "", "", 0, 0);
        List<SharedHitReaction> hitReactions = List.of();
        List<SharedPostHitInstruction> postHitInstructions = List.of();
        SharedSwitchPhaseResult switchPhase = null;
        if ("switch".equals(action.getKind())) {
            UUID uuid = UUID.fromString(action.getId().substring(SWITCH_PREFIX.length()));
            switchSlot = indexOf(teams[sideIndex], uuid);
            if (switchSlot >= 0) {
                int outgoingSlot = state.active[sideIndex];
                int opponentSide = 1 - sideIndex;
                int opponentSlot = state.active[opponentSide];
                BattlePokemon outgoing = teams[sideIndex].members.get(outgoingSlot);
                BattlePokemon incoming = teams[sideIndex].members.get(switchSlot);
                BattlePokemon opponent = teams[opponentSide].members.get(opponentSlot);
                SharedSearchCombatProfile incomingProfile = state.baseProfiles.get(sideIndex).get(switchSlot);
                SharedSearchCombatProfile opponentProfile = profile(state, opponentSide, opponentSlot);
                String incomingAbility = incomingProfile.getAbility();
                String cleanIncomingAbility = incomingAbility.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                String incomingItem = state.heldItems.get(sideIndex).get(switchSlot);
                List<String> incomingTypes = incomingProfile.getTypes();
                boolean grounded = incomingTypes.stream().noneMatch(type -> type.equalsIgnoreCase("flying"))
                        && !cleanIncomingAbility.equals("levitate")
                        && !incomingItem.toLowerCase(Locale.ROOT).contains("air_balloon");
                boolean canPoison = incomingTypes.stream().noneMatch(type ->
                        type.equalsIgnoreCase("poison") || type.equalsIgnoreCase("steel"))
                        && !Set.of("immunity", "comatose", "purifyingsalt").contains(cleanIncomingAbility);
                Map<String, Double> incomingStats = Map.of(
                        "attack", (double) incomingProfile.getStats().getAttack(),
                        "defence", (double) incomingProfile.getStats().getDefence(),
                        "specialAttack", (double) incomingProfile.getStats().getSpecialAttack(),
                        "specialDefence", (double) incomingProfile.getStats().getSpecialDefence(),
                        "speed", (double) incomingProfile.getStats().getSpeed());
                List<SharedEntryMoveObservation> opponentMoves = movesFor(state, opponentSide, opponentSlot).stream()
                        .map(move -> {
                            String id = move.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                            return new SharedEntryMoveObservation(
                                    id,
                                    move.getType().getName(),
                                    move.getPower() <= 0.0 ? "Status" : "Damage",
                                    (int) Math.round(move.getPower()),
                                    Set.of("fissure", "guillotine", "horndrill", "sheercold").contains(id),
                                    move.getPower() == 1.0);
                        }).toList();
                boolean disguiseAvailable = false;
                for (int slot = 0; slot < state.hp[sideIndex].length; slot++) {
                    if (slot != switchSlot && state.hp[sideIndex][slot] > 0) {
                        disguiseAvailable = true;
                        break;
                    }
                }
                switchPhase = SharedSwitchPhaseEvaluator.INSTANCE.evaluate(new SharedSwitchPhaseInput(
                        state.hp[sideIndex][outgoingSlot], outgoing.getMaxHealth(),
                        profileAbility(state, sideIndex, outgoingSlot), "",
                        state.hp[sideIndex][outgoingSlot] <= 0,
                        state.hp[sideIndex][switchSlot], incoming.getMaxHealth(), incomingAbility,
                        incoming.getOriginalPokemon().getSpecies().getResourceIdentifier().getPath(), incomingItem,
                        incomingTypes, incomingStats, grounded, canPoison, false,
                        state.abilityStates.get(sideIndex).get(switchSlot),
                        Map.of(),
                        state.hazards[sideIndex][HAZARD_STEALTH_ROCK],
                        state.hazards[sideIndex][HAZARD_SPIKES],
                        state.hazards[sideIndex][HAZARD_TOXIC_SPIKES],
                        state.hazards[sideIndex][HAZARD_STICKY_WEB],
                        false, false, disguiseAvailable,
                        state.hp[opponentSide][opponentSlot] > 0,
                        opponentProfile.getAbility(),
                        opponentProfile.getStats().getDefence(),
                        opponentProfile.getStats().getSpecialDefence(),
                        state.heldItems.get(opponentSide).get(opponentSlot),
                        opponentMoves,
                        state.field.getWeather() == null ? "" : state.field.getWeather().getId(),
                        state.field.getTerrain() == null ? "" : state.field.getTerrain().getId()));
            }
        } else if ("item".equals(action.getKind())) {
            int[] selection = itemSelection(action.getId());
            itemIndex = selection[0];
            itemTargetSlot = selection[1];
            BattlePokemon target = teams[sideIndex].members.get(itemTargetSlot);
            healing = healingAmount(teams[sideIndex].items.get(itemIndex).item.getItemName(), target.getMaxHealth());
        } else {
            int attackerIndex = state.active[sideIndex];
            int targetSide = 1 - sideIndex;
            int defenderIndex = state.active[targetSide];
            BattlePokemon attacker = teams[sideIndex].members.get(attackerIndex);
            BattlePokemon defender = teams[targetSide].members.get(defenderIndex);
            String moveId = underlyingMoveId(action.getId());
            String normalized = moveId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            Move move = movesFor(state, sideIndex, attackerIndex).stream()
                    .filter(entry -> entry.getName().equalsIgnoreCase(moveId)).findFirst().orElse(null);
            damage = move == null ? action.getExpectedDamage()
                    : projectedDamage(state, sideIndex, attackerIndex, targetSide, defenderIndex, move);
            if ("gimmick".equals(action.getKind())) damage *= gimmickMultiplier(gimmickId(action.getId()));
            projectedHazard = hazardIndex(normalized);
            pressure = switch (normalized) {
                case "yawn" -> "yawn";
                case "saltcure" -> "saltcure";
                case "toxic" -> "toxic";
                default -> "";
            };
            selfBoosts = normalized.equals("curse") && isGhost(attacker)
                    ? Map.of()
                    : SharedSetupThreatEvaluator.INSTANCE.projectedSelfBoosts(normalized);
            fieldEffect = SharedSearchFieldMoveCatalog.INSTANCE.effect(normalized, pokemonItem(attacker));
            if (move != null && damage > 0.0) {
                String attackerAbility = profileAbility(state, sideIndex, attackerIndex).toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                String defenderAbility = profileAbility(state, targetSide, defenderIndex).toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                String attackerItem = pokemonItem(attacker);
                String defenderItem = pokemonItem(defender);
                String cleanAttackerItem = attackerItem.toLowerCase(Locale.ROOT)
                        .replaceFirst("^.*:", "").replaceAll("[^a-z0-9]", "");
                String cleanDefenderItem = defenderItem.toLowerCase(Locale.ROOT)
                        .replaceFirst("^.*:", "").replaceAll("[^a-z0-9]", "");
                String cleanMoveType = move.getType().getName().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                boolean consumesAttackerItem = cleanAttackerItem.equals(
                        cleanMoveType + "gem");
                boolean consumesDefenderItem = switch (cleanDefenderItem) {
                    case "chartiberry" -> cleanMoveType.equals("rock");
                    case "colburberry" -> cleanMoveType.equals("dark");
                    case "yacheberry" -> cleanMoveType.equals("ice");
                    default -> false;
                } && dev.buizz.cobbleventure.ai.core.SharedDamageTypeEvaluator.INSTANCE.effectiveness(
                        move.getType().getName(), profileTypes(state, targetSide, defenderIndex)) > 1.0;
                String projectedAttackerItem = consumesAttackerItem ? "" : attackerItem;
                boolean ignoresAbility = attackerAbility.equals("moldbreaker")
                        || attackerAbility.equals("teravolt");
                boolean contact = BattleAiRoleCatalog.hasMoveFlag(normalized, "contact");
                hitReactions = SharedHitReactionEvaluator.INSTANCE.evaluate(new SharedHitReactionInput(
                        damage,
                        state.hp[sideIndex][attackerIndex] > 0,
                        state.hp[targetSide][defenderIndex] - damage > 0.0,
                        profileAbility(state, sideIndex, attackerIndex),
                        profileAbility(state, targetSide, defenderIndex), defenderItem,
                        normalized, move.getType().getName(), move.getDamageCategory().getName(),
                        dev.buizz.cobbleventure.ai.core.SharedDamageTypeEvaluator.INSTANCE.effectiveness(
                                move.getType().getName(), profileTypes(state, targetSide, defenderIndex)) > 1.0,
                        contact, contact, ignoresAbility,
                        false, false, false, false, false,
                        profileTypes(state, sideIndex, attackerIndex).stream()
                                .anyMatch(type -> type.equalsIgnoreCase("grass")),
                        attackerAbility.equals("overcoat"), false, false, false,
                        false, false, false, 0L,
                        attackerItem, false, "",
                        attackerAbility.equals("stickyhold"),
                        defenderAbility.equals("stickyhold") && !ignoresAbility,
                        consumesAttackerItem, consumesDefenderItem)).getReactions();
                postHitInstructions = SharedPostHitEvaluator.INSTANCE.projectedInstructions(
                        normalized,
                        state.hp[sideIndex][attackerIndex],
                        attacker.getMaxHealth(),
                        projectedAttackerItem,
                        attackerAbility,
                        state.hp[sideIndex][attackerIndex] <= 0,
                        defenderItem,
                        defenderAbility.equals("stickyhold") && !ignoresAbility);
            }
            if (normalized.equals("batonpass")) {
                batonPassTarget = batonPassTargetIndex(state, sideIndex, attackerIndex);
            }
        }
        return new SharedProjectedSearchAction(
                action, sideIndex, switchSlot, itemIndex, itemTargetSlot, healing,
                damage, action.getSuccessProbability(), projectedHazard, pressure,
                selfBoosts, batonPassTarget, "gimmick".equals(action.getKind()),
                fieldEffect.getWeather(), fieldEffect.getTerrain(), fieldEffect.getPseudoWeather(),
                fieldEffect.getSideCondition(), fieldEffect.getFieldDuration(),
                fieldEffect.getSideConditionDuration(), hitReactions, postHitInstructions, switchPhase);
    }

    private SharedSearchProjectionState toProjection(State state) {
        List<List<Integer>> maximumHp = new ArrayList<>();
        for (Team team : teams) {
            maximumHp.add(team.members.stream().map(BattlePokemon::getMaxHealth).toList());
        }
        List<List<SharedSearchPressure>> pressures = new ArrayList<>();
        for (PressureState[] side : state.pressures) {
            pressures.add(Arrays.stream(side)
                    .map(value -> new SharedSearchPressure(
                            value.yawn, value.yawnTurns, value.saltCure,
                            value.toxicCounter, value.sleepTurns))
                    .toList());
        }
        List<List<List<Integer>>> ranks = new ArrayList<>();
        for (int[][] side : state.ranks) {
            ranks.add(Arrays.stream(side).map(CobblemonBattleSearch::integerList).toList());
        }
        return new SharedSearchProjectionState(
                state.turn,
                integerList(state.active),
                Arrays.stream(state.hp).map(CobblemonBattleSearch::integerList).toList(),
                maximumHp,
                booleanList(state.gimmicksRemaining),
                Arrays.stream(state.itemCounts).map(CobblemonBattleSearch::integerList).toList(),
                Arrays.stream(state.hazards).map(CobblemonBattleSearch::integerList).toList(),
                pressures,
                ranks,
                state.heldItems,
                state.abilityStates,
                state.field,
                state.sideConditions,
                state.baseProfiles,
                state.profiles,
                state.formProfiles);
    }

    private State fromProjection(SharedSearchProjectionState projection) {
        int[][] hp = projection.getHp().stream().map(CobblemonBattleSearch::intArray).toArray(int[][]::new);
        int[][] itemCounts = projection.getItemCounts().stream()
                .map(CobblemonBattleSearch::intArray).toArray(int[][]::new);
        int[][] hazards = projection.getHazards().stream()
                .map(CobblemonBattleSearch::intArray).toArray(int[][]::new);
        PressureState[][] pressures = projection.getPressures().stream()
                .map(side -> side.stream()
                        .map(value -> new PressureState(
                                value.getYawn(), value.getYawnTurns(), value.getSaltCure(),
                                value.getToxicCounter(), value.getSleepTurns()))
                        .toArray(PressureState[]::new))
                .toArray(PressureState[][]::new);
        int[][][] ranks = projection.getRanks().stream()
                .map(side -> side.stream().map(CobblemonBattleSearch::intArray).toArray(int[][]::new))
                .toArray(int[][][]::new);
        return new State(
                projection.getTurn(),
                intArray(projection.getActive()),
                hp,
                booleanArray(projection.getGimmicksRemaining()),
                itemCounts,
                hazards,
                pressures,
                ranks,
                projection.getHeldItems(),
                projection.getAbilityStates(),
                projection.getField(),
                projection.getSideConditions(),
                projection.getBaseProfiles(),
                projection.getProfiles(),
                projection.getFormProfiles());
    }

    private static List<Integer> integerList(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static List<Boolean> booleanList(boolean[] values) {
        List<Boolean> result = new ArrayList<>(values.length);
        for (boolean value : values) result.add(value);
        return result;
    }

    private static int[] intArray(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean[] booleanArray(List<Boolean> values) {
        boolean[] result = new boolean[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
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
        ThreatCounterResult preservation = preservationFacts(state, sideIndex);
        int living = living(state.hp[sideIndex]);
        double totalHp = 0.0;
        List<BattleValueMemberInput> members = new ArrayList<>();
        for (int index = 0; index < team.members.size(); index++) {
            double hpRatio = ratio(state.hp[sideIndex][index], team.members.get(index).getMaxHealth());
            totalHp += hpRatio;
            members.add(new BattleValueMemberInput(
                    hpRatio,
                    state.hp[sideIndex][index] > 0,
                    team.roleAnalysis.getRoles().get(index).getAceProfile().getQualifies(),
                    Arrays.stream(state.ranks[sideIndex][index]).filter(rank -> rank > 0).sum(),
                    0.0,
                    preservedResource(preservation, index + 1) != null));
        }
        int ace = team.aceIndex;
        double aceHp = ratio(state.hp[sideIndex][ace], team.members.get(ace).getMaxHealth());
        double coverage = matchupCoverage(state, sideIndex);
        double readiness = living <= 1 ? 0.0 : totalHp / team.members.size();
        return SharedBattleObservation.INSTANCE.valueSide(new BattleValueSideInput(
                members,
                Arrays.stream(state.hazards[sideIndex]).sum(),
                state.gimmicksRemaining[sideIndex] ? 1.0 : 0.0,
                coverage,
                coverage,
                readiness,
                coverage * aceHp));
    }

    private double matchupCoverage(State state, int sideIndex) {
        int attackerIndex = state.active[sideIndex];
        int hp = state.hp[1 - sideIndex][state.active[1 - sideIndex]];
        int best = movesFor(state, sideIndex, attackerIndex).stream().filter(move -> move.getCurrentPp() > 0)
                .mapToInt(move -> (int) Math.round(projectedDamage(
                        state, sideIndex, attackerIndex, 1 - sideIndex,
                        state.active[1 - sideIndex], move))).max().orElse(0);
        return Math.min(1.0, ratio(best, hp));
    }

    private static SearchAction action(String id, String kind, double score) {
        return new SearchAction(id, kind, score, 1.0, 0.0, false, false, false, 0.0, false);
    }

    private static double sharedScore(CandidateScoreFacts input) {
        return SharedCandidateEvaluator.INSTANCE.score(input).getScore();
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
                + Arrays.deepToString(state.itemCounts) + ':' + Arrays.deepToString(state.hazards) + ':'
                + Arrays.deepToString(state.pressures) + ':' + Arrays.deepToString(state.ranks) + ':'
                + state.field + ':' + state.sideConditions + ':' + state.profiles;
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
        int[][] hazards = new int[2][HAZARD_COUNT];
        PressureState[][] pressures = new PressureState[2][];
        int[][][] ranks = new int[2][][];
        int[] active = new int[2];
        for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
            Team team = teams[sideIndex];
            hp[sideIndex] = team.members.stream().mapToInt(BattlePokemon::getHealth).toArray();
            itemCounts[sideIndex] = team.items.stream().mapToInt(BattleItem::quantity).toArray();
            active[sideIndex] = team.activeIndex;
            hazards[sideIndex][HAZARD_STEALTH_ROCK] = initialObservation.hazardLayers(team.sideId, "stealthrock");
            hazards[sideIndex][HAZARD_SPIKES] = initialObservation.hazardLayers(team.sideId, "spikes");
            hazards[sideIndex][HAZARD_TOXIC_SPIKES] = initialObservation.hazardLayers(team.sideId, "toxicspikes");
            hazards[sideIndex][HAZARD_STICKY_WEB] = initialObservation.hazardLayers(team.sideId, "stickyweb");
            pressures[sideIndex] = new PressureState[team.members.size()];
            Arrays.fill(pressures[sideIndex], PressureState.NONE);
            ranks[sideIndex] = new int[team.members.size()][RANK_COUNT];
            for (int memberIndex = 0; memberIndex < team.members.size(); memberIndex++) {
                BattlePokemon member = team.members.get(memberIndex);
                ranks[sideIndex][memberIndex][RANK_ATTACK] = member.getStatChanges().getOrDefault(Stats.ATTACK, 0);
                ranks[sideIndex][memberIndex][RANK_SPECIAL_ATTACK] =
                        member.getStatChanges().getOrDefault(Stats.SPECIAL_ATTACK, 0);
                ranks[sideIndex][memberIndex][RANK_DEFENCE] =
                        member.getStatChanges().getOrDefault(Stats.DEFENCE, 0);
                ranks[sideIndex][memberIndex][RANK_SPECIAL_DEFENCE] =
                        member.getStatChanges().getOrDefault(Stats.SPECIAL_DEFENCE, 0);
                ranks[sideIndex][memberIndex][RANK_SPEED] = member.getStatChanges().getOrDefault(Stats.SPEED, 0);
            }
            ShowdownBattleLogObservation.ActivePressure observed = initialObservation.pressure(team.activePosition);
            pressures[sideIndex][team.activeIndex] = new PressureState(
                    observed.yawn(), observed.yawnTurns(), observed.saltCure(), observed.toxicCounter(), 0);
        }
        List<Map<String, SharedSearchTimedEffect>> sideConditions = List.of(
                initialObservation.sideConditions(teams[0].sideId),
                initialObservation.sideConditions(teams[1].sideId));
        List<List<String>> heldItems = Arrays.stream(teams)
                .map(team -> team.members.stream().map(CobblemonBattleSearch::pokemonItem).toList())
                .toList();
        List<List<Set<String>>> abilityStates = Arrays.stream(teams)
                .map(team -> team.members.stream().map(member -> Set.<String>of()).toList())
                .toList();
        List<List<SharedSearchCombatProfile>> profiles = new ArrayList<>();
        List<List<Map<String, SharedSearchCombatProfile>>> formProfiles = new ArrayList<>();
        for (int sideIndex = 0; sideIndex < teams.length; sideIndex++) {
            List<SharedSearchCombatProfile> sideProfiles = new ArrayList<>();
            List<Map<String, SharedSearchCombatProfile>> sideForms = new ArrayList<>();
            for (int slot = 0; slot < teams[sideIndex].members.size(); slot++) {
                BattlePokemon member = teams[sideIndex].members.get(slot);
                sideProfiles.add(combatProfile(member, sideIndex, slot));
                sideForms.add(combatFormProfiles(member, sideIndex, slot));
            }
            profiles.add(List.copyOf(sideProfiles));
            formProfiles.add(List.copyOf(sideForms));
        }
        return new State(0, active, hp, new boolean[]{!rootGimmicks.isEmpty(), false}, itemCounts,
                hazards, pressures, ranks, heldItems, abilityStates,
                initialObservation.fieldState(), sideConditions,
                profiles, profiles, formProfiles);
    }

    private static Team team(BattleSide side, ActiveBattlePokemon active) {
        BattleActor actor = active.getActor();
        List<BattlePokemon> members = actor.getPokemonList();
        int activeIndex = Math.max(0, members.indexOf(active.getBattlePokemon()));
        List<TeamRoleMemberInput> observations = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            observations.add(BattleAiRoleCatalog.observe(members.get(index), index + 1));
        }
        TeamRoleResult roleAnalysis = SharedTeamRoleEvaluator.INSTANCE.evaluate(new TeamRoleInput(observations));
        int aceIndex = roleAnalysis.getAceCandidates().isEmpty()
                ? 0 : roleAnalysis.getAceCandidates().getFirst().getSlot() - 1;
        List<BattleItem> items = actor instanceof TrainerEntityBattleActor trainer
                ? trainer.getBag().getItems().stream()
                        .map(item -> new BattleItem(item, trainer.getBag().getQuanity(item)))
                        .filter(item -> item.quantity > 0)
                        .toList()
                : List.of();
        return new Team(List.copyOf(members), activeIndex, aceIndex, List.copyOf(observations), roleAnalysis, items,
                actor.getShowdownId(), active.getPNX());
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

    private boolean hasLivingBench(BattlePokemon attacker) {
        for (Team team : teams) {
            if (team.members.stream().noneMatch(member -> member.getUuid().equals(attacker.getUuid()))) continue;
            return team.members.stream().anyMatch(member ->
                    !member.getUuid().equals(attacker.getUuid()) && member.getHealth() > 0);
        }
        return false;
    }

    private static boolean isRecoveryMove(String moveId) {
        return switch (moveId) {
            case "recover", "rest", "roost", "slackoff", "softboiled", "milkdrink",
                    "moonlight", "morningsun", "shoreup", "synthesis", "healorder" -> true;
            default -> false;
        };
    }

    private static boolean isPivotMove(String moveId) {
        return switch (moveId) {
            case "batonpass", "chillyreception", "flipturn", "partingshot", "shedtail",
                    "teleport", "uturn", "voltswitch" -> true;
            default -> false;
        };
    }

    private static boolean isSelfSacrificeMove(String moveId) {
        return switch (moveId) {
            case "explosion", "finalgambit", "healingwish", "lunardance", "memento",
                    "mistyexplosion", "selfdestruct" -> true;
            default -> false;
        };
    }

    private static double gimmickMultiplier(String gimmick) {
        if (gimmick == null) return 1.0;
        return switch (gimmick.toLowerCase(Locale.ROOT)) {
            case "zmove", "max", "dynamax", "gigantamax", "gmax" -> 1.35;
            case "mega", "ultra" -> 1.18;
            case "tera", "terastal", "terastallize", "terastallization" -> 1.20;
            default -> 1.12;
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

    private static PressureState[][] copy(PressureState[][] source) {
        return Arrays.stream(source).map(PressureState[]::clone).toArray(PressureState[][]::new);
    }

    private static int[][][] copy(int[][][] source) {
        return Arrays.stream(source)
                .map(side -> Arrays.stream(side).map(int[]::clone).toArray(int[][]::new))
                .toArray(int[][][]::new);
    }

    private static int hazardIndex(String moveId) {
        return switch (moveId) {
            case "stealthrock", "stoneaxe" -> HAZARD_STEALTH_ROCK;
            case "spikes", "ceaselessedge" -> HAZARD_SPIKES;
            case "toxicspikes" -> HAZARD_TOXIC_SPIKES;
            case "stickyweb" -> HAZARD_STICKY_WEB;
            default -> -1;
        };
    }

    private static boolean isWaterOrSteel(BattlePokemon pokemon) {
        for (var type : pokemon.getEffectedPokemon().getTypes()) {
            String name = type.getName().toLowerCase(Locale.ROOT);
            if (name.equals("water") || name.equals("steel")) return true;
        }
        return false;
    }

    private static boolean isGhost(BattlePokemon pokemon) {
        for (var type : pokemon.getEffectedPokemon().getTypes()) {
            if (type.getName().equalsIgnoreCase("ghost")) return true;
        }
        return false;
    }

    private static List<String> pokemonTypes(BattlePokemon pokemon) {
        List<String> result = new ArrayList<>();
        for (var type : pokemon.getEffectedPokemon().getTypes()) result.add(type.getName());
        return result;
    }

    private static SharedSearchCombatProfile combatProfile(BattlePokemon pokemon, int side, int slot) {
        var effected = pokemon.getEffectedPokemon();
        return new SharedSearchCombatProfile(
                pokemon.getOriginalPokemon().getSpecies().getResourceIdentifier().getPath(),
                pokemonAbility(pokemon),
                pokemonTypes(pokemon),
                new SharedBattleStats(
                        pokemon.getMaxHealth(), effected.getAttack(), effected.getDefence(),
                        effected.getSpecialAttack(), effected.getSpecialDefence(), effected.getSpeed()),
                side,
                slot);
    }

    private static Map<String, SharedSearchCombatProfile> combatFormProfiles(
            BattlePokemon pokemon,
            int side,
            int slot
    ) {
        var original = pokemon.getOriginalPokemon();
        if (!original.getSpecies().getResourceIdentifier().getPath().equals("terapagos")) return Map.of();
        var projected = original.clone(false, null);
        projected.setForm(original.getSpecies().getFormByName("Terastal"));
        List<String> types = new ArrayList<>();
        for (var type : projected.getTypes()) types.add(type.getName());
        return Map.of("terapagosterastal", new SharedSearchCombatProfile(
                "terapagosterastal",
                projected.getAbility().getName(),
                types,
                new SharedBattleStats(
                        projected.getMaxHealth(), projected.getAttack(), projected.getDefence(),
                        projected.getSpecialAttack(), projected.getSpecialDefence(), projected.getSpeed()),
                side,
                slot));
    }

    private static String pokemonAbility(BattlePokemon pokemon) {
        return pokemon.getEffectedPokemon().getAbility().getName();
    }

    private static String pokemonItem(BattlePokemon pokemon) {
        ItemStack stack = pokemon.getEffectedPokemon().heldItem();
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static String itemId(BattlePokemon pokemon) {
        return pokemonItem(pokemon).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static double ratio(double numerator, double denominator) {
        return numerator / Math.max(1.0, denominator);
    }

    record PlannedResponse(
            ShowdownActionResponse response,
            SearchDecision decision,
            UUID batonPassTarget
    ) {}
    private record AttackProfile(double damage, double priority) {
        private static final AttackProfile NONE = new AttackProfile(0.0, 0.0);
    }
    record State(
            int turn,
            int[] active,
            int[][] hp,
            boolean[] gimmicksRemaining,
            int[][] itemCounts,
            int[][] hazards,
            PressureState[][] pressures,
            int[][][] ranks,
            List<List<String>> heldItems,
            List<List<Set<String>>> abilityStates,
            SharedSearchFieldState field,
            List<Map<String, SharedSearchTimedEffect>> sideConditions,
            List<List<SharedSearchCombatProfile>> baseProfiles,
            List<List<SharedSearchCombatProfile>> profiles,
            List<List<Map<String, SharedSearchCombatProfile>>> formProfiles
    ) {}
    private record Team(
            List<BattlePokemon> members,
            int activeIndex,
            int aceIndex,
            List<TeamRoleMemberInput> roleObservations,
            TeamRoleResult roleAnalysis,
            List<BattleItem> items,
            String sideId,
            String activePosition
    ) {}
    private record PressureState(
            boolean yawn,
            int yawnTurns,
            boolean saltCure,
            int toxicCounter,
            int sleepTurns
    ) {
        private static final PressureState NONE = new PressureState(false, 0, false, 0, 0);
    }
    private record BattleItem(BagItem item, int quantity) {}
    private record RootMove(String showdownId, Move move, Targetable target) {}
}
