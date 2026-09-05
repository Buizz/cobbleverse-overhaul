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
import dev.buizz.cobbleventure.adventure.AdventureWorldContext;
import dev.buizz.cobbleventure.adventure.PokemonCenterHealingService;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventMovementFailureReason;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import dev.buizz.cobbleventure.playermenu.MusicPlayback;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
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
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.JigsawBlock;
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
    private static final String DAYCARE_STRUCTURE = "cobbleventure:placeholder/daycare";
    private static final String DAYCARE_NPC_TAG =
        "cobbleventure_npc/cobbleventure/npc/facilities/daycare_attendant";
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
    private static final Map<String, java.util.Optional<BlockPos>> RADAR_OFFSETS = new HashMap<>();
    private static final Map<String, BuildingSettings> SETTINGS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();
    private static final List<TransitionTarget> TRANSITIONS = new ArrayList<>();
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

    static AdventureWorldContext.FacilityPosition daycarePaddock(
        ServerPlayer player
    ) {
        for (EventSpaceInstance eventSpace : EVENT_SPACES.values()) {
            SpaceInstance exterior = eventSpace.spaces.get("exterior");
            if (exterior == null
                || !exterior.structure.equals("cobbleventure:placeholder/daycare")) {
                continue;
            }
            boolean inside = eventSpace.spaces.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("exterior"))
                .map(Map.Entry::getValue)
                .anyMatch(space -> space.level == player.serverLevel()
                    && space.contains(player.blockPosition()));
            if (!inside) {
                continue;
            }
            for (Anchor anchor : exterior.metadata.anchors) {
                if (anchor.id.equals("paddock")) {
                    return new AdventureWorldContext.FacilityPosition(
                        exterior.level.dimension().location(),
                        transform(exterior.origin, anchor.position, exterior.rotation)
                    );
                }
            }
        }
        return null;
    }

    static void initialize(MinecraftServer server) {
        METADATA.clear();
        RADAR_OFFSETS.clear();
        SETTINGS.clear();
        DOORS.clear();
        TRANSITIONS.clear();
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
        if (structure.equals(DAYCARE_STRUCTURE)) {
            removeLegacyExteriorDaycareAttendant(
                level, metadata, origin.toBlockPos(), rotation
            );
        }
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
        registerDungeonEntrances(level, structure, metadata, origin.toBlockPos(), rotation);
        // Open, same-dimension facilities have no portal routes, but their actual
        // placement must still survive restarts and be available to the radar.
        if (settings == null || settings.noInteriorSpace || settings.routes.isEmpty()) {
            data(level.getServer()).rememberBuildingInstance(
                level.dimension().location().toString(), structure,
                origin.toBlockPos(), rotationName, eventSpaceId
            );
        }
        if (settings != null && settings.noInteriorSpace) {
            return;
        }
        if (settings != null && !settings.routes.isEmpty()) {
            if (!hasExteriorRouteTrigger(level, metadata, origin.toBlockPos(), rotation, settings)) {
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

    private static void removeLegacyExteriorDaycareAttendant(
        ServerLevel level, StructureMetadata metadata,
        BlockPos origin, Rotation rotation
    ) {
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.id.equals("paddock")) {
                continue;
            }
            BlockPos position = transform(origin, anchor.position, rotation);
            AABB area = new AABB(position).inflate(2.0D);
            for (Entity entity : level.getEntities((Entity) null, area)) {
                if (entity.getTags().contains(DAYCARE_NPC_TAG)) {
                    entity.discard();
                    LOGGER.info(
                        "Removed legacy exterior daycare attendant: uuid={}, position={}",
                        entity.getUUID(), position
                    );
                }
            }
            return;
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

    /**
     * Returns the already placed exterior for a stable authored event-space ID.
     *
     * <p>Facility positions must not be recalculated from a heightmap when another player
     * joins. Native chunks can briefly report an unprepared height during login, while the
     * persisted building instance remains the authoritative placement.</p>
     */
    static PlacedBuilding resolvePlacedBuilding(
        MinecraftServer server, ResourceKey<Level> dimension,
        String structure, String eventSpaceId
    ) {
        if (server == null || dimension == null || structure == null
            || eventSpaceId == null || eventSpaceId.isBlank()) {
            return null;
        }
        String dimensionId = dimension.location().toString();
        return data(server).buildingInstances().stream()
            .filter(instance -> instance.dimension.equals(dimensionId))
            .filter(instance -> instance.structure.equals(structure))
            .filter(instance -> eventSpaceId.equals(instance.eventSpaceId))
            // A legacy bad login could have persisted a second instance at Y=-1. The
            // generated structure is the higher, actually placed candidate.
            .max(java.util.Comparator.comparingInt(instance -> instance.origin.getY()))
            .map(instance -> new PlacedBuilding(
                new CobbleventureBootstrap.BlockPoint(
                    instance.origin.getX(), instance.origin.getY(), instance.origin.getZ()
                ),
                instance.rotation
            ))
            .orElse(null);
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
                    value.has("seal_entry") && value.get("seal_entry").getAsBoolean(),
                    value.has("entrance_id")
                        ? requiredString(value, "entrance_id") : null
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
                RadarSetting radar = null;
                if (value.has("radar")) {
                    JsonObject configuredRadar = value.getAsJsonObject("radar");
                    radar = new RadarSetting(
                        configuredRadar.has("enabled")
                            && configuredRadar.get("enabled").getAsBoolean(),
                        configuredRadar.has("category")
                            ? configuredRadar.get("category").getAsString() : "SPECIAL_BUILDING",
                        configuredRadar.has("label")
                            ? configuredRadar.get("label").getAsString() : entry.getKey(),
                        configuredRadar.has("anchor")
                            ? configuredRadar.get("anchor").getAsString() : ""
                    );
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
                    value.has("music_track") ? value.get("music_track").getAsString() : null,
                    radar
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

    static BlockPos exteriorNpcApproachOffset(
        String structure, String rotationName, String npcSlot, int distance
    ) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) {
            return null;
        }
        Anchor npc = metadata.namedNpc(npcSlot);
        if (npc == null || npc.position == null || npc.facing == null) {
            return null;
        }
        return rotatedNpcApproachOffset(
            npc.position, npc.facing, Math.max(1, distance), rotationName
        );
    }

    static BlockPos rotatedNpcApproachOffset(
        BlockPos npcPosition, Direction npcFacing, int distance, String rotationName
    ) {
        BlockPos approach = npcPosition.relative(npcFacing, Math.max(1, distance));
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

    static Direction exteriorRoadAnchorOutsideDirection(
        ServerLevel level, String structure, String rotationName
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return null;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return null;
        List<StructureTemplate.StructureBlockInfo> anchors = template.orElseThrow()
            .filterBlocks(
                BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW
            ).stream()
            .filter(marker -> marker.nbt() != null
                && "cobbleventure:road_anchor".equals(marker.nbt().getString("name")))
            .toList();
        if (anchors.size() != 1) return null;
        Direction authored = JigsawBlock.getFrontFacing(anchors.getFirst().state());
        return rotation(rotationName).rotate(authored);
    }

    static BlockPos exteriorDoorOffset(String structure, String rotationName) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) return null;
        Anchor door = metadata.first("door");
        if (door == null || door.position == null) return null;
        return StructureTemplate.transform(
            door.position, Mirror.NONE, rotation(rotationName), BlockPos.ZERO
        );
    }

    static Direction exteriorDoorOutsideDirection(
        String structure, String rotationName
    ) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) return null;
        Anchor door = metadata.first("door");
        if (door == null || door.facing == null) return null;
        // Door metadata stores the direction into the room. The road belongs
        // on the safe/outside side, which is the opposite direction.
        return rotation(rotationName).rotate(door.facing.getOpposite());
    }

    static String musicTrack(String structure) {
        BuildingSettings settings = settingsForStructure(structure);
        return settings == null ? null : settings.musicTrack;
    }

    static boolean isInteriorDimension(ServerLevel level) {
        return level.dimension().equals(INTERIORS);
    }

    static String interiorMusicTrackAt(ServerPlayer player) {
        if (!isInteriorDimension(player.serverLevel())) {
            return null;
        }
        BlockPos position = player.blockPosition();
        for (EventSpaceInstance registration : EVENT_SPACES.values()) {
            for (SpaceInstance space : registration.spaces.values()) {
                if (space.level.dimension().equals(INTERIORS) && space.contains(position)) {
                    String track = musicTrack(space.structure);
                    if (track != null && !track.isBlank()) {
                        return track;
                    }
                }
            }
        }
        return null;
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

    private static boolean hasExteriorRouteTrigger(
        ServerLevel level, StructureMetadata metadata, BlockPos origin,
        Rotation rotation, BuildingSettings settings
    ) {
        boolean hasExteriorRoute = false;
        for (String route : settings.routes.keySet()) {
            if (!route.startsWith("exterior:")) {
                continue;
            }
            hasExteriorRoute = true;
            Anchor anchor = metadata.namedConnection(route.substring("exterior:".length()));
            if (anchor != null) {
                BlockState state = level.getBlockState(
                    transform(origin, anchor.position, rotation)
                );
                if ((anchor.type.equals("door") && state.getBlock() instanceof DoorBlock)
                    || (anchor.type.equals("transition") && state.is(Blocks.BARRIER))) {
                    return true;
                }
            }
        }
        return !hasExteriorRoute;
    }

    private static int restorePersistedBuildingInstances(MinecraftServer server) {
        int restored = 0;
        List<PersistedBuildingInstance> persisted = data(server).buildingInstances();
        for (PersistedBuildingInstance instance : persisted) {
            if (!isAuthoritativeEventSpaceInstance(instance, persisted)) {
                LOGGER.warn(
                    "Ignoring superseded building instance for event space {}: origin={}",
                    instance.eventSpaceId, instance.origin
                );
                continue;
            }
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
                || !hasExteriorRouteTrigger(
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

    private static boolean isAuthoritativeEventSpaceInstance(
        PersistedBuildingInstance candidate, List<PersistedBuildingInstance> instances
    ) {
        if (candidate.eventSpaceId == null || candidate.eventSpaceId.isBlank()) return true;
        PersistedBuildingInstance selected = instances.stream()
            .filter(instance -> candidate.dimension.equals(instance.dimension))
            .filter(instance -> candidate.structure.equals(instance.structure))
            .filter(instance -> candidate.eventSpaceId.equals(instance.eventSpaceId))
            .max(java.util.Comparator
                .comparingInt((PersistedBuildingInstance instance) -> instance.origin.getY())
                .thenComparingInt(instance -> instance.origin.getX())
                .thenComparingInt(instance -> instance.origin.getZ()))
            .orElse(candidate);
        return candidate.equals(selected);
    }

    private static String detectExteriorRotation(
        ServerLevel level, BlockPos origin, StructureMetadata metadata,
        BuildingSettings settings
    ) {
        for (String rotationName : List.of(
            "none", "clockwise_90", "clockwise_180", "counterclockwise_90"
        )) {
            if (hasExteriorRouteTrigger(
                level, metadata, origin, rotation(rotationName), settings
            )) {
                return rotationName;
            }
        }
        return null;
    }

    private static void registerDungeonEntrances(
        ServerLevel level, String structure, StructureMetadata metadata,
        BlockPos origin, Rotation rotation
    ) {
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("dungeon_entrance")
                || anchor.dungeonEntrance == null) {
                continue;
            }
            BlockPos trigger = transform(origin, anchor.position, rotation);
            BlockPos safeReturn = anchor.safeSpawn == null
                ? trigger.relative(rotation.rotate(anchor.facing).getOpposite())
                : transform(origin, anchor.safeSpawn, rotation);
            Set<BlockPos> triggerBlocks = connectedBarrierBlocks(
                level, structure, origin, rotation, anchor
            );
            DungeonSystem.registerBuildingPlacement(
                level, anchor.dungeonEntrance, trigger,
                triggerBlocks.isEmpty() ? Set.of(trigger) : triggerBlocks,
                safeReturn, triggerBlocks.isEmpty() ? 9.0D : 6.25D
            );
        }
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
            exterior, exteriorOrigin, exteriorRotation, exteriorMetadata,
            exteriorStructure, null
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
                ExplicitAirPlacementProcessor.configure(
                    template.orElseThrow(), placementSettings
                );
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
                interior.structure, template.orElseThrow().getSize()
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
            registerDungeonEntrances(
                interiorsLevel, interior.structure, metadata, origin, Rotation.NONE
            );
            index++;
        }

        boolean isDaycare = exteriorStructure.equals(DAYCARE_STRUCTURE);
        if ((eventSpaceId != null && !eventSpaceId.isBlank()) || isDaycare) {
            String registrationKey = eventSpaceId == null || eventSpaceId.isBlank()
                ? "__daycare_instance__|" + instanceKey
                : eventSpaceId;
            EventSpaceInstance existing = EVENT_SPACES.get(registrationKey);
            if (existing != null && !existing.instanceKey.equals(instanceKey)) {
                throw new IllegalStateException(
                    "Duplicate building event space ID: " + registrationKey
                );
            }
            EVENT_SPACES.put(
                registrationKey, new EventSpaceInstance(instanceKey, Map.copyOf(spaces))
            );
        }

        for (Map.Entry<String, RouteTarget> route : settings.routes.entrySet()) {
            int separator = route.getKey().indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String sourceSpaceKey = route.getKey().substring(0, separator);
            String sourceAnchorId = route.getKey().substring(separator + 1);
            SpaceInstance sourceSpace = spaces.get(sourceSpaceKey);
            SpaceInstance targetSpace = spaces.get(route.getValue().space);
            if (sourceSpace == null || targetSpace == null) {
                LOGGER.warn("Building route references an unavailable space: {}", route.getKey());
                continue;
            }
            Anchor sourceAnchor = sourceSpace.metadata.namedConnection(sourceAnchorId);
            Anchor targetAnchor = targetSpace.metadata.namedConnection(route.getValue().door);
            if (sourceAnchor == null || targetAnchor == null) {
                LOGGER.warn("Building route references a missing door or transition: {}", route.getKey());
                continue;
            }
            BlockPos destination = transform(
                targetSpace.origin,
                safeDestination(targetAnchor),
                targetSpace.rotation
            );
            BlockPos reverseDestination = transform(
                sourceSpace.origin,
                safeDestination(sourceAnchor),
                sourceSpace.rotation
            );
            registerConnectionTrigger(
                sourceSpace, sourceAnchor,
                new DoorTarget(
                    targetSpace.level.dimension(), destination,
                    route.getValue().conditions, route.getValue().conditionMode,
                    route.getValue().lockedDialogue, route.getValue().enterDialogue,
                    !route.getValue().space.equals("exterior"), settings.musicTrack
                )
            );
            registerConnectionTrigger(
                targetSpace, targetAnchor,
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
            if (!BuildingEventSpaceIds.isPublic(eventSpace.getKey())) continue;
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
        if (CobbleventureBootstrap.npcPair(level, npcId) != null) {
            return CobbleventureBootstrap.spawnRegionalNpc(level, npcId, position, yaw, "interact");
        }
        if (!isNpcSeatBlock(level.getBlockState(position))
            && !canNpcStandAt(level, position)) {
            LOGGER.warn(
                "Building NPC spawn position is obstructed: npc={}, position={}",
                npcId, position
            );
            return false;
        }
        if (CobbleventureBootstrap.spawnEntityNpcRepresentation(
            level, npcId, position, yaw, "interact"
        )) {
            return true;
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
        tickTransitions(event.getServer());
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

    private static void tickTransitions(MinecraftServer server) {
        if (TRANSITIONS.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            long gameTime = player.level().getGameTime();
            if (player.getPersistentData().getLong(INTERACTION_COOLDOWN) > gameTime) {
                continue;
            }
            AABB playerBounds = player.getBoundingBox().inflate(0.08D);
            TransitionTarget transition = TRANSITIONS.stream()
                .filter(candidate -> candidate.dimension.equals(player.level().dimension()))
                .filter(candidate -> candidate.blocks.stream().anyMatch(
                    position -> playerBounds.intersects(new AABB(position))
                ))
                .findFirst().orElse(null);
            if (transition == null) continue;
            activateTarget(player, transition.target, 10L);
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

    private static BlockPos safeDestination(Anchor anchor) {
        if (anchor.safeSpawn != null) return anchor.safeSpawn;
        return anchor.type.equals("transition")
            ? anchor.position.relative(anchor.facing) : anchor.position;
    }

    private static void registerConnectionTrigger(
        SpaceInstance space, Anchor anchor, DoorTarget target
    ) {
        BlockPos position = transform(space.origin, anchor.position, space.rotation);
        if (anchor.type.equals("door")) {
            registerDoor(space.level, position, target);
            return;
        }
        Set<BlockPos> blocks = transitionBlocks(space, anchor);
        if (blocks.isEmpty()) {
            LOGGER.warn(
                "Configured building transition has no connected barrier blocks: "
                    + "structure={}, anchor={}, position={}",
                space.structure, anchor.id, position
            );
            return;
        }
        Set<BlockPos> immutableBlocks = Set.copyOf(blocks);
        TRANSITIONS.removeIf(existing -> existing.dimension.equals(space.level.dimension())
            && existing.blocks.equals(immutableBlocks));
        TRANSITIONS.add(new TransitionTarget(
            space.level.dimension(), immutableBlocks, target
        ));
    }

    private static Set<BlockPos> transitionBlocks(
        SpaceInstance space, Anchor anchor
    ) {
        return connectedBarrierBlocks(
            space.level, space.structure, space.origin, space.rotation, anchor
        );
    }

    private static Set<BlockPos> connectedBarrierBlocks(
        ServerLevel level, String structure, BlockPos origin,
        Rotation rotation, Anchor anchor
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        var template = structureId == null
            ? java.util.Optional.<StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (template.isEmpty()) return Set.of();
        Set<BlockPos> barriers = template.orElseThrow().filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(), Blocks.BARRIER
        ).stream().map(StructureTemplate.StructureBlockInfo::pos)
            .collect(java.util.stream.Collectors.toSet());
        if (!barriers.contains(anchor.position)) return Set.of();
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(anchor.position);
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            if (!barriers.contains(current) || !connected.add(current)) continue;
            if (connected.size() > 4096) {
                LOGGER.error(
                    "Building transition barrier region is too large: structure={}, anchor={}",
                    structure, anchor.id
                );
                return Set.of();
            }
            for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.values()) {
                pending.add(current.relative(direction));
            }
        }
        return connected.stream().map(local -> transform(
            origin, local, rotation
        )).collect(java.util.stream.Collectors.toUnmodifiableSet());
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
        activateTarget(player, target, 10L);
    }

    private static void activateTarget(
        ServerPlayer player, DoorTarget target, long cooldownTicks
    ) {
        long gameTime = player.level().getGameTime();
        if (player.getPersistentData().getLong(INTERACTION_COOLDOWN) > gameTime) {
            return;
        }
        player.getPersistentData().putLong(
            INTERACTION_COOLDOWN, gameTime + cooldownTicks
        );
        if (!target.allows(player)) {
            sendDialogue(player, target.lockedDialogue);
            return;
        }
        sendDialogue(player, target.enterDialogue);
        ServerLevel destination = player.getServer().getLevel(target.dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal("[건물 출입구] 이동할 공간을 찾을 수 없습니다."));
            return;
        }
        destination.getChunkAt(target.position);
        BlockPos safePosition = findSafeDoorDestination(destination, target.position);
        if (safePosition == null) {
            LOGGER.error(
                "Building teleport blocked because the destination has no safe standing room: "
                    + "dimension={}, destination={}, interior={}",
                target.dimension.location(), target.position, target.interior
            );
            player.sendSystemMessage(Component.literal(
                "[건물 출입구] 안전한 이동 위치를 찾지 못해 이동을 중단했습니다."
            ));
            return;
        }
        ResourceKey<Level> sourceDimension = player.level().dimension();
        player.teleportTo(
            destination,
            safePosition.getX() + 0.5D, safePosition.getY(), safePosition.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        DoorTransitionSound.afterTeleport(player, sourceDimension, safePosition);
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

    private static BlockPos findSafeDoorDestination(ServerLevel level, BlockPos authored) {
        BlockPos sameFloor = findSafeDoorDestinationAtY(level, authored, 0);
        if (sameFloor != null) return sameFloor;
        // Authored building portals must stay on their authored floor. Only tolerate a
        // two-block metadata/placement mismatch; a wider scan can select a roof or another
        // storey that merely happens to have standing room.
        for (int vertical = 1; vertical <= 2; vertical++) {
            BlockPos below = findSafeDoorDestinationAtY(level, authored, -vertical);
            if (below != null) return below;
            BlockPos above = findSafeDoorDestinationAtY(level, authored, vertical);
            if (above != null) return above;
        }
        return null;
    }

    private static BlockPos findSafeDoorDestinationAtY(
        ServerLevel level, BlockPos authored, int verticalOffset
    ) {
        BlockPos center = authored.offset(0, verticalOffset, 0);
        if (hasSafeStandingRoom(level, center)) return center;
        for (int radius = 1; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                    BlockPos column = center.offset(x, 0, z);
                    if (hasSafeStandingRoom(level, column)) return column;
                }
            }
        }
        return null;
    }

    private static boolean hasSafeStandingRoom(ServerLevel level, BlockPos feet) {
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        if (floorState.isAir() || floorState.is(Blocks.BARRIER)
            || floorState.getCollisionShape(level, floor).isEmpty()) {
            return false;
        }
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above())
                .getCollisionShape(level, feet.above()).isEmpty();
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
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) return List.of();
        for (PersistedBuildingInstance instance : data(server).buildingInstances()) {
            if (!instance.dimension.equals(dimension.toString())) continue;
            if (instance.rotation == null || instance.rotation.isBlank()) continue;
            BuildingSettings settings = SETTINGS.get(instance.structure);
            RadarSetting radar = settings == null ? null : settings.radar;
            if (radar == null || !radar.enabled) continue;
            BlockPos offset = radarAnchorOffset(level, instance, radar.anchor);
            BlockPos entrance = instance.origin.offset(offset);
            String id = "building/" + instance.structure + "/"
                + entrance.getX() + "/" + entrance.getY() + "/" + entrance.getZ();
            result.add(new RadarLocationCatalog.Location(
                id,
                RadarLocationCatalog.Kind.valueOf(radar.category),
                dimension,
                entrance.getX() + 0.5D,
                entrance.getY(),
                entrance.getZ() + 0.5D,
                radar.label.isBlank() ? instance.structure : radar.label,
                instance.eventSpaceId == null ? "" : instance.eventSpaceId
            ));
        }
        return List.copyOf(result);
    }

    private static BlockPos radarAnchorOffset(
        ServerLevel level, PersistedBuildingInstance instance, String anchorId
    ) {
        Rotation rotation = rotation(instance.rotation);
        if (anchorId != null && !anchorId.isBlank() && !anchorId.equals("auto")) {
            StructureMetadata metadata = METADATA.get(instance.structure);
            if (metadata != null) {
                Anchor anchor = metadata.anchors.stream()
                    .filter(candidate -> candidate.id.equals(anchorId))
                    .findFirst().orElse(null);
                if (anchor != null) {
                    return transform(BlockPos.ZERO, anchor.position, rotation);
                }
            }
        }
        return RADAR_OFFSETS.computeIfAbsent(
            level.dimension().location() + "|" + instance.structure + "|" + instance.rotation,
            key -> {
                BlockPos door = exteriorDoorApproachOffset(instance.structure, instance.rotation);
                return java.util.Optional.ofNullable(door != null ? door
                    : exteriorRoadAnchorOffset(level, instance.structure, instance.rotation));
            }
        ).orElse(BlockPos.ZERO);
    }

    private static RuntimeData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RuntimeData::new, RuntimeData::load), DATA_FILE
        );
    }

    private record Anchor(
        String id, String type, BlockPos position, BlockPos safeSpawn,
        net.minecraft.core.Direction facing, boolean sealOpening,
        String dungeonEntrance
    ) {
    }

    private record StructureMetadata(List<Anchor> anchors, String interiorStructure) {
        Anchor first(String type) {
            return anchors.stream().filter(anchor -> anchor.type.equals(type)).findFirst().orElse(null);
        }

        Anchor namedConnection(String id) {
            return anchors.stream().filter(anchor -> anchor.id.equals(id)
                && (anchor.type.equals("door") || anchor.type.equals("transition")))
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
        String structure, Vec3i size
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
        String musicTrack, RadarSetting radar
    ) {
    }

    private record RadarSetting(
        boolean enabled, String category, String label, String anchor
    ) {
    }

    private record DoorKey(ResourceKey<Level> dimension, BlockPos position) {
    }

    private record TransitionTarget(
        ResourceKey<Level> dimension, Set<BlockPos> blocks, DoorTarget target
    ) {
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

    record PlacedBuilding(
        CobbleventureBootstrap.BlockPoint origin, String rotation
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
