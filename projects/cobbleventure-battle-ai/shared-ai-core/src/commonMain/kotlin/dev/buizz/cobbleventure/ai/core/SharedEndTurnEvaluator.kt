@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedEndTurnVolatile(
    val id: String = "",
    val source: String = "",
    val sourceSide: Int? = null,
    val count: Int? = null,
)

@Serializable
data class SharedEndTurnResidualInput(
    val side: Int = 0,
    val hp: Int = 0,
    val maximumHp: Int = 1,
    val types: List<String> = emptyList(),
    val status: String = "",
    val toxicCounter: Int = 0,
    val ability: String = "",
    val item: String = "",
    val weather: String = "",
    val terrain: String = "",
    val grounded: Boolean = false,
    val effectivelyAsleep: Boolean = false,
    val opposingAbility: String = "",
    val opposingFainted: Boolean = false,
    val volatiles: List<SharedEndTurnVolatile> = emptyList(),
)

@Serializable
data class SharedEndTurnOperation(
    val kind: String = "",
    val side: Int = 0,
    val sourceSide: Int? = null,
    val effect: String = "",
    val cause: String = "",
    val amount: Int = 0,
    val count: Int? = null,
    val boosts: Map<String, Int> = emptyMap(),
)

@Serializable
data class SharedEndTurnResidualResult(
    val operations: List<SharedEndTurnOperation> = emptyList(),
    val remainingHp: Int = 0,
    val toxicCounter: Int = 0,
    val fainted: Boolean = false,
)

/**
 * 턴 종료 시 모든 런타임에서 동일해야 하는 잔여 피해·회복 순서와 수치를 계산한다.
 * 개별 특성의 이벤트 표현과 폼 변경은 반환된 operation을 소비하는 런타임 어댑터가 담당한다.
 */
object SharedEndTurnResidualEvaluator {
    fun evaluate(input: SharedEndTurnResidualInput): SharedEndTurnResidualResult {
        val operations = mutableListOf<SharedEndTurnOperation>()
        val maximumHp = input.maximumHp.coerceAtLeast(1)
        var hp = input.hp.coerceIn(0, maximumHp)
        var toxicCounter = input.toxicCounter.coerceIn(0, 15)
        val ability = endTurnClean(input.ability)
        val item = endTurnClean(input.item)
        val weather = endTurnClean(input.weather)
        val terrain = endTurnClean(input.terrain)
        val status = endTurnClean(input.status)
        val types = input.types.map(::endTurnClean).toSet()
        val volatiles = input.volatiles.associateBy { endTurnClean(it.id) }

        fun damage(amount: Int, effect: String, cause: String): Boolean {
            if (amount <= 0 || hp <= 0) return hp <= 0
            val applied = minOf(hp, amount)
            hp -= applied
            operations += SharedEndTurnOperation("damage", input.side, effect = effect, cause = cause, amount = applied)
            return hp <= 0
        }

        fun heal(amount: Int, effect: String) {
            if (amount <= 0 || hp <= 0 || hp >= maximumHp) return
            val applied = minOf(maximumHp - hp, amount)
            hp += applied
            operations += SharedEndTurnOperation("heal", input.side, effect = effect, amount = applied)
        }

        fun fraction(divisor: Int): Int = maxOf(1, floor(maximumHp.toDouble() / divisor).toInt())

        if (ability == "dryskin" && weather in setOf("raindance", "primordialsea")) {
            heal(fraction(8), "dryskin")
        } else if (ability == "dryskin" && weather in setOf("sunnyday", "desolateland")) {
            if (damage(fraction(8), "dryskin", "ability")) return result(operations, hp, toxicCounter)
        }
        if (ability == "solarpower" && weather in setOf("sunnyday", "desolateland")) {
            if (damage(fraction(8), "solarpower", "ability")) return result(operations, hp, toxicCounter)
        }
        if (ability == "icebody" && weather in setOf("hail", "snow")) heal(fraction(16), "icebody")

        if (ability == "poisonheal" && status in setOf("psn", "tox")) {
            heal(fraction(8), "poisonheal")
            if (status == "tox") {
                toxicCounter = minOf(15, toxicCounter + 1)
                operations += SharedEndTurnOperation("toxic_counter", input.side, effect = "tox", count = toxicCounter)
            }
        } else if (ability != "magicguard") {
            val statusDamage = when (status) {
                "brn" -> if (ability == "heatproof") maxOf(1, fraction(16) / 2) else fraction(16)
                "psn" -> fraction(8)
                "tox" -> maxOf(1, floor(maximumHp.toDouble() * maxOf(1, toxicCounter) / 16.0).toInt())
                else -> 0
            }
            if (status == "tox" && statusDamage > 0) {
                toxicCounter = minOf(15, toxicCounter + 1)
                operations += SharedEndTurnOperation("toxic_counter", input.side, effect = "tox", count = toxicCounter)
            }
            if (damage(statusDamage, status, "status")) return result(operations, hp, toxicCounter)
        }

        if (input.effectivelyAsleep && !input.opposingFainted && endTurnClean(input.opposingAbility) == "baddreams") {
            if (damage(fraction(8), "baddreams", "ability")) return result(operations, hp, toxicCounter)
        }
        if (
            weather == "sandstorm" &&
            types.none { it in setOf("rock", "ground", "steel") } &&
            ability !in setOf("magicguard", "overcoat", "sandforce", "sandrush", "sandveil")
        ) {
            if (damage(fraction(16), "sandstorm", "weather")) return result(operations, hp, toxicCounter)
        }
        if (terrain == "grassyterrain" && input.grounded) heal(fraction(16), "grassyterrain")
        volatiles["ingrain"]?.let { heal(fraction(16), it.source.ifBlank { "Ingrain" }) }
        volatiles["aquaring"]?.let { heal(fraction(16), it.source.ifBlank { "Aqua Ring" }) }
        if (ability == "speedboost") {
            operations += SharedEndTurnOperation("boost", input.side, effect = "speedboost", boosts = mapOf("speed" to 1))
        }

        volatiles["leechseed"]?.let { leechSeed ->
            if (ability != "magicguard") {
                val applied = minOf(hp, fraction(8))
                hp -= applied
                operations += SharedEndTurnOperation(
                    kind = "drain",
                    side = input.side,
                    sourceSide = leechSeed.sourceSide,
                    effect = leechSeed.source.ifBlank { "Leech Seed" },
                    cause = "volatile",
                    amount = applied,
                )
                if (hp <= 0) return result(operations, hp, toxicCounter)
            }
        }
        if (volatiles.containsKey("curse") && ability != "magicguard") {
            if (damage(fraction(4), "Curse", "volatile")) return result(operations, hp, toxicCounter)
        }
        volatiles["nightmare"]?.let { nightmare ->
            if (input.effectivelyAsleep && ability != "magicguard") {
                if (damage(fraction(4), nightmare.source.ifBlank { "Nightmare" }, "volatile")) {
                    return result(operations, hp, toxicCounter)
                }
            }
        }
        volatiles["saltcure"]?.let { saltCure ->
            if (ability != "magicguard") {
                val divisor = if (types.any { it in setOf("water", "steel") }) 4 else 8
                if (damage(fraction(divisor), saltCure.source.ifBlank { "Salt Cure" }, "volatile")) {
                    return result(operations, hp, toxicCounter)
                }
            }
        }
        volatiles["octolock"]?.let { octolock ->
            operations += SharedEndTurnOperation(
                kind = "boost",
                side = input.side,
                effect = octolock.source.ifBlank { "Octolock" },
                boosts = mapOf("defence" to -1, "specialDefence" to -1),
            )
        }
        volatiles["perishsong"]?.let { perish ->
            val count = (perish.count ?: 3) - 1
            operations += SharedEndTurnOperation("perish_tick", input.side, effect = perish.source.ifBlank { "Perish Song" }, count = count)
            if (count <= 0) {
                hp = 0
                operations += SharedEndTurnOperation("faint", input.side, effect = "perishsong")
                return result(operations, hp, toxicCounter)
            }
        }
        input.volatiles.firstOrNull { endTurnClean(it.id) in BINDING_VOLATILES }?.let { binding ->
            if (ability != "magicguard" && damage(fraction(8), binding.source.ifBlank { binding.id }, "volatile")) {
                return result(operations, hp, toxicCounter)
            }
        }
        when {
            item == "leftovers" -> heal(fraction(16), "Leftovers")
            item == "blacksludge" && "poison" in types -> heal(fraction(16), "Black Sludge")
        }
        return result(operations, hp, toxicCounter)
    }

    private fun result(
        operations: List<SharedEndTurnOperation>,
        hp: Int,
        toxicCounter: Int,
    ) = SharedEndTurnResidualResult(operations, hp, toxicCounter, hp <= 0)

    private val BINDING_VOLATILES = setOf(
        "bind", "clamp", "firespin", "infestation", "magmastorm", "sandtomb",
        "snaptrap", "thundercage", "whirlpool", "wrap",
    )
}

@Serializable
data class SharedTimedEffectInput(
    val kind: String = "",
    val id: String = "",
    val turns: Int = 0,
)

@Serializable
data class SharedTimedEffectResult(
    val ended: Boolean = false,
    val remainingTurns: Int = 0,
    val triggerStatus: String = "",
)

object SharedTimedEffectEvaluator {
    fun evaluate(input: SharedTimedEffectInput): SharedTimedEffectResult {
        val remaining = input.turns - 1
        return SharedTimedEffectResult(
            ended = remaining <= 0,
            remainingTurns = maxOf(0, remaining),
            triggerStatus = if (remaining <= 0 && endTurnClean(input.kind) == "volatile" && endTurnClean(input.id) == "yawn") "slp" else "",
        )
    }
}

@Serializable
data class SharedDynamaxExpiryInput(
    val hp: Int = 0,
    val maximumHp: Int = 1,
    val baseMaximumHp: Int = 1,
    val remainingTurns: Int = 0,
)

@Serializable
data class SharedDynamaxExpiryResult(
    val ended: Boolean = false,
    val remainingTurns: Int = 0,
    val hp: Int = 0,
    val maximumHp: Int = 1,
)

object SharedDynamaxExpiryEvaluator {
    fun evaluate(input: SharedDynamaxExpiryInput): SharedDynamaxExpiryResult {
        val remaining = maxOf(0, input.remainingTurns - 1)
        if (remaining > 0) {
            return SharedDynamaxExpiryResult(false, remaining, input.hp, input.maximumHp.coerceAtLeast(1))
        }
        val oldMaximum = input.maximumHp.coerceAtLeast(1)
        val maximum = input.baseMaximumHp.coerceAtLeast(1)
        val hp = if (input.hp <= 0) 0 else minOf(maximum, maxOf(1, ceil(input.hp.toDouble() / oldMaximum * maximum).toInt()))
        return SharedDynamaxExpiryResult(true, 0, hp, maximum)
    }
}

@Serializable
data class SharedBattleOutcomeInput(
    val sideNames: List<String> = emptyList(),
    val faintedTeams: List<List<Boolean>> = emptyList(),
)

@Serializable
data class SharedBattleOutcomeResult(
    val completed: Boolean = false,
    val status: String = "running",
    val winner: String? = null,
    val defeatedSides: List<Int> = emptyList(),
)

object SharedBattleOutcomeEvaluator {
    fun evaluate(input: SharedBattleOutcomeInput): SharedBattleOutcomeResult {
        val defeated = input.faintedTeams.mapIndexedNotNull { index, team -> index.takeIf { team.isNotEmpty() && team.all { it } } }
        if (defeated.isEmpty()) return SharedBattleOutcomeResult()
        if (defeated.size == input.faintedTeams.size) {
            return SharedBattleOutcomeResult(true, "tie", null, defeated)
        }
        val winnerIndex = input.faintedTeams.indices.firstOrNull { it !in defeated }
        return SharedBattleOutcomeResult(true, "completed", winnerIndex?.let { input.sideNames.getOrNull(it) }, defeated)
    }
}

@Serializable
data class SharedFaintReplacementInput(
    val activeSlot: Int = 0,
    val activeFainted: Boolean = false,
    val manualSelection: Boolean = false,
    val teamHp: List<Int> = emptyList(),
    val teamFainted: List<Boolean> = emptyList(),
)

@Serializable
data class SharedFaintReplacementResult(
    val required: Boolean = false,
    val automatic: Boolean = false,
    val defeated: Boolean = false,
    val eligibleSlots: List<Int> = emptyList(),
)

object SharedFaintReplacementEvaluator {
    fun evaluate(input: SharedFaintReplacementInput): SharedFaintReplacementResult {
        if (!input.activeFainted) return SharedFaintReplacementResult()
        val eligible = input.teamHp.indices.filter { index ->
            index != input.activeSlot && input.teamHp[index] > 0 && input.teamFainted.getOrElse(index) { false }.not()
        }
        return SharedFaintReplacementResult(
            required = eligible.isNotEmpty(),
            automatic = eligible.isNotEmpty() && !input.manualSelection,
            defeated = eligible.isEmpty(),
            eligibleSlots = eligible,
        )
    }
}

private fun endTurnClean(value: String?): String = value.orEmpty().lowercase()
    .substringAfterLast(':').filter { it.isLetterOrDigit() }

@JsExport
fun evaluateSharedEndTurnResidualJson(inputJson: String): String = codec.encodeToString(
    SharedEndTurnResidualEvaluator.evaluate(codec.decodeFromString<SharedEndTurnResidualInput>(inputJson)),
)

@JsExport
fun evaluateSharedTimedEffectJson(inputJson: String): String = codec.encodeToString(
    SharedTimedEffectEvaluator.evaluate(codec.decodeFromString<SharedTimedEffectInput>(inputJson)),
)

@JsExport
fun evaluateSharedDynamaxExpiryJson(inputJson: String): String = codec.encodeToString(
    SharedDynamaxExpiryEvaluator.evaluate(codec.decodeFromString<SharedDynamaxExpiryInput>(inputJson)),
)

@JsExport
fun evaluateSharedBattleOutcomeJson(inputJson: String): String = codec.encodeToString(
    SharedBattleOutcomeEvaluator.evaluate(codec.decodeFromString<SharedBattleOutcomeInput>(inputJson)),
)

@JsExport
fun evaluateSharedFaintReplacementJson(inputJson: String): String = codec.encodeToString(
    SharedFaintReplacementEvaluator.evaluate(codec.decodeFromString<SharedFaintReplacementInput>(inputJson)),
)
