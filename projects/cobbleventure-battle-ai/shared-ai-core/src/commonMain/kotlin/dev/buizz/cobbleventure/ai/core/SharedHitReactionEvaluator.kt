@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class SharedHitReactionInput(
    val damage: Double = 0.0,
    val attackerAlive: Boolean = true,
    val defenderAlive: Boolean = true,
    val attackerAbility: String = "",
    val defenderAbility: String = "",
    val defenderItem: String = "",
    val moveId: String = "",
    val moveType: String = "",
    val moveCategory: String = "",
    val superEffective: Boolean = false,
    val contactPunishment: Boolean = false,
    val effectiveContact: Boolean = false,
    val ignoresDefenderAbility: Boolean = false,
    val attackerCanBurn: Boolean = false,
    val attackerCanPoison: Boolean = false,
    val attackerCanParalyze: Boolean = false,
    val attackerCanSleep: Boolean = false,
    val defenderCanPoison: Boolean = false,
    val attackerGrassType: Boolean = false,
    val attackerOvercoat: Boolean = false,
    val oppositeGender: Boolean = false,
    val attackerAlreadyAttracted: Boolean = false,
    val attackerAlreadyDisabled: Boolean = false,
    val moveIsMax: Boolean = false,
    val moveIsFuture: Boolean = false,
    val resolveRandom: Boolean = true,
    val rngState: Long = 0,
    val attackerItem: String = "",
    val defenderHasIllusion: Boolean = false,
    val defenderGulpMissileForm: String = "",
    val attackerItemRemovalBlocked: Boolean = false,
    val defenderItemRemovalBlocked: Boolean = false,
    val attackerItemConsumedOnHit: Boolean = false,
    val defenderItemConsumedOnHit: Boolean = false,
)

@Serializable
data class SharedHitReaction(
    val code: String,
    val source: String,
    val target: String = "",
    val boosts: Map<String, Double> = emptyMap(),
    val damageFraction: Double = 0.0,
    val status: String = "",
    val volatile: String = "",
    val sideCondition: String = "",
    val consumeItem: Boolean = false,
    val itemAction: String = "",
    val clearState: String = "",
)

@Serializable
data class SharedHitReactionResult(
    val reactions: List<SharedHitReaction> = emptyList(),
    val rngState: Long = 0,
)

/** 피해가 실제로 들어간 뒤 발생하는 도구·특성 반응을 순서 있는 공통 명령으로 만든다. */
object SharedHitReactionEvaluator {
    fun evaluate(input: SharedHitReactionInput): SharedHitReactionResult {
        val rng = SharedBattleRng(input.rngState, restoredState = true)
        if (input.damage <= 0.0) return SharedHitReactionResult(rngState = rng.snapshot())
        val attackerAbility = cleanHitReaction(input.attackerAbility)
        val defenderAbility = cleanHitReaction(input.defenderAbility)
        val originalAttackerItem = cleanHitReaction(input.attackerItem)
        val attackerItem = originalAttackerItem.takeUnless { input.attackerItemConsumedOnHit }.orEmpty()
        val item = cleanHitReaction(input.defenderItem)
        val moveId = cleanHitReaction(input.moveId)
        val moveType = cleanHitReaction(input.moveType)
        val category = cleanHitReaction(input.moveCategory)
        val defenderAbilityActive = !input.ignoresDefenderAbility
        val reactions = mutableListOf<SharedHitReaction>()
        fun add(
            code: String,
            source: String = code,
            target: String = "",
            boosts: Map<String, Double> = emptyMap(),
            damageFraction: Double = 0.0,
            status: String = "",
            volatile: String = "",
            sideCondition: String = "",
            consumeItem: Boolean = false,
            itemAction: String = "",
            clearState: String = "",
        ) = reactions.add(SharedHitReaction(
            code, source, target, boosts, damageFraction, status, volatile, sideCondition, consumeItem,
            itemAction, clearState,
        ))
        fun chance(probability: Double) = input.resolveRandom && rng.nextDouble() < probability
        val defenderItemConsumed = input.defenderItemConsumedOnHit ||
            (input.defenderAlive && item == "weaknesspolicy" && input.superEffective) ||
            (input.defenderAlive && item == "marangaberry" && category == "special")
        val transferableDefenderItem = item.takeUnless { defenderItemConsumed }.orEmpty()

        if (input.defenderAlive && item == "weaknesspolicy" && input.superEffective) {
            add("weaknesspolicy", target = "defender", boosts = mapOf("attack" to 2.0, "specialAttack" to 2.0), consumeItem = true)
        }
        if (input.defenderAlive && item == "marangaberry" && category == "special") {
            add("marangaberry", target = "defender", boosts = mapOf("specialDefence" to 1.0), consumeItem = true)
        }
        if (input.attackerItemConsumedOnHit && originalAttackerItem.isNotEmpty()) {
            add("attackeritemconsumed", target = "attacker", itemAction = "consume_attacker_item")
        }
        if (input.defenderItemConsumedOnHit && item.isNotEmpty()) {
            add("defenderitemconsumed", target = "defender", itemAction = "consume_defender_item")
        }
        if (input.defenderHasIllusion) {
            add("illusion", target = "defender", clearState = "illusion")
        }
        if (defenderAbilityActive && input.defenderAlive && defenderAbility == "pickpocket" &&
            input.contactPunishment && transferableDefenderItem.isEmpty() && attackerItem.isNotEmpty() &&
            !input.attackerItemRemovalBlocked) {
            add("pickpocket", target = "defender", itemAction = "steal_attacker_item")
        }
        if (input.contactPunishment && input.attackerAlive && item == "rockyhelmet") {
            add("rockyhelmet", target = "attacker", damageFraction = 1.0 / 6.0)
        }
        if (defenderAbilityActive && input.contactPunishment && input.attackerAlive && defenderAbility == "gooey") {
            add("gooey", target = "attacker", boosts = mapOf("speed" to -1.0))
        }
        if (defenderAbilityActive && input.attackerAlive && defenderAbility == "cottondown") {
            add("cottondown", target = "attacker", boosts = mapOf("speed" to -1.0))
        }
        val gulpMissileForm = cleanHitReaction(input.defenderGulpMissileForm)
        if (defenderAbilityActive && gulpMissileForm.isNotEmpty() && defenderAbility == "gulpmissile") {
            add(
                "gulpmissile",
                target = "attacker",
                damageFraction = 1.0 / 4.0,
                boosts = if (gulpMissileForm == "gulping") mapOf("defence" to -1.0) else emptyMap(),
                status = if (gulpMissileForm == "gorging") "par" else "",
                clearState = gulpMissileForm,
            )
        }
        if (defenderAbilityActive && input.defenderAlive && moveType == "dark" && defenderAbility == "justified") {
            add("justified", target = "defender", boosts = mapOf("attack" to 1.0))
        }
        if (attackerAbility == "magician" && attackerItem.isEmpty() && transferableDefenderItem.isNotEmpty() &&
            !input.defenderItemRemovalBlocked) {
            add("magician", target = "attacker", itemAction = "steal_defender_item")
        }
        if (defenderAbilityActive && input.defenderAlive && defenderAbility == "cursedbody" &&
            !input.attackerAlreadyDisabled && !input.moveIsMax && !input.moveIsFuture && moveId != "struggle" && chance(0.3)) {
            add("cursedbody", target = "attacker", volatile = "disable")
        }
        if (defenderAbilityActive && input.defenderAlive && input.contactPunishment &&
            defenderAbility == "poisonpoint" && input.attackerCanPoison && chance(0.3)) {
            add("poisonpoint", target = "attacker", status = "psn")
        }
        if (defenderAbilityActive && input.defenderAlive && input.contactPunishment &&
            defenderAbility == "static" && input.attackerCanParalyze && chance(0.3)) {
            add("static", target = "attacker", status = "par")
        }
        if (defenderAbilityActive && input.defenderAlive && input.contactPunishment && defenderAbility == "cutecharm" &&
            input.oppositeGender && !input.attackerAlreadyAttracted && chance(0.3)) {
            add("cutecharm", target = "attacker", volatile = "attract")
        }
        if (defenderAbilityActive && input.defenderAlive && defenderAbility == "stamina") {
            add("stamina", target = "defender", boosts = mapOf("defence" to 1.0))
        }
        if (defenderAbilityActive && input.defenderAlive && moveType == "fire" && defenderAbility == "thermalexchange") {
            add("thermalexchange", target = "defender", boosts = mapOf("attack" to 1.0))
        }
        if (defenderAbilityActive && input.defenderAlive && category == "physical" && defenderAbility == "weakarmor") {
            add("weakarmor", target = "defender", boosts = mapOf("defence" to -1.0, "speed" to 2.0))
        }
        if (defenderAbilityActive && category == "physical" && defenderAbility == "toxicdebris") {
            add("toxicdebris", target = "attackerSide", sideCondition = "toxicspikes")
        }
        if (defenderAbilityActive && input.contactPunishment && input.attackerAlive && defenderAbility in setOf("roughskin", "ironbarbs")) {
            add(defenderAbility, target = "attacker", damageFraction = 1.0 / 8.0)
        }
        if (defenderAbilityActive && input.defenderAlive && input.contactPunishment &&
            defenderAbility == "flamebody" && input.attackerCanBurn && chance(0.3)) {
            add("flamebody", target = "attacker", status = "brn")
        }
        if (defenderAbilityActive && input.defenderAlive && input.contactPunishment && defenderAbility == "effectspore" &&
            !input.attackerGrassType && !input.attackerOvercoat && chance(0.3)) {
            val status = listOf("par", "psn", "slp")[(rng.nextDouble() * 3.0).toInt().coerceIn(0, 2)]
            val eligible = when (status) {
                "par" -> input.attackerCanParalyze
                "psn" -> input.attackerCanPoison
                else -> input.attackerCanSleep
            }
            if (eligible) add("effectspore", target = "attacker", status = status)
        }
        if (input.defenderAlive && input.effectiveContact && attackerAbility == "poisontouch" &&
            input.defenderCanPoison && chance(0.3)) {
            add("poisontouch", target = "defender", status = "psn")
        }
        return SharedHitReactionResult(reactions, rng.snapshot())
    }
}

private fun cleanHitReaction(value: String): String =
    value.lowercase().substringAfterLast(':').filter { it.isLetterOrDigit() }

@JsExport
fun evaluateSharedHitReactionsJson(inputJson: String): String = codec.encodeToString(
    SharedHitReactionEvaluator.evaluate(codec.decodeFromString<SharedHitReactionInput>(inputJson)),
)
