package dev.buizz.cobbleventure.bootstrap;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

final class TrainerCosmetics {
    private static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
        DeferredRegister.create(Registries.ARMOR_MATERIAL, CobbleventureBootstrap.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CobbleventureBootstrap.MOD_ID);

    private static final Holder<ArmorMaterial> COSMETIC_MATERIAL = ARMOR_MATERIALS.register(
        "trainer_cosmetic",
        () -> new ArmorMaterial(
            Util.make(new EnumMap<>(ArmorItem.Type.class), protection -> {
                for (ArmorItem.Type type : ArmorItem.Type.values()) {
                    protection.put(type, 0);
                }
            }),
            0,
            SoundEvents.ARMOR_EQUIP_LEATHER,
            () -> Ingredient.EMPTY,
            List.of(new ArmorMaterial.Layer(
                ResourceLocation.fromNamespaceAndPath(CobbleventureBootstrap.MOD_ID, "youngster_cap")
            )),
            0.0F,
            0.0F
        )
    );

    static final DeferredItem<ArmorItem> YOUNGSTER_CAP = ITEMS.register(
        "youngster_cap",
        () -> new ArmorItem(
            COSMETIC_MATERIAL,
            ArmorItem.Type.HELMET,
            new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(5))
        )
    );

    private TrainerCosmetics() {}

    static void register(IEventBus modBus) {
        ARMOR_MATERIALS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(TrainerCosmetics::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(YOUNGSTER_CAP);
        }
    }
}
