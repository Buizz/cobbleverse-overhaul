@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ActionReachabilityInput(
    val ownPriority: Double = 0.0,
    val opponentPriority: Double = 0.0,
    val speedAdvantage: Boolean = false,
    val actsBeforeOpponent: Boolean? = null,
    val currentHp: Double? = null,
    val incomingDamage: Double? = null,
    val survivalProbability: Double? = null,
    val knockoutBeforeActionProbability: Double? = null,
    val canReachNextAction: Boolean? = null,
    val guaranteedSurvival: Boolean = false,
)

@Serializable
data class ActionReachabilityResult(
    val actsBefore: Boolean,
    val survivalProbability: Double? = null,
    val knockoutBeforeActionProbability: Double? = null,
    val canReachNextAction: Boolean? = null,
    val safePivot: Boolean? = null,
)

/** 우선도·속도·피해 관측에서 플랫폼 공통 행동 가능성 사실을 만든다. */
object SharedActionReachabilityEvaluator {
    fun evaluate(input: ActionReachabilityInput): ActionReachabilityResult {
        val actsBefore = input.actsBeforeOpponent ?: (
            input.ownPriority > input.opponentPriority ||
                (input.ownPriority == input.opponentPriority && input.speedAdvantage)
            )
        val survival = input.survivalProbability?.coerceIn(0.0, 1.0) ?: when {
            actsBefore || input.guaranteedSurvival -> 1.0
            input.currentHp != null && input.incomingDamage != null && input.incomingDamage < input.currentHp -> 1.0
            input.currentHp != null && input.incomingDamage != null -> 0.0
            else -> null
        }
        val koBeforeAction = input.knockoutBeforeActionProbability?.coerceIn(0.0, 1.0)
            ?: if (actsBefore) 0.0 else survival?.let { 1.0 - it }
        val canReach = input.canReachNextAction ?: if (actsBefore) true else survival?.let { it > 0.0 }
        return ActionReachabilityResult(actsBefore, survival, koBeforeAction, canReach, canReach)
    }
}

@JsExport
fun evaluateActionReachabilityJson(inputJson: String): String =
    codec.encodeToString(SharedActionReachabilityEvaluator.evaluate(
        codec.decodeFromString<ActionReachabilityInput>(inputJson),
    ))
