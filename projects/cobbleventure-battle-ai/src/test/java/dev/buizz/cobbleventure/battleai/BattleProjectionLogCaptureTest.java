package dev.buizz.cobbleventure.battleai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BattleProjectionLogCaptureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void remainsDisabledUnlessTheCaptureDirectoryIsExplicitlyConfigured() {
        String previous = System.clearProperty(BattleProjectionLogCapture.DIRECTORY_PROPERTY);
        UUID battleId = UUID.randomUUID();
        Path unexpected = Path.of(battleId + ".showdown.log");
        try {
            BattleProjectionLogCapture.capture(battleId, List.of("|turn|1"));
            assertFalse(Files.exists(unexpected));
        } finally {
            restore(previous);
        }
    }

    @Test
    void atomicallyKeepsTheLatestCumulativeProtocolLog() throws Exception {
        String previous = System.setProperty(
                BattleProjectionLogCapture.DIRECTORY_PROPERTY, temporaryDirectory.toString());
        UUID battleId = UUID.randomUUID();
        try {
            BattleProjectionLogCapture.capture(battleId, List.of(
                    "noise that must not be retained",
                    "|turn|1\n|switch|p1a: Hero|Hero, L50|100/100"));
            Path output = temporaryDirectory.resolve(battleId + ".showdown.log");
            assertTrue(Files.exists(output));
            assertEquals("|turn|1\n|switch|p1a: Hero|Hero, L50|100/100\n", Files.readString(output));

            BattleProjectionLogCapture.capture(battleId, List.of("|turn|1", "|turn|2"));
            assertEquals("|turn|1\n|turn|2\n", Files.readString(output));
            try (var files = Files.list(temporaryDirectory)) {
                assertEquals(1, files.count());
            }
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(BattleProjectionLogCapture.DIRECTORY_PROPERTY);
        else System.setProperty(BattleProjectionLogCapture.DIRECTORY_PROPERTY, previous);
    }
}
