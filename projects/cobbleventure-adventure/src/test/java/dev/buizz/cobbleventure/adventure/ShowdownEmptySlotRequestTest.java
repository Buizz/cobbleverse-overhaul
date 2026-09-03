package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The engine's null active slot must not reach Cobblemon's non-null moveset codec. */
final class ShowdownEmptySlotRequestTest {
    private final Gson gson = new Gson();

    @Test
    void rawNullSlotReproducesTheReportedSanitizerFailure() {
        var request = gson.fromJson("""
            {"active":[{"moves":[]},null]}
            """, ShowdownActionRequest.class);
        assertThrows(NullPointerException.class, () -> request.getActive().get(1).getGimmicks());
    }

    @Test
    void emptyMovesetRetainsBothSlotsThroughTheActualCobblemonPacketCodec() {
        var request = gson.fromJson("""
            {"active":[{"moves":[{"id":"tackle","move":"Tackle","pp":35,"maxpp":35,
              "target":"normal","disabled":false}]},{"moves":[]}],"forceSwitch":[]}
            """, ShowdownActionRequest.class);
        // sanitize() enumerates gimmicks on every moveset before queuing the packet.
        request.getActive().forEach(moveset -> assertNotNull(moveset.getGimmicks()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            request.saveToBuffer(buffer);
            var decoded = new ShowdownActionRequest(false, null, List.of(), false, null)
                .loadFromBuffer(buffer);
            assertEquals(2, decoded.getActive().size());
            assertEquals(1, decoded.getActive().get(0).getMoves().size());
            assertTrue(decoded.getActive().get(1).getMoves().isEmpty());
            assertTrue(decoded.getActive().get(1).getGimmicks().isEmpty());
            assertTrue(decoded.getForceSwitch().isEmpty());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void forceSwitchAndWaitRequestsStillHaveNoActiveMovesets() {
        var switching = gson.fromJson("{\"forceSwitch\":[true,false]}", ShowdownActionRequest.class);
        assertNull(switching.getActive());
        assertEquals(List.of(true, false), switching.getForceSwitch());
        var waiting = gson.fromJson("{\"wait\":true}", ShowdownActionRequest.class);
        assertTrue(waiting.getWait());
        assertNull(waiting.getActive());
    }
}
