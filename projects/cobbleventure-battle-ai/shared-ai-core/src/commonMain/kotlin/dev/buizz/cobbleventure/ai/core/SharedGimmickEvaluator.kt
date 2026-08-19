@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class ProjectedGimmickInput(
    val id: String = "",
    val selectedScore: Double = 0.0,
    val baseScore: Double = 0.0,
    val selectedStateDelta: Double? = null,
    val baseStateDelta: Double? = null,
    val configured: Boolean = false,
    val activationThreshold: Double? = null,
)

@Serializable
data class ProjectedGimmickScore(
    val id: String,
    val score: Double,
    val scoreDifference: Double,
    val configuredBonus: Double,
    val stateDeltaDifference: Double? = null,
    val activationThreshold: Double,
    val viable: Boolean,
)

object SharedGimmickEvaluator {
    fun score(input: ProjectedGimmickInput): ProjectedGimmickScore {
        val id = normalizeGimmick(input.id)
        val scoreDifference = roundGimmick(finiteGimmick(input.selectedScore) - finiteGimmick(input.baseScore))
        val configuredBonus = if (input.configured) when (id) {
            "mega" -> 8.0
            "terastallize" -> 3.0
            else -> 0.0
        } else 0.0
        val score = roundGimmick(scoreDifference + configuredBonus)
        val stateDeltaDifference = if (input.selectedStateDelta != null && input.baseStateDelta != null) {
            roundGimmick(finiteGimmick(input.selectedStateDelta) - finiteGimmick(input.baseStateDelta))
        } else null
        val threshold = input.activationThreshold?.let(::finiteGimmick) ?: defaultThreshold(id)
        return ProjectedGimmickScore(
            id = id,
            score = score,
            scoreDifference = scoreDifference,
            configuredBonus = configuredBonus,
            stateDeltaDifference = stateDeltaDifference,
            activationThreshold = threshold,
            viable = score >= threshold,
        )
    }

    fun scoreJson(inputJson: String): String = codec.encodeToString(
        score(codec.decodeFromString<ProjectedGimmickInput>(inputJson)),
    )

    private fun defaultThreshold(id: String): Double = when (id) {
        "terastallize" -> 5.0
        "dynamax", "gigantamax" -> 18.0
        else -> 0.0
    }
}

@JsExport
fun scoreProjectedGimmickJson(inputJson: String): String =
    SharedGimmickEvaluator.scoreJson(inputJson)

private fun normalizeGimmick(value: String): String {
    val normalized = value.lowercase().filter { it.isLetterOrDigit() }
    return when (normalized) {
        "tera", "terastal", "terastallization" -> "terastallize"
        "gmax", "max" -> "dynamax"
        "z", "zmove" -> "zmove"
        else -> normalized
    }
}

private fun finiteGimmick(value: Double): Double = if (value.isFinite()) value else 0.0
private fun roundGimmick(value: Double): Double = round(value * 100.0) / 100.0
