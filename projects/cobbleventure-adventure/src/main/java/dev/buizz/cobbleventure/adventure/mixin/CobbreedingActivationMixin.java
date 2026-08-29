package dev.buizz.cobbleventure.adventure.mixin;

import dev.buizz.cobbleventure.adventure.daycare.FarmBreedingGate;
import ludichat.cobbreeding.network.ToggleBreedingPacket;
import ludichat.cobbreeding.network.ToggleBreedingPacketPacketHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps direct Cobbreeding pasture activation behind farm progression. */
@Mixin(value = ToggleBreedingPacketPacketHandler.class, remap = false)
public abstract class CobbreedingActivationMixin {
    @Inject(
        method = "handle(Lludichat/cobbreeding/network/ToggleBreedingPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cobbleventure$gateFarmBreeding(
        ToggleBreedingPacket packet,
        MinecraftServer server,
        ServerPlayer player,
        CallbackInfo callback
    ) {
        FarmBreedingGate.Denial denial = FarmBreedingGate.denial(player);
        if (denial == FarmBreedingGate.Denial.NONE) {
            return;
        }
        String key = denial == FarmBreedingGate.Denial.BADGES
            ? "message.cobbleventure_adventure.daycare.farm_breeding_badges"
            : "message.cobbleventure_adventure.daycare.farm_breeding_location";
        player.sendSystemMessage(Component.translatable(
            key, FarmBreedingGate.REQUIRED_BADGES
        ));
        callback.cancel();
    }
}
