@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedDamageTypePokemon(
    val id: String = "",
    val types: List<String> = emptyList(),
    val originalTypes: List<String> = emptyList(),
    val ability: String = "",
    val item: String = "",
    val hp: Int = 0,
    val maximumHp: Int = 1,
    val terastallized: Boolean = false,
    val teraType: String = "",
    val stellarBoostedTypes: List<String> = emptyList(),
    val gastroAcid: Boolean = false,
    val neutralizingGasSuppressed: Boolean = false,
    val teraShellActive: Boolean = false,
)

@Serializable
data class SharedDamageTypeMove(
    val id: String = "",
    val type: String = "Normal",
    val sound: Boolean = false,
)

@Serializable
data class SharedDamageTypeInput(
    val attacker: SharedDamageTypePokemon = SharedDamageTypePokemon(),
    val defender: SharedDamageTypePokemon = SharedDamageTypePokemon(),
    val move: SharedDamageTypeMove = SharedDamageTypeMove(),
    val weather: String = "",
)

@Serializable
data class SharedDamageTypeResult(
    val stab: Double = 1.0,
    val effectiveness: Double = 1.0,
    val absorbedByAbility: String = "",
)

object SharedDamageTypeEvaluator {
    private val chart: Map<String, Map<String, Double>> = mapOf(
        "normal" to mapOf("rock" to 0.5, "ghost" to 0.0, "steel" to 0.5),
        "fire" to mapOf("fire" to 0.5, "water" to 0.5, "grass" to 2.0, "ice" to 2.0, "bug" to 2.0, "rock" to 0.5, "dragon" to 0.5, "steel" to 2.0),
        "water" to mapOf("fire" to 2.0, "water" to 0.5, "grass" to 0.5, "ground" to 2.0, "rock" to 2.0, "dragon" to 0.5),
        "electric" to mapOf("water" to 2.0, "electric" to 0.5, "grass" to 0.5, "ground" to 0.0, "flying" to 2.0, "dragon" to 0.5),
        "grass" to mapOf("fire" to 0.5, "water" to 2.0, "grass" to 0.5, "poison" to 0.5, "ground" to 2.0, "flying" to 0.5, "bug" to 0.5, "rock" to 2.0, "dragon" to 0.5, "steel" to 0.5),
        "ice" to mapOf("fire" to 0.5, "water" to 0.5, "grass" to 2.0, "ice" to 0.5, "ground" to 2.0, "flying" to 2.0, "dragon" to 2.0, "steel" to 0.5),
        "fighting" to mapOf("normal" to 2.0, "ice" to 2.0, "poison" to 0.5, "flying" to 0.5, "psychic" to 0.5, "bug" to 0.5, "rock" to 2.0, "ghost" to 0.0, "dark" to 2.0, "steel" to 2.0, "fairy" to 0.5),
        "poison" to mapOf("grass" to 2.0, "poison" to 0.5, "ground" to 0.5, "rock" to 0.5, "ghost" to 0.5, "steel" to 0.0, "fairy" to 2.0),
        "ground" to mapOf("fire" to 2.0, "electric" to 2.0, "grass" to 0.5, "poison" to 2.0, "flying" to 0.0, "bug" to 0.5, "rock" to 2.0, "steel" to 2.0),
        "flying" to mapOf("electric" to 0.5, "grass" to 2.0, "fighting" to 2.0, "bug" to 2.0, "rock" to 0.5, "steel" to 0.5),
        "psychic" to mapOf("fighting" to 2.0, "poison" to 2.0, "psychic" to 0.5, "dark" to 0.0, "steel" to 0.5),
        "bug" to mapOf("fire" to 0.5, "grass" to 2.0, "fighting" to 0.5, "poison" to 0.5, "flying" to 0.5, "psychic" to 2.0, "ghost" to 0.5, "dark" to 2.0, "steel" to 0.5, "fairy" to 0.5),
        "rock" to mapOf("fire" to 2.0, "ice" to 2.0, "fighting" to 0.5, "ground" to 0.5, "flying" to 2.0, "bug" to 2.0, "steel" to 0.5),
        "ghost" to mapOf("normal" to 0.0, "psychic" to 2.0, "ghost" to 2.0, "dark" to 0.5),
        "dragon" to mapOf("dragon" to 2.0, "steel" to 0.5, "fairy" to 0.0),
        "dark" to mapOf("fighting" to 0.5, "psychic" to 2.0, "ghost" to 2.0, "dark" to 0.5, "fairy" to 0.5),
        "steel" to mapOf("fire" to 0.5, "water" to 0.5, "electric" to 0.5, "ice" to 2.0, "rock" to 2.0, "steel" to 0.5, "fairy" to 2.0),
        "fairy" to mapOf("fire" to 0.5, "fighting" to 2.0, "poison" to 0.5, "dragon" to 2.0, "dark" to 2.0, "steel" to 0.5),
    )

    fun effectiveness(moveType: String, defenderTypes: List<String>): Double =
        multiplier(clean(moveType), defenderTypes)

    fun evaluate(input: SharedDamageTypeInput): SharedDamageTypeResult {
        val attacker = input.attacker
        val defender = input.defender
        val move = input.move
        val moveType = clean(move.type)
        val attackerAbility = activeAbility(attacker)
        val defenderAbility = activeAbility(defender)
        val ignoresDefenderAbility = attackerAbility in setOf("moldbreaker", "teravolt")
        val absorbedBy = if (ignoresDefenderAbility) "" else absorbingAbility(defenderAbility, moveType, move.sound)

        val currentSameType = attacker.types.any { clean(it) == moveType }
        val originalSameType = attacker.originalTypes.any { clean(it) == moveType }
        val stellar = attacker.terastallized && clean(attacker.teraType) == "stellar"
        val stab = when {
            stellar -> {
                val available = attacker.stellarBoostedTypes.none { clean(it) == moveType }
                if (available) if (originalSameType) 2.0 else 1.2 else if (originalSameType) 1.5 else 1.0
            }
            attacker.terastallized && clean(attacker.teraType) == moveType -> when {
                attackerAbility == "adaptability" && originalSameType -> 2.25
                attackerAbility == "adaptability" -> 2.0
                originalSameType -> 2.0
                else -> 1.5
            }
            attacker.terastallized && originalSameType -> 1.5
            currentSameType -> if (attackerAbility == "adaptability") 2.0 else 1.5
            else -> 1.0
        }

        var effectiveness = when {
            moveType == "stellar" -> if (defender.terastallized) 2.0 else 1.0
            absorbedBy.isNotEmpty() -> 0.0
            else -> multiplier(moveType, defender.types)
        }
        if (
            effectiveness == 0.0 && attackerAbility in setOf("mindseye", "scrappy") &&
            moveType in setOf("normal", "fighting") && defender.types.any { clean(it) == "ghost" }
        ) {
            effectiveness = defender.types.fold(1.0) { result, type ->
                if (clean(type) == "ghost") result else result * singleMultiplier(moveType, type)
            }
        }
        if (clean(move.id) == "freezedry" && defender.types.any { clean(it) == "water" }) {
            effectiveness *= 4.0
        }
        if (clean(move.id) == "flyingpress") effectiveness *= multiplier("flying", defender.types)
        if (
            effectiveness <= 1.0 && clean(move.id) != "struggle" && defenderAbility == "wonderguard" &&
            !ignoresDefenderAbility
        ) effectiveness = 0.0
        if (
            effectiveness >= 1.0 && clean(move.id) != "struggle" && defenderAbility == "terashell" &&
            clean(defender.id) == "terapagosterastal" && !ignoresDefenderAbility &&
            (defender.hp >= defender.maximumHp || defender.teraShellActive)
        ) effectiveness = 0.5
        if (
            clean(input.weather) == "deltastream" && defender.types.any { clean(it) == "flying" } &&
            moveType in setOf("electric", "ice", "rock") && effectiveness > 1.0
        ) effectiveness /= 2.0
        return SharedDamageTypeResult(stab, effectiveness, absorbedBy)
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDamageTypeInput>(inputJson)),
    )

    private fun activeAbility(pokemon: SharedDamageTypePokemon): String =
        if (pokemon.gastroAcid || pokemon.neutralizingGasSuppressed) "" else clean(pokemon.ability)

    private fun absorbingAbility(ability: String, moveType: String, sound: Boolean): String = when {
        ability == "soundproof" && sound -> ability
        moveType == "electric" && ability in setOf("lightningrod", "voltabsorb") -> ability
        moveType == "water" && ability in setOf("dryskin", "stormdrain", "waterabsorb") -> ability
        moveType == "fire" && ability in setOf("flashfire", "wellbakedbody") -> ability
        moveType == "grass" && ability == "sapsipper" -> ability
        moveType == "ground" && ability in setOf("eartheater", "levitate") -> ability
        else -> ""
    }

    private fun multiplier(attackType: String, defenderTypes: List<String>): Double =
        defenderTypes.fold(1.0) { result, type -> result * singleMultiplier(attackType, type) }

    private fun singleMultiplier(attackType: String, defenderType: String): Double =
        chart[clean(attackType)]?.get(clean(defenderType)) ?: 1.0

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

@JsExport
fun evaluateSharedDamageTypeJson(inputJson: String): String =
    SharedDamageTypeEvaluator.evaluateJson(inputJson)
