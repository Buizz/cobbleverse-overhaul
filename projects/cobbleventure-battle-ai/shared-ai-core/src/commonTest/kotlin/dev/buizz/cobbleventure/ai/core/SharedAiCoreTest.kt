package dev.buizz.cobbleventure.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedAiCoreTest {
    @Test
    fun winEstimateUsesTheSameExplainableModelOnEveryTarget() {
        val estimate = SharedAiCore.estimateWinProbability(
            BattleValueState(
                own = BattleValueSide(teamSize = 3.0, livingCount = 3.0, totalHpRatio = 2.4),
                opponent = BattleValueSide(teamSize = 3.0, livingCount = 2.0, totalHpRatio = 1.1),
            ),
        )

        assertEquals("heuristic-logistic-v3", estimate.modelVersion)
        assertEquals(101.2, estimate.rawValue)
        assertEquals(0.7548, estimate.rawProbability)
    }

    @Test
    fun winRateSearchOverridesTheHeuristicOnlyForAMeaningfulGain() {
        val decision = SharedAiCore.decideWinRate("root", 0, 8, WinRateFixture())

        assertEquals("risky", decision.selected?.id)
        assertTrue(decision.policyOverride)
        assertEquals(2, decision.visitedNodes)
    }

    @Test
    fun twoTurnSearchExtendsCloseCandidates() {
        val decision = SharedAiCore.decideTwoTurn("root", 0, 10, TwoTurnFixture())

        assertEquals("setup", decision.selected?.id)
        assertEquals(2, decision.depthTurns)
        assertEquals(4, decision.visitedNodes)
    }
}

private class WinRateFixture : SearchRuntime {
    override fun candidates(state: String, sideIndex: Int): List<SearchAction> = when (sideIndex) {
        0 -> listOf(action("safe", 100.0), action("risky", 90.0))
        else -> listOf(action("reply", 100.0))
    }

    override fun transition(state: String, sideZeroActionId: String, sideOneActionId: String): String =
        if (sideZeroActionId == "risky") "risky-result" else "safe-result"

    override fun winProbability(state: String, sideIndex: Int): Double = when (state) {
        "risky-result" -> 0.8
        "safe-result" -> 0.55
        else -> 0.5
    }

    override fun terminal(state: String): Boolean = false
}

private class TwoTurnFixture : SearchRuntime {
    override fun candidates(state: String, sideIndex: Int): List<SearchAction> {
        if (sideIndex == 1) return listOf(action("reply", 100.0))
        return when (state) {
            "root" -> listOf(action("setup", 100.0), action("damage", 99.0))
            "setup-result" -> listOf(action("finish", 100.0))
            "damage-result" -> listOf(action("chip", 100.0))
            else -> emptyList()
        }
    }

    override fun transition(state: String, sideZeroActionId: String, sideOneActionId: String): String = when {
        state == "root" && sideZeroActionId == "setup" -> "setup-result"
        state == "root" -> "damage-result"
        state == "setup-result" -> "setup-finish"
        else -> "damage-finish"
    }

    override fun winProbability(state: String, sideIndex: Int): Double = when (state) {
        "setup-result" -> 0.50
        "damage-result" -> 0.51
        "setup-finish" -> 0.90
        "damage-finish" -> 0.52
        else -> 0.5
    }

    override fun terminal(state: String): Boolean = false
}

private fun action(id: String, score: Double) = SearchAction(id = id, score = score)
