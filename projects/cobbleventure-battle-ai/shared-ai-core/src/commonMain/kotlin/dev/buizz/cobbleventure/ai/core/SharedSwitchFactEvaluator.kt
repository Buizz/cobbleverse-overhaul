@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 기존 웹 switchRuleAdjustments의 점수 수식을 그대로 소유한다. */
object SharedSwitchFactEvaluator {
    fun adjustments(facts: RuleFactBag): List<CandidateAdjustment> = buildList {
        val strategy = facts.string("strategy")
        val force = facts.flag("forceSwitch")
        val hp = facts.firstNumber("hpPercent", default = 0.0)
        val currentIncoming = facts.firstOptionalNumber("currentIncomingDamageRatio", "currentIncomingRatio")
        val targetIncoming = facts.firstOptionalNumber("targetIncomingDamageRatio", "incomingDamageRatio")
        val currentOutgoing = facts.firstOptionalNumber("currentOutgoingDamageRatio", "currentDamageRatio")
        val targetOutgoing = facts.firstOptionalNumber("targetOutgoingDamageRatio", "outgoingDamageRatio")
        val switchDamage = facts.number("switchInDamageRatio").coerceAtLeast(0.0)
        val currentHp = facts.firstNumber("currentHpPercent", default = 1.0).coerceIn(0.0, 1.0)
        val regen = facts.number("regeneratorRecoveryRatio").coerceAtLeast(0.0)
        if (cleanFactId(facts.string("currentAbility")) == "regenerator" && !force && currentHp < 0.6 && regen > 0.0) {
            val urgency = ((0.6 - currentHp) / 0.6).coerceIn(0.0, 1.0)
            val multiplier = when (strategy) {
                "tempo" -> 1.25; "defensive" -> 1.2; "aggressive" -> 0.8; "reckless_ace" -> 0.75; else -> 1.0
            }
            add(rule("rule.switch.regenerator_recovery", roundFact((12.0 + regen * 70.0 + urgency * 28.0) * multiplier)))
        }
        if (facts.number("stayPressurePenalty").coerceAtLeast(0.0) > 0.0) add(rule("rule.switch.clears_residual_pressure", 0.0))
        val oneTurnDelta = facts.firstOptionalNumber("oneTurnEvaluation.delta", "battleStateEvaluation.delta", "battleStateValueDelta")
        val oneTurnWeight = facts.firstNumber("oneTurnSearchWeight", default = 0.35).coerceAtLeast(0.0)
        if (oneTurnDelta != null && oneTurnWeight > 0.0) add(rule("simulation.one_turn_state_value", roundFact(oneTurnDelta * oneTurnWeight)))
        if (facts.flag("aceRecoveryPlanEligible")) add(rule("rule.switch.ace_recovery_sacrifice_plan", 0.0))
        if (facts.flag("batonPassSetupOpportunity")) {
            val multiplier = when (strategy) {
                "reckless_ace" -> 3.1; "setup" -> 1.6; "aggressive" -> 1.15
                "defensive", "hazard" -> 0.75; else -> 1.0
            }
            val transfer = facts.number("batonPassTransferValue").coerceAtLeast(0.0)
            val kos = facts.number("batonPassNewKoTargets").coerceAtLeast(0.0)
            val turns = facts.firstNumber("batonPassSafeSetupTurns", default = 1.0).coerceAtLeast(1.0)
            add(rule("rule.switch.baton_pass_setup_opportunity", roundFact(minOf(125.0, 38.0 + turns * 10.0 + transfer * 0.16 + kos * 24.0) * multiplier)))
        }
        if (facts.flag("mustPreserveResource")) {
            val currentThreat = facts.flag("preservationTargetIsCurrent")
            if (currentThreat && cleanFactId(facts.string("currentThreatClassification")) == "counter") {
                add(rule("rule.switch.unique_counter_deployment", 18.0))
            } else if (!currentThreat) {
                val exposure = maxOf(switchDamage, if (facts.optionalFlag("canReachNextAction") == false) 1.0 else 0.0)
                if (exposure >= 0.2) {
                    val multiplier = when (strategy) { "ace_check" -> 1.3; "defensive" -> 1.15; "reckless_ace" -> 0.75; else -> 1.0 }
                    add(rule("rule.switch.unique_counter_preservation", -roundFact(minOf(180.0, 28.0 + exposure * 95.0) * multiplier)))
                }
            }
        }
        if (facts.flag("targetRoleComplete")) add(rule("rule.switch.role_complete", 0.0))
        val setupCan = facts.firstFlag("setupThreatEvaluation.opponentCanSetup", "opponentSetupThreatEvaluation.opponentCanSetup")
        val setupRisk = facts.firstNumber(
            "setupThreatEvaluation.sweepRiskAfterSetup", "opponentSetupThreatEvaluation.sweepRiskAfterSetup", "opponentSetupSweepRisk", default = 0.0,
        ).coerceIn(0.0, 1.0)
        if (!force && setupCan && setupRisk >= 0.22) {
            val classification = cleanFactId(facts.string("currentThreatClassification"))
            val answer = classification in setOf("counter", "softcheck", "revengekiller") || facts.flag("canKoOnNextAction") || facts.flag("priorityKo") || facts.flag("setupPunishAfterSwitch")
            if (answer) add(rule("rule.switch.setup_answer", roundFact(10.0 + setupRisk * 20.0))) else {
                val pressure = facts.number("targetOutgoingDamageRatio").coerceAtLeast(0.0)
                val multiplier = if (pressure < 0.35) 0.8 else if (pressure < 0.6) 0.55 else 0.3
                val freePenalty = facts.firstNumber("setupThreatEvaluation.freeTurnPenalty", "opponentSetupThreatEvaluation.freeTurnPenalty", default = 0.0)
                val weight = -roundFact(freePenalty * multiplier)
                if (weight < 0.0) add(rule("rule.switch.free_setup_turn", weight))
            }
        }
        if (currentIncoming != null && targetIncoming != null) {
            val weight = roundFact((currentIncoming - targetIncoming) * 12.0)
            if (weight != 0.0) add(rule("rule.switch.defensive_improvement", weight))
        }
        val safeTwoHit = currentOutgoing != null && currentIncoming != null && currentOutgoing >= 0.5 && currentIncoming < hp && !force
        if (currentOutgoing != null && targetOutgoing != null && !safeTwoHit) {
            val weight = roundFact((targetOutgoing - currentOutgoing) * 6.0)
            if (weight != 0.0) add(rule("rule.switch.offensive_improvement", weight))
        }
        if (safeTwoHit) add(rule("rule.switch.hold_safe_two_hit", -4.0))
        if (facts.flag("speedAdvantage")) add(rule("rule.switch.speed_advantage", 2.0))
        if (!force && facts.optionalFlag("survivesSwitchIn") == false) {
            add(rule("rule.switch.faints_on_entry_turn", -240.0))
        } else if (!force && facts.optionalFlag("canReachNextAction") == false) {
            val ace = facts.number("targetAceScore").coerceAtLeast(0.0)
            val role = facts.number("targetRoleScore").coerceAtLeast(0.0)
            val multiplier = when (strategy) { "ace_check" -> 1.3; "defensive" -> 1.15; "reckless_ace" -> 0.8; else -> 1.0 }
            val cost = (if (facts.flag("targetAceQualified")) 650.0 + minOf(180.0, ace * 10.0) else if (facts.flag("targetRoleComplete")) 10.0 else 35.0 + minOf(60.0, role * 5.0)) * multiplier
            add(rule("rule.switch.no_action_opportunity", -roundFact(150.0 + cost)))
        } else if (!force && facts.flag("canKoOnNextAction")) add(rule("rule.switch.next_action_counter_ko", 24.0))
        if (force && facts.optionalFlag("canReachNextAction") == false && !facts.flag("immediateKoBeforeOpponent") && !facts.flag("priorityKo")) {
            val ace = facts.number("targetAceScore").coerceAtLeast(0.0)
            add(rule("rule.switch.forced_no_action", -roundFact(if (facts.flag("targetAceQualified")) 220.0 + minOf(120.0, ace * 8.0) else 140.0)))
        }
        val field = facts.firstOptionalNumber("fieldSynergyValue", "fieldValue")
        if (field != null && field != 0.0) add(rule(if (field > 0.0) "rule.switch.field_synergy" else "rule.switch.field_mismatch", field))
        val currentStatus = cleanFactId(facts.string("currentStatus"))
        if (currentStatus.isNotEmpty() && currentStatus !in setOf("tox", "toxic", "badlypoisoned")) add(rule("rule.switch.status_relief", 4.0))
        if (facts.string("targetStatus").isNotEmpty()) add(rule("rule.switch.target_status", -4.0))
        val boosts = facts.number("currentPositiveBoosts").coerceAtLeast(0.0)
        if (boosts > 0.0 && !force) add(rule("rule.switch.boost_loss", -boosts * 2.0))
        val opponentBoosts = facts.number("opponentOffensiveBoosts").coerceAtLeast(0.0)
        if (!force && facts.flag("targetAceQualified") && opponentBoosts > 0.0 && !facts.flag("canKoOnNextAction") && switchDamage >= 0.2) {
            val multiplier = when (strategy) { "ace_check" -> 1.25; "defensive" -> 1.15; "reckless_ace" -> 0.75; else -> 1.0 }
            add(rule("rule.switch.boosted_attacker_ace_exposure", -roundFact(minOf(240.0, (50.0 + opponentBoosts * 35.0 + switchDamage * 100.0) * multiplier))))
        }
        addAll(SharedSwitchRuleEvaluator.adjustments(SwitchRuleInput(
            hpRatio = hp,
            targetIncomingDamageRatio = targetIncoming,
            targetOutgoingDamageRatio = targetOutgoing,
            forceSwitch = force,
            speedAdvantage = facts.flag("speedAdvantage"),
            priorityKo = facts.flag("priorityKo"),
            immediateKoBeforeOpponent = facts.flag("immediateKoBeforeOpponent"),
            safeTwoHitHold = safeTwoHit,
            safeImmediateKoAvailable = facts.flag("safeImmediateKoAvailable"),
            safePivotAvailable = facts.flag("safePivotAvailable"),
            switchInDamageRatio = switchDamage,
            currentCanReachAction = facts.flag("currentCanReachAction"),
            emergencyEscape = facts.flag("emergencyEscape"),
            currentBestMoveScore = facts.number("currentBestMoveScore"),
            safeActionDenialAvailable = facts.flag("safeActionDenialAvailable"),
            switchedLastTurn = facts.flag("switchedLastTurn"),
            immediateReturn = facts.flag("immediateReturn"),
            forcedReplacement = facts.flag("forcedReplacement"),
            setupThreatTier = facts.number("setupThreatTier").toInt(),
            oneMoreTurnUnmanageable = facts.flag("oneMoreTurnUnmanageable"),
            dynamaxActive = facts.flag("dynamaxActive"),
            dynamaxRemainingTurns = facts.firstNumber("dynamaxRemainingTurns", "remainingDynamaxTurns", default = 0.0).toInt(),
            dynamaxEscapeJustified = facts.flag("dynamaxEscapeJustified"),
        )))
    }

    fun adjustmentsJson(inputJson: String): String = codec.encodeToString(
        adjustments(codec.decodeFromString<RuleFactBag>(inputJson)),
    )
}

@JsExport
fun evaluateSwitchRuleFactsJson(inputJson: String): String = SharedSwitchFactEvaluator.adjustmentsJson(inputJson)

internal fun RuleFactBag.firstOptionalNumber(vararg keys: String): Double? = keys.firstNotNullOfOrNull(::optionalNumber)
internal fun RuleFactBag.firstNumber(vararg keys: String, default: Double): Double = firstOptionalNumber(*keys) ?: default
internal fun RuleFactBag.firstFlag(vararg keys: String): Boolean = keys.firstNotNullOfOrNull(::optionalFlag) == true
private fun rule(code: String, weight: Double) = CandidateAdjustment(code, weight)
private fun roundFact(value: Double): Double = floor(value * 100.0 + 0.5) / 100.0
private fun cleanFactId(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
