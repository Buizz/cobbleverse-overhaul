@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedSearchPressure(
    val yawn: Boolean = false,
    val yawnTurns: Int = 0,
    val saltCure: Boolean = false,
    val toxicCounter: Int = 0,
    val sleepTurns: Int = 0,
)

@Serializable
data class SharedSearchTimedEffect(
    val id: String = "",
    val turns: Int = 0,
    val persistent: Boolean = false,
)

@Serializable
data class SharedSearchFieldState(
    val weather: SharedSearchTimedEffect? = null,
    val terrain: SharedSearchTimedEffect? = null,
    val pseudoWeather: Map<String, SharedSearchTimedEffect> = emptyMap(),
)

@Serializable
data class SharedSearchFieldMoveEffect(
    val weather: String = "",
    val terrain: String = "",
    val pseudoWeather: String = "",
    val sideCondition: String = "",
    val fieldDuration: Int = 0,
    val sideConditionDuration: Int = 0,
)

/** 변신/폼 변경 뒤에도 양 플랫폼의 원본 기술 객체를 재사용하는 탐색 전투 프로필. */
@Serializable
data class SharedSearchCombatProfile(
    val id: String = "",
    val ability: String = "",
    val types: List<String> = emptyList(),
    val stats: SharedBattleStats = SharedBattleStats(),
    val moveSourceSide: Int = -1,
    val moveSourceSlot: Int = -1,
)

/** 전장 지속 상태를 만드는 기술을 플랫폼 독립 ID로 해석한다. */
object SharedSearchFieldMoveCatalog {
    fun effect(moveId: String, sourceItem: String = ""): SharedSearchFieldMoveEffect {
        val id = cleanProjection(moveId)
        val item = cleanProjection(sourceItem)
        val weather = mapOf(
            "sunnyday" to "sunnyday", "raindance" to "raindance", "sandstorm" to "sandstorm",
            "hail" to "hail", "snowscape" to "snow",
        )[id].orEmpty()
        val terrain = mapOf(
            "electricterrain" to "electricterrain", "grassyterrain" to "grassyterrain",
            "mistyterrain" to "mistyterrain", "psychicterrain" to "psychicterrain",
        )[id].orEmpty()
        val pseudo = id.takeIf { it in setOf("trickroom", "magicroom", "wonderroom", "gravity") }.orEmpty()
        val side = id.takeIf {
            it in setOf(
                "auroraveil", "craftyshield", "lightscreen", "luckychant", "matblock", "mist",
                "quickguard", "reflect", "safeguard", "tailwind", "wideguard",
            )
        }.orEmpty()
        val fieldDuration = when {
            terrain.isNotEmpty() && item == "terrainextender" -> 8
            weather == "sunnyday" && item == "heatrock" -> 8
            weather == "raindance" && item == "damprock" -> 8
            weather == "sandstorm" && item == "smoothrock" -> 8
            weather in setOf("hail", "snow") && item == "icyrock" -> 8
            weather.isNotEmpty() || terrain.isNotEmpty() || pseudo.isNotEmpty() -> 5
            else -> 0
        }
        val sideDuration = when {
            side == "tailwind" -> 4
            side in setOf("craftyshield", "matblock", "quickguard", "wideguard") -> 1
            side in setOf("auroraveil", "lightscreen", "reflect") && item == "lightclay" -> 8
            side.isNotEmpty() -> 5
            else -> 0
        }
        return SharedSearchFieldMoveEffect(weather, terrain, pseudo, side, fieldDuration, sideDuration)
    }
}

@Serializable
data class SharedSearchProjectionState(
    val turn: Int = 0,
    val active: List<Int> = listOf(0, 0),
    val hp: List<List<Int>> = emptyList(),
    val maxHp: List<List<Int>> = emptyList(),
    val gimmicksRemaining: List<Boolean> = listOf(false, false),
    val itemCounts: List<List<Int>> = emptyList(),
    val hazards: List<List<Int>> = emptyList(),
    val pressures: List<List<SharedSearchPressure>> = emptyList(),
    val ranks: List<List<List<Int>>> = emptyList(),
    val heldItems: List<List<String>> = emptyList(),
    val abilityStates: List<List<Set<String>>> = emptyList(),
    val field: SharedSearchFieldState = SharedSearchFieldState(),
    val sideConditions: List<Map<String, SharedSearchTimedEffect>> = listOf(emptyMap(), emptyMap()),
    val baseProfiles: List<List<SharedSearchCombatProfile>> = emptyList(),
    val profiles: List<List<SharedSearchCombatProfile>> = emptyList(),
    val formProfiles: List<List<Map<String, SharedSearchCombatProfile>>> = emptyList(),
)

@Serializable
data class SharedProjectedSearchAction(
    val action: SearchAction,
    val side: Int = 0,
    val switchSlot: Int = -1,
    val itemIndex: Int = -1,
    val itemTargetSlot: Int = -1,
    val healing: Int = 0,
    val damage: Double = 0.0,
    val successProbability: Double = 1.0,
    val hazardIndex: Int = -1,
    val pressure: String = "",
    val selfBoosts: Map<String, Double> = emptyMap(),
    val batonPassTarget: Int = -1,
    val consumesGimmick: Boolean = false,
    val weather: String = "",
    val terrain: String = "",
    val pseudoWeather: String = "",
    val sideCondition: String = "",
    val fieldDuration: Int = 0,
    val sideConditionDuration: Int = 0,
    val hitReactions: List<SharedHitReaction> = emptyList(),
    val postHitInstructions: List<SharedPostHitInstruction> = emptyList(),
    val switchPhase: SharedSwitchPhaseResult? = null,
)

object SharedSearchProjectionRuntime {
    fun legalCandidates(
        state: SharedSearchProjectionState,
        sideIndex: Int,
        candidates: List<SharedProjectedSearchAction>,
    ): List<SharedProjectedSearchAction> {
        val active = state.active.getOrElse(sideIndex) { 0 }
        val hp = state.hp.getOrNull(sideIndex).orEmpty()
        val requiresSwitch = hp.getOrElse(active) { 0 } <= 0
        return candidates.asSequence()
            .filter { it.side == sideIndex }
            .filter { candidate ->
                when (candidate.action.kind.lowercase()) {
                    "switch" -> candidate.switchSlot in hp.indices &&
                        candidate.switchSlot != active && hp[candidate.switchSlot] > 0
                    "item" -> !requiresSwitch && candidate.itemTargetSlot in hp.indices &&
                        hp[candidate.itemTargetSlot] > 0 && itemAvailable(state, sideIndex, candidate.itemIndex)
                    else -> !requiresSwitch
                }
            }
            .distinctBy { it.action.id }
            .sortedByDescending { it.action.score }
            .toList()
    }

    fun transition(
        state: SharedSearchProjectionState,
        sideZeroAction: SharedProjectedSearchAction,
        sideOneAction: SharedProjectedSearchAction,
    ): SharedSearchProjectionState {
        val active = state.active.toMutableList()
        val hp = state.hp.map { it.toMutableList() }.toMutableList()
        val maxHp = state.maxHp.map { it.toMutableList() }.toMutableList()
        val gimmicks = state.gimmicksRemaining.toMutableList()
        val itemCounts = state.itemCounts.map { it.toMutableList() }.toMutableList()
        val hazards = state.hazards.map { it.toMutableList() }.toMutableList()
        val pressures = state.pressures.map { it.toMutableList() }.toMutableList()
        val ranks = state.ranks.map { side -> side.map { it.toMutableList() }.toMutableList() }.toMutableList()
        val heldItems = state.heldItems.map { it.toMutableList() }.toMutableList()
        val abilityStates = state.abilityStates.map { side -> side.map { it.toMutableSet() }.toMutableList() }.toMutableList()
        val baseProfiles = state.baseProfiles.map { it.toMutableList() }.toMutableList()
        val profiles = state.profiles.map { it.toMutableList() }.toMutableList()
        val formProfiles = state.formProfiles.map { side -> side.map { it.toMutableMap() }.toMutableList() }.toMutableList()
        var field = tickField(state.field)
        val sideConditions = state.sideConditions.map { tickEffects(it).toMutableMap() }.toMutableList()

        ensureSides(active, hp, maxHp, gimmicks, itemCounts, hazards, pressures, ranks, heldItems, abilityStates, sideConditions)
        ensureProfiles(hp, baseProfiles, profiles, formProfiles)
        for (side in 0..1) {
            val slot = active[side].coerceIn(0, maxOf(0, hp[side].lastIndex))
            val pressure = pressures[side].getOrElse(slot) { SharedSearchPressure() }
            pressures[side][slot] = when {
                pressure.yawn && pressure.yawnTurns <= 1 -> pressure.copy(
                    yawn = false,
                    yawnTurns = 0,
                    sleepTurns = maxOf(2, pressure.sleepTurns),
                )
                pressure.yawn -> pressure.copy(yawnTurns = pressure.yawnTurns - 1)
                pressure.sleepTurns > 0 -> pressure.copy(sleepTurns = pressure.sleepTurns - 1)
                else -> pressure
            }
        }

        val actions = listOf(sideZeroAction, sideOneAction)
        actions.forEachIndexed { side, action ->
            if (action.action.kind.equals("switch", true) && action.switchSlot in hp[side].indices && hp[side][action.switchSlot] > 0) {
                val outgoing = active[side]
                action.switchPhase?.operations.orEmpty().forEach { operation ->
                    when (operation.code) {
                        "regenerator" -> hp[side][outgoing] = minOf(
                            maxHp[side].getOrElse(outgoing) { hp[side][outgoing] },
                            hp[side][outgoing] + operation.amount,
                        )
                        "naturalcure" -> if (outgoing in pressures[side].indices) {
                            pressures[side][outgoing] = pressures[side][outgoing].copy(toxicCounter = 0, sleepTurns = 0)
                        }
                    }
                }
                ranks[side].getOrNull(outgoing)?.fill(0)
                abilityStates[side].getOrNull(outgoing)?.removeAll(SWITCH_RESET_ABILITY_STATES)
                baseProfiles.getOrNull(side)?.getOrNull(outgoing)?.let { base ->
                    if (outgoing in profiles.getOrNull(side).orEmpty().indices) profiles[side][outgoing] = base
                }
                active[side] = action.switchSlot
                val incoming = pressures[side][action.switchSlot]
                pressures[side][action.switchSlot] = SharedSearchPressure(toxicCounter = if (incoming.toxicCounter > 0) 1 else 0)
            }
        }

        actions.forEachIndexed { side, action ->
            if (action.action.kind.equals("switch", true) && action.switchSlot == active[side]) {
                action.switchPhase?.operations.orEmpty().forEach { operation ->
                    when (operation.code) {
                        "slot_heal" -> {
                            hp[side][action.switchSlot] = maxHp[side].getOrElse(action.switchSlot) { hp[side][action.switchSlot] }
                            pressures[side][action.switchSlot] = SharedSearchPressure()
                        }
                        "hazard_damage" -> hp[side][action.switchSlot] =
                            maxOf(0, hp[side][action.switchSlot] - operation.amount)
                        "entry_status" -> if (action.switchSlot in pressures[side].indices) {
                            val value = pressures[side][action.switchSlot]
                            pressures[side][action.switchSlot] = when (operation.status) {
                                "tox", "psn" -> value.copy(toxicCounter = 1)
                                "slp" -> value.copy(sleepTurns = 2)
                                else -> value
                            }
                        }
                        "entry_boost" -> {
                            val targetSide = if (operation.target == "opponent") 1 - side else side
                            val targetSlot = active[targetSide]
                            if (targetSlot in ranks[targetSide].indices) applyBoosts(ranks[targetSide][targetSlot], operation.boosts)
                            if (operation.target != "opponent" && operation.setState.isNotEmpty() &&
                                action.switchSlot in abilityStates[side].indices) {
                                abilityStates[side][action.switchSlot].add(operation.setState)
                            }
                        }
                        "entry_weather" -> field = field.copy(weather = timed(operation.effect, 5))
                        "entry_terrain" -> field = field.copy(terrain = timed(operation.effect, 5))
                        "illusion" -> if (action.switchSlot in abilityStates[side].indices) {
                            abilityStates[side][action.switchSlot].add("illusion")
                        }
                        "entry_adapter" -> if (action.switchSlot in abilityStates[side].indices) {
                            val marker = operation.setState.ifEmpty { "entry:${operation.effect}" }
                            abilityStates[side][action.switchSlot].add(marker)
                            if (operation.effect == "paradox") {
                                abilityStates[side][action.switchSlot].add(
                                    "paradox:${operation.details["stat"].orEmpty()}:${operation.details["source"].orEmpty()}",
                                )
                            }
                            if (operation.consumeItem) clearHeldItem(heldItems, side, action.switchSlot)
                            if (operation.effect == "imposter") {
                                val opponentSide = 1 - side
                                val opponentSlot = active[opponentSide]
                                if (opponentSlot in ranks[opponentSide].indices) {
                                    ranks[side][action.switchSlot] = ranks[opponentSide][opponentSlot].toMutableList()
                                }
                                profiles.getOrNull(opponentSide)?.getOrNull(opponentSlot)?.let { copied ->
                                    if (action.switchSlot in profiles.getOrNull(side).orEmpty().indices) {
                                        profiles[side][action.switchSlot] = copied
                                    }
                                }
                            }
                            if (operation.effect == "trace") {
                                val copiedAbility = operation.details["copiedAbility"].orEmpty()
                                profiles.getOrNull(side)?.getOrNull(action.switchSlot)?.let { current ->
                                    if (copiedAbility.isNotEmpty()) {
                                        profiles[side][action.switchSlot] = current.copy(ability = copiedAbility)
                                    }
                                }
                            }
                            if (operation.effect == "forecast") {
                                val type = operation.details["type"].orEmpty()
                                profiles.getOrNull(side)?.getOrNull(action.switchSlot)?.let { current ->
                                    if (type.isNotEmpty()) profiles[side][action.switchSlot] = current.copy(types = listOf(type))
                                }
                            }
                            if (operation.effect == "terashift") {
                                val form = cleanProjection(operation.details["form"].orEmpty())
                                val formProfile = formProfiles.getOrNull(side)?.getOrNull(action.switchSlot)?.get(form)
                                profiles.getOrNull(side)?.getOrNull(action.switchSlot)?.let { current ->
                                    profiles[side][action.switchSlot] = formProfile
                                        ?: current.copy(id = form, ability = "terashell")
                                }
                            }
                        }
                    }
                }
                if ("toxicspikes" in action.switchPhase?.clearSideConditions.orEmpty() && hazards[side].size > 2) {
                    hazards[side][2] = 0
                }
            }
        }

        actions.forEachIndexed { side, projected ->
            val kind = projected.action.kind.lowercase()
            if (kind == "switch") return@forEachIndexed
            if (kind == "item") {
                if (projected.itemTargetSlot in hp[side].indices) {
                    val maximum = maxHp[side].getOrElse(projected.itemTargetSlot) { Int.MAX_VALUE }
                    hp[side][projected.itemTargetSlot] = minOf(
                        maximum,
                        hp[side][projected.itemTargetSlot] + projected.healing.coerceAtLeast(0),
                    )
                }
                if (projected.itemIndex in itemCounts[side].indices) {
                    itemCounts[side][projected.itemIndex] = maxOf(0, itemCounts[side][projected.itemIndex] - 1)
                }
                return@forEachIndexed
            }
            val targetSide = 1 - side
            val attacker = active[side]
            val defender = active[targetSide]
            val effectiveDamage = projected.damage.coerceAtLeast(0.0) *
                projected.successProbability.coerceIn(0.0, 1.0)
            if (defender in hp[targetSide].indices) {
                hp[targetSide][defender] = maxOf(0, hp[targetSide][defender] - effectiveDamage.roundToInt())
            }
            if (effectiveDamage > 0.0) {
                projected.hitReactions.forEach { reaction ->
                    when (reaction.target) {
                        "attacker" -> {
                            if (reaction.damageFraction > 0.0 && attacker in hp[side].indices) {
                                val reactionDamage = (maxHp[side].getOrElse(attacker) { 0 } * reaction.damageFraction)
                                    .roundToInt().coerceAtLeast(1)
                                hp[side][attacker] = maxOf(0, hp[side][attacker] - reactionDamage)
                            }
                            if (attacker in ranks[side].indices) applyBoosts(ranks[side][attacker], reaction.boosts)
                        }
                        "defender" -> if (defender in ranks[targetSide].indices) {
                            applyBoosts(ranks[targetSide][defender], reaction.boosts)
                        }
                    }
                    if (reaction.sideCondition == "toxicspikes" && hazards[side].size > 2) {
                        hazards[side][2] = minOf(2, hazards[side][2] + 1)
                    }
                    when (reaction.itemAction) {
                        "consume_attacker_item" -> clearHeldItem(heldItems, side, attacker)
                        "consume_defender_item" -> clearHeldItem(heldItems, targetSide, defender)
                        "steal_attacker_item" -> transferHeldItem(
                            heldItems, side, attacker, targetSide, defender,
                        )
                        "steal_defender_item" -> transferHeldItem(
                            heldItems, targetSide, defender, side, attacker,
                        )
                    }
                    if (reaction.consumeItem) {
                        if (reaction.target == "attacker") clearHeldItem(heldItems, side, attacker)
                        if (reaction.target == "defender") clearHeldItem(heldItems, targetSide, defender)
                    }
                    if (reaction.clearState.isNotEmpty() && defender in abilityStates[targetSide].indices) {
                        abilityStates[targetSide][defender].remove(reaction.clearState)
                    }
                }
                projected.postHitInstructions.forEach { instruction ->
                    when (instruction.kind) {
                        "remove_attacker_item", "consume_attacker_berry" ->
                            clearHeldItem(heldItems, side, attacker)
                        "remove_defender_item", "remove_consumable_defender_item" ->
                            clearHeldItem(heldItems, targetSide, defender)
                        "steal_defender_item" -> transferHeldItem(
                            heldItems, targetSide, defender, side, attacker,
                        )
                        "gulp_missile" -> if (attacker in abilityStates[side].indices) {
                            abilityStates[side][attacker].remove("gulping")
                            abilityStates[side][attacker].remove("gorging")
                            if (instruction.effect.isNotEmpty()) abilityStates[side][attacker].add(instruction.effect)
                        }
                    }
                }
            }
            if (projected.hazardIndex in hazards[targetSide].indices) {
                val maximum = if (projected.hazardIndex == 1) 3 else if (projected.hazardIndex == 2) 2 else 1
                hazards[targetSide][projected.hazardIndex] = minOf(maximum, hazards[targetSide][projected.hazardIndex] + 1)
            }
            if (defender in pressures[targetSide].indices) {
                val current = pressures[targetSide][defender]
                pressures[targetSide][defender] = when (projected.pressure.lowercase()) {
                    "yawn" -> current.copy(yawn = true, yawnTurns = 2)
                    "saltcure" -> current.copy(saltCure = true)
                    "toxic" -> current.copy(toxicCounter = 1)
                    else -> current
                }
            }
            if (attacker in ranks[side].indices) applyBoosts(ranks[side][attacker], projected.selfBoosts)
            if (projected.batonPassTarget in hp[side].indices && hp[side][projected.batonPassTarget] > 0) {
                ranks[side][projected.batonPassTarget] = ranks[side][attacker].toMutableList()
                ranks[side][attacker].fill(0)
                active[side] = projected.batonPassTarget
                pressures[side][attacker] = SharedSearchPressure()
            }
            if (projected.consumesGimmick) gimmicks[side] = false
            if (projected.weather.isNotBlank()) {
                field = field.copy(weather = timed(projected.weather, projected.fieldDuration))
            }
            if (projected.terrain.isNotBlank()) {
                field = field.copy(terrain = timed(projected.terrain, projected.fieldDuration))
            }
            if (projected.pseudoWeather.isNotBlank()) {
                val id = cleanProjection(projected.pseudoWeather)
                field = field.copy(pseudoWeather = field.pseudoWeather + (id to timed(id, projected.fieldDuration)))
            }
            if (projected.sideCondition.isNotBlank()) {
                val id = cleanProjection(projected.sideCondition)
                sideConditions[side][id] = timed(id, projected.sideConditionDuration)
            }
        }

        for (side in 0..1) {
            val current = active[side]
            if (hp[side].getOrElse(current) { 0 } > 0) continue
            ranks[side].getOrNull(current)?.fill(0)
            active[side] = hp[side].indices.firstOrNull { hp[side][it] > 0 } ?: current
        }
        return SharedSearchProjectionState(
            turn = state.turn + 1,
            active = active,
            hp = hp,
            maxHp = maxHp,
            gimmicksRemaining = gimmicks,
            itemCounts = itemCounts,
            hazards = hazards,
            pressures = pressures,
            ranks = ranks,
            heldItems = heldItems,
            abilityStates = abilityStates.map { side -> side.map { it.toSet() } },
            field = field,
            sideConditions = sideConditions,
            baseProfiles = baseProfiles.map { it.toList() },
            profiles = profiles.map { it.toList() },
            formProfiles = formProfiles.map { side -> side.map { it.toMap() } },
        )
    }

    private fun ensureProfiles(
        hp: List<List<Int>>,
        baseProfiles: MutableList<MutableList<SharedSearchCombatProfile>>,
        profiles: MutableList<MutableList<SharedSearchCombatProfile>>,
        formProfiles: MutableList<MutableList<MutableMap<String, SharedSearchCombatProfile>>>,
    ) {
        while (baseProfiles.size < hp.size) baseProfiles.add(mutableListOf())
        while (profiles.size < hp.size) profiles.add(mutableListOf())
        while (formProfiles.size < hp.size) formProfiles.add(mutableListOf())
        for (side in hp.indices) {
            while (baseProfiles[side].size < hp[side].size) baseProfiles[side].add(SharedSearchCombatProfile())
            while (profiles[side].size < hp[side].size) {
                profiles[side].add(baseProfiles[side].getOrElse(profiles[side].size) { SharedSearchCombatProfile() })
            }
            while (formProfiles[side].size < hp[side].size) formProfiles[side].add(mutableMapOf())
        }
    }

    private fun itemAvailable(state: SharedSearchProjectionState, side: Int, item: Int): Boolean =
        item < 0 || state.itemCounts.getOrNull(side)?.getOrNull(item)?.let { it > 0 } != false

    private val SWITCH_RESET_ABILITY_STATES = setOf(
        "illusion", "gulping", "gorging", "proteanUsed", "unburdenActivated",
    )

    private fun applyBoosts(ranks: MutableList<Int>, boosts: Map<String, Double>) {
        boosts.forEach { (stat, amount) ->
            val index = when (stat.lowercase()) {
                "attack" -> 0
                "specialattack" -> 1
                "defence", "defense" -> 2
                "specialdefence", "specialdefense" -> 3
                "speed" -> 4
                else -> -1
            }
            if (index in ranks.indices) ranks[index] = (ranks[index] + amount.roundToInt()).coerceIn(-6, 6)
        }
    }

    private fun clearHeldItem(items: MutableList<MutableList<String>>, side: Int, slot: Int) {
        if (side in items.indices && slot in items[side].indices) items[side][slot] = ""
    }

    private fun transferHeldItem(
        items: MutableList<MutableList<String>>,
        sourceSide: Int,
        sourceSlot: Int,
        targetSide: Int,
        targetSlot: Int,
    ) {
        if (sourceSide !in items.indices || targetSide !in items.indices ||
            sourceSlot !in items[sourceSide].indices || targetSlot !in items[targetSide].indices ||
            items[targetSide][targetSlot].isNotEmpty()) return
        items[targetSide][targetSlot] = items[sourceSide][sourceSlot]
        items[sourceSide][sourceSlot] = ""
    }

    private fun ensureSides(
        active: MutableList<Int>,
        hp: MutableList<MutableList<Int>>,
        maxHp: MutableList<MutableList<Int>>,
        gimmicks: MutableList<Boolean>,
        items: MutableList<MutableList<Int>>,
        hazards: MutableList<MutableList<Int>>,
        pressures: MutableList<MutableList<SharedSearchPressure>>,
        ranks: MutableList<MutableList<MutableList<Int>>>,
        heldItems: MutableList<MutableList<String>>,
        abilityStates: MutableList<MutableList<MutableSet<String>>>,
        sideConditions: MutableList<MutableMap<String, SharedSearchTimedEffect>>,
    ) {
        while (active.size < 2) active += 0
        while (gimmicks.size < 2) gimmicks += false
        while (hp.size < 2) hp.add(mutableListOf(0))
        while (maxHp.size < 2) maxHp.add(hp[maxHp.size].toMutableList())
        while (items.size < 2) items.add(mutableListOf())
        while (hazards.size < 2) hazards.add(MutableList(4) { 0 })
        while (pressures.size < 2) pressures.add(MutableList(hp[pressures.size].size) { SharedSearchPressure() })
        while (ranks.size < 2) ranks.add(MutableList(hp[ranks.size].size) { MutableList(5) { 0 } })
        while (heldItems.size < 2) heldItems.add(MutableList(hp[heldItems.size].size) { "" })
        while (abilityStates.size < 2) abilityStates.add(MutableList(hp[abilityStates.size].size) { mutableSetOf() })
        while (sideConditions.size < 2) sideConditions.add(mutableMapOf())
        for (side in 0..1) {
            while (maxHp[side].size < hp[side].size) maxHp[side] += hp[side][maxHp[side].size]
            while (hazards[side].size < 4) hazards[side] += 0
            while (pressures[side].size < hp[side].size) pressures[side] += SharedSearchPressure()
            while (ranks[side].size < hp[side].size) ranks[side].add(MutableList(5) { 0 })
            while (heldItems[side].size < hp[side].size) heldItems[side] += ""
            while (abilityStates[side].size < hp[side].size) abilityStates[side].add(mutableSetOf())
            ranks[side].forEach { while (it.size < 5) it += 0 }
        }
    }

    private fun tickField(field: SharedSearchFieldState): SharedSearchFieldState = field.copy(
        weather = tick(field.weather),
        terrain = tick(field.terrain),
        pseudoWeather = tickEffects(field.pseudoWeather),
    )

    private fun tickEffects(effects: Map<String, SharedSearchTimedEffect>): Map<String, SharedSearchTimedEffect> =
        effects.mapNotNull { (key, value) -> tick(value)?.let { key to it } }.toMap()

    private fun tick(effect: SharedSearchTimedEffect?): SharedSearchTimedEffect? = when {
        effect == null -> null
        effect.persistent -> effect
        effect.turns > 1 -> effect.copy(turns = effect.turns - 1)
        else -> null
    }

    private fun timed(id: String, requestedDuration: Int): SharedSearchTimedEffect {
        val clean = cleanProjection(id)
        val persistent = clean in setOf("desolateland", "primordialsea", "deltastream")
        val duration = if (persistent) Int.MAX_VALUE else if (requestedDuration > 0) requestedDuration else defaultDuration(clean)
        return SharedSearchTimedEffect(clean, duration, persistent)
    }

    private fun defaultDuration(id: String): Int = when (id) {
        "tailwind" -> 4
        else -> 5
    }
}

private fun cleanProjection(value: String): String =
    value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }

@Serializable
private data class SharedProjectedCandidateRequest(
    val state: SharedSearchProjectionState,
    val sideIndex: Int,
    val candidates: List<SharedProjectedSearchAction>,
)

@Serializable
private data class SharedProjectedTransitionRequest(
    val state: SharedSearchProjectionState,
    val sideZeroAction: SharedProjectedSearchAction,
    val sideOneAction: SharedProjectedSearchAction,
)

@JsExport
fun legalSharedSearchCandidatesJson(inputJson: String): String {
    val input = codec.decodeFromString<SharedProjectedCandidateRequest>(inputJson)
    return codec.encodeToString(SharedSearchProjectionRuntime.legalCandidates(input.state, input.sideIndex, input.candidates))
}

@JsExport
fun transitionSharedSearchStateJson(inputJson: String): String {
    val input = codec.decodeFromString<SharedProjectedTransitionRequest>(inputJson)
    return codec.encodeToString(SharedSearchProjectionRuntime.transition(input.state, input.sideZeroAction, input.sideOneAction))
}
