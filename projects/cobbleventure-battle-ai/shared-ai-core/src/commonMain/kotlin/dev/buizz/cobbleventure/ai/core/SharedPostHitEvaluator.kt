@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedPostHitInput(
    val moveId: String = "",
    val moveName: String = "",
    val moveType: String = "Normal",
    val moveCategory: String = "Physical",
    val moveContact: Boolean = false,
    val movePunch: Boolean = false,
    val moveHasSecondaries: Boolean = false,
    val moveVolatileStatus: String = "",
    val bindingVolatile: Boolean = false,
    val drainNumerator: Int = 0,
    val drainDenominator: Int = 1,
    val recoilNumerator: Int = 0,
    val recoilDenominator: Int = 1,
    val landedHits: Int = 0,
    val totalDamage: Int = 0,
    val attackerHp: Int = 0,
    val attackerMaximumHp: Int = 1,
    val attackerItem: String = "",
    val attackerAbility: String = "",
    val attackerFainted: Boolean = false,
    val defenderHp: Int = 0,
    val defenderItem: String = "",
    val defenderAbility: String = "",
    val defenderStatus: String = "",
    val ignoresDefenderAbility: Boolean = false,
    val defenderItemRemovalBlocked: Boolean = false,
)

@Serializable
data class SharedPostHitResult(
    val effectiveContact: Boolean = false,
    val contactPunishment: Boolean = false,
    val drainAmount: Int = 0,
    val drainAsDamage: Boolean = false,
    val shellBellHealing: Int = 0,
    val recoilDamage: Int = 0,
    val lifeOrbDamage: Int = 0,
    val selfCost: String = "",
    val curedStatus: String = "",
    val secondaryBlockSource: String = "",
    val secondaryEffectsSuppressed: Boolean = false,
    val instructions: List<SharedPostHitInstruction> = emptyList(),
)

@Serializable
data class SharedPostHitInstruction(
    val kind: String = "",
    val target: String = "defender",
    val effect: String = "",
    val boosts: Map<String, Int> = emptyMap(),
)

object SharedPostHitEvaluator {
    fun evaluate(input: SharedPostHitInput): SharedPostHitResult {
        val attackerAbility = clean(input.attackerAbility)
        val attackerItem = clean(input.attackerItem)
        val defenderAbility = clean(input.defenderAbility)
        val defenderItem = clean(input.defenderItem)
        val moveId = clean(input.moveId)
        val dealtDamage = input.totalDamage.coerceAtLeast(0)
        val landed = input.landedHits > 0
        val effectiveContact = input.moveContact &&
            !(attackerItem == "punchingglove" && input.movePunch)
        val contactPunishment = effectiveContact && attackerItem != "protectivepads"
        val sheerForce = attackerAbility == "sheerforce" && input.moveHasSecondaries
        val drainAmount = fractionAmount(
            dealtDamage,
            input.drainNumerator,
            input.drainDenominator,
        )
        val recoilDamage = if (
            dealtDamage > 0 && input.recoilNumerator > 0 &&
            attackerAbility !in setOf("rockhead", "magicguard") && !input.attackerFainted
        ) {
            min(
                input.attackerHp.coerceAtLeast(0),
                fractionAmount(dealtDamage, input.recoilNumerator, input.recoilDenominator),
            )
        } else {
            0
        }
        val lifeOrbDamage = if (
            dealtDamage > 0 && attackerItem == "lifeorb" && attackerAbility != "magicguard" &&
            !input.attackerFainted && !sheerForce
        ) {
            min(
                input.attackerHp.coerceAtLeast(0),
                max(1, floor(input.attackerMaximumHp.coerceAtLeast(1) / 10.0).toInt()),
            )
        } else {
            0
        }
        val selfCost = when {
            !landed -> ""
            moveId in setOf("mindblown", "steelbeam") -> "half_maximum_hp"
            moveId == "finalgambit" -> "all_current_hp"
            moveId in SELF_DESTRUCT_MOVES -> "self_destruct"
            else -> ""
        }
        val curedStatus = when {
            dealtDamage <= 0 -> ""
            moveId == "sparklingaria" && clean(input.defenderStatus) == "brn" -> "brn"
            moveId == "wakeupslap" && clean(input.defenderStatus) == "slp" -> "slp"
            moveId == "smellingsalts" && clean(input.defenderStatus) == "par" -> "par"
            else -> ""
        }
        val secondaryBlockSource = when {
            dealtDamage <= 0 || input.defenderHp <= 0 || !input.moveHasSecondaries || sheerForce ||
                clean(input.moveCategory) == "status" -> ""
            defenderItem == "covertcloak" -> "covertcloak"
            defenderAbility == "shielddust" && !input.ignoresDefenderAbility -> "shielddust"
            else -> ""
        }
        return SharedPostHitResult(
            effectiveContact = effectiveContact,
            contactPunishment = contactPunishment,
            drainAmount = drainAmount,
            drainAsDamage = drainAmount > 0 && defenderAbility == "liquidooze" &&
                !input.ignoresDefenderAbility,
            shellBellHealing = if (
                dealtDamage > 0 && attackerItem == "shellbell" && !input.attackerFainted
            ) max(1, floor(dealtDamage / 8.0).toInt()) else 0,
            recoilDamage = recoilDamage,
            lifeOrbDamage = lifeOrbDamage,
            selfCost = selfCost,
            curedStatus = curedStatus,
            secondaryBlockSource = secondaryBlockSource,
            secondaryEffectsSuppressed = sheerForce || secondaryBlockSource.isNotEmpty(),
            instructions = instructions(input, moveId, landed),
        )
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedPostHitInput>(inputJson)),
    )

    fun projectedInstructions(
        moveId: String,
        attackerHp: Int,
        attackerMaximumHp: Int,
        attackerItem: String,
        attackerAbility: String,
        attackerFainted: Boolean,
        defenderItem: String,
        defenderItemRemovalBlocked: Boolean,
    ): List<SharedPostHitInstruction> {
        val input = SharedPostHitInput(
            moveId = moveId,
            landedHits = 1,
            totalDamage = 1,
            attackerHp = attackerHp,
            attackerMaximumHp = attackerMaximumHp,
            attackerItem = attackerItem,
            attackerAbility = attackerAbility,
            attackerFainted = attackerFainted,
            defenderItem = defenderItem,
            defenderItemRemovalBlocked = defenderItemRemovalBlocked,
        )
        return instructions(input, clean(moveId), landed = true)
    }

    private fun fractionAmount(value: Int, numerator: Int, denominator: Int): Int {
        if (value <= 0 || numerator <= 0 || denominator <= 0) return 0
        return max(1, floor(value.toDouble() * numerator / denominator).toInt())
    }

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }

    private fun instructions(
        input: SharedPostHitInput,
        moveId: String,
        landed: Boolean,
    ): List<SharedPostHitInstruction> {
        if (!landed) return emptyList()
        val instructions = mutableListOf<SharedPostHitInstruction>()
        fun add(
            kind: String,
            target: String = "defender",
            effect: String = "",
            boosts: Map<String, Int> = emptyMap(),
        ) {
            instructions += SharedPostHitInstruction(kind, target, effect, boosts)
        }
        when (moveId) {
            "clearsmog" -> add("reset_boosts")
            "hyperspacefury" -> add("boost", "attacker", boosts = mapOf("defence" to -1))
            "fakeout", "upperhand" -> add("volatile", effect = "flinch")
            "saltcure" -> add("volatile_if_alive", effect = "saltcure")
            "thousandwaves" -> add("volatile_if_alive", effect = "thousandwaves")
            "jawlock" -> {
                add("volatile_if_alive", effect = "jawlock")
                add("volatile", "attacker", "jawlock")
            }
            "rapidspin" -> add("clear_hazards", "attacker")
            "ceaselessedge" -> add("side_condition", effect = "spikes")
            "stoneaxe" -> add("side_condition", effect = "stealthrock")
            "doubleshock" -> add("remove_type", "attacker", "electric")
            "burnup" -> add("remove_type", "attacker", "fire")
            "smackdown", "thousandarrows" -> add("volatile_if_alive", effect = "smackdown")
            "sappyseed" -> add("leech_seed")
            "gmaxsnooze" -> add("yawn")
            "orderup" -> add("boost", "attacker", boosts = mapOf("attack" to 1))
            "relicsong", "polarflare" -> add("form_hint", "attacker")
            "fling" -> if (clean(input.attackerItem).isNotEmpty()) add("remove_attacker_item", "attacker")
            "naturalgift" -> if (clean(input.attackerItem).endsWith("berry")) {
                add("consume_attacker_berry", "attacker")
            }
            "surf", "dive" -> if (clean(input.attackerAbility) == "gulpmissile" && !input.attackerFainted) {
                add(
                    "gulp_missile",
                    "attacker",
                    if (input.attackerHp > input.attackerMaximumHp / 2) "gulping" else "gorging",
                )
            }
            "coreenforcer" -> add("suppress_ability")
            "spitup" -> add("end_stockpile", "attacker")
            "icespinner" -> add("clear_terrain")
            "steelroller" -> add("clear_terrain")
            "freezyfrost" -> add("clear_terrain_and_boosts", "both")
            "mortalspin" -> add("mortal_spin")
            "knockoff" -> if (clean(input.defenderItem).isNotEmpty()) {
                add(if (input.defenderItemRemovalBlocked) "item_removal_blocked" else "remove_defender_item")
            }
            "covet", "thief" -> if (
                clean(input.attackerItem).isEmpty() && clean(input.defenderItem).isNotEmpty()
            ) {
                add(
                    if (input.defenderItemRemovalBlocked) "item_removal_blocked" else "steal_defender_item",
                    if (input.defenderItemRemovalBlocked) "defender" else "attacker",
                )
            }
            "bugbite", "incinerate", "pluck" -> if (
                isConsumable(input.defenderItem)
            ) {
                add(
                    if (input.defenderItemRemovalBlocked) "item_removal_blocked"
                    else "remove_consumable_defender_item",
                )
            }
        }
        if (input.bindingVolatile && input.moveVolatileStatus.isNotBlank()) {
            add("volatile_if_alive", effect = input.moveVolatileStatus)
        }
        return instructions
    }

    private val SELF_DESTRUCT_MOVES = setOf(
        "explosion",
        "mistyexplosion",
        "selfdestruct",
    )

    private fun isConsumable(item: String): Boolean {
        val id = clean(item)
        return id.endsWith("berry") || id.endsWith("gem")
    }
}

@Serializable
data class SharedSecondaryRollInput(
    val chance: Double = 100.0,
    val attackerAbility: String = "",
    val blocked: Boolean = false,
    val defenderFainted: Boolean = false,
    val rngState: Long = 0,
)

@Serializable
data class SharedSecondaryRollResult(
    val triggered: Boolean = false,
    val effectiveChance: Double = 0.0,
    val rngState: Long = 0,
)

object SharedSecondaryRollEvaluator {
    fun evaluate(input: SharedSecondaryRollInput): SharedSecondaryRollResult {
        val baseChance = input.chance.coerceIn(0.0, 100.0)
        val effectiveChance = if (clean(input.attackerAbility) == "serenegrace") {
            min(100.0, baseChance * 2.0)
        } else {
            baseChance
        }
        val rng = SharedBattleRng(input.rngState, restoredState = true)
        val triggered = !input.blocked && !input.defenderFainted &&
            rng.nextDouble() * 100.0 < effectiveChance
        return SharedSecondaryRollResult(
            triggered = triggered,
            effectiveChance = effectiveChance,
            rngState = rng.snapshot(),
        )
    }

    fun evaluateJson(inputJson: String): String = codec.encodeToString(
        evaluate(codec.decodeFromString<SharedSecondaryRollInput>(inputJson)),
    )

    private fun clean(value: String?): String = value.orEmpty().lowercase()
        .substringAfterLast(':').filter { it.isLetterOrDigit() }
}

@JsExport
fun evaluateSharedPostHitJson(inputJson: String): String =
    SharedPostHitEvaluator.evaluateJson(inputJson)

@JsExport
fun rollSharedSecondaryJson(inputJson: String): String =
    SharedSecondaryRollEvaluator.evaluateJson(inputJson)
