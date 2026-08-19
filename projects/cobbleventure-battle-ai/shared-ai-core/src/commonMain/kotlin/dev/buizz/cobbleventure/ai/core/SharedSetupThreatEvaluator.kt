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
data class SetupMoveFact(
    val id: String = "",
    val selfBoosts: Map<String, Double> = emptyMap(),
    val boosts: Map<String, Double> = emptyMap(),
)

@Serializable
data class SetupThreatInput(
    val setupMoves: List<SetupMoveFact> = emptyList(),
    val setupMoveIds: List<String> = emptyList(),
    val setupLikelihood: Double = 0.0,
    val opponentCurrentBoosts: Double = 0.0,
    val opponentRoleScore: Double = 0.0,
    val opponentAce: Boolean = false,
    val opponentHpPercent: Double = 1.0,
    val immediateDamageRatio: Double = 0.0,
    val counters: List<String> = emptyList(),
    val softChecks: List<String> = emptyList(),
    val revengeKillers: List<String> = emptyList(),
    val punishOptions: List<String> = emptyList(),
    val counterCount: Double? = null,
    val softCheckCount: Double? = null,
    val revengeKillerCount: Double? = null,
)

@Serializable
data class SetupLikelihoodInput(
    val turn: Int = 1,
    val immediateDamageRatio: Double = 0.0,
    val opponentHpPercent: Double = 1.0,
    val opponentRoleScore: Double = 0.0,
    val opponentAce: Boolean = false,
)

@Serializable
data class SetupBoostFact(
    val moveId: String = "",
    val attack: Double = 0.0,
    val speed: Double = 0.0,
    val pressure: Double = 0.0,
)

@Serializable
data class SetupAnswerFacts(
    val counters: Double = 0.0,
    val softChecks: Double = 0.0,
    val revengeKillers: Double = 0.0,
    val estimatedTotal: Double = 0.0,
)

@Serializable
data class SetupThreatResult(
    val opponentCanSetup: Boolean = false,
    val setupMoveCandidates: List<SetupMoveFact> = emptyList(),
    val setupLikelihood: Double = 0.0,
    val sweepRiskAfterSetup: Double = 0.0,
    val riskTier: Int = 0,
    val strongestBoost: SetupBoostFact? = null,
    val availableAnswersAfterSetup: SetupAnswerFacts = SetupAnswerFacts(),
    val punishOptions: List<String> = emptyList(),
    val oneMoreTurnUnmanageable: Boolean = false,
    val freeTurnPenalty: Double = 0.0,
    val reasons: List<String> = emptyList(),
)

/** 엔진별 원시 관측을 공통 랭크업 위협 사실로 변환한다. */
object SharedSetupThreatEvaluator {
    fun isKnownSetupMove(moveId: String): Boolean = inferredSetupBoosts(cleanFactId(moveId)).isNotEmpty()

    /** 엔진 어댑터가 가상 턴에 적용할 기술 자체 랭크 변화를 공통 정의에서 조회한다. */
    fun projectedSelfBoosts(moveId: String): Map<String, Double> =
        inferredSetupBoosts(cleanFactId(moveId))

    fun likelihood(
        turn: Int,
        immediateDamageRatio: Double,
        opponentHpPercent: Double,
        opponentRoleScore: Double,
        opponentAce: Boolean,
    ): Double = likelihood(SetupLikelihoodInput(
        turn, immediateDamageRatio, opponentHpPercent, opponentRoleScore, opponentAce,
    ))

    fun evaluateObserved(
        setupMoveIds: List<String>,
        setupLikelihood: Double,
        opponentCurrentBoosts: Double,
        opponentRoleScore: Double,
        opponentAce: Boolean,
        opponentHpPercent: Double,
        immediateDamageRatio: Double,
        counterCount: Double,
        softCheckCount: Double,
        revengeKillerCount: Double,
        punishOptions: List<String>,
    ): SetupThreatResult = evaluate(SetupThreatInput(
        setupMoveIds = setupMoveIds,
        setupLikelihood = setupLikelihood,
        opponentCurrentBoosts = opponentCurrentBoosts,
        opponentRoleScore = opponentRoleScore,
        opponentAce = opponentAce,
        opponentHpPercent = opponentHpPercent,
        immediateDamageRatio = immediateDamageRatio,
        counterCount = counterCount,
        softCheckCount = softCheckCount,
        revengeKillerCount = revengeKillerCount,
        punishOptions = punishOptions,
    ))

    fun likelihood(input: SetupLikelihoodInput): Double {
        var result = 0.25
        if (input.turn <= 2) result += 0.25
        when {
            input.immediateDamageRatio < 0.35 -> result += 0.25
            input.immediateDamageRatio < 0.55 -> result += 0.18
            input.immediateDamageRatio < 0.75 -> result += 0.08
            input.immediateDamageRatio >= 1.0 -> result -= 0.4
        }
        if (input.opponentHpPercent >= 0.75) result += 0.12
        if (input.opponentRoleScore >= 4.0) result += 0.16
        else if (input.opponentRoleScore > 0.0) result += 0.08
        if (input.opponentAce) result += 0.08
        return round2(result.coerceIn(0.0, 1.0))
    }

    fun evaluate(input: SetupThreatInput): SetupThreatResult {
        val moves = if (input.setupMoves.isNotEmpty()) {
            input.setupMoves.map { move ->
                val explicitBoosts = if (move.selfBoosts.isNotEmpty()) move.selfBoosts else move.boosts
                SetupMoveFact(
                    id = cleanFactId(move.id),
                    selfBoosts = explicitBoosts.ifEmpty { inferredSetupBoosts(cleanFactId(move.id)) },
                )
            }
        } else {
            input.setupMoveIds.map {
                val id = cleanFactId(it)
                SetupMoveFact(id = id, selfBoosts = inferredSetupBoosts(id))
            }
        }
        if (moves.isEmpty()) return SetupThreatResult()

        val strongest = moves.fold(SetupBoostFact(moveId = moves.first().id)) { best, move ->
            val boosts = move.selfBoosts
            val attack = max(0.0, max(boosts["attack"] ?: 0.0,
                boosts["specialAttack"] ?: boosts["specialattack"] ?: 0.0))
            val speed = max(0.0, boosts["speed"] ?: 0.0)
            val pressure = attack + speed * 0.8
            if (pressure > best.pressure) SetupBoostFact(move.id, attack, speed, pressure) else best
        }
        val counters = max(0.0, input.counterCount ?: input.counters.size.toDouble())
        val softChecks = max(0.0, input.softCheckCount ?: input.softChecks.size.toDouble())
        val revengeKillers = max(0.0, input.revengeKillerCount ?: input.revengeKillers.size.toDouble())
        val effectiveSoftChecks = if (strongest.attack >= 2.0) min(0.5, softChecks * 0.25) else softChecks * 0.65
        val effectiveRevengeKillers = if (strongest.speed > 0.0) revengeKillers * 0.25 else revengeKillers * 0.75
        val estimatedAnswers = counters + effectiveSoftChecks + effectiveRevengeKillers
        val answerScarcity = when {
            estimatedAnswers <= 0.0 -> 1.0
            estimatedAnswers < 1.0 -> 0.82
            estimatedAnswers < 2.0 -> 0.48
            else -> 0.12
        }
        val likelihood = input.setupLikelihood.coerceIn(0.0, 1.0)
        val currentBoostPressure = (max(0.0, input.opponentCurrentBoosts) / 4.0).coerceAtMost(1.0)
        val nextBoostPressure = (strongest.pressure / 3.0).coerceIn(0.0, 1.0)
        val rolePressure = (max(0.0, input.opponentRoleScore) / 10.0).coerceAtMost(1.0)
        val hpPressure = input.opponentHpPercent.coerceIn(0.0, 1.0)
        val immediatePunish = input.immediateDamageRatio.coerceIn(0.0, 1.0)
        val rawRisk = likelihood * (
            0.18 + nextBoostPressure * 0.3 + currentBoostPressure * 0.18 +
                answerScarcity * 0.24 + rolePressure * 0.08 +
                (if (input.opponentAce) 0.08 else 0.0) + hpPressure * 0.05
            ) * (1.0 - min(0.55, immediatePunish * 0.45))
        val risk = round2(rawRisk.coerceIn(0.0, 1.0))
        val tier = when {
            risk >= 0.65 -> 3
            risk >= 0.42 -> 2
            risk >= 0.22 -> 1
            else -> 0
        }
        val punishOptions = input.punishOptions.map(::cleanFactId).filter(String::isNotEmpty).distinct()
        val unmanageable = tier >= 3 && estimatedAnswers < 1.0 && immediatePunish < 1.0
        val penaltyScale = when (tier) { 3 -> 180.0; 2 -> 125.0; 1 -> 70.0; else -> 0.0 }
        val reasons = buildList {
            add("랭크업 가능성 ${jsRound(likelihood * 100.0).toInt()}%, 사용 후 스윕 위험 ${jsRound(risk * 100.0).toInt()}%")
            add("랭크업 후 유효 대응 자원 약 ${round1(estimatedAnswers)}마리")
            if (strongest.moveId.isNotEmpty()) add("${strongest.moveId}: 공격 ${compact(strongest.attack)}, 스피드 ${compact(strongest.speed)} 상승")
            if (punishOptions.isNotEmpty()) add("즉시 응징 수단: ${punishOptions.joinToString(", ")}")
        }
        return SetupThreatResult(
            opponentCanSetup = true,
            setupMoveCandidates = moves,
            setupLikelihood = likelihood,
            sweepRiskAfterSetup = risk,
            riskTier = tier,
            strongestBoost = strongest,
            availableAnswersAfterSetup = SetupAnswerFacts(counters, softChecks, revengeKillers, round2(estimatedAnswers)),
            punishOptions = punishOptions,
            oneMoreTurnUnmanageable = unmanageable,
            freeTurnPenalty = round2(risk * penaltyScale),
            reasons = reasons,
        )
    }
}

@JsExport
fun evaluateSetupThreatJson(inputJson: String): String =
    codec.encodeToString(SharedSetupThreatEvaluator.evaluate(codec.decodeFromString<SetupThreatInput>(inputJson)))

@JsExport
fun evaluateSetupLikelihoodJson(inputJson: String): String =
    codec.encodeToString(SharedSetupThreatEvaluator.likelihood(codec.decodeFromString<SetupLikelihoodInput>(inputJson)))

private fun cleanFactId(value: String): String = value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }
private fun round1(value: Double): Double = jsRound(value * 10.0) / 10.0
private fun round2(value: Double): Double = jsRound(value * 100.0) / 100.0
private fun jsRound(value: Double): Double = kotlin.math.floor(value + 0.5)
private fun compact(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun inferredSetupBoosts(moveId: String): Map<String, Double> = when (moveId) {
    "swordsdance" -> mapOf("attack" to 2.0)
    "nastyplot", "tailglow" -> mapOf("specialAttack" to if (moveId == "tailglow") 3.0 else 2.0)
    "dragondance", "tidyup" -> mapOf("attack" to 1.0, "speed" to 1.0)
    "victorydance" -> mapOf("attack" to 1.0, "defence" to 1.0, "speed" to 1.0)
    "quiverdance" -> mapOf("specialAttack" to 1.0, "specialDefence" to 1.0, "speed" to 1.0)
    "shellsmash" -> mapOf(
        "attack" to 2.0, "specialAttack" to 2.0, "speed" to 2.0,
        "defence" to -1.0, "specialDefence" to -1.0,
    )
    "shiftgear" -> mapOf("attack" to 1.0, "speed" to 2.0)
    "geomancy" -> mapOf("specialAttack" to 2.0, "specialDefence" to 2.0, "speed" to 2.0)
    "agility", "rockpolish", "autotomize" -> mapOf("speed" to 2.0)
    "bellydrum" -> mapOf("attack" to 6.0)
    "calmmind" -> mapOf("specialAttack" to 1.0, "specialDefence" to 1.0)
    "bulkup" -> mapOf("attack" to 1.0, "defence" to 1.0)
    "coil" -> mapOf("attack" to 1.0, "defence" to 1.0)
    "curse" -> mapOf("attack" to 1.0, "defence" to 1.0, "speed" to -1.0)
    "honeclaws", "poweruppunch" -> mapOf("attack" to 1.0)
    "growth", "workup" -> mapOf("attack" to 1.0, "specialAttack" to 1.0)
    "noretreat", "clangoroussoul" -> mapOf(
        "attack" to 1.0, "specialAttack" to 1.0, "defence" to 1.0,
        "specialDefence" to 1.0, "speed" to 1.0,
    )
    "acidarmor", "irondefense", "barrier" -> mapOf("defence" to 2.0)
    "cottonguard" -> mapOf("defence" to 3.0)
    "amnesia" -> mapOf("specialDefence" to 2.0)
    "cosmicpower", "defendorder" -> mapOf("defence" to 1.0, "specialDefence" to 1.0)
    "trailblaze", "flamecharge", "rapidspin", "scaleshot" -> mapOf("speed" to 1.0)
    "torchsong" -> mapOf("specialAttack" to 1.0)
    else -> emptyMap()
}
