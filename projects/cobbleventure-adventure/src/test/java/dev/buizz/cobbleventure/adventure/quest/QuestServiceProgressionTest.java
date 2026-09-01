package dev.buizz.cobbleventure.adventure.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class QuestServiceProgressionTest {
    private static final MainQuestProgression PROGRESSION = new MainQuestProgression(
        true,
        List.of(
            new MainQuestProgression.Step("oak", "test:quest/main/oak", "test:npc/oak"),
            new MainQuestProgression.Step("parcel", "test:quest/main/parcel", "test:npc/clerk")
        )
    );

    @Test
    void keepsLaterNpcStepBlockedUntilEarlierQuestCompletes() {
        Map<String, QuestService.State> states = Map.of(
            "test:quest/main/oak", QuestService.State.ACTIVE,
            "test:quest/main/parcel", QuestService.State.ACTIVE
        );
        assertEquals("oak", QuestService.currentProgressionStep(PROGRESSION, states::get)
            .orElseThrow().id());
    }

    @Test
    void advancesToNextNpcAndEventuallyAllowsGymFallback() {
        Map<String, QuestService.State> second = Map.of(
            "test:quest/main/oak", QuestService.State.COMPLETED,
            "test:quest/main/parcel", QuestService.State.NOT_STARTED
        );
        assertEquals("parcel", QuestService.currentProgressionStep(PROGRESSION, second::get)
            .orElseThrow().id());
        assertTrue(QuestService.currentProgressionStep(
            PROGRESSION, ignored -> QuestService.State.COMPLETED
        ).isEmpty());
    }
}
