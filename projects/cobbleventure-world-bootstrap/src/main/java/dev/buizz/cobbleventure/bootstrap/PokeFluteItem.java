package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** A reusable story tool; owning it never automatically opens a gate. */
final class PokeFluteItem extends Item {
    static final String ID = "cobbleventure_bootstrap:poke_flute";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CobbleventureBootstrap.MOD_ID);
    static final DeferredItem<PokeFluteItem> POKE_FLUTE = ITEMS.register("poke_flute", PokeFluteItem::new);

    private PokeFluteItem() { super(new Item.Properties().stacksTo(1)); }

    static void register(IEventBus modBus) { ITEMS.register(modBus); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer && !GatePokemonSystem.useActivationItem(serverPlayer, stack))
            return InteractionResultHolder.fail(stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.cobbleventure_bootstrap.poke_flute.desc"));
    }
}
