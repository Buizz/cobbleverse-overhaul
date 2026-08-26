package dev.buizz.cobbleventure.pokefinder.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Avoids coupling the compatibility module to PokéNav's optional GUI interfaces. */
@Mixin(targets = "com.metacontent.cobblenav.client.gui.screen.PokenavScreen")
interface PokenavScreenAccessor {
    @Accessor("screenX")
    int cobbleventure$getScreenX();

    @Accessor("screenY")
    int cobbleventure$getScreenY();

    @Invoker("addUnblockableWidget")
    void cobbleventure$addUnblockableWidget(AbstractWidget widget);
}
