package dev.buizz.cobbleventure.adventure.event;

import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;

/** Both visible trainers enter the owner's single CVES session and reward path. */
public final class EventNpcPartner {
    public static final String OWNER_TAG = "cves_partner_owner/";

    private EventNpcPartner() {}

    public static Entity owner(Entity target) {
        for (String tag : target.getTags()) {
            if (!tag.startsWith(OWNER_TAG)) continue;
            try {
                Entity owner = ((ServerLevel) target.level()).getEntity(
                    UUID.fromString(tag.substring(OWNER_TAG.length()))
                );
                return owner != null && owner.isAlive() ? owner : null;
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
        return target;
    }
}
