@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedDamageInput(
    val level: Int = 50,
    val power: Double = 0.0,
    val attack: Double = 1.0,
    val defence: Double = 1.0,
    val stab: Double = 1.0,
    val effectiveness: Double = 1.0,
    val itemModifier: Double = 1.0,
    val abilityModifier: Double = 1.0,
    val fieldModifier: Double = 1.0,
    val randomMinimum: Double = 0.85,
)

@Serializable
data class SharedDamageRange(
    val baseDamage: Int = 0,
    val minimum: Int = 0,
    val maximum: Int = 0,
    val totalModifier: Double = 0.0,
    val immune: Boolean = false,
)

@Serializable
data class SharedDamageRollInput(
    val baseDamage: Int = 0,
    val stab: Double = 1.0,
    val effectiveness: Double = 1.0,
    val itemModifier: Double = 1.0,
    val abilityModifier: Double = 1.0,
    val fieldModifier: Double = 1.0,
    val criticalModifier: Double = 1.0,
    val randomFactor: Double = 1.0,
    val remainingHp: Int? = null,
)

@Serializable
data class SharedDamageRoll(
    val damage: Int = 0,
    val uncappedDamage: Int = 0,
    val totalModifier: Double = 0.0,
    val immune: Boolean = false,
)

@Serializable
data class SharedDamagePipelineInput(
    val level: Int = 50,
    val power: Double = 0.0,
    val stats: SharedDamageStatInput = SharedDamageStatInput(),
    val factors: SharedDamageFactorsInput = SharedDamageFactorsInput(),
)

@Serializable
data class SharedDamagePipelineResult(
    val attack: Double = 1.0,
    val defence: Double = 1.0,
    val baseDamage: Int = 0,
    val minimum: Int = 0,
    val maximum: Int = 0,
    val stab: Double = 1.0,
    val effectiveness: Double = 1.0,
    val itemModifier: Double = 1.0,
    val abilityModifier: Double = 1.0,
    val fieldModifier: Double = 1.0,
)

object SharedDamageCalculator {
    fun range(input: SharedDamageInput): SharedDamageRange {
        val level = input.level.coerceAtLeast(1)
        val power = input.power.finiteOr(0.0).coerceAtLeast(0.0)
        val attack = input.attack.finiteOr(1.0).coerceAtLeast(0.0)
        val defence = input.defence.finiteOr(1.0).coerceAtLeast(1.0)
        val baseDamage = floor(
            (((floor((2.0 * level) / 5.0) + 2.0) * power * attack) / defence) / 50.0 + 2.0,
        ).toInt().coerceAtLeast(0)
        val totalModifier = modifier(
            input.stab,
            input.effectiveness,
            input.itemModifier,
            input.abilityModifier,
            input.fieldModifier,
        )
        val immune = input.effectiveness.finiteOr(0.0) == 0.0
        if (immune) {
            return SharedDamageRange(baseDamage, 0, 0, totalModifier, true)
        }
        val minimumModifier = input.randomMinimum.finiteOr(0.85).coerceAtLeast(0.0)
        return SharedDamageRange(
            baseDamage = baseDamage,
            minimum = floor(baseDamage * totalModifier * minimumModifier).toInt().coerceAtLeast(1),
            maximum = floor(baseDamage * totalModifier).toInt().coerceAtLeast(1),
            totalModifier = totalModifier,
        )
    }

    fun roll(input: SharedDamageRollInput): SharedDamageRoll {
        val totalModifier = modifier(
            input.stab,
            input.effectiveness,
            input.itemModifier,
            input.abilityModifier,
            input.fieldModifier,
            input.criticalModifier,
            input.randomFactor,
        )
        val immune = input.effectiveness.finiteOr(0.0) == 0.0
        if (immune) return SharedDamageRoll(totalModifier = totalModifier, immune = true)
        val uncapped = floor(input.baseDamage.coerceAtLeast(0) * totalModifier)
            .toInt().coerceAtLeast(1)
        val damage = input.remainingHp?.let { uncapped.coerceAtMost(it.coerceAtLeast(0)) } ?: uncapped
        return SharedDamageRoll(damage, uncapped, totalModifier)
    }

    fun rangeJson(inputJson: String): String = codec.encodeToString(
        range(codec.decodeFromString<SharedDamageInput>(inputJson)),
    )

    fun rollJson(inputJson: String): String = codec.encodeToString(
        roll(codec.decodeFromString<SharedDamageRollInput>(inputJson)),
    )

    private fun modifier(vararg values: Double): Double =
        values.fold(1.0) { result, value -> result * value.finiteOr(1.0).coerceAtLeast(0.0) }

    private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback
}

object SharedDamagePipelineEvaluator {
    fun evaluate(input: SharedDamagePipelineInput): SharedDamagePipelineResult {
        val stats = SharedDamageStatEvaluator.evaluate(input.stats)
        val factors = SharedDamageFactorsEvaluator.evaluate(input.factors)
        val range = SharedDamageCalculator.range(
            SharedDamageInput(
                level = input.level,
                power = input.power,
                attack = stats.attack,
                defence = stats.defence,
                stab = factors.stab,
                effectiveness = factors.effectiveness,
                itemModifier = factors.itemModifier,
                abilityModifier = factors.abilityModifier,
                fieldModifier = factors.fieldModifier,
            ),
        )
        return SharedDamagePipelineResult(
            attack = stats.attack,
            defence = stats.defence,
            baseDamage = range.baseDamage,
            minimum = range.minimum,
            maximum = range.maximum,
            stab = factors.stab,
            effectiveness = factors.effectiveness,
            itemModifier = factors.itemModifier,
            abilityModifier = factors.abilityModifier,
            fieldModifier = factors.fieldModifier,
        )
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedDamagePipelineInput>(inputJson)),
    )
}

@JsExport
fun calculateSharedDamageRangeJson(inputJson: String): String =
    SharedDamageCalculator.rangeJson(inputJson)

@JsExport
fun calculateSharedDamageRollJson(inputJson: String): String =
    SharedDamageCalculator.rollJson(inputJson)

@JsExport
fun evaluateSharedDamagePipelineJson(inputJson: String): String =
    SharedDamagePipelineEvaluator.evaluateJson(inputJson)

@JsExport
fun calculateSharedBaseDamage(
    level: Int,
    power: Double,
    attack: Double,
    defence: Double,
): Int = SharedDamageCalculator.range(
    SharedDamageInput(level = level, power = power, attack = attack, defence = defence),
).baseDamage

@JsExport
fun calculateSharedModifiedDamage(
    baseDamage: Int,
    stab: Double,
    effectiveness: Double,
    itemModifier: Double,
    abilityModifier: Double,
    fieldModifier: Double,
    criticalModifier: Double,
    randomFactor: Double,
    remainingHp: Int,
): Int = SharedDamageCalculator.roll(
    SharedDamageRollInput(
        baseDamage = baseDamage,
        stab = stab,
        effectiveness = effectiveness,
        itemModifier = itemModifier,
        abilityModifier = abilityModifier,
        fieldModifier = fieldModifier,
        criticalModifier = criticalModifier,
        randomFactor = randomFactor,
        remainingHp = remainingHp.takeIf { it >= 0 },
    ),
).damage
