@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SharedActionOrderCandidate(
    val inputIndex: Int = 0,
    val side: Int = 0,
    val kind: String = "move",
    val moveSlot: Int? = null,
)

@Serializable
data class SharedActionOrderInput(
    val rngState: Long = 0,
    val actions: List<SharedActionOrderCandidate> = emptyList(),
)

@Serializable
data class SharedOrderedAction(
    val inputIndex: Int = 0,
    val priority: Int = 0,
    val speed: Int = 0,
    val tie: Double = 0.0,
    val quickDraw: Boolean = false,
    val quickClaw: Boolean = false,
    val custap: Boolean = false,
    val pursuitTargetSwitch: Boolean = false,
)

@Serializable
data class SharedActionOrderResult(
    val actions: List<SharedOrderedAction> = emptyList(),
    val rngState: Long = 0,
)

object SharedActionOrderEvaluator {
    private val sunnyWeather = setOf("sunnyday", "desolateland")
    private val rainyWeather = setOf("raindance", "primordialsea")

    fun order(state: SharedBattleState, input: SharedActionOrderInput): SharedActionOrderResult {
        val normalized = SharedBattleContract.normalize(state)
        require(input.actions.size == 2) { "Exactly two actions are required" }
        require(input.actions.map { it.side }.toSet() == setOf(0, 1)) { "Actions must cover sides 0 and 1" }
        val rng = SharedBattleRng(input.rngState, restoredState = true)
        val ties = input.actions.map { rng.nextDouble() }
        val prepared = input.actions.mapIndexed { index, action ->
            require(action.side in 0..1) { "Action side is invalid" }
            val pokemon = active(normalized, action.side)
            val move = action.moveSlot?.let { pokemon.moves.getOrNull(it - 1) }
            val quickDraw = action.kind == "move" && move != null &&
                activeAbility(pokemon) == "quickdraw" && rng.nextDouble() < 0.3
            val quickClaw = action.kind == "move" && move != null &&
                clean(pokemon.item) == "quickclaw" && rng.nextDouble() < 0.2
            val custap = action.kind == "move" && move != null &&
                clean(pokemon.item) == "custapberry" && pokemon.hp <= pokemon.stats.hp / 4
            MutableOrder(
                inputIndex = action.inputIndex,
                side = action.side,
                kind = action.kind,
                move = move,
                priority = priority(normalized, action, pokemon, move),
                speed = effectiveSpeed(normalized, action.side, pokemon),
                tie = ties[index],
                quickDraw = quickDraw,
                quickClaw = quickClaw,
                custap = custap,
            )
        }

        for (action in prepared) {
            if (action.kind != "move" || clean(action.move?.id) != "pursuit") continue
            if (prepared.any { it.side != action.side && it.kind == "switch" }) {
                action.priority = 10_001
                action.pursuitTargetSwitch = true
            }
        }

        val trickRoom = normalized.field.pseudoWeather["trickroom"]?.turns?.let { it > 0 } == true
        val ordered = prepared.sortedWith { left, right ->
            compareValues(right.priority, left.priority).takeIf { it != 0 }
                ?: compareValues(right.quickDraw, left.quickDraw).takeIf { it != 0 }
                ?: compareValues(right.quickClaw, left.quickClaw).takeIf { it != 0 }
                ?: compareValues(right.custap, left.custap).takeIf { it != 0 }
                ?: (if (trickRoom) compareValues(left.speed, right.speed) else compareValues(right.speed, left.speed))
                    .takeIf { it != 0 }
                ?: compareValues(left.tie, right.tie)
        }.map { action ->
            SharedOrderedAction(
                inputIndex = action.inputIndex,
                priority = action.priority,
                speed = action.speed,
                tie = action.tie,
                quickDraw = action.quickDraw,
                quickClaw = action.quickClaw,
                custap = action.custap,
                pursuitTargetSwitch = action.pursuitTargetSwitch,
            )
        }
        return SharedActionOrderResult(ordered, rng.snapshot())
    }

    fun orderJson(stateJson: String, inputJson: String): String = codec.encodeToString(
        order(
            codec.decodeFromString<SharedBattleState>(stateJson),
            codec.decodeFromString<SharedActionOrderInput>(inputJson),
        ),
    )

    private fun priority(
        state: SharedBattleState,
        action: SharedActionOrderCandidate,
        pokemon: SharedPokemonState,
        move: SharedMoveState?,
    ): Int {
        if (action.kind == "item") return 10_002
        if (action.kind == "switch") return 10_000
        if (pokemon.dynamaxTurns > 0) return if (move?.category == "Status") 4 else 0
        val moveId = clean(move?.id)
        if (moveId == "grassyglide" && clean(state.field.terrain?.id) == "grassyterrain" && grounded(pokemon)) {
            return 1
        }
        if (moveId == "thunderclap") return 1
        var result = move?.priority ?: 0
        val ability = activeAbility(pokemon)
        if (ability == "prankster" && move?.category == "Status") result += 1
        if (ability == "galewings" && move?.type == "Flying" && pokemon.hp >= pokemon.stats.hp) result += 1
        return result
    }

    private fun effectiveSpeed(state: SharedBattleState, side: Int, pokemon: SharedPokemonState): Int {
        var speed = pokemon.stats.speed * SharedBattleRankProjection.multiplier(
            pokemon.boosts["speed"]?.toDouble() ?: 0.0,
        )
        val ability = activeAbility(pokemon)
        if (pokemon.status == "par") speed *= 0.5
        if (clean(pokemon.item) == "choicescarf") speed *= 1.5
        if (paradoxBoostStat(state, pokemon) == "speed") speed *= 1.5
        val weather = effectiveWeather(state)
        if (
            (ability == "chlorophyll" && weather in sunnyWeather) ||
            (ability == "sandrush" && weather == "sandstorm") ||
            (ability == "slushrush" && weather in setOf("hail", "snow")) ||
            (ability == "swiftswim" && weather in rainyWeather)
        ) speed *= 2.0
        if (ability == "unburden" && pokemon.abilityState.flag("unburdenActivated")) speed *= 2.0
        if ((state.sides[side].conditions["tailwind"]?.turns ?: 0) > 0) speed *= 2.0
        return floor(speed.coerceAtLeast(1.0)).toInt()
    }

    private fun paradoxBoostStat(state: SharedBattleState, pokemon: SharedPokemonState): String {
        val ability = activeAbility(pokemon)
        val fieldActive =
            (ability == "protosynthesis" && effectiveWeather(state) in sunnyWeather) ||
            (ability == "quarkdrive" && clean(state.field.terrain?.id) == "electricterrain")
        if (!fieldActive && pokemon.abilityState.text("paradoxSource") != "boosterenergy") return ""
        pokemon.abilityState.text("paradoxStat").takeIf { it.isNotEmpty() }?.let { return it }
        return listOf("attack", "defence", "specialAttack", "specialDefence", "speed")
            .maxByOrNull { statValue(pokemon.stats, it) } ?: ""
    }

    private fun effectiveWeather(state: SharedBattleState): String {
        val suppressed = state.sides.indices.any { side ->
            val pokemon = active(state, side)
            !pokemon.fainted && activeAbility(pokemon) in setOf("airlock", "cloudnine")
        }
        return if (suppressed) "" else clean(state.field.weather?.id)
    }

    private fun active(state: SharedBattleState, side: Int): SharedPokemonState =
        state.sides[side].team[state.sides[side].active]

    private fun activeAbility(pokemon: SharedPokemonState): String =
        if (
            "gastroacid" in pokemon.volatiles ||
            ("neutralizinggas" in pokemon.volatiles && clean(pokemon.ability) != "neutralizinggas")
        ) "" else clean(pokemon.ability)

    private fun grounded(pokemon: SharedPokemonState): Boolean =
        pokemon.types.none { it == "Flying" } && activeAbility(pokemon) != "levitate" &&
            clean(pokemon.item) != "airballoon"

    private fun SharedEffectState.flag(key: String): Boolean =
        flags[key] ?: attributes[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun SharedEffectState.text(key: String): String =
        attributes[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun statValue(stats: SharedBattleStats, stat: String): Int = when (stat) {
        "attack" -> stats.attack
        "defence" -> stats.defence
        "specialAttack" -> stats.specialAttack
        "specialDefence" -> stats.specialDefence
        "speed" -> stats.speed
        else -> 0
    }

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }

    private data class MutableOrder(
        val inputIndex: Int,
        val side: Int,
        val kind: String,
        val move: SharedMoveState?,
        var priority: Int,
        val speed: Int,
        val tie: Double,
        val quickDraw: Boolean,
        val quickClaw: Boolean,
        val custap: Boolean,
        var pursuitTargetSwitch: Boolean = false,
    )
}

@JsExport
fun orderSharedBattleActionsJson(stateJson: String, inputJson: String): String =
    SharedActionOrderEvaluator.orderJson(stateJson, inputJson)
