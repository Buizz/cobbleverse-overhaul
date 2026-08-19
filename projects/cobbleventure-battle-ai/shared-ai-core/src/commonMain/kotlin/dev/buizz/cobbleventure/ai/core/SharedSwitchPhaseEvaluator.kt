@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedEntryMoveObservation(
    val id: String = "",
    val type: String = "",
    val category: String = "",
    val power: Int = 0,
    val ohko: Boolean = false,
    val dynamicPower: Boolean = false,
)

@Serializable
data class SharedSwitchPhaseInput(
    val outgoingHp: Int = 0,
    val outgoingMaximumHp: Int = 1,
    val outgoingAbility: String = "",
    val outgoingStatus: String = "",
    val outgoingFainted: Boolean = false,
    val incomingHp: Int = 0,
    val incomingMaximumHp: Int = 1,
    val incomingAbility: String = "",
    val incomingSpecies: String = "",
    val incomingItem: String = "",
    val incomingTypes: List<String> = emptyList(),
    val incomingStats: Map<String, Double> = emptyMap(),
    val incomingGrounded: Boolean = true,
    val incomingCanPoison: Boolean = true,
    val incomingTerastallized: Boolean = false,
    val incomingStateFlags: Set<String> = emptySet(),
    val incomingStateValues: Map<String, String> = emptyMap(),
    val stealthRockLayers: Int = 0,
    val spikesLayers: Int = 0,
    val toxicSpikesLayers: Int = 0,
    val stickyWebLayers: Int = 0,
    val healingWish: Boolean = false,
    val lunarDance: Boolean = false,
    val illusionDisguiseAvailable: Boolean = false,
    val opponentAlive: Boolean = false,
    val opponentAbility: String = "",
    val opponentDefence: Double = 0.0,
    val opponentSpecialDefence: Double = 0.0,
    val opponentItem: String = "",
    val opponentMoves: List<SharedEntryMoveObservation> = emptyList(),
    val weather: String = "",
    val terrain: String = "",
)

@Serializable
data class SharedSwitchPhaseOperation(
    val code: String,
    val source: String = "",
    val target: String = "incoming",
    val amount: Int = 0,
    val boosts: Map<String, Double> = emptyMap(),
    val status: String = "",
    val effect: String = "",
    val setState: String = "",
    val details: Map<String, String> = emptyMap(),
    val consumeItem: Boolean = false,
)

@Serializable
data class SharedSwitchPhaseResult(
    val operations: List<SharedSwitchPhaseOperation> = emptyList(),
    val incomingHp: Int = 0,
    val incomingStatus: String = "",
    val clearSideConditions: List<String> = emptyList(),
)

/** 교체 이탈부터 설치물·등장 특성까지의 순서 있는 공통 명령을 만든다. */
object SharedSwitchPhaseEvaluator {
    fun evaluate(input: SharedSwitchPhaseInput): SharedSwitchPhaseResult {
        val operations = mutableListOf<SharedSwitchPhaseOperation>()
        val cleared = mutableListOf<String>()
        val outgoingAbility = cleanSwitchPhase(input.outgoingAbility)
        val ability = cleanSwitchPhase(input.incomingAbility)
        val item = cleanSwitchPhase(input.incomingItem)
        val types = input.incomingTypes.map(::cleanSwitchPhase).toSet()
        var hp = input.incomingHp.coerceIn(0, input.incomingMaximumHp.coerceAtLeast(1))
        var status = ""
        fun add(
            code: String,
            source: String = code,
            target: String = "incoming",
            amount: Int = 0,
            boosts: Map<String, Double> = emptyMap(),
            status: String = "",
            effect: String = "",
            setState: String = "",
            details: Map<String, String> = emptyMap(),
            consumeItem: Boolean = false,
        ) = operations.add(SharedSwitchPhaseOperation(
            code, source, target, amount, boosts, status, effect, setState, details, consumeItem,
        ))

        if (!input.outgoingFainted && input.outgoingHp > 0 && outgoingAbility == "regenerator") {
            add(
                "regenerator",
                target = "outgoing",
                amount = maxOf(1, floor(input.outgoingMaximumHp.coerceAtLeast(1) / 3.0).toInt()),
            )
        }
        if (!input.outgoingFainted && input.outgoingHp > 0 && outgoingAbility == "naturalcure" &&
            cleanSwitchPhase(input.outgoingStatus).isNotEmpty()) {
            add("naturalcure", target = "outgoing", status = input.outgoingStatus)
        }
        add("reset_switch_state", target = "outgoing")

        val wish = when {
            input.healingWish -> "healingwish"
            input.lunarDance -> "lunardance"
            else -> ""
        }
        if (wish.isNotEmpty() && hp > 0) {
            hp = input.incomingMaximumHp.coerceAtLeast(1)
            status = "cure"
            cleared += wish
            add("slot_heal", source = wish, amount = hp, status = "cure")
        }

        val hazardsPresent = input.stealthRockLayers > 0 || input.spikesLayers > 0 ||
            input.toxicSpikesLayers > 0 || input.stickyWebLayers > 0
        if (item == "heavydutyboots" && hazardsPresent) {
            add("heavy_duty_boots", source = "heavydutyboots")
        } else {
            fun hazardDamage(source: String, requested: Double) {
                if (hp <= 0 || requested <= 0.0) return
                if (ability == "magicguard") {
                    add("hazard_blocked", source = "magicguard", effect = source)
                    return
                }
                val damage = minOf(hp, maxOf(1, floor(requested).toInt()))
                hp -= damage
                add("hazard_damage", source = source, amount = damage)
            }
            if (input.stealthRockLayers > 0 && hp > 0) {
                val effectiveness = SharedDamageTypeEvaluator.effectiveness("rock", input.incomingTypes)
                if (effectiveness > 0.0) {
                    hazardDamage("stealthrock", input.incomingMaximumHp / 8.0 * effectiveness)
                }
            }
            if (input.spikesLayers > 0 && input.incomingGrounded && hp > 0) {
                val divisor = when (input.spikesLayers.coerceIn(1, 3)) {
                    1 -> 8.0
                    2 -> 6.0
                    else -> 4.0
                }
                hazardDamage("spikes", input.incomingMaximumHp / divisor)
            }
            if (input.toxicSpikesLayers > 0 && input.incomingGrounded && hp > 0) {
                if ("poison" in types) {
                    cleared += "toxicspikes"
                    add("absorb_toxic_spikes", source = "toxicspikes")
                } else if (input.incomingCanPoison) {
                    status = if (input.toxicSpikesLayers >= 2) "tox" else "psn"
                    add("entry_status", source = "toxicspikes", status = status)
                }
            }
            if (input.stickyWebLayers > 0 && input.incomingGrounded && hp > 0) {
                add("entry_boost", source = "stickyweb", boosts = mapOf("speed" to -1.0))
            }
        }

        if (hp > 0) {
            when {
                ability == "intrepidsword" && "intrepidSwordUsed" !in input.incomingStateFlags ->
                    add("entry_boost", source = ability, boosts = mapOf("attack" to 1.0), setState = "intrepidSwordUsed")
                ability == "dauntlessshield" && "dauntlessShieldUsed" !in input.incomingStateFlags ->
                    add("entry_boost", source = ability, boosts = mapOf("defence" to 1.0), setState = "dauntlessShieldUsed")
            }
            val embodyBoosts = mapOf(
                "embodyaspectcornerstone" to mapOf("defence" to 1.0),
                "embodyaspecthearthflame" to mapOf("attack" to 1.0),
                "embodyaspectteal" to mapOf("speed" to 1.0),
                "embodyaspectwellspring" to mapOf("specialDefence" to 1.0),
            )[ability]
            if (embodyBoosts != null && input.incomingTerastallized) {
                add("entry_boost", source = ability, boosts = embodyBoosts)
            }
            val weather = mapOf(
                "desolateland" to "desolateland", "drizzle" to "raindance", "drought" to "sunnyday",
                "deltastream" to "deltastream", "orichalcumpulse" to "sunnyday",
                "primordialsea" to "primordialsea", "sandstream" to "sandstorm", "snowwarning" to "snow",
            )[ability]
            if (weather != null) add("entry_weather", source = ability, effect = weather)
            val terrain = mapOf(
                "electricsurge" to "electricterrain", "grassysurge" to "grassyterrain",
                "hadronengine" to "electricterrain", "psychicsurge" to "psychicterrain",
            )[ability]
            if (terrain != null) add("entry_terrain", source = ability, effect = terrain)
            if (ability == "illusion" && input.illusionDisguiseAvailable) {
                add("illusion", source = ability, setState = "illusion")
            }
            if (input.opponentAlive && ability == "download") {
                val boosts = if (input.opponentDefence < input.opponentSpecialDefence) {
                    mapOf("attack" to 1.0)
                } else {
                    mapOf("specialAttack" to 1.0)
                }
                add("entry_boost", source = ability, boosts = boosts)
            }
            if (input.opponentAlive && ability == "intimidate") {
                add("entry_boost", source = ability, target = "opponent", boosts = mapOf("attack" to -1.0))
            }
            entryAdapterOperations(input, ability).forEach(operations::add)
        }
        return SharedSwitchPhaseResult(operations, hp, status, cleared.distinct())
    }
}

private val TRACE_BLOCKED_ABILITIES = setOf(
    "asoneglastrier", "asonespectrier", "battlebond", "comatose", "commander", "deltastream",
    "desolateland", "disguise", "gulpmissile", "hadronengine", "illusion", "imposter", "multitype",
    "neutralizinggas", "orichalcumpulse", "powerconstruct", "primordialsea", "protosynthesis",
    "quarkdrive", "receiver", "rkssystem", "schooling", "shieldsdown", "stancechange", "teraformzero",
    "terashift", "trace", "wonderguard", "zenmode",
)

private fun entryAdapterOperations(
    input: SharedSwitchPhaseInput,
    ability: String,
): List<SharedSwitchPhaseOperation> {
    fun operation(
        effect: String,
        details: Map<String, String> = emptyMap(),
        setState: String = "",
        consumeItem: Boolean = false,
    ) = SharedSwitchPhaseOperation(
        code = "entry_adapter",
        source = ability,
        effect = effect,
        setState = setState,
        details = details,
        consumeItem = consumeItem,
    )
    if (ability == "neutralizinggas") return listOf(operation(ability, setState = "neutralizinggas"))
    if (ability == "imposter" && input.opponentAlive) {
        return listOf(operation(ability, setState = "transformed"))
    }
    if (ability == "trace" && input.opponentAlive) {
        val copied = cleanSwitchPhase(input.opponentAbility)
        return if (copied.isNotEmpty() && copied !in TRACE_BLOCKED_ABILITIES) {
            listOf(operation(ability, mapOf("copiedAbility" to copied), "traced:$copied"))
        } else emptyList()
    }
    if (ability == "forewarn" && input.opponentAlive) {
        val move = input.opponentMoves.maxByOrNull(::forewarnPower)
        return if (move != null && forewarnPower(move) > 0) listOf(operation(
            ability,
            mapOf("moveId" to cleanSwitchPhase(move.id), "power" to forewarnPower(move).toString()),
            "forewarn:${cleanSwitchPhase(move.id)}",
        )) else emptyList()
    }
    if (ability == "anticipation" && input.opponentAlive) {
        val threats = input.opponentMoves.filter { move ->
            move.ohko || (cleanSwitchPhase(move.category) != "status" &&
                SharedDamageTypeEvaluator.effectiveness(move.type, input.incomingTypes) > 1.0)
        }.map { cleanSwitchPhase(it.id) }.filter(String::isNotEmpty)
        return if (threats.isNotEmpty()) listOf(operation(
            ability,
            mapOf("threateningMoves" to threats.joinToString(",")),
            "anticipation",
        )) else emptyList()
    }
    if (ability == "frisk" && input.opponentAlive && cleanSwitchPhase(input.opponentItem).isNotEmpty()) {
        val item = cleanSwitchPhase(input.opponentItem)
        return listOf(operation(ability, mapOf("item" to item), "frisked:$item"))
    }
    if (ability == "protosynthesis" || ability == "quarkdrive") {
        val fieldActive = ability == "protosynthesis" && cleanSwitchPhase(input.weather) in
            setOf("sunnyday", "desolateland") ||
            ability == "quarkdrive" && cleanSwitchPhase(input.terrain) == "electricterrain"
        val previousBooster = input.incomingStateValues["paradoxSource"] == "boosterenergy"
        val consumeBooster = !fieldActive && !previousBooster && cleanSwitchPhase(input.incomingItem) == "boosterenergy"
        if (!fieldActive && !previousBooster && !consumeBooster) return emptyList()
        val stat = input.incomingStateValues["paradoxStat"].orEmpty().ifEmpty {
            listOf("attack", "defence", "specialAttack", "specialDefence", "speed")
                .maxByOrNull { input.incomingStats[it] ?: 0.0 }.orEmpty()
        }
        if (stat.isEmpty()) return emptyList()
        val source = if (consumeBooster || previousBooster) "boosterenergy" else "field"
        return listOf(operation(
            "paradox",
            mapOf("stat" to stat, "source" to source),
            "paradox",
            consumeItem = consumeBooster,
        ))
    }
    if (ability == "terashift" && cleanSwitchPhase(input.incomingSpecies).startsWith("terapagos") &&
        !input.incomingTerastallized) {
        return listOf(operation(ability, mapOf("form" to "terapagosterastal"), "form:terapagosterastal"))
    }
    if (ability == "forecast" && !input.incomingTerastallized) {
        val type = when (cleanSwitchPhase(input.weather)) {
            "sunnyday", "desolateland" -> "fire"
            "raindance", "primordialsea" -> "water"
            "hail", "snow" -> "ice"
            else -> cleanSwitchPhase(input.incomingTypes.firstOrNull().orEmpty())
        }
        return listOf(operation(ability, mapOf("type" to type), "forecast"))
    }
    return emptyList()
}

private fun forewarnPower(move: SharedEntryMoveObservation): Int = when {
    move.ohko -> 160
    cleanSwitchPhase(move.id) in setOf("counter", "metalburst", "mirrorcoat") -> 120
    move.dynamicPower -> 80
    else -> move.power.coerceAtLeast(0)
}

@Serializable
data class SharedForcedSwitchInput(
    val activeSlot: Int = 0,
    val teamHp: List<Int> = emptyList(),
    val preferredSlot: Int? = null,
    val randomSelection: Boolean = false,
    val rngState: Long = 0,
)

@Serializable
data class SharedForcedSwitchResult(
    val selectedSlot: Int = -1,
    val eligibleSlots: List<Int> = emptyList(),
    val rngState: Long = 0,
)

object SharedForcedSwitchEvaluator {
    fun evaluate(input: SharedForcedSwitchInput): SharedForcedSwitchResult {
        val eligible = input.teamHp.indices.filter { it != input.activeSlot && input.teamHp[it] > 0 }
        val rng = SharedBattleRng(input.rngState, restoredState = true)
        val preferred = input.preferredSlot?.takeIf { it in eligible }
        val selected = when {
            preferred != null -> preferred
            eligible.isEmpty() -> -1
            input.randomSelection -> eligible[(rng.nextDouble() * eligible.size).toInt().coerceIn(0, eligible.lastIndex)]
            else -> eligible.first()
        }
        return SharedForcedSwitchResult(selected, eligible, rng.snapshot())
    }
}

private fun cleanSwitchPhase(value: String): String =
    value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }

@JsExport
fun evaluateSharedSwitchPhaseJson(inputJson: String): String = codec.encodeToString(
    SharedSwitchPhaseEvaluator.evaluate(codec.decodeFromString<SharedSwitchPhaseInput>(inputJson)),
)

@JsExport
fun evaluateSharedForcedSwitchJson(inputJson: String): String = codec.encodeToString(
    SharedForcedSwitchEvaluator.evaluate(codec.decodeFromString<SharedForcedSwitchInput>(inputJson)),
)
