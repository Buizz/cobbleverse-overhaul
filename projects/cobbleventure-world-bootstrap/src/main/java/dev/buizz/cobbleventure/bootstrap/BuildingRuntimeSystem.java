package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.block.entity.DisplayCaseBlockEntity;
import com.cobblemon.mod.common.block.entity.HealingMachineBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.PokemonCenterHealingService;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventMovementFailureReason;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import dev.buizz.cobbleventure.playermenu.MusicPlayback;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Objective;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Applies builder-authored anchors and building NPC assignments after template placement. */
final class BuildingRuntimeSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FIXED_POKEMON_TAG = "cobbleventure_building_pokemon";
    private static final String DATA_FILE = "cobbleventure_building_runtime";
    private static final String INTERACTION_COOLDOWN = "cobbleventureBuildingDoorCooldown";
    private static final int LARGE_SLOT_SPACING = 512;
    private static final int COMPACT_SLOT_SPACING = 128;
    private static final int COMPACT_TEMPLATE_LIMIT = 96;
    private static final int SLOTS_PER_ROW = 32;
    private static final int SLOT_MARGIN = 32;
    private static final int SLOT_Y = 64;
    private static final ResourceKey<Level> INTERIORS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "building_interiors")
    );
    private static final Map<String, StructureMetadata> METADATA = new LinkedHashMap<>();
    private static final Map<String, BuildingSettings> SETTINGS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();
    private static final Map<String, EventSpaceInstance> EVENT_SPACES = new LinkedHashMap<>();
    private static final Map<UUID, PendingNpcSeat> PENDING_NPC_SEATS = new LinkedHashMap<>();
    private BuildingRuntimeSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(BuildingRuntimeSystem::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST, BuildingRuntimeSystem::onEntityInteract
        );
        NeoForge.EVENT_BUS.addListener(BuildingRuntimeSystem::onServerTick);
    }

    static void initialize(MinecraftServer server) {
        METADATA.clear();
        SETTINGS.clear();
        DOORS.clear();
        EVENT_SPACES.clear();
        PENDING_NPC_SEATS.clear();
        StructurePlacementFixes.clearPendingElevatorAssemblies();
        loadMetadata(server);
        loadSettings(server);
        if (!METADATA.isEmpty() && server.getLevel(INTERIORS) == null) {
            throw new IllegalStateException("Cobbleventure building_interiors dimension is missing");
        }
        int restored = restorePersistedBuildingInstances(server);
        LOGGER.info(
            "Building runtime loaded: metadata={}, configured={}, restoredInstances={}",
            METADATA.size(), SETTINGS.size(), restored
        );
    }

    static void onStructurePlaced(
        ServerLevel level, String structure, CobbleventureBootstrap.BlockPoint origin,
        String rotationName
    ) {
        onStructurePlaced(level, structure, origin, rotationName, null, null);
    }

    static void onStructurePlaced(
        ServerLevel level, String structure, CobbleventureBootstrap.BlockPoint origin,
        String rotationName, String eventSpaceId
    ) {
        onStructurePlaced(level, structure, origin, rotationName, eventSpaceId, null);
    }

    static void onStructurePlaced(
        ServerLevel level, String structure, CobbleventureBootstrap.BlockPoint origin,
        String rotationName, String eventSpaceId,
        List<CobbleventureBootstrap.ShopVendorAssignment> vendorAssignments
    ) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) {
            return;
        }
        Rotation rotation = rotation(rotationName);
        String instanceKey = instanceKey(level, structure, origin.toBlockPos());
        BuildingSettings settings = settingsForStructure(structure);
        applyFixedNpcs(
            level, metadata, origin.toBlockPos(), rotation, instanceKey,
            settings == null ? Map.of() : settings.fixedNpcs, "exterior"
        );
        applyFixedVendors(
            level, metadata, origin.toBlockPos(), rotation, instanceKey,
            settings == null ? Map.of() : settings.fixedVendors, "exterior"
        );
        applyFixedPokemon(
            level, metadata, origin.toBlockPos(), rotation, instanceKey,
            settings == null ? Map.of() : settings.fixedPokemon, "exterior"
        );
        applyFixedGachaMachines(
            level, metadata, origin.toBlockPos(), rotation, instanceKey,
            settings == null ? Map.of() : settings.fixedGachaMachines, "exterior"
        );
        if (settings != null && settings.noInteriorSpace) {
            return;
        }
        if (settings != null && !settings.routes.isEmpty()) {
            if (!hasExteriorRouteDoor(level, metadata, origin.toBlockPos(), rotation, settings)) {
                LOGGER.warn(
                    "Configured building runtime skipped because its exterior door is absent: "
                        + "dimension={}, structure={}, origin={}, rotation={}",
                    level.dimension().location(), structure, origin, rotationName
                );
                return;
            }
            data(level.getServer()).rememberBuildingInstance(
                level.dimension().location().toString(), structure,
                origin.toBlockPos(), rotationName, eventSpaceId
            );
            prepareConfiguredInteriors(
                level, structure, metadata, origin.toBlockPos(), rotation,
                instanceKey, settings, eventSpaceId, vendorAssignments
            );
        }
    }

    static EventLocationResolverRegistry.Resolution resolveEventSpace(
        MinecraftServer server, EventLocationRef.Resource destination
    ) {
        if (!destination.resourceId().contains(":building/")) {
            return null;
        }
        EventSpaceInstance registration = EVENT_SPACES.get(destination.resourceId());
        if (registration == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_NOT_FOUND
            );
        }
        if (destination.anchor() == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_REQUIRED
            );
        }
        int separator = destination.anchor().indexOf('/');
        if (separator <= 0 || separator == destination.anchor().length() - 1) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        String spaceKey = destination.anchor().substring(0, separator);
        String anchorId = destination.anchor().substring(separator + 1);
        SpaceInstance space = registration.spaces.get(spaceKey);
        Anchor anchor = space == null ? null : space.metadata.anchors.stream()
            .filter(candidate -> candidate.id.equals(anchorId))
            .findFirst().orElse(null);
        if (anchor == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        BlockPos local = anchor.safeSpawn == null ? anchor.position : anchor.safeSpawn;
        BlockPos position = transform(space.origin, local, space.rotation);
        float yaw = space.rotation.rotate(anchor.facing).toYRot();
        return EventLocationResolverRegistry.Resolution.resolved(
            new EventLocationResolverRegistry.ResolvedLocation(
                space.level.dimension().location().toString(),
                position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D,
                yaw, null
            )
        );
    }

    static SpawnDestination resolveStarterSpawn(
        ServerLevel exterior, String exteriorStructure,
        CobbleventureBootstrap.BlockPoint exteriorOrigin, String rotationName,
        String requestedSpace, String npcSlot
    ) {
        BuildingSettings settings = settingsForStructure(exteriorStructure);
        if (settings == null || settings.interiors.isEmpty()) {
            return null;
        }
        InteriorSetting interior = requestedSpace == null || requestedSpace.isBlank()
            ? settings.interiors.getFirst()
            : settings.interiors.stream()
                .filter(candidate -> candidate.key.equals(requestedSpace))
                .findFirst().orElse(null);
        if (interior == null) {
            return null;
        }
        StructureMetadata metadata = METADATA.get(interior.structure);
        ServerLevel level = exterior.getServer().getLevel(INTERIORS);
        if (metadata == null || level == null) {
            return null;
        }
        String key = instanceKey(
            exterior, exteriorStructure, exteriorOrigin.toBlockPos()
        );
        BlockPos base = instanceOrigin(data(exterior.getServer()), key, false);
        int index = settings.interiors.indexOf(interior);
        BlockPos origin = base.offset(
            (index % 4) * 128, placementYOffset(interior.structure), (index / 4) * 128
        );
        Anchor anchor = npcSlot == null || npcSlot.isBlank()
            ? metadata.first("door") : metadata.namedNpc(npcSlot);
        if (anchor == null) {
            return null;
        }
        BlockPos local = anchor.safeSpawn == null ? anchor.position : anchor.safeSpawn;
        BlockPos position = transform(origin, local, Rotation.NONE);
        String preparedKey = key + "|space|" + interior.key;
        RuntimeData runtime = data(exterior.getServer());
        level.getChunk(position.getX() >> 4, position.getZ() >> 4);
        if (!runtime.hasPrepared(preparedKey)
            || !hasAuthoredInteriorSupport(level, origin, metadata)
            || !hasSafeSpawnSupport(level, position)) {
            StructureMetadata exteriorMetadata = METADATA.get(exteriorStructure);
            if (exteriorMetadata != null) {
                LOGGER.warn(
                    "Starter interior was not ready and will be prepared synchronously: "
                        + "structure={}, space={}, instance={}, origin={}",
                    interior.structure, interior.key, key, origin
                );
                prepareConfiguredInteriors(
                    exterior, exteriorStructure, exteriorMetadata,
                    exteriorOrigin.toBlockPos(), rotation(rotationName), key, settings, null, null
                );
                level.getChunk(position.getX() >> 4, position.getZ() >> 4);
            }
        }
        if (!runtime.hasPrepared(preparedKey)
            || !hasAuthoredInteriorSupport(level, origin, metadata)
            || !hasSafeSpawnSupport(level, position)) {
            LOGGER.error(
                "Starter interior destination rejected after synchronous preparation: "
                    + "structure={}, space={}, instance={}, origin={}, spawn={}",
                interior.structure, interior.key, key, origin, position
            );
            return null;
        }
        return new SpawnDestination(level, position, anchor.facing.toYRot());
    }

    static SpawnDestination resolveAutomaticNpcSpawn(
        ServerLevel exterior, String exteriorStructure,
        CobbleventureBootstrap.BlockPoint exteriorOrigin, String rotationName, int slot
    ) {
        BuildingSettings settings = settingsForStructure(exteriorStructure);
        ServerLevel interiorsLevel = exterior.getServer().getLevel(INTERIORS);
        if (settings == null || interiorsLevel == null || slot < 0) {
            return null;
        }
        Set<String> reachable = reachableInteriorSpaces(settings);
        String key = instanceKey(exterior, exteriorStructure, exteriorOrigin.toBlockPos());
        BlockPos base = instanceOrigin(data(exterior.getServer()), key, false);
        int remaining = slot;
        for (int index = 0; index < settings.interiors.size(); index++) {
            InteriorSetting interior = settings.interiors.get(index);
            if (!reachable.contains(interior.key)) {
                continue;
            }
            StructureMetadata metadata = METADATA.get(interior.structure);
            if (metadata == null) {
                continue;
            }
            for (Anchor anchor : metadata.anchors) {
                if (!anchor.type.equals("npc_position")) {
                    continue;
                }
                if (remaining-- > 0) {
                    continue;
                }
                BlockPos origin = base.offset(
                    (index % 4) * 128, placementYOffset(interior.structure),
                    (index / 4) * 128
                );
                BlockPos position = transform(origin, anchor.position, Rotation.NONE);
                return new SpawnDestination(interiorsLevel, position, anchor.facing.toYRot());
            }
        }
        return null;
    }

    static void showAutomaticNpcPresence(
        ServerLevel exterior, String exteriorStructure,
        CobbleventureBootstrap.BlockPoint exteriorOrigin, String rotationName, int count
    ) {
        if (count <= 0) {
            return;
        }
        StructureMetadata metadata = METADATA.get(exteriorStructure);
        if (metadata == null) {
            return;
        }
        Anchor door = metadata.first("door");
        if (door == null) {
            return;
        }
        Rotation rotation = rotation(rotationName);
        BlockPos local = door.safeSpawn == null ? door.position : door.safeSpawn;
        BlockPos markerPosition = transform(exteriorOrigin.toBlockPos(), local, rotation);
        String instance = instanceKey(exterior, exteriorStructure, exteriorOrigin.toBlockPos());
        String markerTag = "cv_npc_presence_" + Integer.toUnsignedString(instance.hashCode(), 36);
        AABB search = new AABB(markerPosition).inflate(4.0D, 4.0D, 4.0D);
        Display.TextDisplay display = exterior.getEntitiesOfClass(
            Display.TextDisplay.class, search, entity -> entity.getTags().contains(markerTag)
        ).stream().findFirst().orElse(null);
        if (display == null) {
            display = EntityType.TEXT_DISPLAY.create(exterior);
            if (display == null) {
                return;
            }
            display.addTag("cobbleventure_npc_presence");
            display.addTag(markerTag);
            display.setPos(
                markerPosition.getX() + 0.5D, markerPosition.getY() + 2.35D,
                markerPosition.getZ() + 0.5D
            );
            if (!exterior.addFreshEntity(display)) {
                return;
            }
        }
        configureNpcPresenceDisplay(display, count);
    }

    static void removeNearbyEasyNpc(ServerLevel level, BlockPos position) {
        AABB nearby = new AABB(position).inflate(2.25D, 2.5D, 2.25D);
        level.getEntities((Entity) null, nearby, BuildingRuntimeSystem::isEasyNpc)
            .stream().min(java.util.Comparator.comparingDouble(
                entity -> entity.distanceToSqr(Vec3.atCenterOf(position))
            )).ifPresent(Entity::discard);
    }

    private static Set<String> reachableInteriorSpaces(BuildingSettings settings) {
        Set<String> reachable = new HashSet<>();
        reachable.add("exterior");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, RouteTarget> route : settings.routes.entrySet()) {
                int separator = route.getKey().indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String source = route.getKey().substring(0, separator);
                String target = route.getValue().space;
                if (reachable.contains(source) && reachable.add(target)) {
                    changed = true;
                }
                if (reachable.contains(target) && reachable.add(source)) {
                    changed = true;
                }
            }
        }
        reachable.remove("exterior");
        return reachable;
    }

    private static void configureNpcPresenceDisplay(Display.TextDisplay display, int count) {
        CompoundTag data = display.saveWithoutId(new CompoundTag());
        data.putString(
            "text",
            "{\"text\":\"◆ NPC " + count + "\",\"color\":\"#8dffad\",\"bold\":true}"
        );
        data.putString("billboard", "center");
        data.putInt("background", 0x55000000);
        data.putBoolean("shadow", true);
        data.putBoolean("see_through", true);
        data.putFloat("view_range", 0.75F);
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0F, 0.0F, 0.0F));
        transformation.put("scale", floatList(0.65F, 0.65F, 0.65F));
        transformation.put("left_rotation", floatList(0.0F, 0.0F, 0.0F, 1.0F));
        transformation.put("right_rotation", floatList(0.0F, 0.0F, 0.0F, 1.0F));
        data.put("transformation", transformation);
        display.load(data);
    }

    private static ListTag floatList(float... values) {
        ListTag result = new ListTag();
        for (float value : values) {
            result.add(FloatTag.valueOf(value));
        }
        return result;
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
                    position(value, "safe_spawn", null),
                    direction(value.has("facing")
                        ? value.get("facing").getAsString()
                        : value.has("door_facing")
                            ? value.get("door_facing").getAsString() : "north"),
                    value.has("seal_entry") && value.get("seal_entry").getAsBoolean()
                ));
            }
        }
        String interiorStructure = null;
        if (root.has("interior_structure")) {
            interiorStructure = requiredString(root, "interior_structure");
        }
        return new StructureMetadata(List.copyOf(anchors), interiorStructure);
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
                Map<String, String> fixedPokemon = new LinkedHashMap<>();
                if (value.has("fixed_pokemon")) {
                    for (Map.Entry<String, JsonElement> pokemon
                        : value.getAsJsonObject("fixed_pokemon").entrySet()) {
                        fixedPokemon.put(pokemon.getKey(), pokemon.getValue().getAsString());
                    }
                }
                Map<String, String> fixedVendors = new LinkedHashMap<>();
                if (value.has("fixed_vendors")) {
                    for (Map.Entry<String, JsonElement> vendor
                        : value.getAsJsonObject("fixed_vendors").entrySet()) {
                        fixedVendors.put(vendor.getKey(), vendor.getValue().getAsString());
                    }
                }
                Map<String, String> fixedGachaMachines = new LinkedHashMap<>();
                if (value.has("fixed_gacha_machines")) {
                    for (Map.Entry<String, JsonElement> machine
                        : value.getAsJsonObject("fixed_gacha_machines").entrySet()) {
                        fixedGachaMachines.put(machine.getKey(), machine.getValue().getAsString());
                    }
                }
                List<InteriorSetting> interiors = new ArrayList<>();
                if (value.has("interiors")) {
                    for (JsonElement interiorElement : value.getAsJsonArray("interiors")) {
                        JsonObject interior = interiorElement.getAsJsonObject();
                        interiors.add(new InteriorSetting(
                            requiredString(interior, "key"),
                            requiredString(interior, "structure")
                        ));
                    }
                }
                Map<String, RouteTarget> routes = new LinkedHashMap<>();
                if (value.has("door_routes")) {
                    for (Map.Entry<String, JsonElement> route
                        : value.getAsJsonObject("door_routes").entrySet()) {
                        JsonObject target = route.getValue().getAsJsonObject();
                        List<PlayerConditions.Condition> conditions = new ArrayList<>();
                        if (target.has("conditions")) {
                            for (JsonElement condition : target.getAsJsonArray("conditions")) {
                                conditions.add(PlayerConditions.parse(condition.getAsJsonObject()));
                            }
                        }
                        routes.put(route.getKey(), new RouteTarget(
                            requiredString(target, "space"),
                            target.has("door") ? requiredString(target, "door")
                                : requiredString(target, "arrival"),
                            target.has("condition_mode")
                                ? target.get("condition_mode").getAsString() : "all",
                            List.copyOf(conditions),
                            strings(target, "locked_dialogue", List.of("문이 잠겨 있다.")),
                            strings(target, "enter_dialogue", List.of())
                        ));
                    }
                }
                SETTINGS.put(entry.getKey(), new BuildingSettings(
                    value.has("placement_y_offset")
                        ? value.get("placement_y_offset").getAsInt() : 0,
                    value.has("no_interior_space")
                        && value.get("no_interior_space").getAsBoolean(),
                    Map.copyOf(fixed),
                    Map.copyOf(fixedPokemon),
                    Map.copyOf(fixedVendors),
                    Map.copyOf(fixedGachaMachines),
                    value.has("citizen_placement_allowed")
                        ? value.get("citizen_placement_allowed").getAsBoolean()
                        : value.has("random_citizen_eligible")
                            && value.get("random_citizen_eligible").getAsBoolean(),
                    List.copyOf(interiors), Map.copyOf(routes),
                    value.has("music_track") ? value.get("music_track").getAsString() : null
                ));
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid building settings: " + location, error);
        }
    }

    private static List<String> strings(
        JsonObject parent, String key, List<String> fallback
    ) {
        if (!parent.has(key)) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : parent.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    static int placementYOffset(String structure) {
        BuildingSettings settings = settingsForStructure(structure);
        return settings == null ? 0 : settings.placementYOffset;
    }

    static BlockPos exteriorDoorApproachOffset(
        String structure, String rotationName
    ) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) {
            return null;
        }
        Anchor door = metadata.first("door");
        if (door == null || door.position == null) {
            return null;
        }
        BlockPos approach = door.safeSpawn == null
            ? door.position : door.safeSpawn;
        return StructureTemplate.transform(
            approach, Mirror.NONE, rotation(rotationName), BlockPos.ZERO
        );
    }

    static BlockPos exteriorRoadAnchorOffset(
        ServerLevel level, String structure, String rotationName
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return null;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return null;
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation(rotationName));
        List<StructureTemplate.StructureBlockInfo> anchors = template.orElseThrow()
            .filterBlocks(BlockPos.ZERO, settings, Blocks.JIGSAW).stream()
            .filter(marker -> marker.nbt() != null
                && "cobbleventure:road_anchor".equals(marker.nbt().getString("name")))
            .toList();
        if (anchors.size() > 1) {
            LOGGER.error(
                "Building template has multiple road anchors: structure={}, count={}",
                structure, anchors.size()
            );
            return null;
        }
        return anchors.isEmpty() ? null : anchors.getFirst().pos();
    }

    static String musicTrack(String structure) {
        BuildingSettings settings = settingsForStructure(structure);
        return settings == null ? null : settings.musicTrack;
    }

    /**
     * Generated house variants append a roof-colour suffix to the editor's
     * base resource ID. Resolve them to the explicitly authored base setting;
     * the absence of that setting must never imply an automatic interior.
     */
    private static BuildingSettings settingsForStructure(String structure) {
        BuildingSettings exact = SETTINGS.get(structure);
        if (exact != null || !structure.startsWith("cobbleventure:houses/")) {
            return exact;
        }
        String bestMatch = null;
        for (String candidate : SETTINGS.keySet()) {
            if (candidate.startsWith("cobbleventure:houses/")
                && structure.startsWith(candidate + "_")
                && (bestMatch == null || candidate.length() > bestMatch.length())) {
                bestMatch = candidate;
            }
        }
        return bestMatch == null ? null : SETTINGS.get(bestMatch);
    }

    private static boolean hasExteriorRouteDoor(
        ServerLevel level, StructureMetadata metadata, BlockPos origin,
        Rotation rotation, BuildingSettings settings
    ) {
        boolean hasExteriorRoute = false;
        for (String route : settings.routes.keySet()) {
            if (!route.startsWith("exterior:")) {
                continue;
            }
            hasExteriorRoute = true;
            Anchor anchor = metadata.namedDoor(route.substring("exterior:".length()));
            if (anchor != null
                && level.getBlockState(transform(origin, anchor.position, rotation)).getBlock()
                    instanceof DoorBlock) {
                return true;
            }
        }
        return !hasExteriorRoute;
    }

    private static int restorePersistedBuildingInstances(MinecraftServer server) {
        int restored = 0;
        for (PersistedBuildingInstance instance : data(server).buildingInstances()) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(instance.dimension);
            if (dimensionId == null) {
                LOGGER.warn("Ignoring building runtime instance with invalid dimension: {}", instance);
                continue;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            StructureMetadata metadata = METADATA.get(instance.structure);
            BuildingSettings settings = settingsForStructure(instance.structure);
            if (level == null || metadata == null || settings == null || settings.routes.isEmpty()) {
                continue;
            }
            String rotationName = instance.rotation;
            if (rotationName == null || rotationName.isBlank()
                || !hasExteriorRouteDoor(
                    level, metadata, instance.origin, rotation(rotationName), settings
                )) {
                rotationName = detectExteriorRotation(level, instance.origin, metadata, settings);
            }
            if (rotationName == null) {
                LOGGER.warn(
                    "Persisted building runtime door could not be found: dimension={}, "
                        + "structure={}, origin={}",
                    instance.dimension, instance.structure, instance.origin
                );
                continue;
            }
            onStructurePlaced(
                level, instance.structure,
                new CobbleventureBootstrap.BlockPoint(
                    instance.origin.getX(), instance.origin.getY(), instance.origin.getZ()
                ),
                rotationName, instance.eventSpaceId
            );
            restored++;
        }
        return restored;
    }

    private static String detectExteriorRotation(
        ServerLevel level, BlockPos origin, StructureMetadata metadata,
        BuildingSettings settings
    ) {
        for (String rotationName : List.of(
            "none", "clockwise_90", "clockwise_180", "counterclockwise_90"
        )) {
            if (hasExteriorRouteDoor(
                level, metadata, origin, rotation(rotationName), settings
            )) {
                return rotationName;
            }
        }
        return null;
    }

    private static void applyFixedNpcs(
        ServerLevel level, StructureMetadata metadata,
        BlockPos origin, Rotation rotation, String instanceKey,
        Map<String, String> fixedNpcs, String spaceKey
    ) {
        if (fixedNpcs.isEmpty()) {
            return;
        }
        RuntimeData data = data(level.getServer());
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("npc_position")) {
                continue;
            }
            String scoped = spaceKey + ":" + anchor.id;
            String npc = FixedNpcAssignments.match(fixedNpcs, scoped);
            if (npc == null && spaceKey.equals("exterior")) {
                npc = FixedNpcAssignments.match(fixedNpcs, anchor.id);
            }
            String spawnKey = instanceKey + "|npc|" + scoped;
            if (npc == null || data.hasSpawned(spawnKey)) {
                continue;
            }
            BlockPos position = transform(origin, anchor.position, rotation);
            float yaw = rotation.rotate(anchor.facing).toYRot();
            if (spawnNpc(level, npc, position, yaw)) {
                data.markSpawned(spawnKey);
            }
        }
    }

    private static void applyFixedVendors(
        ServerLevel level, StructureMetadata metadata,
        BlockPos origin, Rotation rotation, String instanceKey,
        Map<String, String> fixedVendors, String spaceKey
    ) {
        if (fixedVendors.isEmpty()) return;
        RuntimeData runtime = data(level.getServer());
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("npc_position")) continue;
            String scoped = spaceKey + ":" + anchor.id;
            String vendor = fixedVendors.get(scoped);
            if (vendor == null && spaceKey.equals("exterior")) {
                vendor = fixedVendors.get(anchor.id);
            }
            String spawnKey = instanceKey + "|vendor|" + scoped;
            if (vendor == null || runtime.hasSpawned(spawnKey)) continue;
            BlockPos position = transform(origin, anchor.position, rotation);
            float yaw = rotation.rotate(anchor.facing).toYRot();
            if (CobbleventureBootstrap.spawnConfiguredVendor(
                level, vendor, new CobbleventureBootstrap.BlockPoint(
                    position.getX(), position.getY(), position.getZ()
                ), yaw
            )) {
                runtime.markSpawned(spawnKey);
            }
        }
    }

    private static void applyFixedGachaMachines(
        ServerLevel level, StructureMetadata metadata,
        BlockPos origin, Rotation rotation, String instanceKey,
        Map<String, String> fixedMachines, String spaceKey
    ) {
        if (fixedMachines.isEmpty()) return;
        RuntimeData runtime = data(level.getServer());
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("npc_position")) continue;
            String scoped = spaceKey + ":" + anchor.id;
            String profile = fixedMachines.get(scoped);
            if (profile == null && spaceKey.equals("exterior")) profile = fixedMachines.get(anchor.id);
            String spawnKey = instanceKey + "|gacha|" + scoped;
            if (profile == null || runtime.hasSpawned(spawnKey)) continue;
            BlockPos position = transform(origin, anchor.position, rotation);
            if (placeConfiguredGachaMachine(level, position, profile)) runtime.markSpawned(spawnKey);
        }
    }

    private static boolean placeConfiguredGachaMachine(
        ServerLevel level, BlockPos position, String profile
    ) {
        try {
            Class<?> integration = Class.forName(
                "dev.buizz.cobbleventure.casino.CobbleventureCasino"
            );
            Object result = integration.getMethod(
                "placeConfiguredMachine", ServerLevel.class, BlockPos.class, String.class
            ).invoke(null, level, position, profile);
            return result instanceof Boolean placed && placed;
        } catch (ReflectiveOperationException error) {
            LOGGER.warn(
                "Casino gacha integration unavailable: profile={}, position={}",
                profile, position, error
            );
            return false;
        }
    }

    private static void applyFixedPokemon(
        ServerLevel level, StructureMetadata metadata,
        BlockPos origin, Rotation rotation, String instanceKey,
        Map<String, String> fixedPokemon, String spaceKey
    ) {
        if (fixedPokemon.isEmpty()) return;
        RuntimeData runtime = data(level.getServer());
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("npc_position")) continue;
            String scoped = spaceKey + ":" + anchor.id;
            String properties = fixedPokemon.get(scoped);
            if (properties == null && spaceKey.equals("exterior")) {
                properties = fixedPokemon.get(anchor.id);
            }
            String spawnKey = instanceKey + "|pokemon|" + scoped;
            if (properties == null || runtime.hasSpawned(spawnKey)) continue;
            BlockPos position = transform(origin, anchor.position, rotation);
            float yaw = rotation.rotate(anchor.facing).toYRot();
            if (spawnFixedPokemon(level, properties, position, yaw)) {
                runtime.markSpawned(spawnKey);
            }
        }
    }

    private static boolean spawnFixedPokemon(
        ServerLevel level, String properties, BlockPos position, float yaw
    ) {
        if (!canNpcStandAt(level, position)) {
            LOGGER.warn(
                "Building Pokemon spawn position is obstructed: pokemon={}, position={}",
                properties, position
            );
            return false;
        }
        try {
            PokemonEntity entity = PokemonProperties.Companion
                .parse(properties + " uncatchable").createEntity(level);
            entity.moveTo(
                position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D,
                yaw, 0.0F
            );
            applyEntityFacing(entity, yaw);
            entity.setPersistenceRequired();
            entity.setCountsTowardsSpawnCap(false);
            entity.getPokemon().setTradeable(false);
            entity.setNoAi(true);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.addTag(FIXED_POKEMON_TAG);
            return level.addFreshEntity(entity);
        } catch (RuntimeException error) {
            LOGGER.error(
                "Building Pokemon placement failed: pokemon={}, position={}",
                properties, position, error
            );
            return false;
        }
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
            || !NurseNpcRouting.usesLegacyFallback(event.getTarget().getTags())) {
            return;
        }
        // Existing worlds may still contain the old V4 representation. Keep its
        // verified service behavior while V5-bound nurses are owned by CVES.
        PokemonCenterHealingService.StartResult healing =
            PokemonCenterHealingService.start(
                player, event.getTarget().blockPosition(), 8
            );
        switch (healing.status()) {
            case HEALING_MACHINE_NOT_FOUND -> player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.pokemon_center_no_healer"
            ));
            case HEALING_UNAVAILABLE -> player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.pokemon_center_healer_unavailable"
            ));
            case STARTED -> player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.pokemon_center_nurse_greeting"
            ));
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void prepareConfiguredInteriors(
        ServerLevel exterior, String exteriorStructure, StructureMetadata exteriorMetadata,
        BlockPos exteriorOrigin, Rotation exteriorRotation, String instanceKey,
        BuildingSettings settings, String eventSpaceId,
        List<CobbleventureBootstrap.ShopVendorAssignment> vendorAssignments
    ) {
        ServerLevel interiorsLevel = exterior.getServer().getLevel(INTERIORS);
        if (interiorsLevel == null) {
            return;
        }
        Map<String, SpaceInstance> spaces = new LinkedHashMap<>();
        spaces.put("exterior", new SpaceInstance(
            exterior, exteriorOrigin, exteriorRotation, exteriorMetadata, null
        ));
        RuntimeData runtime = data(exterior.getServer());
        BlockPos base = instanceOrigin(runtime, instanceKey, false);
        int index = 0;
        for (InteriorSetting interior : settings.interiors) {
            StructureMetadata metadata = METADATA.get(interior.structure);
            if (metadata == null) {
                LOGGER.warn("Configured interior metadata is missing: {}", interior.structure);
                continue;
            }
            ResourceLocation structureId = ResourceLocation.tryParse(interior.structure);
            var template = structureId == null ? java.util.Optional.<StructureTemplate>empty()
                : interiorsLevel.getStructureManager().get(structureId);
            if (template.isEmpty()) {
                LOGGER.warn("Configured interior structure is missing: {}", interior.structure);
                continue;
            }
            BlockPos origin = base.offset(
                (index % 4) * 128, placementYOffset(interior.structure), (index / 4) * 128
            );
            String preparedKey = instanceKey + "|space|" + interior.key;
            boolean interiorPresent = hasAuthoredInteriorSupport(
                interiorsLevel, origin, metadata
            );
            if (!runtime.hasPrepared(preparedKey) || !interiorPresent) {
                if (runtime.hasPrepared(preparedKey)) {
                    LOGGER.warn(
                        "Prepared building interior is absent and will be rebuilt: "
                            + "structure={}, instance={}, origin={}",
                        interior.structure, instanceKey, origin
                    );
                }
                forceChunks(interiorsLevel, origin, template.orElseThrow().getSize());
                StructurePlaceSettings placementSettings = new StructurePlaceSettings()
                    .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
                    .addProcessor(CreateElevatorEntityPlacementProcessor.INSTANCE);
                boolean placed = template.orElseThrow().placeInWorld(
                    interiorsLevel, origin, origin, placementSettings,
                    RandomSource.create(interiorsLevel.getSeed() ^ origin.asLong()), 2
                );
                if (!placed) {
                    LOGGER.error(
                        "Configured interior placement failed: structure={}, instance={}",
                        interior.structure, instanceKey
                    );
                    index++;
                    continue;
                }
                StructurePlacementFixes.afterPlacement(
                    interiorsLevel, origin, template.orElseThrow(), placementSettings
                );
                if (!hasAuthoredInteriorSupport(interiorsLevel, origin, metadata)) {
                    LOGGER.error(
                        "Configured interior has no floor at its authored safe spawn after "
                            + "placement: structure={}, instance={}, origin={}",
                        interior.structure, instanceKey, origin
                    );
                    index++;
                    continue;
                }
                runtime.markPrepared(preparedKey);
            }
            // Also covers interiors persisted by an older runtime, where the structure is
            // already present but its saved Create elevator is still a static assembly.
            StructurePlacementFixes.scheduleElevatorAssemblies(
                interiorsLevel, origin, template.orElseThrow(), new StructurePlaceSettings()
            );
            spaces.put(interior.key, new SpaceInstance(
                interiorsLevel, origin, Rotation.NONE, metadata,
                template.orElseThrow().getSize()
            ));
            applyFixedNpcs(
                interiorsLevel, metadata, origin, Rotation.NONE,
                instanceKey + "|" + interior.key, settings.fixedNpcs, interior.key
            );
            Map<String, String> interiorVendors = settings.fixedVendors;
            if (vendorAssignments != null) {
                Map<String, String> catalogVendors = new LinkedHashMap<>();
                for (CobbleventureBootstrap.ShopVendorAssignment assignment
                    : vendorAssignments) {
                    catalogVendors.put(
                        interior.key + ":" + assignment.slotId(), assignment.vendorUnit()
                    );
                }
                interiorVendors = Map.copyOf(catalogVendors);
            }
            applyFixedVendors(
                interiorsLevel, metadata, origin, Rotation.NONE,
                instanceKey + "|" + interior.key, interiorVendors, interior.key
            );
            applyFixedGachaMachines(
                interiorsLevel, metadata, origin, Rotation.NONE,
                instanceKey + "|" + interior.key, settings.fixedGachaMachines, interior.key
            );
            index++;
        }

        if (eventSpaceId != null && !eventSpaceId.isBlank()) {
            EventSpaceInstance existing = EVENT_SPACES.get(eventSpaceId);
            if (existing != null && !existing.instanceKey.equals(instanceKey)) {
                throw new IllegalStateException(
                    "Duplicate building event space ID: " + eventSpaceId
                );
            }
            EVENT_SPACES.put(
                eventSpaceId, new EventSpaceInstance(instanceKey, Map.copyOf(spaces))
            );
        }

        for (Map.Entry<String, RouteTarget> route : settings.routes.entrySet()) {
            int separator = route.getKey().indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String sourceSpaceKey = route.getKey().substring(0, separator);
            String sourceDoorId = route.getKey().substring(separator + 1);
            SpaceInstance sourceSpace = spaces.get(sourceSpaceKey);
            SpaceInstance targetSpace = spaces.get(route.getValue().space);
            if (sourceSpace == null || targetSpace == null) {
                LOGGER.warn("Building route references an unavailable space: {}", route.getKey());
                continue;
            }
            Anchor sourceDoor = sourceSpace.metadata.namedDoor(sourceDoorId);
            Anchor targetDoor = targetSpace.metadata.namedDoor(route.getValue().door);
            if (sourceDoor == null || targetDoor == null) {
                LOGGER.warn("Building route references a missing door: {}", route.getKey());
                continue;
            }
            BlockPos door = transform(
                sourceSpace.origin, sourceDoor.position, sourceSpace.rotation
            );
            BlockPos targetDoorPosition = transform(
                targetSpace.origin, targetDoor.position, targetSpace.rotation
            );
            BlockPos destination = transform(
                targetSpace.origin,
                targetDoor.safeSpawn == null ? targetDoor.position : targetDoor.safeSpawn,
                targetSpace.rotation
            );
            BlockPos reverseDestination = transform(
                sourceSpace.origin,
                sourceDoor.safeSpawn == null ? sourceDoor.position : sourceDoor.safeSpawn,
                sourceSpace.rotation
            );
            registerDoor(
                sourceSpace.level, door,
                new DoorTarget(
                    targetSpace.level.dimension(), destination,
                    route.getValue().conditions, route.getValue().conditionMode,
                    route.getValue().lockedDialogue, route.getValue().enterDialogue,
                    !route.getValue().space.equals("exterior"), settings.musicTrack
                )
            );
            registerDoor(
                targetSpace.level, targetDoorPosition,
                new DoorTarget(
                    sourceSpace.level.dimension(), reverseDestination,
                    List.of(), "all", List.of(), List.of(),
                    !sourceSpaceKey.equals("exterior"), settings.musicTrack
                )
            );
        }
    }

    /** Returns authored facility IDs whose registered interior contains the player. */
    static Set<String> activeEventSpaces(ServerPlayer player) {
        Set<String> result = new HashSet<>();
        BlockPos position = player.blockPosition();
        for (Map.Entry<String, EventSpaceInstance> eventSpace : EVENT_SPACES.entrySet()) {
            for (Map.Entry<String, SpaceInstance> space : eventSpace.getValue().spaces.entrySet()) {
                if (space.getKey().equals("exterior")) continue;
                SpaceInstance instance = space.getValue();
                if (instance.level == player.serverLevel() && instance.contains(position)) {
                    result.add(eventSpace.getKey());
                    break;
                }
            }
        }
        return Set.copyOf(result);
    }

    static boolean spawnNpc(ServerLevel level, String npcId, BlockPos position) {
        return spawnNpc(level, npcId, position, 0.0F);
    }

    static boolean spawnNpc(
        ServerLevel level, String npcId, BlockPos position, float yaw
    ) {
        if (!isNpcSeatBlock(level.getBlockState(position))
            && !canNpcStandAt(level, position)) {
            LOGGER.warn(
                "Building NPC spawn position is obstructed: npc={}, position={}",
                npcId, position
            );
            return false;
        }
        UUID spawnedNpcId = UUID.randomUUID();
        String slug = npcId.substring(Math.max(npcId.lastIndexOf('/'), npcId.lastIndexOf(':')) + 1);
        String preset = "easy_npc:preset/encounter/" + slug
            + CobbleventureBootstrap.npcPresetSuffix(level, npcId) + ".npc.snbt";
        String command = "easy_npc preset import data " + preset + " "
            + position.getX() + " " + position.getY() + " " + position.getZ()
            + " " + spawnedNpcId;
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
            } else {
                Entity spawnedNpc = level.getEntity(spawnedNpcId);
                boolean seated = isNpcSeatBlock(level.getBlockState(position));
                if (spawnedNpc != null) {
                    CobbleventureBootstrap.applyNpcWorldFont(spawnedNpc);
                    applyEntityFacing(spawnedNpc, yaw);
                }
                if (spawnedNpc == null) {
                    PENDING_NPC_SEATS.put(
                        spawnedNpcId,
                        new PendingNpcSeat(
                            level.dimension(), position.immutable(), npcId, yaw, seated, 0
                        )
                    );
                } else if (seated
                    && !seatNpc(level, spawnedNpc, position)) {
                    LOGGER.warn(
                        "Building NPC was spawned but could not ride its seat: npc={}, uuid={}, position={}",
                        npcId, spawnedNpcId, position
                    );
                }
            }
            return result != 0;
        } catch (CommandSyntaxException error) {
            LOGGER.error("Building NPC placement failed: npc={}, position={}", npcId, position, error);
            return false;
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        StructurePlacementFixes.tickPendingElevatorAssemblies(event.getServer());
        if (PENDING_NPC_SEATS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PendingNpcSeat>> iterator =
            PENDING_NPC_SEATS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingNpcSeat> entry = iterator.next();
            PendingNpcSeat pending = entry.getValue();
            ServerLevel level = event.getServer().getLevel(pending.dimension);
            Entity npc = level == null ? null : level.getEntity(entry.getKey());
            if (npc != null) {
                CobbleventureBootstrap.applyNpcWorldFont(npc);
                applyEntityFacing(npc, pending.yaw);
                if (pending.seated && !seatNpc(level, npc, pending.position)) {
                    LOGGER.warn(
                        "Building NPC was spawned but could not ride its delayed seat: "
                            + "npc={}, uuid={}, position={}",
                        pending.npcId, entry.getKey(), pending.position
                    );
                }
                iterator.remove();
                continue;
            }
            if (pending.attempts >= 20) {
                LOGGER.warn(
                    "Building NPC was imported but its entity never became available: "
                        + "npc={}, uuid={}, position={}",
                    pending.npcId, entry.getKey(), pending.position
                );
                iterator.remove();
            } else {
                entry.setValue(new PendingNpcSeat(
                    pending.dimension, pending.position, pending.npcId,
                    pending.yaw, pending.seated, pending.attempts + 1
                ));
            }
        }
    }

    private static boolean isEasyNpc(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
            .getNamespace().equals("easy_npc");
    }

    private static void applyEntityFacing(Entity entity, float yaw) {
        entity.moveTo(entity.getX(), entity.getY(), entity.getZ(), yaw, 0.0F);
        entity.setYRot(yaw);
        entity.setXRot(0.0F);
        if (entity instanceof LivingEntity living) {
            living.setYBodyRot(yaw);
            living.setYHeadRot(yaw);
        }
    }

    private static boolean isNpcSeatBlock(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.equals("chair") || path.endsWith("_chair")
            || path.equals("stool") || path.endsWith("_stool")
            || path.equals("seat") || path.endsWith("_seat")
            || path.equals("bench") || path.endsWith("_bench");
    }

    private static boolean canNpcStandAt(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight()
            || feet.getY() >= level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockPos floor = feet.below();
        BlockPos head = feet.above();
        AABB npcBounds = new AABB(
            feet.getX() + 0.15D, feet.getY(), feet.getZ() + 0.15D,
            feet.getX() + 0.85D, feet.getY() + 1.9D, feet.getZ() + 0.85D
        );
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
            && level.getFluidState(floor).isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getFluidState(feet).isEmpty()
            && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
            && level.getFluidState(head).isEmpty()
            && level.noCollision(npcBounds);
    }

    private static boolean seatNpc(ServerLevel level, Entity npc, BlockPos position) {
        ArmorStand seat = EntityType.ARMOR_STAND.create(level);
        if (seat == null) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        net.minecraft.core.Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
            : net.minecraft.core.Direction.NORTH;
        float yaw = facing.toYRot();
        seat.setInvisible(true);
        seat.setInvulnerable(true);
        seat.setNoGravity(true);
        seat.setSilent(true);
        CompoundTag seatData = new CompoundTag();
        seat.saveWithoutId(seatData);
        seatData.putBoolean("Marker", true);
        seat.load(seatData);
        seat.addTag("cobbleventure_npc_seat");
        seat.moveTo(
            position.getX() + 0.5D, position.getY() + 0.45D,
            position.getZ() + 0.5D, yaw, 0.0F
        );
        if (!level.addFreshEntity(seat)) {
            return false;
        }
        npc.setYRot(yaw);
        if (!npc.startRiding(seat, true)) {
            seat.discard();
            return false;
        }
        applySittingPose(level, npc, position, yaw);
        return true;
    }

    private static void applySittingPose(
        ServerLevel level, Entity npc, BlockPos position, float yaw
    ) {
        String command = "easy_npc pose set easy_npc:pose/humanoid/chair_sitting "
            + npc.getUUID();
        try {
            int result = level.getServer().getCommands().getDispatcher().execute(
                command,
                level.getServer().createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(Vec3.atCenterOf(position))
                    .withRotation(new net.minecraft.world.phys.Vec2(0.0F, yaw))
                    .withPermission(4)
                    .withSuppressedOutput()
            );
            if (result == 0) {
                LOGGER.warn("EasyNPC sitting pose command returned no result: npc={}", npc.getUUID());
            }
        } catch (CommandSyntaxException error) {
            LOGGER.warn("EasyNPC sitting pose could not be applied: npc={}", npc.getUUID(), error);
        }
    }

    private static void registerDoor(ServerLevel level, BlockPos lower, DoorTarget target) {
        if (!(level.getBlockState(lower).getBlock() instanceof DoorBlock)) {
            LOGGER.warn(
                "Configured building route has no authored door block: dimension={}, position={}",
                level.dimension().location(), lower
            );
            return;
        }
        registerDoorBlocks(level, lower, target);
        BlockPos paired = pairedDoorPosition(level, lower);
        if (paired != null) {
            registerDoorBlocks(level, paired, target);
        }
    }

    private static void registerDoorBlocks(ServerLevel level, BlockPos lower, DoorTarget target) {
        DOORS.put(new DoorKey(level.dimension(), lower.immutable()), target);
        DOORS.put(new DoorKey(level.dimension(), lower.above().immutable()), target);
    }

    private static BlockPos pairedDoorPosition(ServerLevel level, BlockPos lower) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return null;
        }
        net.minecraft.core.Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        for (net.minecraft.core.Direction side
            : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
            BlockPos candidate = lower.relative(side);
            BlockState other = level.getBlockState(candidate);
            if (other.getBlock() == state.getBlock()
                && other.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && other.getValue(DoorBlock.FACING) == facing
                && other.getValue(DoorBlock.HINGE) != hinge) {
                return candidate;
            }
        }
        return null;
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof HealingMachineBlockEntity) {
            // Pokemon Center healing is a nurse service. Consume the block interaction
            // on both logical sides so Cobblemon cannot start its normal direct-use
            // flow or briefly show a client-side activation before the server rejects it.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (!event.getLevel().isClientSide()
                && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && event.getEntity() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable(
                    "message.cobbleventure_bootstrap.pokemon_center_nurse_required"
                ));
            }
            return;
        }
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof DisplayCaseBlockEntity displayCase
            && !displayCase.getStack().isEmpty()) {
            // A filled display case is part of the authored scenery. Cobblemon swaps or
            // returns its item on any further click, so consume the interaction on both
            // client and server before the block can hand that item to the player.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (StructurePlacementFixes.isAuthoredStorage(
            event.getLevel().getBlockEntity(event.getPos())
        )) {
            // Chests, barrels and modded furniture inventories copied from map
            // templates are scenery. Player-placed storage has no marker and remains usable.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DoorTarget target = doorTarget(player.serverLevel(), event.getPos());
        if (target == null) {
            if (event.getLevel().getBlockState(event.getPos()).getBlock() instanceof DoorBlock) {
                LOGGER.warn(
                    "Unregistered building door clicked: dimension={}, position={}, state={}, nearest={}",
                    player.level().dimension().location(), event.getPos(),
                    event.getLevel().getBlockState(event.getPos()),
                    nearestRegisteredDoor(player.serverLevel(), event.getPos())
                );
            }
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        long gameTime = player.level().getGameTime();
        if (player.getPersistentData().getLong(INTERACTION_COOLDOWN) > gameTime) {
            return;
        }
        player.getPersistentData().putLong(INTERACTION_COOLDOWN, gameTime + 10L);
        if (!target.allows(player)) {
            sendDialogue(player, target.lockedDialogue);
            return;
        }
        sendDialogue(player, target.enterDialogue);
        ServerLevel destination = player.getServer().getLevel(target.dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal("[건물 문] 이동할 공간을 찾을 수 없습니다."));
            return;
        }
        destination.getChunkAt(target.position);
        if (target.interior && !hasSafeSpawnSupport(destination, target.position)) {
            LOGGER.error(
                "Building teleport blocked because the prepared interior is absent: "
                    + "dimension={}, destination={}",
                target.dimension.location(), target.position
            );
            player.sendSystemMessage(Component.literal(
                "[건물 문] 내부 공간이 아직 준비되지 않아 이동을 중단했습니다."
            ));
            return;
        }
        ResourceKey<Level> sourceDimension = player.level().dimension();
        player.teleportTo(
            destination,
            target.position.getX() + 0.5D, target.position.getY(), target.position.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
        DoorTransitionSound.afterTeleport(player, sourceDimension, target.position);
        if (target.interior) MusicPlayback.enterInterior(player, target.musicTrack);
        else MusicPlayback.leaveInterior(player);
    }

    private static DoorTarget doorTarget(ServerLevel level, BlockPos clicked) {
        DoorTarget direct = DOORS.get(new DoorKey(level.dimension(), clicked));
        if (direct != null) {
            return direct;
        }
        BlockState clickedState = level.getBlockState(clicked);
        if (!(clickedState.getBlock() instanceof DoorBlock)) {
            return null;
        }
        BlockPos lower = clickedState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
            ? clicked.below() : clicked;
        DoorTarget lowerTarget = DOORS.get(new DoorKey(level.dimension(), lower));
        if (lowerTarget != null) {
            return lowerTarget;
        }
        net.minecraft.core.Direction facing = level.getBlockState(lower).getValue(DoorBlock.FACING);
        DoorHingeSide hinge = level.getBlockState(lower).getValue(DoorBlock.HINGE);
        for (net.minecraft.core.Direction side
            : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
            BlockPos candidate = lower.relative(side);
            BlockState other = level.getBlockState(candidate);
            if (other.getBlock() != clickedState.getBlock()
                || other.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                || other.getValue(DoorBlock.FACING) != facing
                || other.getValue(DoorBlock.HINGE) == hinge) {
                continue;
            }
            DoorTarget paired = DOORS.get(new DoorKey(level.dimension(), candidate));
            if (paired != null) {
                return paired;
            }
        }
        return null;
    }

    private static String nearestRegisteredDoor(ServerLevel level, BlockPos clicked) {
        DoorKey nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (DoorKey key : DOORS.keySet()) {
            if (!key.dimension.equals(level.dimension())) {
                continue;
            }
            double distance = key.position.distSqr(clicked);
            if (distance < nearestDistance) {
                nearest = key;
                nearestDistance = distance;
            }
        }
        return nearest == null ? "none"
            : nearest.position + " (distanceSquared=" + nearestDistance + ")";
    }

    private static void sendDialogue(ServerPlayer player, List<String> lines) {
        for (String line : lines) {
            player.sendSystemMessage(Component.literal("[건물 문] " + line));
        }
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

    private static net.minecraft.core.Direction direction(String value) {
        return switch (value) {
            case "east" -> net.minecraft.core.Direction.EAST;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "west" -> net.minecraft.core.Direction.WEST;
            default -> net.minecraft.core.Direction.NORTH;
        };
    }

    private static BlockPos instanceOrigin(
        RuntimeData data, String key, boolean compact
    ) {
        Integer compactSlot = data.existingInstanceSlot("compact|" + key);
        Integer largeSlot = data.existingInstanceSlot("large|" + key);
        if (compactSlot != null) {
            return allocatedInstanceOrigin(compactSlot, true);
        }
        if (largeSlot != null) {
            return allocatedInstanceOrigin(largeSlot, false);
        }
        int slot = data.instanceSlot((compact ? "compact|" : "large|") + key, key);
        if (slot >= 0) {
            return allocatedInstanceOrigin(slot, compact);
        }
        return legacyInstanceOrigin(key);
    }

    private static BlockPos allocatedInstanceOrigin(int slot, boolean compact) {
        int x = Math.floorMod(slot, SLOTS_PER_ROW);
        int z = Math.floorDiv(slot, SLOTS_PER_ROW);
        int spacing = compact ? COMPACT_SLOT_SPACING : LARGE_SLOT_SPACING;
        int zDirection = compact ? 1 : -1;
        return new BlockPos(
            x * spacing + SLOT_MARGIN,
            SLOT_Y,
            (z + (compact ? 0 : 1)) * spacing * zDirection + SLOT_MARGIN
        );
    }

    private static BlockPos legacyInstanceOrigin(String key) {
        int hash = key.hashCode();
        int x = Math.floorMod(hash, 4096) - 2048;
        int z = Math.floorMod(Integer.rotateLeft(hash, 13), 4096) - 2048;
        return new BlockPos(x * LARGE_SLOT_SPACING, SLOT_Y, z * LARGE_SLOT_SPACING);
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

    private static boolean hasAuthoredInteriorSupport(
        ServerLevel level, BlockPos origin, StructureMetadata metadata
    ) {
        for (Anchor anchor : metadata.anchors) {
            if (anchor.safeSpawn == null) {
                continue;
            }
            if (!hasSafeSpawnSupport(level, transform(origin, anchor.safeSpawn, Rotation.NONE))) {
                return false;
            }
        }
        // Older interior metadata without safe_spawn cannot be verified without inventing
        // a coordinate. Preserve its existing prepared-state behavior instead.
        return true;
    }

    private static boolean hasSafeSpawnSupport(ServerLevel level, BlockPos safeSpawn) {
        return !level.getBlockState(safeSpawn.below()).isAir();
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

    static List<RadarLocationCatalog.Location> radarLocations(
        MinecraftServer server, ResourceLocation dimension
    ) {
        List<RadarLocationCatalog.Location> result = new ArrayList<>();
        for (PersistedBuildingInstance instance : data(server).buildingInstances()) {
            if (!instance.dimension.equals(dimension.toString())) continue;
            if (instance.rotation == null || instance.rotation.isBlank()) continue;
            BlockPos offset = exteriorDoorApproachOffset(
                instance.structure, instance.rotation
            );
            if (offset == null) continue;
            BlockPos entrance = instance.origin.offset(offset);
            String id = "building/" + instance.structure + "/"
                + entrance.getX() + "/" + entrance.getY() + "/" + entrance.getZ();
            result.add(new RadarLocationCatalog.Location(
                id,
                RadarLocationCatalog.buildingKind(instance.structure),
                dimension,
                entrance.getX() + 0.5D,
                entrance.getY(),
                entrance.getZ() + 0.5D,
                instance.structure,
                instance.eventSpaceId == null ? "" : instance.eventSpaceId
            ));
        }
        return List.copyOf(result);
    }

    private static RuntimeData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RuntimeData::new, RuntimeData::load), DATA_FILE
        );
    }

    private record Anchor(
        String id, String type, BlockPos position, BlockPos safeSpawn,
        net.minecraft.core.Direction facing, boolean sealOpening
    ) {
    }

    private record StructureMetadata(List<Anchor> anchors, String interiorStructure) {
        Anchor first(String type) {
            return anchors.stream().filter(anchor -> anchor.type.equals(type)).findFirst().orElse(null);
        }

        Anchor namedDoor(String id) {
            return anchors.stream().filter(anchor -> anchor.id.equals(id)
                && anchor.type.equals("door"))
                .findFirst().orElse(null);
        }

        Anchor namedNpc(String id) {
            return anchors.stream().filter(anchor -> anchor.id.equals(id)
                && anchor.type.equals("npc_position"))
                .findFirst().orElse(null);
        }

    }

    private record InteriorSetting(String key, String structure) {
    }

    private record RouteTarget(
        String space, String door, String conditionMode,
        List<PlayerConditions.Condition> conditions,
        List<String> lockedDialogue, List<String> enterDialogue
    ) {
    }

    private record SpaceInstance(
        ServerLevel level, BlockPos origin, Rotation rotation, StructureMetadata metadata,
        Vec3i size
    ) {
        boolean contains(BlockPos position) {
            return BuildingEventSpaceBounds.contains(origin, size, position);
        }
    }

    private record EventSpaceInstance(
        String instanceKey, Map<String, SpaceInstance> spaces
    ) {
    }

    record SpawnDestination(ServerLevel level, BlockPos position, float yaw) {
    }

    private record BuildingSettings(
        int placementYOffset, boolean noInteriorSpace,
        Map<String, String> fixedNpcs, Map<String, String> fixedPokemon,
        Map<String, String> fixedVendors, Map<String, String> fixedGachaMachines,
        boolean citizenPlacementAllowed,
        List<InteriorSetting> interiors, Map<String, RouteTarget> routes,
        String musicTrack
    ) {
    }

    private record DoorKey(ResourceKey<Level> dimension, BlockPos position) {
    }

    private record DoorTarget(
        ResourceKey<Level> dimension, BlockPos position,
        List<PlayerConditions.Condition> conditions, String conditionMode,
        List<String> lockedDialogue, List<String> enterDialogue,
        boolean interior, String musicTrack
    ) {
        boolean allows(ServerPlayer player) {
            if (conditions.isEmpty()) {
                return true;
            }
            return PlayerConditions.matches(player, conditionMode, conditions);
        }
    }

    private record PersistedBuildingInstance(
        String dimension, String structure, BlockPos origin, String rotation,
        String eventSpaceId
    ) {
    }

    private record PendingNpcSeat(
        ResourceKey<Level> dimension, BlockPos position, String npcId,
        float yaw, boolean seated, int attempts
    ) {
    }

    static final class RuntimeData extends SavedData {
        private final Set<String> spawned = new HashSet<>();
        private final Set<String> prepared = new HashSet<>();
        private final Map<String, Integer> instanceSlots = new LinkedHashMap<>();
        private final Map<String, PersistedBuildingInstance> buildingInstances =
            new LinkedHashMap<>();
        private int nextInstanceSlot;

        static RuntimeData load(CompoundTag tag, HolderLookup.Provider registries) {
            RuntimeData data = new RuntimeData();
            readSet(tag.getString("spawned"), data.spawned);
            readSet(tag.getString("prepared"), data.prepared);
            readSlots(tag.getString("instanceSlots"), data.instanceSlots);
            readBuildingInstances(
                tag.getString("buildingInstances"), data.buildingInstances
            );
            data.recoverLegacyBuildingInstances();
            data.nextInstanceSlot = data.instanceSlots.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
            return data;
        }

        int instanceSlot(String allocationKey, String legacyInstanceKey) {
            Integer existing = instanceSlots.get(allocationKey);
            if (existing != null) {
                return existing;
            }
            String preparedPrefix = legacyInstanceKey + "|";
            if (prepared.stream().anyMatch(value -> value.startsWith(preparedPrefix))) {
                return -1;
            }
            int allocated = nextInstanceSlot++;
            instanceSlots.put(allocationKey, allocated);
            setDirty();
            return allocated;
        }

        Integer existingInstanceSlot(String allocationKey) {
            return instanceSlots.get(allocationKey);
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

        void rememberBuildingInstance(
            String dimension, String structure, BlockPos origin, String rotation,
            String eventSpaceId
        ) {
            PersistedBuildingInstance instance = new PersistedBuildingInstance(
                dimension, structure, origin.immutable(), rotation, eventSpaceId
            );
            String key = instanceKey(instance);
            PersistedBuildingInstance existing = buildingInstances.get(key);
            if (existing == null
                || ((existing.rotation == null || existing.rotation.isBlank())
                    && rotation != null && !rotation.isBlank())
                || ((existing.eventSpaceId == null || existing.eventSpaceId.isBlank())
                    && eventSpaceId != null && !eventSpaceId.isBlank())) {
                buildingInstances.put(key, instance);
                setDirty();
            }
        }

        List<PersistedBuildingInstance> buildingInstances() {
            return List.copyOf(buildingInstances.values());
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putString("spawned", String.join("\n", spawned));
            tag.putString("prepared", String.join("\n", prepared));
            StringBuilder serializedSlots = new StringBuilder();
            for (Map.Entry<String, Integer> entry : instanceSlots.entrySet()) {
                if (!serializedSlots.isEmpty()) {
                    serializedSlots.append('\n');
                }
                serializedSlots.append(entry.getValue()).append('\t').append(entry.getKey());
            }
            tag.putString("instanceSlots", serializedSlots.toString());
            StringBuilder serializedBuildings = new StringBuilder();
            for (PersistedBuildingInstance instance : buildingInstances.values()) {
                if (!serializedBuildings.isEmpty()) {
                    serializedBuildings.append('\n');
                }
                serializedBuildings.append(instance.dimension).append('\t')
                    .append(instance.structure).append('\t')
                    .append(instance.origin.getX()).append('\t')
                    .append(instance.origin.getY()).append('\t')
                    .append(instance.origin.getZ()).append('\t')
                    .append(instance.rotation == null ? "" : instance.rotation).append('\t')
                    .append(instance.eventSpaceId == null ? "" : instance.eventSpaceId);
            }
            tag.putString("buildingInstances", serializedBuildings.toString());
            return tag;
        }

        private void recoverLegacyBuildingInstances() {
            for (String preparedKey : prepared) {
                int marker = preparedKey.indexOf("|space|");
                if (marker <= 0) {
                    continue;
                }
                PersistedBuildingInstance instance = parseLegacyInstanceKey(
                    preparedKey.substring(0, marker)
                );
                if (instance != null) {
                    buildingInstances.putIfAbsent(instanceKey(instance), instance);
                }
            }
        }

        private static PersistedBuildingInstance parseLegacyInstanceKey(String key) {
            String[] fields = key.split("\\|", 3);
            if (fields.length != 3) {
                return null;
            }
            String[] coordinates = fields[2].split(",", 3);
            if (coordinates.length != 3) {
                return null;
            }
            try {
                return new PersistedBuildingInstance(
                    fields[0], fields[1],
                    new BlockPos(
                        Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]),
                        Integer.parseInt(coordinates[2])
                    ),
                    null, null
                );
            } catch (NumberFormatException ignored) {
                LOGGER.warn("Ignoring invalid legacy building runtime instance: {}", key);
                return null;
            }
        }

        private static String instanceKey(PersistedBuildingInstance instance) {
            return instance.dimension + "|" + instance.structure + "|"
                + instance.origin.getX() + "," + instance.origin.getY() + ","
                + instance.origin.getZ();
        }

        private static void readSet(String serialized, Set<String> target) {
            if (!serialized.isBlank()) {
                target.addAll(List.of(serialized.split("\\n")));
            }
        }

        private static void readSlots(
            String serialized, Map<String, Integer> target
        ) {
            if (serialized.isBlank()) {
                return;
            }
            for (String line : serialized.split("\\n")) {
                int separator = line.indexOf('\t');
                if (separator <= 0 || separator == line.length() - 1) {
                    continue;
                }
                try {
                    int slot = Integer.parseInt(line.substring(0, separator));
                    if (slot >= 0) {
                        target.put(line.substring(separator + 1), slot);
                    }
                } catch (NumberFormatException ignored) {
                    LOGGER.warn("Ignoring invalid building interior slot: {}", line);
                }
            }
        }

        private static void readBuildingInstances(
            String serialized, Map<String, PersistedBuildingInstance> target
        ) {
            if (serialized.isBlank()) {
                return;
            }
            for (String line : serialized.split("\\n")) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 6 && fields.length != 7) {
                    LOGGER.warn("Ignoring invalid building runtime instance: {}", line);
                    continue;
                }
                try {
                    PersistedBuildingInstance instance = new PersistedBuildingInstance(
                        fields[0], fields[1],
                        new BlockPos(
                            Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4])
                        ),
                        fields[5], fields.length == 7 && !fields[6].isBlank() ? fields[6] : null
                    );
                    target.put(instanceKey(instance), instance);
                } catch (NumberFormatException ignored) {
                    LOGGER.warn("Ignoring invalid building runtime instance: {}", line);
                }
            }
        }
    }
}
