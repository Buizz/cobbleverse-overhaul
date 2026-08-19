@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedEffectiveStatPokemon(
    val id: String = "",
    val baseSpecies: String = "",
    val types: List<String> = emptyList(),
    val ability: String = "",
    val item: String = "",
    val status: String = "",
    val hp: Int = 0,
    val maximumHp: Int = 1,
    val stats: SharedBattleStats = SharedBattleStats(),
    val boosts: Map<String, Int> = emptyMap(),
    val dynamaxTurns: Int = 0,
    val canEvolve: Boolean = false,
    val gastroAcid: Boolean = false,
    val neutralizingGasSuppressed: Boolean = false,
    val paradoxSource: String = "",
    val paradoxStat: String = "",
)

@Serializable
data class SharedEffectiveStatInput(
    val pokemon: SharedEffectiveStatPokemon = SharedEffectiveStatPokemon(),
    val stat: String = "attack",
    val weather: String = "",
    val terrain: String = "",
    val ignoreStages: Boolean = false,
    val ignoreNegative: Boolean = false,
    val ignorePositive: Boolean = false,
)

@Serializable
data class SharedEffectiveStatResult(
    val value: Double = 1.0,
    val stage: Int = 0,
    val paradoxStat: String = "",
)

@Serializable
data class SharedDamageStatInput(
    val attacker: SharedEffectiveStatPokemon = SharedEffectiveStatPokemon(),
    val defender: SharedEffectiveStatPokemon = SharedEffectiveStatPokemon(),
    val category: String = "Physical",
    val weather: String = "",
    val terrain: String = "",
    val critical: Boolean = false,
)

@Serializable
data class SharedDamageStatResult(
    val attack: Double = 1.0,
    val defence: Double = 1.0,
)

object SharedEffectiveStatEvaluator {
    private val sunnyWeather = setOf("sunnyday", "desolateland")

    fun evaluate(input: SharedEffectiveStatInput): SharedEffectiveStatResult {
        val pokemon = input.pokemon
        val stat = canonicalStat(input.stat)
        var stage = pokemon.boosts[stat] ?: 0
        if (input.ignoreStages) stage = 0
        if (input.ignoreNegative && stage < 0) stage = 0
        if (input.ignorePositive && stage > 0) stage = 0
        val ability = activeAbility(pokemon)
        val weather = clean(input.weather)
        val terrain = clean(input.terrain)
        var value = statValue(pokemon.stats, stat) * SharedBattleRankProjection.multiplier(stage.toDouble())
        if (
            stat in setOf("attack", "specialAttack") && clean(pokemon.item) == "lightball" &&
            clean(pokemon.baseSpecies.ifEmpty { pokemon.id }) == "pikachu"
        ) value *= 2.0
        if (
            stat in setOf("attack", "specialAttack") && ability == "defeatist" &&
            pokemon.hp <= pokemon.maximumHp / 2
        ) value *= 0.5
        if (stat in setOf("attack", "specialDefence") && ability == "flowergift" && weather in sunnyWeather) {
            value *= 1.5
        }
        if (stat == "attack") {
            if (clean(pokemon.status) == "brn" && ability != "guts") value *= 0.5
            if (ability in setOf("hugepower", "purepower")) value *= 2.0
            if (ability == "gorillatactics" && pokemon.dynamaxTurns <= 0) value *= 1.5
            if (pokemon.item == "choiceband") value *= 1.5
            if (ability == "orichalcumpulse" && weather in sunnyWeather) value *= 4.0 / 3.0
        }
        if (stat == "specialAttack") {
            if (pokemon.item == "choicespecs") value *= 1.5
            if (ability == "solarpower" && weather in sunnyWeather) value *= 1.5
            if (ability == "hadronengine" && terrain == "electricterrain") value *= 4.0 / 3.0
        }
        if (stat == "specialDefence" && pokemon.item == "assaultvest") value *= 1.5
        if (stat in setOf("defence", "specialDefence") && pokemon.item == "eviolite" && pokemon.canEvolve) {
            value *= 1.5
        }
        if (stat == "speed") {
            if (clean(pokemon.status) == "par") value *= 0.5
            if (pokemon.item == "choicescarf") value *= 1.5
        }
        val paradoxStat = paradoxStat(pokemon, weather, terrain)
        if (paradoxStat == stat) value *= if (stat == "speed") 1.5 else 1.3
        return SharedEffectiveStatResult(value.coerceAtLeast(1.0), stage, paradoxStat)
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedEffectiveStatInput>(inputJson)),
    )

    internal fun activeAbility(pokemon: SharedEffectiveStatPokemon): String =
        if (pokemon.gastroAcid || pokemon.neutralizingGasSuppressed) "" else clean(pokemon.ability)

    private fun paradoxStat(pokemon: SharedEffectiveStatPokemon, weather: String, terrain: String): String {
        val ability = activeAbility(pokemon)
        val fieldActive =
            (ability == "protosynthesis" && weather in sunnyWeather) ||
            (ability == "quarkdrive" && terrain == "electricterrain")
        if (!fieldActive && clean(pokemon.paradoxSource) != "boosterenergy") return ""
        canonicalStat(pokemon.paradoxStat).takeIf { pokemon.paradoxStat.isNotEmpty() }?.let { return it }
        return listOf("attack", "defence", "specialAttack", "specialDefence", "speed")
            .maxByOrNull { statValue(pokemon.stats, it) } ?: ""
    }

    private fun statValue(stats: SharedBattleStats, stat: String): Int = when (stat) {
        "attack" -> stats.attack
        "defence" -> stats.defence
        "specialAttack" -> stats.specialAttack
        "specialDefence" -> stats.specialDefence
        "speed" -> stats.speed
        else -> 1
    }

    private fun canonicalStat(value: String): String = when (clean(value)) {
        "attack", "atk" -> "attack"
        "defence", "defense", "def" -> "defence"
        "specialattack", "spa" -> "specialAttack"
        "specialdefence", "specialdefense", "spd" -> "specialDefence"
        "speed", "spe" -> "speed"
        else -> value
    }

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

object SharedDamageStatEvaluator {
    fun evaluate(input: SharedDamageStatInput): SharedDamageStatResult {
        val physical = input.category == "Physical"
        val attackerAbility = SharedEffectiveStatEvaluator.activeAbility(input.attacker)
        val defenderAbility = SharedEffectiveStatEvaluator.activeAbility(input.defender)
        val ignoresDefenderAbility = attackerAbility in setOf("moldbreaker", "teravolt")
        var attack = SharedEffectiveStatEvaluator.evaluate(
            SharedEffectiveStatInput(
                pokemon = input.attacker,
                stat = if (physical) "attack" else "specialAttack",
                weather = input.weather,
                terrain = input.terrain,
                ignoreNegative = input.critical,
                ignoreStages = defenderAbility == "unaware" && !ignoresDefenderAbility,
            ),
        ).value
        if (!physical && defenderAbility == "vesselofruin") attack *= 0.75
        if (physical && defenderAbility == "tabletsofruin" && !ignoresDefenderAbility) attack *= 0.75
        var defence = SharedEffectiveStatEvaluator.evaluate(
            SharedEffectiveStatInput(
                pokemon = input.defender,
                stat = if (physical) "defence" else "specialDefence",
                weather = input.weather,
                terrain = input.terrain,
                ignorePositive = input.critical,
                ignoreStages = attackerAbility == "unaware",
            ),
        ).value
        if (!physical && attackerAbility == "beadsofruin") defence *= 0.75
        if (physical && attackerAbility == "swordofruin") defence *= 0.75
        if (!physical && clean(input.weather) == "sandstorm" && input.defender.types.any { it == "Rock" }) {
            defence *= 1.5
        }
        return SharedDamageStatResult(attack, defence)
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDamageStatInput>(inputJson)),
    )

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

@JsExport
fun evaluateSharedEffectiveStatJson(inputJson: String): String =
    SharedEffectiveStatEvaluator.evaluateJson(inputJson)

@JsExport
fun evaluateSharedDamageStatsJson(inputJson: String): String =
    SharedDamageStatEvaluator.evaluateJson(inputJson)
