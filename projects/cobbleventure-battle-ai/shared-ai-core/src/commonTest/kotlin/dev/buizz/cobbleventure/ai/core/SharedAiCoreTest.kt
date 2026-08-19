package dev.buizz.cobbleventure.ai.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedAiCoreTest {
    @Test
    fun candidateScoringIsSharedByJvmAndJavaScript() {
        val move = SharedCandidateEvaluator.score(
            CandidateScoreInput(
                difficulty = "expert_search",
                strategy = "aggressive",
                expectedDamage = 80.0,
                accuracy = 0.9,
                priority = 1.0,
                tacticalValue = 5.0,
                roleValue = 3.0,
                koChance = "possible",
                adjustments = listOf(CandidateAdjustment("fixture", -2.0)),
            ),
        )
        val switch = SharedCandidateEvaluator.score(
            CandidateScoreInput(
                kind = "switch",
                expectedDamage = 30.0,
                matchupValue = 10.0,
                hpRatio = 0.8,
                adjustments = listOf(CandidateAdjustment("fixture", -5.0)),
            ),
        )

        assertEquals(126.9, move.score, 0.0000001)
        assertEquals(43.0, switch.score, 0.0000001)

        val observedMove = SharedCandidateEvaluator.score(
            CandidateScoreFacts(
                difficulty = "expert_search",
                strategy = "aggressive",
                expectedDamage = 80.0,
                accuracyPercent = 90.0,
                priority = 1.0,
                tacticalValue = 5.0,
                roleValue = 3.0,
                koChance = "possible",
                adjustments = listOf(CandidateAdjustment("fixture", -2.0)),
            ),
        )
        assertEquals(move, observedMove)

        val item = SharedCandidateEvaluator.scoreTrainerItem(
            TrainerItemCandidateFacts(
                healing = 50.0,
                curedStatusValue = 70.0,
                preventsImmediateKnockout = true,
                incomingDamage = 30.0,
                futureRoleValue = 20.0,
                resourceCost = 10.0,
                strongMoveAvailable = true,
            ),
        )
        assertEquals(205.0, item.score, 0.0000001)
    }

    @Test
    fun battleValueObservationIsAggregatedByTheSharedCore() {
        val side = SharedBattleObservation.valueSide(
            BattleValueSideInput(
                members = listOf(
                    BattleValueMemberInput(0.75, true, true, 2.0, 0.0, true),
                    BattleValueMemberInput(0.0, false, false, 5.0, 3.0, true),
                    BattleValueMemberInput(0.5, true, false, 1.0, 1.5, false),
                ),
                hazardLayers = 2.0,
                gimmicksRemaining = 1.0,
                matchupCoverage = 0.8,
            ),
        )

        assertEquals(3.0, side.teamSize)
        assertEquals(2.0, side.livingCount)
        assertEquals(1.25, side.totalHpRatio)
        assertEquals(3.0, side.positiveBoosts)
        assertEquals(1.0, side.uniqueCountersAlive)
    }

    @Test
    fun projectedGimmickThresholdIsSharedByJvmAndJavaScript() {
        val rejectedTera = SharedGimmickEvaluator.score(
            ProjectedGimmickInput(
                id = "terastal",
                selectedScore = 104.0,
                baseScore = 100.0,
            ),
        )
        val configuredTera = SharedGimmickEvaluator.score(
            ProjectedGimmickInput(
                id = "terastallize",
                selectedScore = 104.0,
                baseScore = 100.0,
                configured = true,
            ),
        )

        assertEquals("terastallize", rejectedTera.id)
        assertEquals(5.0, rejectedTera.activationThreshold)
        assertEquals(false, rejectedTera.viable)
        assertEquals(7.0, configuredTera.score)
        assertTrue(configuredTera.viable)
    }

    @Test
    fun setupThreatFactsAreDerivedByTheSharedCore() {
        val likelihood = SharedSetupThreatEvaluator.likelihood(
            turn = 1,
            immediateDamageRatio = 0.15,
            opponentHpPercent = 1.0,
            opponentRoleScore = 8.0,
            opponentAce = true,
        )
        val result = SharedSetupThreatEvaluator.evaluateObserved(
            setupMoveIds = listOf("Dragon Dance"),
            setupLikelihood = likelihood,
            opponentCurrentBoosts = 0.0,
            opponentRoleScore = 8.0,
            opponentAce = true,
            opponentHpPercent = 1.0,
            immediateDamageRatio = 0.15,
            counterCount = 0.0,
            softCheckCount = 0.0,
            revengeKillerCount = 0.0,
            punishOptions = listOf("Taunt"),
        )

        assertEquals(1.0, likelihood)
        assertEquals(3, result.riskTier)
        assertEquals(1.0, result.strongestBoost?.attack)
        assertEquals(1.0, result.strongestBoost?.speed)
        assertTrue(result.oneMoreTurnUnmanageable)
        assertEquals(listOf("taunt"), result.punishOptions)
    }

    @Test
    fun actionReachabilityUsesPriorityBeforeDamageExposure() {
        val priority = SharedActionReachabilityEvaluator.evaluate(ActionReachabilityInput(
            ownPriority = 1.0,
            opponentPriority = 0.0,
            currentHp = 40.0,
            incomingDamage = 100.0,
        ))
        val slower = SharedActionReachabilityEvaluator.evaluate(ActionReachabilityInput(
            currentHp = 40.0,
            incomingDamage = 100.0,
        ))

        assertTrue(priority.actsBefore)
        assertEquals(0.0, priority.knockoutBeforeActionProbability)
        assertEquals(false, slower.canReachNextAction)
        assertEquals(1.0, slower.knockoutBeforeActionProbability)
    }

    @Test
    fun uniqueThreatCounterIsPreservedByTheSharedCore() {
        val result = SharedPreservationEvaluator.evaluate(ThreatCounterInput(listOf(
            ThreatObservationInput(
                enemySlot = 1,
                enemyPokemonId = "boss",
                aceScore = 12.0,
                setupScore = 5.0,
                offense = 160.0,
                resources = listOf(
                    CounterMatchupInput(1, "counter", incomingDamageRatio = 0.25, outgoingDamageRatio = 0.8),
                    CounterMatchupInput(2, "bench", incomingDamageRatio = 1.2, outgoingDamageRatio = 0.2),
                ),
            ),
        )))

        assertEquals("critical", result.threats.single().threatLevel)
        assertEquals("counter", result.threats.single().counters.single().classification)
        assertEquals(1, result.mustPreserveResources.single().slot)
        assertEquals("boss", result.mustPreserveResources.single().threats.single().enemyPokemonId)
    }

    @Test
    fun completedNonAceRoleBecomesExpendableOnlyWhenNotPreserved() {
        val result = SharedRoleProgressEvaluator.evaluate(RoleProgressInput(
            roleScores = mapOf("lead" to 6.0, "hazardControl" to 4.0),
            primaryRole = "lead",
            hazardSetConditions = listOf("stealthrock"),
            hazardMaxLayers = mapOf("stealthrock" to 1.0),
            opponentHazardLayers = mapOf("stealthrock" to 1.0),
            opponentLivingCount = 3,
            activeTurns = 1,
        ))

        assertTrue(result.roleComplete)
        assertTrue(result.expendableResource)
        assertEquals(listOf("lead", "hazardControl"), result.completedRoles)
    }

    @Test
    fun teamRoleAceIsSelectedByTheSharedCore() {
        val result = SharedTeamRoleEvaluator.evaluate(TeamRoleInput(listOf(
            TeamRoleMemberInput(
                slot = 1,
                pokemonId = "support",
                species = "Support",
                stats = TeamRoleStatsInput(attack = 60.0, specialAttack = 60.0, speed = 110.0),
                moveIds = listOf("batonpass", "agility"),
                catalogRoleScores = mapOf("pivot" to 3.0, "setupSweeper" to 3.0),
                catalogTags = listOf("setupboost", "pivot"),
                hasBatonPassSetupMove = true,
            ),
            TeamRoleMemberInput(
                slot = 2,
                pokemonId = "receiver",
                species = "Receiver",
                stats = TeamRoleStatsInput(attack = 150.0, speed = 80.0),
                moveIds = listOf("earthquake"),
                catalogRoleScores = mapOf("ace" to 2.5),
            ),
        )))

        assertEquals("receiver", result.aceCandidates.single().pokemonId)
        assertTrue(result.aceCandidates.single().aceProfile.batonPassSupport)
        assertEquals(2, result.aceCandidates.single().aceProfile.estimatedKoCapacity)
    }

    @Test
    fun hitReactionsCombineItemsContactAndDefenderAbilitiesInTheSharedCore() {
        val result = SharedHitReactionEvaluator.evaluate(SharedHitReactionInput(
            damage = 40.0,
            attackerAlive = true,
            defenderAlive = true,
            defenderAbility = "Weak Armor",
            defenderItem = "Rocky Helmet",
            moveId = "Tackle",
            moveType = "Normal",
            moveCategory = "Physical",
            contactPunishment = true,
            effectiveContact = true,
            resolveRandom = false,
        ))

        assertEquals(listOf("rockyhelmet", "weakarmor"), result.reactions.map { it.code })
        assertEquals(1.0 / 6.0, result.reactions.first().damageFraction)
        assertEquals(mapOf("defence" to -1.0, "speed" to 2.0), result.reactions.last().boosts)
    }

    @Test
    fun statefulHitReactionsOwnItemTransferIllusionAndGulpMissile() {
        val pickpocket = SharedHitReactionEvaluator.evaluate(SharedHitReactionInput(
            damage = 30.0,
            attackerItem = "Choice Band",
            defenderAbility = "Pickpocket",
            defenderHasIllusion = true,
            contactPunishment = true,
            resolveRandom = false,
        ))
        assertEquals(listOf("illusion", "pickpocket"), pickpocket.reactions.map { it.code })
        assertEquals("steal_attacker_item", pickpocket.reactions.last().itemAction)

        val gulpMissile = SharedHitReactionEvaluator.evaluate(SharedHitReactionInput(
            damage = 30.0,
            defenderAbility = "Gulp Missile",
            defenderGulpMissileForm = "gorging",
            resolveRandom = false,
        )).reactions.single()
        assertEquals(0.25, gulpMissile.damageFraction)
        assertEquals("par", gulpMissile.status)
        assertEquals("gorging", gulpMissile.clearState)

        val state = SharedSearchProjectionState(
            active = listOf(0, 0),
            hp = listOf(listOf(100), listOf(100)),
            maxHp = listOf(listOf(100), listOf(100)),
            heldItems = listOf(listOf("choiceband"), listOf("")),
            abilityStates = listOf(listOf(emptySet()), listOf(setOf("illusion"))),
        )
        val attack = SharedProjectedSearchAction(
            action = SearchAction("move:tackle", "move", 1.0),
            side = 0,
            damage = 30.0,
            hitReactions = pickpocket.reactions,
        )
        val idle = SharedProjectedSearchAction(SearchAction("move:splash", "move", 0.0), side = 1)
        val next = SharedSearchProjectionRuntime.transition(state, attack, idle)
        assertEquals("", next.heldItems[0][0])
        assertEquals("choiceband", next.heldItems[1][0])
        assertEquals(emptySet(), next.abilityStates[1][0])
    }

    @Test
    fun sharedSwitchPhaseOwnsExitHazardsEntryAbilitiesAndForcedSelection() {
        val phase = SharedSwitchPhaseEvaluator.evaluate(SharedSwitchPhaseInput(
            outgoingHp = 30,
            outgoingMaximumHp = 90,
            outgoingAbility = "Regenerator",
            incomingHp = 100,
            incomingMaximumHp = 100,
            incomingAbility = "Intimidate",
            incomingTypes = listOf("Fire", "Flying"),
            incomingGrounded = false,
            stealthRockLayers = 1,
            spikesLayers = 3,
            opponentAlive = true,
            opponentDefence = 90.0,
            opponentSpecialDefence = 110.0,
        ))
        assertEquals(50, phase.incomingHp)
        assertEquals(
            listOf("regenerator", "reset_switch_state", "hazard_damage", "entry_boost"),
            phase.operations.map { it.code },
        )
        assertEquals(30, phase.operations.first().amount)
        assertEquals("opponent", phase.operations.last().target)

        val state = SharedSearchProjectionState(
            active = listOf(0, 0),
            hp = listOf(listOf(30, 100), listOf(100)),
            maxHp = listOf(listOf(90, 100), listOf(100)),
            pressures = listOf(List(2) { SharedSearchPressure() }, listOf(SharedSearchPressure())),
            ranks = listOf(
                List(2) { List(5) { 0 } },
                listOf(listOf(2, 0, 0, 0, 0)),
            ),
            abilityStates = listOf(
                listOf(setOf("intrepidSwordUsed", "illusion"), emptySet()),
                listOf(emptySet()),
            ),
        )
        val switch = SharedProjectedSearchAction(
            SearchAction("switch:1", "switch", 1.0), side = 0, switchSlot = 1, switchPhase = phase,
        )
        val idle = SharedProjectedSearchAction(SearchAction("move:splash", "move", 0.0), side = 1)
        val next = SharedSearchProjectionRuntime.transition(state, switch, idle)
        assertEquals(60, next.hp[0][0])
        assertEquals(50, next.hp[0][1])
        assertEquals(1, next.ranks[1][0][0])
        assertEquals(setOf("intrepidSwordUsed"), next.abilityStates[0][0])

        val oneTimePhase = SharedSwitchPhaseEvaluator.evaluate(SharedSwitchPhaseInput(
            incomingHp = 100,
            incomingMaximumHp = 100,
            incomingAbility = "Intrepid Sword",
        ))
        val oneTimeState = state.copy(abilityStates = listOf(List(2) { emptySet() }, listOf(emptySet())))
        val oneTimeNext = SharedSearchProjectionRuntime.transition(
            oneTimeState,
            switch.copy(switchPhase = oneTimePhase),
            idle,
        )
        assertEquals(setOf("intrepidSwordUsed"), oneTimeNext.abilityStates[0][1])

        val forced = SharedForcedSwitchEvaluator.evaluate(SharedForcedSwitchInput(
            activeSlot = 0,
            teamHp = listOf(50, 0, 70, 80),
            preferredSlot = 2,
            randomSelection = true,
            rngState = 1234,
        ))
        assertEquals(listOf(2, 3), forced.eligibleSlots)
        assertEquals(2, forced.selectedSlot)
        assertEquals(1234, forced.rngState)
    }

    @Test
    fun projectionDifferentialReportsOnlyObservableStateMismatches() {
        val expected = SharedSearchProjectionState(
            turn = 4,
            active = listOf(1, 0),
            hp = listOf(listOf(60, 75), listOf(100)),
            maxHp = listOf(listOf(100, 100), listOf(100)),
            hazards = listOf(listOf(1, 2, 0, 0), listOf(0, 0, 0, 0)),
            pressures = listOf(
                listOf(SharedSearchPressure(), SharedSearchPressure(toxicCounter = 1)),
                listOf(SharedSearchPressure()),
            ),
            ranks = listOf(List(2) { List(5) { 0 } }, listOf(listOf(-1, 0, 0, 0, 0))),
            field = SharedSearchFieldState(weather = SharedSearchTimedEffect("raindance", 4)),
        )
        val observed = SharedProjectionObservation(
            turn = 4,
            sides = listOf(
                SharedObservedProjectionSide(
                    activeHp = 75,
                    activeMaximumHp = 100,
                    hazards = listOf(1, 2, 0, 0),
                    pressure = SharedSearchPressure(toxicCounter = 1),
                    ranks = List(5) { 0 },
                    gimmickRemaining = false,
                ),
                SharedObservedProjectionSide(
                    activeHp = 100,
                    activeMaximumHp = 100,
                    hazards = listOf(0, 0, 0, 0),
                    pressure = SharedSearchPressure(),
                    ranks = listOf(-1, 0, 0, 0, 0),
                ),
            ),
            field = expected.field,
        )
        assertTrue(SharedProjectionDifferentialEvaluator.evaluate(expected, observed).matches)

        val mismatch = SharedProjectionDifferentialEvaluator.evaluate(
            expected,
            observed.copy(sides = observed.sides.toMutableList().also {
                it[0] = it[0].copy(activeHp = 74)
            }),
        )
        assertFalse(mismatch.matches)
        assertEquals(listOf("sides[0].activeHp"), mismatch.differences.map { it.path })
    }

    @Test
    fun sharedEntryAdaptersOwnTraceRevealParadoxTransformAndFormDecisions() {
        val base = SharedSwitchPhaseInput(
            incomingHp = 100,
            incomingMaximumHp = 100,
            incomingTypes = listOf("Normal"),
            opponentAlive = true,
            opponentAbility = "Intimidate",
            opponentItem = "Choice Scarf",
            opponentMoves = listOf(
                SharedEntryMoveObservation("Tackle", "Normal", "Physical", 40),
                SharedEntryMoveObservation("Fissure", "Ground", "Physical", 0, ohko = true),
                SharedEntryMoveObservation("Close Combat", "Fighting", "Physical", 120),
            ),
        )
        fun adapter(input: SharedSwitchPhaseInput) = SharedSwitchPhaseEvaluator.evaluate(input)
            .operations.single { it.code == "entry_adapter" }

        assertEquals("intimidate", adapter(base.copy(incomingAbility = "Trace")).details["copiedAbility"])
        assertEquals("fissure", adapter(base.copy(incomingAbility = "Forewarn")).details["moveId"])
        assertEquals(
            "fissure,closecombat",
            adapter(base.copy(incomingAbility = "Anticipation")).details["threateningMoves"],
        )
        assertEquals("choicescarf", adapter(base.copy(incomingAbility = "Frisk")).details["item"])
        assertEquals("transformed", adapter(base.copy(incomingAbility = "Imposter")).setState)

        val paradox = adapter(base.copy(
            incomingAbility = "Protosynthesis",
            incomingItem = "Booster Energy",
            incomingStats = mapOf(
                "attack" to 100.0, "defence" to 90.0, "specialAttack" to 80.0,
                "specialDefence" to 70.0, "speed" to 120.0,
            ),
        ))
        assertEquals(mapOf("stat" to "speed", "source" to "boosterenergy"), paradox.details)
        assertTrue(paradox.consumeItem)

        val teraShift = adapter(base.copy(incomingAbility = "Tera Shift", incomingSpecies = "Terapagos"))
        assertEquals("terapagosterastal", teraShift.details["form"])
        val forecast = adapter(base.copy(incomingAbility = "Forecast", weather = "RainDance"))
        assertEquals("water", forecast.details["type"])

        val state = SharedSearchProjectionState(
            active = listOf(0, 0),
            hp = listOf(listOf(100, 100), listOf(100)),
            maxHp = listOf(listOf(100, 100), listOf(100)),
            pressures = listOf(List(2) { SharedSearchPressure() }, listOf(SharedSearchPressure())),
            ranks = listOf(List(2) { List(5) { 0 } }, listOf(listOf(2, 0, 0, 0, 0))),
            heldItems = listOf(listOf("", "boosterenergy"), listOf("choicescarf")),
            abilityStates = listOf(List(2) { emptySet() }, listOf(emptySet())),
        )
        val switch = SharedProjectedSearchAction(
            SearchAction("switch:1", "switch", 1.0),
            side = 0,
            switchSlot = 1,
            switchPhase = SharedSwitchPhaseEvaluator.evaluate(base.copy(
                incomingAbility = "Protosynthesis",
                incomingItem = "Booster Energy",
                incomingStats = mapOf("speed" to 120.0),
            )),
        )
        val next = SharedSearchProjectionRuntime.transition(
            state,
            switch,
            SharedProjectedSearchAction(SearchAction("move:splash", "move", 0.0), side = 1),
        )
        assertEquals("", next.heldItems[0][1])
        assertTrue("paradox" in next.abilityStates[0][1])

        val imposterNext = SharedSearchProjectionRuntime.transition(
            state,
            switch.copy(switchPhase = SharedSwitchPhaseEvaluator.evaluate(
                base.copy(incomingAbility = "Imposter"),
            )),
            SharedProjectedSearchAction(SearchAction("move:splash", "move", 0.0), side = 1),
        )
        assertEquals(2, imposterNext.ranks[0][1][0])
        assertTrue("transformed" in imposterNext.abilityStates[0][1])
    }

    @Test
    fun recoveryAndResidualPressureFactsAreShared() {
        val recovery = SharedSustainmentFactDeriver.recovery(RecoveryFactInput(
            currentHp = 40.0,
            maxHp = 100.0,
            healFraction = 0.5,
            opponentBestDamage = 30.0,
        ))
        val pressure = SharedSustainmentFactDeriver.residualPressure(ResidualPressureInput(
            currentHp = 35.0,
            maxHp = 160.0,
            toxicCounter = 3,
        ))

        assertEquals(50.0, recovery.recoveryAmount)
        assertEquals(20.0, recovery.recoveryNetHpChange)
        assertEquals(30.0, pressure.toxicNextDamage)
        assertTrue(pressure.toxicTwoTurnLethal)
        assertTrue(pressure.urgentSwitchPressure)
    }

    @Test
    fun setupRankTransitionsIncludeBatonPassDefensiveStats() {
        assertEquals(
            mapOf("defence" to 2.0),
            SharedSetupThreatEvaluator.projectedSelfBoosts("Acid Armor"),
        )
        assertEquals(-1.0, SharedSetupThreatEvaluator.projectedSelfBoosts("Shell Smash")["defence"])
        assertEquals(1.0, SharedSetupThreatEvaluator.projectedSelfBoosts("Quiver Dance")["specialDefence"])
    }

    @Test
    fun battleRankProjectionAndBatonPassAreIdenticalOnJvmAndJavaScript() {
        val ranks = intArrayOf(5, 0, 0, 0, 0)
        SharedBattleRankProjection.apply(ranks, mapOf(
            "attack" to 2.0,
            "specialAttack" to 2.0,
            "defence" to -1.0,
            "specialDefence" to -1.0,
            "speed" to 2.0,
        ))
        assertTrue(ranks.contentEquals(intArrayOf(6, 2, -1, -1, 2)))

        val side = arrayOf(ranks, IntArray(SharedBattleRankProjection.COUNT))
        SharedBattleRankProjection.batonPass(side, 0, 1)
        assertTrue(side[0].contentEquals(IntArray(SharedBattleRankProjection.COUNT)))
        assertTrue(side[1].contentEquals(intArrayOf(6, 2, -1, -1, 2)))
        assertEquals(200.0, SharedBattleRankProjection.adjustDamage(100.0, 0.0, 2.0, 0.0, 0.0))
    }

    @Test
    fun batonPassTransferValueIsDerivedFromProjectedMatchups() {
        val result = SharedBatonPassFactDeriver.derive(BatonPassFactInput(
            available = true,
            targetAvailable = true,
            targetSlot = 2,
            targetAce = true,
            currentBoosts = mapOf("speed" to 1.0),
            passedBoosts = mapOf("attack" to 2.0, "speed" to 1.0),
            targets = listOf(
                BatonPassTargetObservation(100.0, 70.0, 110.0),
                BatonPassTargetObservation(100.0, 40.0, 80.0),
            ),
        ))

        assertEquals(3.0, result.batonPassBoostTotal)
        assertEquals(2.0, result.batonPassAdditionalBoostTotal)
        assertEquals(1, result.batonPassNewKoTargets)
        assertTrue(result.batonPassTransferValue > 100.0)
    }

    @Test
    fun switchRiskRulesAreSharedByJvmAndJavaScript() {
        val adjustments = SharedSwitchRuleEvaluator.adjustments(
            SwitchRuleInput(
                hpRatio = 0.45,
                targetIncomingDamageRatio = 0.7,
                targetOutgoingDamageRatio = 0.5,
                safeImmediateKoAvailable = true,
                switchInDamageRatio = 0.7,
                switchedLastTurn = true,
                immediateReturn = true,
                dynamaxActive = true,
                dynamaxRemainingTurns = 2,
            ),
        ).associate { it.code to it.weight }

        assertEquals(-80.0, adjustments["rule.switch.lethal_switch_in"])
        assertEquals(-30.0, adjustments["rule.switch.guaranteed_ko_penalty"])
        assertEquals(-6.0, adjustments["rule.switch.repeated_switch"])
        assertEquals(-18.0, adjustments["rule.switch.dynamax_turn_cost"])
        assertEquals(-38.5, adjustments["rule.switch.incoming_hit_cost"])
    }

    @Test
    fun moveResourceRulesAreSharedByJvmAndJavaScript() {
        val recovery = SharedMoveRuleEvaluator.adjustments(
            MoveRuleInput(
                recoveryMove = true,
                hpRatio = 0.3,
                recoveryAmount = 50.0,
                recoveryExpectedIncomingDamage = 80.0,
                recoveryNetHpChange = -30.0,
                recoveryBeforeActionKoRisk = 0.9,
            ),
        ).associate { it.code to it.weight }
        val sacrifice = SharedMoveRuleEvaluator.adjustments(
            MoveRuleInput(
                selfSacrifice = true,
                opponentHp = 200.0,
                expectedDamage = 140.0,
                activeRoleScore = 11.0,
                mustPreserveResource = true,
            ),
        ).associate { it.code to it.weight }

        assertEquals(24.0, recovery["rule.recovery.survival_value"])
        assertEquals(-520.0, recovery["rule.recovery.ko_before_heal"])
        assertEquals(-95.0, recovery["rule.recovery.negative_exchange"])
        assertEquals(-435.0, sacrifice["rule.self_sacrifice.resource_cost"])
    }

    @Test
    fun winEstimateUsesTheSameExplainableModelOnEveryTarget() {
        val estimate = SharedAiCore.estimateWinProbability(
            BattleValueState(
                own = BattleValueSide(teamSize = 3.0, livingCount = 3.0, totalHpRatio = 2.4),
                opponent = BattleValueSide(teamSize = 3.0, livingCount = 2.0, totalHpRatio = 1.1),
            ),
        )

        assertEquals("heuristic-logistic-v3", estimate.modelVersion)
        assertEquals(101.2, estimate.rawValue)
        assertEquals(0.7548, estimate.rawProbability)
    }

    @Test
    fun winRateSearchOverridesTheHeuristicOnlyForAMeaningfulGain() {
        val decision = SharedAiCore.decideWinRate("root", 0, 8, WinRateFixture())

        assertEquals("risky", decision.selected?.id)
        assertTrue(decision.policyOverride)
        assertEquals(2, decision.visitedNodes)
    }

    @Test
    fun twoTurnSearchExtendsCloseCandidates() {
        val decision = SharedAiCore.decideTwoTurn("root", 0, 10, TwoTurnFixture())

        assertEquals("setup", decision.selected?.id)
        assertEquals(2, decision.depthTurns)
        assertEquals(4, decision.visitedNodes)
    }

    @Test
    fun twoTurnSearchKeepsEvaluatingAForcedOwnAction() {
        val locked = action("damage", 99.0)
        val decision = SharedAiCore.decideTwoTurn(
            "root",
            0,
            10,
            TwoTurnFixture(),
            exactOwnAction = locked,
        )

        assertEquals("damage", decision.selected?.id)
        assertEquals(2, decision.depthTurns)
        assertEquals(2, decision.visitedNodes)
        assertEquals("chip", decision.evaluations.single().outcomes.single().continuation?.action?.id)
    }

    @Test
    fun sharedBattleContractNormalizesTheSameStateAndCommandsOnEveryTarget() {
        val move = SharedMoveState(id = "tackle", name = "Tackle", maxPp = 35, pp = 40)
        val member = SharedPokemonState(
            id = "pikachu",
            name = "Pikachu",
            stats = SharedBattleStats(
                hp = 100,
                attack = 55,
                defence = 40,
                specialAttack = 50,
                specialDefence = 50,
                speed = 90,
            ),
            hp = 130,
            boosts = mapOf("attack" to 9, "speed" to -8),
            moves = listOf(move),
        )
        val state = SharedBattleState(
            seed = 4_294_967_297L,
            turn = -2,
            manualFaintSwitchSides = listOf(0, 0, 3),
            sides = listOf(
                SharedBattleSideState(name = "A", team = listOf(member)),
                SharedBattleSideState(
                    name = "B",
                    team = listOf(member.copy(id = "eevee", name = "Eevee")),
                ),
            ),
        )

        val normalized = SharedBattleContract.normalize(state)
        assertEquals(1L, normalized.seed)
        assertEquals(0, normalized.turn)
        assertEquals(listOf(0), normalized.manualFaintSwitchSides)
        assertEquals(100, normalized.sides[0].team[0].hp)
        assertEquals(mapOf("attack" to 6, "speed" to -6), normalized.sides[0].team[0].boosts)
        assertEquals(35, normalized.sides[0].team[0].moves[0].pp)

        val commands = SharedBattleContract.normalizeCommands(
            normalized,
            SharedTurnCommands(listOf(
                SharedBattleCommand(moveSlot = 1),
                SharedBattleCommand(moveSlot = 1),
            )),
        )
        assertEquals(listOf(0, 1), commands.commands.map { it.side })
        assertTrue(
            SharedBattleContract.normalizeStateJson(codec.encodeToString(state)).contains("\"seed\":1"),
        )
    }

    @Test
    fun sharedBattleRngMatchesTheExistingWebXorshift32Sequence() {
        val sample = SharedBattleContract.sampleRng(SharedRngRequest(seed = 12_345, draws = 5))

        assertEquals(
            listOf(2_548_642_403L, 2_231_655_569L, 3_696_820_378L, 1_963_845_983L, 3_438_003_404L),
            sample.unsignedValues,
        )
        assertEquals(3_438_003_404L, sample.state)
        assertEquals(0.593402051134035, sample.values[0], 0.000000000000001)
    }

    @Test
    fun sharedActionOrderHandlesPrioritySpeedTrickRoomAndPursuitOnEveryTarget() {
        fun member(name: String, speed: Int, move: SharedMoveState) = SharedPokemonState(
            id = name.lowercase(),
            name = name,
            stats = SharedBattleStats(hp = 100, speed = speed),
            hp = 100,
            moves = listOf(move),
        )
        val tackle = SharedMoveState(id = "tackle", category = "Physical", maxPp = 35, pp = 35)
        val pursuit = tackle.copy(id = "pursuit")
        val trickRoom = SharedEffectState(id = "trickroom", turns = 3)
        val state = SharedBattleState(
            field = SharedBattleFieldState(pseudoWeather = mapOf("trickroom" to trickRoom)),
            sides = listOf(
                SharedBattleSideState(name = "A", team = listOf(member("Slow", 50, pursuit))),
                SharedBattleSideState(name = "B", team = listOf(
                    member("Fast", 120, tackle),
                    member("Bench", 80, tackle),
                )),
            ),
        )
        val roomResult = SharedActionOrderEvaluator.order(
            state,
            SharedActionOrderInput(
                rngState = SharedBattleRng(42).snapshot(),
                actions = listOf(
                    SharedActionOrderCandidate(inputIndex = 0, side = 0, moveSlot = 1),
                    SharedActionOrderCandidate(inputIndex = 1, side = 1, moveSlot = 1),
                ),
            ),
        )
        val pursuitResult = SharedActionOrderEvaluator.order(
            state,
            SharedActionOrderInput(
                rngState = SharedBattleRng(42).snapshot(),
                actions = listOf(
                    SharedActionOrderCandidate(inputIndex = 0, side = 0, moveSlot = 1),
                    SharedActionOrderCandidate(inputIndex = 1, side = 1, kind = "switch"),
                ),
            ),
        )

        assertEquals(listOf(0, 1), roomResult.actions.map { it.inputIndex })
        assertEquals(50, roomResult.actions[0].speed)
        assertEquals(120, roomResult.actions[1].speed)
        assertEquals(listOf(0, 1), pursuitResult.actions.map { it.inputIndex })
        assertEquals(10_001, pursuitResult.actions[0].priority)
        assertTrue(pursuitResult.actions[0].pursuitTargetSwitch)
        assertEquals(10_000, pursuitResult.actions[1].priority)
    }

    @Test
    fun sharedActionBuilderResolvesLocksDisableChargingAndTrapsOnEveryTarget() {
        val slash = SharedMoveState(id = "slash", name = "Slash", maxPp = 20, pp = 20)
        val ember = SharedMoveState(id = "ember", name = "Ember", maxPp = 20, pp = 20)
        fun member(name: String) = SharedPokemonState(
            id = name.lowercase(),
            name = name,
            stats = SharedBattleStats(hp = 100),
            hp = 100,
            moves = listOf(slash, ember),
        )
        val locked = member("Locked").copy(lockedMove = SharedEffectState(id = "slash"))
        val base = SharedBattleState(sides = listOf(
            SharedBattleSideState(name = "A", team = listOf(locked, member("Bench"))),
            SharedBattleSideState(name = "B", team = listOf(member("Target"))),
        ))
        val lockedResult = SharedActionBuildEvaluator.build(
            base,
            SharedTurnCommands(listOf(
                SharedBattleCommand(side = 0, moveSlot = 2),
                SharedBattleCommand(side = 1, moveSlot = 1),
            )),
        )
        assertEquals(1, lockedResult.actions[0].moveSlot)
        assertTrue(lockedResult.actions[0].locked)
        assertTrue(lockedResult.actions[0].noPpCost)

        val disabled = locked.copy(
            lockedMove = null,
            volatiles = mapOf("disable" to SharedEffectState(
                id = "disable",
                attributes = JsonObject(mapOf("moveId" to JsonPrimitive("slash"))),
            )),
        )
        val disabledState = base.copy(sides = listOf(
            base.sides[0].copy(team = listOf(disabled, member("Bench"))),
            base.sides[1],
        ))
        val disabledResult = SharedActionBuildEvaluator.build(
            disabledState,
            SharedTurnCommands(listOf(
                SharedBattleCommand(side = 0, moveSlot = 1),
                SharedBattleCommand(side = 1, moveSlot = 1),
            )),
        )
        assertEquals(2, disabledResult.actions[0].moveSlot)

        val charging = disabled.copy(
            volatiles = emptyMap(),
            chargingMove = SharedEffectState(id = "ember"),
        )
        val chargingState = disabledState.copy(sides = listOf(
            disabledState.sides[0].copy(team = listOf(charging, member("Bench"))),
            disabledState.sides[1],
        ))
        val chargingResult = SharedActionBuildEvaluator.build(
            chargingState,
            SharedTurnCommands(listOf(
                SharedBattleCommand(side = 0),
                SharedBattleCommand(side = 1, moveSlot = 1),
            )),
        )
        assertEquals(2, chargingResult.actions[0].moveSlot)
        assertTrue(chargingResult.actions[0].chargingRelease)

        val trapped = disabled.copy(volatiles = mapOf("meanlook" to SharedEffectState(id = "meanlook")))
        val trappedState = disabledState.copy(sides = listOf(
            disabledState.sides[0].copy(team = listOf(trapped, member("Bench"))),
            disabledState.sides[1],
        ))
        val error = assertFailsWith<IllegalStateException> {
            SharedActionBuildEvaluator.build(
                trappedState,
                SharedTurnCommands(listOf(
                    SharedBattleCommand(side = 0, kind = "switch", switchSlot = 2),
                    SharedBattleCommand(side = 1, moveSlot = 1),
                )),
            )
        }
        assertTrue(error.message.orEmpty().contains("cannot switch while trapped"))
    }

    @Test
    fun sharedDamageCalculatorOwnsBaseRangeImmunityAndHpCap() {
        val range = SharedDamageCalculator.range(
            SharedDamageInput(
                level = 50,
                power = 100.0,
                attack = 120.0,
                defence = 100.0,
                stab = 1.5,
                effectiveness = 2.0,
                itemModifier = 1.3,
            ),
        )
        assertEquals(54, range.baseDamage)
        assertEquals(179, range.minimum)
        assertEquals(210, range.maximum)
        assertEquals(3.9, range.totalModifier, 0.000_001)

        val roll = SharedDamageCalculator.roll(
            SharedDamageRollInput(
                baseDamage = range.baseDamage,
                stab = 1.5,
                effectiveness = 2.0,
                itemModifier = 1.3,
                criticalModifier = 1.5,
                randomFactor = 0.9,
                remainingHp = 200,
            ),
        )
        assertEquals(284, roll.uncappedDamage)
        assertEquals(200, roll.damage)

        val immune = SharedDamageCalculator.range(
            SharedDamageInput(power = 100.0, attack = 100.0, defence = 100.0, effectiveness = 0.0),
        )
        assertEquals(0, immune.minimum)
        assertEquals(0, immune.maximum)
        assertTrue(immune.immune)
    }

    @Test
    fun sharedDamageTypeEvaluatorOwnsTeraImmunityAndSpecialTypeRules() {
        fun pokemon(
            id: String,
            types: List<String>,
            ability: String = "",
            terastallized: Boolean = false,
            teraType: String = "",
        ) = SharedDamageTypePokemon(
            id = id,
            types = types,
            originalTypes = types,
            ability = ability,
            hp = 100,
            maximumHp = 100,
            terastallized = terastallized,
            teraType = teraType,
        )

        val teraAdaptability = SharedDamageTypeEvaluator.evaluate(
            SharedDamageTypeInput(
                attacker = pokemon("attacker", listOf("Fire"), "adaptability", true, "Fire"),
                defender = pokemon("defender", listOf("Grass")),
                move = SharedDamageTypeMove(type = "Fire"),
            ),
        )
        assertEquals(2.25, teraAdaptability.stab)
        assertEquals(2.0, teraAdaptability.effectiveness)

        val absorbed = SharedDamageTypeEvaluator.evaluate(
            SharedDamageTypeInput(
                attacker = pokemon("attacker", listOf("Normal")),
                defender = pokemon("defender", listOf("Water"), "waterabsorb"),
                move = SharedDamageTypeMove(type = "Water"),
            ),
        )
        assertEquals(0.0, absorbed.effectiveness)
        assertEquals("waterabsorb", absorbed.absorbedByAbility)

        val scrappy = SharedDamageTypeEvaluator.evaluate(
            SharedDamageTypeInput(
                attacker = pokemon("attacker", listOf("Normal"), "scrappy"),
                defender = pokemon("defender", listOf("Ghost", "Rock")),
                move = SharedDamageTypeMove(type = "Normal"),
            ),
        )
        assertEquals(0.5, scrappy.effectiveness)

        val freezeDry = SharedDamageTypeEvaluator.evaluate(
            SharedDamageTypeInput(
                attacker = pokemon("attacker", listOf("Ice")),
                defender = pokemon("defender", listOf("Water", "Flying")),
                move = SharedDamageTypeMove(id = "freezedry", type = "Ice"),
            ),
        )
        assertEquals(4.0, freezeDry.effectiveness)

        val wonderGuard = SharedDamageTypeEvaluator.evaluate(
            SharedDamageTypeInput(
                attacker = pokemon("attacker", listOf("Normal")),
                defender = pokemon("defender", listOf("Normal"), "wonderguard"),
                move = SharedDamageTypeMove(type = "Normal"),
            ),
        )
        assertEquals(0.0, wonderGuard.effectiveness)

        val teraShellInDeltaStream = SharedDamageTypeEvaluator.evaluate(
            SharedDamageTypeInput(
                attacker = pokemon("attacker", listOf("Electric")),
                defender = pokemon("terapagos-terastal", listOf("Flying"), "terashell"),
                move = SharedDamageTypeMove(type = "Electric"),
                weather = "deltastream",
            ),
        )
        assertEquals(0.5, teraShellInDeltaStream.effectiveness)
    }

    @Test
    fun sharedDamageModifierEvaluatorOwnsItemAbilityFieldAndScreenRules() {
        val result = SharedDamageModifierEvaluator.evaluate(
            SharedDamageModifierInput(
                attacker = SharedDamageModifierPokemon(
                    types = listOf("Fire"),
                    ability = "toughclaws",
                    item = "lifeorb",
                    hp = 100,
                    maximumHp = 100,
                    helpingHand = true,
                ),
                defender = SharedDamageModifierPokemon(
                    types = listOf("Normal"),
                    ability = "fluffy",
                    hp = 100,
                    maximumHp = 100,
                ),
                move = SharedDamageModifierMove(
                    id = "flareblitz",
                    type = "Fire",
                    category = "Physical",
                    power = 120.0,
                    contact = true,
                    recoil = true,
                ),
                weather = "sunnyday",
                defenderSideConditions = listOf("reflect"),
            ),
        )
        assertEquals(1.3, result.itemModifier, 0.000_001)
        assertEquals(1.3, result.abilityModifier, 0.000_001)
        assertEquals(1.125, result.fieldModifier, 0.000_001)

        val glove = SharedDamageModifierEvaluator.evaluate(
            SharedDamageModifierInput(
                attacker = SharedDamageModifierPokemon(
                    ability = "ironfist",
                    item = "punchingglove",
                    hp = 100,
                    maximumHp = 100,
                ),
                defender = SharedDamageModifierPokemon(
                    ability = "fluffy",
                    hp = 100,
                    maximumHp = 100,
                ),
                move = SharedDamageModifierMove(
                    type = "Fighting",
                    category = "Physical",
                    power = 80.0,
                    contact = true,
                    punch = true,
                ),
            ),
        )
        assertEquals(1.1, glove.itemModifier, 0.000_001)
        assertEquals(1.2, glove.abilityModifier, 0.000_001)
        assertEquals(1.0, glove.fieldModifier, 0.000_001)

        val auraBreak = SharedDamageModifierEvaluator.evaluate(
            SharedDamageModifierInput(
                attacker = SharedDamageModifierPokemon(
                    ability = "tintedlens",
                    hp = 100,
                    maximumHp = 100,
                ),
                defender = SharedDamageModifierPokemon(hp = 100, maximumHp = 100),
                move = SharedDamageModifierMove(type = "Dark", category = "Special", power = 80.0),
                effectiveness = 0.5,
                activeAbilitySources = listOf(
                    SharedDamageAbilitySource(ability = "darkaura"),
                    SharedDamageAbilitySource(ability = "aurabreak"),
                ),
            ),
        )
        assertEquals(1.5, auraBreak.abilityModifier, 0.000_001)
    }

    @Test
    fun sharedEffectiveStatAndDamageStatEvaluatorsOwnBattleStatProjection() {
        val pikachu = SharedEffectiveStatPokemon(
            id = "pikachu",
            item = "lightball",
            hp = 100,
            maximumHp = 100,
            stats = SharedBattleStats(hp = 100, attack = 100),
            boosts = mapOf("attack" to 1),
        )
        val boosted = SharedEffectiveStatEvaluator.evaluate(
            SharedEffectiveStatInput(pokemon = pikachu, stat = "attack"),
        )
        assertEquals(300.0, boosted.value, 0.000_001)

        val unaware = SharedDamageStatEvaluator.evaluate(
            SharedDamageStatInput(
                attacker = pikachu.copy(
                    item = "",
                    ability = "unaware",
                    boosts = mapOf("attack" to 2),
                ),
                defender = pikachu.copy(
                    id = "defender",
                    item = "",
                    ability = "unaware",
                    boosts = mapOf("defence" to 2),
                    stats = SharedBattleStats(hp = 100, defence = 100),
                ),
                category = "Physical",
            ),
        )
        assertEquals(100.0, unaware.attack, 0.000_001)
        assertEquals(100.0, unaware.defence, 0.000_001)

        val sandDefence = SharedDamageStatEvaluator.evaluate(
            SharedDamageStatInput(
                attacker = pikachu.copy(item = "", ability = ""),
                defender = pikachu.copy(
                    id = "rock",
                    types = listOf("Rock"),
                    item = "assaultvest",
                    stats = SharedBattleStats(hp = 100, specialDefence = 100),
                    boosts = emptyMap(),
                ),
                category = "Special",
                weather = "sandstorm",
            ),
        )
        assertEquals(225.0, sandDefence.defence, 0.000_001)
    }

    @Test
    fun sharedDamageApplicationOwnsSurvivalSubstituteDisguiseAndImmunity() {
        val focusSash = SharedDamageApplicationEvaluator.evaluate(
            SharedDamageApplicationInput(
                turn = 3,
                attackerName = "Attacker",
                defenderName = "Defender",
                moveId = "closecombat",
                moveName = "Close Combat",
                moveType = "Fighting",
                damage = 100,
                defenderHp = 100,
                defenderMaximumHp = 100,
                focusSash = true,
            ),
        )
        assertEquals(99, focusSash.damage)
        assertEquals(1, focusSash.remainingHp)
        assertTrue(focusSash.consumeFocusSash)
        assertEquals("Focus Sash", focusSash.preventionSource)
        assertEquals(
            listOf(JsonPrimitive("damage_prevented"), JsonPrimitive("damage")),
            focusSash.events.map { it["type"] },
        )

        val substitute = SharedDamageApplicationEvaluator.evaluate(
            SharedDamageApplicationInput(
                attackerName = "Attacker",
                defenderName = "Defender",
                moveName = "Thunderbolt",
                moveType = "Electric",
                damage = 40,
                defenderHp = 80,
                defenderMaximumHp = 100,
                substituteHp = 25,
                critical = true,
            ),
        )
        assertEquals(25, substitute.appliedDamage)
        assertEquals(80, substitute.remainingHp)
        assertEquals(0, substitute.substituteHp)
        assertTrue(substitute.substituteBlocked)
        assertTrue(substitute.substituteEnded)
        assertEquals(
            listOf(JsonPrimitive("damage"), JsonPrimitive("volatile_end"), JsonPrimitive("critical")),
            substitute.events.map { it["type"] },
        )

        val disguise = SharedDamageApplicationEvaluator.evaluate(
            SharedDamageApplicationInput(
                attackerName = "Attacker",
                defenderName = "Mimikyu",
                moveName = "Iron Head",
                damage = 60,
                defenderHp = 90,
                defenderMaximumHp = 100,
                disguise = true,
            ),
        )
        assertTrue(disguise.disguiseBlocked)
        assertEquals(0, disguise.damage)
        assertEquals(90, disguise.remainingHp)
        assertEquals(
            listOf(JsonPrimitive("ability_activate"), JsonPrimitive("damage")),
            disguise.events.map { it["type"] },
        )

        val immune = SharedDamageApplicationEvaluator.evaluate(
            SharedDamageApplicationInput(
                attackerName = "Attacker",
                defenderName = "Ghost",
                moveName = "Normal Move",
                damage = 50,
                defenderHp = 70,
                defenderMaximumHp = 100,
                effectiveness = 0.0,
            ),
        )
        assertTrue(immune.immune)
        assertEquals(70, immune.remainingHp)
        assertEquals(JsonPrimitive(0), immune.events.single()["damage"])
    }

    @Test
    fun sharedDirectDamageOwnsMagicGuardAndFaintResults() {
        val blocked = SharedDirectDamageEvaluator.evaluate(
            SharedDirectDamageInput(
                pokemon = "Clefable",
                amount = 20,
                hp = 80,
                maximumHp = 100,
                source = "poison",
                cause = "status",
                magicGuard = true,
            ),
        )
        assertTrue(blocked.blockedByMagicGuard)
        assertEquals(80, blocked.remainingHp)
        assertEquals(JsonPrimitive("ability_activate"), blocked.events.single()["type"])

        val fainted = SharedDirectDamageEvaluator.evaluate(
            SharedDirectDamageInput(
                pokemon = "Target",
                amount = 90,
                hp = 45,
                maximumHp = 100,
                source = "Future Sight",
                cause = "future_attack",
                magicGuard = true,
            ),
        )
        assertEquals(45, fainted.damage)
        assertEquals(0, fainted.remainingHp)
        assertTrue(fainted.fainted)
        assertEquals(JsonPrimitive("damage"), fainted.events.single()["type"])
    }

    @Test
    fun sharedPostHitEvaluatorOwnsContactDrainRecoilItemsAndSuppression() {
        val result = SharedPostHitEvaluator.evaluate(
            SharedPostHitInput(
                moveId = "wakeupslap",
                moveContact = true,
                movePunch = true,
                moveHasSecondaries = true,
                drainNumerator = 1,
                drainDenominator = 2,
                recoilNumerator = 1,
                recoilDenominator = 3,
                landedHits = 1,
                totalDamage = 90,
                attackerHp = 80,
                attackerMaximumHp = 100,
                attackerItem = "punchingglove",
                attackerAbility = "",
                defenderHp = 50,
                defenderAbility = "liquidooze",
                defenderStatus = "slp",
            ),
        )
        assertEquals(false, result.effectiveContact)
        assertEquals(false, result.contactPunishment)
        assertEquals(45, result.drainAmount)
        assertTrue(result.drainAsDamage)
        assertEquals(30, result.recoilDamage)
        assertEquals("slp", result.curedStatus)

        val fakeOut = SharedPostHitEvaluator.evaluate(
            SharedPostHitInput(moveId = "fakeout", landedHits = 1),
        )
        assertEquals("volatile", fakeOut.instructions.single().kind)
        assertEquals("flinch", fakeOut.instructions.single().effect)

        val binding = SharedPostHitEvaluator.evaluate(
            SharedPostHitInput(
                moveId = "wrap",
                moveVolatileStatus = "partiallytrapped",
                bindingVolatile = true,
                landedHits = 1,
            ),
        )
        assertEquals("volatile_if_alive", binding.instructions.single().kind)
        assertEquals("partiallytrapped", binding.instructions.single().effect)

        val sheerForce = SharedPostHitEvaluator.evaluate(
            SharedPostHitInput(
                moveHasSecondaries = true,
                totalDamage = 80,
                landedHits = 1,
                attackerHp = 100,
                attackerMaximumHp = 100,
                attackerItem = "lifeorb",
                attackerAbility = "sheerforce",
                defenderHp = 100,
                defenderItem = "covertcloak",
            ),
        )
        assertEquals(0, sheerForce.lifeOrbDamage)
        assertTrue(sheerForce.secondaryEffectsSuppressed)
        assertEquals("", sheerForce.secondaryBlockSource)

        val shieldDust = SharedPostHitEvaluator.evaluate(
            SharedPostHitInput(
                moveHasSecondaries = true,
                totalDamage = 30,
                landedHits = 1,
                attackerHp = 100,
                defenderHp = 70,
                defenderAbility = "shielddust",
            ),
        )
        assertEquals("shielddust", shieldDust.secondaryBlockSource)

        val thief = SharedPostHitEvaluator.projectedInstructions(
            moveId = "thief",
            attackerHp = 80,
            attackerMaximumHp = 100,
            attackerItem = "",
            attackerAbility = "",
            attackerFainted = false,
            defenderItem = "leftovers",
            defenderItemRemovalBlocked = false,
        )
        assertEquals("steal_defender_item", thief.single().kind)
        assertEquals("item_removal_blocked", SharedPostHitEvaluator.projectedInstructions(
            "thief", 80, 100, "", "", false, "leftovers", true,
        ).single().kind)

        val gulpMissile = SharedPostHitEvaluator.projectedInstructions(
            "surf", 49, 100, "", "gulpmissile", false, "", false,
        ).single()
        assertEquals("gulp_missile", gulpMissile.kind)
        assertEquals("gorging", gulpMissile.effect)
    }

    @Test
    fun sharedSecondaryRollPreservesConditionalRngConsumption() {
        val initialState = 0x1234_5678L
        val blocked = SharedSecondaryRollEvaluator.evaluate(
            SharedSecondaryRollInput(
                chance = 30.0,
                blocked = true,
                rngState = initialState,
            ),
        )
        assertEquals(initialState, blocked.rngState)
        assertEquals(false, blocked.triggered)

        val sereneGrace = SharedSecondaryRollEvaluator.evaluate(
            SharedSecondaryRollInput(
                chance = 60.0,
                attackerAbility = "serenegrace",
                rngState = initialState,
            ),
        )
        assertEquals(100.0, sereneGrace.effectiveChance)
        assertTrue(sereneGrace.triggered)
        assertTrue(sereneGrace.rngState != initialState)
    }

    @Test
    fun sharedGenericEffectEvaluatorsOwnStatusVolatileBoostFieldAndHazards() {
        val sleep = SharedStatusApplicationEvaluator.evaluate(
            SharedStatusApplicationInput(status = "slp", rngState = 0x1234_5678L),
        )
        assertTrue(sleep.applied)
        assertTrue(sleep.statusTurns in 1..3)
        assertTrue(sleep.rngState != 0x1234_5678L)

        val blockedPoison = SharedStatusApplicationEvaluator.evaluate(
            SharedStatusApplicationInput(status = "tox", types = listOf("Steel")),
        )
        assertEquals("type", blockedPoison.blockedBy)

        val binding = SharedVolatileApplicationEvaluator.evaluate(
            SharedVolatileApplicationInput(id = "wrap", sourceItem = "gripclaw"),
        )
        assertTrue(binding.applied)
        assertEquals(7, binding.turns)

        val contrary = SharedBoostApplicationEvaluator.evaluate(
            SharedBoostApplicationInput(
                stat = "attack",
                amount = -1,
                currentStage = 2,
                ability = "contrary",
                loweredByFoe = true,
            ),
        )
        assertEquals("apply", contrary.action)
        assertEquals(1, contrary.appliedAmount)
        assertEquals(3, contrary.nextStage)

        val reflected = SharedBoostApplicationEvaluator.evaluate(
            SharedBoostApplicationInput(
                stat = "speed",
                amount = -1,
                ability = "mirrorarmor",
                loweredByFoe = true,
            ),
        )
        assertEquals("reflect", reflected.action)

        val rain = SharedFieldApplicationEvaluator.evaluate(
            SharedFieldApplicationInput(kind = "weather", id = "raindance", sourceItem = "damprock"),
        )
        assertEquals(8, rain.turns)

        val spikes = SharedSideConditionApplicationEvaluator.evaluate(
            SharedSideConditionApplicationInput(id = "spikes", previousLayers = 2),
        )
        assertEquals(3, spikes.layers)
        assertTrue(spikes.applied)
    }

    @Test
    fun sharedEndTurnResidualOwnsOrderedDamageHealingAndFaintRules() {
        val toxic = SharedEndTurnResidualEvaluator.evaluate(
            SharedEndTurnResidualInput(
                side = 0,
                hp = 80,
                maximumHp = 160,
                types = listOf("Water"),
                status = "tox",
                toxicCounter = 2,
                weather = "sandstorm",
                terrain = "grassyterrain",
                grounded = true,
                volatiles = listOf(
                    SharedEndTurnVolatile("saltcure", source = "Salt Cure"),
                ),
            ),
        )
        assertEquals(listOf("toxic_counter", "damage", "damage", "heal", "damage"), toxic.operations.map { it.kind })
        assertEquals(listOf("tox", "tox", "sandstorm", "grassyterrain", "Salt Cure"), toxic.operations.map { it.effect })
        assertEquals(20, toxic.remainingHp)
        assertEquals(3, toxic.toxicCounter)
        assertEquals(false, toxic.fainted)

        val perish = SharedEndTurnResidualEvaluator.evaluate(
            SharedEndTurnResidualInput(
                side = 1,
                hp = 50,
                maximumHp = 100,
                volatiles = listOf(SharedEndTurnVolatile("perishsong", count = 1)),
                item = "leftovers",
            ),
        )
        assertEquals(listOf("perish_tick", "faint"), perish.operations.map { it.kind })
        assertTrue(perish.fainted)
        assertEquals(0, perish.remainingHp)

        val solarPower = SharedEndTurnResidualEvaluator.evaluate(
            SharedEndTurnResidualInput(
                hp = 80,
                maximumHp = 160,
                ability = "solarpower",
                weather = "sunnyday",
            ),
        )
        assertEquals("solarpower", solarPower.operations.single().effect)
        assertEquals(20, solarPower.operations.single().amount)
    }

    @Test
    fun sharedEndTurnUtilitiesOwnTimersDynamaxAndBattleOutcome() {
        val yawn = SharedTimedEffectEvaluator.evaluate(SharedTimedEffectInput("volatile", "yawn", 1))
        assertTrue(yawn.ended)
        assertEquals("slp", yawn.triggerStatus)

        val dynamax = SharedDynamaxExpiryEvaluator.evaluate(
            SharedDynamaxExpiryInput(hp = 101, maximumHp = 200, baseMaximumHp = 100, remainingTurns = 1),
        )
        assertTrue(dynamax.ended)
        assertEquals(51, dynamax.hp)
        assertEquals(100, dynamax.maximumHp)

        val win = SharedBattleOutcomeEvaluator.evaluate(
            SharedBattleOutcomeInput(
                sideNames = listOf("Blue", "Red"),
                faintedTeams = listOf(listOf(true, true), listOf(false, true)),
            ),
        )
        assertTrue(win.completed)
        assertEquals("completed", win.status)
        assertEquals("Red", win.winner)

        val tie = SharedBattleOutcomeEvaluator.evaluate(
            SharedBattleOutcomeInput(
                sideNames = listOf("Blue", "Red"),
                faintedTeams = listOf(listOf(true), listOf(true)),
            ),
        )
        assertEquals("tie", tie.status)
        assertEquals(null, tie.winner)

        val replacement = SharedFaintReplacementEvaluator.evaluate(
            SharedFaintReplacementInput(
                activeSlot = 0,
                activeFainted = true,
                manualSelection = false,
                teamHp = listOf(0, 75, 0),
                teamFainted = listOf(true, false, true),
            ),
        )
        assertTrue(replacement.required)
        assertTrue(replacement.automatic)
        assertEquals(listOf(1), replacement.eligibleSlots)
    }

    @Test
    fun sharedSearchProjectionOwnsCandidateLegalityAndTurnTransition() {
        val state = SharedSearchProjectionState(
            turn = 3,
            active = listOf(0, 0),
            hp = listOf(listOf(100, 80), listOf(90, 70)),
            maxHp = listOf(listOf(100, 100), listOf(100, 100)),
            gimmicksRemaining = listOf(true, false),
            itemCounts = listOf(listOf(1), emptyList()),
            hazards = listOf(listOf(0, 0, 0, 0), listOf(0, 2, 0, 0)),
            pressures = listOf(
                listOf(SharedSearchPressure(yawn = true, yawnTurns = 2), SharedSearchPressure()),
                listOf(SharedSearchPressure(), SharedSearchPressure()),
            ),
            ranks = listOf(
                listOf(listOf(2, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0)),
                listOf(listOf(0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0)),
            ),
            field = SharedSearchFieldState(
                weather = SharedSearchTimedEffect("sunnyday", 2),
                terrain = SharedSearchTimedEffect("grassyterrain", 1),
                pseudoWeather = mapOf("gravity" to SharedSearchTimedEffect("gravity", 2)),
            ),
            sideConditions = listOf(
                mapOf("reflect" to SharedSearchTimedEffect("reflect", 2)),
                emptyMap(),
            ),
        )
        val illegalSwitch = SharedProjectedSearchAction(
            action = SearchAction("switch:active", "switch", 200.0),
            side = 0,
            switchSlot = 0,
        )
        val legalMove = SharedProjectedSearchAction(
            action = SearchAction("move:spikes", "move", 100.0, expectedDamage = 30.0),
            side = 0,
            damage = 30.0,
            hazardIndex = 1,
            selfBoosts = mapOf("attack" to 1.0),
            consumesGimmick = true,
            weather = "raindance",
            terrain = "electricterrain",
            pseudoWeather = "trickroom",
            sideCondition = "tailwind",
            hitReactions = listOf(
                SharedHitReaction("rockyhelmet", "rockyhelmet", "attacker", damageFraction = 1.0 / 6.0),
                SharedHitReaction("weakarmor", "weakarmor", "defender", boosts = mapOf("defence" to -1.0, "speed" to 2.0)),
                SharedHitReaction("toxicdebris", "toxicdebris", "attackerSide", sideCondition = "toxicspikes"),
            ),
        )
        assertEquals(
            listOf("move:spikes"),
            SharedSearchProjectionRuntime.legalCandidates(state, 0, listOf(illegalSwitch, legalMove)).map { it.action.id },
        )

        val switched = SharedProjectedSearchAction(
            action = SearchAction("switch:bench", "switch", 80.0),
            side = 1,
            switchSlot = 1,
        )
        val next = SharedSearchProjectionRuntime.transition(state, legalMove, switched)
        assertEquals(4, next.turn)
        assertEquals(listOf(0, 1), next.active)
        assertEquals(40, next.hp[1][1])
        assertEquals(83, next.hp[0][0])
        assertEquals(3, next.hazards[1][1])
        assertEquals(1, next.hazards[0][2])
        assertEquals(3, next.ranks[0][0][0])
        assertEquals(-1, next.ranks[1][1][2])
        assertEquals(2, next.ranks[1][1][4])
        assertEquals(1, next.pressures[0][0].yawnTurns)
        assertEquals(false, next.gimmicksRemaining[0])
        assertEquals("raindance", next.field.weather?.id)
        assertEquals(5, next.field.weather?.turns)
        assertEquals("electricterrain", next.field.terrain?.id)
        assertEquals(5, next.field.pseudoWeather["trickroom"]?.turns)
        assertEquals(1, next.field.pseudoWeather["gravity"]?.turns)
        assertEquals(4, next.sideConditions[0]["tailwind"]?.turns)
        assertEquals(1, next.sideConditions[0]["reflect"]?.turns)

        val failedConditionalMove = legalMove.copy(
            damage = 90.0,
            successProbability = 0.0,
            weather = "",
            terrain = "",
            pseudoWeather = "",
            sideCondition = "",
        )
        val afterYawnExpires = SharedSearchProjectionRuntime.transition(
            next,
            failedConditionalMove,
            switched.copy(switchSlot = 0),
        )
        assertEquals(90, afterYawnExpires.hp[1][0])
        assertEquals(false, afterYawnExpires.pressures[0][0].yawn)
        assertEquals(2, afterYawnExpires.pressures[0][0].sleepTurns)
        assertEquals(4, afterYawnExpires.field.weather?.turns)
        assertEquals(4, afterYawnExpires.field.terrain?.turns)
        assertEquals(4, afterYawnExpires.field.pseudoWeather["trickroom"]?.turns)
        assertEquals(3, afterYawnExpires.sideConditions[0]["tailwind"]?.turns)
        assertEquals(null, afterYawnExpires.sideConditions[0]["reflect"])

        val lightClayScreen = SharedSearchFieldMoveCatalog.effect("Light Screen", "minecraft:light_clay")
        assertEquals("lightscreen", lightClayScreen.sideCondition)
        assertEquals(8, lightClayScreen.sideConditionDuration)
        assertEquals("snow", SharedSearchFieldMoveCatalog.effect("Snowscape").weather)
    }

    @Test
    fun sharedSearchProjectionCarriesTransformAndFormProfilesIntoNextTurn() {
        fun profile(id: String, ability: String, types: List<String>, speed: Int, side: Int, slot: Int) =
            SharedSearchCombatProfile(
                id = id,
                ability = ability,
                types = types,
                stats = SharedBattleStats(hp = 100, attack = 80, defence = 80, specialAttack = 80,
                    specialDefence = 80, speed = speed),
                moveSourceSide = side,
                moveSourceSlot = slot,
            )
        val lead = profile("lead", "pressure", listOf("water"), 70, 0, 0)
        val ditto = profile("ditto", "imposter", listOf("normal"), 48, 0, 1)
        val target = profile("dragapult", "infiltrator", listOf("dragon", "ghost"), 142, 1, 0)
        val state = SharedSearchProjectionState(
            active = listOf(0, 0),
            hp = listOf(listOf(100, 100), listOf(100)),
            maxHp = listOf(listOf(100, 100), listOf(100)),
            hazards = listOf(List(4) { 0 }, List(4) { 0 }),
            pressures = listOf(List(2) { SharedSearchPressure() }, listOf(SharedSearchPressure())),
            ranks = listOf(List(2) { List(5) { 0 } }, listOf(listOf(1, 2, 0, 0, 3))),
            heldItems = listOf(listOf("", "choicescarf"), listOf("lifeorb")),
            abilityStates = listOf(List(2) { emptySet() }, listOf(emptySet())),
            baseProfiles = listOf(listOf(lead, ditto), listOf(target)),
            profiles = listOf(listOf(lead, ditto), listOf(target)),
            formProfiles = listOf(List(2) { emptyMap() }, listOf(emptyMap())),
        )
        val imposter = SharedProjectedSearchAction(
            action = SearchAction("switch:ditto", "switch", 1.0), side = 0, switchSlot = 1,
            switchPhase = SharedSwitchPhaseResult(operations = listOf(
                SharedSwitchPhaseOperation("entry_adapter", "imposter", effect = "imposter", setState = "transformed"),
            )),
        )
        val idle = SharedProjectedSearchAction(SearchAction("move:splash", "move", 0.0), side = 1)
        val transformed = SharedSearchProjectionRuntime.transition(state, imposter, idle)
        assertEquals("dragapult", transformed.profiles[0][1].id)
        assertEquals("infiltrator", transformed.profiles[0][1].ability)
        assertEquals(1, transformed.profiles[0][1].moveSourceSide)
        assertEquals(0, transformed.profiles[0][1].moveSourceSlot)
        assertEquals(listOf(1, 2, 0, 0, 3), transformed.ranks[0][1])

        val returnToLead = SharedProjectedSearchAction(
            action = SearchAction("switch:lead", "switch", 1.0), side = 0, switchSlot = 0,
            switchPhase = SharedSwitchPhaseResult(),
        )
        val restored = SharedSearchProjectionRuntime.transition(transformed, returnToLead, idle)
        assertEquals("ditto", restored.profiles[0][1].id)
        assertEquals("imposter", restored.profiles[0][1].ability)
        assertEquals(0, restored.profiles[0][1].moveSourceSide)
        assertEquals(1, restored.profiles[0][1].moveSourceSlot)
    }

    @Test
    fun sharedSearchProjectionAppliesTraceForecastAndTeraShiftProfiles() {
        fun profile(id: String, ability: String, types: List<String>, speed: Int) = SharedSearchCombatProfile(
            id, ability, types,
            SharedBattleStats(100, 80, 80, 80, 80, speed), 0, 1,
        )
        val lead = profile("lead", "pressure", listOf("normal"), 50).copy(moveSourceSlot = 0)
        val target = profile("target", "swiftswim", listOf("water"), 90).copy(
            moveSourceSide = 1, moveSourceSlot = 0,
        )
        fun projected(
            incoming: SharedSearchCombatProfile,
            operation: SharedSwitchPhaseOperation,
            forms: Map<String, SharedSearchCombatProfile> = emptyMap(),
        ): SharedSearchCombatProfile {
            val state = SharedSearchProjectionState(
                active = listOf(0, 0), hp = listOf(listOf(100, 100), listOf(100)),
                maxHp = listOf(listOf(100, 100), listOf(100)),
                hazards = listOf(List(4) { 0 }, List(4) { 0 }),
                pressures = listOf(List(2) { SharedSearchPressure() }, listOf(SharedSearchPressure())),
                ranks = listOf(List(2) { List(5) { 0 } }, listOf(List(5) { 0 })),
                heldItems = listOf(listOf("", ""), listOf("")),
                abilityStates = listOf(List(2) { emptySet() }, listOf(emptySet())),
                baseProfiles = listOf(listOf(lead, incoming), listOf(target)),
                profiles = listOf(listOf(lead, incoming), listOf(target)),
                formProfiles = listOf(listOf(emptyMap(), forms), listOf(emptyMap())),
            )
            return SharedSearchProjectionRuntime.transition(
                state,
                SharedProjectedSearchAction(
                    SearchAction("switch:test", "switch", 1.0), side = 0, switchSlot = 1,
                    switchPhase = SharedSwitchPhaseResult(operations = listOf(operation)),
                ),
                SharedProjectedSearchAction(SearchAction("move:splash", "move", 0.0), side = 1),
            ).profiles[0][1]
        }

        val traced = projected(
            profile("porygon2", "trace", listOf("normal"), 60),
            SharedSwitchPhaseOperation(
                "entry_adapter", "trace", effect = "trace",
                details = mapOf("copiedAbility" to "swiftswim"),
            ),
        )
        assertEquals("swiftswim", traced.ability)

        val forecast = projected(
            profile("castform", "forecast", listOf("normal"), 70),
            SharedSwitchPhaseOperation(
                "entry_adapter", "forecast", effect = "forecast", details = mapOf("type" to "fire"),
            ),
        )
        assertEquals(listOf("fire"), forecast.types)

        val terastal = profile("terapagosterastal", "terashell", listOf("normal"), 85).copy(
            stats = SharedBattleStats(105, 95, 110, 105, 110, 85),
        )
        val shifted = projected(
            profile("terapagos", "terashift", listOf("normal"), 60),
            SharedSwitchPhaseOperation(
                "entry_adapter", "terashift", effect = "terashift",
                details = mapOf("form" to "terapagosterastal"),
            ),
            mapOf("terapagosterastal" to terastal),
        )
        assertEquals("terapagosterastal", shifted.id)
        assertEquals("terashell", shifted.ability)
        assertEquals(110, shifted.stats.defence)
    }

    @Test
    fun sharedSearchFieldCombatAppliesWeatherTerrainScreensAndSpeedTogether() {
        val result = SharedSearchFieldCombatEvaluator.evaluate(SharedSearchFieldCombatInput(
            field = SharedSearchFieldState(
                weather = SharedSearchTimedEffect("raindance", 3),
                terrain = SharedSearchTimedEffect("electricterrain", 3),
                pseudoWeather = mapOf("trickroom" to SharedSearchTimedEffect("trickroom", 3)),
            ),
            attackerSideConditions = mapOf("tailwind" to SharedSearchTimedEffect("tailwind", 2)),
            defenderSideConditions = mapOf("lightscreen" to SharedSearchTimedEffect("lightscreen", 2)),
            moveType = "electric",
            moveCategory = "special",
            attackerTypes = listOf("electric"),
            attackerAbility = "swiftswim",
            defenderTypes = listOf("water"),
        ))

        assertEquals(1.0, result.weatherDamageMultiplier)
        assertEquals(1.3, result.terrainDamageMultiplier)
        assertEquals(0.5, result.screenDamageMultiplier)
        assertEquals(4.0, result.speedMultiplier)
        assertTrue(result.trickRoomActive)
    }

    @Test
    fun sharedSearchCandidateGeneratorNormalizesEveryPlatformInput() {
        val generated = SharedSearchCandidateGenerator.generate(
            listOf(
                SharedSearchCandidateObservation(
                    id = "move:1",
                    kind = "MOVE",
                    score = 80.0,
                    successProbability = 1.4,
                    expectedDamage = 35.0,
                ),
                SharedSearchCandidateObservation(id = "move:1", score = 70.0),
                SharedSearchCandidateObservation(
                    id = "switch:2",
                    kind = "switch",
                    score = 90.0,
                    opponentKnockoutBeforeActionProbability = -0.5,
                ),
                SharedSearchCandidateObservation(id = "disabled", score = 999.0, disabled = true),
                SharedSearchCandidateObservation(id = "illegal", score = 999.0, legal = false),
            ),
        )

        assertEquals(listOf("switch:2", "move:1"), generated.map { it.id })
        assertEquals("move", generated[1].kind)
        assertEquals(1.0, generated[1].successProbability)
        assertEquals(0.0, generated[0].opponentKnockoutBeforeActionProbability)
    }

    @Test
    fun sharedSwitchMatchupDerivesEscapeAndCounterValue() {
        val result = SharedSwitchMatchupEvaluator.evaluate(
            SwitchMatchupFacts(
                currentHpRatio = 0.4,
                targetHpRatio = 0.8,
                currentIncomingDamage = 60.0,
                targetIncomingDamage = 0.0,
                currentIncomingDamageRatio = 0.6,
                targetIncomingDamageRatio = 0.0,
                currentOutgoingDamageRatio = 0.1,
                targetOutgoingDamageRatio = 0.7,
                hazardDamageRatio = 0.125,
                currentCanReachAction = false,
            ),
        )

        assertTrue(result.emergencyEscape)
        assertTrue(result.noEffectiveMoveEscape)
        assertEquals(167.5, result.matchupValue, 0.0000001)
        assertEquals(0.6, result.defensiveImprovement, 0.0000001)
        assertEquals(0.6, result.offensiveImprovement, 0.0000001)
    }

    @Test
    fun sharedSwitchObservationDerivesHazardsRatiosAndReachability() {
        val hazard = SharedSwitchMatchupEvaluator.entryHazardDamage(EntryHazardObservation(
            currentHp = 160.0,
            maximumHp = 200.0,
            stealthRockLayers = 1,
            spikesLayers = 2,
            rockEffectiveness = 2.0,
        ))
        val evaluation = SharedSwitchMatchupEvaluator.derive(SwitchMatchupObservation(
            currentHp = 40.0,
            currentMaximumHp = 100.0,
            targetHp = 160.0,
            targetMaximumHp = 200.0,
            opponentHp = 100.0,
            currentIncomingDamage = 60.0,
            targetIncomingDamage = 20.0,
            currentOutgoingDamage = 10.0,
            targetOutgoingDamage = 70.0,
            targetHazardDamage = hazard.damage,
            currentSpeed = 80.0,
            opponentSpeed = 100.0,
        ))

        assertEquals(83.0, hazard.damage)
        assertEquals(0.415, hazard.damageRatio, 0.0000001)
        assertEquals(77.0, hazard.hpAfterHazards)
        assertFalse(evaluation.reachability.actsBefore)
        assertFalse(evaluation.facts.currentCanReachAction)
        assertEquals(0.385, evaluation.facts.targetHpRatio, 0.0000001)
        assertEquals(0.415, evaluation.facts.hazardDamageRatio, 0.0000001)
        assertEquals(105.5, evaluation.result.matchupValue, 0.0000001)
        assertTrue(evaluation.result.emergencyEscape)
    }
}

private class WinRateFixture : SearchRuntime {
    override fun candidates(state: String, sideIndex: Int): List<SearchAction> = when (sideIndex) {
        0 -> listOf(action("safe", 100.0), action("risky", 90.0))
        else -> listOf(action("reply", 100.0))
    }

    override fun transition(state: String, sideZeroActionId: String, sideOneActionId: String): String =
        if (sideZeroActionId == "risky") "risky-result" else "safe-result"

    override fun winProbability(state: String, sideIndex: Int): Double = when (state) {
        "risky-result" -> 0.8
        "safe-result" -> 0.55
        else -> 0.5
    }

    override fun terminal(state: String): Boolean = false
}

private class TwoTurnFixture : SearchRuntime {
    override fun candidates(state: String, sideIndex: Int): List<SearchAction> {
        if (sideIndex == 1) return listOf(action("reply", 100.0))
        return when (state) {
            "root" -> listOf(action("setup", 100.0), action("damage", 99.0))
            "setup-result" -> listOf(action("finish", 100.0))
            "damage-result" -> listOf(action("chip", 100.0))
            else -> emptyList()
        }
    }

    override fun transition(state: String, sideZeroActionId: String, sideOneActionId: String): String = when {
        state == "root" && sideZeroActionId == "setup" -> "setup-result"
        state == "root" -> "damage-result"
        state == "setup-result" -> "setup-finish"
        else -> "damage-finish"
    }

    override fun winProbability(state: String, sideIndex: Int): Double = when (state) {
        "setup-result" -> 0.50
        "damage-result" -> 0.51
        "setup-finish" -> 0.90
        "damage-finish" -> 0.52
        else -> 0.5
    }

    override fun terminal(state: String): Boolean = false
}

private fun action(id: String, score: Double) = SearchAction(id = id, score = score)
