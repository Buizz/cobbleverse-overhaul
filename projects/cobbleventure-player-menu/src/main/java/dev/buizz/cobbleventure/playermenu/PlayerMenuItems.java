package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.api.item.PokemonSelectingItem;
import com.cobblemon.mod.common.api.pokemon.experience.CandyExperienceSource;
import com.cobblemon.mod.common.item.battle.BagItem;
import com.cobblemon.mod.common.pokemon.AddExperienceResult;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.buizz.cobbleventure.playermenu.client.PlayerMenuClient;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

final class PlayerMenuItems {
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CobbleventurePlayerMenu.MOD_ID);

    static final DeferredItem<Item> WORLD_MAP = ITEMS.register(
        "world_map",
        () -> new WorldMapItem(new Item.Properties().stacksTo(1))
    );
    static final DeferredItem<Item> STALE_RICE_CAKE = ITEMS.register(
        "stale_rice_cake",
        () -> new StaleRiceCakeItem(new Item.Properties().rarity(Rarity.UNCOMMON))
    );
    static final DeferredItem<Item> MYSTICAL_CANDY = ITEMS.register(
        "mystical_candy",
        () -> new MysticalCandyItem(new Item.Properties().rarity(Rarity.RARE))
    );

    private PlayerMenuItems() {}

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(PlayerMenuItems::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(WORLD_MAP);
            event.accept(STALE_RICE_CAKE);
            event.accept(MYSTICAL_CANDY);
        }
    }

    private abstract static class PokemonConsumableItem extends Item implements PokemonSelectingItem {
        private PokemonConsumableItem(Properties properties) {
            super(properties);
        }

        @Override
        public BagItem getBagItem() {
            return null;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer serverPlayer) {
                return CobblemonSelectingItemCompat.use(this, serverPlayer, stack);
            }
            return InteractionResultHolder.success(stack);
        }

        static ItemStack consumeOne(ServerPlayer player, ItemStack stack) {
            stack.consume(1, player);
            return stack;
        }
    }

    private static final class StaleRiceCakeItem extends PokemonConsumableItem {
        private StaleRiceCakeItem(Properties properties) {
            super(properties);
        }

        @Override
        public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag
        ) {
            tooltip.add(Component.translatable(
                "item.cobbleventure_player_menu.stale_rice_cake.tooltip"
            ).withStyle(ChatFormatting.GRAY));
        }

        @Override
        public InteractionResultHolder<ItemStack> applyToPokemon(
            ServerPlayer player, ItemStack stack, Pokemon pokemon
        ) {
            if (pokemon.getCurrentFullness() <= 0) {
                player.displayClientMessage(Component.translatable(
                    "message.cobbleventure_player_menu.stale_rice_cake.already_hungry",
                    pokemon.getDisplayName(false)
                ), true);
                return InteractionResultHolder.fail(stack);
            }

            pokemon.setCurrentFullness(0);
            consumeOne(player, stack);
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_player_menu.stale_rice_cake.used",
                pokemon.getDisplayName(false)
            ), true);
            return InteractionResultHolder.success(stack);
        }
    }

    private static final class MysticalCandyItem extends PokemonConsumableItem {
        private MysticalCandyItem(Properties properties) {
            super(properties);
        }

        @Override
        public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag
        ) {
            tooltip.add(Component.translatable(
                "item.cobbleventure_player_menu.mystical_candy.tooltip"
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                "item.cobbleventure_player_menu.mystical_candy.restriction",
                LevelCapCandyMath.MINIMUM_LEVEL_GAP
            ).withStyle(ChatFormatting.DARK_GRAY));
        }

        @Override
        public InteractionResultHolder<ItemStack> applyToPokemon(
            ServerPlayer player, ItemStack stack, Pokemon pokemon
        ) {
            int levelCap = ProgressionNetwork.levelCap(player);
            int levelGap = levelCap - pokemon.getLevel();
            if (levelGap < LevelCapCandyMath.MINIMUM_LEVEL_GAP) {
                player.displayClientMessage(Component.translatable(
                    "message.cobbleventure_player_menu.level_cap_candy.level_gap",
                    pokemon.getDisplayName(false), LevelCapCandyMath.MINIMUM_LEVEL_GAP, levelCap
                ), true);
                return InteractionResultHolder.fail(stack);
            }

            int remainingExperience = pokemon.getExperienceToLevel(levelCap);
            int experienceYield = LevelCapCandyMath.experienceYield(remainingExperience);
            if (experienceYield <= 0) {
                return InteractionResultHolder.fail(stack);
            }

            AddExperienceResult result = pokemon.addExperienceWithPlayer(
                player, new CandyExperienceSource(player, stack), experienceYield
            );
            int experienceAdded = result.getExperienceAdded();
            if (experienceAdded <= 0) {
                player.displayClientMessage(Component.translatable(
                    "message.cobbleventure_player_menu.level_cap_candy.failed"
                ), true);
                return InteractionResultHolder.fail(stack);
            }

            consumeOne(player, stack);
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_player_menu.level_cap_candy.used",
                pokemon.getDisplayName(false), experienceAdded, levelCap
            ), true);
            return InteractionResultHolder.success(stack);
        }
    }

    private static final class WorldMapItem extends Item {
        private WorldMapItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide()) {
                if (ProgressionNetwork.clientSnapshot().map()) {
                    PlayerMenuClient.openWorldMap();
                } else {
                    ProgressionNetwork.requestSnapshot();
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("지도 기능은 아직 잠겨 있습니다."), true
                    );
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
    }
}
