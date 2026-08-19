@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SharedDamageApplicationInput(
    val turn: Int = 0,
    val attackerSide: Int = 0,
    val defenderSide: Int = 1,
    val attackerName: String = "",
    val defenderName: String = "",
    val moveId: String = "",
    val moveName: String = "",
    val moveType: String = "Normal",
    val moveTarget: String = "normal",
    val damage: Int = 0,
    val defenderHp: Int = 0,
    val defenderMaximumHp: Int = 1,
    val substituteHp: Int? = null,
    val endure: Boolean = false,
    val sturdy: Boolean = false,
    val focusSash: Boolean = false,
    val focusBand: Boolean = false,
    val disguise: Boolean = false,
    val attackerInfiltrator: Boolean = false,
    val ignoresDefenderAbility: Boolean = false,
    val stab: Double = 1.0,
    val effectiveness: Double = 1.0,
    val randomFactor: Double = 1.0,
    val critical: Boolean = false,
    val hit: Int = 1,
    val hits: Int = 1,
    val rngState: Long = 0,
)

@Serializable
data class SharedDamageApplicationResult(
    val damage: Int = 0,
    val appliedDamage: Int = 0,
    val remainingHp: Int = 0,
    val substituteHp: Int? = null,
    val substituteBlocked: Boolean = false,
    val substituteEnded: Boolean = false,
    val disguiseBlocked: Boolean = false,
    val consumeFocusSash: Boolean = false,
    val immune: Boolean = false,
    val landed: Boolean = false,
    val fainted: Boolean = false,
    val preventionSource: String = "",
    val rngState: Long = 0,
    val events: List<JsonObject> = emptyList(),
)

@Serializable
data class SharedDirectDamageInput(
    val turn: Int = 0,
    val side: Int = 0,
    val pokemon: String = "",
    val amount: Int = 0,
    val hp: Int = 0,
    val maximumHp: Int = 1,
    val source: String = "",
    val cause: String = "move",
    val magicGuard: Boolean = false,
)

@Serializable
data class SharedDirectDamageResult(
    val damage: Int = 0,
    val remainingHp: Int = 0,
    val blockedByMagicGuard: Boolean = false,
    val fainted: Boolean = false,
    val events: List<JsonObject> = emptyList(),
)

object SharedDamageApplicationEvaluator {
    fun evaluate(input: SharedDamageApplicationInput): SharedDamageApplicationResult {
        val events = mutableListOf<JsonObject>()
        val rng = SharedBattleRng(input.rngState, restoredState = true)
        val hp = input.defenderHp.coerceAtLeast(0)
        val maximumHp = input.defenderMaximumHp.coerceAtLeast(1)
        var damage = input.damage.coerceIn(0, hp)
        var preventionSource = ""
        var consumeFocusSash = false

        if (damage >= hp && hp > 1 && (clean(input.moveId) == "falseswipe" || input.endure)) {
            damage = hp - 1
            preventionSource = if (clean(input.moveId) == "falseswipe") input.moveName else "endure"
            events += preventedEvent(input, preventionSource, hp)
        }
        if (damage >= hp && hp >= maximumHp && input.sturdy && !input.ignoresDefenderAbility) {
            damage = hp - 1
            preventionSource = "sturdy"
            events += preventedEvent(input, preventionSource, hp)
        }
        if (damage >= hp && hp >= maximumHp && input.focusSash) {
            damage = hp - 1
            preventionSource = "Focus Sash"
            consumeFocusSash = true
            events += preventedEvent(input, preventionSource, hp)
        }
        if (damage >= hp && input.focusBand && rng.nextDouble() < 0.1) {
            damage = max(0, hp - 1)
            preventionSource = "Focus Band"
            events += preventedEvent(input, preventionSource, hp)
        }

        if (input.effectiveness == 0.0) {
            events += damageEvent(input, damage = 0, remainingHp = hp, effectiveness = 0.0, critical = false)
            return SharedDamageApplicationResult(
                remainingHp = hp,
                substituteHp = input.substituteHp,
                immune = true,
                rngState = rng.snapshot(),
                events = events,
            )
        }

        val substituteBlocked = damage > 0 && input.substituteHp != null &&
            clean(input.moveTarget) != "self" && !input.attackerInfiltrator
        val disguiseBlocked = damage > 0 && !substituteBlocked && input.disguise &&
            !input.ignoresDefenderAbility
        if (disguiseBlocked) {
            damage = 0
            events += event(
                "turn" to input.turn,
                "type" to "ability_activate",
                "side" to input.defenderSide,
                "pokemon" to input.defenderName,
                "ability" to "disguise",
                "targetSide" to input.attackerSide,
                "target" to input.attackerName,
                "move" to input.moveName,
                "hit" to input.hit,
            )
        }

        var remainingHp = hp
        var remainingSubstituteHp = input.substituteHp
        val appliedDamage: Int
        var substituteEnded = false
        if (substituteBlocked) {
            appliedDamage = min(damage, input.substituteHp ?: 0)
            remainingSubstituteHp = max(0, (input.substituteHp ?: 0) - appliedDamage)
            events += event(
                "turn" to input.turn,
                "type" to "damage",
                "side" to input.defenderSide,
                "pokemon" to input.defenderName,
                "source" to "substitute",
                "cause" to "substitute",
                "move" to input.moveName,
                "moveType" to input.moveType,
                "damage" to appliedDamage,
                "remainingHp" to hp,
                "maximumHp" to maximumHp,
                "substituteHp" to remainingSubstituteHp,
                "effectiveness" to input.effectiveness,
                "hit" to input.hit,
            )
            if ((remainingSubstituteHp ?: 0) <= 0) {
                substituteEnded = true
                events += event(
                    "turn" to input.turn,
                    "type" to "volatile_end",
                    "side" to input.defenderSide,
                    "pokemon" to input.defenderName,
                    "effect" to "substitute",
                    "source" to input.moveName,
                )
            }
        } else {
            appliedDamage = damage
            remainingHp = max(0, hp - damage)
        }
        if (input.critical && damage > 0) {
            events += event(
                "turn" to input.turn,
                "type" to "critical",
                "side" to input.defenderSide,
                "pokemon" to input.defenderName,
                "source" to input.attackerName,
                "move" to input.moveName,
                "hit" to input.hit,
            )
        }
        if (!substituteBlocked) {
            events += damageEvent(
                input,
                damage = damage,
                remainingHp = remainingHp,
                effectiveness = input.effectiveness,
                critical = input.critical,
            )
        }
        return SharedDamageApplicationResult(
            damage = damage,
            appliedDamage = appliedDamage,
            remainingHp = remainingHp,
            substituteHp = remainingSubstituteHp,
            substituteBlocked = substituteBlocked,
            substituteEnded = substituteEnded,
            disguiseBlocked = disguiseBlocked,
            consumeFocusSash = consumeFocusSash,
            landed = true,
            fainted = remainingHp <= 0,
            preventionSource = preventionSource,
            rngState = rng.snapshot(),
            events = events,
        )
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDamageApplicationInput>(inputJson)),
    )

    private fun preventedEvent(input: SharedDamageApplicationInput, source: String, hp: Int): JsonObject = event(
        "turn" to input.turn,
        "type" to "damage_prevented",
        "side" to input.defenderSide,
        "pokemon" to input.defenderName,
        "source" to source,
        "remainingHp" to hp,
    )

    private fun damageEvent(
        input: SharedDamageApplicationInput,
        damage: Int,
        remainingHp: Int,
        effectiveness: Double,
        critical: Boolean,
    ): JsonObject = event(
        "turn" to input.turn,
        "type" to "damage",
        "side" to input.defenderSide,
        "pokemon" to input.defenderName,
        "source" to input.attackerName,
        "move" to input.moveName,
        "moveType" to input.moveType,
        "damage" to damage,
        "remainingHp" to remainingHp,
        "maximumHp" to input.defenderMaximumHp,
        "stab" to input.stab,
        "effectiveness" to effectiveness,
        "randomFactor" to round(input.randomFactor * 10_000.0) / 10_000.0,
        "critical" to critical,
        "hit" to input.hit,
        "hits" to input.hits,
    )

    private fun event(vararg entries: Pair<String, Any?>): JsonObject = JsonObject(
        entries.mapNotNull { (key, value) ->
            val primitive = when (value) {
                null -> null
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                else -> error("Unsupported event value for $key")
            }
            primitive?.let { key to it }
        }.toMap(),
    )

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

object SharedDirectDamageEvaluator {
    fun evaluate(input: SharedDirectDamageInput): SharedDirectDamageResult {
        val hp = input.hp.coerceAtLeast(0)
        val maximumHp = input.maximumHp.coerceAtLeast(1)
        if (input.magicGuard && clean(input.cause) !in setOf("move", "futureattack", "selfcost")) {
            return SharedDirectDamageResult(
                remainingHp = hp,
                blockedByMagicGuard = true,
                events = listOf(
                    event(
                        "turn" to input.turn,
                        "type" to "ability_activate",
                        "side" to input.side,
                        "pokemon" to input.pokemon,
                        "ability" to "magicguard",
                        "source" to input.source,
                        "cause" to input.cause,
                    ),
                ),
            )
        }
        val damage = input.amount.coerceIn(0, hp)
        if (damage <= 0) return SharedDirectDamageResult(remainingHp = hp)
        val remainingHp = hp - damage
        return SharedDirectDamageResult(
            damage = damage,
            remainingHp = remainingHp,
            fainted = remainingHp <= 0,
            events = listOf(
                event(
                    "turn" to input.turn,
                    "type" to "damage",
                    "side" to input.side,
                    "pokemon" to input.pokemon,
                    "source" to input.source,
                    "cause" to input.cause,
                    "damage" to damage,
                    "remainingHp" to remainingHp,
                    "maximumHp" to maximumHp,
                    "effectiveness" to 1.0,
                ),
            ),
        )
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDirectDamageInput>(inputJson)),
    )

    private fun event(vararg entries: Pair<String, Any?>): JsonObject = JsonObject(
        entries.mapNotNull { (key, value) ->
            val primitive = when (value) {
                null -> null
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                else -> error("Unsupported event value for $key")
            }
            primitive?.let { key to it }
        }.toMap(),
    )

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

@JsExport
fun applySharedDamageJson(inputJson: String): String =
    SharedDamageApplicationEvaluator.evaluateJson(inputJson)

@JsExport
fun applySharedDirectDamageJson(inputJson: String): String =
    SharedDirectDamageEvaluator.evaluateJson(inputJson)
