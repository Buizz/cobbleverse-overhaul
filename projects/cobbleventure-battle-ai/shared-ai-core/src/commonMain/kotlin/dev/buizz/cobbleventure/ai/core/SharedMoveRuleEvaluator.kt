@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class MoveRuleInput(
    val hasSafeImmediateKo: Boolean = false,
    val safeFinisher: Boolean = false,
    val damageMove: Boolean = false,
    val highValueHazard: Boolean = false,
    val guaranteedKo: Boolean = false,
    val actsBefore: Boolean = false,
    val setupThreatTier: Int = 0,
    val recoveryMove: Boolean = false,
    val hpRatio: Double = 1.0,
    val incomingDamageRatio: Double? = null,
    val hasCurrentStatus: Boolean = false,
    val recoveryAmount: Double? = null,
    val recoveryExpectedIncomingDamage: Double? = null,
    val recoveryNetHpChange: Double? = null,
    val recoveryExposureTurns: Int = 1,
    val recoveryBeforeActionKoRisk: Double = 0.0,
    val opponentLikelyToSetup: Boolean = false,
    val opponentSetupLikelihood: Double = 0.0,
    val setupFreeTurnPenalty: Double = 0.0,
    val pivotMove: Boolean = false,
    val partingShot: Boolean = false,
    val hasLivingBench: Boolean? = null,
    val forceSwitch: Boolean = false,
    val survivalProbability: Double? = null,
    val safePivot: Boolean = false,
    val selfSacrifice: Boolean = false,
    val opponentHp: Double? = null,
    val expectedDamage: Double = 0.0,
    val meaningfulSacrificeDamage: Boolean = false,
    val activeRoleScore: Double? = null,
    val expendableResource: Boolean = false,
    val roleComplete: Boolean = false,
    val mustPreserveResource: Boolean = false,
)

object SharedMoveRuleEvaluator {
    fun adjustments(input: MoveRuleInput): List<CandidateAdjustment> = buildList {
        if (input.hasSafeImmediateKo && !input.safeFinisher) {
            add(CandidateAdjustment(
                if (input.damageMove) "rule.immediate_ko_attack_preference" else "rule.immediate_ko_dominance",
                if (input.highValueHazard) -12.0 else if (input.damageMove) -10.0 else -80.0,
            ))
        }
        if (input.guaranteedKo && input.actsBefore && input.setupThreatTier >= 2) {
            add(CandidateAdjustment("rule.immediate_ko_response", 4.0))
        }
        if (input.recoveryMove) recovery(input, this)
        if (input.pivotMove) {
            if (input.hasLivingBench == false) {
                add(CandidateAdjustment("rule.pivot.no_bench", -60.0))
            } else if (input.hasLivingBench != false && !input.forceSwitch && (
                input.actsBefore || (input.survivalProbability ?: 0.0) >= 1.0 || input.safePivot
            )) {
                add(CandidateAdjustment("rule.pivot.safe_pivot", if (input.partingShot) 12.0 else 8.0))
            }
        }
        if (input.selfSacrifice) sacrifice(input, this)
    }

    fun adjustmentsJson(inputJson: String): String = codec.encodeToString(
        adjustments(codec.decodeFromString<MoveRuleInput>(inputJson)),
    )

    private fun recovery(input: MoveRuleInput, target: MutableList<CandidateAdjustment>) = with(target) {
        val hp = finiteMove(input.hpRatio).coerceIn(0.0, 1.0)
        val incoming = input.incomingDamageRatio?.let(::finiteMove)?.coerceAtLeast(0.0)
        val projectedHp = if (incoming == null || input.actsBefore) hp else hp - incoming
        val emergency = hp <= 0.45 || (projectedHp > 0.0 && projectedHp <= 0.25) || (incoming != null && incoming >= hp)
        val survivalWeight = when {
            hp <= 0.35 -> 24.0
            hp <= 0.5 -> 12.0
            projectedHp > 0.0 && projectedHp <= 0.6 -> 30.0
            !input.hasCurrentStatus -> -10.0
            else -> 0.0
        }
        if (survivalWeight != 0.0) add(CandidateAdjustment(
            if (survivalWeight > 0.0) "rule.recovery.survival_value" else "rule.recovery.healthy_penalty",
            survivalWeight,
        ))
        val beforeRisk = finiteMove(input.recoveryBeforeActionKoRisk).coerceIn(0.0, 1.0)
        if (beforeRisk >= 0.75) add(CandidateAdjustment(
            "rule.recovery.ko_before_heal",
            if (beforeRisk >= 0.85) -520.0 else -260.0,
        ))
        val amount = input.recoveryAmount?.let(::finiteMove)
        val expectedIncoming = input.recoveryExpectedIncomingDamage?.let(::finiteMove)
        val net = input.recoveryNetHpChange?.let(::finiteMove)
        val exposure = input.recoveryExposureTurns.coerceAtLeast(1)
        if (amount != null && expectedIncoming != null && net != null && net < 0.0) {
            val penalty = -minOf(520.0, (if (exposure >= 3) 120.0 else 80.0) + abs(net) * (if (exposure >= 3) 0.35 else 0.5))
            add(CandidateAdjustment(
                if (exposure >= 3) "rule.recovery.sleep_turn_damage" else "rule.recovery.negative_exchange",
                roundMove(penalty),
            ))
        }
        if (!emergency && input.opponentLikelyToSetup) {
            val legacy = if (hp >= 0.8) 95.0 else if (hp >= 0.65) 75.0 else 45.0
            add(CandidateAdjustment("rule.recovery.free_setup_risk", -maxOf(legacy, finiteMove(input.setupFreeTurnPenalty))))
        }
    }

    private fun sacrifice(input: MoveRuleInput, target: MutableList<CandidateAdjustment>) = with(target) {
        val opponentHp = input.opponentHp?.let(::finiteMove)
        val damageRatio = if (opponentHp != null && opponentHp > 0.0) finiteMove(input.expectedDamage) / opponentHp else null
        val meaningful = input.guaranteedKo || (damageRatio != null && damageRatio >= 0.6) || input.meaningfulSacrificeDamage
        val hp = finiteMove(input.hpRatio).coerceIn(0.0, 1.0)
        val incoming = input.incomingDamageRatio?.let(::finiteMove)
        val roleScore = input.activeRoleScore?.let(::finiteMove)
        val expendable = input.expendableResource || input.roleComplete || (roleScore != null && roleScore <= 4.0) ||
            (hp <= 0.25 && incoming != null && incoming >= 0.6)
        var weight = -220.0
        if (meaningful) weight += 35.0
        if (input.guaranteedKo) weight += 45.0
        if (expendable) weight += 70.0
        if (roleScore != null && roleScore >= 10.0) weight -= 70.0 else if (roleScore != null && roleScore >= 6.0) weight -= 35.0
        if (input.mustPreserveResource) weight -= 180.0
        if (!meaningful) weight -= 60.0
        add(CandidateAdjustment("rule.self_sacrifice.resource_cost", weight))
    }
}

@JsExport
fun evaluateMoveRulesJson(inputJson: String): String =
    SharedMoveRuleEvaluator.adjustmentsJson(inputJson)

private fun finiteMove(value: Double): Double = if (value.isFinite()) value else 0.0
private fun roundMove(value: Double): Double = round(value * 100.0) / 100.0
