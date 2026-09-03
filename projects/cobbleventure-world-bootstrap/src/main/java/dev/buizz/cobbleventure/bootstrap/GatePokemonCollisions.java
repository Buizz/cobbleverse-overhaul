package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Player-specific obstacles shared by vanilla and Lithium's movement solver. */
public final class GatePokemonCollisions {
    private GatePokemonCollisions() {}

    public static List<VoxelShape> append(
        List<VoxelShape> original, List<GatePokemonNetwork.View> views, AABB swept
    ) {
        List<VoxelShape> shapes = null;
        for (var view : views) {
            if (!view.blocks(swept)) continue;
            if (shapes == null) shapes = new ArrayList<>(original);
            shapes.add(Shapes.create(view.bounds()));
        }
        return shapes == null ? original : shapes;
    }
}
