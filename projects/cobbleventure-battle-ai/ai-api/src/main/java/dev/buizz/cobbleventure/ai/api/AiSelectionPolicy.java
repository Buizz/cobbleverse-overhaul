package dev.buizz.cobbleventure.ai.api;

/** 실제 전투 런타임이 선택할 판단 구현과 정보 권한을 구분한다. */
public enum AiSelectionPolicy {
    HEURISTIC,
    WIN_PROBABILITY,
    TWO_TURN_SEARCH,
    COMMITTED_ACTION_COUNTER
}
