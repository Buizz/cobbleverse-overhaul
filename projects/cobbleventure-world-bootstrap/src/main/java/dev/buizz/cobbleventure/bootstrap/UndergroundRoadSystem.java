package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Places authored underground passage modules and resolves their external connectors. */
final class UndergroundRoadSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONNECTOR_PREFIX = "cobbleventure:underground_connector/";
    private static final String LEGACY_PORT_PREFIX = "cobbleventure:underground_port/";

    private UndergroundRoadSystem() {}

    static JsonObject loadDocument(ServerLevel level, String roadId) {
        String slug = roadId.substring(roadId.lastIndexOf('/') + 1);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "underground_roads/generation_1/" + slug + ".json"
        );
        Resource resource = level.getServer().getResourceManager().getResource(location)
            .orElseThrow(() -> new IllegalStateException("Missing underground passage document: " + location));
        try (Reader reader = resource.openAsReader()) {
            JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
            if (!roadId.equals(document.get("id").getAsString())) {
                throw new IllegalStateException("Underground passage ID mismatch: " + location);
            }
            return document;
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid underground passage document: " + location, error);
        }
    }

    static Port resolvePort(ServerLevel level, JsonObject document, String tag) {
        RoadData road = roadData(level, document);
        JsonObject ports = document.getAsJsonObject("ports");
        if (ports == null || !ports.has(tag)) {
            throw new IllegalStateException("Underground passage port is missing: " + road.id() + " / " + tag);
        }
        JsonObject port = ports.getAsJsonObject(tag);
        ModuleData module = road.modulesById().get(port.get("module").getAsString());
        if (module == null) throw new IllegalStateException("Underground passage port module is missing: " + tag);
        StructureTemplate.StructureBlockInfo connector = module.connectors().get(port.get("connector").getAsString());
        if (connector == null) throw new IllegalStateException("Underground passage connector is missing: " + tag);
        Direction outward = JigsawBlock.getFrontFacing(connector.state());
        if (outward != Direction.UP) {
            throw new IllegalStateException("Underground passage external port must use an upward stair connector: " + tag);
        }
        BlockPos portal = connector.pos();
        BlockPos destination = portal.relative(outward.getOpposite(), 3).above();
        return new Port(point(destination), point(portal));
    }

    static void generate(ServerLevel level, long worldSeed, Collection<JsonObject> documents) {
        for (JsonObject document : documents) {
            if (document.has("enabled") && !document.get("enabled").getAsBoolean()) continue;
            RoadData road = roadData(level, document);
            BlockPos generatedMarker = road.origin().offset(-1, -1, -1);
            if (level.getBlockState(generatedMarker).is(Blocks.REINFORCED_DEEPSLATE)) continue;
            Set<ChunkPos> forcedChunks = new HashSet<>();
            for (ModuleData module : road.modules()) {
                for (int x = SectionMath.blockToSectionCoord(module.min().getX()); x <= SectionMath.blockToSectionCoord(module.max().getX()); x++) {
                    for (int z = SectionMath.blockToSectionCoord(module.min().getZ()); z <= SectionMath.blockToSectionCoord(module.max().getZ()); z++) {
                        ChunkPos chunk = new ChunkPos(x, z); forcedChunks.add(chunk); level.getChunk(x, z); level.setChunkForced(x, z, true);
                    }
                }
            }
            try {
                for (ModuleData module : road.modules()) {
                    boolean placed = module.template().placeInWorld(
                        level, module.placementOrigin(), module.placementOrigin(), module.settings(),
                        RandomSource.create(worldSeed ^ module.placementOrigin().asLong() ^ module.id().hashCode()), 2
                    );
                    if (!placed) throw new IllegalStateException("Underground passage module placement failed: " + road.id() + " / " + module.id());
                    StructurePlacementFixes.afterPlacement(level, module.placementOrigin(), module.template(), module.settings());
                    for (StructureTemplate.StructureBlockInfo connector : module.connectors().values()) {
                        level.setBlock(connector.pos(), connectorFinalState(level, connector), 2);
                    }
                }
                level.setBlock(generatedMarker, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
                LOGGER.info("Underground passage placed: id={}, origin={}, modules={}", road.id(), road.origin(), road.modules().size());
            } finally {
                for (ChunkPos chunk : forcedChunks) level.setChunkForced(chunk.x, chunk.z, false);
            }
        }
    }

    private static RoadData roadData(ServerLevel level, JsonObject document) {
        String id = document.get("id").getAsString();
        JsonObject originJson = document.getAsJsonObject("dimension").getAsJsonObject("origin");
        BlockPos origin = new BlockPos(originJson.get("x").getAsInt(), originJson.get("y").getAsInt(), originJson.get("z").getAsInt());
        List<ModuleData> modules = new ArrayList<>();
        Map<String, ModuleData> byId = new HashMap<>();
        for (JsonElement element : document.getAsJsonArray("modules")) {
            JsonObject json = element.getAsJsonObject();
            String moduleId = json.get("id").getAsString();
            ResourceLocation structureId = ResourceLocation.parse(json.get("structure").getAsString());
            StructureTemplate template = level.getStructureManager().get(structureId)
                .orElseThrow(() -> new IllegalStateException("Underground passage module is missing: " + structureId));
            Rotation rotation = rotation(json.get("rotation").getAsString());
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
            JsonObject position = json.getAsJsonObject("position");
            BlockPos minimum = origin.offset(position.get("x").getAsInt(), position.get("y").getAsInt(), position.get("z").getAsInt());
            int width = template.getSize().getX(), depth = template.getSize().getZ();
            BlockPos placementOrigin = switch (rotation) {
                case CLOCKWISE_90 -> minimum.offset(depth - 1, 0, 0);
                case CLOCKWISE_180 -> minimum.offset(width - 1, 0, depth - 1);
                case COUNTERCLOCKWISE_90 -> minimum.offset(0, 0, width - 1);
                default -> minimum;
            };
            int rotatedWidth = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90 ? depth : width;
            int rotatedDepth = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90 ? width : depth;
            Map<String, StructureTemplate.StructureBlockInfo> connectors = new HashMap<>();
            for (StructureTemplate.StructureBlockInfo marker : template.filterBlocks(placementOrigin, settings, Blocks.JIGSAW)) {
                if (marker.nbt() == null) continue;
                String name = marker.nbt().getString("name");
                String tag = name.startsWith(CONNECTOR_PREFIX) ? name.substring(CONNECTOR_PREFIX.length())
                    : name.startsWith(LEGACY_PORT_PREFIX) ? name.substring(LEGACY_PORT_PREFIX.length()) : null;
                if (tag != null && connectors.putIfAbsent(tag, marker) != null) {
                    throw new IllegalStateException("Duplicate underground connector: " + moduleId + " / " + tag);
                }
            }
            if (connectors.isEmpty()) throw new IllegalStateException("Underground passage module has no connectors: " + structureId);
            ModuleData module = new ModuleData(moduleId, template, settings, placementOrigin, minimum,
                minimum.offset(rotatedWidth - 1, template.getSize().getY() - 1, rotatedDepth - 1), Map.copyOf(connectors));
            if (byId.putIfAbsent(moduleId, module) != null) throw new IllegalStateException("Duplicate underground module ID: " + moduleId);
            modules.add(module);
        }
        if (modules.isEmpty()) throw new IllegalStateException("Underground passage requires at least one module: " + id);
        return new RoadData(id, origin, List.copyOf(modules), Map.copyOf(byId));
    }

    private static Rotation rotation(String value) {
        return switch (value) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static BlockState connectorFinalState(ServerLevel level, StructureTemplate.StructureBlockInfo connector) {
        String finalState = connector.nbt() == null ? "minecraft:air" : connector.nbt().getString("final_state");
        try {
            return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), finalState, false).blockState();
        } catch (CommandSyntaxException error) {
            LOGGER.warn("Invalid underground connector final_state: {}", finalState);
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static CobbleventureBootstrap.BlockPoint point(BlockPos position) {
        return new CobbleventureBootstrap.BlockPoint(position.getX(), position.getY(), position.getZ());
    }

    record Port(CobbleventureBootstrap.BlockPoint destination, CobbleventureBootstrap.BlockPoint portalAnchor) {}
    private record RoadData(String id, BlockPos origin, List<ModuleData> modules, Map<String, ModuleData> modulesById) {}
    private record ModuleData(String id, StructureTemplate template, StructurePlaceSettings settings,
        BlockPos placementOrigin, BlockPos min, BlockPos max, Map<String, StructureTemplate.StructureBlockInfo> connectors) {}

    private static final class SectionMath {
        private SectionMath() {}
        static int blockToSectionCoord(int block) { return Math.floorDiv(block, 16); }
    }
}
