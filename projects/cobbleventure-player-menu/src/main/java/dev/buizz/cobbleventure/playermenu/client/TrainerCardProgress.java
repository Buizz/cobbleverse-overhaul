package dev.buizz.cobbleventure.playermenu.client;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 트레이너 카드가 표시할 진행 정보의 클라이언트 모델이다.
 *
 * <p>지역 수와 지역별 관장/리그 구성은 의도적으로 가변 목록이다. 서버 진행도 동기화가
 * 추가되면 {@link #current()}만 서버가 내려준 값으로 교체하고 화면 배치는 유지한다.</p>
 */
record TrainerCardProgress(List<LeaguePage> pages) {
    private static final int UNDECIDED_PREVIEW_SLOTS = 8;

    static TrainerCardProgress current() {
        List<LeaguePage> configuredPages = LeagueProgressionContent.pages();
        if (!configuredPages.isEmpty()) return new TrainerCardProgress(configuredPages);
        return new TrainerCardProgress(List.of(new LeaguePage(
            Component.translatable("screen.cobbleventure_player_menu.trainer_card.region.undecided"),
            buildPreviewChallenges(),
            false
        )));
    }

    private static List<Challenge> buildPreviewChallenges() {
        return java.util.stream.IntStream.range(0, UNDECIDED_PREVIEW_SLOTS)
            .mapToObj(index -> new Challenge(Component.empty(), false, ChallengeKind.GYM))
            .toList();
    }

    record LeaguePage(Component title, List<Challenge> challenges, boolean leagueCleared) {
        LeaguePage {
            challenges = List.copyOf(challenges);
        }
    }

    record Challenge(Component name, boolean completed, ChallengeKind kind) {
    }

    enum ChallengeKind {
        GYM,
        LEAGUE,
        CHAMPION
    }
}
