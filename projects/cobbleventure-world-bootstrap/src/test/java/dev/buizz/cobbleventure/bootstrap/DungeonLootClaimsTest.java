package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DungeonLootClaimsTest {
    private static final UUID RED = UUID.fromString(
        "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID LEAF = UUID.fromString(
        "00000000-0000-0000-0000-000000000002"
    );

    @Test
    void perPlayerAllowsOneClaimForEachParticipant() {
        DungeonLootClaims claims = new DungeonLootClaims();

        assertEquals(
            DungeonLootClaims.ClaimResult.CLAIMED,
            claims.claim("per_player", "cache", RED)
        );
        assertEquals(
            DungeonLootClaims.ClaimResult.ALREADY_CLAIMED,
            claims.claim("per_player", "cache", RED)
        );
        assertEquals(
            DungeonLootClaims.ClaimResult.CLAIMED,
            claims.claim("per_player", "cache", LEAF)
        );
    }

    @Test
    void firstClaimAllowsOnlyTheFirstParticipant() {
        DungeonLootClaims claims = new DungeonLootClaims();

        assertEquals(
            DungeonLootClaims.ClaimResult.CLAIMED,
            claims.claim("first_claim", "cache", RED)
        );
        assertEquals(
            DungeonLootClaims.ClaimResult.ALREADY_CLAIMED,
            claims.claim("first_claim", "cache", LEAF)
        );
    }

    @Test
    void failedGrantCanReleaseTheClaimForRetry() {
        DungeonLootClaims claims = new DungeonLootClaims();
        claims.claim("per_player", "cache", RED);

        claims.release("cache", RED);

        assertEquals(
            DungeonLootClaims.ClaimResult.CLAIMED,
            claims.claim("per_player", "cache", RED)
        );
    }

    @Test
    void restoredClaimsStillPreventDuplicateRewards() {
        DungeonLootClaims original = new DungeonLootClaims();
        original.claim("per_player", "cache", RED);

        DungeonLootClaims restored = DungeonLootClaims.restore(original.snapshot());

        assertEquals(
            DungeonLootClaims.ClaimResult.ALREADY_CLAIMED,
            restored.claim("per_player", "cache", RED)
        );
        assertEquals(
            DungeonLootClaims.ClaimResult.CLAIMED,
            restored.claim("per_player", "cache", LEAF)
        );
    }
}
