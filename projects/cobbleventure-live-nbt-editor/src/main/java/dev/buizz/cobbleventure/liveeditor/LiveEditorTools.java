package dev.buizz.cobbleventure.liveeditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = LiveNbtEditorMod.MOD_ID)
final class LiveEditorTools {
    private static final String MODE_TAG = "cobbleventureLiveToolMode";
    private static final String NPC_LABEL_TAG = "cobbleventureLiveNpcLabel";
    private static final String DOOR_LABEL_TAG = "cobbleventureLiveDoorLabel";
    private static final String TRANSITION_LABEL_TAG = "cobbleventureLiveTransitionLabel";
    private static final String ARRIVAL_LABEL_TAG = "cobbleventureLiveArrivalLabel";

    private LiveEditorTools() {}

    static LiteralArgumentBuilder<CommandSourceStack> appendCommands(
        LiteralArgumentBuilder<CommandSourceStack> root
    ) {
        return root
            .then(Commands.literal("tool")
                .then(Commands.literal("mode")
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .executes(context -> setMode(
                            context.getSource(),
                            StringArgumentType.getString(context, "mode")
                        ))))
                .then(Commands.literal("npc")
                    .then(Commands.argument("label", StringArgumentType.word())
                        .executes(context -> setLabel(
                            context.getSource(), ToolMode.NPC,
                            NPC_LABEL_TAG, StringArgumentType.getString(context, "label")
                        ))))
                .then(Commands.literal("door")
                    .then(Commands.argument("label", StringArgumentType.word())
                        .executes(context -> setLabel(
                            context.getSource(), ToolMode.NPC,
                            DOOR_LABEL_TAG, StringArgumentType.getString(context, "label")
                        ))))
                .then(Commands.literal("transition")
                    .then(Commands.argument("label", StringArgumentType.word())
                        .executes(context -> setLabel(
                            context.getSource(), ToolMode.NPC,
                            TRANSITION_LABEL_TAG, StringArgumentType.getString(context, "label")
                        ))))
                .then(Commands.literal("arrival")
                    .then(Commands.argument("label", StringArgumentType.word())
                        .executes(context -> setLabel(
                            context.getSource(), ToolMode.ARRIVAL,
                            ARRIVAL_LABEL_TAG, StringArgumentType.getString(context, "label")
                        )))))
            .then(Commands.literal("anchor")
                .then(Commands.literal("list")
                    .executes(context -> listAnchors(context.getSource())))
                .then(Commands.literal("show")
                    .executes(context -> showAnchors(context.getSource()))))
            .then(Commands.literal("palette")
                .executes(context -> givePalette(context.getSource())))
            .then(Commands.literal("worldedit")
                .then(Commands.literal("select")
                    .executes(context -> LiveNbtEditorMod.selectWorldEditRegion(
                        context.getSource().getPlayerOrException()
                    ))));
    }

    static void preparePlayer(ServerPlayer player) {
        if (player.getPersistentData().getString(MODE_TAG).isBlank()) {
            player.getPersistentData().putString(MODE_TAG, ToolMode.NPC.id);
        }
        boolean hasStick = player.getInventory().items.stream()
            .anyMatch(stack -> stack.is(Items.STICK));
        if (!hasStick) {
            ItemStack tool = new ItemStack(Items.STICK);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal(toolName(mode(player))));
            player.getInventory().add(tool);
        }
        int markers = LiveEditorBlocks.givePalette(player);
        if (markers > 0) {
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 굴착 공기 마커 " + markers + "개를 지급했습니다. "
                    + "흙·산을 비울 공간에 채우면 실제 배치 때 공기로 바뀝니다."
            ));
        }
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] 편집 막대기: 문/베리어 우클릭=출입구, "
                + "일반 블록 우클릭=NPC 위치, 웅크리기+좌클릭=삭제"
        ));
    }

    private static int givePalette(CommandSourceStack source) throws CommandSyntaxException {
        int markers = LiveEditorBlocks.givePalette(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal(
            markers > 0
                ? "[Live NBT Editor] 굴착 공기 마커 " + markers + "개와 편집 블록을 지급했습니다."
                : "[Live NBT Editor] 굴착 공기 마커가 이미 64개 이상 있습니다."
        ), false);
        return 1;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!validToolEvent(event.getHand(), event.getEntity() instanceof ServerPlayer player
            ? player : null, event.getLevel().isClientSide())) return;
        ServerPlayer player = (ServerPlayer) event.getEntity();
        if (!editable(player, event.getPos())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        try {
            ServerLevel level = player.serverLevel();
            BlockPos door = lowerDoor(level, event.getPos());
            LiveEditorNetwork.openAnchorEditor(
                player, event.getPos(), door != null,
                level.getBlockState(event.getPos()).is(Blocks.BARRIER)
            );
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] " + error.getMessage()
            ));
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!validToolEvent(event.getHand(), event.getEntity() instanceof ServerPlayer player
            ? player : null, event.getLevel().isClientSide())) return;
        ServerPlayer player = (ServerPlayer) event.getEntity();
        if (!player.isShiftKeyDown()
            || !player.serverLevel().dimension().equals(LiveNbtEditorMod.EDIT_LEVEL)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        ToolMode next = mode(player).next();
        applyMode(player, next);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !player.isShiftKeyDown()
            || !player.getMainHandItem().is(Items.STICK)
            || !editable(player, event.getPos())) return;
        event.setCanceled(true);
        int removed = removeAt(player.serverLevel(), event.getPos());
        if (removed > 0) LiveNbtEditorMod.editorMetadataChanged(player.getServer());
        player.sendSystemMessage(Component.literal(
            removed == 0
                ? "[Live NBT Editor] 이 위치에는 마커가 없습니다."
                : "[Live NBT Editor] 마커 " + removed + "개를 삭제했습니다."
        ));
    }

    private static boolean validToolEvent(
        InteractionHand hand, ServerPlayer player, boolean clientSide
    ) {
        return hand == InteractionHand.MAIN_HAND && !clientSide && player != null
            && player.getMainHandItem().is(Items.STICK);
    }

    private static boolean editable(ServerPlayer player, BlockPos position) {
        if (!player.serverLevel().dimension().equals(LiveNbtEditorMod.EDIT_LEVEL)) return false;
        if (!LiveNbtEditorMod.hasActiveStructure()) {
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 먼저 웹에서 NBT를 여세요."
            ));
            return false;
        }
        if (!inside(relative(position))) {
            player.sendSystemMessage(Component.literal(
                "[Live NBT Editor] 현재 NBT 편집 범위 밖입니다."
            ));
            return false;
        }
        return true;
    }

    static void applyAnchor(
        ServerPlayer player, BlockPos clicked, String type, String requestedLabel
    ) {
        if (!editable(player, clicked)) return;
        String label = requestedLabel.trim().toLowerCase(Locale.ROOT);
        if (!label.matches("[a-z0-9][a-z0-9_]*")) {
            throw new IllegalStateException("라벨은 영문 소문자, 숫자와 밑줄만 사용할 수 있습니다.");
        }
        ServerLevel level = player.serverLevel();
        switch (type) {
            case "delete" -> {
                int removed = removeAt(level, clicked);
                player.sendSystemMessage(Component.literal(
                    "[Live NBT Editor] 마커 " + removed + "개를 삭제했습니다."
                ));
            }
            case "door" -> {
                BlockPos door = lowerDoor(level, clicked);
                if (door == null) throw new IllegalStateException("문 마커는 실제 문에 지정해야 합니다.");
                player.getPersistentData().putString(DOOR_LABEL_TAG, label);
                setDoor(player, canonicalDoor(level, door));
            }
            case "transition" -> {
                if (!level.getBlockState(clicked).is(Blocks.BARRIER)) {
                    throw new IllegalStateException("접촉 전환은 베리어에 지정해야 합니다.");
                }
                player.getPersistentData().putString(TRANSITION_LABEL_TAG, label);
                setTransition(player, clicked);
            }
            case "npc_position" -> setPoint(player, clicked, ToolMode.NPC, label);
            case "arrival" -> setPoint(player, clicked, ToolMode.ARRIVAL, label);
            case "interaction_point" -> setPoint(player, clicked, ToolMode.INTERACTION, label);
            case "patrol_point" -> setPoint(player, clicked, ToolMode.PATROL, label);
            case "exterior_spawn" -> setPoint(player, clicked, ToolMode.SPAWN, label);
            default -> throw new IllegalStateException("지원하지 않는 앵커 종류입니다: " + type);
        }
        LiveNbtEditorMod.editorMetadataChanged(player.getServer());
    }

    private static void setDoor(ServerPlayer player, BlockPos door) {
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(door);
        Direction safeSide = playerSide(player, door);
        if (!inside(relative(door.relative(safeSide)))) safeSide = safeSide.getOpposite();
        String label = label(player, DOOR_LABEL_TAG, "door");
        JsonObject anchor = baseAnchor(label, "door", relative(door));
        anchor.add("safe_spawn", positionJson(relative(door.relative(safeSide))));
        anchor.addProperty("door_facing", state.getValue(DoorBlock.FACING).getName());
        anchor.addProperty("safe_side", safeSide.getName());
        replaceAnchor(anchor, "door", label, relative(door));
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] 문 마커 저장: " + label + "=" + format(relative(door))
        ));
    }

    private static void setTransition(ServerPlayer player, BlockPos clicked) {
        Set<BlockPos> region = connectedBarrierRegion(player.serverLevel(), clicked);
        String label = label(player, TRANSITION_LABEL_TAG, "transition");
        JsonObject anchor = baseAnchor(label, "transition", relative(clicked));
        BlockPos safe = relative(player.blockPosition());
        if (!inside(safe)) safe = relative(clicked).relative(player.getDirection().getOpposite());
        anchor.add("safe_spawn", positionJson(safe));
        anchor.addProperty("facing", player.getDirection().getName());
        replaceAnchor(anchor, "transition", label, relative(clicked));
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] 접촉 전환 마커 저장: " + label
                + " · 베리어 " + region.size() + "개"
        ));
    }

    private static void setPoint(ServerPlayer player, BlockPos clicked, ToolMode mode) {
        setPoint(player, clicked, mode, null);
    }

    private static void setPoint(
        ServerPlayer player, BlockPos clicked, ToolMode mode, String explicitLabel
    ) {
        BlockPos worldPosition = mode == ToolMode.INTERACTION ? clicked : clicked.above();
        BlockPos position = relative(worldPosition);
        if (!inside(position)) throw new IllegalStateException("마커 위치가 편집 범위 밖입니다.");
        String type;
        String label;
        switch (mode) {
            case NPC -> {
                type = "npc_position";
                label = explicitLabel == null ? label(player, NPC_LABEL_TAG, "npc") : explicitLabel;
            }
            case ARRIVAL -> {
                type = "arrival";
                label = explicitLabel == null
                    ? label(player, ARRIVAL_LABEL_TAG, "arrival") : explicitLabel;
            }
            case SPAWN -> {
                type = "exterior_spawn";
                label = explicitLabel == null ? "exterior_spawn" : explicitLabel;
            }
            case PATROL -> {
                type = "patrol_point";
                label = explicitLabel == null ? nextLabel("patrol") : explicitLabel;
            }
            case INTERACTION -> {
                type = "interaction_point";
                label = explicitLabel == null ? nextLabel("interaction") : explicitLabel;
            }
            default -> throw new IllegalStateException("지원하지 않는 도구 모드입니다.");
        }
        JsonObject anchor = baseAnchor(label, type, position);
        anchor.addProperty("facing", player.getDirection().getName());
        replaceAnchor(anchor, type, label, position);
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] " + mode.label + " 저장: " + label + "=" + format(position)
        ));
    }

    private static JsonObject baseAnchor(String label, String type, BlockPos position) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("id", label);
        anchor.addProperty("label", label);
        anchor.addProperty("type", type);
        anchor.add("position", positionJson(position));
        return anchor;
    }

    private static void replaceAnchor(
        JsonObject replacement, String type, String label, BlockPos position
    ) {
        JsonArray anchors = anchors();
        for (int index = anchors.size() - 1; index >= 0; index--) {
            JsonElement element = anchors.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject anchor = element.getAsJsonObject();
            if (label.equals(anchorLabel(anchor))
                || (type.equals(anchorType(anchor)) && position.equals(anchorPosition(anchor)))) {
                anchors.remove(index);
            }
        }
        anchors.add(replacement);
    }

    private static int removeAt(ServerLevel level, BlockPos clicked) {
        Set<BlockPos> targets = new HashSet<>();
        targets.add(relative(clicked));
        targets.add(relative(clicked.above()));
        BlockPos lower = lowerDoor(level, clicked);
        if (lower != null) {
            lower = canonicalDoor(level, lower);
            targets.add(relative(lower));
            BlockPos paired = pairedDoor(level, lower);
            if (paired != null) targets.add(relative(paired));
        }
        JsonArray anchors = anchors();
        int removed = 0;
        for (int index = anchors.size() - 1; index >= 0; index--) {
            JsonElement element = anchors.get(index);
            if (element.isJsonObject() && targets.contains(anchorPosition(element.getAsJsonObject()))) {
                anchors.remove(index);
                removed++;
            }
        }
        return removed;
    }

    private static JsonArray anchors() {
        JsonObject metadata = LiveNbtEditorMod.activeStructureMetadata();
        if (!metadata.has("anchors") || !metadata.get("anchors").isJsonArray()) {
            metadata.add("anchors", new JsonArray());
        }
        return metadata.getAsJsonArray("anchors");
    }

    private static int setMode(CommandSourceStack source, String requested)
        throws CommandSyntaxException {
        ToolMode selected = ToolMode.parseRequested(requested);
        if (selected == null) {
            source.sendFailure(Component.literal(
                "[Live NBT Editor] 모드: npc, arrival, interaction, patrol, spawn"
            ));
            return 0;
        }
        applyMode(source.getPlayerOrException(), selected);
        return 1;
    }

    private static int setLabel(
        CommandSourceStack source, ToolMode selected, String tag, String value
    ) throws CommandSyntaxException {
        if (!value.matches("[a-z0-9][a-z0-9_]*")) {
            source.sendFailure(Component.literal(
                "[Live NBT Editor] 라벨은 영문 소문자, 숫자와 밑줄만 사용할 수 있습니다."
            ));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        player.getPersistentData().putString(tag, value);
        applyMode(player, selected);
        source.sendSuccess(() -> Component.literal(
            "[Live NBT Editor] 라벨 설정: " + value
        ), false);
        return 1;
    }

    private static int listAnchors(CommandSourceStack source) {
        if (!LiveNbtEditorMod.hasActiveStructure()) {
            source.sendFailure(Component.literal("[Live NBT Editor] 활성 NBT가 없습니다."));
            return 0;
        }
        List<String> values = anchors().asList().stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .map(anchor -> anchorType(anchor) + ":" + anchorLabel(anchor)
                + "=" + format(anchorPosition(anchor)))
            .toList();
        source.sendSuccess(() -> Component.literal(
            "[Live NBT Editor] 앵커 " + values.size() + "개: "
                + (values.isEmpty() ? "없음" : String.join(", ", values))
        ), false);
        return values.size();
    }

    private static int showAnchors(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!LiveNbtEditorMod.hasActiveStructure()) return 0;
        source.sendSuccess(() -> Component.literal(
            "[Live NBT Editor] 앵커 " + anchors().size()
                + "개가 입체 마커로 항상 표시됩니다."
        ), false);
        return anchors().size();
    }

    private static Set<BlockPos> connectedBarrierRegion(ServerLevel level, BlockPos seed) {
        Set<BlockPos> result = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(seed.immutable());
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            if (!inside(relative(current)) || !level.getBlockState(current).is(Blocks.BARRIER)
                || !result.add(current)) continue;
            if (result.size() > 4096) {
                throw new IllegalStateException("연결된 베리어 영역은 4096블록 이하여야 합니다.");
            }
            for (Direction direction : Direction.values()) pending.add(current.relative(direction));
        }
        return Set.copyOf(result);
    }

    private static BlockPos lowerDoor(ServerLevel level, BlockPos clicked) {
        BlockState state = level.getBlockState(clicked);
        if (!(state.getBlock() instanceof DoorBlock)) return null;
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
            ? clicked.below() : clicked;
    }

    static BlockPos pairedDoor(ServerLevel level, BlockPos lower) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) return null;
        Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        for (Direction side : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
            BlockPos candidate = lower.relative(side);
            BlockState other = level.getBlockState(candidate);
            if (other.getBlock() == state.getBlock()
                && other.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && other.getValue(DoorBlock.FACING) == facing
                && other.getValue(DoorBlock.HINGE) != hinge) return candidate;
        }
        return null;
    }

    private static BlockPos canonicalDoor(ServerLevel level, BlockPos lower) {
        BlockPos paired = pairedDoor(level, lower);
        if (paired == null) return lower;
        if (lower.getX() != paired.getX()) return lower.getX() < paired.getX() ? lower : paired;
        if (lower.getY() != paired.getY()) return lower.getY() < paired.getY() ? lower : paired;
        return lower.getZ() <= paired.getZ() ? lower : paired;
    }

    private static Direction playerSide(ServerPlayer player, BlockPos door) {
        double x = player.getX() - (door.getX() + 0.5D);
        double z = player.getZ() - (door.getZ() + 0.5D);
        if (Math.abs(x) > Math.abs(z)) return x >= 0 ? Direction.EAST : Direction.WEST;
        return z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static BlockPos relative(BlockPos world) {
        return world.subtract(LiveNbtEditorMod.ORIGIN);
    }

    private static boolean inside(BlockPos relative) {
        Vec3i size = LiveNbtEditorMod.activeStructureSize();
        return relative.getX() >= 0 && relative.getX() < size.getX()
            && relative.getY() >= 0 && relative.getY() < size.getY()
            && relative.getZ() >= 0 && relative.getZ() < size.getZ();
    }

    private static JsonArray positionJson(BlockPos position) {
        JsonArray value = new JsonArray();
        value.add(position.getX());
        value.add(position.getY());
        value.add(position.getZ());
        return value;
    }

    private static BlockPos anchorPosition(JsonObject anchor) {
        if (!anchor.has("position") || !anchor.get("position").isJsonArray()) {
            return new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
        JsonArray value = anchor.getAsJsonArray("position");
        if (value.size() != 3) return new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        return new BlockPos(value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt());
    }

    private static String anchorType(JsonObject anchor) {
        return anchor.has("type") ? anchor.get("type").getAsString() : "unknown";
    }

    private static String anchorLabel(JsonObject anchor) {
        if (anchor.has("label")) return anchor.get("label").getAsString();
        return anchor.has("id") ? anchor.get("id").getAsString() : "unnamed";
    }

    private static String nextLabel(String prefix) {
        int index = 1;
        Set<String> used = new HashSet<>();
        for (JsonElement element : anchors()) {
            if (element.isJsonObject()) used.add(anchorLabel(element.getAsJsonObject()));
        }
        while (used.contains(prefix + "_" + index)) index++;
        return prefix + "_" + index;
    }

    private static String label(ServerPlayer player, String tag, String fallback) {
        String value = player.getPersistentData().getString(tag);
        return value.isBlank() ? fallback : value;
    }

    private static ToolMode mode(ServerPlayer player) {
        ToolMode value = ToolMode.parseRequested(player.getPersistentData().getString(MODE_TAG));
        return value == null ? ToolMode.NPC : value;
    }

    private static void applyMode(ServerPlayer player, ToolMode mode) {
        player.getPersistentData().putString(MODE_TAG, mode.id);
        ItemStack held = player.getMainHandItem();
        if (held.is(Items.STICK)) {
            held.set(DataComponents.CUSTOM_NAME, Component.literal(toolName(mode)));
        }
        player.sendSystemMessage(Component.literal(
            "[Live NBT Editor] 막대기 모드: " + mode.label
        ));
    }

    private static String toolName(ToolMode mode) {
        return "라이브 NBT 편집 막대기 · " + mode.label;
    }

    private static String format(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private enum ToolMode {
        NPC("npc", "NPC 위치"),
        ARRIVAL("arrival", "도착 지점"),
        INTERACTION("interaction", "상호작용 지점"),
        PATROL("patrol", "순찰 지점"),
        SPAWN("spawn", "기본 스폰");

        private final String id;
        private final String label;

        ToolMode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        ToolMode next() {
            ToolMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        static ToolMode parseRequested(String value) {
            if (value == null) return null;
            String normalized = value.toLowerCase(Locale.ROOT);
            for (ToolMode mode : values()) if (mode.id.equals(normalized)) return mode;
            return null;
        }
    }
}
