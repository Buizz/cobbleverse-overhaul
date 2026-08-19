package dev.buizz.cobbleventure.adventure.battleai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/** 명시적으로 활성화된 개발 서버에서만 누적 Showdown 프로토콜 로그를 보존한다. */
final class BattleProjectionLogCapture {
    static final String DIRECTORY_PROPERTY = "cobbleventure.ai.projectionLogCaptureDir";

    private BattleProjectionLogCapture() {}

    static void capture(UUID battleId, List<String> battleLog) {
        String configured = System.getProperty(DIRECTORY_PROPERTY, "").trim();
        if (configured.isEmpty() || battleId == null || battleLog == null) return;
        try {
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path destination = directory.resolve(battleId + ".showdown.log").normalize();
            if (!destination.startsWith(directory)) return;
            StringBuilder content = new StringBuilder();
            for (String batch : battleLog) {
                if (batch == null || batch.isBlank()) continue;
                for (String line : batch.split("\\R")) {
                    if (line.startsWith("|")) content.append(line).append('\n');
                }
            }
            Path temporary = Files.createTempFile(directory, "." + battleId + '-', ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException ignored) {
            // 진단 캡처 실패가 전투 AI 선택을 중단해서는 안 된다.
        }
    }
}
