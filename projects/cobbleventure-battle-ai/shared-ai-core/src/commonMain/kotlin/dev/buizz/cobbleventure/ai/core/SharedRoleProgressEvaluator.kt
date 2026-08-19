@file:OptIn(ExperimentalJsExport::class)

package dev.buizz.cobbleventure.ai.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.math.max
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class RoleProgressInput(
    val roleScores: Map<String, Double> = emptyMap(),
    val primaryRole: String = "",
    val aceQualified: Boolean = false,
    val hazardSetConditions: List<String> = emptyList(),
    val hazardMaxLayers: Map<String, Double> = emptyMap(),
    val opponentHazardLayers: Map<String, Double> = emptyMap(),
    val hasHazardRemoval: Boolean = false,
    val ownHazardLayers: Double = 0.0,
    val opponentHazardSetterAlive: Boolean = false,
    val opponentLivingCount: Int = 0,
    val highThreatCount: Int = 0,
    val setupThreatCount: Int = 0,
    val assignedThreats: List<String> = emptyList(),
    val mustPreserveResource: Boolean = false,
    val activeTurns: Int = 0,
)

@Serializable
data class RoleProgressResult(
    val roleComplete: Boolean,
    val expendableResource: Boolean,
    val completedRoles: List<String>,
    val remainingRoles: List<String>,
    val auxiliaryRoles: List<String>,
    val hazardSetComplete: Boolean,
    val hazardRemovalComplete: Boolean,
    val assignedThreats: List<String>,
    val reasons: List<String>,
)

/** 역할 프로필과 현재 판 상태에서 역할 완료/보존 여부를 공통 계산한다. */
object SharedRoleProgressEvaluator {
    fun evaluate(input: RoleProgressInput): RoleProgressResult {
        val roles = input.roleScores.filterValues { it > 0.0 }.keys.toList()
        val primaryScore = input.roleScores[input.primaryRole] ?: 0.0
        val threshold = max(2.5, primaryScore * 0.4)
        val tracked = roles.filter { role ->
            if (role != input.primaryRole && (input.roleScores[role] ?: 0.0) < threshold) return@filter false
            when (role) {
                "ace" -> input.aceQualified
                "support", "pivot" -> input.primaryRole == role
                else -> true
            }
        }
        val auxiliary = roles.filterNot { it in tracked }
        val hazardSetComplete = input.hazardSetConditions.isNotEmpty() && input.hazardSetConditions.all {
            (input.opponentHazardLayers[it] ?: 0.0) >= (input.hazardMaxLayers[it] ?: 1.0)
        }
        val hazardRemovalComplete = input.hasHazardRemoval && input.ownHazardLayers <= 0.0 && !input.opponentHazardSetterAlive
        val completed = mutableListOf<String>()
        val remaining = mutableListOf<String>()
        tracked.forEach { role ->
            val complete = when (role) {
                "lead" -> input.activeTurns > 0 || hazardSetComplete
                "hazardControl" ->
                    (input.hazardSetConditions.isEmpty() || hazardSetComplete) &&
                        (!input.hasHazardRemoval || hazardRemovalComplete)
                "revengeKiller" -> input.highThreatCount <= 0
                "disruptor" -> input.setupThreatCount <= 0
                "wall" -> input.assignedThreats.isEmpty() && input.highThreatCount <= 0 && input.opponentLivingCount > 0
                else -> input.opponentLivingCount <= 0
            }
            if (complete) completed += role else remaining += role
        }
        val reasons = buildList {
            if (input.hazardSetConditions.isNotEmpty()) {
                add(if (hazardSetComplete) {
                    "설치 임무 완료: ${input.hazardSetConditions.joinToString(", ")} 최대 층수"
                } else {
                    val pending = input.hazardSetConditions.filter {
                        (input.opponentHazardLayers[it] ?: 0.0) < (input.hazardMaxLayers[it] ?: 1.0)
                    }
                    "설치 임무 남음: ${pending.joinToString(", ")}"
                })
            }
            if (input.hasHazardRemoval) add(when {
                hazardRemovalComplete -> "제거 임무 완료: 아군 설치물 없음, 상대 설치 요원 없음"
                input.ownHazardLayers > 0.0 -> "제거 임무 남음: 아군 쪽 설치물 ${compact(input.ownHazardLayers)}층"
                else -> "제거 임무 남음: 상대 설치 요원 생존"
            })
            if (input.assignedThreats.isNotEmpty()) add("담당 위협 생존: ${input.assignedThreats.joinToString(", ")}")
        }
        val roleComplete = tracked.isNotEmpty() && remaining.isEmpty() && input.opponentLivingCount > 0
        return RoleProgressResult(
            roleComplete,
            roleComplete && !input.mustPreserveResource && !input.aceQualified,
            completed, remaining, auxiliary, hazardSetComplete, hazardRemovalComplete,
            input.assignedThreats, reasons,
        )
    }
}

@JsExport
fun evaluateRoleProgressJson(inputJson: String): String =
    codec.encodeToString(SharedRoleProgressEvaluator.evaluate(codec.decodeFromString<RoleProgressInput>(inputJson)))

private fun compact(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
