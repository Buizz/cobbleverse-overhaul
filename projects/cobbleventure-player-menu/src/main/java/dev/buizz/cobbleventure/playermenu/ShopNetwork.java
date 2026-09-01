package dev.buizz.cobbleventure.playermenu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.playermenu.client.ShopClient;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-authoritative shop sessions backed by CobbleDollars and the Cobbleventure bag. */
public final class ShopNetwork {
    public static final String VENDOR_DATA_KEY = "cobbleventure_shop_vendor";
    private static final String VERSION = "2";
    private static final int MAX_BUY_QUANTITY = 999;
    private static final long SESSION_TICKS = 20L * 60L * 10L;
    private static final double MAX_INTERACTION_DISTANCE_SQR = 100.0D;
    private static final ResourceLocation CATALOG = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure", "economy/catalog.json"
    );
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ShopNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(ShopNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ShopNetwork::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(ShopNetwork::onPlayerLogout);
    }

    public static void requestTransaction(
        UUID token, int offerIndex, int quantity, boolean selling, ItemStack stack
    ) {
        PacketDistributor.sendToServer(new TransactionPayload(
            token, offerIndex, quantity, selling, stack.copyWithCount(1)
        ));
    }

    public static void close(UUID token) {
        PacketDistributor.sendToServer(new ClosePayload(token));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, ShopNetwork::handleOpen);
        registrar.playToServer(
            TransactionPayload.TYPE, TransactionPayload.STREAM_CODEC, ShopNetwork::handleTransaction
        );
        registrar.playToClient(
            TransactionResultPayload.TYPE,
            TransactionResultPayload.STREAM_CODEC,
            ShopNetwork::handleTransactionResult
        );
        registrar.playToServer(ClosePayload.TYPE, ClosePayload.STREAM_CODEC, ShopNetwork::handleClose);
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Entity target = event.getTarget();
        String vendorId = target.getPersistentData().getString(VENDOR_DATA_KEY);
        if (vendorId.isBlank()) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        open(player, target, vendorId);
    }

    /** Opens the Cobbleventure product menu for a server-authorized vendor entity. */
    public static int open(ServerPlayer player, Entity target, String vendorId) {
        if (target == null || !target.isAlive()
            || player.distanceToSqr(target) > MAX_INTERACTION_DISTANCE_SQR) return 0;
        ShopDefinition definition = loadDefinition(player, vendorId);
        if (definition == null || definition.offers().isEmpty()) return 0;
        target.getPersistentData().putString(VENDOR_DATA_KEY, vendorId);
        UUID token = UUID.randomUUID();
        SESSIONS.put(player.getUUID(), new Session(
            token,
            target.getUUID(),
            vendorId,
            player.serverLevel().getGameTime() + SESSION_TICKS
        ));
        PacketDistributor.sendToPlayer(player, openPayload(player, token, definition));
        return 1;
    }

    private static void handleOpen(OpenPayload payload, IPayloadContext context) {
        ShopClient.open(payload);
    }

    private static void handleTransaction(TransactionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Session session = SESSIONS.get(player.getUUID());
        if (!validSession(player, session, payload.token())) {
            context.reply(failure(
                player, payload.token(), "screen.cobbleventure_player_menu.shop.error.session"
            ));
            return;
        }
        if (payload.quantity() < 1
            || !payload.selling() && payload.quantity() > MAX_BUY_QUANTITY) {
            context.reply(failure(
                player, payload.token(), "screen.cobbleventure_player_menu.shop.error.quantity"
            ));
            return;
        }

        ShopDefinition definition = loadDefinition(player, session.vendorId());
        if (definition == null) {
            context.reply(failure(
                player, payload.token(), "screen.cobbleventure_player_menu.shop.error.offer"
            ));
            return;
        }

        String error;
        if (payload.selling()) {
            ItemStack prototype = payload.stack().copyWithCount(1);
            BigInteger unitPrice = definition.salePrices().getOrDefault(
                prototype.getItem(), BigInteger.ZERO
            );
            error = sell(player, prototype, unitPrice, payload.quantity());
        } else {
            if (payload.offerIndex() < 0
                || payload.offerIndex() >= definition.offers().size()) {
                context.reply(failure(
                    player, payload.token(), "screen.cobbleventure_player_menu.shop.error.offer"
                ));
                return;
            }
            error = buy(player, definition.offers().get(payload.offerIndex()), payload.quantity());
        }
        ShopDefinition refreshed = loadDefinition(player, session.vendorId());
        if (refreshed == null) {
            context.reply(failure(
                player, payload.token(), "screen.cobbleventure_player_menu.shop.error.offer"
            ));
            return;
        }
        context.reply(resultPayload(player, payload.token(), refreshed, error));
    }

    private static String buy(ServerPlayer player, Offer offer, int quantity) {
        BigInteger total = offer.buyPrice().multiply(BigInteger.valueOf(quantity));
        BigInteger before = PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO);
        if (before.compareTo(total) < 0) {
            return "screen.cobbleventure_player_menu.shop.error.money";
        }

        long requested = (long) offer.count() * quantity;
        if (requested > Integer.MAX_VALUE) {
            return "screen.cobbleventure_player_menu.shop.error.quantity";
        }
        List<ItemStack> stacks = splitStacks(offer.item(), (int) requested);
        if (!BagApi.insertAll(player, stacks).complete()) {
            return "screen.cobbleventure_player_menu.shop.error.bag_full";
        }
        try {
            PlayerExtensionKt.setCobbleDollars(player, before.subtract(total));
        } catch (RuntimeException error) {
            BagApi.remove(player, new ItemStack(offer.item()), (int) requested);
            return "screen.cobbleventure_player_menu.shop.error.transaction";
        }
        return "screen.cobbleventure_player_menu.shop.success.buy";
    }

    private static String sell(
        ServerPlayer player, ItemStack prototype, BigInteger unitPrice, int quantity
    ) {
        if (prototype.isEmpty() || ImportantItemProtection.isProtected(prototype)) {
            return "screen.cobbleventure_player_menu.shop.error.not_sellable";
        }
        if (!BagApi.removeFromBag(player, prototype, quantity)) {
            return "screen.cobbleventure_player_menu.shop.error.items";
        }
        BigInteger before = PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO);
        BigInteger earned = unitPrice.multiply(BigInteger.valueOf(quantity));
        try {
            PlayerExtensionKt.setCobbleDollars(player, before.add(earned));
        } catch (RuntimeException error) {
            List<ItemStack> rollback = new ArrayList<>();
            int remaining = quantity;
            while (remaining > 0) {
                int count = Math.min(remaining, prototype.getMaxStackSize());
                rollback.add(prototype.copyWithCount(count));
                remaining -= count;
            }
            BagApi.insertAll(player, rollback);
            return "screen.cobbleventure_player_menu.shop.error.transaction";
        }
        return "screen.cobbleventure_player_menu.shop.success.sell";
    }

    private static List<ItemStack> splitStacks(Item item, int count) {
        List<ItemStack> result = new ArrayList<>();
        int remaining = count;
        int maxStackSize = new ItemStack(item).getMaxStackSize();
        while (remaining > 0) {
            int amount = Math.min(remaining, maxStackSize);
            result.add(new ItemStack(item, amount));
            remaining -= amount;
        }
        return result;
    }

    private static boolean validSession(ServerPlayer player, Session session, UUID token) {
        if (session == null || !session.token().equals(token)) return false;
        Entity vendor = player.serverLevel().getEntity(session.entityId());
        return vendor != null && vendor.isAlive()
            && player.serverLevel().getGameTime() <= session.expiresAt()
            && player.distanceToSqr(vendor) <= MAX_INTERACTION_DISTANCE_SQR
            && session.vendorId().equals(vendor.getPersistentData().getString(VENDOR_DATA_KEY));
    }

    private static void handleTransactionResult(
        TransactionResultPayload payload, IPayloadContext context
    ) {
        ShopClient.update(payload);
    }

    private static void handleClose(ClosePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.token().equals(payload.token())) {
            SESSIONS.remove(player.getUUID());
        }
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    private static OpenPayload openPayload(
        ServerPlayer player, UUID token, ShopDefinition definition
    ) {
        return new OpenPayload(
            token,
            definition.name(),
            definition.role(),
            PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO).toString(),
            clientOffers(player, definition)
        );
    }

    private static TransactionResultPayload resultPayload(
        ServerPlayer player, UUID token, ShopDefinition definition, String message
    ) {
        return new TransactionResultPayload(
            token,
            message.startsWith("screen.cobbleventure_player_menu.shop.success."),
            message,
            PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO).toString(),
            clientOffers(player, definition)
        );
    }

    private static TransactionResultPayload failure(
        ServerPlayer player, UUID token, String message
    ) {
        return new TransactionResultPayload(
            token,
            false,
            message,
            PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO).toString(),
            List.of()
        );
    }

    private static List<ClientOffer> clientOffers(ServerPlayer player, ShopDefinition definition) {
        List<ClientOffer> offers = new ArrayList<>();
        for (int index = 0; index < definition.offers().size(); index++) {
            Offer offer = definition.offers().get(index);
            ItemStack prototype = new ItemStack(offer.item());
            offers.add(new ClientOffer(
                index,
                offer.category(),
                prototype,
                offer.count(),
                offer.buyPrice().toString(),
                offer.sellPrice().toString(),
                BagApi.count(player, prototype),
                false
            ));
        }
        List<SellCandidate> candidates = new ArrayList<>();
        Map<Integer, List<Integer>> buckets = new HashMap<>();
        for (ItemStack stack : BagStorage.load(player)) {
            if (stack.isEmpty() || ImportantItemProtection.isProtected(stack)) continue;
            int hash = ItemStack.hashItemAndComponents(stack);
            List<Integer> bucket = buckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
            int match = -1;
            for (int candidateIndex : bucket) {
                if (ItemStack.isSameItemSameComponents(candidates.get(candidateIndex).stack(), stack)) {
                    match = candidateIndex;
                    break;
                }
            }
            if (match < 0) {
                bucket.add(candidates.size());
                candidates.add(new SellCandidate(stack.copyWithCount(1), stack.getCount()));
            } else {
                SellCandidate candidate = candidates.get(match);
                candidates.set(match, new SellCandidate(
                    candidate.stack(), candidate.count() + stack.getCount()
                ));
            }
        }
        for (SellCandidate candidate : candidates) {
            ItemStack prototype = candidate.stack();
            BigInteger price = definition.salePrices().getOrDefault(
                prototype.getItem(), BigInteger.ZERO
            );
            offers.add(new ClientOffer(
                offers.size(), "", prototype, 1, "0", price.toString(), candidate.count(), true
            ));
        }
        return List.copyOf(offers);
    }

    private static ShopDefinition loadDefinition(ServerPlayer player, String vendorId) {
        Resource resource = player.getServer().getResourceManager().getResource(CATALOG).orElse(null);
        if (resource == null) return null;
        String language = player.clientInformation().language();
        try (Reader reader = resource.openAsReader()) {
            JsonObject catalog = JsonParser.parseReader(reader).getAsJsonObject();
            SellPricePolicy sellPricePolicy = readSellPricePolicy(catalog);
            Map<Item, StandardPrice> standardPrices = new HashMap<>();
            Map<Item, BigInteger> resolvedSalePrices = new HashMap<>();
            if (catalog.has("standard_prices")) {
                for (JsonElement priceElement : catalog.getAsJsonArray("standard_prices")) {
                    JsonObject price = priceElement.getAsJsonObject();
                    ResourceLocation itemId = ResourceLocation.tryParse(requiredString(price, "item"));
                    if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) continue;
                    StandardPrice standardPrice = new StandardPrice(
                        nonNegativeMoney(requiredString(price, "buy_price")),
                        nonNegativeMoney(requiredString(price, "sell_price")),
                        price.has("use_default_sell_price") && price.get("use_default_sell_price").getAsBoolean(),
                        price.has("no_sell_penalty") && price.get("no_sell_penalty").getAsBoolean()
                    );
                    Item item = BuiltInRegistries.ITEM.get(itemId);
                    standardPrices.put(item, standardPrice);
                    resolvedSalePrices.put(item, standardPrice.resolveSellPrice(sellPricePolicy));
                }
            }
            for (JsonElement element : catalog.getAsJsonArray("vendor_units")) {
                JsonObject vendor = element.getAsJsonObject();
                if (!vendorId.equals(requiredString(vendor, "id"))) continue;
                List<Offer> offers = new ArrayList<>();
                for (JsonElement categoryElement : vendor.getAsJsonArray("categories")) {
                    JsonObject category = categoryElement.getAsJsonObject();
                    String categoryName = localized(category.get("name"), language);
                    for (JsonElement offerElement : category.getAsJsonArray("offers")) {
                        JsonObject offer = offerElement.getAsJsonObject();
                        ResourceLocation itemId = ResourceLocation.tryParse(requiredString(offer, "item"));
                        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) continue;
                        BigInteger buyPrice = positiveMoney(requiredString(offer, "price"));
                        Item item = BuiltInRegistries.ITEM.get(itemId);
                        StandardPrice standardPrice = standardPrices.get(item);
                        BigInteger sellPrice;
                        if (offer.has("sell_price")) {
                            sellPrice = nonNegativeMoney(requiredString(offer, "sell_price"));
                        } else if (standardPrice != null) {
                            sellPrice = standardPrice.resolveSellPrice(sellPricePolicy);
                        } else if (sellPricePolicy.applyDefaultToAll()) {
                            sellPrice = percentageOf(buyPrice, sellPricePolicy.defaultPercentage());
                        } else {
                            sellPrice = BigInteger.ZERO;
                        }
                        resolvedSalePrices.put(item, sellPrice);
                        offers.add(new Offer(
                            categoryName,
                            item,
                            Math.max(1, offer.get("count").getAsInt()),
                            buyPrice,
                            sellPrice
                        ));
                    }
                }
                return new ShopDefinition(
                    localized(vendor.get("display_name"), language),
                    localized(vendor.get("role"), language),
                    List.copyOf(offers),
                    Map.copyOf(resolvedSalePrices)
                );
            }
        } catch (IOException | RuntimeException error) {
            return null;
        }
        return null;
    }

    private static String localized(JsonElement element, String language) {
        if (element == null) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        JsonObject value = element.getAsJsonObject();
        if (value.has(language)) return value.get(language).getAsString();
        if (value.has("ko_kr")) return value.get("ko_kr").getAsString();
        return value.has("en_us") ? value.get("en_us").getAsString() : "";
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string: " + key);
        }
        return object.get(key).getAsString();
    }

    private static SellPricePolicy readSellPricePolicy(JsonObject catalog) {
        if (!catalog.has("sell_price_policy") || !catalog.get("sell_price_policy").isJsonObject()) {
            return new SellPricePolicy(true, 50);
        }
        JsonObject policy = catalog.getAsJsonObject("sell_price_policy");
        boolean applyDefaultToAll = !policy.has("apply_default_to_all")
            || policy.get("apply_default_to_all").getAsBoolean();
        int percentage = policy.has("default_percentage")
            ? policy.get("default_percentage").getAsInt()
            : 50;
        return new SellPricePolicy(applyDefaultToAll, Math.max(0, Math.min(100, percentage)));
    }

    private static BigInteger percentageOf(BigInteger price, int percentage) {
        return price.multiply(BigInteger.valueOf(percentage)).divide(BigInteger.valueOf(100));
    }

    private static BigInteger positiveMoney(String value) {
        BigInteger parsed = new BigInteger(value);
        if (parsed.signum() < 0) throw new IllegalArgumentException("Negative price");
        return parsed;
    }

    private static BigInteger nonNegativeMoney(String value) {
        return positiveMoney(value);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }

    private record Session(UUID token, UUID entityId, String vendorId, long expiresAt) {}
    private record ShopDefinition(
        String name, String role, List<Offer> offers, Map<Item, BigInteger> salePrices
    ) {}
    private record SellPricePolicy(boolean applyDefaultToAll, int defaultPercentage) {}
    private record StandardPrice(
        BigInteger buyPrice,
        BigInteger sellPrice,
        boolean useDefaultSellPrice,
        boolean noSellPenalty
    ) {
        private BigInteger resolveSellPrice(SellPricePolicy policy) {
            if (noSellPenalty) return sellPrice;
            if (policy.applyDefaultToAll() || useDefaultSellPrice) {
                return percentageOf(buyPrice, policy.defaultPercentage());
            }
            return sellPrice;
        }
    }
    private record Offer(
        String category, Item item, int count, BigInteger buyPrice, BigInteger sellPrice
    ) {}
    private record SellCandidate(ItemStack stack, int count) {}

    public record ClientOffer(
        int index,
        String category,
        ItemStack stack,
        int count,
        String buyPrice,
        String sellPrice,
        int owned,
        boolean sellCandidate
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(index);
            buffer.writeUtf(category);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
            buffer.writeVarInt(count);
            buffer.writeUtf(buyPrice);
            buffer.writeUtf(sellPrice);
            buffer.writeVarInt(owned);
            buffer.writeBoolean(sellCandidate);
        }

        private static ClientOffer read(RegistryFriendlyByteBuf buffer) {
            return new ClientOffer(
                buffer.readVarInt(),
                buffer.readUtf(),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                buffer.readVarInt(),
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readVarInt(),
                buffer.readBoolean()
            );
        }
    }

    public record OpenPayload(
        UUID token, String name, String role, String balance, List<ClientOffer> offers
    ) implements CustomPacketPayload {
        public static final Type<OpenPayload> TYPE = new Type<>(id("shop_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(OpenPayload::write, OpenPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(token);
            buffer.writeUtf(name);
            buffer.writeUtf(role);
            buffer.writeUtf(balance);
            buffer.writeVarInt(offers.size());
            for (ClientOffer offer : offers) offer.write(buffer);
        }

        private static OpenPayload read(RegistryFriendlyByteBuf buffer) {
            UUID token = buffer.readUUID();
            String name = buffer.readUtf();
            String role = buffer.readUtf();
            String balance = buffer.readUtf();
            int size = Math.max(0, Math.min(4096, buffer.readVarInt()));
            List<ClientOffer> offers = new ArrayList<>(size);
            for (int index = 0; index < size; index++) offers.add(ClientOffer.read(buffer));
            return new OpenPayload(token, name, role, balance, List.copyOf(offers));
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TransactionPayload(
        UUID token, int offerIndex, int quantity, boolean selling, ItemStack stack
    )
        implements CustomPacketPayload {
        public static final Type<TransactionPayload> TYPE = new Type<>(id("shop_transaction"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TransactionPayload> STREAM_CODEC =
            StreamCodec.ofMember(TransactionPayload::write, TransactionPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(token);
            buffer.writeVarInt(offerIndex);
            buffer.writeVarInt(quantity);
            buffer.writeBoolean(selling);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        }

        private static TransactionPayload read(RegistryFriendlyByteBuf buffer) {
            return new TransactionPayload(
                buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TransactionResultPayload(
        UUID token,
        boolean success,
        String message,
        String balance,
        List<ClientOffer> offers
    ) implements CustomPacketPayload {
        public static final Type<TransactionResultPayload> TYPE = new Type<>(id("shop_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TransactionResultPayload> STREAM_CODEC =
            StreamCodec.ofMember(TransactionResultPayload::write, TransactionResultPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(token);
            buffer.writeBoolean(success);
            buffer.writeUtf(message);
            buffer.writeUtf(balance);
            buffer.writeVarInt(offers.size());
            for (ClientOffer offer : offers) offer.write(buffer);
        }

        private static TransactionResultPayload read(RegistryFriendlyByteBuf buffer) {
            UUID token = buffer.readUUID();
            boolean success = buffer.readBoolean();
            String message = buffer.readUtf();
            String balance = buffer.readUtf();
            int size = Math.max(0, Math.min(4096, buffer.readVarInt()));
            List<ClientOffer> offers = new ArrayList<>(size);
            for (int index = 0; index < size; index++) offers.add(ClientOffer.read(buffer));
            return new TransactionResultPayload(
                token, success, message, balance, List.copyOf(offers)
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClosePayload(UUID token) implements CustomPacketPayload {
        public static final Type<ClosePayload> TYPE = new Type<>(id("shop_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClosePayload> STREAM_CODEC =
            StreamCodec.ofMember(ClosePayload::write, ClosePayload::read);

        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeUUID(token); }
        private static ClosePayload read(RegistryFriendlyByteBuf buffer) {
            return new ClosePayload(buffer.readUUID());
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
