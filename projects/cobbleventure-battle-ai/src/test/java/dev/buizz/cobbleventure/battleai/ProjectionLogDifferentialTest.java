package dev.buizz.cobbleventure.battleai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buizz.cobbleventure.ai.core.SearchAction;
import dev.buizz.cobbleventure.ai.core.SharedProjectedSearchAction;
import dev.buizz.cobbleventure.ai.core.SharedProjectionDifferentialEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedProjectionDifferentialResult;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldState;
import dev.buizz.cobbleventure.ai.core.SharedSearchPressure;
import dev.buizz.cobbleventure.ai.core.SharedSearchProjectionRuntime;
import dev.buizz.cobbleventure.ai.core.SharedSearchProjectionState;
import dev.buizz.cobbleventure.ai.core.SharedSearchTimedEffect;
import dev.buizz.cobbleventure.ai.core.SharedSwitchPhaseEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedSwitchPhaseInput;
import dev.buizz.cobbleventure.ai.core.SharedSwitchPhaseResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectionLogDifferentialTest {
    @Test
    void matchesPlatformDependentEntryAdapterActivationLogs() {
        assertAdapterLog("traced:intimidate",
                "|-ability|p1a: User|Intimidate|Trace|[from] ability: Trace|[of] p2a: Target");
        assertAdapterLog("forewarn:fissure",
                "|-activate|p1a: User|ability: Forewarn|Fissure|[of] p2a: Target");
        assertAdapterLog("anticipation", "|-ability|p1a: User|Anticipation");
        assertAdapterLog("frisked:choicescarf",
                "|-item|p2a: Target|Choice Scarf|[from] ability: Frisk|[of] p1a: User");
        assertAdapterLog("transformed", "|-transform|p1a: User|p2a: Target|[from] ability: Imposter");
        assertAdapterLog("paradox", "|-start|p1a: User|protosynthesisspe");
        assertAdapterLog("form:terapagosterastal",
                "|-activate|p1a: User|ability: Tera Shift");
        assertAdapterLog("forecast",
                "|-formechange|p1a: User|Castform-Rainy|[from] ability: Forecast");
    }

    @Test
    void matchesDirectDamageAndSelfBoostAgainstBattleLog() {
        SharedSearchProjectionState initial = state(
                1,
                List.of(0, 0),
                List.of(List.of(100), List.of(100)),
                List.of(false, false),
                List.of(List.of(0, 0, 0, 0), List.of(0, 0, 0, 0)),
                emptyPressures(1, 1),
                emptyRanks(1, 1),
                new SharedSearchFieldState(null, null, Map.of()),
                List.of(Map.of(), Map.of()));
        SharedProjectedSearchAction attack = new SharedProjectedSearchAction(
                action("move:poweruppunch", "move"), 0, -1, -1, -1, 0,
                30.0, 1.0, -1, "", Map.of("attack", 1.0), -1, false,
                "", "", "", "", 0, 0, List.of(), List.of(), null);
        SharedSearchProjectionState expected = SharedSearchProjectionRuntime.INSTANCE.transition(
                initial, attack, idle(1, false));
        ShowdownBattleLogObservation actual = ShowdownBattleLogObservation.parse(List.of(
                "|turn|1",
                "|switch|p1a: Player|Player, L50|100/100",
                "|switch|p2a: Enemy|Enemy, L50|100/100",
                "|move|p1a: Player|Power-Up Punch|p2a: Enemy",
                "|-damage|p2a: Enemy|70/100",
                "|-boost|p1a: Player|atk|1",
                "|turn|2"));

        assertMatches(expected, actual);
    }

    @Test
    void matchesSwitchHazardAndEntryAbilityAgainstBattleLog() {
        SharedSearchProjectionState initial = state(
                1,
                List.of(0, 0),
                List.of(List.of(100, 100), List.of(100)),
                List.of(false, false),
                List.of(List.of(1, 0, 0, 0), List.of(0, 0, 0, 0)),
                emptyPressures(2, 1),
                emptyRanks(2, 1),
                new SharedSearchFieldState(null, null, Map.of()),
                List.of(Map.of(), Map.of()));
        SharedSwitchPhaseResult phase = SharedSwitchPhaseEvaluator.INSTANCE.evaluate(
                new SharedSwitchPhaseInput(
                        100, 100, "", "", false,
                        100, 100, "intimidate", "test", "", List.of("normal"), Map.of(),
                        true, true, false, Set.of(), Map.of(),
                        1, 0, 0, 0, false, false, false,
                        true, "", 100.0, 100.0, "", List.of(), "", ""));
        SharedSearchProjectionState expected = SharedSearchProjectionRuntime.INSTANCE.transition(
                initial, switchAction(0, 1, phase), idle(1, false));
        ShowdownBattleLogObservation actual = ShowdownBattleLogObservation.parse(List.of(
                "|turn|1",
                "|-sidestart|p1: Player|move: Stealth Rock",
                "|switch|p1a: Old|Old, L50|100/100",
                "|switch|p2a: Enemy|Enemy, L50|100/100",
                "|switch|p1a: New|New, L50|100/100",
                "|-damage|p1a: New|88/100|[from] Stealth Rock",
                "|-unboost|p2a: Enemy|atk|1",
                "|turn|2"));

        assertMatches(expected, actual);
    }

    @Test
    void matchesTimedFieldPressureRanksAndConsumedGimmickAgainstBattleLog() {
        List<List<SharedSearchPressure>> pressures = List.of(
                List.of(new SharedSearchPressure(true, 2, false, 1, 0)),
                List.of(new SharedSearchPressure(false, 0, false, 0, 0)));
        List<List<List<Integer>>> ranks = List.of(
                List.of(List.of(1, 0, 0, 0, 0)),
                List.of(List.of(0, 0, 0, 0, 0)));
        SharedSearchProjectionState initial = state(
                1,
                List.of(0, 0),
                List.of(List.of(100), List.of(100)),
                List.of(true, false),
                List.of(List.of(0, 0, 0, 0), List.of(0, 0, 0, 0)),
                pressures,
                ranks,
                new SharedSearchFieldState(
                        new SharedSearchTimedEffect("raindance", 5, false), null, Map.of()),
                List.of(
                        Map.of("reflect", new SharedSearchTimedEffect("reflect", 5, false)),
                        Map.of()));
        SharedSearchProjectionState expected = SharedSearchProjectionRuntime.INSTANCE.transition(
                initial, idle(0, true), idle(1, false));
        ShowdownBattleLogObservation actual = ShowdownBattleLogObservation.parse(List.of(
                "|-weather|RainDance",
                "|-sidestart|p1: Player|move: Reflect",
                "|switch|p1a: Player|Player, L50|100/100 tox",
                "|switch|p2a: Enemy|Enemy, L50|100/100",
                "|-start|p1a: Player|move: Yawn",
                "|-boost|p1a: Player|atk|1",
                "|turn|1",
                "|-terastallize|p1a: Player|Water",
                "|turn|2"));

        assertMatches(expected, actual);
    }

    private static void assertMatches(
            SharedSearchProjectionState expected,
            ShowdownBattleLogObservation actual
    ) {
        SharedProjectionDifferentialResult result =
                SharedProjectionDifferentialEvaluator.INSTANCE.evaluate(
                        expected, actual.projectionObservation(List.of("p1", "p2")));
        assertTrue(result.getMatches(), () -> "projection differences: " + result.getDifferences());
    }

    private static void assertAdapterLog(String marker, String activationLine) {
        SharedSearchProjectionState expected = new SharedSearchProjectionState(
                1,
                List.of(0, 0),
                List.of(List.of(100), List.of(100)),
                List.of(List.of(100), List.of(100)),
                List.of(false, false),
                List.of(List.of(), List.of()),
                List.of(List.of(0, 0, 0, 0), List.of(0, 0, 0, 0)),
                emptyPressures(1, 1),
                emptyRanks(1, 1),
                List.of(List.of(""), List.of("")),
                List.of(List.of(Set.of(marker)), List.of(Set.of())),
                new SharedSearchFieldState(null, null, Map.of()),
                List.of(Map.of(), Map.of()),
                List.of(), List.of(), List.of());
        ShowdownBattleLogObservation actual = ShowdownBattleLogObservation.parse(List.of(
                "|turn|1",
                "|switch|p1a: User|User, L50|100/100",
                "|switch|p2a: Target|Target, L50|100/100",
                activationLine));
        assertMatches(expected, actual);
    }

    private static SharedSearchProjectionState state(
            int turn,
            List<Integer> active,
            List<List<Integer>> hp,
            List<Boolean> gimmicks,
            List<List<Integer>> hazards,
            List<List<SharedSearchPressure>> pressures,
            List<List<List<Integer>>> ranks,
            SharedSearchFieldState field,
            List<Map<String, SharedSearchTimedEffect>> sideConditions
    ) {
        List<List<Integer>> maximumHp = hp.stream()
                .map(side -> side.stream().map(ignored -> 100).toList()).toList();
        return new SharedSearchProjectionState(
                turn, active, hp, maximumHp, gimmicks,
                List.of(List.of(), List.of()), hazards, pressures, ranks,
                hp.stream().map(side -> side.stream().map(ignored -> "").toList()).toList(),
                hp.stream().map(side -> side.stream().map(ignored -> Set.<String>of()).toList()).toList(),
                field, sideConditions,
                List.of(), List.of(), List.of());
    }

    private static SharedProjectedSearchAction switchAction(
            int side,
            int slot,
            SharedSwitchPhaseResult phase
    ) {
        return projected(action("switch:test", "switch"), side, slot, false, phase);
    }

    private static SharedProjectedSearchAction idle(int side, boolean consumesGimmick) {
        return projected(action("move:splash", consumesGimmick ? "gimmick" : "move"),
                side, -1, consumesGimmick, null);
    }

    private static SharedProjectedSearchAction projected(
            SearchAction action,
            int side,
            int switchSlot,
            boolean consumesGimmick,
            SharedSwitchPhaseResult phase
    ) {
        return new SharedProjectedSearchAction(
                action, side, switchSlot, -1, -1, 0, 0.0, 1.0, -1, "", Map.of(), -1,
                consumesGimmick, "", "", "", "", 0, 0, List.of(), List.of(), phase);
    }

    private static SearchAction action(String id, String kind) {
        return new SearchAction(id, kind, 0.0, 1.0, 0.0, false, false, false, 0.0, false);
    }

    private static List<List<SharedSearchPressure>> emptyPressures(int firstSize, int secondSize) {
        return List.of(
                java.util.stream.IntStream.range(0, firstSize)
                        .mapToObj(ignored -> new SharedSearchPressure(false, 0, false, 0, 0)).toList(),
                java.util.stream.IntStream.range(0, secondSize)
                        .mapToObj(ignored -> new SharedSearchPressure(false, 0, false, 0, 0)).toList());
    }

    private static List<List<List<Integer>>> emptyRanks(int firstSize, int secondSize) {
        return List.of(
                java.util.stream.IntStream.range(0, firstSize)
                        .mapToObj(ignored -> List.of(0, 0, 0, 0, 0)).toList(),
                java.util.stream.IntStream.range(0, secondSize)
                        .mapToObj(ignored -> List.of(0, 0, 0, 0, 0)).toList());
    }
}
