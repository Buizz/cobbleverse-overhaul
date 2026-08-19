package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import java.math.BigInteger;

/** Player-owned state exposed to CVES without leaking its Minecraft storage format. */
public interface EventStateAccess {
    String playerName();

    boolean flag(String resourceId);

    boolean hasItem(String resourceId, int count);

    BigInteger money();

    int levelCap();

    void setFlag(String resourceId, boolean value);

    void setPlayerVariable(String resourceId, JsonElement value);

    void unlockFeature(String resourceId);

    void setLevelCap(int level);

    boolean changeMoney(
        String operationId, BigInteger delta, boolean notify, boolean allowDebt
    );

    void grantBadge(String resourceId);

    void grantFieldMove(String move);
}
