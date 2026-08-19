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
data class CounterMatchupInput(
    val slot: Int,
    val pokemonId: String = "",
    val species: String = "",
    val living: Boolean = true,
    val hpPercent: Double = 1.0,
    val incomingDamageRatio: Double = 1.0,
    val outgoingDamageRatio: Double = 0.0,
    val survivesHit: Boolean = false,
    val actsBefore: Boolean = false,
    val priorityKo: Boolean = false,
    val aceQualified: Boolean = false,
)

@Serializable
data class ThreatObservationInput(
    val enemySlot: Int,
    val enemyPokemonId: String = "",
    val species: String = "",
    val living: Boolean = true,
    val aceScore: Double = 0.0,
    val setupScore: Double = 0.0,
    val offense: Double = 0.0,
    val hpPercent: Double = 1.0,
    val resources: List<CounterMatchupInput> = emptyList(),
)

@Serializable
data class ThreatCounterInput(val threats: List<ThreatObservationInput> = emptyList())

@Serializable
data class CounterResourceFact(
    val slot: Int,
    val pokemonId: String = "",
    val species: String = "",
    val classification: String = "",
    val incomingDamageRatio: Double = 1.0,
    val outgoingDamageRatio: Double = 0.0,
    val survivesHit: Boolean = false,
    val actsBefore: Boolean = false,
    val priorityKo: Boolean = false,
    val aceQualified: Boolean = false,
)

@Serializable
data class PreservedThreatFact(
    val enemySlot: Int,
    val enemyPokemonId: String,
    val species: String,
    val threatLevel: String,
)

@Serializable
data class PreservedResourceFact(
    val slot: Int,
    val pokemonId: String = "",
    val species: String = "",
    val classification: String = "",
    val incomingDamageRatio: Double = 1.0,
    val outgoingDamageRatio: Double = 0.0,
    val survivesHit: Boolean = false,
    val actsBefore: Boolean = false,
    val priorityKo: Boolean = false,
    val aceQualified: Boolean = false,
    val threats: List<PreservedThreatFact> = emptyList(),
)

@Serializable
data class ThreatCounterFact(
    val enemySlot: Int,
    val enemyPokemonId: String,
    val species: String,
    val threatLevel: String,
    val threatScore: Double,
    val counters: List<CounterResourceFact> = emptyList(),
    val softChecks: List<CounterResourceFact> = emptyList(),
    val revengeKillers: List<CounterResourceFact> = emptyList(),
    val mustPreserveResources: List<CounterResourceFact> = emptyList(),
)

@Serializable
data class ThreatCounterResult(
    val threats: List<ThreatCounterFact> = emptyList(),
    val mustPreserveResources: List<PreservedResourceFact> = emptyList(),
)

/** 대면 관측을 카운터 분류와 유일 대응 자원 보존 사실로 변환한다. */
object SharedPreservationEvaluator {
    fun evaluate(input: ThreatCounterInput): ThreatCounterResult {
        val threats = input.threats.filter { it.living }.map { enemy ->
            val score = min(12.0, enemy.aceScore.coerceIn(0.0, 20.0)) +
                min(6.0, max(0.0, enemy.setupScore)) +
                max(0.0, enemy.offense - 100.0) / 15.0 + enemy.hpPercent.coerceIn(0.0, 1.0) * 2.0
            val level = when {
                score >= 14.0 -> "critical"
                score >= 9.0 -> "high"
                score >= 5.0 -> "medium"
                else -> "low"
            }
            val resources = if (level == "critical" || level == "high") {
                enemy.resources.filter { it.living }.mapNotNull(::classify).sortedWith(
                    compareByDescending<CounterResourceFact> { it.classification == "counter" }
                        .thenByDescending { it.priorityKo }
                        .thenByDescending { it.outgoingDamageRatio }
                        .thenBy { it.incomingDamageRatio },
                )
            } else emptyList()
            val counters = resources.filter { it.classification == "counter" }
            val revenge = resources.filter { it.classification == "revenge_killer" }
            val soft = resources.filter { it.classification == "soft_check" }
            val preserve = when {
                counters.size <= 1 && counters.size == 1 -> counters
                counters.isEmpty() && soft.size == 1 -> soft
                counters.isEmpty() && soft.isEmpty() && revenge.size == 1 -> revenge
                else -> emptyList()
            }
            ThreatCounterFact(
                enemy.enemySlot, enemy.enemyPokemonId, enemy.species, level, round2(score),
                counters, soft, revenge, preserve,
            )
        }.sortedWith(compareByDescending<ThreatCounterFact> { it.threatScore }.thenBy { it.enemySlot })

        val preservedBySlot = linkedMapOf<Int, CounterResourceFact>()
        threats.forEach { threat -> threat.mustPreserveResources.forEach {
            if (it.slot !in preservedBySlot) preservedBySlot[it.slot] = it
        } }
        val preserved = preservedBySlot.values.map { resource ->
            PreservedResourceFact(
                slot = resource.slot,
                pokemonId = resource.pokemonId,
                species = resource.species,
                classification = resource.classification,
                incomingDamageRatio = resource.incomingDamageRatio,
                outgoingDamageRatio = resource.outgoingDamageRatio,
                survivesHit = resource.survivesHit,
                actsBefore = resource.actsBefore,
                priorityKo = resource.priorityKo,
                aceQualified = resource.aceQualified,
                threats = threats.filter { threat -> threat.mustPreserveResources.any { it.slot == resource.slot } }
                    .map { PreservedThreatFact(it.enemySlot, it.enemyPokemonId, it.species, it.threatLevel) },
            )
        }
        return ThreatCounterResult(threats, preserved)
    }

    private fun classify(input: CounterMatchupInput): CounterResourceFact? {
        val survives = input.survivesHit || input.incomingDamageRatio < input.hpPercent
        val revenge = input.priorityKo || (input.outgoingDamageRatio >= 1.0 && input.actsBefore)
        val counter = survives && (input.outgoingDamageRatio >= 0.65 ||
            (input.incomingDamageRatio <= 0.35 && input.outgoingDamageRatio >= 0.35))
        val soft = counter || (survives && (input.outgoingDamageRatio >= 0.35 || input.incomingDamageRatio <= 0.6))
        if (!soft && !revenge) return null
        return CounterResourceFact(
            input.slot, input.pokemonId, input.species,
            if (counter) "counter" else if (revenge) "revenge_killer" else "soft_check",
            input.incomingDamageRatio, input.outgoingDamageRatio, survives, input.actsBefore,
            input.priorityKo, input.aceQualified,
        )
    }
}

@JsExport
fun evaluateThreatCountersJson(inputJson: String): String =
    codec.encodeToString(SharedPreservationEvaluator.evaluate(codec.decodeFromString<ThreatCounterInput>(inputJson)))

private fun round2(value: Double): Double = round(value * 100.0) / 100.0
