@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class BatonPassTargetObservation(
    val targetHp: Double,
    val baselineDamage: Double,
    val boostedDamage: Double,
)

@Serializable
data class BatonPassFactInput(
    val available: Boolean = false,
    val targetAvailable: Boolean = false,
    val targetSlot: Int = 0,
    val targetName: String = "",
    val targetAce: Boolean = false,
    val currentBoosts: Map<String, Double> = emptyMap(),
    val passedBoosts: Map<String, Double> = emptyMap(),
    val canRaiseSweepFurther: Boolean = false,
    val canRaiseDefenseFurther: Boolean = false,
    val targets: List<BatonPassTargetObservation> = emptyList(),
)

@Serializable
data class BatonPassFactResult(
    val batonPassAvailable: Boolean = false,
    val batonPassTargetAvailable: Boolean = false,
    val batonPassTargetSlot: Int = 0,
    val batonPassTargetName: String = "",
    val batonPassTargetAce: Boolean = false,
    val batonPassCurrentBoostTotal: Double = 0.0,
    val batonPassCurrentSweepBoostTotal: Double = 0.0,
    val batonPassCurrentDefensiveBoostTotal: Double = 0.0,
    val batonPassCanRaiseSweepFurther: Boolean = false,
    val batonPassCanRaiseDefenseFurther: Boolean = false,
    val batonPassBoostTotal: Double = 0.0,
    val batonPassAdditionalBoostTotal: Double = 0.0,
    val batonPassNewKoTargets: Int = 0,
    val batonPassSafeKoTargets: Int = 0,
    val batonPassPressureGain: Double = 0.0,
    val batonPassTransferValue: Double = 0.0,
)

object SharedBatonPassFactDeriver {
    private val sweepStats = setOf("attack", "specialAttack", "speed")
    private val defensiveStats = setOf("defence", "specialDefence")
    private val transferableStats = sweepStats + defensiveStats

    fun derive(input: BatonPassFactInput): BatonPassFactResult {
        if (!input.available) return BatonPassFactResult()
        if (!input.targetAvailable) return BatonPassFactResult(batonPassAvailable = true)
        fun total(boosts: Map<String, Double>, stats: Set<String>) =
            stats.sumOf { max(0.0, boosts[it] ?: 0.0) }
        val currentSweep = total(input.currentBoosts, sweepStats)
        val currentDefense = total(input.currentBoosts, defensiveStats)
        val current = total(input.currentBoosts, transferableStats)
        val passed = total(input.passedBoosts, transferableStats)
        var newKos = 0
        var safeKos = 0
        var pressure = 0.0
        input.targets.forEach { target ->
            val hp = max(1.0, target.targetHp)
            val baselineKo = target.baselineDamage >= target.targetHp
            val boostedKo = target.boostedDamage >= target.targetHp
            if (boostedKo && !baselineKo) newKos++
            if (boostedKo) safeKos++
            pressure += max(0.0, min(1.25, target.boostedDamage / hp) - min(1.25, target.baselineDamage / hp))
        }
        val roundedPressure = round2(pressure)
        val value = passed * 14.0 + newKos * 55.0 + safeKos * 8.0 + pressure * 36.0
        return BatonPassFactResult(
            true, true, input.targetSlot, input.targetName, input.targetAce,
            current, currentSweep, currentDefense,
            input.canRaiseSweepFurther, input.canRaiseDefenseFurther,
            passed, max(0.0, passed - current), newKos, safeKos,
            roundedPressure, round2(value),
        )
    }
}

@JsExport
fun deriveBatonPassFactsJson(inputJson: String): String = codec.encodeToString(
    SharedBatonPassFactDeriver.derive(codec.decodeFromString<BatonPassFactInput>(inputJson)),
)

private fun round2(value: Double): Double = round(value * 100.0) / 100.0
