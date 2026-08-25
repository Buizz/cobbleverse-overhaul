package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Places reusable NBT world objects such as villain bases and legendary sites. */
final class WorldStructureSystem {
    private static final Logger LOGGER = LogUtils.getLogger();

    private WorldStructureSystem() {}

    static List<WorldStructure> parse(JsonArray objects) {
        List<WorldStructure> structures = new ArrayList<>();
        for (JsonElement element : objects) {
            JsonObject value = element.getAsJsonObject();
            String type = requiredString(value, "type");
            if (!List.of("structure", "villain_base", "legendary_site").contains(type)) {
                continue;
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            if (anchor == null) {
                throw new IllegalStateException("World structure anchor is missing");
            }
            String resource = requiredString(value, "resource");
            if (ResourceLocation.tryParse(resource) == null) {
                throw new IllegalStateException("Invalid world structure resource: " + resource);
            }
            List<DungeonConnection> connections = new ArrayList<>();
            if (value.has("connections")) {
                for (JsonElement connectionElement : value.getAsJsonArray("connections")) {
                    JsonObject connection = connectionElement.getAsJsonObject();
                    JsonObject target = connection.getAsJsonObject("target");
                    if (target == null || !"dungeon".equals(requiredString(target, "type"))) {
                        continue;
                    }
                    String from = requiredString(connection, "from");
                    if (!from.startsWith("structure:") || from.length() == "structure:".length()) {
                        throw new IllegalStateException(
                            "Dungeon structure connection requires structure:<anchor>: " + from
                        );
                    }
                    connections.add(new DungeonConnection(
                        from.substring("structure:".length()),
                        requiredString(target, "entrance_id")
                    ));
                }
            }
            structures.add(new WorldStructure(
                requiredString(value, "id"), type,
                new HexCoord(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                resource,
                value.has("rotation") ? value.get("rotation").getAsInt() : 0,
                List.copyOf(connections)
            ));
        }
        return List.copyOf(structures);
    }

    static void placeAll(ServerLevel level, HexWorldPlan world) {
        for (WorldStructure structure : world.worldStructures()) {
            place(level, world, structure);
        }
    }

    private static void place(
        ServerLevel level, HexWorldPlan world, WorldStructure configured
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(configured.structure());
        var optional = structureId == null
            ? java.util.Optional.<StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (optional.isEmpty()) {
            throw new IllegalStateException(
                "World structure NBT is missing: " + configured.structure()
            );
        }
        StructureTemplate template = optional.orElseThrow();
        Rotation rotation = rotation(configured.rotation());
        CobbleventureBootstrap.Point center = world.grid().worldCenter(configured.anchor());
        Vec3i rotatedSize = template.getSize(rotation);
        int minX = center.x() - rotatedSize.getX() / 2;
        int minZ = center.z() - rotatedSize.getZ() / 2;
        int floorY = CobbleventureBootstrap.nativeTerrainColumn(
            world, center.x(), center.z()
        ).groundY();
        BlockPos origin = rotatedTemplateOrigin(
            minX, floorY, minZ,
            template.getSize().getX(), template.getSize().getZ(), rotation
        );
        BlockPos marker = new BlockPos(
            center.x(), world.grid().origin().y() - 18, center.z()
        );
        if (!level.getBlockState(marker).is(Blocks.RESPAWN_ANCHOR)) {
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
                .addProcessor(GroundFloorAirPreservationProcessor.INSTANCE);
            if (!template.placeInWorld(
                level, origin, origin, settings,
                RandomSource.create(level.getSeed() ^ origin.asLong()), 2
            )) {
                throw new IllegalStateException(
                    "World structure placement failed: " + configured.id()
                );
            }
            level.setBlock(marker, Blocks.RESPAWN_ANCHOR.defaultBlockState(), 2);
            LOGGER.info(
                "World structure generated: id={}, type={}, anchor={}, origin={}",
                configured.id(), configured.type(), configured.anchor(), origin
            );
        }
        BuildingRuntimeSystem.onStructurePlaced(
            level, configured.structure(),
            new CobbleventureBootstrap.BlockPoint(
                origin.getX(), origin.getY(), origin.getZ()
            ),
            rotationName(configured.rotation())
        );
    }

    static BlockPos rotatedTemplateOrigin(
        int x, int y, int z, int width, int depth, Rotation rotation
    ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(x + depth - 1, y, z);
            case CLOCKWISE_180 -> new BlockPos(x + width - 1, y, z + depth - 1);
            case COUNTERCLOCKWISE_90 -> new BlockPos(x, y, z + width - 1);
            default -> new BlockPos(x, y, z);
        };
    }

    private static Rotation rotation(int value) {
        return switch (Math.floorMod(value, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static String rotationName(int value) {
        return switch (Math.floorMod(value, 4)) {
            case 1 -> "clockwise_90";
            case 2 -> "clockwise_180";
            case 3 -> "counterclockwise_90";
            default -> "none";
        };
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalStateException("World structure string is missing: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalStateException("World structure string is empty: " + key);
        }
        return result;
    }

    record WorldStructure(
        String id,
        String type,
        HexCoord anchor,
        String structure,
        int rotation,
        List<DungeonConnection> dungeonConnections
    ) {}

    record DungeonConnection(String anchorId, String entranceId) {}
}
