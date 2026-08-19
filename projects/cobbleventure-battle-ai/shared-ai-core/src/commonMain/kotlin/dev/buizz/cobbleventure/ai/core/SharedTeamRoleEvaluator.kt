@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class TeamRoleStatsInput(
    val hp: Double = 0.0,
    val attack: Double = 0.0,
    val defense: Double = 0.0,
    val specialAttack: Double = 0.0,
    val specialDefense: Double = 0.0,
    val speed: Double = 0.0,
)

@Serializable
data class ManualAceInput(
    val forced: Boolean = false,
    val blocked: Boolean = false,
    val priority: Double = 0.0,
    val reason: String = "",
)

@Serializable
data class TeamRoleMemberInput(
    val slot: Int,
    val pokemonId: String = "",
    val species: String = "",
    val level: Double = 0.0,
    val ability: String = "",
    val stats: TeamRoleStatsInput = TeamRoleStatsInput(),
    val moveIds: List<String> = emptyList(),
    val catalogRoleScores: Map<String, Double> = emptyMap(),
    val catalogTags: List<String> = emptyList(),
    val catalogReasons: List<String> = emptyList(),
    val hasBatonPassSetupMove: Boolean = false,
    val manualAce: ManualAceInput = ManualAceInput(),
    val gimmickAceValue: Double = 0.0,
    val gimmickReasons: List<String> = emptyList(),
    val speciesAceScore: Double = 0.0,
)

@Serializable
data class TeamRoleInput(val members: List<TeamRoleMemberInput> = emptyList())

@Serializable
data class TeamRoleScore(val role: String, val score: Double)

@Serializable
data class AceProfileResult(
    val score: Double,
    val qualifies: Boolean,
    val tier: String = "none",
    val manual: ManualAceInput = ManualAceInput(),
    val reasons: List<String> = emptyList(),
    val offense: Double = 0.0,
    val speed: Double = 0.0,
    val hasSetup: Boolean = false,
    val setupAbility: Boolean = false,
    val batonPassSupport: Boolean = false,
    val hasSetupRoute: Boolean = false,
    val estimatedKoCapacity: Int = 1,
    val offensiveAnchor: Boolean = false,
)

@Serializable
data class BatonPassProfileResult(
    val qualifies: Boolean = false,
    val hasBatonPass: Boolean = false,
    val hasSetupMove: Boolean = false,
    val setupAbility: String = "",
)

@Serializable
data class TeamMemberRoleResult(
    val slot: Int,
    val pokemonId: String,
    val species: String,
    val primaryRole: String,
    val roles: List<TeamRoleScore>,
    val roleScores: Map<String, Double>,
    val rawRoleScores: Map<String, Double>,
    val aceScore: Double,
    val aceProfile: AceProfileResult,
    val batonPassProfile: BatonPassProfileResult,
    val moveIds: List<String>,
    val reasons: List<String>,
    val warnings: List<String>,
)

@Serializable
data class HazardRolePlan(
    val setters: List<TeamMemberRoleResult> = emptyList(),
    val removers: List<TeamMemberRoleResult> = emptyList(),
)

@Serializable
data class TeamRoleResult(
    val roles: List<TeamMemberRoleResult> = emptyList(),
    val aceCandidates: List<TeamMemberRoleResult> = emptyList(),
    val subAceCandidates: List<TeamMemberRoleResult> = emptyList(),
    val defensiveCore: List<TeamMemberRoleResult> = emptyList(),
    val speedControl: List<TeamMemberRoleResult> = emptyList(),
    val hazardPlan: HazardRolePlan = HazardRolePlan(),
    val setupThreats: List<TeamMemberRoleResult> = emptyList(),
    val vulnerabilities: List<String> = emptyList(),
)

/** 플랫폼이 관측한 기술 카탈로그 사실과 능력치를 팀 역할/에이스 결정으로 변환한다. */
object SharedTeamRoleEvaluator {
    private val roles = listOf(
        "lead", "ace", "subAce", "setupSweeper", "wall", "pivot",
        "hazardControl", "revengeKiller", "disruptor", "support",
    )
    private val setupAbilities = setOf(
        "asoneglastrier", "asonespectrier", "beastboost", "chillingneigh", "competitive",
        "contrary", "defiant", "download", "grimneigh", "intrepidsword", "moxie",
        "soulheart", "speedboost",
    )

    fun evaluate(input: TeamRoleInput): TeamRoleResult {
        val levels = input.members.map { it.level }.filter { it > 0.0 }.sortedDescending()
        val maximumLevel = levels.firstOrNull() ?: 0.0
        val secondLevel = levels.firstOrNull { it < maximumLevel } ?: maximumLevel
        val batonSupport = input.members.any { "batonpass" in it.moveIds }
        val teamSetupRoute = batonSupport || input.members.any {
            it.hasBatonPassSetupMove || "setupboost" in it.catalogTags || it.ability in setupAbilities
        }
        val observed = input.members.mapIndexed { index, member ->
            analyzeMember(member, index, maximumLevel, secondLevel, batonSupport)
        }
        val finalized = finalizeAces(observed, teamSetupRoute)
        fun byRole(role: String) = finalized.filter { it.roles.any { candidate -> candidate.role == role } }
            .sortedByDescending { it.roleScores[role] ?: 0.0 }
        val ace = finalized.filter { it.aceProfile.qualifies }
        val subAces = finalized.filter { it.aceProfile.tier == "subAce" }
        val defensive = byRole("wall").take(3)
        val speed = (byRole("revengeKiller") + byRole("pivot")).distinctBy { it.slot }.take(4)
        val setters = byRole("hazardControl").filter { member ->
            input.members.firstOrNull { it.slot == member.slot }?.catalogTags?.contains("hazardset") == true
        }.take(3)
        val removers = byRole("hazardControl").filter { member ->
            input.members.firstOrNull { it.slot == member.slot }?.catalogTags?.contains("hazardremove") == true
        }.take(3)
        return TeamRoleResult(
            finalized, ace, subAces, defensive, speed, HazardRolePlan(setters, removers),
            byRole("setupSweeper").take(3),
            buildList {
                if (ace.isEmpty()) add("명확한 에이스 후보가 약합니다.")
                if (defensive.isEmpty()) add("안정적인 막이 후보가 부족합니다.")
                if (removers.isEmpty()) add("설치물 제거 수단이 확인되지 않았습니다.")
            },
        )
    }

    private fun analyzeMember(
        member: TeamRoleMemberInput,
        index: Int,
        maximumLevel: Double,
        secondLevel: Double,
        batonSupport: Boolean,
    ): TeamMemberRoleResult {
        val scores = linkedMapOf<String, Double>().also { target ->
            roles.forEach { target[it] = 0.0 }
            member.catalogRoleScores.forEach { (role, score) -> target[role] = (target[role] ?: 0.0) + score }
        }
        val tags = member.catalogTags.toSet()
        val reasons = member.catalogReasons.map { "포켓몬 기본 역할: $it" }.toMutableList()
        val warnings = buildList {
            if (member.moveIds.isEmpty()) add("기술 정보 없음")
            if (member.stats.attack == 0.0 && member.stats.specialAttack == 0.0 &&
                member.stats.hp + member.stats.defense + member.stats.specialDefense == 0.0) add("능력치 정보 없음")
        }
        fun add(role: String, value: Double, reason: String) {
            scores[role] = (scores[role] ?: 0.0) + value
            reasons += reason
        }
        val offense = max(member.stats.attack, member.stats.specialAttack)
        val bulk = member.stats.hp + member.stats.defense + member.stats.specialDefense
        if (index == 0) add("lead", 1.2, "선봉 슬롯이라 초반 판 만들기 가능성을 봤습니다.")
        if (offense >= 115.0) add("ace", 2.4, "공격 능력치가 높아 에이스/돌파 역할 후보입니다.")
        if (member.stats.speed >= 100.0) {
            add("revengeKiller", 1.8, "스피드가 높아 복수 처리와 마무리 역할을 기대할 수 있습니다.")
            scores["ace"] = (scores["ace"] ?: 0.0) + 0.7
        }
        if (bulk >= 300.0) add("wall", 2.2, "내구 합이 높아 교체 받이와 장기전 자원으로 봤습니다.")
        if ("setupboost" in tags) add("setupSweeper", 2.5, "랭크업 기술을 보유해 전개형 스위퍼 후보입니다.")
        if ("recovery" in tags) {
            add("wall", 1.8, "회복기를 보유해 막이/유지력 역할 가치가 있습니다.")
            scores["support"] = (scores["support"] ?: 0.0) + 0.8
        }
        if ("pivot" in tags) add("pivot", 2.2, "피벗 기술로 유리 대면을 연결할 수 있습니다.")
        val hasBaton = "batonpass" in member.moveIds
        val batonAbility = member.ability in setOf("speedboost", "moody")
        if (hasBaton && (member.hasBatonPassSetupMove || batonAbility)) {
            add("pivot", 1.6, "랭크업 수단과 배턴터치를 함께 보유해 에이스 전개 요원으로 평가합니다.")
            scores["support"] = (scores["support"] ?: 0.0) + 1.2
        }
        if ("hazardset" in tags || "hazardremove" in tags) add("hazardControl", 2.2, "설치물 설치/제거로 판 관리 역할을 맡을 수 있습니다.")
        if ("priority" in tags || "finisher" in tags) add("revengeKiller", 1.8, "선공기/마무리 태그로 복수 처리 가치가 있습니다.")
        if ("disrupt" in tags || "setupanswer" in tags) add("disruptor", 1.8, "상대 전개를 끊는 방해 기술 가치가 있습니다.")
        val ace = aceProfile(member, scores, maximumLevel, secondLevel, batonSupport)
        val displayed = scores.toMutableMap().also { if (!ace.qualifies) it["ace"] = 0.0 }
        val top = topRoles(displayed)
        return TeamMemberRoleResult(
            member.slot, member.pokemonId, member.species, top.firstOrNull()?.role ?: "support",
            top, displayed, scores, ace.score, ace,
            BatonPassProfileResult(hasBaton && (member.hasBatonPassSetupMove || batonAbility), hasBaton,
                member.hasBatonPassSetupMove, if (batonAbility) member.ability else ""),
            member.moveIds, reasons.take(4), warnings,
        )
    }

    private fun aceProfile(
        member: TeamRoleMemberInput,
        scores: Map<String, Double>,
        maximumLevel: Double,
        secondLevel: Double,
        batonSupport: Boolean,
    ): AceProfileResult {
        val manual = member.manualAce
        if (manual.blocked) return AceProfileResult(-1_000_000.0, false, manual = manual, reasons = listOf(manual.reason))
        val offense = max(member.stats.attack, member.stats.specialAttack)
        val speed = member.stats.speed
        val baseTotal = member.stats.run { hp + attack + defense + specialAttack + specialDefense + speed }
        val rawAce = max(0.0, scores["ace"] ?: 0.0)
        val setupScore = max(0.0, scores["setupSweeper"] ?: 0.0)
        val revenge = max(0.0, scores["revengeKiller"] ?: 0.0)
        val utility = listOf("wall", "support", "hazardControl", "disruptor", "pivot").maxOf { scores[it] ?: 0.0 }
        val highest = member.level > 0.0 && maximumLevel > 0.0 && member.level >= maximumLevel
        val levelGap = if (highest) member.level - secondLevel else 0.0
        val hasSetup = "setupboost" in member.catalogTags || setupScore >= 3.5
        val setupAbility = member.ability in setupAbilities
        val hasRoute = hasSetup || setupAbility || batonSupport
        val reasons = mutableListOf<String>()
        var score = 0.0
        fun plus(value: Double, reason: String) { score += value; reasons += reason }
        if (manual.forced) plus(100.0 + manual.priority, manual.reason)
        if (rawAce > 0.0) plus(min(4.5, rawAce * 0.55), "공격 역할 성향 ${round1(rawAce)}")
        when {
            offense >= 145.0 -> plus(3.2, "매우 높은 공격 능력치")
            offense >= 125.0 -> plus(2.3, "높은 공격 능력치")
            offense >= 115.0 -> plus(1.4, "공격 능력치 우수")
        }
        when {
            speed >= 120.0 -> plus(2.2, "매우 빠른 스피드")
            speed >= 100.0 -> plus(1.4, "빠른 스피드")
            speed >= 85.0 -> plus(0.6, "준수한 스피드")
        }
        if (hasSetup) plus(2.6, "랭크업 전개 가능")
        if (setupAbility) plus(1.8, "특성으로 랭크업 가능")
        if (batonSupport && !hasSetup) plus(1.2, "팀의 배턴터치 전개 수혜")
        if (revenge >= 3.0) plus(0.8, "마무리/복수 처리 성향")
        when {
            baseTotal >= 670.0 -> plus(3.0, "초전설급 종족값")
            baseTotal >= 600.0 -> plus(2.2, "높은 종족값")
            baseTotal >= 570.0 -> plus(1.3, "준전설급 종족값")
            baseTotal >= 530.0 -> plus(0.7, "평균 이상 종족값")
        }
        if (highest) plus(if (levelGap >= 5.0) 2.4 else 1.1, if (levelGap >= 5.0) "파티 내 고레벨 에이스 후보" else "파티 내 최고 레벨")
        if (member.gimmickAceValue > 0.0) { score += member.gimmickAceValue; reasons += member.gimmickReasons }
        val anchor = manual.forced || member.gimmickAceValue > 0.0 || offense >= 115.0 || speed >= 100.0 ||
            hasSetup || baseTotal >= 570.0 || member.speciesAceScore >= 2.4 || levelGap >= 5.0
        if (!anchor && utility >= rawAce) plus(-3.5, "방어/지원 성향이 더 강해 에이스 제외 경향")
        else if (utility >= rawAce + 2.0 && !manual.forced && member.gimmickAceValue <= 0.0) plus(-1.5, "막이/지원 역할 보존")
        val capacity = if (manual.forced || (hasRoute && (offense >= 100.0 || rawAce >= 2.0 || member.gimmickAceValue > 0.0)) ||
            (offense >= 135.0 && speed >= 100.0)) 2 else 1
        return AceProfileResult(round2(score), manual.forced || (score >= 5.8 && anchor), manual = manual,
            reasons = reasons, offense = offense, speed = speed, hasSetup = hasSetup,
            setupAbility = setupAbility, batonPassSupport = batonSupport, hasSetupRoute = hasRoute,
            estimatedKoCapacity = capacity, offensiveAnchor = anchor)
    }

    private fun finalizeAces(entries: List<TeamMemberRoleResult>, hasTeamSetupRoute: Boolean): List<TeamMemberRoleResult> {
        val candidates = entries.filter { !it.aceProfile.manual.blocked }.sortedWith(
            compareByDescending<TeamMemberRoleResult> { it.aceProfile.manual.forced }
                .thenByDescending { it.aceProfile.manual.priority }.thenByDescending { it.aceScore }.thenBy { it.slot })
        val strongest = candidates.filter { it.aceProfile.offensiveAnchor || it.aceProfile.manual.forced }
            .sortedWith(compareByDescending<TeamMemberRoleResult> { it.aceProfile.offense }
                .thenByDescending { it.aceScore }.thenBy { it.slot })
        val primary = candidates.firstOrNull { it.aceProfile.manual.forced }
            ?: if (!hasTeamSetupRoute) strongest.firstOrNull() else candidates.firstOrNull {
                it.aceProfile.estimatedKoCapacity >= 2 && it.aceProfile.offensiveAnchor
            } ?: strongest.firstOrNull()
        val subSlots = candidates.filter { it.slot != primary?.slot && primary != null &&
            (it.aceProfile.qualifies || it.aceProfile.estimatedKoCapacity >= 2 ||
                (it.aceProfile.offensiveAnchor && primary.aceScore - it.aceScore <= 3.0)) }.take(2).map { it.slot }.toSet()
        return entries.map { entry ->
            val isAce = entry.slot == primary?.slot
            val isSub = entry.slot in subSlots
            val scores = entry.rawRoleScores.toMutableMap().also {
                it["ace"] = if (isAce) max(3.0, entry.rawRoleScores["ace"] ?: 0.0) else 0.0
                it["subAce"] = if (isSub) max(1.5, min(4.5, entry.aceScore * 0.35)) else 0.0
            }
            val top = topRoles(scores)
            val prefix = when {
                isAce -> if (!hasTeamSetupRoute) "에이스 확정: 팀에 확실한 랭크업 경로가 없어 가장 높은 공격 능력을 우선했습니다."
                    else "에이스 확정: 최소 ${entry.aceProfile.estimatedKoCapacity}명을 처리할 전개 잠재력을 가진 팀 내 최우선 후보입니다."
                isSub -> "준에이스 판단: 에이스 점수 ${entry.aceScore}로 최종 에이스 다음 공격 자원입니다."
                else -> null
            }
            entry.copy(primaryRole = top.firstOrNull()?.role ?: "support", roles = top, roleScores = scores,
                aceProfile = entry.aceProfile.copy(qualifies = isAce, tier = if (isAce) "ace" else if (isSub) "subAce" else "none"),
                reasons = (listOfNotNull(prefix) + entry.reasons).take(4))
        }
    }

    private fun topRoles(scores: Map<String, Double>) = scores.map { TeamRoleScore(it.key, round2(it.value)) }
        .filter { it.score > 0.0 }.sortedByDescending { it.score }.take(4)
    private fun round1(value: Double) = round(value * 10.0) / 10.0
    private fun round2(value: Double) = round(value * 100.0) / 100.0
}

@JsExport
fun analyzeSharedTeamProfileJson(inputJson: String): String =
    codec.encodeToString(SharedTeamRoleEvaluator.evaluate(codec.decodeFromString<TeamRoleInput>(inputJson)))
