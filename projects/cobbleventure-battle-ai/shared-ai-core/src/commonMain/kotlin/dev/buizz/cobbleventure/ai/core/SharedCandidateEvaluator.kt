@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class CandidateAdjustment(
    val code: String = "",
    val weight: Double = 0.0,
)

@Serializable
data class CandidateScoreInput(
    val kind: String = "move",
    val difficulty: String = "standard",
    val strategy: String = "balanced",
    val expectedDamage: Double? = null,
    val power: Double = 0.0,
    val accuracy: Double = 1.0,
    val priority: Double = 0.0,
    val statusMove: Boolean = false,
    val tacticalValue: Double = 0.0,
    val roleValue: Double = 0.0,
    val koChance: String = "none",
    val hpRatio: Double = 0.0,
    val matchupValue: Double = 0.0,
    val baseValue: Double = 0.0,
    val adjustments: List<CandidateAdjustment> = emptyList(),
)

/** 플랫폼 원본 명중률과 전술 사실을 공통 점수 입력으로 변환하는 경계 계약. */
@Serializable
data class CandidateScoreFacts(
    val kind: String = "move",
    val difficulty: String = "standard",
    val strategy: String = "balanced",
    val expectedDamage: Double? = null,
    val power: Double = 0.0,
    val accuracyPercent: Double? = null,
    val alwaysHits: Boolean = false,
    val priority: Double = 0.0,
    val statusMove: Boolean = false,
    val tacticalValue: Double = 0.0,
    val roleValue: Double = 0.0,
    val koChance: String = "none",
    val hpRatio: Double = 0.0,
    val matchupValue: Double = 0.0,
    val baseValue: Double = 0.0,
    val adjustments: List<CandidateAdjustment> = emptyList(),
)

@Serializable
data class TrainerItemCandidateFacts(
    val healing: Double = 0.0,
    val curedStatusValue: Double = 0.0,
    val preventsImmediateKnockout: Boolean = false,
    val incomingDamage: Double = 0.0,
    val futureRoleValue: Double = 0.0,
    val resourceCost: Double = 0.0,
    val lethalAfterUse: Boolean = false,
    val inefficientPotion: Boolean = false,
    val strongMoveAvailable: Boolean = false,
    val baseUtility: Double = 0.0,
)

@Serializable
data class TrainerItemCandidateScore(
    val score: Double,
    val components: Map<String, Double>,
)

@Serializable
data class CandidateScore(
    val score: Double,
    val components: Map<String, Double>,
)

object SharedCandidateEvaluator {
    fun input(facts: CandidateScoreFacts) = CandidateScoreInput(
        kind = facts.kind,
        difficulty = facts.difficulty,
        strategy = facts.strategy,
        expectedDamage = facts.expectedDamage?.let(::finiteCandidate),
        power = finiteCandidate(facts.power),
        accuracy = when {
            facts.alwaysHits || facts.accuracyPercent == null -> 1.0
            else -> (finiteCandidate(facts.accuracyPercent) / 100.0).coerceIn(0.0, 1.0)
        },
        priority = finiteCandidate(facts.priority),
        statusMove = facts.statusMove,
        tacticalValue = finiteCandidate(facts.tacticalValue),
        roleValue = finiteCandidate(facts.roleValue),
        koChance = facts.koChance,
        hpRatio = finiteCandidate(facts.hpRatio),
        matchupValue = finiteCandidate(facts.matchupValue),
        baseValue = finiteCandidate(facts.baseValue),
        adjustments = facts.adjustments,
    )

    fun score(facts: CandidateScoreFacts): CandidateScore = score(input(facts))

    fun score(input: CandidateScoreInput): CandidateScore {
        val adjustment = input.adjustments.sumOf { finiteCandidate(it.weight) }
        val components = when (input.kind.lowercase()) {
            "switch" -> switchComponents(input, adjustment)
            "item", "gimmick" -> linkedMapOf(
                "base" to finiteCandidate(input.baseValue),
                "rules" to adjustment,
            )
            else -> moveComponents(input, adjustment)
        }
        return CandidateScore(
            score = components.values.sum(),
            components = components,
        )
    }

    fun scoreJson(inputJson: String): String = codec.encodeToString(
        score(codec.decodeFromString<CandidateScoreInput>(inputJson)),
    )

    fun scoreFactsJson(inputJson: String): String = codec.encodeToString(
        score(codec.decodeFromString<CandidateScoreFacts>(inputJson)),
    )

    fun scoreTrainerItem(facts: TrainerItemCandidateFacts): TrainerItemCandidateScore {
        val components = linkedMapOf(
            "healing" to finiteCandidate(facts.healing).coerceAtLeast(0.0) * 0.72,
            "statusCure" to finiteCandidate(facts.curedStatusValue).coerceAtLeast(0.0),
            "immediateSurvival" to if (facts.preventsImmediateKnockout) 95.0 else 0.0,
            "incomingExposure" to -minOf(90.0, finiteCandidate(facts.incomingDamage).coerceAtLeast(0.0) * 0.2),
            "futureRole" to finiteCandidate(facts.futureRoleValue),
            "resource" to -finiteCandidate(facts.resourceCost).coerceAtLeast(0.0),
            "lethalAfterUse" to if (facts.lethalAfterUse) -260.0 else 0.0,
            "inefficientPotion" to if (facts.inefficientPotion) -12.0 else 0.0,
            "strongMoveOpportunity" to if (facts.strongMoveAvailable && !facts.preventsImmediateKnockout) -35.0 else 0.0,
            "baseUtility" to finiteCandidate(facts.baseUtility),
        )
        return TrainerItemCandidateScore(components.values.sum(), components)
    }

    private fun moveComponents(input: CandidateScoreInput, adjustment: Double): Map<String, Double> {
        val accuracy = finiteCandidate(input.accuracy).coerceIn(0.0, 1.0)
        val powerWeight = when (input.strategy.lowercase()) {
            "aggressive" -> 1.2
            "defensive" -> 0.82
            else -> 1.0
        }
        val accuracyWeight = if (input.strategy.equals("defensive", true)) accuracy * accuracy else accuracy
        val directValue = finiteCandidate(input.expectedDamage ?: input.power)
        val priorityWeight = when (input.difficulty.lowercase()) {
            "expert", "expert_winrate", "expert_search", "cheater" -> 12.0
            else -> 5.0
        }
        val status = if (input.statusMove) when (input.strategy.lowercase()) {
            "defensive" -> 38.0
            "balanced" -> 12.0
            else -> 4.0
        } else 0.0
        val knockout = when (input.koChance.lowercase()) {
            "guaranteed" -> 55.0 * accuracy
            "possible" -> 25.0 * accuracy
            else -> 0.0
        }
        return linkedMapOf(
            "direct" to directValue * powerWeight * accuracyWeight,
            "priority" to finiteCandidate(input.priority) * priorityWeight,
            "status" to status,
            "tactical" to finiteCandidate(input.tacticalValue),
            "role" to finiteCandidate(input.roleValue),
            "rules" to adjustment,
            "knockout" to knockout,
        )
    }

    private fun switchComponents(input: CandidateScoreInput, adjustment: Double) = linkedMapOf(
        "damage" to finiteCandidate(input.expectedDamage ?: 0.0),
        "matchup" to finiteCandidate(input.matchupValue),
        "health" to finiteCandidate(input.hpRatio).coerceIn(0.0, 1.0) * 10.0,
        "rules" to adjustment,
    )
}

@JsExport
fun scoreActionCandidateJson(inputJson: String): String =
    SharedCandidateEvaluator.scoreJson(inputJson)

@JsExport
fun scoreObservedActionCandidateJson(inputJson: String): String =
    SharedCandidateEvaluator.scoreFactsJson(inputJson)

@JsExport
fun scoreSharedTrainerItemCandidateJson(inputJson: String): String = codec.encodeToString(
    SharedCandidateEvaluator.scoreTrainerItem(codec.decodeFromString<TrainerItemCandidateFacts>(inputJson)),
)

private fun finiteCandidate(value: Double): Double = if (value.isFinite()) value else 0.0
