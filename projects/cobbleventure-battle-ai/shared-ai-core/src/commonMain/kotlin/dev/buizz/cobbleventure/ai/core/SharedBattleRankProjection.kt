package dev.buizz.cobbleventure.ai.core

/** 웹과 JVM 전투 전이가 함께 사용하는 랭크 배열과 피해 배율 계산. */
object SharedBattleRankProjection {
    const val ATTACK = 0
    const val SPECIAL_ATTACK = 1
    const val DEFENCE = 2
    const val SPECIAL_DEFENCE = 3
    const val SPEED = 4
    const val COUNT = 5

    fun apply(ranks: IntArray, changes: Map<String, Double>) {
        changes.forEach { (stat, amount) ->
            val index = index(stat)
            if (index >= 0) ranks[index] =
                (ranks[index] + kotlin.math.floor(amount + 0.5).toInt()).coerceIn(-6, 6)
        }
    }

    fun batonPass(sideRanks: Array<IntArray>, sourceIndex: Int, targetIndex: Int) {
        sideRanks[targetIndex] = sideRanks[sourceIndex].copyOf()
        sideRanks[sourceIndex].fill(0)
    }

    fun multiplier(stage: Double): Double {
        val bounded = stage.coerceIn(-6.0, 6.0)
        return if (bounded >= 0.0) (2.0 + bounded) / 2.0 else 2.0 / (2.0 - bounded)
    }

    fun adjustDamage(
        damage: Double,
        actualAttackStage: Double,
        projectedAttackStage: Double,
        actualDefenceStage: Double,
        projectedDefenceStage: Double,
    ): Double = damage *
        multiplier(projectedAttackStage) / multiplier(actualAttackStage) *
        multiplier(actualDefenceStage) / multiplier(projectedDefenceStage)

    private fun index(stat: String): Int = when (stat) {
        "attack" -> ATTACK
        "specialAttack", "specialattack" -> SPECIAL_ATTACK
        "defence", "defense" -> DEFENCE
        "specialDefence", "specialdefence", "specialDefense", "specialdefense" -> SPECIAL_DEFENCE
        "speed" -> SPEED
        else -> -1
    }
}
