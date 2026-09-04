package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonPiecePlannerTest {
    @Test
    void soloGeneratedDungeonPassesMultiSeedTopologyAndMarkerStress() throws Exception {
        List<DungeonPieceDefinition> pieces = packagedRocketPieces();
        assertTimeout(Duration.ofSeconds(20), () -> {
            for (String name : List.of("rocket_pokemon_tower")) {
                DungeonDefinition dungeon = packagedDungeon(name);
                for (long seed = 0; seed < 32; seed++) {
                    DungeonDefinition runDungeon = dungeon.materializeGeneratedTrainers(seed);
                    DungeonPieceLayout generated;
                    try {
                        generated = DungeonPieceLayout.generate(runDungeon, pieces, seed);
                    } catch (IllegalStateException failure) {
                        throw new IllegalStateException(name + " seed=" + seed, failure);
                    }
                    assertNoOverlap(generated.plan());
                    assertConnected(generated.plan());
                    assertNoOpenConnectors(generated.plan(), pieces);
                    assertRequiredFeatures(runDungeon, generated, seed);
                    assertEncountersUseDistinctSections(runDungeon, generated, seed);
                }
                assertEquals(
                    DungeonPieceLayout.generate(dungeon, pieces, 17L),
                    DungeonPieceLayout.generate(dungeon, pieces, 17L)
                );
            }
        });
    }

    @Test
    void generatesDistinctVerticalProfilesForRuntimeRocketDungeons() throws Exception {
        List<DungeonPieceDefinition> pieces = packagedRocketPieces();
        for (String name : List.of(
            "rocket_casino_hideout", "rocket_silph_company", "rocket_pokemon_tower"
        )) {
            DungeonDefinition dungeon = packagedDungeon(name);
            Set<Integer> observedFloorCounts = new java.util.HashSet<>();
            for (long seed = 1; seed <= 4; seed++) {
                DungeonPieceLayout generated;
                try {
                    generated = DungeonPieceLayout.generate(dungeon, pieces, seed);
                } catch (IllegalStateException failure) {
                    throw new IllegalStateException(name + " seed=" + seed, failure);
                }
                List<Integer> elevations = generated.plan().placements().stream()
                    .filter(DungeonPiecePlan.Placement::criticalPath)
                    .map(placement -> placement.minimum().getY())
                    .toList();
                int changes = 0;
                for (int index = 1; index < elevations.size(); index++) {
                    int previous = elevations.get(index - 1);
                    int current = elevations.get(index);
                    if (previous != current) changes++;
                    if (dungeon.layout().verticalDirection().equals("ascending")) {
                        assertTrue(current >= previous, name + " descended");
                    } else if (dungeon.layout().verticalDirection().equals("descending")) {
                        assertTrue(current <= previous, name + " ascended");
                    }
                }
                assertTrue(changes >= dungeon.layout().floorChanges().minimum(), name);
                assertTrue(changes <= dungeon.layout().floorChanges().maximum(), name);
                assertTrue(elevations.stream().allMatch(y -> y % 8 == 0),
                    name + " did not align floors to the regular NBT piece height");
                assertEquals(changes + 1, elevations.stream().distinct().count(),
                    name + " did not build each floor before joining them");
                observedFloorCounts.add(changes + 1);
            }
            assertTrue(observedFloorCounts.size() > 1,
                name + " ignored the configured floor-count range");
        }
    }

    @Test
    void silphRuntimePlanningCompletesAcrossMultipleEntrySeeds() throws Exception {
        List<DungeonPieceDefinition> pieces = packagedRocketPieces();
        DungeonDefinition dungeon = packagedDungeon("rocket_silph_company");

        assertTimeout(Duration.ofSeconds(12), () -> {
            for (long seed = 0; seed < 24; seed++) {
                DungeonPieceLayout generated;
                try {
                    generated = DungeonPieceLayout.generate(dungeon, pieces, seed);
                } catch (IllegalStateException failure) {
                    throw new IllegalStateException("silph seed=" + seed, failure);
                }
                assertNoOverlap(generated.plan());
                assertConnected(generated.plan());
                assertNoOpenConnectors(generated.plan(), pieces);
            }
        });
    }

    @Test
    void assignsEveryRuntimeDungeonTrainerActorToAConfiguredNpcSlot() throws Exception {
        List<DungeonPieceDefinition> pieces = packagedRocketPieces();
        for (String name : List.of(
            "rocket_casino_hideout", "rocket_silph_company", "rocket_pokemon_tower"
        )) {
            DungeonDefinition dungeon = packagedDungeon(name);
            DungeonDefinition runDungeon = dungeon.materializeGeneratedTrainers(517L);
            DungeonPiecePlanner.Settings plannerSettings = DungeonPieceLayout
                .plannerSettings(runDungeon, pieces.stream().filter(piece ->
                    piece.tags().contains(runDungeon.terrain().piecePool())
                ).toList(), false);
            assertTrue(plannerSettings.chamberCount()
                <= runDungeon.vertical().floorCount().maximum(), name);
            DungeonPieceLayout generated;
            try {
                generated = DungeonPieceLayout.generate(runDungeon, pieces, 517L);
            } catch (IllegalStateException failure) {
                throw new IllegalStateException(name, failure);
            }
            Map<DungeonPieceLayout.MarkerKey, BlockPos> markers =
                generated.featureMarkers(runDungeon, 517L);

            long assignedNpcSlots = markers.keySet().stream()
                .filter(key -> key.kind().equals("npc_spawn"))
                .count();
            assertEquals(
                runDungeon.npcPlacement().requiredSlots(), assignedNpcSlots, name
            );
            for (DungeonDefinition.Encounter encounter : runDungeon.encounters()) {
                if (!encounter.kind().equals("trainer")) continue;
                for (int actor = 0; actor < encounter.actorCount(); actor++) {
                    assertTrue(markers.containsKey(new DungeonPieceLayout.MarkerKey(
                        "npc_spawn",
                        DungeonPieceLayout.npcMarkerReference(encounter.id(), actor)
                    )), name + " -> " + encounter.id() + "#" + actor);
                }
            }
            List<BlockPos> npcPositions = markers.entrySet().stream()
                .filter(entry -> entry.getKey().kind().equals("npc_spawn"))
                .map(Map.Entry::getValue).toList();
            Map<Integer, Long> npcByFloor = generated.plan().placements().stream()
                .filter(placement -> !placement.pieceId().contains("/stairs_"))
                .map(placement -> placement.minimum().getY()).distinct()
                .collect(java.util.stream.Collectors.toMap(
                    floorY -> floorY,
                    floorY -> npcPositions.stream().filter(position ->
                        position.getY() >= floorY
                            && position.getY() < floorY + runDungeon.vertical().floorHeight()
                    ).count()
                ));
            long minimumFloorNpcs = npcByFloor.values().stream()
                .mapToLong(Long::longValue).min().orElse(0L);
            long maximumFloorNpcs = npcByFloor.values().stream()
                .mapToLong(Long::longValue).max().orElse(0L);
            assertTrue(maximumFloorNpcs - minimumFloorNpcs <= 1,
                name + " did not distribute NPCs evenly by floor: " + npcByFloor);

            Set<Integer> occupiedNpcPlacements = generated.markers().stream()
                .filter(marker -> marker.kind().equals("npc_spawn")
                    && npcPositions.contains(marker.position()))
                .map(DungeonPieceLayout.ResolvedMarker::placementIndex)
                .collect(java.util.stream.Collectors.toSet());
            for (DungeonPiecePlan.Placement chamber : generated.plan().placements()
                .stream().filter(placement -> placement.role().equals("room")).toList()) {
                assertTrue(occupiedNpcPlacements.contains(chamber.index()),
                    name + " generated an empty ordinary chamber at "
                    + chamber.minimum() + "; occupied=" + occupiedNpcPlacements
                    + "; rooms=" + generated.plan().placements().stream()
                        .filter(placement -> placement.role().equals("room"))
                        .map(placement -> placement.index() + ":" + placement.pieceId()
                            + "@" + placement.minimum())
                        .toList());
            }
        }
    }

    private static void assertSparseRoomCadence(DungeonPiecePlan plan, String name) {
        List<String> roles = plan.placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath)
            .map(DungeonPiecePlan.Placement::role)
            .toList();
        Set<String> roomRoles = Set.of("room", "support");
        for (int index = 1; index < roles.size(); index++) {
            if (!roomRoles.contains(roles.get(index))) continue;
            assertTrue(index < 2 || (!roomRoles.contains(roles.get(index - 1))
                && !roomRoles.contains(roles.get(index - 2))),
                name + " placed rooms too densely on the critical route");
        }
    }

    @Test
    void usesMinimalSafePlanWhenConfiguredGenerationCannotFit() throws Exception {
        DungeonPieceLayout.clearCache();
        DungeonDefinition definition = pieceDungeon(
            "use_fallback_plan", 3, 3, 128, 1, 30
        );

        DungeonPieceLayout generated = DungeonPieceLayout.generate(
            definition, testPieces(), 41L
        );

        assertEquals(3, generated.plan().placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath).count());
        assertEquals("start", generated.plan().placements().getFirst().role());
        assertEquals("exit", generated.plan().placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath).toList().getLast().role());
    }

    @Test
    void reusesLastValidatedPlanWhenLaterGenerationFails() throws Exception {
        DungeonPieceLayout.clearCache();
        DungeonPieceLayout valid = DungeonPieceLayout.generate(
            pieceDungeon("use_last_valid", 6, 6, 0, 100, 80), testPieces(), 91L
        );

        DungeonPieceLayout recovered = DungeonPieceLayout.generate(
            pieceDungeon("use_last_valid", 3, 3, 128, 1, 80), testPieces(), 92L
        );

        assertEquals(valid, recovered);
    }

    @Test
    void resolvesEntryAndExitMarkersFromRotatedPiecePlan() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        );
        assertTrue(stream != null);
        DungeonDefinition definition;
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            root.add("plan", JsonParser.parseString("""
                {"mode":"runtime","seed_policy":"fixed","fallback":"reject_entry",
                 "max_attempts":100}
                """).getAsJsonObject());
            root.add("terrain", JsonParser.parseString("""
                {"mode":"nbt_pieces","piece_pool":"cobbleventure:theme/test",
                 "bounds":[80,16,80]}
                """).getAsJsonObject());
            root.add("layout", JsonParser.parseString("""
                {"mode":"critical_path_branches","critical_path_rooms":[6,6],
                 "branch_count":[0,0],"branch_depth":[1,1],"loop_chance":0}
                """).getAsJsonObject());
            addSyntheticFallbacks(root);
            definition = DungeonDefinition.parse(root);
        }

        DungeonPieceLayout layout = DungeonPieceLayout.generate(
            definition, testPieces(), 7734L
        );

        assertEquals(6, layout.plan().placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath).count());
        assertNoOpenConnectors(layout.plan(), testPieces());
        assertTrue(layout.requiredMarker("entry", null).getX() >= 0);
        assertTrue(layout.requiredMarker("exit", null).getX() >= 0);
        assertTrue(!layout.requiredMarker("entry", null).equals(
            layout.requiredMarker("exit", null)
        ));
        assertTrue(layout.requiredMarker("boss", "boss").getX() >= 0);
    }

    @Test
    void createsDeterministicCriticalPathAndBranchesWithoutOverlap() {
        List<DungeonPieceDefinition> pieces = testPieces();
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(80, 16, 80), 6, 6, 2, 2, 1, 1, 0.0D, 100
        );

        DungeonPiecePlan first = DungeonPiecePlanner.generate(pieces, settings, 7734L);
        DungeonPiecePlan repeated = DungeonPiecePlanner.generate(pieces, settings, 7734L);

        assertEquals(first, repeated);
        assertEquals(6, first.placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath).count());
        assertEquals("start", first.placements().getFirst().role());
        assertEquals("boss", first.placements().get(4).role());
        assertEquals("exit", first.placements().get(5).role());
        assertEquals(first.placements().size() - 1, first.links().size());
        assertTrue(first.links().stream()
            .filter(link -> !link.criticalPath()).count() >= 2);
        assertNoOpenConnectors(first, pieces);
        assertNoOverlap(first);
        first.placements().forEach(placement -> {
            assertTrue(placement.minimum().getX() >= 0);
            assertTrue(placement.minimum().getY() >= 0);
            assertTrue(placement.minimum().getZ() >= 0);
            assertTrue(placement.minimum().getX() + placement.size().getX()
                <= first.bounds().getX());
            assertTrue(placement.minimum().getY() + placement.size().getY()
                <= first.bounds().getY());
            assertTrue(placement.minimum().getZ() + placement.size().getZ()
                <= first.bounds().getZ());
        });
    }

    @Test
    void mazeLayoutUsesCorridorsAndJunctionsForItsInteriorPath() {
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(80, 16, 80), 7, 7, 2, 2, 2, 2,
            0.25D, 100, "maze"
        );

        DungeonPiecePlan plan = DungeonPiecePlanner.generate(
            testPieces(), settings, 8128L
        );

        plan.placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath)
            .filter(placement -> !Set.of("start", "boss", "exit")
                .contains(placement.role()))
            .forEach(placement -> assertTrue(
                Set.of("corridor", "junction").contains(placement.role())
            ));
        assertNoOverlap(plan);
    }

    @Test
    void roomsAndCorridorsLayoutUsesTwoRoutePiecesBetweenRooms() {
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(80, 16, 80), 8, 8, 0, 0, 1, 1,
            0.0D, 100, "rooms_and_corridors"
        );

        DungeonPiecePlan plan = DungeonPiecePlanner.generate(
            testPieces(), settings, 9921L
        );

        assertTrue(Set.of("corridor", "junction").contains(plan.placements().get(1).role()));
        assertEquals("corridor", plan.placements().get(2).role());
        assertTrue(Set.of("corridor", "junction")
            .contains(plan.placements().get(3).role()));
        assertNoOverlap(plan);
    }

    @Test
    void roomNetworkUsesTwoOrThreeRoutePiecesBetweenRooms() {
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(112, 16, 112), 7, 7, 1, 1, 1, 1,
            0.0D, 100, "room_network"
        );

        DungeonPiecePlan plan = DungeonPiecePlanner.generate(
            testPieces(), settings, 419L
        );

        assertEquals("corridor", plan.placements().get(1).role());
        assertEquals("corridor", plan.placements().get(2).role());
        assertTrue(Set.of("corridor", "junction")
            .contains(plan.placements().get(3).role()));
        assertNoOverlap(plan);
        assertConnected(plan);
    }

    @Test
    void hubAndSpokesReservesItsBranchesOnTheCentralRoom() {
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(112, 16, 112), 7, 7, 2, 2, 1, 1,
            0.0D, 100, "hub_and_spokes"
        );

        DungeonPiecePlan plan = DungeonPiecePlanner.generate(
            testPieces(), settings, 731L
        );
        DungeonPiecePlan.Placement hub = plan.placements().get(2);
        long branchLinks = plan.links().stream()
            .filter(link -> !link.criticalPath())
            .filter(link -> link.fromIndex() == hub.index()
                || link.toIndex() == hub.index())
            .count();

        assertEquals("junction", hub.role());
        assertEquals(2, branchLinks);
        assertNoOverlap(plan);
        assertConnected(plan);
    }

    @Test
    void runtimeLayoutsUseTheSameRoomNetworkCadence() throws Exception {
        DungeonPieceLayout maze = DungeonPieceLayout.generate(
            pieceDungeon("reject_entry", 7, 7, 0, 100, 80, "maze"),
            testPieces(), 8128L
        );
        DungeonPieceLayout rooms = DungeonPieceLayout.generate(
            pieceDungeon(
                "reject_entry", 8, 8, 0, 100, 80,
                "rooms_and_corridors"
            ),
            testPieces(), 9921L
        );

        for (DungeonPieceLayout layout : List.of(maze, rooms)) {
            assertTrue(Set.of("corridor", "junction")
                .contains(layout.plan().placements().get(1).role()));
            assertTrue(Set.of("corridor", "junction")
                .contains(layout.plan().placements().get(2).role()));
            assertTrue(Set.of("corridor", "junction")
                .contains(layout.plan().placements().get(3).role()));
            assertNoOverlap(layout.plan());
            assertConnected(layout.plan());
        }
    }

    @Test
    void npcSlotDemandExpandsThePassageNetworkWithoutAddingRooms() throws Exception {
        JsonObject base = resourceJson(
            "data/cobbleventure/dungeons/generation_1/rocket_silph_company.json"
        );
        base.getAsJsonObject("topology").add(
            "critical_path_rooms", JsonParser.parseString("[6,6]")
        );
        JsonObject compactRoot = base.deepCopy();
        compactRoot.add("npc_placement", JsonParser.parseString("""
            {"capacity_mode":"fixed","required_slots":9,"minimum_spacing":4}
            """).getAsJsonObject());
        JsonObject expandedRoot = base.deepCopy();
        expandedRoot.add("npc_placement", JsonParser.parseString("""
            {"capacity_mode":"fixed","required_slots":18,"minimum_spacing":4}
            """).getAsJsonObject());
        List<DungeonPieceDefinition> pieces = packagedRocketPieces().stream()
            .filter(piece -> piece.id().contains("/rocket/"))
            .toList();

        DungeonPiecePlanner.Settings compact = DungeonPieceLayout.plannerSettings(
            DungeonDefinition.parse(compactRoot), pieces, false
        );
        DungeonPiecePlanner.Settings expanded = DungeonPieceLayout.plannerSettings(
            DungeonDefinition.parse(expandedRoot), pieces, false
        );

        assertEquals(12, compact.criticalPathMin());
        assertEquals(21, expanded.criticalPathMin());
        assertEquals(3, compact.chamberCount());
        assertEquals(3, expanded.chamberCount());
        assertTrue(expanded.criticalPathMin() > compact.criticalPathMin());
    }

    @Test
    void placesASelectedMultiCellChamberWithoutOverlap() throws Exception {
        List<DungeonPieceDefinition> pieces = packagedRocketPieces().stream()
            .filter(piece -> piece.id().contains("/rocket/"))
            .filter(piece -> !piece.role().equals("treasure"))
            .filter(piece -> !piece.role().equals("room")
                || piece.connectors().size() != 4
                || piece.id().endsWith("/empty_chamber_2x2"))
            .toList();
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(192, 32, 192), 14, 14, 0, 0, 1, 1,
            0.0D, 256, "room_network", "ascending", 2, 2,
            "discrete_floors", 8,
            List.of("cobbleventure:dungeon_piece/rocket/empty_chamber_2x2")
        );

        DungeonPiecePlan plan = DungeonPiecePlanner.generate(
            pieces, settings, 9_041L
        );
        Map<Integer, List<DungeonPiecePlan.Placement>> floors = plan.placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath)
            .filter(placement -> !placement.pieceId().contains("/stairs_"))
            .collect(java.util.stream.Collectors.groupingBy(
                placement -> placement.minimum().getY()
            ));
        long stairs = plan.placements().stream()
            .filter(placement -> placement.pieceId().contains("/stairs_"))
            .count();

        assertEquals(2, stairs);
        assertEquals(3, floors.size());
        assertTrue(floors.values().stream().allMatch(floor -> floor.size() >= 10));
        assertTrue(plan.placements().stream()
            .filter(placement -> placement.role().equals("room"))
            .allMatch(placement -> placement.pieceId()
                .endsWith("/empty_chamber_2x2")),
            "an unselected ordinary chamber was placed");
        DungeonPiecePlan.Placement hub = plan.placements().stream()
            .filter(placement -> placement.pieceId().endsWith("/empty_chamber_2x2"))
            .findFirst().orElseThrow();
        assertEquals(new BlockPos(32, 8, 32), hub.size());
        assertTrue(plan.placements().stream()
            .filter(placement -> placement.pieceId().contains("/stairs_"))
            .allMatch(placement -> placement.pieceId().endsWith("/stairs_up")));
        assertNoOverlap(plan);
        assertConnected(plan);
        assertNoOpenConnectors(plan, pieces);
    }

    @Test
    void assignsMarkerRelativeGateToAReusableRoomSlot() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        );
        assertTrue(stream != null);
        DungeonDefinition definition;
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            root.add("plan", JsonParser.parseString("""
                {"mode":"runtime","seed_policy":"fixed","fallback":"reject_entry",
                 "max_attempts":100}
                """).getAsJsonObject());
            root.add("terrain", JsonParser.parseString("""
                {"mode":"nbt_pieces","piece_pool":"cobbleventure:theme/test",
                 "bounds":[80,16,80]}
                """).getAsJsonObject());
            root.add("spatial_layout", JsonParser.parseString("""
                {"algorithm":"room_scatter","chamber_pieces":[
                  "cobbleventure:dungeon_piece/test/route_room"
                ]}
                """).getAsJsonObject());
            root.add("layout", JsonParser.parseString("""
                {"mode":"rooms_and_corridors","critical_path_rooms":[6,6],
                 "branch_count":[0,0],"branch_depth":[1,1],"loop_chance":0}
                """).getAsJsonObject());
            addSyntheticFallbacks(root);
            root.add("gates", JsonParser.parseString("""
                [{"id":"test_lock","placement":"marker","min":[-1,0,0],
                  "max":[1,2,0],"block":"minecraft:iron_bars",
                  "requires":["west_grunt"]}]
                """).getAsJsonArray());
            definition = DungeonDefinition.parse(root);
        }

        DungeonPieceLayout layout = DungeonPieceLayout.generate(
            definition, testPieces(), 7781L
        );
        Map<DungeonPieceLayout.MarkerKey, BlockPos> markers =
            layout.featureMarkers(definition, 7781L);

        assertTrue(markers.containsKey(
            new DungeonPieceLayout.MarkerKey("gate", "test_lock")
        ));
    }

    @Test
    void rejectsPoolThatCannotFitInsideBounds() {
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(4, 4, 4), 3, 3, 0, 0, 1, 1, 0.0D, 3
        );

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> DungeonPiecePlanner.generate(testPieces(), settings, 1L)
        );

        assertTrue(error.getMessage().contains("planning failed"));
    }

    @Test
    void rejectsPlanThatWouldExceedPieceUsageLimit() {
        List<DungeonPieceDefinition> pieces = testPieces().stream().map(piece ->
            piece.id().endsWith("/corridor")
                ? pieceWithUsage(
                    "corridor", "corridor", northSouthConnectors(), "[]", 0, 1
                ) : piece
        ).toList();
        DungeonPiecePlanner.Settings settings = new DungeonPiecePlanner.Settings(
            new BlockPos(80, 16, 80), 8, 8, 0, 0, 1, 1,
            0.0D, 20, "rooms_and_corridors"
        );

        assertThrows(IllegalStateException.class, () ->
            DungeonPiecePlanner.generate(pieces, settings, 9981L)
        );
    }

    private static void assertNoOverlap(DungeonPiecePlan plan) {
        for (int first = 0; first < plan.placements().size(); first++) {
            DungeonPiecePlan.Placement a = plan.placements().get(first);
            for (int second = first + 1; second < plan.placements().size(); second++) {
                DungeonPiecePlan.Placement b = plan.placements().get(second);
                boolean overlaps = a.minimum().getX() < b.minimum().getX() + b.size().getX()
                    && a.minimum().getX() + a.size().getX() > b.minimum().getX()
                    && a.minimum().getY() < b.minimum().getY() + b.size().getY()
                    && a.minimum().getY() + a.size().getY() > b.minimum().getY()
                    && a.minimum().getZ() < b.minimum().getZ() + b.size().getZ()
                    && a.minimum().getZ() + a.size().getZ() > b.minimum().getZ();
                assertTrue(!overlaps, "Pieces overlap: " + first + " and " + second);
            }
        }
    }

    private static void assertConnected(DungeonPiecePlan plan) {
        Map<Integer, List<Integer>> graph = new java.util.HashMap<>();
        for (DungeonPiecePlan.Link link : plan.links()) {
            graph.computeIfAbsent(link.fromIndex(), ignored -> new ArrayList<>())
                .add(link.toIndex());
            graph.computeIfAbsent(link.toIndex(), ignored -> new ArrayList<>())
                .add(link.fromIndex());
        }
        Set<Integer> visited = new java.util.HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (!visited.add(current)) continue;
            graph.getOrDefault(current, List.of()).forEach(queue::addLast);
        }
        assertEquals(plan.placements().size(), visited.size());
    }

    private static void assertNoOpenConnectors(
        DungeonPiecePlan plan, List<DungeonPieceDefinition> pieces
    ) {
        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
        DungeonPiecePlanValidator.validateNoOpenConnectors(plan, byId);
    }

    private static void assertRequiredFeatures(
        DungeonDefinition dungeon, DungeonPieceLayout layout, long seed
    ) {
        Map<DungeonPieceLayout.MarkerKey, BlockPos> features =
            layout.featureMarkers(dungeon, seed);
        dungeon.encounters().forEach(encounter -> assertTrue(features.containsKey(
            new DungeonPieceLayout.MarkerKey(
                encounter.boss() ? "boss" : "encounter", encounter.id()
            )
        )));
        dungeon.loot().containers().forEach(container -> assertTrue(features.containsKey(
            new DungeonPieceLayout.MarkerKey("loot", container.id())
        )));
        dungeon.support().healingStations().forEach(station -> assertTrue(
            features.containsKey(new DungeonPieceLayout.MarkerKey(
                "healing_station", station.id()
            ))
        ));
        dungeon.objectives().forEach(objective -> assertTrue(features.containsKey(
            new DungeonPieceLayout.MarkerKey("objective", objective.id())
        )));
        dungeon.gates().stream().filter(gate -> gate.placement().equals("marker"))
            .forEach(gate -> assertTrue(features.containsKey(
                new DungeonPieceLayout.MarkerKey("gate", gate.id())
            )));
        if (dungeon.completion().returnTrigger().equals("clear_exit")) {
            assertTrue(features.containsKey(
                new DungeonPieceLayout.MarkerKey("objective", "clear_exit")
            ));
        }
        layout.requiredMarker("entry", null);
        layout.requiredMarker("exit", null);
    }

    private static void assertEncountersUseDistinctSections(
        DungeonDefinition dungeon, DungeonPieceLayout layout, long seed
    ) {
        Map<DungeonPieceLayout.MarkerKey, BlockPos> features =
            layout.featureMarkers(dungeon, seed);
        List<Integer> encounterPlacements = dungeon.encounters().stream()
            .filter(encounter -> !encounter.boss())
            .map(encounter -> features.get(new DungeonPieceLayout.MarkerKey(
                "encounter", encounter.id()
            )))
            .map(position -> layout.plan().placements().stream()
                .filter(placement -> contains(placement, position))
                .map(DungeonPiecePlan.Placement::index)
                .findFirst().orElseThrow())
            .toList();
        Map<Integer, Long> occupancy = encounterPlacements.stream().collect(
            java.util.stream.Collectors.groupingBy(
                index -> index, java.util.stream.Collectors.counting()
            )
        );
        assertTrue(occupancy.size() >= 2,
            "Dungeon encounters used only one section for seed " + seed);
        Set<Integer> passagePlacements = layout.plan().placements().stream()
            .filter(placement -> Set.of("corridor", "junction").contains(placement.role()))
            .map(DungeonPiecePlan.Placement::index).collect(java.util.stream.Collectors.toSet());
        assertTrue(occupancy.entrySet().stream().allMatch(entry ->
                !passagePlacements.contains(entry.getKey()) || entry.getValue() <= 1
            ),
            "More than one encounter occupied a passage piece for seed "
                + seed + ": " + occupancy);
    }

    private static boolean contains(
        DungeonPiecePlan.Placement placement, BlockPos position
    ) {
        BlockPos minimum = placement.minimum();
        BlockPos maximum = minimum.offset(placement.size());
        return position.getX() >= minimum.getX() && position.getX() < maximum.getX()
            && position.getY() >= minimum.getY() && position.getY() < maximum.getY()
            && position.getZ() >= minimum.getZ() && position.getZ() < maximum.getZ();
    }

    private static List<DungeonPieceDefinition> testPieces() {
        return List.of(
            piece("start", "start", northSouthConnectors(), marker("entry")),
            piece("room", "room", fourConnectors(), marker("gate")),
            piece("route_room", "room", northSouthConnectors(), marker("gate")),
            piece("corridor", "corridor", northSouthConnectors(), "[]"),
            piece("junction", "junction", fourConnectors(), marker("gate")),
            piece("t_junction", "junction", threeConnectors(), marker("gate")),
            piece("boss", "boss", northSouthConnectors(), marker("boss", "boss")),
            piece("exit", "exit", terminalConnector(), marker("exit")),
            piece("dead_end", "dead_end", terminalConnector(), "[]"),
            piece("treasure", "treasure", terminalConnector(), "[]"),
            piece("support", "support", terminalConnector(), marker("gate"))
        );
    }

    private List<DungeonPieceDefinition> packagedRocketPieces() throws Exception {
        List<DungeonPieceDefinition> pieces = new ArrayList<>();
        for (String theme : List.of("rocket", "pokemon_tower")) {
            for (String id : List.of(
                "boss", "corner", "corridor", "dead_end", "empty_chamber_1x2",
                "empty_chamber_2x2", "encounter_room", "exit", "junction", "room",
                "route_room", "stairs_down", "stairs_up", "start", "support", "t_junction",
                "treasure"
            )) {
                pieces.add(DungeonPieceDefinition.parse(resourceJson(
                    "data/cobbleventure/dungeon_pieces/" + theme + "/" + id + ".json"
                )));
            }
        }
        return pieces;
    }

    private DungeonDefinition packagedDungeon(String name) throws Exception {
        return DungeonDefinition.parse(resourceJson(
            "data/cobbleventure/dungeons/generation_1/" + name + ".json"
        ));
    }

    private com.google.gson.JsonObject resourceJson(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertTrue(stream != null, "Missing test resource: " + path);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private DungeonDefinition pieceDungeon(
        String fallback, int minimumRooms, int maximumRooms,
        int branchCount, int maxAttempts, int horizontalBounds
    ) throws Exception {
        return pieceDungeon(
            fallback, minimumRooms, maximumRooms, branchCount,
            maxAttempts, horizontalBounds, "critical_path_branches"
        );
    }

    private DungeonDefinition pieceDungeon(
        String fallback, int minimumRooms, int maximumRooms,
        int branchCount, int maxAttempts, int horizontalBounds,
        String layoutMode
    ) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
            "data/cobbleventure/dungeons/generation_1/rocket_power_plant.json"
        );
        assertTrue(stream != null);
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            root.add("plan", JsonParser.parseString("""
                {"mode":"runtime","seed_policy":"fixed","fallback":"%s",
                 "max_attempts":%d}
                """.formatted(fallback, maxAttempts)).getAsJsonObject());
            root.add("terrain", JsonParser.parseString("""
                {"mode":"nbt_pieces","piece_pool":"cobbleventure:theme/test",
                 "bounds":[%d,16,%d]}
                """.formatted(horizontalBounds, horizontalBounds)).getAsJsonObject());
            root.add("layout", JsonParser.parseString("""
                {"mode":"%s","critical_path_rooms":[%d,%d],
                 "branch_count":[%d,%d],"branch_depth":[1,1],"loop_chance":0}
                """.formatted(
                    layoutMode, minimumRooms, maximumRooms, branchCount, branchCount
                )).getAsJsonObject());
            addSyntheticFallbacks(root);
            return DungeonDefinition.parse(root);
        }
    }

    private static void addSyntheticFallbacks(com.google.gson.JsonObject root) {
        int index = 0;
        for (var encounter : root.getAsJsonArray("encounters")) {
            encounter.getAsJsonObject().add(
                "position", JsonParser.parseString(
                    "[%d,1,1]".formatted(index++ % 3)
                )
            );
        }
        for (var station : root.getAsJsonObject("support")
            .getAsJsonArray("healing_stations")) {
            station.getAsJsonObject().add(
                "position", JsonParser.parseString("[2,1,2]")
            );
        }
        index = 0;
        for (var container : root.getAsJsonObject("loot")
            .getAsJsonArray("containers")) {
            container.getAsJsonObject().add(
                "position", JsonParser.parseString(
                    "[%d,1,0]".formatted(index++ % 3)
                )
            );
        }
        root.add("gates", JsonParser.parseString("[]"));
        root.getAsJsonObject("completion").add(
            "clear_exit_position", JsonParser.parseString("[0,1,2]")
        );
    }

    private static DungeonPieceDefinition piece(
        String id, String role, String connectors, String markers
    ) {
        return pieceWithUsage(id, role, connectors, markers, 0, 256);
    }

    private static DungeonPieceDefinition pieceWithUsage(
        String id, String role, String connectors, String markers,
        int minimumPerPlan, int maximumPerPlan
    ) {
        return DungeonPieceDefinition.parse(JsonParser.parseString("""
            {
              "schema_version": 1,
              "piece_id": "cobbleventure:dungeon_piece/test/%s",
              "structure": "cobbleventure:dungeon/test/%s",
              "role": "%s",
              "size": [5, 5, 5],
              "weight": 10,
              "min_per_plan": %d,
              "max_per_plan": %d,
              "allow_rotation": true,
              "tags": ["cobbleventure:theme/test"],
              "connectors": %s,
              "markers": %s
            }
            """.formatted(
                id, id, role, minimumPerPlan, maximumPerPlan,
                connectors, markers
            )).getAsJsonObject());
    }

    private static String marker(String kind) {
        if (kind.equals("gate")) {
            return """
                [{"id":"gate","kind":"gate","position":[2,1,0],
                  "connector":"north"}]
                """;
        }
        return """
            [{"id":"%s","kind":"%s","position":[2,1,2]}]
            """.formatted(kind, kind);
    }

    private static String marker(String kind, String reference) {
        return """
            [{"id":"%s","kind":"%s","position":[2,1,2],"reference":"%s"}]
            """.formatted(kind, kind, reference);
    }

    private static String terminalConnector() {
        return """
            [{"id":"north","position":[2,1,0],"facing":"north",
              "socket":"cobbleventure:socket/test","tags":[]}]
            """;
    }

    private static String northSouthConnectors() {
        return """
            [
              {"id":"north","position":[2,1,0],"facing":"north",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"south","position":[2,1,4],"facing":"south",
               "socket":"cobbleventure:socket/test","tags":[]}
            ]
            """;
    }

    private static String fourConnectors() {
        return """
            [
              {"id":"north","position":[2,1,0],"facing":"north",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"south","position":[2,1,4],"facing":"south",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"west","position":[0,1,2],"facing":"west",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"east","position":[4,1,2],"facing":"east",
               "socket":"cobbleventure:socket/test","tags":[]}
            ]
        """;
    }

    private static String threeConnectors() {
        return """
            [
              {"id":"north","position":[2,1,0],"facing":"north",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"south","position":[2,1,4],"facing":"south",
               "socket":"cobbleventure:socket/test","tags":[]},
              {"id":"east","position":[4,1,2],"facing":"east",
               "socket":"cobbleventure:socket/test","tags":[]}
            ]
            """;
    }

}
