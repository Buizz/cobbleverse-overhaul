package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Resolves a planned piece graph and its semantic markers into instance coordinates. */
record DungeonPieceLayout(
    DungeonPiecePlan plan,
    List<ResolvedMarker> markers
) {
    private static final Map<String, DungeonPieceLayout> LAST_VALID =
        new ConcurrentHashMap<>();

    static void clearCache() {
        LAST_VALID.clear();
    }

    static DungeonPieceLayout generate(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> allPieces,
        long seed
    ) {
        return generate(definition, allPieces, Map.of(), seed);
    }

    static DungeonPieceLayout generate(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> allPieces,
        Map<String, DungeonAuthoredPlanDefinition> authoredPlans,
        long seed
    ) {
        String pool = definition.terrain().piecePool();
        List<DungeonPieceDefinition> pieces = allPieces.stream()
            .filter(piece -> piece.tags().contains(pool))
            .toList();
        if (pieces.isEmpty()) {
            throw new IllegalStateException("Dungeon piece pool is empty: " + pool);
        }
        DungeonPiecePoolValidator.validate(definition, pieces);
        if (definition.plan().mode().equals("runtime")
            && definition.topology().mode().equals("authored")) {
            throw new IllegalStateException(
                "Dungeon topology is not available for runtime planning: authored"
            );
        }
        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
        if (definition.plan().mode().equals("runtime")) {
            return runtimeLayout(definition, pieces, byId, seed);
        }
        DungeonPiecePlan plan = authoredPlan(definition, authoredPlans, byId, seed);
        DungeonPiecePlanValidator.validate(
            plan, byId, definition.terrain().piecePool(), definition.terrain().bounds()
        );
        return resolveMarkers(definition, plan, byId, seed);
    }

    static void validateAuthoredDefinitions(
        Collection<DungeonDefinition> dungeons,
        Collection<DungeonPieceDefinition> pieces,
        Map<String, DungeonAuthoredPlanDefinition> authoredPlans
    ) {
        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
        for (DungeonDefinition dungeon : dungeons) {
            if (dungeon.plan().mode().equals("runtime")) continue;
            for (String planId : dungeon.plan().planIds()) {
                DungeonAuthoredPlanDefinition authored = authoredPlans.get(planId);
                if (authored == null) {
                    throw new IllegalStateException(
                        "Dungeon references missing authored plan: "
                            + dungeon.id() + " -> " + planId
                    );
                }
                DungeonPiecePlan plan = authored.toPlan(byId);
                DungeonPiecePlanValidator.validate(
                    plan, byId, dungeon.terrain().piecePool(), dungeon.terrain().bounds()
                );
                resolveMarkers(dungeon, plan, byId, plan.seed());
            }
        }
    }

    private static DungeonPieceLayout runtimeLayout(
        DungeonDefinition definition,
        List<DungeonPieceDefinition> pieces,
        Map<String, DungeonPieceDefinition> byId,
        long seed
    ) {
        long timeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            definition.plan().generationTimeoutMs()
        );
        long startedAt = System.nanoTime();
        long deadline = saturatedDeadline(startedAt, timeoutNanos);
        boolean hasFallback = definition.plan().fallback().equals("use_fallback_plan");
        long primaryDeadline = hasFallback
            ? saturatedDeadline(startedAt, timeoutNanos / 3L * 2L)
            : deadline;
        IllegalStateException lastFailure = null;
        DungeonPiecePlanner.Settings settings = singleAttempt(
            plannerSettings(definition, false)
        );
        for (int attempt = 0; attempt < definition.plan().maxAttempts(); attempt++) {
            if (System.nanoTime() >= primaryDeadline) break;
            try {
                long attemptSeed = attempt == 0 ? seed
                    : markerSeed(seed + attempt, "layout_attempt");
                DungeonPiecePlan plan = DungeonPiecePlanner.generate(
                    pieces, settings, attemptSeed, primaryDeadline
                );
                DungeonPiecePlanValidator.validate(
                    plan, byId, definition.terrain().piecePool(),
                    definition.terrain().bounds()
                );
                DungeonPiecePlanValidator.validateNoOpenConnectors(plan, byId);
                return resolveMarkers(definition, plan, byId, seed);
            } catch (IllegalStateException failure) {
                lastFailure = preferPlanningFailure(lastFailure, failure);
            }
        }
        if (definition.plan().fallback().equals("use_last_valid")) {
            DungeonPieceLayout cached = LAST_VALID.get(definition.id());
            if (cached != null) return cached;
        }
        if (definition.plan().fallback().equals("use_fallback_plan")) {
            IllegalStateException fallbackFailure = null;
            DungeonPiecePlanner.Settings fallbackSettings = singleAttempt(
                plannerSettings(definition, true)
            );
            for (int attempt = 0; attempt < definition.plan().maxAttempts(); attempt++) {
                if (System.nanoTime() >= deadline) break;
                try {
                    long fallbackSeed = attempt == 0 ? seed
                        : markerSeed(seed + attempt, "fallback_layout_attempt");
                    DungeonPiecePlan fallback = DungeonPiecePlanner.generate(
                        pieces, fallbackSettings, fallbackSeed, deadline
                    );
                    DungeonPiecePlanValidator.validate(
                        fallback, byId, definition.terrain().piecePool(),
                        definition.terrain().bounds()
                    );
                    DungeonPiecePlanValidator.validateNoOpenConnectors(
                        fallback, byId
                    );
                    return resolveMarkers(definition, fallback, byId, seed);
                } catch (IllegalStateException failure) {
                    fallbackFailure = preferPlanningFailure(
                        fallbackFailure, failure
                    );
                }
            }
            if (fallbackFailure != null && lastFailure != null) {
                fallbackFailure.addSuppressed(lastFailure);
            }
            if (fallbackFailure != null) throw fallbackFailure;
        }
        if (lastFailure != null) throw lastFailure;
        throw new IllegalStateException("Dungeon runtime planning produced no attempts");
    }

    private static long saturatedDeadline(long startedAt, long timeoutNanos) {
        if (timeoutNanos >= Long.MAX_VALUE - startedAt) return Long.MAX_VALUE;
        return startedAt + timeoutNanos;
    }

    private static IllegalStateException preferPlanningFailure(
        IllegalStateException current, IllegalStateException candidate
    ) {
        if (current == null) return candidate;
        boolean currentIsCapacity = current.getMessage() != null
            && current.getMessage().contains("NPC");
        boolean candidateIsCapacity = candidate.getMessage() != null
            && candidate.getMessage().contains("NPC");
        return candidateIsCapacity || !currentIsCapacity ? candidate : current;
    }

    private static DungeonPiecePlanner.Settings singleAttempt(
        DungeonPiecePlanner.Settings settings
    ) {
        return new DungeonPiecePlanner.Settings(
            settings.bounds(), settings.criticalPathMin(), settings.criticalPathMax(),
            settings.branchCountMin(), settings.branchCountMax(),
            settings.branchDepthMin(), settings.branchDepthMax(),
            settings.loopChance(), 1, settings.layoutMode(),
            settings.verticalDirection(), settings.floorChangesMin(),
            settings.floorChangesMax(), settings.verticalMode(),
            settings.floorHeight()
        );
    }

    private static DungeonPiecePlan authoredPlan(
        DungeonDefinition definition,
        Map<String, DungeonAuthoredPlanDefinition> authoredPlans,
        Map<String, DungeonPieceDefinition> pieces,
        long seed
    ) {
        List<String> ids = definition.plan().planIds();
        int index = definition.plan().mode().equals("authored")
            ? 0 : new java.util.Random(seed).nextInt(ids.size());
        String selectedId = ids.get(index);
        DungeonAuthoredPlanDefinition authored = authoredPlans.get(selectedId);
        if (authored == null) {
            throw new IllegalStateException(
                "Dungeon authored plan is missing: " + selectedId
            );
        }
        return authored.toPlan(pieces);
    }

    private static DungeonPieceLayout resolveMarkers(
        DungeonDefinition definition,
        DungeonPiecePlan plan,
        Map<String, DungeonPieceDefinition> byId,
        long seed
    ) {
        List<ResolvedMarker> markers = new ArrayList<>();
        Map<MarkerKey, BlockPos> uniqueMarkers = new LinkedHashMap<>();
        for (DungeonPiecePlan.Placement placement : plan.placements()) {
            DungeonPieceDefinition piece = byId.get(placement.pieceId());
            if (piece == null) {
                throw new IllegalStateException(
                    "Planned dungeon piece definition disappeared: " + placement.pieceId()
                );
            }
            for (DungeonPieceDefinition.Marker marker : piece.markers()) {
                BlockPos transformed = StructureTemplate.transform(
                    marker.position(), Mirror.NONE, placement.rotation(), BlockPos.ZERO
                );
                BlockPos position = placement.templateOrigin().offset(transformed);
                markers.add(new ResolvedMarker(
                    marker.kind(), marker.reference(), position,
                    placement.index(), marker.connector()
                ));
                if (marker.reference() != null
                    || marker.kind().equals("entry")
                    || marker.kind().equals("exit")) {
                    MarkerKey key = new MarkerKey(marker.kind(), marker.reference());
                    BlockPos previous = uniqueMarkers.putIfAbsent(key, position);
                    if (previous != null) {
                        throw new IllegalStateException(
                            "Duplicate dungeon piece marker: " + key.display()
                        );
                    }
                }
            }
        }
        requireMarker(markers, "entry", null);
        requireMarker(markers, "exit", null);
        DungeonPieceLayout generated = new DungeonPieceLayout(plan, List.copyOf(markers));
        generated.validateGateProgression(definition, seed);
        LAST_VALID.put(definition.id(), generated);
        return generated;
    }

    private static DungeonPiecePlanner.Settings plannerSettings(
        DungeonDefinition definition, boolean safeFallback
    ) {
        DungeonDefinition.Topology topology = definition.topology();
        DungeonDefinition.Vertical vertical = definition.vertical();
        String layoutMode = switch (definition.spatialLayout().algorithm()) {
            case "grid_walk" -> java.util.Set.of(
                "legacy_maze", "legacy_rooms_and_corridors",
                "critical_path_branches", "maze", "rooms_and_corridors"
            ).contains(topology.mode()) ? topology.mode() : "corridor_spine";
            case "socket_accretion" -> "room_network";
            case "hub_and_spokes" -> "hub_and_spokes";
            case "authored" -> topology.mode();
            case "scatter_graph", "bsp_floor" -> throw new IllegalStateException(
                "Dungeon spatial algorithm is not available at runtime yet: "
                    + definition.spatialLayout().algorithm()
            );
            default -> throw new IllegalStateException(
                "Unknown dungeon spatial algorithm: "
                    + definition.spatialLayout().algorithm()
            );
        };
        int safeCriticalRooms = Math.min(
            topology.criticalPathRooms().maximum(),
            Math.max(
                Math.max(6, topology.criticalPathRooms().minimum()),
                vertical.floorCount().minimum() + 2
            )
        );
        int safeBranches = topology.branchCount().maximum() > 0
            && safeCriticalRooms >= 4 ? 1 : 0;
        return new DungeonPiecePlanner.Settings(
            definition.terrain().bounds(),
            safeFallback ? safeCriticalRooms : topology.criticalPathRooms().minimum(),
            safeFallback ? safeCriticalRooms : topology.criticalPathRooms().maximum(),
            safeFallback ? safeBranches : topology.branchCount().minimum(),
            safeFallback ? safeBranches : topology.branchCount().maximum(),
            safeFallback ? 1 : topology.branchDepth().minimum(),
            safeFallback ? 1 : topology.branchDepth().maximum(),
            safeFallback ? 0.0D : topology.loopChance(),
            safeFallback ? Math.max(64, definition.plan().maxAttempts())
                : definition.plan().maxAttempts(),
            layoutMode,
            vertical.mode().equals("flat") ? "flat" : vertical.direction(),
            Math.max(0, vertical.floorCount().minimum() - 1),
            Math.max(0, vertical.floorCount().maximum() - 1),
            vertical.mode(), vertical.floorHeight()
        );
    }

    BlockPos requiredMarker(String kind, String reference) {
        return requireMarker(markers, kind, reference);
    }

    Map<MarkerKey, BlockPos> featureMarkers(
        DungeonDefinition definition, long seed
    ) {
        return featureAssignments(definition, seed).entrySet().stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().position()
            )
        );
    }

    private Map<MarkerKey, ResolvedMarker> featureAssignments(
        DungeonDefinition definition, long seed
    ) {
        Map<MarkerKey, ResolvedMarker> assigned = new LinkedHashMap<>();
        Map<String, List<ResolvedMarker>> candidates = new HashMap<>();
        for (ResolvedMarker marker : markers) {
            if (marker.reference() == null
                && !marker.kind().equals("entry")
                && !marker.kind().equals("exit")) {
                if (marker.kind().equals("gate")
                    && !isUsableGateMarker(marker)) continue;
                candidates.computeIfAbsent(marker.kind(), ignored -> new ArrayList<>())
                    .add(marker);
            } else {
                assigned.put(new MarkerKey(marker.kind(), marker.reference()), marker);
            }
        }
        for (Map.Entry<String, List<ResolvedMarker>> entry : candidates.entrySet()) {
            Collections.shuffle(entry.getValue(), new java.util.Random(
                markerSeed(seed, entry.getKey())
            ));
        }

        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            if (definition.npcPlacement().enabled()
                && encounter.kind().equals("trainer") && !encounter.boss()
                && encounter.position() == null) {
                assignEncounterCenter(assigned, candidates, definition, encounter);
            } else {
                assignFeature(
                    assigned, candidates, encounter.boss() ? "boss" : "encounter",
                    encounter.id(), encounter.position(), definition.id()
                );
            }
        }
        assignNpcSlots(assigned, candidates, definition);
        for (DungeonDefinition.LootContainer container : definition.loot().containers()) {
            assignFeature(
                assigned, candidates, "loot", container.id(),
                container.position(), definition.id()
            );
        }
        for (DungeonDefinition.HealingStation station : definition.support().healingStations()) {
            assignFeature(
                assigned, candidates, "healing_station", station.id(),
                station.position(), definition.id()
            );
        }
        for (DungeonDefinition.Checkpoint checkpoint : definition.support().checkpoints()) {
            assignFeature(
                assigned, candidates, "checkpoint", checkpoint.id(),
                checkpoint.position(), definition.id()
            );
        }
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            assignFeature(
                assigned, candidates, "objective", objective.id(),
                objective.position(), definition.id()
            );
        }
        for (DungeonDefinition.Gate gate : definition.gates()) {
            if (!gate.placement().equals("marker")) continue;
            assignGateFeature(assigned, candidates, definition, gate);
        }
        return Map.copyOf(assigned);
    }

    private void assignGateFeature(
        Map<MarkerKey, ResolvedMarker> assigned,
        Map<String, List<ResolvedMarker>> candidates,
        DungeonDefinition definition,
        DungeonDefinition.Gate gate
    ) {
        MarkerKey key = new MarkerKey("gate", gate.id());
        if (assigned.containsKey(key)) return;
        if (plan == null) {
            assignFeature(
                assigned, candidates, "gate", gate.id(), null, definition.id()
            );
            return;
        }
        int start = plan.placements().stream()
            .filter(placement -> placement.role().equals("start"))
            .map(DungeonPiecePlan.Placement::index).findFirst().orElseThrow();
        List<ResolvedMarker> available = candidates.getOrDefault("gate", List.of());
        for (int index = available.size() - 1; index >= 0; index--) {
            ResolvedMarker marker = available.get(index);
            DungeonPiecePlan.Link blocked = linkedAt(marker).orElse(null);
            if (blocked == null) continue;
            Set<Integer> reachable = reachable(start, graphWithout(blocked));
            if (!gateRequirementsReachable(gate, definition, assigned, reachable)) {
                continue;
            }
            assigned.put(key, available.remove(index));
            return;
        }
        throw new IllegalStateException(
            "Dungeon has no gate marker with reachable requirements: "
                + definition.id() + " -> " + gate.id()
        );
    }

    private static boolean gateRequirementsReachable(
        DungeonDefinition.Gate gate,
        DungeonDefinition definition,
        Map<MarkerKey, ResolvedMarker> assigned,
        Set<Integer> reachable
    ) {
        for (DungeonDefinition.GateRequirement requirement : gate.requirements()) {
            if (requirement.type().equals("item")) continue;
            String kind = requirement.type();
            if (kind.equals("encounter")) {
                DungeonDefinition.Encounter encounter = definition.encounters().stream()
                    .filter(value -> value.id().equals(requirement.reference()))
                    .findFirst().orElseThrow();
                kind = encounter.boss() ? "boss" : "encounter";
            }
            ResolvedMarker required = assigned.get(new MarkerKey(
                kind, requirement.reference()
            ));
            if (required != null && required.placementIndex() >= 0
                && !reachable.contains(required.placementIndex())) {
                return false;
            }
        }
        return true;
    }

    private static void assignEncounterCenter(
        Map<MarkerKey, ResolvedMarker> assigned,
        Map<String, List<ResolvedMarker>> candidates,
        DungeonDefinition definition,
        DungeonDefinition.Encounter encounter
    ) {
        MarkerKey key = new MarkerKey("encounter", encounter.id());
        if (assigned.containsKey(key)) return;
        Map<String, Integer> actorCounts = definition.encounters().stream().collect(
            java.util.stream.Collectors.toMap(
                DungeonDefinition.Encounter::id,
                DungeonDefinition.Encounter::actorCount
            )
        );
        Map<Integer, Integer> occupancy = new HashMap<>();
        for (Map.Entry<MarkerKey, ResolvedMarker> entry : assigned.entrySet()) {
            if (!entry.getKey().kind().equals("encounter")) continue;
            occupancy.merge(
                entry.getValue().placementIndex(),
                actorCounts.getOrDefault(entry.getKey().reference(), 1),
                Integer::sum
            );
        }
        List<ResolvedMarker> available = candidates.getOrDefault(
            "encounter", List.of()
        );
        int selected = -1;
        int minimumOccupancy = Integer.MAX_VALUE;
        for (int index = available.size() - 1; index >= 0; index--) {
            ResolvedMarker marker = available.get(index);
            int roomOccupancy = occupancy.getOrDefault(marker.placementIndex(), 0);
            int roomCapacity = npcSlotCapacity(
                candidates.getOrDefault("npc_spawn", List.of()).stream()
                    .filter(slot -> slot.placementIndex() == marker.placementIndex())
                    .toList(),
                definition.npcPlacement()
            );
            if (roomOccupancy + encounter.actorCount()
                > Math.min(definition.npcPlacement().maximumPerRoom(), roomCapacity)) {
                continue;
            }
            if (roomOccupancy < minimumOccupancy) {
                selected = index;
                minimumOccupancy = roomOccupancy;
            }
        }
        if (selected < 0) {
            throw new IllegalStateException(
                "Dungeon cannot reserve an NPC room for encounter: "
                    + definition.id() + " -> " + encounter.id()
                    + " (encounter markers=" + available.size()
                    + ", occupied rooms=" + occupancy + ")"
            );
        }
        assigned.put(key, available.remove(selected));
    }

    private static void assignNpcSlots(
        Map<MarkerKey, ResolvedMarker> assigned,
        Map<String, List<ResolvedMarker>> candidates,
        DungeonDefinition definition
    ) {
        if (!definition.npcPlacement().enabled()) return;
        List<ResolvedMarker> available = candidates.getOrDefault(
            "npc_spawn", new ArrayList<>()
        );
        DungeonDefinition.NpcPlacement settings = definition.npcPlacement();
        int capacity = npcSlotCapacity(available, settings);
        if (capacity < settings.requiredSlots()) {
            throw new IllegalStateException(
                "Dungeon NPC slot capacity is insufficient: " + definition.id()
                    + " requires " + settings.requiredSlots() + " but plan provides "
                    + capacity
            );
        }

        Map<Integer, Integer> occupancy = new HashMap<>();
        List<ResolvedMarker> used = new ArrayList<>();
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            if (!encounter.kind().equals("trainer")) continue;
            ResolvedMarker anchor = assigned.get(new MarkerKey(
                encounter.boss() ? "boss" : "encounter", encounter.id()
            ));
            int preferredPlacement = anchor == null ? -1 : anchor.placementIndex();
            List<ResolvedMarker> selected = selectNpcGroup(
                available, used, occupancy, encounter.actorCount(),
                preferredPlacement, settings
            );
            if (selected.size() != encounter.actorCount()) {
                throw new IllegalStateException(
                    "Dungeon cannot place encounter NPCs in one safe room: "
                        + definition.id() + " -> " + encounter.id()
                );
            }
            for (int index = 0; index < selected.size(); index++) {
                ResolvedMarker marker = selected.get(index);
                available.remove(marker);
                used.add(marker);
                occupancy.merge(marker.placementIndex(), 1, Integer::sum);
                assigned.put(
                    new MarkerKey("npc_spawn", npcMarkerReference(encounter.id(), index)),
                    marker
                );
            }
        }
    }

    static String npcMarkerReference(String encounterId, int actorIndex) {
        return encounterId + "#" + actorIndex;
    }

    private static List<ResolvedMarker> selectNpcGroup(
        List<ResolvedMarker> available,
        List<ResolvedMarker> used,
        Map<Integer, Integer> occupancy,
        int actorCount,
        int preferredPlacement,
        DungeonDefinition.NpcPlacement settings
    ) {
        List<Integer> placements = available.stream()
            .map(ResolvedMarker::placementIndex)
            .distinct()
            .filter(index -> preferredPlacement < 0 || index == preferredPlacement)
            .sorted(java.util.Comparator.comparingInt(index ->
                index == preferredPlacement ? Integer.MIN_VALUE
                    : occupancy.getOrDefault(index, 0)
            ))
            .toList();
        for (int placement : placements) {
            if (occupancy.getOrDefault(placement, 0) + actorCount
                > settings.maximumPerRoom()) continue;
            List<ResolvedMarker> room = available.stream()
                .filter(marker -> marker.placementIndex() == placement)
                .toList();
            List<ResolvedMarker> selected = new ArrayList<>();
            if (selectCompatibleSlots(
                room, 0, actorCount, used, selected, settings.minimumSpacing()
            )) return List.copyOf(selected);
        }
        return List.of();
    }

    private static int npcSlotCapacity(
        List<ResolvedMarker> available,
        DungeonDefinition.NpcPlacement settings
    ) {
        Map<Integer, List<ResolvedMarker>> byRoom = available.stream().collect(
            java.util.stream.Collectors.groupingBy(ResolvedMarker::placementIndex)
        );
        int capacity = 0;
        for (List<ResolvedMarker> room : byRoom.values()) {
            int roomMaximum = Math.min(settings.maximumPerRoom(), room.size());
            for (int target = roomMaximum; target > 0; target--) {
                if (selectCompatibleSlots(
                    room, 0, target, List.of(), new ArrayList<>(),
                    settings.minimumSpacing()
                )) {
                    capacity += target;
                    break;
                }
            }
        }
        return capacity;
    }

    private static boolean selectCompatibleSlots(
        List<ResolvedMarker> candidates,
        int start,
        int target,
        List<ResolvedMarker> fixed,
        List<ResolvedMarker> selected,
        double minimumSpacing
    ) {
        if (selected.size() == target) return true;
        for (int index = start; index < candidates.size(); index++) {
            ResolvedMarker candidate = candidates.get(index);
            if (!farEnough(candidate, fixed, minimumSpacing)
                || !farEnough(candidate, selected, minimumSpacing)) continue;
            selected.add(candidate);
            if (selectCompatibleSlots(
                candidates, index + 1, target, fixed, selected, minimumSpacing
            )) return true;
            selected.removeLast();
        }
        return false;
    }

    private static boolean farEnough(
        ResolvedMarker candidate,
        List<ResolvedMarker> others,
        double minimumSpacing
    ) {
        double requiredSquared = minimumSpacing * minimumSpacing;
        return others.stream().allMatch(other ->
            candidate.position().distSqr(other.position()) >= requiredSquared
        );
    }

    private static long markerSeed(long seed, String kind) {
        long mixed = seed ^ ((long) kind.hashCode() * 0x9E3779B97F4A7C15L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static void assignFeature(
        Map<MarkerKey, ResolvedMarker> assigned,
        Map<String, List<ResolvedMarker>> candidates,
        String kind,
        String reference,
        BlockPos fallback,
        String dungeonId
    ) {
        MarkerKey key = new MarkerKey(kind, reference);
        if (fallback != null) {
            assigned.put(key, new ResolvedMarker(kind, reference, fallback, -1, null));
            return;
        }
        if (assigned.containsKey(key)) return;
        List<ResolvedMarker> available = candidates.getOrDefault(kind, List.of());
        if (!available.isEmpty()) {
            int selected = available.size() - 1;
            if (kind.equals("encounter")) {
                Map<Integer, Long> occupancy = assigned.values().stream()
                    .filter(marker -> marker.kind().equals("encounter"))
                    .map(ResolvedMarker::placementIndex)
                    .filter(index -> index >= 0)
                    .collect(java.util.stream.Collectors.groupingBy(
                        index -> index, java.util.stream.Collectors.counting()
                    ));
                long minimumOccupancy = available.stream()
                    .mapToLong(marker -> occupancy.getOrDefault(
                        marker.placementIndex(), 0L
                    )).min().orElse(0L);
                for (int index = available.size() - 1; index >= 0; index--) {
                    if (occupancy.getOrDefault(
                        available.get(index).placementIndex(), 0L
                    ) == minimumOccupancy) {
                        selected = index;
                        break;
                    }
                }
            }
            assigned.put(key, available.remove(selected));
            return;
        }
        throw new IllegalStateException(
            "Dungeon has no available " + kind + " marker: "
                + dungeonId + " -> " + reference
        );
    }

    private void validateGateProgression(DungeonDefinition definition, long seed) {
        Map<MarkerKey, ResolvedMarker> assigned = featureAssignments(definition, seed);
        int start = plan.placements().stream()
            .filter(placement -> placement.role().equals("start"))
            .map(DungeonPiecePlan.Placement::index).findFirst().orElseThrow();
        for (DungeonDefinition.Gate gate : definition.gates()) {
            if (!gate.placement().equals("marker")) continue;
            ResolvedMarker gateMarker = assigned.get(new MarkerKey("gate", gate.id()));
            if (gateMarker == null || gateMarker.placementIndex() < 0) {
                throw invalidGate(gate, "marker is not attached to a planned piece");
            }
            if (gateMarker.connector() == null) {
                throw invalidGate(gate, "marker does not declare its blocked connector");
            }
            DungeonPiecePlan.Link blocked = linkedAt(gateMarker).orElseThrow(() -> invalidGate(
                gate, "marker connector is not used by a plan link"
            ));
            Set<Integer> reachable = reachable(start, graphWithout(blocked));
            boolean fromReachable = reachable.contains(blocked.fromIndex());
            boolean toReachable = reachable.contains(blocked.toIndex());
            if (fromReachable == toReachable) {
                throw invalidGate(gate, "blocked link does not separate locked progression");
            }
            for (DungeonDefinition.GateRequirement requirement : gate.requirements()) {
                if (requirement.type().equals("item")) continue;
                String kind = requirement.type();
                if (kind.equals("encounter")) {
                    DungeonDefinition.Encounter encounter = definition.encounters().stream()
                        .filter(value -> value.id().equals(requirement.reference()))
                        .findFirst().orElseThrow();
                    kind = encounter.boss() ? "boss" : "encounter";
                }
                ResolvedMarker required = assigned.get(new MarkerKey(
                    kind, requirement.reference()
                ));
                if (required == null || required.placementIndex() < 0) continue;
                if (!reachable.contains(required.placementIndex())) {
                    throw invalidGate(
                        gate, "required " + requirement.type()
                            + " is behind the gate: " + requirement.reference()
                    );
                }
            }
        }
    }

    private boolean isUsableGateMarker(ResolvedMarker marker) {
        if (plan == null) return true;
        if (marker.connector() == null || marker.placementIndex() < 0) return false;
        return linkedAt(marker).filter(link -> {
            int start = plan.placements().stream()
                .filter(placement -> placement.role().equals("start"))
                .map(DungeonPiecePlan.Placement::index).findFirst().orElseThrow();
            Set<Integer> reachable = reachable(start, graphWithout(link));
            return reachable.contains(link.fromIndex())
                != reachable.contains(link.toIndex());
        }).isPresent();
    }

    private java.util.Optional<DungeonPiecePlan.Link> linkedAt(ResolvedMarker marker) {
        return plan.links().stream().filter(link ->
            (link.fromIndex() == marker.placementIndex()
                && link.fromConnector().equals(marker.connector()))
            || (link.toIndex() == marker.placementIndex()
                && link.toConnector().equals(marker.connector()))
        ).findFirst();
    }

    private Map<Integer, Set<Integer>> graphWithout(DungeonPiecePlan.Link blocked) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (DungeonPiecePlan.Link link : plan.links()) {
            if (link.equals(blocked)) continue;
            graph.computeIfAbsent(link.fromIndex(), ignored -> new HashSet<>())
                .add(link.toIndex());
            graph.computeIfAbsent(link.toIndex(), ignored -> new HashSet<>())
                .add(link.fromIndex());
        }
        return graph;
    }

    private static Set<Integer> reachable(int start, Map<Integer, Set<Integer>> graph) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (!visited.add(current)) continue;
            graph.getOrDefault(current, Set.of()).forEach(queue::addLast);
        }
        return visited;
    }

    private static IllegalStateException invalidGate(
        DungeonDefinition.Gate gate, String reason
    ) {
        return new IllegalStateException(
            "Invalid dungeon gate progression: " + gate.id() + " -> " + reason
        );
    }

    private static BlockPos requireMarker(
        List<ResolvedMarker> markers, String kind, String reference
    ) {
        return markers.stream()
            .filter(marker -> marker.kind().equals(kind)
                && java.util.Objects.equals(marker.reference(), reference))
            .map(ResolvedMarker::position)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Dungeon piece marker is missing: "
                    + new MarkerKey(kind, reference).display()
            ));
    }

    record ResolvedMarker(
        String kind,
        String reference,
        BlockPos position,
        int placementIndex,
        String connector
    ) {
        ResolvedMarker(String kind, String reference, BlockPos position) {
            this(kind, reference, position, -1, null);
        }
    }

    record MarkerKey(String kind, String reference) {
        private String display() {
            return reference == null ? kind : kind + ":" + reference;
        }
    }
}
