package dev.buizz.cobbleventure.adventure;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrainerBattleRuntimeIndexTest {
    @Test
    void finishingOneConcurrentBattlePreservesTheOtherRuntime() {
        TrainerBattleRuntimeIndex<Object> index = new TrainerBattleRuntimeIndex<>();
        Object first = new Object();
        Object second = new Object();
        UUID firstBattle = UUID.randomUUID();
        UUID secondBattle = UUID.randomUUID();

        index.register("runtime/first", first);
        index.register("runtime/second", second);
        assertTrue(index.activate(firstBattle, runtime -> runtime == first));
        assertTrue(index.activate(secondBattle, runtime -> runtime == second));

        assertSame(first, index.finish(firstBattle));
        assertSame(second, index.finish(secondBattle));
    }
}
