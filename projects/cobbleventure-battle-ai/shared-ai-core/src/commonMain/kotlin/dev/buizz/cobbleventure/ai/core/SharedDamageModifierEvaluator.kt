@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedDamageModifierPokemon(
    val id: String = "",
    val familyId: String = "",
    val types: List<String> = emptyList(),
    val ability: String = "",
    val item: String = "",
    val gender: String = "",
    val hp: Int = 0,
    val maximumHp: Int = 1,
    val gastroAcid: Boolean = false,
    val neutralizingGasSuppressed: Boolean = false,
    val flashFireBoosted: Boolean = false,
    val helpingHand: Boolean = false,
    val tarShot: Boolean = false,
    val consecutiveMoveId: String = "",
    val consecutiveMoveCount: Int = 0,
)

@Serializable
data class SharedDamageModifierMove(
    val id: String = "",
    val type: String = "Normal",
    val category: String = "Status",
    val power: Double = 0.0,
    val contact: Boolean = false,
    val punch: Boolean = false,
    val bite: Boolean = false,
    val slicing: Boolean = false,
    val recoil: Boolean = false,
    val hasSecondaries: Boolean = false,
)

@Serializable
data class SharedDamageAbilitySource(
    val ability: String = "",
    val fainted: Boolean = false,
    val gastroAcid: Boolean = false,
    val neutralizingGasSuppressed: Boolean = false,
)

@Serializable
data class SharedDamageModifierInput(
    val attacker: SharedDamageModifierPokemon = SharedDamageModifierPokemon(),
    val defender: SharedDamageModifierPokemon = SharedDamageModifierPokemon(),
    val move: SharedDamageModifierMove = SharedDamageModifierMove(),
    val effectiveness: Double = 1.0,
    val weather: String = "",
    val terrain: String = "",
    val activeAbilitySources: List<SharedDamageAbilitySource> = emptyList(),
    val defenderSideConditions: List<String> = emptyList(),
    val defenderAlreadyActed: Boolean = false,
    val attackerFaintedAllies: Int = 0,
    val critical: Boolean = false,
)

@Serializable
data class SharedDamageModifierResult(
    val itemModifier: Double = 1.0,
    val abilityModifier: Double = 1.0,
    val fieldModifier: Double = 1.0,
)

@Serializable
data class SharedDamageFactorsInput(
    val type: SharedDamageTypeInput = SharedDamageTypeInput(),
    val modifiers: SharedDamageModifierInput = SharedDamageModifierInput(),
)

@Serializable
data class SharedDamageFactorsResult(
    val stab: Double = 1.0,
    val effectiveness: Double = 1.0,
    val absorbedByAbility: String = "",
    val itemModifier: Double = 1.0,
    val abilityModifier: Double = 1.0,
    val fieldModifier: Double = 1.0,
)

object SharedDamageFactorsEvaluator {
    fun evaluate(input: SharedDamageFactorsInput): SharedDamageFactorsResult {
        val type = SharedDamageTypeEvaluator.evaluate(input.type)
        val modifiers = SharedDamageModifierEvaluator.evaluate(
            input.modifiers.copy(effectiveness = type.effectiveness),
        )
        return SharedDamageFactorsResult(
            stab = type.stab,
            effectiveness = type.effectiveness,
            absorbedByAbility = type.absorbedByAbility,
            itemModifier = modifiers.itemModifier,
            abilityModifier = modifiers.abilityModifier,
            fieldModifier = modifiers.fieldModifier,
        )
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDamageFactorsInput>(inputJson)),
    )
}

object SharedDamageModifierEvaluator {
    private val typeBoostingItems = mapOf(
        "blackglasses" to "dark",
        "blackbelt" to "fighting",
        "charcoal" to "fire",
        "charcoalstick" to "fire",
        "dragonfang" to "dragon",
        "magnet" to "electric",
        "miracleseed" to "grass",
        "mysticwater" to "water",
        "nevermeltice" to "ice",
        "pixieplate" to "fairy",
        "sharpbeak" to "flying",
        "spelltag" to "ghost",
    )
    private val resistanceBerries = mapOf(
        "chartiberry" to "rock",
        "colburberry" to "dark",
        "yacheberry" to "ice",
    )
    private val crashMoves = setOf("axekick", "highjumpkick", "jumpkick", "supercellslam")

    fun evaluate(input: SharedDamageModifierInput): SharedDamageModifierResult {
        val attacker = input.attacker
        val defender = input.defender
        val move = input.move
        val attackerAbility = activeAbility(attacker)
        val defenderAbility = activeAbility(defender)
        val attackerItem = clean(attacker.item)
        val defenderItem = clean(defender.item)
        val moveId = clean(move.id)
        val moveType = clean(move.type)
        val category = clean(move.category)
        val weather = clean(input.weather)
        val terrain = clean(input.terrain)
        val ignoresDefenderAbility = attackerAbility in setOf("moldbreaker", "teravolt")
        val effectiveContact = move.contact && !(attackerItem == "punchingglove" && move.punch)

        var itemModifier = when {
            attacker.item == "lifeorb" -> 1.3
            clean(attacker.familyId) == "ogerpon" && attackerItem in setOf(
                "cornerstonemask", "hearthflamemask", "wellspringmask",
            ) -> 1.2
            else -> 1.0
        }
        if (category != "status" && attackerItem == "${moveType}gem") itemModifier *= 1.3
        if (typeBoostingItems[attackerItem] == moveType) itemModifier *= 1.2
        if (attackerItem == "expertbelt" && input.effectiveness > 1.0) itemModifier *= 1.2
        if (attackerItem == "wiseglasses" && category == "special") itemModifier *= 1.1
        if (attackerItem == "punchingglove" && move.punch) itemModifier *= 1.1
        if (attackerItem == "metronome" && clean(attacker.consecutiveMoveId) == moveId) {
            itemModifier *= 1.0 + attacker.consecutiveMoveCount.coerceIn(0, 5) * 0.2
        }

        val activeAbilities = input.activeAbilitySources.asSequence()
            .filterNot { it.fainted }
            .map { activeAbility(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        var abilityModifier = 1.0
        val auraAbility = when (moveType) {
            "dark" -> "darkaura"
            "fairy" -> "fairyaura"
            else -> ""
        }
        if (auraAbility.isNotEmpty() && auraAbility in activeAbilities) {
            abilityModifier *= if ("aurabreak" in activeAbilities) 0.75 else 4.0 / 3.0
        }
        if (attackerAbility == "toughclaws" && effectiveContact) abilityModifier *= 1.3
        if (attackerAbility == "technician" && move.power > 0.0 && move.power <= 60.0) abilityModifier *= 1.5
        if (attackerAbility == "ironfist" && move.punch) abilityModifier *= 1.2
        if (attackerAbility == "strongjaw" && move.bite) abilityModifier *= 1.5
        if (attackerAbility == "sharpness" && move.slicing) abilityModifier *= 1.5
        if (attackerAbility == "dragonsmaw" && moveType == "dragon") abilityModifier *= 1.5
        if (attackerAbility == "transistor" && moveType == "electric") abilityModifier *= 1.3
        if (attackerAbility == "waterbubble" && moveType == "water") abilityModifier *= 2.0
        if (attackerAbility == "sandforce" && weather == "sandstorm" && moveType in setOf("rock", "ground", "steel")) abilityModifier *= 1.3
        if (attackerAbility == "hustle" && category == "physical") abilityModifier *= 1.5
        if (attackerAbility == "reckless" && (move.recoil || moveId in crashMoves)) abilityModifier *= 1.2
        if (attackerAbility == "analytic" && input.defenderAlreadyActed) abilityModifier *= 1.3
        if (attackerAbility == "rivalry" && attacker.gender.isNotEmpty() && defender.gender.isNotEmpty()) {
            abilityModifier *= if (attacker.gender == defender.gender) 1.25 else 0.75
        }
        if (attackerAbility == "sheerforce" && move.hasSecondaries) abilityModifier *= 1.3
        if (attackerAbility == "flashfire" && attacker.flashFireBoosted && moveType == "fire") abilityModifier *= 1.5
        if (attacker.hp <= attacker.maximumHp / 3) {
            if (
                (attackerAbility == "overgrow" && moveType == "grass") ||
                (attackerAbility == "blaze" && moveType == "fire") ||
                (attackerAbility == "torrent" && moveType == "water") ||
                (attackerAbility == "swarm" && moveType == "bug")
            ) abilityModifier *= 1.5
        }
        if (attackerAbility == "tintedlens" && input.effectiveness > 0.0 && input.effectiveness < 1.0) {
            abilityModifier *= 2.0
        }
        if (
            defenderAbility in setOf("filter", "solidrock", "prismarmor") && input.effectiveness > 1.0 &&
            !ignoresDefenderAbility
        ) abilityModifier *= 0.75
        if (attackerAbility == "supremeoverlord") {
            abilityModifier *= 1.0 + input.attackerFaintedAllies.coerceIn(0, 5) * 0.1
        }

        var fieldModifier = defensiveAbilityModifier(
            defender,
            defenderAbility,
            moveType,
            category,
            effectiveContact,
            ignoresDefenderAbility,
        )
        if (weather in setOf("sunnyday", "desolateland")) {
            if (moveType == "fire") fieldModifier *= 1.5
            if (moveType == "water") fieldModifier *= 0.5
        } else if (weather in setOf("raindance", "primordialsea")) {
            if (moveType == "water") fieldModifier *= 1.5
            if (moveType == "fire") fieldModifier *= 0.5
        }
        if (grounded(attacker, attackerAbility)) {
            if (terrain == "electricterrain" && moveType == "electric") fieldModifier *= 1.3
            if (terrain == "grassyterrain" && moveType == "grass") fieldModifier *= 1.3
            if (terrain == "psychicterrain" && moveType == "psychic") fieldModifier *= 1.3
        }
        if (terrain == "mistyterrain" && grounded(defender, defenderAbility) && moveType == "dragon") {
            fieldModifier *= 0.5
        }
        if (attacker.helpingHand && move.power > 0.0) fieldModifier *= 1.5
        if (defender.tarShot && moveType == "fire") fieldModifier *= 2.0
        val sideConditions = input.defenderSideConditions.map(::clean).toSet()
        if (!input.critical && attackerAbility != "infiltrator") {
            if ("auroraveil" in sideConditions) fieldModifier *= 0.5
            else if (category == "physical" && "reflect" in sideConditions) fieldModifier *= 0.5
            else if (category == "special" && "lightscreen" in sideConditions) fieldModifier *= 0.5
        }
        if (resistanceBerries[defenderItem] == moveType && input.effectiveness > 1.0) fieldModifier *= 0.5
        return SharedDamageModifierResult(itemModifier, abilityModifier, fieldModifier)
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDamageModifierInput>(inputJson)),
    )

    private fun defensiveAbilityModifier(
        defender: SharedDamageModifierPokemon,
        ability: String,
        moveType: String,
        category: String,
        effectiveContact: Boolean,
        ignored: Boolean,
    ): Double {
        if (ignored) return 1.0
        var result = 1.0
        if (ability in setOf("multiscale", "shadowshield") && defender.hp >= defender.maximumHp) result *= 0.5
        if (ability == "thickfat" && moveType in setOf("fire", "ice")) result *= 0.5
        if (ability == "purifyingsalt" && moveType == "ghost") result *= 0.5
        if (ability == "heatproof" && moveType == "fire") result *= 0.5
        if (ability == "waterbubble" && moveType == "fire") result *= 0.5
        if (ability == "furcoat" && category == "physical") result *= 0.5
        if (ability == "fluffy") {
            if (effectiveContact) result *= 0.5
            if (moveType == "fire") result *= 2.0
        }
        if (ability == "dryskin" && moveType == "fire") result *= 1.25
        return result
    }

    private fun grounded(pokemon: SharedDamageModifierPokemon, ability: String): Boolean =
        pokemon.types.none { clean(it) == "flying" } && ability != "levitate" && clean(pokemon.item) != "airballoon"

    private fun activeAbility(pokemon: SharedDamageModifierPokemon): String =
        if (pokemon.gastroAcid || pokemon.neutralizingGasSuppressed) "" else clean(pokemon.ability)

    private fun activeAbility(source: SharedDamageAbilitySource): String =
        if (source.gastroAcid || source.neutralizingGasSuppressed) "" else clean(source.ability)

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

@JsExport
fun evaluateSharedDamageModifiersJson(inputJson: String): String =
    SharedDamageModifierEvaluator.evaluateJson(inputJson)

@JsExport
fun evaluateSharedDamageFactorsJson(inputJson: String): String =
    SharedDamageFactorsEvaluator.evaluateJson(inputJson)
