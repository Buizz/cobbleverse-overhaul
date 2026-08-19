@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class BattleValueMemberInput(
    val hpRatio: Double = 0.0,
    val living: Boolean = false,
    val aceCandidate: Boolean = false,
    val positiveBoosts: Double = 0.0,
    val statusBurden: Double = 0.0,
    val uniqueCounter: Boolean = false,
)

@Serializable
data class BattleValueSideInput(
    val members: List<BattleValueMemberInput> = emptyList(),
    val hazardLayers: Double = 0.0,
    val gimmicksRemaining: Double = 0.0,
    val matchupCoverage: Double = 0.0,
    val safeKoCoverage: Double = 0.0,
    val benchReadiness: Double = 0.0,
    val sweepPotential: Double = 0.0,
)

object SharedBattleObservation {
    fun valueSide(input: BattleValueSideInput): BattleValueSide {
        val living = input.members.filter { it.living }
        val aces = input.members.filter { it.aceCandidate }
        return BattleValueSide(
            teamSize = input.members.size.toDouble(),
            livingCount = living.size.toDouble(),
            totalHpRatio = input.members.sumOf { finiteObservation(it.hpRatio).coerceIn(0.0, 1.0) },
            aceCandidateCount = aces.size.toDouble(),
            aceAliveCount = aces.count { it.living }.toDouble(),
            aceHpRatio = aces.sumOf { finiteObservation(it.hpRatio).coerceIn(0.0, 1.0) },
            positiveBoosts = living.sumOf { finiteObservation(it.positiveBoosts).coerceAtLeast(0.0) },
            statusBurden = living.sumOf { finiteObservation(it.statusBurden).coerceAtLeast(0.0) },
            hazardLayers = finiteObservation(input.hazardLayers).coerceAtLeast(0.0),
            uniqueCountersAlive = living.count { it.uniqueCounter }.toDouble(),
            gimmicksRemaining = finiteObservation(input.gimmicksRemaining).coerceAtLeast(0.0),
            matchupCoverage = finiteObservation(input.matchupCoverage).coerceIn(0.0, 1.0),
            safeKoCoverage = finiteObservation(input.safeKoCoverage).coerceIn(0.0, 1.0),
            benchReadiness = finiteObservation(input.benchReadiness).coerceIn(0.0, 1.0),
            sweepPotential = finiteObservation(input.sweepPotential).coerceIn(0.0, 1.0),
        )
    }

    fun valueSideJson(inputJson: String): String = codec.encodeToString(
        valueSide(codec.decodeFromString<BattleValueSideInput>(inputJson)),
    )
}

@JsExport
fun extractBattleValueSideJson(inputJson: String): String =
    SharedBattleObservation.valueSideJson(inputJson)

private fun finiteObservation(value: Double): Double = if (value.isFinite()) value else 0.0
