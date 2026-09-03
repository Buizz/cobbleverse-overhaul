package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The client uses the exact server collision bounds, not a separate pose approximation. */
public final class GatePokemonNetwork {
    private static volatile Snapshot client = new Snapshot(ResourceLocation.withDefaultNamespace("overworld"), List.of());
    private GatePokemonNetwork() {}

    static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(
            Snapshot.TYPE, Snapshot.CODEC, (payload, context) -> client = payload
        ));
    }

    static void sync(ServerPlayer player, List<View> views) {
        PacketDistributor.sendToPlayer(player, new Snapshot(player.level().dimension().location(), views));
    }

    public static void clearClient() { client = new Snapshot(ResourceLocation.withDefaultNamespace("overworld"), List.of()); }

    static List<View> clientViews(ResourceLocation dimension) {
        return client.dimension.equals(dimension) ? client.views : List.of();
    }

    public static View clientView(Entity entity) {
        return clientViews(entity.level().dimension().location()).stream()
            .filter(view -> view.entityId.equals(entity.getUUID())).findFirst().orElse(null);
    }

    public record View(UUID entityId, AABB bounds, boolean hidden, String pose) {
        public boolean blocks(AABB swept) { return !hidden && bounds.intersects(swept); }
    }

    private record Snapshot(ResourceLocation dimension, List<View> views) implements CustomPacketPayload {
        static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CobbleventureBootstrap.MOD_ID, "gate_pokemon"));
        static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeResourceLocation(value.dimension);
                buffer.writeVarInt(value.views.size());
                for (View view : value.views) {
                    buffer.writeUUID(view.entityId);
                    buffer.writeDouble(view.bounds.minX); buffer.writeDouble(view.bounds.minY); buffer.writeDouble(view.bounds.minZ);
                    buffer.writeDouble(view.bounds.maxX); buffer.writeDouble(view.bounds.maxY); buffer.writeDouble(view.bounds.maxZ);
                    buffer.writeBoolean(view.hidden); buffer.writeBoolean(view.pose.equals("sleep"));
                }
            }, buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                int count = buffer.readVarInt();
                if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid gate Pokemon count");
                List<View> views = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    UUID id = buffer.readUUID();
                    AABB box = new AABB(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
                    views.add(new View(id, box, buffer.readBoolean(), buffer.readBoolean() ? "sleep" : "stand"));
                }
                return new Snapshot(dimension, List.copyOf(views));
            }
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
