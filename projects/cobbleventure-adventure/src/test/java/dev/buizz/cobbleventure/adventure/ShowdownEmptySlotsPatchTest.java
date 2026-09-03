package dev.buizz.cobbleventure.adventure;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ShowdownEmptySlotsPatchTest {
    @Test
    void everyRuleIsIdempotentAndUnknownEngineTextFailsExplicitly() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/showdown-empty-slots.json"))) {
            for (var file : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                for (var rule : file.getValue().getAsJsonArray()) {
                    String from = rule.getAsJsonObject().get("from").getAsString();
                    String to = rule.getAsJsonObject().get("to").getAsString();
                    String result = ShowdownEmptySlotsPatch.replace(from + "\n" + from, from, to);
                    assertEquals(to + "\n" + to, result);
                    assertEquals(result, ShowdownEmptySlotsPatch.replace(result, from, to));
                    assertThrows(IllegalStateException.class, () ->
                        ShowdownEmptySlotsPatch.replace("different engine version", from, to));
                }
            }
        }
    }
}
