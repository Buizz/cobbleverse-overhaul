package dev.buizz.cobbleventure.battleai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShowdownBattleLogObservationTest {
    @Test
    void reconstructsHazardLayersAndRemoval() {
        ShowdownBattleLogObservation observation = ShowdownBattleLogObservation.parse(List.of(
                "|-sidestart|p2: Rival|move: Stealth Rock",
                "|-sidestart|p2: Rival|move: Spikes",
                "|-sidestart|p2: Rival|move: Spikes",
                "|-sideend|p2: Rival|move: Stealth Rock"));

        assertEquals(0, observation.hazardLayers("p2", "stealthrock"));
        assertEquals(2, observation.hazardLayers("p2", "spikes"));
    }

    @Test
    void reconstructsYawnSaltCureAndToxicProgress() {
        ShowdownBattleLogObservation observation = ShowdownBattleLogObservation.parse(List.of(
                "|turn|1",
                "|switch|p1a: Garganacl|Garganacl, L50|200/200",
                "|-start|p1a: Garganacl|move: Yawn",
                "|-start|p1a: Garganacl|Salt Cure",
                "|-status|p1a: Garganacl|tox",
                "|-damage|p1a: Garganacl|180/200 tox|[from] psn",
                "|turn|2"));

        ShowdownBattleLogObservation.ActivePressure pressure = observation.pressure("p1a");
        assertTrue(pressure.yawn());
        assertEquals(1, pressure.yawnTurns());
        assertTrue(pressure.saltCure());
        assertEquals(2, pressure.toxicCounter());
    }

    @Test
    void reconstructsAndTicksTheCompleteTimedBattlefield() {
        ShowdownBattleLogObservation observation = ShowdownBattleLogObservation.parse(List.of(
                "|-weather|RainDance",
                "|-fieldstart|move: Electric Terrain",
                "|-fieldstart|move: Trick Room",
                "|-sidestart|p1: Trainer|move: Reflect",
                "|-sidestart|p1: Trainer|move: Tailwind",
                "|turn|1",
                "|turn|2"));

        assertEquals("raindance", observation.fieldState().getWeather().getId());
        assertEquals(4, observation.fieldState().getWeather().getTurns());
        assertEquals("electricterrain", observation.fieldState().getTerrain().getId());
        assertEquals(4, observation.fieldState().getPseudoWeather().get("trickroom").getTurns());
        assertEquals(4, observation.sideConditions("p1").get("reflect").getTurns());
        assertEquals(3, observation.sideConditions("p1").get("tailwind").getTurns());
    }

    @Test
    void switchClearsVolatilesAndResetsToxicStageFromCondition() {
        ShowdownBattleLogObservation observation = ShowdownBattleLogObservation.parse(List.of(
                "|-start|p1a: First|move: Yawn",
                "|-start|p1a: First|Salt Cure",
                "|-status|p1a: First|tox",
                "|switch|p1a: Second|Clodsire, L50|120/200 tox"));

        ShowdownBattleLogObservation.ActivePressure pressure = observation.pressure("p1a");
        assertFalse(pressure.yawn());
        assertFalse(pressure.saltCure());
        assertEquals(1, pressure.toxicCounter());
    }

    @Test
    void confirmsThatBatonPassActuallyExecutedAfterTheDecisionCursor() {
        List<String> log = List.of(
                "|turn|3",
                "|move|p2a: Support|Baton Pass|p2a: Support",
                "|-end|p2a: Support|move: Baton Pass");

        assertTrue(ShowdownBattleLogObservation.hasMoveSince(log, 1, "p2a", "batonpass"));
        assertFalse(ShowdownBattleLogObservation.hasMoveSince(log, 2, "p2a", "batonpass"));
    }
}
