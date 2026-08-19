@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedObservedProjectionSide(
    val activeHp: Int = -1,
    val activeMaximumHp: Int = -1,
    val hazards: List<Int> = emptyList(),
    val pressure: SharedSearchPressure? = null,
    val ranks: List<Int> = emptyList(),
    val gimmickRemaining: Boolean? = null,
    val adapterStates: Set<String>? = null,
    val sideConditions: Map<String, SharedSearchTimedEffect> = emptyMap(),
)

@Serializable
data class SharedProjectionObservation(
    val turn: Int = -1,
    val sides: List<SharedObservedProjectionSide> = emptyList(),
    val field: SharedSearchFieldState = SharedSearchFieldState(),
)

@Serializable
data class SharedProjectionDifference(
    val path: String,
    val expected: String,
    val observed: String,
)

@Serializable
data class SharedProjectionDifferentialResult(
    val matches: Boolean,
    val differences: List<SharedProjectionDifference> = emptyList(),
)

/** 플랫폼 로그에서 관측 가능한 상태만 공통 탐색 투영과 비교한다. */
object SharedProjectionDifferentialEvaluator {
    fun evaluate(
        expected: SharedSearchProjectionState,
        observed: SharedProjectionObservation,
    ): SharedProjectionDifferentialResult {
        val differences = mutableListOf<SharedProjectionDifference>()
        fun compare(path: String, expectedValue: Any?, observedValue: Any?) {
            if (expectedValue != observedValue) differences += SharedProjectionDifference(
                path,
                expectedValue.toString(),
                observedValue.toString(),
            )
        }

        if (observed.turn >= 0) compare("turn", expected.turn, observed.turn)
        observed.sides.forEachIndexed { side, actual ->
            val activeSlot = expected.active.getOrElse(side) { 0 }
            if (actual.activeHp >= 0) {
                compare("sides[$side].activeHp", expected.hp.getOrNull(side)?.getOrNull(activeSlot), actual.activeHp)
            }
            if (actual.activeMaximumHp >= 0) {
                compare(
                    "sides[$side].activeMaximumHp",
                    expected.maxHp.getOrNull(side)?.getOrNull(activeSlot),
                    actual.activeMaximumHp,
                )
            }
            actual.hazards.forEachIndexed { index, layers ->
                if (layers >= 0) compare(
                    "sides[$side].hazards[$index]",
                    expected.hazards.getOrNull(side)?.getOrNull(index) ?: 0,
                    layers,
                )
            }
            actual.pressure?.let {
                compare(
                    "sides[$side].pressure",
                    expected.pressures.getOrNull(side)?.getOrNull(activeSlot) ?: SharedSearchPressure(),
                    it,
                )
            }
            actual.ranks.forEachIndexed { index, rank ->
                compare(
                    "sides[$side].ranks[$index]",
                    expected.ranks.getOrNull(side)?.getOrNull(activeSlot)?.getOrNull(index) ?: 0,
                    rank,
                )
            }
            actual.gimmickRemaining?.let {
                compare("sides[$side].gimmickRemaining", expected.gimmicksRemaining.getOrNull(side), it)
            }
            actual.adapterStates?.let {
                compare(
                    "sides[$side].adapterStates",
                    expected.abilityStates.getOrNull(side)?.getOrNull(activeSlot).orEmpty()
                        .filter(::isEntryAdapterState).toSet(),
                    it,
                )
            }
            compare(
                "sides[$side].sideConditions",
                expected.sideConditions.getOrNull(side).orEmpty(),
                actual.sideConditions,
            )
        }
        compare("field", expected.field, observed.field)
        return SharedProjectionDifferentialResult(differences.isEmpty(), differences)
    }
}

private fun isEntryAdapterState(value: String): Boolean =
    value == "neutralizinggas" || value == "transformed" || value == "anticipation" ||
        value == "paradox" || value == "forecast" || value.startsWith("traced:") ||
        value.startsWith("forewarn:") || value.startsWith("frisked:") || value.startsWith("form:")

@Serializable
private data class SharedProjectionDifferentialInput(
    val expected: SharedSearchProjectionState,
    val observed: SharedProjectionObservation,
)

@JsExport
fun evaluateSharedProjectionDifferentialJson(inputJson: String): String {
    val input = codec.decodeFromString<SharedProjectionDifferentialInput>(inputJson)
    return codec.encodeToString(SharedProjectionDifferentialEvaluator.evaluate(input.expected, input.observed))
}
