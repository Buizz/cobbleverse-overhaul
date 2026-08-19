@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 플랫폼 어댑터가 관측한 행동을 탐색 코어의 단일 후보 계약으로 정규화한다. */
@Serializable
data class SharedSearchCandidateObservation(
    val id: String = "",
    val kind: String = "move",
    val score: Double = 0.0,
    val successProbability: Double = 1.0,
    val expectedDamage: Double = 0.0,
    val nonConsecutive: Boolean = false,
    val statusMove: Boolean = false,
    val guaranteedKnockout: Boolean = false,
    val opponentKnockoutBeforeActionProbability: Double = 0.0,
    val heuristicSelected: Boolean = false,
    val legal: Boolean = true,
    val disabled: Boolean = false,
)

object SharedSearchCandidateGenerator {
    fun generate(observations: List<SharedSearchCandidateObservation>): List<SearchAction> =
        observations.asSequence()
            .filter { it.id.isNotBlank() && it.legal && !it.disabled }
            .map(::normalize)
            .sortedByDescending { it.score }
            .distinctBy { it.id }
            .toList()

    private fun normalize(observation: SharedSearchCandidateObservation) = SearchAction(
        id = observation.id,
        kind = observation.kind.trim().lowercase().ifBlank { "move" },
        score = finiteSearchCandidate(observation.score),
        successProbability = finiteSearchCandidate(observation.successProbability).coerceIn(0.0, 1.0),
        expectedDamage = finiteSearchCandidate(observation.expectedDamage).coerceAtLeast(0.0),
        nonConsecutive = observation.nonConsecutive,
        statusMove = observation.statusMove,
        guaranteedKnockout = observation.guaranteedKnockout,
        opponentKnockoutBeforeActionProbability =
            finiteSearchCandidate(observation.opponentKnockoutBeforeActionProbability).coerceIn(0.0, 1.0),
        heuristicSelected = observation.heuristicSelected,
    )
}

@JsExport
fun generateSharedSearchActionsJson(inputJson: String): String = codec.encodeToString(
    SharedSearchCandidateGenerator.generate(
        codec.decodeFromString<List<SharedSearchCandidateObservation>>(inputJson),
    ),
)

private fun finiteSearchCandidate(value: Double): Double = if (value.isFinite()) value else 0.0
