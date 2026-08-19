@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SharedBuiltAction(
    val side: Int = 0,
    val kind: String = "move",
    val moveSlot: Int? = null,
    val switchSlot: Int? = null,
    val item: String? = null,
    val itemTargetSlot: Int? = null,
    val selfSwitchSlot: Int? = null,
    val locked: Boolean = false,
    val lockSource: String = "",
    val noPpCost: Boolean = false,
    val chargingRelease: Boolean = false,
    val gimmick: String = "",
    val teraType: String = "",
)

@Serializable
data class SharedActionStateCleanup(
    val side: Int = 0,
    val clearEncore: Boolean = false,
    val clearLockedMove: Boolean = false,
    val clearChoiceLock: Boolean = false,
    val clearChargingMove: Boolean = false,
)

@Serializable
data class SharedActionBuildResult(
    val actions: List<SharedBuiltAction> = emptyList(),
    val cleanups: List<SharedActionStateCleanup> = emptyList(),
)

object SharedActionBuildEvaluator {
    private val choiceItems = setOf("choiceband", "choicescarf", "choicespecs")
    private val trappingVolatiles = setOf(
        "block", "fairylock", "ingrain", "jawlock", "meanlook", "spiderweb",
        "thousandwaves", "trapped",
    )

    fun build(state: SharedBattleState, turn: SharedTurnCommands): SharedActionBuildResult {
        val normalized = SharedBattleContract.normalize(state)
        val commands = SharedBattleContract.normalizeCommands(normalized, turn).commands
        val cleanups = ArrayList<SharedActionStateCleanup>()
        val actions = commands.map { command ->
            val side = command.side
            val pokemon = active(normalized, side)
            val cleanup = MutableCleanup(side)
            val charging = chargingSelection(pokemon, cleanup)
            val action = if (charging != null) {
                SharedBuiltAction(
                    side = side,
                    moveSlot = charging,
                    locked = true,
                    lockSource = "charging",
                    noPpCost = true,
                    chargingRelease = true,
                )
            } else {
                val locked = lockedSelection(pokemon, cleanup)
                when (command.kind) {
                    "item" -> {
                        if (locked?.preventsSwitch == true) {
                            error("Side ${side + 1} cannot use an item while locked into ${moveName(pokemon, locked.slot)}")
                        }
                        SharedBuiltAction(
                            side = side,
                            kind = "item",
                            item = clean(command.item),
                            itemTargetSlot = command.itemTargetSlot,
                        )
                    }
                    "switch" -> {
                        if (locked?.preventsSwitch == true) {
                            error("Side ${side + 1} cannot switch while locked into ${moveName(pokemon, locked.slot)}")
                        }
                        val switchSlot = command.switchSlot!!
                        val targetIndex = switchSlot - 1
                        val target = normalized.sides[side].team.getOrNull(targetIndex)
                        if (target == null || target.fainted || targetIndex == normalized.sides[side].active) {
                            error("Side ${side + 1} cannot switch to slot $switchSlot")
                        }
                        if (isTrapped(normalized, side, pokemon)) {
                            error("Side ${side + 1} cannot switch while trapped")
                        }
                        SharedBuiltAction(side = side, kind = "switch", switchSlot = switchSlot)
                    }
                    else -> {
                        val selected = locked ?: Selection(
                            usableMoveRespectingDisable(pokemon, command.moveSlot),
                        )
                        SharedBuiltAction(
                            side = side,
                            moveSlot = selected.slot,
                            selfSwitchSlot = command.selfSwitchSlot,
                            locked = locked != null,
                            lockSource = locked?.source.orEmpty(),
                            noPpCost = locked?.noPpCost == true,
                            gimmick = if (locked == null) command.gimmick else "",
                            teraType = command.teraType,
                        )
                    }
                }
            }
            cleanups += cleanup.freeze()
            action
        }
        return SharedActionBuildResult(actions, cleanups)
    }

    fun buildJson(stateJson: String, commandsJson: String): String = codec.encodeToString(
        build(
            codec.decodeFromString<SharedBattleState>(stateJson),
            codec.decodeFromString<SharedTurnCommands>(commandsJson),
        ),
    )

    private fun chargingSelection(pokemon: SharedPokemonState, cleanup: MutableCleanup): Int? {
        val id = clean(pokemon.chargingMove?.id)
        if (id.isEmpty()) return null
        val index = pokemon.moves.indexOfFirst { clean(it.id) == id }
        if (index < 0) {
            cleanup.clearChargingMove = true
            return null
        }
        return index + 1
    }

    private fun lockedSelection(pokemon: SharedPokemonState, cleanup: MutableCleanup): Selection? {
        val encoreMove = clean(pokemon.volatiles["encore"]?.text("moveId"))
        if (encoreMove.isNotEmpty()) {
            val index = pokemon.moves.indexOfFirst { clean(it.id) == encoreMove && it.pp > 0 }
            if (index >= 0) return Selection(index + 1, "encore")
            cleanup.clearEncore = true
        }

        val lockedId = clean(pokemon.lockedMove?.id)
        if (lockedId.isNotEmpty()) {
            val index = pokemon.moves.indexOfFirst { clean(it.id) == lockedId && it.pp > 0 }
            if (index < 0) {
                cleanup.clearLockedMove = true
            } else {
                return Selection(
                    slot = index + 1,
                    source = pokemon.lockedMove?.text("kind").orEmpty().ifEmpty { "move" },
                    preventsSwitch = true,
                    noPpCost = true,
                )
            }
        }

        if (hasChoiceLock(pokemon)) {
            val choiceId = clean(pokemon.choiceLock?.id)
            if (choiceId.isNotEmpty()) {
                val index = pokemon.moves.indexOfFirst { clean(it.id) == choiceId && it.pp > 0 }
                if (index >= 0) {
                    return Selection(
                        index + 1,
                        if (activeAbility(pokemon) == "gorillatactics") "gorillatactics" else "choice",
                    )
                }
            }
        } else if (pokemon.choiceLock != null) {
            cleanup.clearChoiceLock = true
        }
        return null
    }

    private fun usableMoveRespectingDisable(pokemon: SharedPokemonState, requestedSlot: Int?): Int? {
        val requestedIndex = requestedSlot?.minus(1)
        var selected = requestedIndex?.takeIf { it in pokemon.moves.indices && pokemon.moves[it].pp > 0 }
            ?: pokemon.moves.indexOfFirst { it.pp > 0 }.takeIf { it >= 0 }
        if (selected != null && !disabled(pokemon, pokemon.moves[selected])) return selected + 1
        val fallback = pokemon.moves.indexOfFirst { it.pp > 0 && !disabled(pokemon, it) }
        if (fallback >= 0) selected = fallback
        return selected?.plus(1)
    }

    private fun disabled(pokemon: SharedPokemonState, move: SharedMoveState): Boolean {
        val disabledMove = clean(pokemon.volatiles["disable"]?.text("moveId"))
        return disabledMove.isNotEmpty() && disabledMove == clean(move.id)
    }

    private fun hasChoiceLock(pokemon: SharedPokemonState): Boolean =
        clean(pokemon.item) in choiceItems ||
            (activeAbility(pokemon) == "gorillatactics" && pokemon.dynamaxTurns <= 0)

    private fun isTrapped(state: SharedBattleState, side: Int, pokemon: SharedPokemonState): Boolean {
        if (pokemon.volatiles.keys.any { clean(it) in trappingVolatiles }) return true
        val opponent = active(state, 1 - side)
        val bypassesAbilityTrap = pokemon.types.contains("Ghost") || clean(pokemon.item) == "shedshell"
        if (opponent.fainted || bypassesAbilityTrap) return false
        val opponentAbility = activeAbility(opponent)
        if (opponentAbility == "shadowtag" && activeAbility(pokemon) != "shadowtag") return true
        if (opponentAbility == "arenatrap" && grounded(pokemon)) return true
        return opponentAbility == "magnetpull" && pokemon.types.contains("Steel")
    }

    private fun grounded(pokemon: SharedPokemonState): Boolean =
        !pokemon.types.contains("Flying") && activeAbility(pokemon) != "levitate" &&
            clean(pokemon.item) != "airballoon"

    private fun active(state: SharedBattleState, side: Int): SharedPokemonState =
        state.sides[side].team[state.sides[side].active]

    private fun activeAbility(pokemon: SharedPokemonState): String =
        if (
            "gastroacid" in pokemon.volatiles ||
            ("neutralizinggas" in pokemon.volatiles && clean(pokemon.ability) != "neutralizinggas")
        ) "" else clean(pokemon.ability)

    private fun moveName(pokemon: SharedPokemonState, slot: Int?): String =
        slot?.let { pokemon.moves.getOrNull(it - 1)?.name }.orEmpty()

    private fun SharedEffectState.text(key: String): String =
        attributes[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }

    private data class Selection(
        val slot: Int?,
        val source: String = "",
        val preventsSwitch: Boolean = false,
        val noPpCost: Boolean = false,
    )

    private class MutableCleanup(val side: Int) {
        var clearEncore = false
        var clearLockedMove = false
        var clearChoiceLock = false
        var clearChargingMove = false

        fun freeze() = SharedActionStateCleanup(
            side, clearEncore, clearLockedMove, clearChoiceLock, clearChargingMove,
        )
    }
}

@JsExport
fun buildSharedBattleActionsJson(stateJson: String, commandsJson: String): String =
    SharedActionBuildEvaluator.buildJson(stateJson, commandsJson)
