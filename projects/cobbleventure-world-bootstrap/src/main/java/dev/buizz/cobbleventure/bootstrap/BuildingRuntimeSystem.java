package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/** Applies builder-authored anchors and building NPC assignments after template placement. */
final class BuildingRuntimeSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_building_runtime";
    private static final String INTERACTION_COOLDOWN = "cobbleventureBuildingDoorCooldown";
    private static final int SLOT_SPACING = 512;
    private static final int SLOT_Y = 64;
    private static final ResourceKey<Level> INTERIORS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "building_interiors")
    );
    private static final Map<String, StructureMetadata> METADATA = new LinkedHashMap<>();
    private static final Map<String, BuildingSettings> SETTINGS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();

    private BuildingRuntimeSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(BuildingRuntimeSystem::onRightClickBlock);
    }

    static void initialize(MinecraftServer server) {
        METADATA.clear();
        SETTINGS.clear();
        DOORS.clear();
        loadMetadata(server);
        loadSettings(server);
        if (!METADATA.isEmpty() && server.getLevel(INTERIORS) == null) {
            throw new IllegalStateException("Cobbleventure building_interiors dimension is missing");
        }
        LOGGER.info(
            "Building runtime loaded: metadata={}, configured={}",
            METADATA.size(), SETTINGS.size()
        );
    }

    static void onStructurePlaced(
        ServerLevel level, String structure, CobbleventureBootstrap.BlockPoint origin,
        String rotationName
    ) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) {
            return;
        }
        Rotation rotation = rotation(rotationName);
        String instanceKey = instanceKey(level, structure, origin.toBlockPos());
        applyFixedNpcs(level, structure, metadata, origin.toBlockPos(), rotation, instanceKey);
        prepareInterior(level, structure, metadata, origin.toBlockPos(), rotation, instanceKey);
    }

    private static void loadMetadata(MinecraftServer server) {
        Map<ResourceLocation, Resource> resources = server.getResourceManager().listResources(
            "structure_metadata",
            location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".structure.json")
        );
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation location = entry.getKey();
            String path = location.getPath()
                .substring("structure_metadata/".length())
                .replaceFirst("\\.structure\\.json$", "");
            String structure = location.getNamespace() + ":" + path;
            try (Reader reader = entry.getValue().openAsReader()) {
                METADATA.put(structure, parseMetadata(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Invalid building metadata: " + location, error);
            }
        });
    }

    private static StructureMetadata parseMetadata(JsonObject root) {
        List<Anchor> anchors = new ArrayList<>();
        if (root.has("anchors")) {
            for (JsonElement element : root.getAsJsonArray("anchors")) {
                JsonObject value = element.getAsJsonObject();
                String type = requiredString(value, "type");
                String id = value.has("label") ? value.get("label").getAsString()
                    : value.has("id") ? value.get("id").getAsString() : type;
                anchors.add(new Anchor(
                    id, type, position(value, "position", null),
                    position(value, "safe_spawn", null)
                ));
            }
        }
        return new StructureMetadata(List.copyOf(anchors));
    }

    private static void loadSettings(MinecraftServer server) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "building_settings.json"
        );
        Resource resource = server.getResourceManager().getResource(location).orElse(null);
        if (resource == null) {
            return;
        }
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject buildings = root.getAsJsonObject("buildings");
            if (buildings == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : buildings.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                Map<String, String> fixed = new LinkedHashMap<>();
                if (value.has("fixed_npcs")) {
                    for (Map.Entry<String, JsonElement> npc
                        : value.getAsJsonObject("fixed_npcs").entrySet()) {
                        fixed.put(npc.getKey(), npc.getValue().getAsString());
                    }
                }
                SETTINGS.put(entry.getKey(), new BuildingSettings(
                    Map.copyOf(fixed),
                    value.has("citizen_placement_allowed")
                        ? value.get("citizen_placement_allowed").getAsBoolean()
                        : value.has("random_citizen_eligible")
                            && value.get("random_citizen_eligible").getAsBoolean()
                ));
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid building settings: " + location, error);
        }
    }

    private static void applyFixedNpcs(
        ServerLevel level, String structure, StructureMetadata metadata,
        BlockPos origin, Rotation rotation, String instanceKey
    ) {
        BuildingSettings settings = SETTINGS.get(structure);
        if (settings == null || settings.fixedNpcs.isEmpty()) {
            return;
        }
        RuntimeData data = data(level.getServer());
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("npc_position")) {
                continue;
            }
            String npc = settings.fixedNpcs.get(anchor.id);
            String spawnKey = instanceKey + "|npc|" + anchor.id;
            if (npc == null || data.hasSpawned(spawnKey)) {
                continue;
            }
            BlockPos position = transform(origin, anchor.position, rotation);
            if (spawnNpc(level, npc, position)) {
                data.markSpawned(spawnKey);
            }
        }
    }

    private static void prepareInterior(
        ServerLevel exterior, String exteriorStructure, StructureMetadata exteriorMetadata,
        BlockPos exteriorOrigin, Rotation exteriorRotation, String instanceKey
    ) {
        Anchor entry = exteriorMetadata.first("interior_entry");
        if (entry == null) {
            return;
        }
        String name = exteriorStructure.substring(exteriorStructure.lastIndexOf('/') + 1);
        String interiorStructure = "cobbleventure:interiors/" + name;
        StructureMetadata interiorMetadata = METADATA.get(interiorStructure);
        if (interiorMetadata == null) {
            LOGGER.warn("Interior metadata is missing for {}", exteriorStructure);
            return;
        }
        ServerLevel interiors = exterior.getServer().getLevel(INTERIORS);
        if (interiors == null) {
            return;
        }
        ResourceLocation interiorId = ResourceLocation.tryParse(interiorStructure);
        var template = interiorId == null ? java.util.Optional
            .<StructureTemplate>empty() : interiors.getStructureManager().get(interiorId);
        if (template.isEmpty()) {
            LOGGER.warn("Interior structure is missing: {}", interiorStructure);
            return;
        }

        BlockPos interiorOrigin = instanceOrigin(instanceKey);
        RuntimeData data = data(exterior.getServer());
        String preparedKey = instanceKey + "|interior";
        if (!data.hasPrepared(preparedKey)) {
            forceChunks(interiors, interiorOrigin, template.orElseThrow().getSize());
            boolean placed = template.orElseThrow().placeInWorld(
                interiors, interiorOrigin, interiorOrigin, new StructurePlaceSettings(),
                RandomSource.create(interiors.getSeed() ^ interiorOrigin.asLong()), 2
            );
            if (!placed) {
                LOGGER.error("Interior placement failed: structure={}, instance={}", interiorStructure, instanceKey);
                return;
            }
            data.markPrepared(preparedKey);
        }
        applyFixedNpcs(
            interiors, interiorStructure, interiorMetadata, interiorOrigin,
            Rotation.NONE, instanceKey + "|inside"
        );

        Anchor interiorSpawn = interiorMetadata.first("interior_spawn");
        Anchor exit = interiorMetadata.first("interior_exit");
        if (exit == null) {
            LOGGER.warn("Interior exit anchor is missing: {}", interiorStructure);
            return;
        }
        BlockPos entryDoor = transform(exteriorOrigin, entry.position, exteriorRotation);
        BlockPos outside = entry.safeSpawn == null ? entryDoor : transform(
            exteriorOrigin, entry.safeSpawn, exteriorRotation
        );
        BlockPos exitDoor = interiorOrigin.offset(exit.position);
        BlockPos inside = interiorSpawn != null ? interiorOrigin.offset(interiorSpawn.position)
            : exit.safeSpawn != null ? interiorOrigin.offset(exit.safeSpawn) : exitDoor;
        registerDoor(exterior, entryDoor, new DoorTarget(INTERIORS, inside, "입장"));
        registerDoor(interiors, exitDoor, new DoorTarget(exterior.dimension(), outside, "퇴장"));
    }

    private static boolean spawnNpc(ServerLevel level, String npcId, BlockPos position) {
        String slug = npcId.substring(Math.max(npcId.lastIndexOf('/'), npcId.lastIndexOf(':')) + 1);
        String preset = "easy_npc:preset/encounter/" + slug + ".npc.snbt";
        String command = "easy_npc preset import_new data " + preset + " "
            + position.getX() + " " + position.getY() + " " + position.getZ();
        try {
            int result = level.getServer().getCommands().getDispatcher().execute(
                command,
                level.getServer().createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(Vec3.atLowerCornerOf(position))
                    .withPermission(4)
                    .withSuppressedOutput()
            );
            if (result == 0) {
                LOGGER.warn("Building NPC command returned no result: npc={}, position={}", npcId, position);
            }
            return result != 0;
        } catch (CommandSyntaxException error) {
            LOGGER.error("Building NPC placement failed: npc={}, position={}", npcId, position, error);
            return false;
        }
    }

    private static void registerDoor(ServerLevel level, BlockPos lower, DoorTarget target) {
        DOORS.put(new DoorKey(level.dimension(), lower.immutable()), target);
        DOORS.put(new DoorKey(level.dimension(), lower.above().immutable()), target);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DoorTarget target = DOORS.get(new DoorKey(player.level().dimension(), event.getPos()));
        if (target == null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        long gameTime = player.level().getGameTime();
        if (player.getPersistentData().getLong(INTERACTION_COOLDOWN) > gameTime) {
            return;
        }
        player.getPersistentData().putLong(INTERACTION_COOLDOWN, gameTime + 10L);
        ServerLevel destination = player.getServer().getLevel(target.dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal("[건물 문] 이동할 공간을 찾을 수 없습니다."));
            return;
        }
        player.sendSystemMessage(Component.literal("[건물 문] " + target.action + "합니다."));
        player.teleportTo(
            destination,
            target.position.getX() + 0.5D, target.position.getY(), target.position.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
    }

    private static BlockPos transform(BlockPos origin, BlockPos local, Rotation rotation) {
        return origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    private static Rotation rotation(String value) {
        return switch (value) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static BlockPos instanceOrigin(String key) {
        int hash = key.hashCode();
        int x = Math.floorMod(hash, 4096) - 2048;
        int z = Math.floorMod(Integer.rotateLeft(hash, 13), 4096) - 2048;
        return new BlockPos(x * SLOT_SPACING, SLOT_Y, z * SLOT_SPACING);
    }

    private static String instanceKey(ServerLevel level, String structure, BlockPos origin) {
        return level.dimension().location() + "|" + structure + "|"
            + origin.getX() + "," + origin.getY() + "," + origin.getZ();
    }

    private static void forceChunks(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = origin.getX() >> 4; x <= (origin.getX() + size.getX()) >> 4; x++) {
            for (int z = origin.getZ() >> 4; z <= (origin.getZ() + size.getZ()) >> 4; z++) {
                level.getChunk(x, z);
            }
        }
    }

    private static BlockPos position(JsonObject value, String key, BlockPos fallback) {
        if (!value.has(key)) {
            return fallback;
        }
        var array = value.getAsJsonArray(key);
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("Building metadata field is required: " + key);
        }
        return value.get(key).getAsString();
    }

    private static RuntimeData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RuntimeData::new, RuntimeData::load), DATA_FILE
        );
    }

    private record Anchor(String id, String type, BlockPos position, BlockPos safeSpawn) {
    }

    private record StructureMetadata(List<Anchor> anchors) {
        Anchor first(String type) {
            return anchors.stream().filter(anchor -> anchor.type.equals(type)).findFirst().orElse(null);
        }
    }

    private record BuildingSettings(Map<String, String> fixedNpcs, boolean citizenPlacementAllowed) {
    }

    private record DoorKey(ResourceKey<Level> dimension, BlockPos position) {
    }

    private record DoorTarget(ResourceKey<Level> dimension, BlockPos position, String action) {
    }

    static final class RuntimeData extends SavedData {
        private final Set<String> spawned = new HashSet<>();
        private final Set<String> prepared = new HashSet<>();

        static RuntimeData load(CompoundTag tag, HolderLookup.Provider registries) {
            RuntimeData data = new RuntimeData();
            readSet(tag.getString("spawned"), data.spawned);
            readSet(tag.getString("prepared"), data.prepared);
            return data;
        }

        boolean hasSpawned(String key) {
            return spawned.contains(key);
        }

        void markSpawned(String key) {
            if (spawned.add(key)) {
                setDirty();
            }
        }

        boolean hasPrepared(String key) {
            return prepared.contains(key);
        }

        void markPrepared(String key) {
            if (prepared.add(key)) {
                setDirty();
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putString("spawned", String.join("\n", spawned));
            tag.putString("prepared", String.join("\n", prepared));
            return tag;
        }

        private static void readSet(String serialized, Set<String> target) {
            if (!serialized.isBlank()) {
                target.addAll(List.of(serialized.split("\\n")));
            }
        }
    }
}
