package dev.buizz.cobbleventure.ai.core

import kotlinx.serialization.Serializable

/** 플랫폼 어댑터가 계산한 관측 사실을 공통 규칙 엔진에 전달하는 안정된 경계다. */
@Serializable
data class RuleFactBag(
    val kind: String,
    val numbers: Map<String, Double> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val stringLists: Map<String, List<String>> = emptyMap(),
) {
    fun number(key: String, fallback: Double = 0.0): Double =
        numbers[key]?.takeIf { it.isFinite() } ?: fallback

    fun optionalNumber(key: String): Double? = numbers[key]?.takeIf { it.isFinite() }

    fun flag(key: String): Boolean = flags[key] == true

    fun optionalFlag(key: String): Boolean? = flags[key]

    fun string(key: String): String = strings[key].orEmpty()

    fun hasTag(tag: String): Boolean = tag in tags

    fun strings(key: String): List<String> = stringLists[key].orEmpty()
}
