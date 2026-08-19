@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedSearchFieldCombatInput(
    val field: SharedSearchFieldState = SharedSearchFieldState(),
    val attackerSideConditions: Map<String, SharedSearchTimedEffect> = emptyMap(),
    val defenderSideConditions: Map<String, SharedSearchTimedEffect> = emptyMap(),
    val moveType: String = "",
    val moveCategory: String = "",
    val attackerTypes: List<String> = emptyList(),
    val attackerAbility: String = "",
    val attackerItem: String = "",
    val defenderTypes: List<String> = emptyList(),
    val defenderAbility: String = "",
    val defenderItem: String = "",
)

@Serializable
data class SharedSearchFieldCombatResult(
    val weatherDamageMultiplier: Double,
    val terrainDamageMultiplier: Double,
    val screenDamageMultiplier: Double,
    val speedMultiplier: Double,
    val trickRoomActive: Boolean,
)

/** 탐색 상태의 전장 효과가 피해와 속도에 주는 배율을 JVM/JS에서 동일하게 계산한다. */
object SharedSearchFieldCombatEvaluator {
    fun evaluate(input: SharedSearchFieldCombatInput): SharedSearchFieldCombatResult {
        val weather = cleanField(input.field.weather?.id.orEmpty())
        val terrain = cleanField(input.field.terrain?.id.orEmpty())
        val moveType = cleanField(input.moveType)
        val category = cleanField(input.moveCategory)
        val attackerAbility = cleanField(input.attackerAbility)
        val attackerGrounded = grounded(input.attackerTypes, attackerAbility, input.attackerItem)
        val defenderGrounded = grounded(input.defenderTypes, input.defenderAbility, input.defenderItem)

        val weatherDamage = when {
            weather == "primordialsea" && moveType == "fire" -> 0.0
            weather == "desolateland" && moveType == "water" -> 0.0
            weather in setOf("raindance", "primordialsea") && moveType == "water" -> 1.5
            weather in setOf("raindance", "primordialsea") && moveType == "fire" -> 0.5
            weather in setOf("sunnyday", "desolateland") && moveType == "fire" -> 1.5
            weather in setOf("sunnyday", "desolateland") && moveType == "water" -> 0.5
            else -> 1.0
        }
        val terrainDamage = when {
            terrain == "electricterrain" && attackerGrounded && moveType == "electric" -> 1.3
            terrain == "grassyterrain" && attackerGrounded && moveType == "grass" -> 1.3
            terrain == "psychicterrain" && attackerGrounded && moveType == "psychic" -> 1.3
            terrain == "mistyterrain" && defenderGrounded && moveType == "dragon" -> 0.5
            else -> 1.0
        }
        val screens = input.defenderSideConditions.keys.map(::cleanField).toSet()
        val screenDamage = when {
            "auroraveil" in screens -> 0.5
            category == "physical" && "reflect" in screens -> 0.5
            category == "special" && "lightscreen" in screens -> 0.5
            else -> 1.0
        }
        var speed = if (input.attackerSideConditions.keys.any { cleanField(it) == "tailwind" }) 2.0 else 1.0
        if (
            (attackerAbility == "chlorophyll" && weather in setOf("sunnyday", "desolateland")) ||
            (attackerAbility == "sandrush" && weather == "sandstorm") ||
            (attackerAbility == "slushrush" && weather in setOf("snow", "hail")) ||
            (attackerAbility == "swiftswim" && weather in setOf("raindance", "primordialsea"))
        ) speed *= 2.0

        return SharedSearchFieldCombatResult(
            weatherDamage,
            terrainDamage,
            screenDamage,
            speed,
            input.field.pseudoWeather.keys.any { cleanField(it) == "trickroom" },
        )
    }

    private fun grounded(types: List<String>, ability: String, item: String): Boolean =
        types.none { cleanField(it) == "flying" } && ability != "levitate" && cleanField(item) != "airballoon"
}

@JsExport
fun evaluateSharedSearchFieldCombatJson(inputJson: String): String = codec.encodeToString(
    SharedSearchFieldCombatEvaluator.evaluate(codec.decodeFromString<SharedSearchFieldCombatInput>(inputJson)),
)

private fun cleanField(value: String): String =
    value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }
