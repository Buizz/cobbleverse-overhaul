@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val OPPONENT_TEMPERATURE = 70.0
private const val MINIMUM_GAIN = 0.02
private const val SECOND_TURN_DISCOUNT = 0.72
private const val CONTINUATION_BEAM_GAP = 0.04
private const val TRANSITION_CACHE_LIMIT = 512
private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
private val transitionCache = linkedMapOf<String, String>()

@Serializable
data class BattleValueSide(
    val teamSize: Double = 1.0,
    val livingCount: Double = 0.0,
    val totalHpRatio: Double = 0.0,
    val aceCandidateCount: Double = 1.0,
    val aceAliveCount: Double = 0.0,
    val aceHpRatio: Double = 0.0,
    val positiveBoosts: Double = 0.0,
    val statusBurden: Double = 0.0,
    val hazardLayers: Double = 0.0,
    val uniqueCountersAlive: Double = 0.0,
    val gimmicksRemaining: Double = 0.0,
    val matchupCoverage: Double = 0.0,
    val safeKoCoverage: Double = 0.0,
    val benchReadiness: Double = 0.0,
    val sweepPotential: Double = 0.0,
)

@Serializable
data class BattleValueState(
    val own: BattleValueSide = BattleValueSide(),
    val opponent: BattleValueSide = BattleValueSide(),
    val fieldAdvantage: Double = 0.0,
    val informationConfidence: Double? = null,
    val terminalOutcome: String? = null,
)

@Serializable
data class WinFactor(
    val component: String,
    val contribution: Double,
)

@Serializable
data class WinEstimate(
    val probability: Double,
    val probabilityPercent: Double,
    val confidence: Double,
    val modelVersion: String = "heuristic-logistic-v3",
    val featureSchemaVersion: Int = 3,
    val terminal: Boolean,
    val terminalOutcome: String? = null,
    val rawValue: Double,
    val rawProbability: Double,
    val topFactors: List<WinFactor>,
    val components: Map<String, Double>,
    val state: BattleValueState,
)

@Serializable
data class SearchAction(
    val id: String,
    val kind: String = "move",
    val score: Double = 0.0,
    val successProbability: Double = 1.0,
    val expectedDamage: Double = 0.0,
    val nonConsecutive: Boolean = false,
    val statusMove: Boolean = false,
    val guaranteedKnockout: Boolean = false,
    val opponentKnockoutBeforeActionProbability: Double = 0.0,
    val heuristicSelected: Boolean = false,
) {
    val reliabilityPenalty: Double get() = (1.0 - successProbability.coerceIn(0.0, 1.0)) * 0.18
}

@Serializable
data class SearchOutcome(
    val opponentAction: SearchAction,
    val opponentProbability: Double,
    val winProbability: Double,
    val evaluatedWinProbability: Double,
    val riskWinProbability: Double,
    val continuation: SearchEvaluation? = null,
)

@Serializable
data class SearchEvaluation(
    val action: SearchAction? = null,
    val expectedWinProbability: Double,
    val worstWinProbability: Double,
    val searchValue: Double,
    val outcomes: List<SearchOutcome> = emptyList(),
)

@Serializable
data class SearchDecision(
    val selected: SearchAction? = null,
    val policyOverride: Boolean = false,
    val evaluations: List<SearchEvaluation> = emptyList(),
    val visitedNodes: Int = 0,
    val cacheHits: Int = 0,
    val budgetExhausted: Boolean = false,
    val depthTurns: Int = 1,
)

interface SearchRuntime {
    fun candidates(state: String, sideIndex: Int): List<SearchAction>
    fun transition(state: String, sideZeroActionId: String, sideOneActionId: String): String?
    fun winProbability(state: String, sideIndex: Int): Double
    fun terminal(state: String): Boolean
}

object SharedAiCore {
    fun estimateWinProbability(
        state: BattleValueState,
        calibrationIntercept: Double = 0.0,
        calibrationSlope: Double = 1.0,
    ): WinEstimate {
        val own = normalize(state.own)
        val opponent = normalize(state.opponent)
        val components = linkedMapOf(
            "pokemonCount" to (own.livingCount - opponent.livingCount) * 70.0,
            "totalHp" to (own.totalHpRatio - opponent.totalHpRatio) * 24.0,
            "aceSurvival" to (
                (own.aceAliveCount / own.aceCandidateCount - opponent.aceAliveCount / opponent.aceCandidateCount) * 54.0 +
                    (own.aceHpRatio / own.aceCandidateCount - opponent.aceHpRatio / opponent.aceCandidateCount) * 84.0
                ),
            "status" to (opponent.statusBurden - own.statusBurden) * 9.0,
            "boosts" to (own.positiveBoosts - opponent.positiveBoosts) * 7.0,
            "hazards" to (opponent.hazardLayers - own.hazardLayers) * 5.0,
            "gimmicks" to (own.gimmicksRemaining - opponent.gimmicksRemaining) * 4.0,
            "uniqueCounters" to (own.uniqueCountersAlive - opponent.uniqueCountersAlive) * 16.0,
            "matchupCoverage" to (own.matchupCoverage - opponent.matchupCoverage) * 36.0,
            "safeKoCoverage" to (own.safeKoCoverage - opponent.safeKoCoverage) * 22.0,
            "benchReadiness" to (own.benchReadiness - opponent.benchReadiness) * 16.0,
            "sweepPotential" to (own.sweepPotential - opponent.sweepPotential) * 22.0,
            "field" to finite(state.fieldAdvantage),
        )
        val value = round(components.values.sum(), 2)
        val unroundedRawProbability = 1.0 / (1.0 + kotlin.math.exp(-value / 90.0))
        val rawProbability = round(unroundedRawProbability, 4)
        val terminalOutcome = state.terminalOutcome?.lowercase() ?: when {
            own.livingCount <= 0 && opponent.livingCount <= 0 -> "draw"
            opponent.livingCount <= 0 -> "win"
            own.livingCount <= 0 -> "loss"
            else -> null
        }
        val calibratedProbability = calibrate(unroundedRawProbability, calibrationIntercept, calibrationSlope)
        val probability = when (terminalOutcome) {
            "win" -> 1.0
            "loss" -> 0.0
            "draw" -> 0.5
            else -> calibratedProbability
        }
        val terminal = terminalOutcome == "win" || terminalOutcome == "loss" || terminalOutcome == "draw"
        val confidence = state.informationConfidence?.coerceIn(0.0, 1.0)
            ?: if (terminal) 1.0 else 0.9
        val topFactors = components.entries
            .filter { kotlin.math.abs(it.value) >= 0.5 }
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(5)
            .map { WinFactor(it.key, it.value) }
        return WinEstimate(
            probability = probability,
            probabilityPercent = round(probability * 100.0, 1),
            confidence = round(confidence, 3),
            terminal = terminal,
            terminalOutcome = terminalOutcome,
            rawValue = value,
            rawProbability = rawProbability,
            topFactors = topFactors,
            components = components,
            state = BattleValueState(own, opponent, state.fieldAdvantage, confidence, terminalOutcome),
        )
    }

    fun decideWinRate(state: String, sideIndex: Int, maxNodes: Int, runtime: SearchRuntime): SearchDecision {
        val ownRanked = ranked(runtime.candidates(state, sideIndex))
        if (ownRanked.isEmpty()) return SearchDecision()
        val heuristic = heuristic(ownRanked)!!
        val own = bounded(ownRanked, heuristic, 4)
        val opponentRanked = ranked(runtime.candidates(state, other(sideIndex)))
        val opponent = bounded(opponentRanked, heuristic(opponentRanked), 2)
        val distribution = distribution(opponent)
        val context = SearchContext(maxNodes.coerceAtLeast(0))
        val evaluations = own.mapNotNull { action ->
            val outcomes = distribution.mapNotNull { opponentEntry ->
                val next = transition(context, runtime, state, sideIndex, action, opponentEntry.action) ?: return@mapNotNull null
                val probability = runtime.winProbability(next, sideIndex)
                SearchOutcome(opponentEntry.action, opponentEntry.probability, probability, probability, probability)
            }
            if (outcomes.isEmpty()) null else evaluate(action, outcomes, false)
        }
        val heuristicEvaluation = evaluations.firstOrNull { it.action?.id == heuristic.id }
        val winner = evaluations.sortedWith(
            compareByDescending<SearchEvaluation> { it.expectedWinProbability }
                .thenByDescending { it.action?.score ?: 0.0 },
        ).firstOrNull()
        val selected = if (winner != null &&
            (heuristicEvaluation == null || winner.expectedWinProbability >= heuristicEvaluation.expectedWinProbability + MINIMUM_GAIN)
        ) winner.action ?: heuristic else heuristic
        return SearchDecision(
            selected = selected,
            policyOverride = selected.id != heuristic.id,
            evaluations = evaluations,
            visitedNodes = context.nodes,
            cacheHits = context.cacheHits,
            budgetExhausted = context.budgetExhausted,
        )
    }

    fun decideTwoTurn(
        state: String,
        sideIndex: Int,
        maxNodes: Int,
        runtime: SearchRuntime,
        exactOpponentAction: SearchAction? = null,
    ): SearchDecision {
        val ownRanked = ranked(runtime.candidates(state, sideIndex))
        if (ownRanked.isEmpty()) return SearchDecision()
        val heuristic = heuristic(ownRanked)!!
        val own = bounded(ownRanked, heuristic, 3)
        val opponentDistribution = exactOpponentAction?.let { listOf(WeightedAction(it, 1.0)) }
            ?: ranked(runtime.candidates(state, other(sideIndex))).let {
                distribution(bounded(it, heuristic(it), 2))
            }
        val context = SearchContext(maxNodes.coerceAtLeast(0))
        val evaluations = own.mapNotNull { action ->
            val outcomes = opponentDistribution.mapNotNull { opponent ->
                val next = transition(context, runtime, state, sideIndex, action, opponent.action) ?: return@mapNotNull null
                val probability = runtime.winProbability(next, sideIndex)
                MutableOutcome(opponent.action, opponent.probability, probability, next)
            }.toMutableList()
            if (outcomes.isEmpty()) null else MutableEvaluation(action, outcomes)
        }.toMutableList()
        evaluations.sortWith { left, right -> evaluationOrder.compare(left.value, right.value) }
        val gap = if (evaluations.size > 1) evaluations[0].value.searchValue - evaluations[1].value.searchValue
            else Double.POSITIVE_INFINITY
        val beam = if (gap <= CONTINUATION_BEAM_GAP) evaluations.take(2) else emptyList()
        beam.forEach { candidate ->
            val outcome = candidate.outcomes.sortedWith(
                compareByDescending<MutableOutcome> { it.probability }.thenBy { it.winProbability },
            ).firstOrNull() ?: return@forEach
            val continuation = secondTurn(context, runtime, outcome.nextState, sideIndex) ?: return@forEach
            val immediateWeight = 1.0 - SECOND_TURN_DISCOUNT
            outcome.evaluatedProbability = outcome.winProbability * immediateWeight + continuation.expectedWinProbability * SECOND_TURN_DISCOUNT
            outcome.riskProbability = outcome.winProbability * immediateWeight + continuation.worstWinProbability * SECOND_TURN_DISCOUNT
            outcome.continuation = continuation
            candidate.refresh(reliability = false)
        }
        evaluations.sortWith { left, right -> evaluationOrder.compare(left.value, right.value) }
        val initiallySelected = evaluations.firstOrNull()?.value
        val selected = if (initiallySelected == null) heuristic
            else nonConsecutiveGuard(evaluations.map { it.value }, initiallySelected)
        return SearchDecision(
            selected = selected,
            policyOverride = selected.id != heuristic.id,
            evaluations = evaluations.map { it.value },
            visitedNodes = context.nodes,
            cacheHits = context.cacheHits,
            budgetExhausted = context.budgetExhausted,
            depthTurns = if (beam.isEmpty()) 1 else 2,
        )
    }

    fun estimateWinProbabilityJson(
        stateJson: String,
        calibrationIntercept: Double = 0.0,
        calibrationSlope: Double = 1.0,
    ): String = codec.encodeToString(estimateWinProbability(
        codec.decodeFromString<BattleValueState>(stateJson),
        calibrationIntercept,
        calibrationSlope,
    ))
}

@JsExport
fun estimateWinProbabilityJson(
    stateJson: String,
    calibrationIntercept: Double = 0.0,
    calibrationSlope: Double = 1.0,
): String = SharedAiCore.estimateWinProbabilityJson(stateJson, calibrationIntercept, calibrationSlope)

@JsExport
fun decideWinRateJson(
    state: String,
    sideIndex: Int,
    maxNodes: Int,
    candidates: (String, Int) -> String,
    transition: (String, String, String) -> String?,
    winProbability: (String, Int) -> Double,
    terminal: (String) -> Boolean,
): String = codec.encodeToString(SharedAiCore.decideWinRate(
    state,
    sideIndex,
    maxNodes,
    callbackRuntime(candidates, transition, winProbability, terminal),
))

@JsExport
fun decideTwoTurnJson(
    state: String,
    sideIndex: Int,
    maxNodes: Int,
    candidates: (String, Int) -> String,
    transition: (String, String, String) -> String?,
    winProbability: (String, Int) -> Double,
    terminal: (String) -> Boolean,
    exactOpponentActionJson: String? = null,
): String = codec.encodeToString(SharedAiCore.decideTwoTurn(
    state,
    sideIndex,
    maxNodes,
    callbackRuntime(candidates, transition, winProbability, terminal),
    exactOpponentActionJson?.let { codec.decodeFromString<SearchAction>(it) },
))

private fun callbackRuntime(
    candidates: (String, Int) -> String,
    transition: (String, String, String) -> String?,
    winProbability: (String, Int) -> Double,
    terminal: (String) -> Boolean,
) = object : SearchRuntime {
    override fun candidates(state: String, sideIndex: Int): List<SearchAction> =
        codec.decodeFromString(candidates(state, sideIndex))
    override fun transition(state: String, sideZeroActionId: String, sideOneActionId: String): String? =
        transition(state, sideZeroActionId, sideOneActionId)
    override fun winProbability(state: String, sideIndex: Int): Double = winProbability(state, sideIndex)
    override fun terminal(state: String): Boolean = terminal(state)
}

private data class WeightedAction(val action: SearchAction, val probability: Double)
private data class SearchContext(
    val maxNodes: Int,
    val cache: MutableMap<String, String> = mutableMapOf(),
    var nodes: Int = 0,
    var cacheHits: Int = 0,
    var budgetExhausted: Boolean = false,
)
private data class MutableOutcome(
    val opponent: SearchAction,
    val probability: Double,
    val winProbability: Double,
    val nextState: String,
    var evaluatedProbability: Double = winProbability,
    var riskProbability: Double = winProbability,
    var continuation: SearchEvaluation? = null,
) {
    fun immutable() = SearchOutcome(opponent, probability, winProbability, evaluatedProbability, riskProbability, continuation)
}
private class MutableEvaluation(val action: SearchAction, val outcomes: MutableList<MutableOutcome>) {
    var value: SearchEvaluation = evaluate(action, outcomes.map { it.immutable() }, true)
    fun refresh(reliability: Boolean) {
        value = evaluate(action, outcomes.map { it.immutable() }, reliability)
    }
}

private fun secondTurn(context: SearchContext, runtime: SearchRuntime, state: String, sideIndex: Int): SearchEvaluation? {
    val probability = runtime.winProbability(state, sideIndex)
    if (runtime.terminal(state)) return SearchEvaluation(null, probability, probability, probability)
    val ownRanked = ranked(runtime.candidates(state, sideIndex))
    val opponentRanked = ranked(runtime.candidates(state, other(sideIndex)))
    val own = bounded(ownRanked, heuristic(ownRanked), 2)
    val opponents = distribution(bounded(opponentRanked, heuristic(opponentRanked), 1))
    return own.mapNotNull { action ->
        val outcomes = opponents.mapNotNull { opponent ->
            val next = transition(context, runtime, state, sideIndex, action, opponent.action) ?: return@mapNotNull null
            val nextProbability = runtime.winProbability(next, sideIndex)
            SearchOutcome(opponent.action, opponent.probability, nextProbability, nextProbability, nextProbability)
        }
        if (outcomes.isEmpty()) null else evaluate(action, outcomes, true, roundValues = false)
    }.sortedWith(
        compareByDescending<SearchEvaluation> { it.searchValue }
            .thenByDescending { it.expectedWinProbability },
    ).firstOrNull()
}

private fun transition(
    context: SearchContext,
    runtime: SearchRuntime,
    state: String,
    sideIndex: Int,
    own: SearchAction,
    opponent: SearchAction,
): String? {
    val zero = if (sideIndex == 0) own.id else opponent.id
    val one = if (sideIndex == 0) opponent.id else own.id
    val key = "$state|$zero|$one"
    context.cache[key]?.let { context.cacheHits += 1; return it }
    transitionCache[key]?.let {
        context.cacheHits += 1
        context.cache[key] = it
        return it
    }
    if (context.nodes >= context.maxNodes) { context.budgetExhausted = true; return null }
    val next = runtime.transition(state, zero, one) ?: return null
    context.nodes += 1
    context.cache[key] = next
    transitionCache.remove(key)
    transitionCache[key] = next
    if (transitionCache.size > TRANSITION_CACHE_LIMIT) {
        transitionCache.remove(transitionCache.keys.first())
    }
    return next
}

private fun ranked(candidates: List<SearchAction>) = candidates.sortedByDescending { it.score }
private fun heuristic(candidates: List<SearchAction>): SearchAction? =
    candidates.firstOrNull { it.heuristicSelected } ?: candidates.firstOrNull()
private fun bounded(candidates: List<SearchAction>, selected: SearchAction?, limit: Int): List<SearchAction> {
    val result = candidates.take(limit).toMutableList()
    if (selected != null && result.none { it.id == selected.id }) {
        if (result.isEmpty()) result += selected else result[result.lastIndex] = selected
    }
    return result
}
private fun distribution(candidates: List<SearchAction>): List<WeightedAction> {
    if (candidates.isEmpty()) return emptyList()
    val maximum = candidates.maxOf { it.score }
    val weights = candidates.map { kotlin.math.exp(((it.score - maximum) / OPPONENT_TEMPERATURE).coerceAtLeast(-20.0)) }
    val total = weights.sum()
    return candidates.indices.map { WeightedAction(candidates[it], if (total > 0) weights[it] / total else 1.0 / candidates.size) }
}
private fun evaluate(
    action: SearchAction,
    outcomes: List<SearchOutcome>,
    reliability: Boolean,
    roundValues: Boolean = true,
): SearchEvaluation {
    val covered = outcomes.sumOf { it.opponentProbability }.coerceAtLeast(Double.MIN_VALUE)
    val penalty = if (reliability) action.reliabilityPenalty else 0.0
    val expected = (outcomes.sumOf { it.evaluatedWinProbability * it.opponentProbability } / covered - penalty).coerceAtLeast(0.0)
    val worst = (outcomes.minOf { it.riskWinProbability } - penalty).coerceAtLeast(0.0)
    val searchValue = expected * 0.8 + worst * 0.2
    return if (roundValues) {
        SearchEvaluation(action, round(expected), round(worst), round(searchValue), outcomes)
    } else {
        SearchEvaluation(action, expected, worst, searchValue, outcomes)
    }
}
private val evaluationOrder = compareByDescending<SearchEvaluation> { it.searchValue }.thenByDescending { it.action?.score ?: 0.0 }
private fun nonConsecutiveGuard(evaluations: List<SearchEvaluation>, selected: SearchEvaluation): SearchAction {
    val selectedAction = selected.action ?: return evaluations.first().action!!
    val immediate = evaluations.mapNotNull { it.action }
        .filter { it.kind == "move" && !it.statusMove && it.nonConsecutive }
        .sortedWith(compareByDescending<SearchAction> { it.expectedDamage }.thenByDescending { it.score })
        .firstOrNull() ?: return selectedAction
    if (selectedAction.kind != "move" || selectedAction.statusMove || selectedAction.id == immediate.id || selectedAction.guaranteedKnockout) return selectedAction
    val continuations = selected.outcomes.mapNotNull { it.continuation?.action }
    val deferred = continuations.isNotEmpty() && continuations.all { it.id == immediate.id }
    val useful = immediate.expectedDamage - selectedAction.expectedDamage >= maxOf(8.0, selectedAction.expectedDamage * 0.08) &&
        immediate.opponentKnockoutBeforeActionProbability <= selectedAction.opponentKnockoutBeforeActionProbability + 0.05
    return if (deferred || useful) immediate else selectedAction
}
private fun normalize(side: BattleValueSide): BattleValueSide {
    val teamSize = finite(side.teamSize).coerceAtLeast(1.0)
    val aceCount = finite(side.aceCandidateCount).coerceAtLeast(1.0)
    return side.copy(
        teamSize = teamSize,
        livingCount = finite(side.livingCount).coerceIn(0.0, teamSize),
        totalHpRatio = finite(side.totalHpRatio).coerceIn(0.0, teamSize),
        aceCandidateCount = aceCount,
        aceAliveCount = finite(side.aceAliveCount).coerceIn(0.0, aceCount),
        aceHpRatio = finite(side.aceHpRatio).coerceIn(0.0, aceCount),
        positiveBoosts = finite(side.positiveBoosts).coerceAtLeast(0.0),
        statusBurden = finite(side.statusBurden).coerceAtLeast(0.0),
        hazardLayers = finite(side.hazardLayers).coerceAtLeast(0.0),
        uniqueCountersAlive = finite(side.uniqueCountersAlive).coerceAtLeast(0.0),
        gimmicksRemaining = finite(side.gimmicksRemaining).coerceAtLeast(0.0),
        matchupCoverage = finite(side.matchupCoverage).coerceIn(0.0, 1.0),
        safeKoCoverage = finite(side.safeKoCoverage).coerceIn(0.0, 1.0),
        benchReadiness = finite(side.benchReadiness).coerceIn(0.0, 1.0),
        sweepPotential = finite(side.sweepPotential).coerceIn(0.0, 1.0),
    )
}
private fun other(sideIndex: Int): Int {
    require(sideIndex == 0 || sideIndex == 1) { "sideIndex must be 0 or 1" }
    return 1 - sideIndex
}
private fun finite(value: Double) = if (value.isFinite()) value else 0.0
private fun calibrate(probability: Double, intercept: Double, slope: Double): Double {
    val clamped = probability.coerceIn(0.0001, 0.9999)
    val logit = kotlin.math.ln(clamped / (1.0 - clamped))
    return (1.0 / (1.0 + kotlin.math.exp(-(finite(intercept) + finite(slope) * logit)))).coerceIn(0.01, 0.99)
}
private fun round(value: Double, places: Int = 4): Double {
    var scale = 1.0
    repeat(places) { scale *= 10.0 }
    return kotlin.math.floor(value * scale + 0.5) / scale
}
