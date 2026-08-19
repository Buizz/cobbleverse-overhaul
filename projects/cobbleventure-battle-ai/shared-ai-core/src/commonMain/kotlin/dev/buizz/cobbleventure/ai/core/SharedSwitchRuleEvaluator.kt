@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SwitchRuleInput(
    val hpRatio: Double = 1.0,
    val targetIncomingDamageRatio: Double? = null,
    val targetOutgoingDamageRatio: Double? = null,
    val forceSwitch: Boolean = false,
    val speedAdvantage: Boolean = false,
    val priorityKo: Boolean = false,
    val immediateKoBeforeOpponent: Boolean = false,
    val safeTwoHitHold: Boolean = false,
    val safeImmediateKoAvailable: Boolean = false,
    val safePivotAvailable: Boolean = false,
    val switchInDamageRatio: Double = 0.0,
    val currentCanReachAction: Boolean = false,
    val emergencyEscape: Boolean = false,
    val currentBestMoveScore: Double = 0.0,
    val safeActionDenialAvailable: Boolean = false,
    val switchedLastTurn: Boolean = false,
    val immediateReturn: Boolean = false,
    val forcedReplacement: Boolean = false,
    val setupThreatTier: Int = 0,
    val oneMoreTurnUnmanageable: Boolean = false,
    val dynamaxActive: Boolean = false,
    val dynamaxRemainingTurns: Int = 0,
    val dynamaxEscapeJustified: Boolean = false,
)

object SharedSwitchRuleEvaluator {
    fun adjustments(input: SwitchRuleInput): List<CandidateAdjustment> = buildList {
        val hp = finiteSwitch(input.hpRatio).coerceIn(0.0, 1.0)
        val incoming = input.targetIncomingDamageRatio?.let(::finiteSwitch)?.coerceAtLeast(0.0)
        val outgoing = input.targetOutgoingDamageRatio?.let(::finiteSwitch)?.coerceAtLeast(0.0)
        if (incoming != null && outgoing != null && outgoing >= 0.9 && incoming < 1.0 && !input.safeTwoHitHold) {
            add(CandidateAdjustment("rule.switch.safe_counter_ko", 10.0))
        }
        if (incoming != null && incoming >= hp) {
            val actsBefore = input.speedAdvantage || input.priorityKo
            val canKoBeforeFaint = input.forceSwitch && (
                input.immediateKoBeforeOpponent || input.priorityKo || (actsBefore && outgoing != null && outgoing >= 1.0)
            )
            if (!canKoBeforeFaint) {
                add(CandidateAdjustment("rule.switch.lethal_switch_in", if (input.forceSwitch && actsBefore) -40.0 else -80.0))
            }
        }
        if (!input.forceSwitch && input.safeImmediateKoAvailable) {
            add(CandidateAdjustment("rule.switch.guaranteed_ko_penalty", -30.0))
        }
        if (!input.forceSwitch && input.safePivotAvailable) {
            add(CandidateAdjustment("rule.switch.pivot_available", -12.0))
        }
        val switchInDamage = finiteSwitch(input.switchInDamageRatio).coerceAtLeast(0.0)
        if (!input.forceSwitch && switchInDamage > 0.0) {
            add(CandidateAdjustment("rule.switch.incoming_hit_cost", -roundSwitch(minOf(70.0, switchInDamage * 55.0))))
        }
        if (!input.forceSwitch && input.currentCanReachAction && !input.emergencyEscape) {
            val weight = -roundSwitch(minOf(24.0, finiteSwitch(input.currentBestMoveScore).coerceAtLeast(0.0) * 0.06))
            if (weight < 0.0) add(CandidateAdjustment("rule.switch.action_opportunity_cost", weight))
        }
        if (!input.forceSwitch && input.safeActionDenialAvailable) {
            add(CandidateAdjustment("rule.switch.safe_disruption_available", -80.0))
        }
        if (!input.forceSwitch && input.switchedLastTurn && input.setupThreatTier < 3 && !input.oneMoreTurnUnmanageable) {
            val penalty = 2.0 + (if (input.immediateReturn) 4.0 else 0.0) + (if (input.forcedReplacement) 36.0 else 0.0)
            add(CandidateAdjustment("rule.switch.repeated_switch", -penalty))
        }
        val dynamaxTurns = input.dynamaxRemainingTurns.coerceAtLeast(0)
        if (!input.forceSwitch && input.dynamaxActive && dynamaxTurns > 0) {
            val multiplier = if (input.dynamaxEscapeJustified) 0.5 else 1.0
            add(CandidateAdjustment("rule.switch.dynamax_turn_cost", -roundSwitch(dynamaxTurns * 9.0 * multiplier)))
        }
    }

    fun adjustmentsJson(inputJson: String): String = codec.encodeToString(
        adjustments(codec.decodeFromString<SwitchRuleInput>(inputJson)),
    )
}

@JsExport
fun evaluateSwitchRulesJson(inputJson: String): String =
    SharedSwitchRuleEvaluator.adjustmentsJson(inputJson)

private fun finiteSwitch(value: Double): Double = if (value.isFinite()) value else 0.0
private fun roundSwitch(value: Double): Double = floor(value * 100.0 + 0.5) / 100.0
