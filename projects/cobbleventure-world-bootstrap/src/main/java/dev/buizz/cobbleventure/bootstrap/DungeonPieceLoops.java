package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Adds optional links between already adjacent free connectors in a valid piece plan. */
final class DungeonPieceLoops {
    private DungeonPieceLoops() {}

    static DungeonPiecePlan add(
        DungeonPiecePlan plan,
        Map<String, DungeonPieceDefinition> definitions,
        double chance,
        long seed
    ) {
        if (chance <= 0.0D) return plan;
        if (chance > 1.0D) {
            throw new IllegalArgumentException("Dungeon loop chance exceeds one");
        }
        Set<ConnectorKey> used = new HashSet<>();
        for (DungeonPiecePlan.Link link : plan.links()) {
            used.add(new ConnectorKey(link.fromIndex(), link.fromConnector()));
            used.add(new ConnectorKey(link.toIndex(), link.toConnector()));
        }
        List<DungeonPiecePlan.Link> links = new ArrayList<>(plan.links());
        Random random = new Random(seed ^ 0x4C4F4F50534C494EL);
        for (int firstIndex = 0; firstIndex < plan.placements().size(); firstIndex++) {
            DungeonPiecePlan.Placement first = plan.placements().get(firstIndex);
            DungeonPieceDefinition firstDefinition = definitions.get(first.pieceId());
            if (firstDefinition == null) continue;
            for (int secondIndex = firstIndex + 1;
                secondIndex < plan.placements().size(); secondIndex++) {
                DungeonPiecePlan.Placement second = plan.placements().get(secondIndex);
                DungeonPieceDefinition secondDefinition = definitions.get(second.pieceId());
                if (secondDefinition == null) continue;
                for (DungeonPieceDefinition.Connector from : firstDefinition.connectors()) {
                    ConnectorKey fromKey = new ConnectorKey(firstIndex, from.id());
                    if (used.contains(fromKey)) continue;
                    Direction fromFacing = first.rotation().rotate(from.facing());
                    BlockPos fromPosition = connectorPosition(first, from);
                    for (DungeonPieceDefinition.Connector to : secondDefinition.connectors()) {
                        ConnectorKey toKey = new ConnectorKey(secondIndex, to.id());
                        if (used.contains(toKey)
                            || fromFacing.getOpposite()
                                != second.rotation().rotate(to.facing())
                            || !compatible(from, to)
                            || !fromPosition.relative(fromFacing).equals(
                                connectorPosition(second, to)
                            )
                            || random.nextDouble() > chance) {
                            continue;
                        }
                        links.add(new DungeonPiecePlan.Link(
                            firstIndex, from.id(), secondIndex, to.id(), false
                        ));
                        used.add(fromKey);
                        used.add(toKey);
                        break;
                    }
                }
            }
        }
        return new DungeonPiecePlan(
            plan.seed(), plan.bounds(), plan.placements(), List.copyOf(links)
        );
    }

    private static BlockPos connectorPosition(
        DungeonPiecePlan.Placement placement,
        DungeonPieceDefinition.Connector connector
    ) {
        return placement.templateOrigin().offset(StructureTemplate.transform(
            connector.position(), Mirror.NONE, placement.rotation(), BlockPos.ZERO
        ));
    }

    private static boolean compatible(
        DungeonPieceDefinition.Connector first,
        DungeonPieceDefinition.Connector second
    ) {
        if (!first.socket().equals(second.socket())) return false;
        return first.tags().isEmpty() || second.tags().isEmpty()
            || first.tags().stream().anyMatch(second.tags()::contains);
    }

    private record ConnectorKey(int placementIndex, String connectorId) {}
}
