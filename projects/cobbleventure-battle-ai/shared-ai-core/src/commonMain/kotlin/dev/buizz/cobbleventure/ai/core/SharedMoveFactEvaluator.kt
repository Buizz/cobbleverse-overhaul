@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.floor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 기존 웹 moveRuleAdjustments를 함수 단위로 흡수하는 공통 규칙 엔진이다. */
object SharedMoveFactEvaluator {
    fun adjustments(facts: RuleFactBag): List<CandidateAdjustment> = buildList {
        val moveId = cleanMoveFactId(facts.firstString("id", "moveId", "name"))
        val statusMove = facts.string("category") == "Status"
        if (statusMove) statusDisruption(facts, this)
        statusControl(facts, moveId, this)
        val oneTurnDelta = facts.firstOptionalNumber("oneTurnEvaluation.delta", "battleStateEvaluation.delta", "battleStateValueDelta")
        val oneTurnWeight = facts.firstNumber("oneTurnSearchWeight", default = 0.35).coerceAtLeast(0.0)
        if (oneTurnDelta != null && oneTurnWeight > 0.0) add(ruleMove("simulation.one_turn_state_value", jsRound2(oneTurnDelta * oneTurnWeight)))
        val pressure = facts.number("stayPressurePenalty").coerceAtLeast(0.0)
        if (pressure > 0.0) add(ruleMove("rule.action.switch_cleared_pressure", -pressure))
        val damageMove = facts.flag("computed.isDamage")
        val beforeKo = facts.number("opponentKnockoutBeforeActionProbability").coerceIn(0.0, 1.0)
        if (damageMove && beforeKo >= 0.25) add(ruleMove(
            "rule.action.ko_before_acting",
            if (beforeKo >= 0.75) -520.0 else if (beforeKo >= 0.5) -280.0 else -120.0,
        ))
        if (moveId == "upperhand") upperHand(facts, this)
        if (moveId in setOf("suckerpunch", "thunderclap") && facts.flag("conditionalPriorityRepeatFailure")) {
            add(ruleMove("rule.conditional_priority.repeat_failure", facts.firstNumber("conditionalPriorityAdaptPenalty", default = -2000.0)))
        }
        basicResourceRules(facts, moveId, damageMove, this)
        hazardRules(facts, moveId, this)
        saltCureRules(facts, moveId, this)
        residualStatusRules(facts, this)
        trickRoomRules(facts, moveId, this)
        batonPassRules(facts, moveId, this)
        protectRules(facts, moveId, this)
        setupRules(facts, this)
        focusAndSturdyRules(facts, this)
        setupDisruptionRules(facts, moveId, this)
        setupThreatRules(facts, moveId, damageMove, this)
        addAll(existingResourceRules(facts))
    }

    fun adjustmentsJson(inputJson: String): String = codec.encodeToString(
        adjustments(codec.decodeFromString<RuleFactBag>(inputJson)),
    )

    private fun statusDisruption(facts: RuleFactBag, target: MutableList<CandidateAdjustment>) = with(target) {
        val damage = facts.firstNumber("disruptionThreeTurnDamageRatio", default = 3.0).coerceAtLeast(0.0)
        val survives = facts.flag("disruptionCanSurviveThreeTurns") || damage < 1.0
        val defensive = facts.flag("disruptionDefensiveSetup")
        val reduction = facts.number("disruptionDefensiveDamageReduction").coerceAtLeast(0.0)
        val escape = facts.flag("disruptionSwitchEscapeAvailable")
        val discount = ((if (survives) 0.45 else 0.0) + (if (defensive && reduction > 0.0) 0.18 else 0.0) + (if (escape) 0.1 else 0.0)).coerceAtMost(0.78)
        val exactTaunt = facts.number("exactTauntRisk").coerceIn(0.0, 1.0)
        val exactEncore = facts.number("exactEncoreRisk").coerceIn(0.0, 1.0)
        val exact = maxOf(exactTaunt, exactEncore)
        if (exact > 0.0) {
            val taunt = exactTaunt >= exactEncore
            val tauntFirst = taunt && facts.number("opponentDisruptionActsBeforeProbability") >= 1.0
            val adjusted = exact * if (tauntFirst) 1.0 else 1.0 - discount
            add(ruleMove("rule.status_disruption.exact_${if (taunt) "taunt" else "encore"}", -jsRound(adjusted * 700.0)))
        } else {
            val taunt = facts.number("opponentTauntRisk").coerceIn(0.0, 1.0)
            val encore = facts.number("opponentEncoreRisk").coerceIn(0.0, 1.0)
            if (taunt > 0.0) add(ruleMove("rule.status_disruption.taunt_risk", -jsRound(taunt * (1.0 - discount) * 90.0)))
            if (encore > 0.0) add(ruleMove("rule.status_disruption.encore_risk", -jsRound(encore * (1.0 - discount) * 75.0)))
        }
    }

    private fun statusControl(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        val observed = facts.optionalNumber("statusControlTargetStatusMoveCount") != null || facts.optionalFlag("encoreTargetValid") != null
        if (!observed || moveId !in setOf("taunt", "encore")) return
        val affected = facts.flag("statusControlTargetAlreadyAffected")
        val survives = facts.flag("statusControlCanSurviveThreeTurns")
        val damage = facts.firstNumber("statusControlThreeTurnDamageRatio", default = 3.0).coerceAtLeast(0.0)
        val hazards = facts.number("statusControlSwitchHazardLayers").coerceAtLeast(0.0)
        if (affected) {
            add(ruleMove("rule.status_control.${moveId}_already_active", -1000.0)); return
        }
        if (moveId == "taunt") {
            val count = facts.number("statusControlTargetStatusMoveCount").coerceAtLeast(0.0)
            if (count <= 0.0) {
                add(ruleMove("rule.status_control.taunt_no_target", -1000.0)); return
            }
            val ratio = facts.number("statusControlTargetStatusMoveRatio").coerceIn(0.0, 1.0)
            val value = facts.number("statusControlTargetValue").coerceIn(0.0, 1.0)
            val confidence = facts.number("tauntPreventionConfidence").coerceIn(0.0, 1.0)
            add(ruleMove("rule.status_control.taunt_lock", jsRound(12.0 + ratio * 30.0 + value * 34.0 + confidence * 75.0)))
            if (!survives && confidence < 1.0) add(ruleMove("rule.status_control.taunt_short_life", -minOf(120.0, jsRound(45.0 + maxOf(0.0, damage - 1.0) * 65.0))))
            return
        }
        if (!facts.flag("encoreTargetValid")) {
            add(ruleMove("rule.status_control.encore_no_target", -1000.0)); return
        }
        val confidence = facts.number("encoreExactTargetConfidence").coerceIn(0.0, 1.0)
        if (facts.flag("encoreTargetIsStatus")) {
            val value = facts.number("encoreTargetStatusValue").coerceIn(0.0, 1.0)
            add(ruleMove("rule.status_control.encore_status_lock", jsRound(38.0 + value * 72.0 + confidence * 70.0 + minOf(18.0, hazards * 6.0))))
        } else {
            val ratio = facts.firstNumber("encoreTargetDamageRatio", default = 1.0).coerceAtLeast(0.0)
            add(ruleMove("rule.status_control.encore_attack_lock", if (ratio <= 0.2) 52.0 else if (ratio <= 0.35) 24.0 else if (ratio >= 0.65) -130.0 else if (ratio >= 0.5) -75.0 else -18.0))
        }
        if (!survives) add(ruleMove("rule.status_control.encore_short_life", -minOf(110.0, jsRound(35.0 + maxOf(0.0, damage - 1.0) * 55.0))))
    }

    private fun upperHand(facts: RuleFactBag, target: MutableList<CandidateAdjustment>) = with(target) {
        when (facts.string("upperHandExactOutcome")) {
            "failure" -> add(ruleMove("rule.upper_hand.exact_failure", -2000.0))
            "success" -> add(ruleMove("rule.upper_hand.exact_success", 90.0))
            else -> {
                val probability = facts.number("upperHandSuccessProbability").coerceIn(0.0, 1.0)
                if (probability <= 0.0) add(ruleMove("rule.upper_hand.no_valid_target", -1200.0))
                else add(ruleMove("rule.upper_hand.predicted_priority", jsRound2(-140.0 * (1.0 - probability) + 70.0 * probability)))
            }
        }
    }

    private fun basicResourceRules(
        facts: RuleFactBag,
        moveId: String,
        damageMove: Boolean,
        target: MutableList<CandidateAdjustment>,
    ) = with(target) {
        if (facts.flag("selfBoostAlreadyMaxed")) add(ruleMove("rule.setup.all_boosts_maxed", -1000.0))
        if (facts.flag("computed.hasSafeImmediateKo") && !facts.flag("computed.safeFinisher")) {
            add(ruleMove(
                if (damageMove) "rule.immediate_ko_attack_preference" else "rule.immediate_ko_dominance",
                if (facts.flag("computed.highValueHazard")) -12.0 else if (damageMove) -10.0 else -80.0,
            ))
        }
        if (facts.string("koChance") == "guaranteed" && facts.flag("computed.actsBefore") && facts.number("computed.setupThreatTier") >= 2.0) {
            add(ruleMove("rule.immediate_ko_response", 4.0))
        }
        val selfDrop = facts.number("selfDropTotal").coerceAtLeast(0.0)
        if (selfDrop > 0.0) {
            val safe = facts.flag("safeNoDropKoAvailable") || facts.flag("safeNoDropFinisherAvailable")
            val guaranteed = facts.string("koChance") == "guaranteed"
            add(ruleMove(
                if (guaranteed && safe) "rule.self_drop.safe_ko_alternative" else "rule.self_drop.stat_cost",
                if (guaranteed && safe) -95.0 - selfDrop * 8.0 else -minOf(30.0, selfDrop * 6.0),
            ))
        }
        val recoil = facts.number("expectedRecoilDamage")
        if (recoil > 0.0) {
            val faints = facts.flag("recoilWouldFaint")
            val safe = facts.flag("safeNoRecoilKoAvailable")
            add(ruleMove(
                if (faints && safe) "rule.recoil.safe_ko_alternative" else if (faints) "rule.recoil.necessary_trade" else "rule.recoil.hp_cost",
                if (faints && safe) -140.0 else if (faints) -12.0 else -minOf(36.0, maxOf(4.0, recoil * 0.35)),
            ))
        }
    }

    private fun hazardRules(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        val maximum = mapOf("stealthrock" to 1.0, "stickyweb" to 1.0, "spikes" to 3.0, "toxicspikes" to 2.0)[moveId] ?: return@with
        if (!facts.tags.contains("hazardset")) return@with
        val layers = facts.firstNumber("existingHazardLayers", "opponentHazards.$moveId", "field.opponentHazards.$moveId", default = 0.0).coerceAtLeast(0.0)
        if (cleanMoveFactId(facts.string("opponentAbility")) == "magicbounce") {
            add(ruleMove("rule.entry_hazard.magic_bounce", -30.0)); return@with
        }
        if (layers >= maximum) {
            add(ruleMove("rule.entry_hazard.already_maxed", -180.0)); return@with
        }
        val incoming = facts.firstOptionalNumber("opponentMaxDamageToCurrentHealthRatio", "incomingDamageRatio")
        val hp = facts.firstNumber("hpPercent", default = 1.0)
        val opponents = facts.firstNumber("livingOpponents", default = 2.0).coerceAtLeast(0.0)
        val highValueDespiteKo = facts.flag("immediateKoAvailable") && moveId == "stealthrock" && opponents >= 3.0
        if ((!facts.flag("immediateKoAvailable") || highValueDespiteKo) && (facts.flag("computed.actsBefore") || incoming == null || incoming < hp)) {
            if (opponents > 1.0) add(ruleMove("rule.entry_hazard.team_value", 12.0 + 2.0 * minOf(6.0, opponents)))
            val turn = facts.firstNumber("turn", default = 1.0).coerceAtLeast(1.0)
            if (moveId == "stealthrock" && opponents >= 3.0) {
                add(ruleMove("rule.entry_hazard.stealth_rock_pressure", 18.0 + minOf(6.0, opponents) * 8.0 + if (turn <= 2.0 && opponents >= 4.0) 10.0 else 0.0))
            }
            if (moveId == "stealthrock" && turn <= 2.0 && opponents >= 4.0) add(ruleMove("rule.entry_hazard.early_stealth_rock", 42.0))
        } else add(ruleMove("rule.entry_hazard.cannot_set", -30.0))
    }

    private fun trickRoomRules(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        if (moveId != "trickroom") return@with
        val active = facts.flag("trickRoomActive") || facts.flag("field.pseudoWeather.trickroom")
        val survives = facts.optionalFlag("canSurviveToSetRoom") ?: (facts.firstNumber("incomingDamageRatio", default = 0.0) < 1.0)
        val aces = facts.number("slowAceCount").coerceAtLeast(0.0)
        val advantage = facts.number("trickRoomAdvantage")
        val hp = facts.firstNumber("hpPercent", "healthRatio", default = 1.0)
        when {
            active && !facts.flag("shouldReverseTrickRoom") -> add(ruleMove("rule.trick_room.already_active", -160.0))
            !survives -> add(ruleMove("rule.trick_room.cannot_survive", -90.0))
            advantage > 0.0 || aces > 0.0 -> add(ruleMove("rule.trick_room.slow_ace_plan", 55.0 + advantage.coerceIn(0.0, 60.0 / 22.0) * 22.0 + minOf(48.0, aces * 18.0) + (if (facts.flag("activeIsSlower")) 18.0 else 0.0) + (if (hp <= 0.45) 18.0 else 0.0)))
            facts.flag("activeIsFaster") || advantage < 0.0 -> add(ruleMove("rule.trick_room.bad_speed_context", -70.0))
        }
    }

    private fun saltCureRules(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        if (moveId != "saltcure") return@with
        if (facts.flag("computed.saltCureActive") || facts.flag("opponentVolatiles.saltcure")) {
            add(ruleMove("rule.salt_cure.already_active", -45.0)); return@with
        }
        if (facts.flag("immediateKoAvailable")) return@with
        val maxHp = facts.firstNumber("opponentMaxHp", "opponentHp", default = 0.0)
        val residual = facts.optionalNumber("saltCureResidualDamage") ?: maxHp / 8.0
        val survival = facts.firstNumber("expectedSurvivalTurns", "survivalTurns", "turnsCanSurvive", default = 1.0).coerceIn(1.0, 6.0)
        val hp = facts.firstNumber("opponentHp", "opponentMaxHp", default = 0.0)
        val opponentTurns = if (residual > 0.0 && hp > 0.0) kotlin.math.ceil(hp / residual).coerceIn(1.0, 6.0) else survival
        val pressureTurns = maxOf(survival, opponentTurns)
        val opponents = facts.firstNumber("livingOpponents", default = 2.0).coerceAtLeast(0.0)
        val earlyRock = facts.number("opponentHazards.stealthrock") <= 0.0 && facts.firstNumber("turn", default = 1.0) <= 2.0 && opponents >= 4.0
        val ace = facts.flag("opponentAceQualified") || facts.number("opponentAceScore") >= 5.8 || facts.flag("opponentIsAce")
        val boosts = facts.number("computed.opponentPositiveBoosts").coerceAtLeast(0.0)
        val tier = facts.number("computed.setupThreatTier")
        val likelihood = facts.number("computed.opponentSetupLikelihood").coerceIn(0.0, 1.0)
        val likelyFirst = facts.flag("opponentLikelyFirstTurnSetup") || (facts.firstNumber("turn", default = 1.0) <= 2.0 && facts.number("opponentSetupMoveCount") > 0.0 && likelihood >= 0.65)
        val incoming = facts.firstOptionalNumber("currentIncomingDamageRatio", "opponentMaxDamageToCurrentHealthRatio", "incomingDamageRatio")
        val urgent = ace || likelyFirst || tier >= 3.0 || boosts >= 2.0 || facts.flag("opponentCanSweep") || facts.flag("oneMoreTurnUnmanageable")
        val dot = minOf(if (urgent) 185.0 else 135.0, jsRound2(maxOf(0.0, residual) * pressureTurns * 0.68))
        val pressure = (if (ace) 24.0 else 0.0) + (if (likelyFirst) 58.0 else if (tier >= 3.0) 36.0 else if (tier >= 2.0) 18.0 else 0.0) + minOf(36.0, boosts * 12.0) + (if (incoming != null && incoming >= 0.5) 20.0 else 0.0)
        val weight = if (earlyRock && !urgent) minOf(34.0, 22.0 + dot) else 22.0 + dot + pressure
        add(ruleMove("rule.salt_cure.persistent_pressure", jsRound2(weight)))
    }

    private fun residualStatusRules(facts: RuleFactBag, target: MutableList<CandidateAdjustment>) = with(target) {
        if (cleanMoveFactId(facts.firstString("opponentStatus", "targetStatus")).isNotEmpty() || facts.flag("statusBlocked")) return@with
        val candidates = mutableListOf<Pair<String, Double>>()
        fun addStatus(status: String, chance: Double) {
            val id = cleanMoveFactId(status)
            if (id in setOf("tox", "toxic", "badlypoisoned", "psn", "poison", "brn", "burn")) candidates += id to chance.coerceIn(0.0, 100.0)
        }
        val explicitLength = facts.optionalNumber("statusResidualCandidates.length")?.toInt()
        if (explicitLength != null) {
            repeat(explicitLength) { index -> addStatus(facts.string("statusResidualCandidates.$index.status"), facts.firstNumber("statusResidualCandidates.$index.chance", default = 100.0)) }
        } else {
            addStatus(facts.string("status"), 100.0)
            val secondaryLength = facts.firstNumber("secondaries.length", default = 0.0).toInt()
            repeat(secondaryLength) { index -> addStatus(facts.string("secondaries.$index.status"), facts.firstNumber("secondaries.$index.chance", default = 100.0)) }
        }
        if (candidates.isEmpty()) return@with
        val maxHp = facts.firstNumber("opponentMaxHp", "opponentHp", default = 0.0)
        val turns = facts.firstNumber("expectedSurvivalTurns", "survivalTurns", "turnsCanSurvive", default = 1.0).coerceIn(1.0, 6.0)
        var best = 0.0
        for ((status, chancePercent) in candidates) {
            val chance = chancePercent / 100.0
            val toxic = status in setOf("tox", "toxic", "badlypoisoned")
            val poison = status in setOf("psn", "poison")
            val burn = status in setOf("brn", "burn")
            val residual = if (toxic) (maxHp / 16.0) * ((turns * (turns + 1.0)) / 2.0) else if (poison) (maxHp / 8.0) * turns else if (burn) (maxHp / 16.0) * turns else 0.0
            val utility = if (burn) 10.0 * turns else if (toxic) 4.0 * turns else 0.0
            best = maxOf(best, minOf(95.0, (residual * 0.5 + utility) * chance))
        }
        if (best > 0.0) add(ruleMove("rule.status_residual.expected_value", jsRound2(best)))
    }

    private fun batonPassRules(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        val gain = facts.number("batonPassAdditionalBoostTotal").coerceAtLeast(0.0)
        val survival = facts.number("setupFollowupSurvivalProbability").coerceIn(0.0, 1.0)
        if (moveId != "batonpass" && facts.flag("batonPassTargetAvailable") && gain > 0.0 && survival >= 0.65) {
            add(ruleMove("rule.baton_pass.setup_for_ace", jsRound2(minOf(180.0, 70.0 + gain * 24.0 + facts.number("batonPassNewKoTargets").coerceAtLeast(0.0) * 42.0 + facts.number("batonPassPressureGain").coerceAtLeast(0.0) * 28.0))))
        }
        val sweep = facts.firstNumber("batonPassCurrentSweepBoostTotal", "batonPassCurrentBoostTotal", default = 0.0)
        val defense = facts.number("batonPassCurrentDefensiveBoostTotal")
        val safe = survival >= 0.85 && facts.firstNumber("incomingDamageRatio", default = 1.0) <= 0.35 && facts.number("opponentKnockoutBeforeActionProbability") < 0.2
        val development = (sweep < 6.0 && facts.flag("batonPassCanRaiseSweepFurther")) || (defense < 2.0 && facts.flag("batonPassCanRaiseDefenseFurther"))
        val ready = facts.flag("batonPassTargetAvailable") && facts.flag("batonPassTargetAce") && sweep >= 3.0 && facts.number("batonPassNewKoTargets") >= 2.0 && (!safe || (sweep >= 6.0 && defense >= 2.0))
        if (moveId != "batonpass" && ready && (gain > 0.0 || facts.tags.contains("setupboost") || facts.number("effectiveSelfBoostTotal") > 0.0)) add(ruleMove("rule.baton_pass.ready_to_transfer", -220.0))
        if (moveId == "batonpass" && safe && development) add(ruleMove("rule.baton_pass.safe_development_remaining", -90.0))
        if (moveId != "batonpass") return@with
        val boosts = facts.firstNumber("batonPassCurrentBoostTotal", "batonPassBoostTotal", default = 0.0).coerceAtLeast(0.0)
        when {
            !facts.flag("batonPassTargetAvailable") || !facts.flag("batonPassTargetAce") -> add(ruleMove("rule.baton_pass.no_ace_target", -180.0))
            boosts <= 0.0 -> add(ruleMove("rule.baton_pass.no_boosts", -150.0))
            facts.number("opponentKnockoutBeforeActionProbability") >= 0.75 -> add(ruleMove("rule.baton_pass.ko_before_pass", -420.0))
            else -> {
                val followup = facts.firstNumber("setupFollowupSurvivalProbability", default = 1.0).coerceIn(0.0, 1.0)
                val urgent = followup < 0.55 || facts.number("incomingDamageRatio") >= facts.firstNumber("hpPercent", default = 1.0)
                val weight = minOf(260.0, 55.0 + facts.number("batonPassTransferValue").coerceAtLeast(0.0) * 0.75 + (if (urgent) 70.0 else 0.0))
                add(ruleMove(if (urgent) "rule.baton_pass.pass_before_faint" else "rule.baton_pass.transfer_to_ace", jsRound2(weight)))
            }
        }
    }

    private fun protectRules(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        val protect = setOf("protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "burningbulwark", "obstruct", "silktrap", "endure", "maxguard")
        val probability = facts.firstNumber("protectSuccessProbability", default = 1.0).coerceIn(0.0, 1.0)
        if (moveId in protect && probability < 1.0) add(ruleMove("rule.protect.consecutive_failure_risk", jsRound((1.0 - probability) * -21000.0) / 100.0))
    }

    private fun setupRules(facts: RuleFactBag, target: MutableList<CandidateAdjustment>) = with(target) {
        if (!facts.tags.contains("setupboost")) return@with
        val incoming = facts.firstOptionalNumber("opponentMaxDamageToCurrentHealthRatio", "incomingDamageRatio")
        val setupIncoming = facts.firstOptionalNumber("setupIncomingDamageRatioAfterBoost") ?: incoming
        val followup = facts.optionalNumber("setupFollowupSurvivalProbability") ?: if (facts.optionalFlag("setupCanSurviveIncoming") == false) 0.0 else 1.0
        val survives = facts.optionalFlag("setupCanSurviveIncoming") != false && followup >= 0.5
        val assured = survives && facts.number("setupGuardConsumptionProbability") < 0.25 && (setupIncoming == null || setupIncoming < 0.5)
        val priorityLikelihood = facts.number("opponentConditionalPriorityLikelihood").coerceIn(0.0, 0.85)
        val priorityKo = facts.number("opponentConditionalPriorityKnockoutProbability").coerceIn(0.0, 1.0)
        val effective = facts.firstNumber("effectiveSelfBoostTotal", "setupEffectiveBoostTotal", default = 1.0) > 0.0
        val revealed = facts.stringLists["opponentRevealedSetupResetMoveIds"].orEmpty()
        val active = facts.stringLists["opponentActiveRevealedSetupResetMoveIds"].orEmpty()
        if (effective && revealed.isNotEmpty()) add(ruleMove("rule.setup.revealed_boost_reset", if (active.isNotEmpty()) -240.0 else -100.0))
        if (priorityLikelihood >= 0.25 && effective && survives) add(ruleMove("rule.setup.conditional_priority_bait", jsRound2(priorityLikelihood * (42.0 + priorityKo * 38.0))))
        if (facts.flag("reliableKoAlternative") && !assured) add(ruleMove(if (facts.flag("computed.knockoutBoostAlternative")) "rule.setup.foregoes_ko_boost" else "rule.setup.foregoes_safe_ko", if (facts.flag("computed.knockoutBoostAlternative")) -260.0 else -180.0))
        if (facts.firstNumber("turn", default = 2.0) == 1.0 && !facts.flag("opponentActionKnown")) add(ruleMove("rule.setup.first_turn_unknown", -2.0))
        if (!survives) {
            add(ruleMove("rule.setup.cannot_reach_followup", if (followup <= 0.05) -360.0 else if (followup < 0.25) -280.0 else -210.0))
            add(ruleMove("rule.setup.cannot_survive_turn", -220.0))
        } else if (incoming != null) {
            val bonus = if (incoming <= 0.1) 18.0 else if (incoming <= 0.2) 16.0 else if (incoming <= 1.0 / 3.0) 10.0 else if (incoming >= 1.0) -20.0 else if (incoming >= 0.5) -10.0 else 0.0
            if (bonus != 0.0) add(ruleMove(if (bonus > 0.0) "rule.setup.safe_turn" else "rule.setup.damage_risk", bonus))
        }
        val currentDamage = facts.number("setupCurrentBestDamage")
        val boostedDamage = facts.number("setupBoostedBestDamage")
        val improvement = facts.optionalNumber("setupDamageImprovement") ?: maxOf(0.0, boostedDamage - currentDamage)
        val boostTotal = facts.firstNumber("setupEffectiveBoostTotal", default = 1.0).coerceAtLeast(0.0)
        val newKo = facts.number("setupNewKoTargets").coerceAtLeast(0.0)
        val futureKo = facts.number("setupFutureNewKoTargets").coerceAtLeast(0.0)
        val speed = facts.number("setupNewSpeedAdvantages").coerceAtLeast(0.0)
        val futurePressure = facts.number("setupFuturePressureGain").coerceAtLeast(0.0)
        val currentPressure = facts.number("setupCurrentPressureGain").coerceAtLeast(0.0)
        val profile = facts.optionalNumber("setupLivingTargetCount") != null || facts.optionalNumber("setupEffectiveBoostTotal") != null
        val gain = newKo + speed * 0.65 + futurePressure + currentPressure * 0.6
        if (profile && (facts.flag("setupBoostAlreadyMaxed") || boostTotal <= 0.0)) add(ruleMove("rule.setup.boost_already_maxed", -260.0))
        else if (profile && gain <= 0.01) add(ruleMove("rule.setup.no_matchup_gain", -190.0))
        else if (survives && (if (profile) gain > 0.01 else improvement > 0.0)) {
            val safe = incoming == null || incoming < 0.5
            val koImproved = facts.flag("setupKoAfterBoost") && !facts.flag("setupKoBeforeBoost")
            val hp = facts.optionalNumber("opponentHp")
            val hpBonus = if (hp != null && hp != 0.0 && boostedDamage >= hp * 0.75) 30.0 else 0.0
            val weight = if (profile) {
                minOf(120.0, improvement * 0.55) +
                    minOf(150.0, newKo * 55.0 + futureKo * 20.0) +
                    minOf(70.0, futurePressure * 45.0) +
                    minOf(50.0, speed * 25.0) +
                    (if (koImproved) if (safe) 55.0 else 25.0 else 0.0) + hpBonus
            } else {
                minOf(120.0, improvement * 0.55) +
                    (if (koImproved) if (safe) 245.0 else 105.0 else 0.0) + hpBonus
            }
            add(ruleMove("rule.setup.team_sweep_plan", jsRound2(weight)))
        }
    }

    private fun focusAndSturdyRules(facts: RuleFactBag, target: MutableList<CandidateAdjustment>) = with(target) {
        when {
            facts.flag("focusSashBlocked") -> add(ruleMove("rule.focus_sash.single_hit_blocked", -90.0))
            facts.flag("sturdyBlocked") -> add(ruleMove("rule.sturdy.single_hit_blocked", -90.0))
            facts.flag("breaksFocusSash") -> add(ruleMove("rule.focus_sash.multi_hit_breaker", 55.0))
            facts.flag("breaksSturdy") || facts.flag("sturdyBreaker") -> add(ruleMove("rule.sturdy.multi_hit_breaker", 55.0))
        }
    }

    private fun setupDisruptionRules(facts: RuleFactBag, moveId: String, target: MutableList<CandidateAdjustment>) = with(target) {
        val boosts = facts.number("computed.opponentPositiveBoosts").coerceAtLeast(0.0)
        if (moveId == "haze") add(ruleMove(if (boosts <= 0.0) "rule.haze.no_opponent_boosts" else "rule.haze.immediate_boost_reset", if (boosts <= 0.0) -1000.0 else 240.0 + minOf(6.0, boosts) * 40.0))
        val tier = facts.number("computed.setupThreatTier")
        if (tier < 2.0) return@with
        when {
            moveId in setOf("haze", "clearsmog") -> add(ruleMove("rule.setup_disruption.boost_reset", if (tier >= 3.0) 17.0 else 13.0))
            moveId in setOf("roar", "whirlwind", "dragontail", "circlethrow") -> add(ruleMove("rule.setup_disruption.phaze", (if (tier >= 3.0) 16.0 else 12.0) + facts.number("opponentHazardLayers").coerceIn(0.0, 3.0)))
            moveId == "taunt" && !facts.flag("opponentAlreadyBoosted") -> add(ruleMove("rule.setup_disruption.taunt", if (tier >= 3.0) 12.0 else 8.0))
            moveId == "taunt" -> add(ruleMove("rule.setup_disruption.late_taunt", if (tier >= 3.0) -16.0 else -12.0))
        }
    }

    private fun setupThreatRules(
        facts: RuleFactBag,
        moveId: String,
        damageMove: Boolean,
        target: MutableList<CandidateAdjustment>,
    ) = with(target) {
        val canSetup = facts.firstFlag("setupThreatEvaluation.opponentCanSetup", "opponentSetupThreatEvaluation.opponentCanSetup")
        val sweepRisk = facts.firstNumber("setupThreatEvaluation.sweepRiskAfterSetup", "opponentSetupThreatEvaluation.sweepRiskAfterSetup", "opponentSetupSweepRisk", default = 0.0).coerceIn(0.0, 1.0)
        if (!canSetup || sweepRisk < 0.22) return@with
        val reset = moveId in setOf("haze", "clearsmog")
        val phaze = moveId in setOf("roar", "whirlwind", "dragontail", "circlethrow")
        val taunt = moveId == "taunt"
        val status = cleanMoveFactId(facts.string("status"))
        var secondaryPunish = false
        val secondaryLength = facts.firstNumber("secondaries.length", default = 0.0).toInt()
        repeat(secondaryLength) { index ->
            if (cleanMoveFactId(facts.string("secondaries.$index.status")) in setOf("brn", "par", "slp") && facts.firstNumber("secondaries.$index.chance", default = 100.0) >= 60.0) secondaryPunish = true
        }
        val guaranteed = facts.string("koChance") == "guaranteed"
        val immediate = facts.flag("immediateKoBeforeOpponent")
        val punish = reset || phaze || taunt || moveId == "encore" || guaranteed || immediate || status in setOf("brn", "par", "slp") || secondaryPunish
        val freeTurn = facts.firstNumber("setupThreatEvaluation.freeTurnPenalty", "opponentSetupThreatEvaluation.freeTurnPenalty", default = 0.0)
        if (punish && !guaranteed && !immediate) {
            add(ruleMove("rule.setup_threat.punish_option", jsRound2(maxOf(12.0, freeTurn * 0.85))))
            return@with
        }
        if (facts.flag("computed.recoveryMove") || punish) return@with
        val multiplier = if (!damageMove) {
            if (facts.tags.contains("setupboost")) 0.45 else if (facts.tags.contains("hazardset")) 1.0 else 0.8
        } else {
            val hp = maxOf(1.0, facts.firstNumber("opponentHp", default = 1.0))
            if (facts.number("expectedDamage") / hp < 0.2) 0.45 else 0.0
        }
        if (multiplier <= 0.0) return@with
        val penalty = -jsRound(freeTurn * multiplier * 100.0) / 100.0
        if (penalty < 0.0) add(ruleMove(if (facts.tags.contains("hazardset")) "rule.setup_threat.free_hazard_turn" else if (facts.tags.contains("setupboost")) "rule.setup_threat.setup_race" else "rule.setup_threat.free_turn", penalty))
    }

    private fun existingResourceRules(facts: RuleFactBag): List<CandidateAdjustment> = SharedMoveRuleEvaluator.adjustments(
        MoveRuleInput(
            hasSafeImmediateKo = facts.flag("computed.hasSafeImmediateKo"),
            safeFinisher = facts.flag("computed.safeFinisher"),
            damageMove = facts.flag("computed.isDamage"),
            highValueHazard = facts.flag("computed.highValueHazard"),
            guaranteedKo = facts.string("koChance") == "guaranteed",
            actsBefore = facts.flag("computed.actsBefore"),
            setupThreatTier = facts.number("computed.setupThreatTier").toInt(),
            recoveryMove = facts.flag("computed.recoveryMove"),
            hpRatio = facts.firstNumber("hpPercent", "healthRatio", "currentHpRatio", default = 1.0),
            incomingDamageRatio = facts.firstOptionalNumber("currentIncomingDamageRatio", "opponentMaxDamageToCurrentHealthRatio", "incomingDamageRatio"),
            hasCurrentStatus = facts.string("currentStatus").isNotEmpty() || facts.string("status").isNotEmpty(),
            recoveryAmount = facts.optionalNumber("recoveryAmount"),
            recoveryExpectedIncomingDamage = facts.optionalNumber("recoveryExpectedIncomingDamage"),
            recoveryNetHpChange = facts.optionalNumber("recoveryNetHpChange"),
            recoveryExposureTurns = facts.firstNumber("recoveryExposureTurns", default = if (cleanMoveFactId(facts.firstString("id", "moveId", "name")) == "rest") 3.0 else 1.0).toInt(),
            recoveryBeforeActionKoRisk = facts.firstNumber("recoveryBeforeActionKoRisk", "opponentKnockoutBeforeActionProbability", default = 0.0),
            opponentLikelyToSetup = facts.flag("computed.opponentLikelyToSetup"),
            opponentSetupLikelihood = facts.number("computed.opponentSetupLikelihood"),
            setupFreeTurnPenalty = facts.firstNumber("setupThreatEvaluation.freeTurnPenalty", "opponentSetupThreatEvaluation.freeTurnPenalty", default = 0.0),
            pivotMove = facts.flag("computed.pivotMove"),
            partingShot = cleanMoveFactId(facts.firstString("id", "moveId", "name")) == "partingshot",
            hasLivingBench = facts.optionalFlag("computed.hasLivingBench"),
            forceSwitch = facts.flag("forceSwitch"),
            survivalProbability = facts.optionalNumber("survivalProbability") ?: if (facts.flag("computed.actsBefore")) 1.0 else null,
            safePivot = facts.flag("safePivot"),
            selfSacrifice = facts.flag("computed.selfSacrifice"),
            opponentHp = facts.optionalNumber("opponentHp"),
            expectedDamage = facts.number("expectedDamage"),
            meaningfulSacrificeDamage = facts.flag("meaningfulSacrificeDamage"),
            activeRoleScore = facts.firstOptionalNumber("activeRoleScore", "userRoleScore"),
            expendableResource = facts.flag("expendableResource"),
            roleComplete = facts.flag("roleComplete"),
            mustPreserveResource = facts.flag("mustPreserveResource"),
        ),
    )
}

@JsExport
fun evaluateMoveRuleFactsJson(inputJson: String): String = SharedMoveFactEvaluator.adjustmentsJson(inputJson)

private fun RuleFactBag.firstString(vararg keys: String): String = keys.firstNotNullOfOrNull { strings[it] } ?: ""
private fun ruleMove(code: String, weight: Double) = CandidateAdjustment(code, weight)
private fun jsRound(value: Double): Double = floor(value + 0.5)
private fun jsRound2(value: Double): Double = floor(value * 100.0 + 0.5) / 100.0
private fun cleanMoveFactId(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
