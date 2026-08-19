@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedStatusApplicationInput(
    val status: String = "",
    val currentStatus: String = "",
    val fainted: Boolean = false,
    val types: List<String> = emptyList(),
    val ability: String = "",
    val terrain: String = "",
    val grounded: Boolean = false,
    val flowerVeilProtected: Boolean = false,
    val sweetVeilProtected: Boolean = false,
    val leafGuardSun: Boolean = false,
    val safeguardProtected: Boolean = false,
    val rngState: Long = 0,
)

@Serializable
data class SharedStatusApplicationResult(
    val applied: Boolean = false,
    val status: String = "",
    val statusTurns: Int = 0,
    val toxicCounter: Int = 0,
    val blockedBy: String = "",
    val rngState: Long = 0,
)

object SharedStatusApplicationEvaluator {
    fun evaluate(input: SharedStatusApplicationInput): SharedStatusApplicationResult {
        val status = clean(input.status)
        val ability = clean(input.ability)
        val types = input.types.map(::clean).toSet()
        val terrain = clean(input.terrain)
        val blockedBy = when {
            status.isEmpty() -> "invalid"
            input.currentStatus.isNotBlank() -> "existing_status"
            input.fainted -> "fainted"
            ability == "comatose" -> "comatose"
            input.flowerVeilProtected -> "flowerveil"
            status == "slp" && input.sweetVeilProtected -> "sweetveil"
            input.leafGuardSun -> "leafguard"
            input.grounded && terrain == "mistyterrain" -> "mistyterrain"
            input.grounded && terrain == "electricterrain" && status == "slp" -> "electricterrain"
            input.safeguardProtected -> "safeguard"
            status == "brn" && "fire" in types -> "type"
            status == "par" && "electric" in types -> "type"
            status in setOf("psn", "tox") && ("poison" in types || "steel" in types) -> "type"
            status == "frz" && "ice" in types -> "type"
            status == "brn" && ability in setOf("purifyingsalt", "thermalexchange", "waterbubble", "waterveil") -> ability
            status == "par" && ability in setOf("purifyingsalt", "limber") -> ability
            status in setOf("psn", "tox") && ability in setOf("purifyingsalt", "immunity") -> ability
            status == "slp" && ability in setOf("purifyingsalt", "insomnia", "vitalspirit") -> ability
            ability == "purifyingsalt" -> ability
            else -> ""
        }
        val rng = SharedBattleRng(input.rngState, restoredState = true)
        if (blockedBy.isNotEmpty()) {
            return SharedStatusApplicationResult(blockedBy = blockedBy, rngState = rng.snapshot())
        }
        return SharedStatusApplicationResult(
            applied = true,
            status = status,
            statusTurns = if (status == "slp") 1 + (rng.nextDouble() * 3.0).toInt() else 0,
            toxicCounter = if (status == "tox") 1 else 0,
            rngState = rng.snapshot(),
        )
    }
}

@Serializable
data class SharedVolatileApplicationInput(
    val id: String = "",
    val fainted: Boolean = false,
    val alreadyActive: Boolean = false,
    val ability: String = "",
    val sourceIsOpponent: Boolean = false,
    val aromaVeilProtected: Boolean = false,
    val sourceItem: String = "",
)

@Serializable
data class SharedVolatileApplicationResult(
    val applied: Boolean = false,
    val id: String = "",
    val turns: Int? = null,
    val eventDuration: Int? = null,
    val perishCount: Int? = null,
    val blockedBy: String = "",
    val emitBlockActivation: Boolean = false,
)

object SharedVolatileApplicationEvaluator {
    fun evaluate(input: SharedVolatileApplicationInput): SharedVolatileApplicationResult {
        val id = clean(input.id)
        val ability = clean(input.ability)
        val blockedBy = when {
            id.isEmpty() -> "invalid"
            input.fainted -> "fainted"
            input.alreadyActive -> "already_active"
            input.sourceIsOpponent && input.aromaVeilProtected && id in AROMA_VEIL_VOLATILES -> "aromaveil"
            id == "confusion" && ability == "owntempo" -> "owntempo"
            id == "flinch" && ability == "innerfocus" -> "innerfocus"
            id in setOf("attract", "taunt") && ability == "oblivious" -> "oblivious"
            else -> ""
        }
        if (blockedBy.isNotEmpty()) {
            return SharedVolatileApplicationResult(
                id = id,
                blockedBy = blockedBy,
                emitBlockActivation = blockedBy in setOf("aromaveil", "innerfocus", "oblivious"),
            )
        }
        val eventDuration = VOLATILE_DURATIONS[id]
        var turns = eventDuration
        if (id in BINDING_VOLATILES) {
            turns = if (clean(input.sourceItem) in setOf("gribclaw", "gripclaw")) 7 else turns ?: 4
        }
        return SharedVolatileApplicationResult(
            applied = true,
            id = id,
            turns = turns,
            eventDuration = eventDuration,
            perishCount = if (id == "perishsong") 3 else null,
        )
    }

    private val AROMA_VEIL_VOLATILES = setOf("attract", "disable", "encore", "healblock", "taunt", "torment")
    private val BINDING_VOLATILES = setOf(
        "bind", "clamp", "firespin", "infestation", "magmastorm", "sandtomb",
        "snaptrap", "thundercage", "whirlpool", "wrap",
    )
    private val VOLATILE_DURATIONS = mapOf(
        "confusion" to 4, "charge" to 2, "disable" to 4, "electrify" to 1, "embargo" to 5,
        "endure" to 1, "encore" to 3, "flinch" to 1, "followme" to 1, "healblock" to 5,
        "helpinghand" to 1, "laserfocus" to 2, "lockon" to 2, "magiccoat" to 1,
        "magnetrise" to 5, "mindreader" to 2, "powder" to 1, "protect" to 1,
        "ragepowder" to 1, "taunt" to 3, "telekinesis" to 3, "uproar" to 3,
        "yawn" to 2, "perishsong" to 4,
    )
}

@Serializable
data class SharedBoostApplicationInput(
    val stat: String = "",
    val amount: Int = 0,
    val currentStage: Int = 0,
    val ability: String = "",
    val item: String = "",
    val grassType: Boolean = false,
    val flowerVeilProtected: Boolean = false,
    val source: String = "",
    val loweredByFoe: Boolean = false,
)

@Serializable
data class SharedBoostApplicationResult(
    val action: String = "ignore",
    val stat: String = "",
    val appliedAmount: Int = 0,
    val nextStage: Int = 0,
    val blockedBy: String = "",
    val loweredByFoe: Boolean = false,
)

object SharedBoostApplicationEvaluator {
    fun evaluate(input: SharedBoostApplicationInput): SharedBoostApplicationResult {
        if (input.stat !in BOOST_STATS || input.amount == 0) return SharedBoostApplicationResult()
        val ability = clean(input.ability)
        val item = clean(input.item)
        val source = clean(input.source)
        val contraryAmount = if (ability == "contrary") -input.amount else input.amount
        val modifiedAmount = if (ability == "simple") contraryAmount * 2 else contraryAmount
        val loweredByFoe = modifiedAmount < 0 && input.loweredByFoe
        val blockedBy = when {
            loweredByFoe && ability == "mirrorarmor" && source != "mirrorarmor" -> "mirrorarmor"
            loweredByFoe && input.grassType && input.flowerVeilProtected -> "flowerveil"
            loweredByFoe && item == "clearamulet" -> "clearamulet"
            loweredByFoe && ability in setOf("clearbody", "whitesmoke") -> ability
            loweredByFoe && input.stat == "attack" && source == "intimidate" && ability in setOf("innerfocus", "oblivious") -> ability
            loweredByFoe && input.stat == "attack" && ability == "hypercutter" -> ability
            loweredByFoe && input.stat == "accuracy" && ability == "keeneye" -> ability
            else -> ""
        }
        if (blockedBy.isNotEmpty()) {
            return SharedBoostApplicationResult(
                action = if (blockedBy == "mirrorarmor") "reflect" else "block",
                stat = input.stat,
                appliedAmount = modifiedAmount,
                nextStage = input.currentStage,
                blockedBy = blockedBy,
                loweredByFoe = loweredByFoe,
            )
        }
        val next = (input.currentStage + modifiedAmount).coerceIn(-6, 6)
        val applied = next - input.currentStage
        return SharedBoostApplicationResult(
            action = if (applied == 0) "ignore" else "apply",
            stat = input.stat,
            appliedAmount = applied,
            nextStage = next,
            loweredByFoe = loweredByFoe && applied < 0,
        )
    }

    private val BOOST_STATS = setOf("attack", "defence", "specialAttack", "specialDefence", "speed", "accuracy", "evasion")
}

@Serializable
data class SharedFieldApplicationInput(
    val kind: String = "",
    val id: String = "",
    val currentWeather: String = "",
    val sourceItem: String = "",
)

@Serializable
data class SharedFieldApplicationResult(
    val applied: Boolean = false,
    val id: String = "",
    val turns: Int? = null,
    val blockedBy: String = "",
)

object SharedFieldApplicationEvaluator {
    fun evaluate(input: SharedFieldApplicationInput): SharedFieldApplicationResult {
        val id = clean(input.id)
        val kind = clean(input.kind)
        val currentWeather = clean(input.currentWeather)
        val item = clean(input.sourceItem)
        if (id.isEmpty()) return SharedFieldApplicationResult(blockedBy = "invalid")
        if (kind == "weather" && currentWeather in PERSISTENT_WEATHERS && id !in PERSISTENT_WEATHERS) {
            return SharedFieldApplicationResult(id = id, blockedBy = currentWeather)
        }
        var turns: Int? = 5
        if (kind == "terrain" && item == "terrainextender") turns = 8
        if (kind == "weather") {
            if (id in PERSISTENT_WEATHERS) turns = null
            val rock = mapOf("sunnyday" to "heatrock", "raindance" to "damprock", "sandstorm" to "smoothrock", "snow" to "icyrock", "hail" to "icyrock")[id]
            if (item == rock) turns = 8
        }
        return SharedFieldApplicationResult(applied = true, id = id, turns = turns)
    }

    private val PERSISTENT_WEATHERS = setOf("deltastream", "desolateland", "primordialsea")
}

@Serializable
data class SharedSideConditionApplicationInput(
    val id: String = "",
    val previousLayers: Int = 0,
    val alreadyActive: Boolean = false,
    val sourceItem: String = "",
)

@Serializable
data class SharedSideConditionApplicationResult(
    val applied: Boolean = false,
    val id: String = "",
    val layers: Int? = null,
    val turns: Int? = null,
)

object SharedSideConditionApplicationEvaluator {
    fun evaluate(input: SharedSideConditionApplicationInput): SharedSideConditionApplicationResult {
        val id = clean(input.id)
        if (id.isEmpty()) return SharedSideConditionApplicationResult()
        val maximumLayers = mapOf("spikes" to 3, "toxicspikes" to 2)[id]
        if (maximumLayers != null) {
            val layers = min(maximumLayers, input.previousLayers.coerceAtLeast(0) + 1)
            return SharedSideConditionApplicationResult(
                applied = layers != input.previousLayers,
                id = id,
                layers = layers,
            )
        }
        if (id in setOf("stealthrock", "stickyweb")) {
            return SharedSideConditionApplicationResult(
                applied = !input.alreadyActive,
                id = id,
                layers = 1,
            )
        }
        var turns = mapOf(
            "auroraveil" to 5, "craftyshield" to 1, "lightscreen" to 5, "luckychant" to 5,
            "matblock" to 1, "quickguard" to 1, "reflect" to 5, "safeguard" to 5,
            "tailwind" to 4, "wideguard" to 1,
        )[id] ?: 5
        if (id in setOf("auroraveil", "lightscreen", "reflect") && clean(input.sourceItem) == "lightclay") turns = 8
        return SharedSideConditionApplicationResult(applied = true, id = id, turns = turns)
    }
}

private fun clean(value: String?): String = value.orEmpty().lowercase()
    .substringAfterLast(':').filter { it.isLetterOrDigit() }

@JsExport
fun applySharedStatusJson(inputJson: String): String = codec.encodeToString(
    SharedStatusApplicationEvaluator.evaluate(codec.decodeFromString<SharedStatusApplicationInput>(inputJson)),
)

@JsExport
fun applySharedVolatileJson(inputJson: String): String = codec.encodeToString(
    SharedVolatileApplicationEvaluator.evaluate(codec.decodeFromString<SharedVolatileApplicationInput>(inputJson)),
)

@JsExport
fun applySharedBoostJson(inputJson: String): String = codec.encodeToString(
    SharedBoostApplicationEvaluator.evaluate(codec.decodeFromString<SharedBoostApplicationInput>(inputJson)),
)

@JsExport
fun applySharedFieldJson(inputJson: String): String = codec.encodeToString(
    SharedFieldApplicationEvaluator.evaluate(codec.decodeFromString<SharedFieldApplicationInput>(inputJson)),
)

@JsExport
fun applySharedSideConditionJson(inputJson: String): String = codec.encodeToString(
    SharedSideConditionApplicationEvaluator.evaluate(codec.decodeFromString<SharedSideConditionApplicationInput>(inputJson)),
)
