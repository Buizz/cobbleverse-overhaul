package dev.buizz.cobbleventure.adventure;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Applies the pack's default player movement speed without a visible potion effect. */
final class DefaultPlayerMovementSpeed {
    private static final ResourceLocation MODIFIER_ID =
        ResourceLocation.fromNamespaceAndPath(
            CobbleventureAdventure.MOD_ID,
            "default_player_movement_speed"
        );

    // Equivalent to `/effect give @s minecraft:speed infinite 2 true` (Speed III).
    private static final double SPEED_MULTIPLIER = 0.60D;

    private DefaultPlayerMovementSpeed() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(DefaultPlayerMovementSpeed::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(DefaultPlayerMovementSpeed::onPlayerRespawned);
        NeoForge.EVENT_BUS.addListener(DefaultPlayerMovementSpeed::onPlayerChangedDimension);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        apply(event.getEntity());
    }

    private static void onPlayerRespawned(PlayerEvent.PlayerRespawnEvent event) {
        apply(event.getEntity());
    }

    private static void onPlayerChangedDimension(
        PlayerEvent.PlayerChangedDimensionEvent event
    ) {
        apply(event.getEntity());
    }

    private static void apply(Player player) {
        if (!(player instanceof ServerPlayer)) {
            return;
        }

        AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movementSpeed == null) {
            return;
        }

        // Replace by ID so reconnects and repeated lifecycle events never stack the bonus.
        movementSpeed.removeModifier(MODIFIER_ID);
        movementSpeed.addPermanentModifier(
            new AttributeModifier(
                MODIFIER_ID,
                SPEED_MULTIPLIER,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            )
        );
    }
}
