package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BuildingRadarPersistenceTest {
    @Test
    void sameDimensionFacilitiesPersistWithoutAnyPreparedInterior() {
        var runtime = new BuildingRuntimeSystem.RuntimeData();
        runtime.rememberBuildingInstance("minecraft:overworld",
            "cobbleventure:facilities/pokemon_center", new BlockPos(120, 64, 240),
            "clockwise_90", "cobbleventure:building/pewter/facility_pokemon_center");
        runtime.rememberBuildingInstance("minecraft:overworld",
            "cobbleventure:facilities/pokemart", new BlockPos(150, 65, 270),
            "none", "cobbleventure:building/pewter/facility_pokemart");

        CompoundTag saved = runtime.save(new CompoundTag(), null);
        assertTrue(saved.getString("prepared").isEmpty());
        var restored = BuildingRuntimeSystem.RuntimeData.load(saved, null);
        assertEquals(2, restored.buildingInstances().size());
        assertEquals(saved.getString("buildingInstances"),
            restored.save(new CompoundTag(), null).getString("buildingInstances"));
    }
}
