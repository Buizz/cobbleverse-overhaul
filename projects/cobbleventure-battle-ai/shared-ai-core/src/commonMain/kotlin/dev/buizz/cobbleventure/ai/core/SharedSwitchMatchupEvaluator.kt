@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.floor

@Serializable
data class EntryHazardObservation(
    val currentHp: Double,
    val maximumHp: Double,
    val stealthRockLayers: Int = 0,
    val spikesLayers: Int = 0,
    val rockEffectiveness: Double = 1.0,
    val types: List<String> = emptyList(),
    val ability: String = "",
    val item: String = "",
    val grounded: Boolean? = null,
    val ignoresHazards: Boolean? = null,
)

@Serializable
data class EntryHazardResult(
    val damage: Double,
    val damageRatio: Double,
    val hpAfterHazards: Double,
)

@Serializable
data class SwitchMatchupObservation(
    val currentHp: Double,
    val currentMaximumHp: Double,
    val targetHp: Double,
    val targetMaximumHp: Double,
    val opponentHp: Double,
    val currentIncomingDamage: Double = 0.0,
    val targetIncomingDamage: Double = 0.0,
    val currentOutgoingDamage: Double = 0.0,
    val targetOutgoingDamage: Double = 0.0,
    val targetHazardDamage: Double = 0.0,
    val currentAttackPriority: Double = 0.0,
    val opponentAttackPriority: Double = 0.0,
    val currentSpeed: Double = 0.0,
    val opponentSpeed: Double = 0.0,
    val trickRoomActive: Boolean = false,
    val probabilisticActionRoute: Boolean = false,
    val guaranteedSurvival: Boolean = false,
)

@Serializable
data class SwitchMatchupEvaluation(
    val facts: SwitchMatchupFacts,
    val reachability: ActionReachabilityResult,
    val result: SwitchMatchupResult,
)

@Serializable
data class SwitchMatchupFacts(
    val currentHpRatio: Double = 1.0,
    val targetHpRatio: Double = 1.0,
    val currentIncomingDamage: Double = 0.0,
    val targetIncomingDamage: Double = 0.0,
    val currentIncomingDamageRatio: Double = 0.0,
    val targetIncomingDamageRatio: Double = 0.0,
    val currentOutgoingDamageRatio: Double = 0.0,
    val targetOutgoingDamageRatio: Double = 0.0,
    val hazardDamageRatio: Double = 0.0,
    val currentCanReachAction: Boolean = true,
)

@Serializable
data class SwitchMatchupResult(
    val matchupValue: Double,
    val emergencyEscape: Boolean,
    val noEffectiveMoveEscape: Boolean,
    val defensiveImprovement: Double,
    val offensiveImprovement: Double,
)

/** 플랫폼 전투 객체에서 읽은 피해 비율을 공통 교체 상성 사실로 파생한다. */
object SharedSwitchMatchupEvaluator {
    fun entryHazardDamage(input: EntryHazardObservation): EntryHazardResult {
        val hp = finiteMatchup(input.currentHp).coerceAtLeast(0.0)
        val maximumHp = finiteMatchup(input.maximumHp).coerceAtLeast(1.0)
        val ability = cleanMatchup(input.ability)
        val item = cleanMatchup(input.item)
        val types = input.types.map(::cleanMatchup)
        val ignoresHazards = input.ignoresHazards
            ?: (ability == "magicguard" || item == "heavydutyboots")
        if (ignoresHazards) return EntryHazardResult(0.0, 0.0, hp)
        val grounded = input.grounded
            ?: ("flying" !in types && ability != "levitate" && item != "airballoon")

        var damage = 0.0
        if (input.stealthRockLayers > 0) {
            val effectiveness = if (types.isEmpty()) finiteMatchup(input.rockEffectiveness).coerceAtLeast(0.0)
                else SharedDamageTypeEvaluator.effectiveness("rock", input.types)
            if (effectiveness > 0.0) damage += maxOf(1.0, floor(maximumHp / 8.0 * effectiveness))
        }
        if (input.spikesLayers > 0 && grounded) {
            val divisor = when (input.spikesLayers.coerceIn(1, 3)) { 1 -> 8.0; 2 -> 6.0; else -> 4.0 }
            damage += maxOf(1.0, floor(maximumHp / divisor))
        }
        damage = damage.coerceAtMost(hp)
        return EntryHazardResult(damage, damage / maximumHp, (hp - damage).coerceAtLeast(0.0))
    }

    fun derive(input: SwitchMatchupObservation): SwitchMatchupEvaluation {
        val currentMax = finiteMatchup(input.currentMaximumHp).coerceAtLeast(1.0)
        val targetMax = finiteMatchup(input.targetMaximumHp).coerceAtLeast(1.0)
        val opponentHp = finiteMatchup(input.opponentHp).coerceAtLeast(1.0)
        val currentHp = finiteMatchup(input.currentHp).coerceAtLeast(0.0)
        val targetHp = finiteMatchup(input.targetHp).coerceAtLeast(0.0)
        val hazardDamage = finiteMatchup(input.targetHazardDamage).coerceIn(0.0, targetHp)
        val speedAdvantage = if (input.trickRoomActive) {
            finiteMatchup(input.currentSpeed) < finiteMatchup(input.opponentSpeed)
        } else {
            finiteMatchup(input.currentSpeed) > finiteMatchup(input.opponentSpeed)
        }
        val reachability = SharedActionReachabilityEvaluator.evaluate(ActionReachabilityInput(
            ownPriority = finiteMatchup(input.currentAttackPriority),
            opponentPriority = finiteMatchup(input.opponentAttackPriority),
            speedAdvantage = speedAdvantage,
            currentHp = currentHp,
            incomingDamage = finiteMatchup(input.currentIncomingDamage).coerceAtLeast(0.0),
            guaranteedSurvival = input.guaranteedSurvival,
        ))
        val facts = SwitchMatchupFacts(
            currentHpRatio = currentHp / currentMax,
            targetHpRatio = (targetHp - hazardDamage).coerceAtLeast(0.0) / targetMax,
            currentIncomingDamage = finiteMatchup(input.currentIncomingDamage).coerceAtLeast(0.0),
            targetIncomingDamage = finiteMatchup(input.targetIncomingDamage).coerceAtLeast(0.0),
            currentIncomingDamageRatio = finiteMatchup(input.currentIncomingDamage).coerceAtLeast(0.0) / currentMax,
            targetIncomingDamageRatio = finiteMatchup(input.targetIncomingDamage).coerceAtLeast(0.0) / targetMax,
            currentOutgoingDamageRatio = finiteMatchup(input.currentOutgoingDamage).coerceAtLeast(0.0) / opponentHp,
            targetOutgoingDamageRatio = finiteMatchup(input.targetOutgoingDamage).coerceAtLeast(0.0) / opponentHp,
            hazardDamageRatio = hazardDamage / targetMax,
            currentCanReachAction = reachability.canReachNextAction == true || input.probabilisticActionRoute,
        )
        return SwitchMatchupEvaluation(facts, reachability, evaluate(facts))
    }

    fun evaluate(input: SwitchMatchupFacts): SwitchMatchupResult {
        val currentHp = finiteMatchup(input.currentHpRatio).coerceIn(0.0, 1.0)
        val targetHp = finiteMatchup(input.targetHpRatio).coerceIn(0.0, 1.0)
        val currentIncoming = finiteMatchup(input.currentIncomingDamageRatio).coerceAtLeast(0.0)
        val targetIncoming = finiteMatchup(input.targetIncomingDamageRatio).coerceAtLeast(0.0)
        val currentOutgoing = finiteMatchup(input.currentOutgoingDamageRatio).coerceAtLeast(0.0)
        val targetOutgoing = finiteMatchup(input.targetOutgoingDamageRatio).coerceAtLeast(0.0)
        val hazard = finiteMatchup(input.hazardDamageRatio).coerceAtLeast(0.0)
        val defensiveImprovement = currentIncoming - targetIncoming
        val offensiveImprovement = targetOutgoing - currentOutgoing
        val emergency = !input.currentCanReachAction && currentIncoming >= currentHp && targetIncoming < targetHp
        val noEffectiveMove = currentOutgoing <= 0.15 && targetOutgoing >= currentOutgoing + 0.1 && targetIncoming < 0.5

        var value = -18.0 + defensiveImprovement * 90.0 + offensiveImprovement * 45.0 - hazard * 100.0
        if (finiteMatchup(input.targetIncomingDamage) == 0.0 && finiteMatchup(input.currentIncomingDamage) > 0.0) {
            value += 24.0
        }
        if (emergency) value += 45.0
        if (noEffectiveMove) value += 32.0
        if (currentOutgoing <= 0.15 && targetOutgoing >= currentOutgoing + 0.1) {
            value += 4.0 + offensiveImprovement * 20.0
        }
        return SwitchMatchupResult(
            matchupValue = value,
            emergencyEscape = emergency,
            noEffectiveMoveEscape = noEffectiveMove,
            defensiveImprovement = defensiveImprovement,
            offensiveImprovement = offensiveImprovement,
        )
    }
}

@JsExport
fun evaluateSharedSwitchMatchupJson(inputJson: String): String = codec.encodeToString(
    SharedSwitchMatchupEvaluator.evaluate(codec.decodeFromString<SwitchMatchupFacts>(inputJson)),
)

@JsExport
fun deriveSharedSwitchMatchupObservationJson(inputJson: String): String = codec.encodeToString(
    SharedSwitchMatchupEvaluator.derive(codec.decodeFromString<SwitchMatchupObservation>(inputJson)),
)

@JsExport
fun deriveEntryHazardDamageJson(inputJson: String): String = codec.encodeToString(
    SharedSwitchMatchupEvaluator.entryHazardDamage(codec.decodeFromString<EntryHazardObservation>(inputJson)),
)

private fun finiteMatchup(value: Double): Double = if (value.isFinite()) value else 0.0
private fun cleanMatchup(value: String): String = value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }
