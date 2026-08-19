@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class RecoveryFactInput(
    val currentHp: Double,
    val maxHp: Double,
    val healFraction: Double = 0.0,
    val fullHeal: Boolean = false,
    val exposureTurns: Int = 1,
    val opponentBestDamage: Double = 0.0,
)

@Serializable
data class RecoveryFactResult(
    val recoveryAmount: Double,
    val recoveryValue: Double,
    val recoveryExposureTurns: Int,
    val recoveryExpectedIncomingDamage: Double,
    val recoveryNetHpChange: Double,
)

@Serializable
data class ResidualPressureInput(
    val currentHp: Double,
    val maxHp: Double,
    val yawnActive: Boolean = false,
    val yawnTurns: Int = 0,
    val sleepExploitable: Boolean = false,
    val saltCureActive: Boolean = false,
    val saltCureResidualDamage: Double = 0.0,
    val toxicCounter: Int = 0,
    val ignoresResidualDamage: Boolean = false,
)

@Serializable
data class ResidualPressureResult(
    val stayPressurePenalty: Double,
    val yawnSwitchPressure: Double,
    val yawnTurns: Int,
    val sleepExploitable: Boolean,
    val saltCureSwitchPressure: Double,
    val saltCureResidualDamage: Double,
    val toxicSwitchPressure: Double,
    val toxicCounter: Int,
    val toxicNextDamage: Double,
    val toxicFollowingDamage: Double,
    val toxicImmediateLethal: Boolean,
    val toxicTwoTurnLethal: Boolean,
    val urgentSwitchPressure: Boolean,
)

@Serializable
data class HazardLayerInput(val conditionId: String, val currentLayers: Int = 0)

@Serializable
data class HazardLayerResult(val conditionId: String, val maximumLayers: Int, val layerDelta: Int)

@Serializable
data class SaltCureDamageInput(val maxHp: Double, val waterOrSteel: Boolean = false)

object SharedSustainmentFactDeriver {
    fun recovery(input: RecoveryFactInput): RecoveryFactResult {
        val missing = max(0.0, input.maxHp - input.currentHp)
        val fractional = if (input.healFraction > 0.0) max(1.0, floor(input.maxHp * input.healFraction)) else 0.0
        val amount = if (input.fullHeal) missing else min(missing, fractional)
        val turns = if (amount > 0.0) max(1, input.exposureTurns) else 0
        val incoming = if (turns > 0) max(0.0, input.opponentBestDamage) * turns else 0.0
        return RecoveryFactResult(amount, amount * 0.75, turns, incoming, amount - incoming)
    }

    fun residualPressure(input: ResidualPressureInput): ResidualPressureResult {
        val maxHp = max(1.0, input.maxHp)
        val hpRatio = (input.currentHp / maxHp).coerceIn(0.0, 1.0)
        val yawn = if (input.yawnActive && !input.sleepExploitable) {
            if (input.yawnTurns <= 1) 220.0 else 110.0
        } else 0.0
        val saltDamage = if (input.saltCureActive && !input.ignoresResidualDamage) {
            max(0.0, input.saltCureResidualDamage)
        } else 0.0
        val salt = if (saltDamage > 0.0) min(150.0, saltDamage * (0.55 + (1.0 - hpRatio) * 0.9)) else 0.0
        val toxicCounter = if (!input.ignoresResidualDamage) max(0, input.toxicCounter) else 0
        val toxicNext = if (toxicCounter > 0) max(1.0, floor(maxHp * toxicCounter / 16.0)) else 0.0
        val toxicFollowing = if (toxicCounter > 0) max(1.0, floor(maxHp * min(15, toxicCounter + 1) / 16.0)) else 0.0
        val toxic = if (toxicCounter > 0) min(220.0, (toxicNext + toxicFollowing) * (0.5 + (1.0 - hpRatio) * 0.45)) else 0.0
        return ResidualPressureResult(
            round2(yawn + salt + toxic), yawn, input.yawnTurns, input.sleepExploitable,
            round2(salt), saltDamage, round2(toxic), toxicCounter, toxicNext, toxicFollowing,
            toxicCounter > 0 && toxicNext >= input.currentHp,
            toxicCounter > 0 && toxicNext + toxicFollowing >= input.currentHp,
            yawn >= 220.0 || (toxicCounter > 0 && toxicNext + toxicFollowing >= input.currentHp),
        )
    }

    fun hazard(input: HazardLayerInput): HazardLayerResult {
        val id = cleanId(input.conditionId)
        val maximum = when (id) { "spikes" -> 3; "toxicspikes" -> 2; else -> 1 }
        return HazardLayerResult(id, maximum, if (input.currentLayers < maximum) 1 else 0)
    }

    fun saltCureDamage(input: SaltCureDamageInput): Double =
        max(1.0, floor(input.maxHp / if (input.waterOrSteel) 4.0 else 8.0))
}

@JsExport
fun deriveRecoveryFactsJson(inputJson: String): String = codec.encodeToString(
    SharedSustainmentFactDeriver.recovery(codec.decodeFromString<RecoveryFactInput>(inputJson)),
)

@JsExport
fun deriveResidualPressureJson(inputJson: String): String = codec.encodeToString(
    SharedSustainmentFactDeriver.residualPressure(codec.decodeFromString<ResidualPressureInput>(inputJson)),
)

@JsExport
fun deriveHazardLayerFactsJson(inputJson: String): String = codec.encodeToString(
    SharedSustainmentFactDeriver.hazard(codec.decodeFromString<HazardLayerInput>(inputJson)),
)

@JsExport
fun deriveSaltCureDamageJson(inputJson: String): String = codec.encodeToString(
    SharedSustainmentFactDeriver.saltCureDamage(codec.decodeFromString<SaltCureDamageInput>(inputJson)),
)

private fun cleanId(value: String): String = value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }
private fun round2(value: Double): Double = round(value * 100.0) / 100.0
