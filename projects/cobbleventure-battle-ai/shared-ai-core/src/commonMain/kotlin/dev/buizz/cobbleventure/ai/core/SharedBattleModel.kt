@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val UINT32_RANGE = 4_294_967_296.0
private const val RNG_SEED_SALT = -1_640_531_527 // 0x9e3779b9

@Serializable
data class SharedEngineIdentity(
    val id: String = "cobbleventure-shared",
    val version: String = "1",
)

@Serializable
data class SharedBattleStats(
    val hp: Int = 1,
    val attack: Int = 1,
    val defence: Int = 1,
    val specialAttack: Int = 1,
    val specialDefence: Int = 1,
    val speed: Int = 1,
)

@Serializable
data class SharedFraction(val numerator: Int = 0, val denominator: Int = 1)

/**
 * 기술·특성·도구별 중첩 상태를 공통 모델이 손실 없이 운반하기 위한 확장 영역.
 * 일반 필드로 승격되기 전의 데이터도 attributes에 그대로 보존한다.
 */
@Serializable
data class SharedEffectState(
    val id: String = "",
    val turns: Int = 0,
    val layers: Int = 0,
    val sourceSide: Int? = null,
    val sourceSlot: Int? = null,
    val values: Map<String, Double> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SharedMoveSecondary(
    val chance: Double = 100.0,
    val status: String = "",
    val volatileStatus: String = "",
    val boosts: Map<String, Int> = emptyMap(),
    val selfBoosts: Map<String, Int> = emptyMap(),
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SharedMoveState(
    val id: String = "",
    val name: String = "",
    val type: String = "Normal",
    val category: String = "Status",
    val power: Double = 0.0,
    val accuracy: Double = 100.0,
    val alwaysHits: Boolean = false,
    val priority: Int = 0,
    val maxPp: Int = 1,
    val pp: Int = 1,
    val target: String = "normal",
    val contact: Boolean = false,
    val punch: Boolean = false,
    val powder: Boolean = false,
    val sound: Boolean = false,
    val status: String = "",
    val selfStatus: String = "",
    val volatileStatus: String = "",
    val boosts: Map<String, Int> = emptyMap(),
    val selfBoosts: Map<String, Int> = emptyMap(),
    val heal: SharedFraction? = null,
    val drain: SharedFraction? = null,
    val recoil: SharedFraction? = null,
    val weather: String = "",
    val terrain: String = "",
    val pseudoWeather: String = "",
    val sideCondition: String = "",
    val slotCondition: String = "",
    val multiHitMinimum: Int? = null,
    val multiHitMaximum: Int? = null,
    val multiAccuracy: Boolean = false,
    val willCrit: Boolean = false,
    val selfSwitch: Boolean = false,
    val forceSwitch: Boolean = false,
    val fixedDamage: JsonElement? = null,
    val dynamicDamage: Boolean = false,
    val dynamicPower: Boolean = false,
    val secondaries: List<SharedMoveSecondary> = emptyList(),
    val effectState: SharedEffectState = SharedEffectState(),
)

@Serializable
data class SharedPokemonState(
    val id: String = "",
    val name: String = "",
    val baseSpecies: String = "",
    val level: Int = 50,
    val types: List<String> = listOf("Normal"),
    val originalTypes: List<String> = listOf("Normal"),
    val gender: String = "",
    val ability: String = "",
    val baseAbility: String = "",
    val item: String = "",
    val stats: SharedBattleStats = SharedBattleStats(),
    val baseMaximumHp: Int = 1,
    val hp: Int = 1,
    val fainted: Boolean = false,
    val status: String = "",
    val statusTurns: Int = 0,
    val toxicCounter: Int = 0,
    val boosts: Map<String, Int> = emptyMap(),
    val volatiles: Map<String, SharedEffectState> = emptyMap(),
    val abilityState: SharedEffectState = SharedEffectState(),
    val activeTurns: Int = 0,
    val lastMoveId: String = "",
    val lastMoveSucceeded: Boolean? = null,
    val consecutiveMoveId: String = "",
    val consecutiveMoveCount: Int = 0,
    val protectCounter: Int = 0,
    val lockedMove: SharedEffectState? = null,
    val choiceLock: SharedEffectState? = null,
    val chargingMove: SharedEffectState? = null,
    val teraType: String? = null,
    val configuredTeraType: String = "Normal",
    val terastallized: Boolean = false,
    val stellarBoostedTypes: List<String> = emptyList(),
    val hasDynamaxed: Boolean = false,
    val dynamaxTurns: Int = 0,
    val dynamaxMode: String? = null,
    val moves: List<SharedMoveState> = emptyList(),
    val effectState: SharedEffectState = SharedEffectState(),
)

@Serializable
data class SharedBagEntry(val item: String = "", val quantity: Int = 0)

@Serializable
data class SharedBattleSideState(
    val name: String = "",
    val active: Int = 0,
    val bag: List<SharedBagEntry> = emptyList(),
    val itemUsesRemaining: Int = 0,
    val usedGimmicks: Map<String, Boolean> = emptyMap(),
    val gimmickResources: Map<String, String> = emptyMap(),
    val conditions: Map<String, SharedEffectState> = emptyMap(),
    val team: List<SharedPokemonState> = emptyList(),
    val effectState: SharedEffectState = SharedEffectState(),
)

@Serializable
data class SharedBattleFieldState(
    val weather: SharedEffectState? = null,
    val terrain: SharedEffectState? = null,
    val pseudoWeather: Map<String, SharedEffectState> = emptyMap(),
    val effectState: SharedEffectState = SharedEffectState(),
)

@Serializable
data class SharedBattleEvent(
    val turn: Int = 0,
    val type: String = "",
    val side: Int? = null,
    val sourceSide: Int? = null,
    val pokemon: String? = null,
    val sourcePokemon: String? = null,
    val targetPokemon: String? = null,
    val move: String? = null,
    val ability: String? = null,
    val item: String? = null,
    val effect: String? = null,
    val slot: Int? = null,
    val remainingHp: Int? = null,
    val maximumHp: Int? = null,
    val status: String? = null,
    val winner: String? = null,
    val message: String? = null,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SharedBattleState(
    val schemaVersion: Int = 1,
    val engine: SharedEngineIdentity = SharedEngineIdentity(),
    val seed: Long = 0,
    val rngState: Long? = null,
    val turn: Int = 0,
    val status: String = "running",
    val winner: String? = null,
    val gimmickProfile: String = "cobbleventure_all",
    val field: SharedBattleFieldState = SharedBattleFieldState(),
    val manualFaintSwitchSides: List<Int> = emptyList(),
    val strictMoveEffectValidation: Boolean = false,
    val sides: List<SharedBattleSideState> = emptyList(),
    val events: List<SharedBattleEvent> = emptyList(),
    val futureAttacks: List<SharedEffectState> = emptyList(),
    val lastSuccessfulMove: SharedMoveState? = null,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SharedBattleCommand(
    val side: Int = -1,
    val kind: String = "move",
    val moveSlot: Int? = null,
    val switchSlot: Int? = null,
    val item: String? = null,
    val itemTargetSlot: Int? = null,
    val selfSwitchSlot: Int? = null,
    val gimmick: String = "",
    val teraType: String = "",
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SharedTurnCommands(val commands: List<SharedBattleCommand> = emptyList())

@Serializable
data class SharedRngRequest(
    val seed: Long = 0,
    val restoredState: Boolean = false,
    val draws: Int = 1,
)

@Serializable
data class SharedRngSample(
    val state: Long = 0,
    val unsignedValues: List<Long> = emptyList(),
    val values: List<Double> = emptyList(),
)

/** 웹 엔진의 xorshift32와 같은 비트열을 JVM과 JavaScript에서 생성한다. */
class SharedBattleRng(seed: Long, restoredState: Boolean = false) {
    private var state: Int = if (restoredState) seed.toInt() else seed.toInt() xor RNG_SEED_SALT

    fun nextUnsigned(): Long {
        var value = state
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        state = value
        return snapshot()
    }

    fun nextDouble(): Double = nextUnsigned().toDouble() / UINT32_RANGE

    fun snapshot(): Long = state.toLong() and 0xffff_ffffL
}

object SharedBattleContract {
    fun normalize(state: SharedBattleState): SharedBattleState {
        require(state.sides.size == 2) { "A shared battle requires exactly two sides" }
        val normalizedSides = state.sides.mapIndexed { sideIndex, side ->
            require(side.team.isNotEmpty()) { "Side ${sideIndex + 1} requires a team" }
            require(side.active in side.team.indices) { "Side ${sideIndex + 1} active slot is invalid" }
            side.copy(
                bag = side.bag.mapNotNull { entry ->
                    entry.takeIf { it.item.isNotBlank() && it.quantity > 0 }
                },
                itemUsesRemaining = side.itemUsesRemaining.coerceAtLeast(0),
                team = side.team.map(::normalizePokemon),
            )
        }
        return state.copy(
            schemaVersion = state.schemaVersion.coerceAtLeast(1),
            seed = unsigned32(state.seed),
            rngState = state.rngState?.let(::unsigned32),
            turn = state.turn.coerceAtLeast(0),
            manualFaintSwitchSides = state.manualFaintSwitchSides.distinct().filter { it in 0..1 },
            sides = normalizedSides,
        )
    }

    fun normalizeCommands(state: SharedBattleState, turn: SharedTurnCommands): SharedTurnCommands {
        val normalizedState = normalize(state)
        require(turn.commands.size == 2) { "Exactly two commands are required" }
        val commands = turn.commands.mapIndexed { index, command ->
            val sideIndex = if (command.side < 0) index else command.side
            require(sideIndex == index) { "Command side order must be 0, 1" }
            val side = normalizedState.sides[sideIndex]
            when (command.kind) {
                "move" -> command.moveSlot?.let { moveSlot ->
                    require(moveSlot in 1..side.team[side.active].moves.size) {
                        "Side ${sideIndex + 1} move slot is invalid"
                    }
                }
                "switch" -> require(command.switchSlot != null && command.switchSlot in 1..side.team.size) {
                    "Side ${sideIndex + 1} switch slot is invalid"
                }
                "item" -> require(!command.item.isNullOrBlank()) { "Side ${sideIndex + 1} item is missing" }
                else -> error("Unsupported command kind: ${command.kind}")
            }
            command.copy(side = sideIndex)
        }
        return SharedTurnCommands(commands)
    }

    fun normalizeStateJson(inputJson: String): String = codec.encodeToString(
        normalize(codec.decodeFromString<SharedBattleState>(inputJson)),
    )

    fun normalizeCommandsJson(stateJson: String, commandsJson: String): String = codec.encodeToString(
        normalizeCommands(
            codec.decodeFromString<SharedBattleState>(stateJson),
            codec.decodeFromString<SharedTurnCommands>(commandsJson),
        ),
    )

    fun sampleRng(request: SharedRngRequest): SharedRngSample {
        val rng = SharedBattleRng(unsigned32(request.seed), request.restoredState)
        val unsigned = ArrayList<Long>()
        val values = ArrayList<Double>()
        repeat(request.draws.coerceIn(0, 10_000)) {
            val value = rng.nextUnsigned()
            unsigned += value
            values += value.toDouble() / UINT32_RANGE
        }
        return SharedRngSample(rng.snapshot(), unsigned, values)
    }

    fun sampleRngJson(inputJson: String): String = codec.encodeToString(
        sampleRng(codec.decodeFromString<SharedRngRequest>(inputJson)),
    )

    private fun normalizePokemon(pokemon: SharedPokemonState): SharedPokemonState {
        val stats = pokemon.stats.copy(
            hp = pokemon.stats.hp.coerceAtLeast(1),
            attack = pokemon.stats.attack.coerceAtLeast(1),
            defence = pokemon.stats.defence.coerceAtLeast(1),
            specialAttack = pokemon.stats.specialAttack.coerceAtLeast(1),
            specialDefence = pokemon.stats.specialDefence.coerceAtLeast(1),
            speed = pokemon.stats.speed.coerceAtLeast(1),
        )
        val hp = pokemon.hp.coerceIn(0, stats.hp)
        return pokemon.copy(
            level = pokemon.level.coerceIn(1, 100),
            stats = stats,
            baseMaximumHp = pokemon.baseMaximumHp.coerceAtLeast(1),
            hp = hp,
            fainted = hp == 0 || pokemon.fainted,
            statusTurns = pokemon.statusTurns.coerceAtLeast(0),
            toxicCounter = pokemon.toxicCounter.coerceAtLeast(0),
            boosts = pokemon.boosts.mapValues { (_, rank) -> rank.coerceIn(-6, 6) },
            activeTurns = pokemon.activeTurns.coerceAtLeast(0),
            consecutiveMoveCount = pokemon.consecutiveMoveCount.coerceAtLeast(0),
            protectCounter = pokemon.protectCounter.coerceAtLeast(0),
            dynamaxTurns = pokemon.dynamaxTurns.coerceAtLeast(0),
            moves = pokemon.moves.map { move ->
                val maximum = move.maxPp.coerceAtLeast(1)
                move.copy(
                    power = finite(move.power).coerceAtLeast(0.0),
                    accuracy = finite(move.accuracy).coerceIn(0.0, 100.0),
                    maxPp = maximum,
                    pp = move.pp.coerceIn(0, maximum),
                    secondaries = move.secondaries.map { secondary ->
                        secondary.copy(chance = finite(secondary.chance).coerceIn(0.0, 100.0))
                    },
                )
            },
        )
    }

    private fun unsigned32(value: Long): Long = value and 0xffff_ffffL
    private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0
}

@JsExport
fun normalizeSharedBattleStateJson(inputJson: String): String =
    SharedBattleContract.normalizeStateJson(inputJson)

@JsExport
fun normalizeSharedBattleCommandsJson(stateJson: String, commandsJson: String): String =
    SharedBattleContract.normalizeCommandsJson(stateJson, commandsJson)

@JsExport
fun sampleSharedBattleRngJson(inputJson: String): String =
    SharedBattleContract.sampleRngJson(inputJson)
