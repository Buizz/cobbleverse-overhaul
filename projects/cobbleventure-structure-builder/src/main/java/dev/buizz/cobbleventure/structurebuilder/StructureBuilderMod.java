package dev.buizz.cobbleventure.structurebuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(StructureBuilderMod.MOD_ID)
@EventBusSubscriber(modid = StructureBuilderMod.MOD_ID)
public final class StructureBuilderMod {
    public static final String MOD_ID = "cobbleventure_structure_builder";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PREPARE_PROPERTY = "cobbleventure.builder.prepareWorld";
    private static final String DATA_FILE = "cobbleventure_structure_builder";
    private static final String TOOL_MODE_TAG = "cobbleventureBuilderToolMode";
    private static final String NPC_LABEL_TAG = "cobbleventureBuilderNpcLabel";
    private static final String DOOR_LABEL_TAG = "cobbleventureBuilderDoorLabel";
    private static final String ARRIVAL_LABEL_TAG = "cobbleventureBuilderArrivalLabel";
    private static final ResourceLocation CATALOG = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure_builder", "structure_builder/catalog.json"
    );
    private static final int ORIGIN_X = -320;
    private static final int ORIGIN_Z = -280;
    private static final int INTERIOR_ORIGIN_X = 512;
    private static final int INTERIOR_ORIGIN_Z = -280;
    private static final int INTERIOR_CELL_SIZE = 96;
    private static int shutdownTicks = -1;

    public StructureBuilderMod(IEventBus modBus) {
        BuilderStrengthBlocks.register(modBus);
        BuilderEditorNetwork.register(modBus);
        if (FMLEnvironment.dist.isClient()) {
            dev.buizz.cobbleventure.structurebuilder.client.BuilderEditorClient.register(modBus);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        configureWorld(event.getServer());
        if (!Boolean.getBoolean(PREPARE_PROPERTY)) {
            return;
        }
        event.getServer().saveEverything(true, true, true);
        shutdownTicks = 20;
        LOGGER.info("Blank Cobbleventure structure builder world is ready for packaging");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (shutdownTicks < 0) {
            return;
        }
        if (--shutdownTicks == 0) {
            event.getServer().saveEverything(true, true, true);
            event.getServer().halt(false);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        shutdownTicks = -1;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || player.serverLevel() != server.overworld()) {
            return;
        }
        configureWorld(server);
        player.setGameMode(GameType.CREATIVE);
        server.getPlayerList().op(player.getGameProfile());
        server.getCommands().sendCommands(player);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 건축 명령 권한을 활성화했습니다. WorldEdit 명령을 사용할 수 있습니다."
        ));
        giveEditorStick(player);
        try {
            Catalog catalog = loadCatalog(server);
            BuilderData data = data(server);
            if (!data.prepared) {
                prepareLayout(server.overworld(), catalog, data);
                player.sendSystemMessage(Component.literal(
                    "[Structure Builder] 구조물 " + catalog.entries().size()
                        + "개를 건축 부지에 배치했습니다."
                ));
            } else if (!data.catalogHash.equals(catalog.catalogHash())) {
                player.sendSystemMessage(Component.literal(
                    "[Structure Builder] 원본 NBT가 변경되었습니다. 편집 내용을 보존했으며 "
                        + "필요할 때 /cobbleventure_builder load confirm을 사용하세요."
                ));
            }
            BlockPos spawn = spawnPosition(data.groundY);
            player.teleportTo(
                server.overworld(), spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                0.0F, 0.0F
            );
        } catch (BuilderException error) {
            player.sendSystemMessage(Component.literal(
                "[Structure Builder] 초기화 실패: " + error.getMessage()
            ));
            LOGGER.error("Structure builder initialization failed", error);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || event.getLevel().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !player.getMainHandItem().is(Items.STICK)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        try {
            Catalog catalog = loadCatalog(player.getServer());
            BuilderData data = data(player.getServer());
            requirePrepared(data);
            BlockPos door = lowerDoorPosition(player.serverLevel(), event.getPos());
            BuilderEditorNetwork.openAnchorEditor(
                player,
                door == null ? event.getPos() : canonicalDoorPosition(player.serverLevel(), door),
                door != null
            );
        } catch (BuilderException error) {
            player.sendSystemMessage(Component.literal(
                "[Structure Builder] " + error.getMessage()
            ));
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || event.getLevel().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !player.isShiftKeyDown()
            || !player.getMainHandItem().is(Items.STICK)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 실제 문 우클릭=연결 문, 일반 블록 우클릭=NPC 위치, "
                + "웅크리기+좌클릭=설정 삭제"
        ));
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !player.isShiftKeyDown()
            || !player.getMainHandItem().is(Items.STICK)) {
            return;
        }
        event.setCanceled(true);
        try {
            Catalog catalog = loadCatalog(player.getServer());
            BuilderData data = data(player.getServer());
            BlockPos selected = lowerDoorPosition(player.serverLevel(), event.getPos());
            if (selected == null) {
                selected = event.getPos().above();
            } else {
                selected = canonicalDoorPosition(player.serverLevel(), selected);
            }
            EditContext edit = findContext(catalog, data, selected);
            int removed = data.removeAnchorsAt(edit.key(), selected.subtract(edit.origin()));
            BlockPos paired = pairedDoorPosition(player.serverLevel(), selected);
            if (paired != null) {
                removed += data.removeAnchorsAt(edit.key(), paired.subtract(edit.origin()));
            }
            removed += data.removeNpcAnchorsAt(edit.key(), selected.subtract(edit.origin()));
            removed += data.removePointAnchorsAt(edit.key(), selected.subtract(edit.origin()));
            removed += data.removePointAnchorsAt(
                edit.key(), selected.below().subtract(edit.origin())
            );
            player.sendSystemMessage(Component.literal(
                removed == 0
                    ? "[Structure Builder] 이 문에는 지정된 출입구가 없습니다."
                    : "[Structure Builder] 출입구 지정을 해제했습니다."
            ));
        } catch (BuilderException error) {
            player.sendSystemMessage(Component.literal(
                "[Structure Builder] " + error.getMessage()
            ));
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cobbleventure_builder")
                .then(Commands.literal("status")
                    .executes(context -> status(context.getSource())))
                .then(Commands.literal("load")
                    .then(Commands.literal("confirm")
                        .executes(context -> loadAll(context.getSource()))))
                .then(Commands.literal("save")
                    .then(Commands.literal("all")
                        .executes(context -> saveAll(context.getSource())))
                    .then(Commands.argument("structure", StringArgumentType.greedyString())
                        .executes(context -> saveOne(
                            context.getSource(),
                            StringArgumentType.getString(context, "structure")
                        ))))
                .then(Commands.literal("tp")
                    .then(Commands.argument("structure", StringArgumentType.greedyString())
                        .executes(context -> teleportToPlot(
                            context.getSource(),
                            StringArgumentType.getString(context, "structure")
                        ))))
                .then(Commands.literal("exterior")
                    .then(Commands.literal("list")
                        .executes(context -> listExteriors(context.getSource())))
                    .then(Commands.literal("tp")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                            .executes(context -> teleportToPlot(
                                context.getSource(),
                                StringArgumentType.getString(context, "id")
                            )))))
                .then(Commands.literal("tool")
                    .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                            .executes(context -> setToolModeCommand(
                                context.getSource(),
                                StringArgumentType.getString(context, "mode")
                            ))))
                    .then(Commands.literal("npc")
                        .then(Commands.argument("label", StringArgumentType.word())
                            .executes(context -> setNpcLabelCommand(
                                context.getSource(),
                                StringArgumentType.getString(context, "label")
                            ))))
                    .then(Commands.literal("leader")
                        .executes(context -> setNpcLabelCommand(
                            context.getSource(), "leader"
                        )))
                    .then(Commands.literal("door")
                        .then(Commands.argument("label", StringArgumentType.word())
                            .executes(context -> setDoorLabelCommand(
                                context.getSource(),
                                StringArgumentType.getString(context, "label")
                            ))))
                    .then(Commands.literal("arrival")
                        .then(Commands.argument("label", StringArgumentType.word())
                            .executes(context -> setArrivalLabelCommand(
                                context.getSource(),
                                StringArgumentType.getString(context, "label")
                            )))))
                .then(Commands.literal("anchor")
                    .then(Commands.literal("list")
                        .executes(context -> listAnchors(context.getSource())))
                    .then(Commands.literal("show")
                        .executes(context -> showAnchors(context.getSource()))))
                .then(Commands.literal("interior")
                    .then(Commands.literal("list")
                        .executes(context -> listInteriors(context.getSource())))
                    .then(Commands.literal("tp")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> teleportToInterior(
                                context.getSource(),
                                StringArgumentType.getString(context, "id")
                            ))))
                    .then(Commands.literal("save")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> saveInterior(
                                context.getSource(),
                                StringArgumentType.getString(context, "id")
                            ))))
                    .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.literal("confirm")
                                .executes(context -> deleteInterior(
                                    context.getSource(),
                                    StringArgumentType.getString(context, "id")
                                )))))
                    .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("width", IntegerArgumentType.integer(5, 80))
                                .then(Commands.argument("depth", IntegerArgumentType.integer(5, 80))
                                    .then(Commands.argument("floor_height", IntegerArgumentType.integer(3, 12))
                                        .then(Commands.argument("floors", IntegerArgumentType.integer(1, 8))
                                            .executes(context -> createInterior(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "width"),
                                                IntegerArgumentType.getInteger(context, "depth"),
                                                IntegerArgumentType.getInteger(context, "floor_height"),
                                                IntegerArgumentType.getInteger(context, "floors")
                                            )))))))))
        );
    }

    private static int status(CommandSourceStack source) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] 구조물=" + catalog.entries().size()
                        + ", 새 내부=" + data.interiorCount()
                        + ", 배치=" + data.prepared
                        + ", 카탈로그="
                        + (data.catalogHash.equals(catalog.catalogHash()) ? "최신" : "변경됨")
                ),
                false
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int loadAll(CommandSourceStack source) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            prepareLayout(source.getServer().overworld(), catalog, data);
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] 원본 NBT로 모든 부지를 다시 불러왔습니다."
                ),
                true
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int saveAll(CommandSourceStack source) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            requirePrepared(data);
            int saved = 0;
            List<String> removedDoorAnchors = new ArrayList<>();
            for (PlannedEntry planned : editablePlan(catalog, data)) {
                if (planned.entry().category().equals("interiors")
                    && data.interior(planned.entry().label()).isPresent()) {
                    continue;
                }
                removedDoorAnchors.addAll(
                    export(source.getServer().overworld(), catalog, planned)
                );
                saved++;
            }
            for (InteriorPlot interior : data.interiors()) {
                removedDoorAnchors.addAll(
                    exportInterior(source.getServer().overworld(), interior)
                );
                saved++;
            }
            int count = saved;
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] " + count + "개 부지를 NBT로 내보냈습니다."
                        + removedDoorAnchorNotice(removedDoorAnchors)
                ),
                true
            );
            return saved;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int saveOne(CommandSourceStack source, String requested) {
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            requirePrepared(data);
            PlannedEntry planned = find(catalog, data, requested);
            Optional<InteriorPlot> resized = planned.entry().category().equals("interiors")
                ? data.interior(planned.entry().label()) : Optional.empty();
            List<String> removedDoorAnchors;
            if (resized.isPresent()) {
                removedDoorAnchors = exportInterior(
                    source.getServer().overworld(), resized.get()
                );
            } else {
                removedDoorAnchors = export(
                    source.getServer().overworld(), catalog, planned
                );
            }
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] NBT 내보내기 완료: " + planned.entry().source()
                        + removedDoorAnchorNotice(removedDoorAnchors)
                ),
                true
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int teleportToPlot(
        CommandSourceStack source, String requested
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        try {
            Catalog catalog = loadCatalog(source.getServer());
            BuilderData data = data(source.getServer());
            requirePrepared(data);
            PlannedEntry planned = find(catalog, data, requested);
            player.teleportTo(
                source.getServer().overworld(),
                planned.cellX() + catalog.cellSize() / 2.0D,
                data.groundY + 1.0D,
                planned.cellZ() + 4.5D,
                180.0F, 0.0F
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int listExteriors(CommandSourceStack source) {
        BuilderData builderData = data(source.getServer());
        List<String> values = plan(loadCatalog(source.getServer()), builderData.groundY).stream()
            .filter(planned -> !planned.entry().category().equals("interiors"))
            .map(planned -> planned.entry().label())
            .toList();
        source.sendSuccess(
            () -> Component.literal(
                "[Structure Builder] 외부 공간 " + values.size() + "개: "
                    + (values.isEmpty() ? "없음" : String.join(", ", values))
            ), false
        );
        return values.size();
    }

    private static int fail(CommandSourceStack source, BuilderException error) {
        source.sendFailure(Component.literal("[Structure Builder] " + error.getMessage()));
        return 0;
    }

    static EditorSnapshot editorSnapshot(ServerPlayer player) {
        Catalog catalog = loadCatalog(player.getServer());
        BuilderData builderData = data(player.getServer());
        EditContext current;
        try {
            current = findContext(catalog, builderData, player.blockPosition());
        } catch (BuilderException ignored) {
            current = new EditContext("", "체크무늬 작업장", player.blockPosition(), new Vec3i(0, 0, 0), false);
        }
        List<EditorSpace> spaces = new ArrayList<>();
        for (PlannedEntry planned : editablePlan(catalog, builderData)) {
            if (planned.entry().category().equals("interiors")
                && builderData.interior(planned.entry().label()).isPresent()) {
                continue;
            }
            boolean interior = planned.entry().category().equals("interiors");
            spaces.add(new EditorSpace(
                planned.entry().exportId(), planned.entry().label(),
                interior, planned.origin(), planned.entry().size(), interior,
                planned.entry().interior() == null ? planned.entry().size().getY()
                    : planned.entry().interior().floorHeight(),
                planned.entry().interior() == null ? 1 : planned.entry().interior().floors()
            ));
        }
        for (InteriorPlot plot : builderData.interiors()) {
            spaces.add(new EditorSpace(
                plot.key(), plot.id(), true, plot.origin(), plot.size(), true,
                plot.floorHeight(), plot.floors()
            ));
        }
        List<EditorMarker> markers = new ArrayList<>();
        if (!current.key().isBlank()) {
            BlockPos currentOrigin = current.origin();
            builderData.anchors(current.key()).forEach(anchor -> {
                BlockPos position = currentOrigin.offset(anchor.position());
                BlockPos pairedPosition = pairedDoorPosition(player.serverLevel(), position);
                markers.add(new EditorMarker(
                    anchor.label(), anchor.role(), position, pairedPosition
                ));
            });
            builderData.npcAnchors(current.key()).forEach(anchor -> markers.add(
                new EditorMarker(
                    anchor.label(), "npc_position", currentOrigin.offset(anchor.position()), null
                )
            ));
            builderData.pointAnchors(current.key()).forEach(anchor -> markers.add(
                new EditorMarker(
                    anchor.id(), anchor.type(), currentOrigin.offset(anchor.position()), null
                )
            ));
        }
        return new EditorSnapshot(
            current.key(), current.label(), current.interior(), current.size(), List.copyOf(spaces),
            List.copyOf(markers)
        );
    }

    static void applyEditorAnchor(
        ServerPlayer player, BlockPos clicked, String type, String label
    ) {
        validateAnchorLabel(label, "앵커");
        Catalog catalog = loadCatalog(player.getServer());
        BuilderData builderData = data(player.getServer());
        BlockPos door = lowerDoorPosition(player.serverLevel(), clicked);
        if (door != null) {
            door = canonicalDoorPosition(player.serverLevel(), door);
        }
        if (type.equals("delete")) {
            BlockPos target = door == null ? clicked.above() : door;
            EditContext edit = findContext(catalog, builderData, target);
            BlockPos relative = target.subtract(edit.origin());
            int removed = builderData.removeAnchorsAt(edit.key(), relative)
                + builderData.removeNpcAnchorsAt(edit.key(), relative)
                + builderData.removePointAnchorsAt(edit.key(), relative);
            BlockPos paired = pairedDoorPosition(player.serverLevel(), target);
            if (paired != null) {
                removed += builderData.removeAnchorsAt(
                    edit.key(), paired.subtract(edit.origin())
                );
            }
            player.sendSystemMessage(Component.literal(
                "[Structure Builder] 이 위치의 앵커 " + removed + "개를 삭제했습니다."
            ));
            BuilderEditorNetwork.sendSnapshot(player);
            return;
        } else if (type.equals("door")) {
            if (door == null) throw new BuilderException("문 앵커는 실제 문 블록에 지정해야 합니다.");
            EditContext edit = findContext(catalog, builderData, door);
            Direction safeSide = playerSide(player, door);
            BlockState doorState = player.serverLevel().getBlockState(door);
            builderData.removeAnchorsAt(edit.key(), door.subtract(edit.origin()));
            BlockPos paired = pairedDoorPosition(player.serverLevel(), door);
            if (paired != null) {
                builderData.removeAnchorsAt(edit.key(), paired.subtract(edit.origin()));
            }
            builderData.putAnchor(edit.key(), new DoorAnchor(
                label, "door", door.subtract(edit.origin()),
                door.relative(safeSide).subtract(edit.origin()),
                doorState.getValue(DoorBlock.FACING).getName(), safeSide.getName(), false
            ));
        } else if (type.equals("npc_position")) {
            EditContext edit = findContext(catalog, builderData, clicked.above());
            builderData.removeNpcAnchorsAt(edit.key(), clicked.above().subtract(edit.origin()));
            builderData.putNpcAnchor(
                edit.key(), new NpcAnchor(label, clicked.above().subtract(edit.origin()))
            );
        } else if (type.equals("arrival")) {
            EditContext edit = findContext(catalog, builderData, clicked.above());
            builderData.removePointAnchorsAt(edit.key(), clicked.above().subtract(edit.origin()));
            builderData.putPointAnchor(edit.key(), new PointAnchor(
                label, "arrival", clicked.above().subtract(edit.origin()),
                player.getDirection().getName()
            ));
        } else {
            throw new BuilderException("지원하지 않는 앵커 종류입니다: " + type);
        }
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] " + type + " '" + label + "' 저장 완료"
        ));
        BuilderEditorNetwork.sendSnapshot(player);
    }

    static void editorTeleport(ServerPlayer player, String key) {
        EditorSpace selected = editorSnapshot(player).spaces().stream()
            .filter(space -> space.key().equals(key)).findFirst()
            .orElseThrow(() -> new BuilderException("공간을 찾을 수 없습니다: " + key));
        teleport(player, selected.origin().offset(1, 1, 1));
        BuilderEditorNetwork.sendSnapshot(player);
    }

    static void editorSaveCurrent(ServerPlayer player) {
        Catalog catalog = loadCatalog(player.getServer());
        BuilderData builderData = data(player.getServer());
        requirePrepared(builderData);
        EditContext current = findContext(catalog, builderData, player.blockPosition());
        Optional<InteriorPlot> dynamic = builderData.interiors().stream()
            .filter(plot -> plot.key().equals(current.key())).findFirst();
        List<String> removedDoorAnchors;
        if (dynamic.isPresent()) {
            removedDoorAnchors = exportInterior(player.serverLevel(), dynamic.get());
        } else {
            PlannedEntry planned = editablePlan(catalog, builderData).stream()
                .filter(value -> value.entry().exportId().equals(current.key()))
                .findFirst()
                .orElseThrow(() -> new BuilderException(
                    "현재 위치에 저장할 NBT 부지가 없습니다."
                ));
            removedDoorAnchors = export(player.serverLevel(), catalog, planned);
        }
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 현재 NBT 저장 완료: " + current.label()
                + removedDoorAnchorNotice(removedDoorAnchors)
        ));
        BuilderEditorNetwork.sendSnapshot(player);
    }

    static void editorSelectCurrent(ServerPlayer player) {
        Catalog catalog = loadCatalog(player.getServer());
        BuilderData builderData = data(player.getServer());
        requirePrepared(builderData);
        EditContext current = findContext(catalog, builderData, player.blockPosition());
        selectWorldEditRegion(player, current);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] WorldEdit 영역 선택 완료: " + current.label()
                + " · " + current.size().getX() + "x" + current.size().getY()
                + "x" + current.size().getZ()
        ));
    }

    static void editorMoveCurrent(ServerPlayer player, String relativeDirection, int amount) {
        if (amount < 1 || amount > 64) {
            throw new BuilderException("이동 칸 수는 1~64 사이여야 합니다.");
        }
        Catalog catalog = loadCatalog(player.getServer());
        BuilderData builderData = data(player.getServer());
        requirePrepared(builderData);
        EditContext current = findContext(catalog, builderData, player.blockPosition());
        Direction direction = editorMoveDirection(player, relativeDirection);
        BlockPos offset = new BlockPos(
            direction.getStepX() * amount,
            direction.getStepY() * amount,
            direction.getStepZ() * amount
        );
        selectWorldEditRegion(player, current);
        int movedBlocks = runWorldEdit(player, "move " + amount + " " + direction.getName());
        if (movedBlocks <= 0) {
            throw new BuilderException("WorldEdit이 이동한 블록이 없어 영역 위치를 변경하지 않았습니다.");
        }
        clearContextOutline(player.serverLevel(), current);
        builderData.moveSpace(current.key(), current.origin().offset(offset));
        EditContext moved = new EditContext(
            current.key(), current.label(), current.origin().offset(offset),
            current.size(), current.interior()
        );
        outlineContext(player.serverLevel(), moved, builderData);
        selectWorldEditRegion(player, moved);
        player.teleportTo(
            player.serverLevel(), player.getX() + offset.getX(),
            player.getY() + offset.getY(), player.getZ() + offset.getZ(),
            player.getYRot(), player.getXRot()
        );
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 현재 영역의 모든 블록을 "
                + moveDirectionLabel(relativeDirection) + " " + amount + "칸 이동했습니다."
        ));
        BuilderEditorNetwork.sendSnapshot(player);
    }

    private static Direction editorMoveDirection(ServerPlayer player, String value) {
        return switch (value) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "front" -> player.getDirection();
            case "back" -> player.getDirection().getOpposite();
            case "left" -> player.getDirection().getCounterClockWise();
            case "right" -> player.getDirection().getClockWise();
            default -> throw new BuilderException("지원하지 않는 이동 방향입니다: " + value);
        };
    }

    private static String moveDirectionLabel(String value) {
        return switch (value) {
            case "up" -> "위로";
            case "down" -> "아래로";
            case "front" -> "바라보는 방향으로";
            case "back" -> "뒤로";
            case "left" -> "왼쪽으로";
            case "right" -> "오른쪽으로";
            default -> value;
        };
    }

    private static void clearContextOutline(ServerLevel level, EditContext context) {
        int minX = context.origin().getX() - 1;
        int maxX = context.origin().getX() + context.size().getX();
        int minZ = context.origin().getZ() - 1;
        int maxZ = context.origin().getZ() + context.size().getZ();
        int minimumY = context.origin().getY() - 1;
        int maximumY = context.origin().getY() + context.size().getY();
        for (int y = minimumY; y <= maximumY; y++) {
            for (int x = minX; x <= maxX; x++) {
                clearOutlineBlock(level, new BlockPos(x, y, minZ));
                clearOutlineBlock(level, new BlockPos(x, y, maxZ));
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                clearOutlineBlock(level, new BlockPos(minX, y, z));
                clearOutlineBlock(level, new BlockPos(maxX, y, z));
            }
        }
    }

    private static void clearOutlineBlock(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        if (state.is(Blocks.BLACK_CONCRETE) || state.is(Blocks.YELLOW_CONCRETE)
            || state.is(Blocks.LIGHT_BLUE_CONCRETE)) {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void outlineContext(
        ServerLevel level, EditContext context, BuilderData builderData
    ) {
        Optional<InteriorPlot> interior = builderData.interiors().stream()
            .filter(plot -> plot.key().equals(context.key())).findFirst();
        if (interior.isPresent()) {
            outlineInterior(level, interior.orElseThrow());
        } else {
            outlineNbtFootprint(level, context.origin(), context.size());
        }
    }

    private static void selectWorldEditRegion(ServerPlayer player, EditContext current) {
        BlockPos start = current.origin();
        BlockPos end = start.offset(
            current.size().getX() - 1,
            current.size().getY() - 1,
            current.size().getZ() - 1
        );
        try {
            Class<?> adapterClass = Class.forName("com.sk89q.worldedit.neoforge.NeoForgeAdapter");
            Class<?> worldClass = Class.forName("com.sk89q.worldedit.world.World");
            Class<?> actorClass = Class.forName("com.sk89q.worldedit.extension.platform.Actor");
            Class<?> ownerClass = Class.forName("com.sk89q.worldedit.session.SessionOwner");
            Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Class<?> selectorClass = Class.forName(
                "com.sk89q.worldedit.regions.selector.CuboidRegionSelector"
            );
            Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");

            Object actor = adapterClass.getMethod("adaptPlayer", ServerPlayer.class)
                .invoke(null, player);
            Object world = adapterClass.getMethod("adapt", ServerLevel.class)
                .invoke(null, player.serverLevel());
            Object minimum = vectorClass.getMethod("at", int.class, int.class, int.class)
                .invoke(null, start.getX(), start.getY(), start.getZ());
            Object maximum = vectorClass.getMethod("at", int.class, int.class, int.class)
                .invoke(null, end.getX(), end.getY(), end.getZ());
            Object selector = selectorClass
                .getConstructor(worldClass, vectorClass, vectorClass)
                .newInstance(world, minimum, maximum);
            Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
            Object sessionManager = worldEditClass.getMethod("getSessionManager").invoke(worldEdit);
            Object session = sessionManager.getClass().getMethod("get", ownerClass)
                .invoke(sessionManager, actor);
            session.getClass().getMethod(
                "setRegionSelector", worldClass,
                Class.forName("com.sk89q.worldedit.regions.RegionSelector")
            ).invoke(session, world, selector);
            boolean selected = (boolean) session.getClass()
                .getMethod("isSelectionDefined", worldClass).invoke(session, world);
            if (!selected) {
                throw new BuilderException("WorldEdit 편집 세션에 영역이 등록되지 않았습니다.");
            }
            session.getClass().getMethod("dispatchCUISelection", actorClass)
                .invoke(session, actor);
        } catch (BuilderException error) {
            throw error;
        } catch (ReflectiveOperationException | LinkageError error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new BuilderException(
                "WorldEdit 선택 영역 설정 실패: " + cause.getMessage(), cause
            );
        }
    }

    private static int runWorldEdit(ServerPlayer player, String command) {
        CommandDispatcher<CommandSourceStack> dispatcher = player.getServer()
            .getCommands().getDispatcher();
        int separator = command.indexOf(' ');
        String commandName = separator < 0 ? command : command.substring(0, separator);
        String arguments = separator < 0 ? "" : command.substring(separator);
        String root = dispatcher.getRoot().getChild("/" + commandName) != null
            ? "/" + commandName
            : dispatcher.getRoot().getChild(commandName) != null ? commandName : null;
        if (root == null) {
            throw new BuilderException(
                "WorldEdit 명령을 찾을 수 없습니다: //" + commandName
                    + ". 에디터 월드에 WorldEdit이 설치되어 있는지 확인하세요."
            );
        }
        try {
            return dispatcher.execute(
                root + arguments,
                player.createCommandSourceStack().withPermission(4)
            );
        } catch (CommandSyntaxException error) {
            throw new BuilderException(
                "WorldEdit 명령 실행 실패: //" + command + " · " + error.getMessage(), error
            );
        }
    }

    static void editorResize(
        ServerPlayer player, int width, int depth, int floorHeight, int floors
    ) {
        Catalog catalog = loadCatalog(player.getServer());
        BuilderData builderData = data(player.getServer());
        if (width < 5 || width > 80 || depth < 5 || depth > 80
            || floorHeight < 3 || floorHeight > 12 || floors < 1 || floors > 8
            || floorHeight * floors > 80) {
            throw new BuilderException("너비·깊이 5~80, 층 높이 3~12, 층수 1~8 범위가 필요합니다.");
        }
        EditContext context = findContext(catalog, builderData, player.blockPosition());
        if (!context.interior()) {
            throw new BuilderException("크기 변경은 내부 NBT에서만 가능합니다.");
        }
        Vec3i resizedSize = new Vec3i(width, floorHeight * floors, depth);
        boolean anchorOutside = builderData.anchors(context.key()).stream().anyMatch(anchor ->
                !insideSize(anchor.position(), resizedSize)
                    || !insideSize(anchor.safeSpawn(), resizedSize))
            || builderData.npcAnchors(context.key()).stream().anyMatch(anchor ->
                !insideSize(anchor.position(), resizedSize))
            || builderData.pointAnchors(context.key()).stream().anyMatch(anchor ->
                !insideSize(anchor.position(), resizedSize));
        if (anchorOutside) {
            throw new BuilderException(
                "새 크기 밖에 문·NPC·도착 앵커가 있습니다. 먼저 앵커를 옮기거나 삭제하세요."
            );
        }
        InteriorPlot current = builderData.interiors().stream()
            .filter(plot -> plot.key().equals(context.key())).findFirst()
            .orElseGet(() -> editablePlan(catalog, builderData).stream()
                .filter(planned -> planned.entry().category().equals("interiors")
                    && planned.entry().exportId().equals(context.key()))
                .findFirst()
                .map(planned -> {
                    InteriorSpec spec = planned.entry().interior();
                    int currentFloors = spec == null ? 1 : spec.floors();
                    int currentFloorHeight = spec == null
                        ? planned.entry().size().getY() : spec.floorHeight();
                    return new InteriorPlot(
                        planned.entry().label(), planned.origin(),
                        planned.entry().size().getX(), planned.entry().size().getZ(),
                        currentFloorHeight, currentFloors
                    );
                })
                .orElseThrow(() -> new BuilderException("현재 내부 NBT를 찾을 수 없습니다.")));
        InteriorPlot resized = new InteriorPlot(
            current.id(), current.origin(), width, depth, floorHeight, floors
        );
        builderData.addInterior(resized);
        outlineInterior(player.serverLevel(), resized);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 내부 NBT 크기 변경: " + resized.id() + " · "
                + resized.width() + "x" + resized.size().getY() + "x" + resized.depth()
        ));
        BuilderEditorNetwork.sendSnapshot(player);
    }

    private static boolean insideSize(BlockPos position, Vec3i size) {
        return position.getX() >= 0 && position.getX() < size.getX()
            && position.getY() >= 0 && position.getY() < size.getY()
            && position.getZ() >= 0 && position.getZ() < size.getZ();
    }

    record EditorSnapshot(
        String currentKey, String currentLabel, boolean interior, Vec3i size,
        List<EditorSpace> spaces, List<EditorMarker> markers
    ) {}

    record EditorSpace(
        String key, String label, boolean interior, BlockPos origin, Vec3i size,
        boolean resizable, int floorHeight, int floors
    ) {}

    record EditorMarker(
        String label, String type, BlockPos position, BlockPos pairedPosition
    ) {}

    private static void giveEditorStick(ServerPlayer player) {
        boolean alreadyHasStick = player.getInventory().items.stream()
            .anyMatch(stack -> stack.is(Items.STICK));
        if (alreadyHasStick) {
            return;
        }
        ItemStack tool = new ItemStack(Items.STICK);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal(toolName(toolMode(player))));
        player.getInventory().add(tool);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 편집 막대기: 실제 문 우클릭=연결 문, "
                + "일반 블록 우클릭=NPC 위치, 웅크리기+좌클릭=설정 삭제"
        ));
    }

    private static ToolMode toolMode(ServerPlayer player) {
        return ToolMode.parse(player.getPersistentData().getString(TOOL_MODE_TAG));
    }

    private static void setToolMode(ServerPlayer player, ToolMode mode) {
        player.getPersistentData().putString(TOOL_MODE_TAG, mode.id);
        ItemStack held = player.getMainHandItem();
        if (held.is(Items.STICK)) {
            held.set(DataComponents.CUSTOM_NAME, Component.literal(toolName(mode)));
        }
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 도구 모드: " + mode.label
        ));
    }

    private static String toolName(ToolMode mode) {
        return "건축 편집 막대기 · 문/NPC";
    }

    private static int setToolModeCommand(CommandSourceStack source, String requested)
        throws CommandSyntaxException {
        ToolMode mode = ToolMode.byId(requested).orElseThrow(
            () -> new BuilderException("알 수 없는 도구 모드: " + requested)
        );
        setToolMode(source.getPlayerOrException(), mode);
        return 1;
    }

    private static int setNpcLabelCommand(CommandSourceStack source, String label)
        throws CommandSyntaxException {
        if (!label.matches("[a-z0-9][a-z0-9_]*")) {
            return fail(source, new BuilderException(
                "NPC 라벨은 영문 소문자, 숫자와 밑줄만 사용할 수 있습니다."
            ));
        }
        ServerPlayer player = source.getPlayerOrException();
        player.getPersistentData().putString(NPC_LABEL_TAG, label);
        setToolMode(player, ToolMode.NPC);
        source.sendSuccess(
            () -> Component.literal("[Structure Builder] NPC 라벨=" + label), false
        );
        return 1;
    }

    private static int setDoorLabelCommand(CommandSourceStack source, String label)
        throws CommandSyntaxException {
        validateAnchorLabel(label, "문");
        ServerPlayer player = source.getPlayerOrException();
        player.getPersistentData().putString(DOOR_LABEL_TAG, label);
        setToolMode(player, ToolMode.DOOR);
        source.sendSuccess(
            () -> Component.literal(
                "[Structure Builder] 문 이름=" + label
                    + " · 실제 문을 우클릭하고 목적지는 웹에서 연결하세요."
            ), false
        );
        return 1;
    }

    private static int setArrivalLabelCommand(CommandSourceStack source, String label)
        throws CommandSyntaxException {
        validateAnchorLabel(label, "도착 지점");
        ServerPlayer player = source.getPlayerOrException();
        player.getPersistentData().putString(ARRIVAL_LABEL_TAG, label);
        setToolMode(player, ToolMode.ARRIVAL);
        source.sendSuccess(
            () -> Component.literal(
                "[Structure Builder] 도착 지점=" + label + " · 바닥을 우클릭하세요."
            ), false
        );
        return 1;
    }

    private static void validateAnchorLabel(String label, String subject) {
        if (!label.matches("[a-z0-9][a-z0-9_]*")) {
            throw new BuilderException(
                subject + " 이름은 영문 소문자, 숫자와 밑줄만 사용할 수 있습니다."
            );
        }
    }

    private static void setNpcAnchor(
        ServerPlayer player, Catalog catalog, BuilderData data, BlockPos position
    ) {
        String label = player.getPersistentData().getString(NPC_LABEL_TAG);
        if (label.isBlank()) {
            throw new BuilderException(
                "/cobbleventure_builder tool npc <라벨>을 먼저 사용하세요."
            );
        }
        EditContext edit = findContext(catalog, data, position);
        NpcAnchor anchor = new NpcAnchor(label, position.subtract(edit.origin()));
        data.putNpcAnchor(edit.key(), anchor);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] " + edit.label() + "에 NPC 위치를 지정했습니다: "
                + label + "=" + format(anchor.position())
        ));
    }

    private static void inspect(ServerPlayer player, EditContext edit, BuilderData data) {
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] " + edit.label() + " · 크기 "
                + edit.size().getX() + "x" + edit.size().getY() + "x" + edit.size().getZ()
                + " · 문 " + data.anchors(edit.key()).size()
                + " · NPC " + data.npcAnchors(edit.key()).size()
                + " · 지점 " + data.pointAnchors(edit.key()).size()
        ));
    }

    private static int listAnchors(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BuilderData builderData = data(source.getServer());
        EditContext edit = findContext(
            loadCatalog(source.getServer()), builderData, player.blockPosition()
        );
        List<String> values = new ArrayList<>();
        builderData.anchors(edit.key()).forEach(anchor -> values.add(
            anchor.role() + "=" + format(anchor.position())
        ));
        builderData.npcAnchors(edit.key()).forEach(anchor -> values.add(
            anchor.label() + "=" + format(anchor.position())
        ));
        builderData.pointAnchors(edit.key()).forEach(anchor -> values.add(
            anchor.id() + "=" + format(anchor.position()) + " [" + anchor.type() + "]"
        ));
        source.sendSuccess(
            () -> Component.literal(
                "[Structure Builder] " + edit.label() + " 앵커: "
                    + (values.isEmpty() ? "없음" : String.join(", ", values))
            ), false
        );
        return values.size();
    }

    private static int showAnchors(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BuilderData builderData = data(source.getServer());
        EditContext edit = findContext(
            loadCatalog(source.getServer()), builderData, player.blockPosition()
        );
        List<BlockPos> positions = new ArrayList<>();
        builderData.anchors(edit.key()).forEach(anchor -> positions.add(anchor.position()));
        builderData.npcAnchors(edit.key()).forEach(anchor -> positions.add(anchor.position()));
        builderData.pointAnchors(edit.key()).forEach(anchor -> positions.add(anchor.position()));
        for (BlockPos relative : positions) {
            BlockPos position = edit.origin().offset(relative);
            player.serverLevel().sendParticles(
                ParticleTypes.END_ROD,
                position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D,
                16, 0.2D, 0.35D, 0.2D, 0.01D
            );
        }
        source.sendSuccess(
            () -> Component.literal(
                "[Structure Builder] 앵커 " + positions.size() + "개를 입자로 표시했습니다."
            ), false
        );
        return positions.size();
    }

    private static void setPointAnchor(
        ServerPlayer player, Catalog catalog, BuilderData data,
        BlockPos clicked, ToolMode mode
    ) {
        BlockPos position = mode == ToolMode.INTERACTION ? clicked : clicked.above();
        EditContext edit = findContext(catalog, data, position);
        String type;
        String id;
        if (mode == ToolMode.ARRIVAL) {
            type = "arrival";
            id = player.getPersistentData().getString(ARRIVAL_LABEL_TAG);
            if (id.isBlank()) {
                throw new BuilderException(
                    "/cobbleventure_builder tool arrival <이름>을 먼저 사용하세요."
                );
            }
        } else if (mode == ToolMode.SPAWN) {
            type = edit.interior() ? "interior_spawn" : "exterior_spawn";
            id = type;
        } else {
            type = mode == ToolMode.PATROL ? "patrol_point" : "interaction_point";
            id = data.nextPointId(edit.key(), type);
        }
        PointAnchor anchor = new PointAnchor(
            id, type, position.subtract(edit.origin()), player.getDirection().getName()
        );
        data.putPointAnchor(edit.key(), anchor);
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] " + edit.label() + "에 " + id
                + " 지점을 지정했습니다: " + format(anchor.position())
        ));
    }

    private static BlockPos lowerDoorPosition(ServerLevel level, BlockPos clicked) {
        BlockState state = level.getBlockState(clicked);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return null;
        }
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
            ? clicked.below()
            : clicked;
    }

    private static BlockPos pairedDoorPosition(ServerLevel level, BlockPos lower) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return null;
        }
        Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        for (Direction side : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
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

    private static BlockPos canonicalDoorPosition(ServerLevel level, BlockPos lower) {
        BlockPos paired = pairedDoorPosition(level, lower);
        if (paired == null) {
            return lower;
        }
        return comparePosition(lower, paired) <= 0 ? lower : paired;
    }

    private static int comparePosition(BlockPos left, BlockPos right) {
        int x = Integer.compare(left.getX(), right.getX());
        if (x != 0) return x;
        int y = Integer.compare(left.getY(), right.getY());
        return y != 0 ? y : Integer.compare(left.getZ(), right.getZ());
    }

    private static Direction playerSide(ServerPlayer player, BlockPos door) {
        double offsetX = player.getX() - (door.getX() + 0.5D);
        double offsetZ = player.getZ() - (door.getZ() + 0.5D);
        if (Math.abs(offsetX) > Math.abs(offsetZ)) {
            return offsetX >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return offsetZ >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private static String roleLabel(String role) {
        return "이름 있는 문";
    }

    private static String format(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static void requirePrepared(BuilderData data) {
        if (!data.prepared) {
            throw new BuilderException("건축 부지가 아직 생성되지 않았습니다.");
        }
    }

    private static PlannedEntry find(Catalog catalog, BuilderData data, String requested) {
        return editablePlan(catalog, data).stream()
            .filter(planned -> planned.entry().label().equals(requested)
                || planned.entry().source().equals(requested)
                || planned.entry().exportId().equals(requested))
            .findFirst()
            .orElseThrow(() -> new BuilderException(
                "구조물을 찾을 수 없습니다: " + requested
            ));
    }

    private static PlannedEntry findContaining(
        Catalog catalog, BuilderData data, BlockPos position
    ) {
        return editablePlan(catalog, data).stream()
            .filter(planned -> contains(planned, position))
            .findFirst()
            .orElseThrow(() -> new BuilderException(
                "현재 위치가 구조물 선택 영역 안에 있지 않습니다."
            ));
    }

    private static EditContext findContext(
        Catalog catalog, BuilderData data, BlockPos position
    ) {
        for (InteriorPlot plot : data.interiors()) {
            if (plot.contains(position)) {
                return plot.context();
            }
        }
        PlannedEntry planned = findContaining(catalog, data, position);
        return new EditContext(
            planned.entry().exportId(), planned.entry().label(),
            planned.origin(), planned.entry().size(),
            planned.entry().category().equals("interiors")
        );
    }

    private static void teleportThroughDoor(
        ServerPlayer player, Catalog catalog, BuilderData data,
        EditContext current, BlockPos door
    ) {
        BlockPos relative = door.subtract(current.origin());
        DoorAnchor selected = data.anchors(current.key()).stream()
            .filter(anchor -> anchor.position().equals(relative))
            .findFirst()
            .orElseThrow(() -> new BuilderException(
                "이 문은 아직 입장문 또는 퇴장문으로 지정되지 않았습니다."
            ));
        String linkId = current.label();
        EditContext destination;
        if (current.interior()) {
            destination = exteriorContext(catalog, data, linkId);
        } else {
            destination = interiorContext(catalog, data, linkId);
        }
        String spawnType = destination.interior() ? "interior_spawn" : "exterior_spawn";
        BlockPos target = data.pointAnchors(destination.key()).stream()
            .filter(anchor -> anchor.type().equals(spawnType))
            .findFirst()
            .map(anchor -> destination.origin().offset(anchor.position()))
            .orElseGet(() -> data.anchors(destination.key()).stream()
                .filter(anchor -> anchor.role().equals("door"))
                .findFirst()
                .map(anchor -> destination.origin().offset(anchor.safeSpawn()))
                .orElse(destination.origin().offset(1, 1, 1)));
        player.teleportTo(
            player.serverLevel(), target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
        player.sendSystemMessage(Component.literal(
            "[Structure Builder] 미리보기 이동: " + destination.label()
        ));
    }

    private static EditContext interiorContext(Catalog catalog, BuilderData data, String id) {
        Optional<InteriorPlot> dynamic = data.interior(id);
        if (dynamic.isPresent()) {
            return dynamic.get().context();
        }
        return editablePlan(catalog, data).stream()
            .filter(planned -> planned.entry().category().equals("interiors")
                && planned.entry().label().equals(id))
            .findFirst()
            .map(planned -> new EditContext(
                planned.entry().exportId(), planned.entry().label(),
                planned.origin(), planned.entry().size(), true
            ))
            .orElseThrow(() -> new BuilderException(
                "연결된 내부 공간이 없습니다. 같은 ID로 interior create를 실행하세요: " + id
            ));
    }

    private static EditContext exteriorContext(Catalog catalog, BuilderData data, String id) {
        return editablePlan(catalog, data).stream()
            .filter(planned -> !planned.entry().category().equals("interiors")
                && planned.entry().label().equals(id))
            .findFirst()
            .map(planned -> new EditContext(
                planned.entry().exportId(), planned.entry().label(),
                planned.origin(), planned.entry().size(), false
            ))
            .orElseThrow(() -> new BuilderException(
                "같은 ID의 외부 건물이 없습니다: " + id
            ));
    }

    private static int createInterior(
        CommandSourceStack source, String id,
        int width, int depth, int floorHeight, int floors
    ) throws CommandSyntaxException {
        if (!id.matches("[a-z0-9][a-z0-9_]*")) {
            return fail(source, new BuilderException(
                "내부 ID는 영문 소문자, 숫자와 밑줄만 사용할 수 있습니다."
            ));
        }
        int height = floorHeight * floors;
        if (height > 80) {
            return fail(source, new BuilderException("내부 전체 높이는 80블록 이하여야 합니다."));
        }
        BuilderData data = data(source.getServer());
        boolean catalogInterior = editablePlan(loadCatalog(source.getServer()), data).stream()
            .anyMatch(planned -> planned.entry().category().equals("interiors")
                && planned.entry().label().equals(id));
        if (data.interior(id).isPresent() || catalogInterior) {
            return fail(source, new BuilderException("이미 존재하는 내부 공간입니다: " + id));
        }
        Catalog catalog = loadCatalog(source.getServer());
        long catalogBackedOverrides = data.interiors().stream()
            .filter(plot -> catalog.entries().stream().anyMatch(entry ->
                entry.category().equals("interiors") && entry.label().equals(plot.id())
            )).count();
        int index = catalog.interiorCount()
            + data.interiorCount() - Math.toIntExact(catalogBackedOverrides);
        BlockPos origin = new BlockPos(
            INTERIOR_ORIGIN_X + (index % 8) * INTERIOR_CELL_SIZE,
            data.groundY + 1,
            INTERIOR_ORIGIN_Z + (index / 8) * INTERIOR_CELL_SIZE
        );
        InteriorPlot plot = new InteriorPlot(id, origin, width, depth, floorHeight, floors);
        data.addInterior(plot);
        outlineInterior(source.getServer().overworld(), plot);
        teleport(source.getPlayerOrException(), plot.origin().offset(1, 0, 1));
        source.sendSuccess(
            () -> Component.literal(
                "[Structure Builder] 내부 공간 생성: " + id + " · "
                    + width + "x" + height + "x" + depth + " · " + floors + "층"
            ), true
        );
        return 1;
    }

    private static int listInteriors(CommandSourceStack source) {
        BuilderData builderData = data(source.getServer());
        List<String> values = new ArrayList<>();
        builderData.interiors().stream()
            .map(plot -> plot.id() + "(" + plot.floors() + "층)")
            .forEach(values::add);
        for (PlannedEntry planned : plan(loadCatalog(source.getServer()), builderData.groundY)) {
            if (!planned.entry().category().equals("interiors")) {
                continue;
            }
            if (builderData.interior(planned.entry().label()).isPresent()) {
                continue;
            }
            InteriorSpec spec = planned.entry().interior();
            values.add(spec == null
                ? planned.entry().label()
                : planned.entry().label() + "(" + spec.floors() + "층)");
        }
        String value = values.isEmpty() ? "없음" : String.join(", ", values);
        source.sendSuccess(
            () -> Component.literal("[Structure Builder] 내부 공간: " + value), false
        );
        return values.size();
    }

    private static int teleportToInterior(CommandSourceStack source, String id)
        throws CommandSyntaxException {
        BuilderData builderData = data(source.getServer());
        EditContext edit = interiorContext(loadCatalog(source.getServer()), builderData, id);
        teleport(source.getPlayerOrException(), edit.origin().offset(1, 0, 1));
        return 1;
    }

    private static void teleport(ServerPlayer player, BlockPos position) {
        player.teleportTo(
            player.serverLevel(), position.getX() + 0.5D, position.getY(),
            position.getZ() + 0.5D, player.getYRot(), player.getXRot()
        );
    }

    private static int saveInterior(CommandSourceStack source, String id) {
        try {
            BuilderData builderData = data(source.getServer());
            Optional<InteriorPlot> dynamic = builderData.interior(id);
            if (dynamic.isPresent()) {
                exportInterior(source.getServer().overworld(), dynamic.get());
            } else {
                Catalog catalog = loadCatalog(source.getServer());
                PlannedEntry planned = editablePlan(catalog, builderData)
                    .stream()
                    .filter(value -> value.entry().category().equals("interiors")
                        && value.entry().label().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new BuilderException(
                        "내부 공간을 찾을 수 없습니다: " + id
                    ));
                export(source.getServer().overworld(), catalog, planned);
            }
            source.sendSuccess(
                () -> Component.literal("[Structure Builder] 내부 NBT 내보내기 완료: " + id),
                true
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static int deleteInterior(CommandSourceStack source, String id) {
        try {
            BuilderData builderData = data(source.getServer());
            boolean catalogInterior = plan(loadCatalog(source.getServer()), builderData.groundY)
                .stream().anyMatch(planned -> planned.entry().category().equals("interiors")
                    && planned.entry().label().equals(id));
            if (catalogInterior) {
                throw new BuilderException(
                    "불러온 내부 NBT는 삭제할 수 없습니다. 크기는 변경할 수 있습니다: " + id
                );
            }
            InteriorPlot plot = builderData.interior(id).orElseThrow(() ->
                new BuilderException("동적으로 만든 내부 공간만 삭제할 수 있습니다: " + id)
            );
            clearInterior(source.getServer().overworld(), plot);
            builderData.removeInterior(id);
            source.sendSuccess(
                () -> Component.literal(
                    "[Structure Builder] 내부 공간과 작업 구역을 삭제했습니다: " + id
                ), true
            );
            return 1;
        } catch (BuilderException error) {
            return fail(source, error);
        }
    }

    private static void clearInterior(ServerLevel level, InteriorPlot plot) {
        BlockPos start = plot.origin().offset(-1, -1, -1);
        Vec3i size = plot.size();
        BlockPos end = plot.origin().offset(
            size.getX(), size.getY(), size.getZ()
        );
        for (BlockPos position : BlockPos.betweenClosed(start, end)) {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void outlineInterior(ServerLevel level, InteriorPlot plot) {
        BlockState border = Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState();
        for (int floor = 0; floor < plot.floors(); floor++) {
            int y = plot.origin().getY() + floor * plot.floorHeight() - 1;
            int relativeY = y - plot.origin().getY();
            for (int offset = 0; offset < 3; offset++) {
                level.setBlock(plot.origin().offset(-1 + offset, relativeY, -1), border, 18);
                level.setBlock(plot.origin().offset(-1, relativeY, -1 + offset), border, 18);
                level.setBlock(plot.origin().offset(plot.width() - offset, relativeY, -1), border, 18);
                level.setBlock(plot.origin().offset(plot.width(), relativeY, -1 + offset), border, 18);
                level.setBlock(plot.origin().offset(-1 + offset, relativeY, plot.depth()), border, 18);
                level.setBlock(plot.origin().offset(-1, relativeY, plot.depth() - offset), border, 18);
                level.setBlock(plot.origin().offset(plot.width() - offset, relativeY, plot.depth()), border, 18);
                level.setBlock(plot.origin().offset(plot.width(), relativeY, plot.depth() - offset), border, 18);
            }
        }
        outlineNbtFootprint(level, plot.origin(), plot.size());
    }

    private static boolean contains(PlannedEntry planned, BlockPos position) {
        BlockPos origin = planned.origin();
        Vec3i size = planned.entry().size();
        return position.getX() >= origin.getX()
            && position.getX() < origin.getX() + size.getX()
            && position.getY() >= origin.getY()
            && position.getY() < origin.getY() + size.getY()
            && position.getZ() >= origin.getZ()
            && position.getZ() < origin.getZ() + size.getZ();
    }

    private static void configureWorld(MinecraftServer server) {
        server.setDefaultGameType(GameType.CREATIVE);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        ServerLevel level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
    }

    private static void prepareLayout(
        ServerLevel level, Catalog catalog, BuilderData data
    ) {
        int groundY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0
        ) - 1;
        int rows = catalog.exteriorRows();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < catalog.columns(); column++) {
                int cellIndex = row * catalog.columns() + column;
                outlineCell(
                    level,
                    ORIGIN_X + column * catalog.cellSize(),
                    ORIGIN_Z + row * catalog.cellSize(),
                    groundY,
                    catalog.cellSize(),
                    cellIndex < catalog.exteriorCount()
                );
            }
        }
        int interiorRows = catalog.interiorRows();
        for (int row = 0; row < interiorRows; row++) {
            for (int column = 0; column < catalog.columns(); column++) {
                int cellIndex = row * catalog.columns() + column;
                outlineCell(
                    level,
                    INTERIOR_ORIGIN_X + column * INTERIOR_CELL_SIZE,
                    INTERIOR_ORIGIN_Z + row * INTERIOR_CELL_SIZE,
                    groundY,
                    INTERIOR_CELL_SIZE,
                    cellIndex < catalog.interiorCount()
                );
            }
        }
        for (PlannedEntry planned : plan(catalog, groundY)) {
            placeSource(level, planned);
        }
        data.replaceCatalogAnchors(catalog);
        data.complete(catalog.catalogHash(), groundY);
        BlockPos spawn = spawnPosition(groundY);
        level.setDefaultSpawnPos(spawn, 0.0F);
        level.getServer().saveEverything(true, true, true);
    }

    private static void placeSource(ServerLevel level, PlannedEntry planned) {
        ResourceLocation id = ResourceLocation.parse(planned.entry().structureId());
        var template = level.getStructureManager().get(id).orElseThrow(
            () -> new BuilderException("패키징된 원본 NBT가 없습니다: " + id)
        );
        Vec3i actual = template.getSize();
        Vec3i expected = planned.entry().size();
        if (!actual.equals(expected)) {
            throw new BuilderException(
                "카탈로그와 NBT 크기가 다릅니다: " + id
                    + " (카탈로그 " + expected + ", NBT " + actual + ")"
            );
        }
        loadChunks(level, planned.origin(), expected);
        boolean placed = template.placeInWorld(
            level, planned.origin(), planned.origin(), new StructurePlaceSettings(),
            RandomSource.create(level.getSeed() ^ planned.origin().asLong()), 2
        );
        if (!placed) {
            throw new BuilderException("NBT 배치에 실패했습니다: " + id);
        }
        outlineNbtFootprint(level, planned.origin(), expected);
        placeLabel(level, planned);
    }

    private static List<String> export(
        ServerLevel level, Catalog catalog, PlannedEntry planned
    ) {
        planned = authoredPlot(level, catalog, planned);
        List<String> removedDoorAnchors = reconcileDoorAnchors(
            level, planned.entry().exportId(), planned.origin()
        );
        ResourceLocation exportId = ResourceLocation.parse(planned.entry().exportId());
        var manager = level.getStructureManager();
        var template = manager.getOrCreate(exportId);
        BlockPos exportOrigin = authoredFootprintOrigin(level, planned);
        template.fillFromWorld(
            level, exportOrigin, planned.entry().size(), false, Blocks.STRUCTURE_VOID
        );
        template.setAuthor("Cobbleventure Structure Builder");
        if (!manager.save(exportId)) {
            throw new BuilderException("NBT 파일 저장에 실패했습니다: " + exportId);
        }
        exportAnchors(level.getServer(), planned);
        return removedDoorAnchors;
    }

    private static PlannedEntry authoredPlot(
        ServerLevel level, Catalog catalog, PlannedEntry planned
    ) {
        boolean interior = planned.entry().category().equals("interiors");
        int cellSize = interior ? INTERIOR_CELL_SIZE : catalog.cellSize();
        int startX = interior ? INTERIOR_ORIGIN_X : ORIGIN_X;
        int startZ = interior ? INTERIOR_ORIGIN_Z : ORIGIN_Z;
        int currentRows = interior ? catalog.interiorRows() : catalog.exteriorRows();
        int scanRows = Math.max(1, currentRows + 4);
        int signXOffset = cellSize / 2;
        int signY = planned.origin().getY();
        for (int row = 0; row < scanRows; row++) {
            for (int column = 0; column < catalog.columns(); column++) {
                int cellX = startX + column * cellSize;
                int cellZ = startZ + row * cellSize;
                int signX = cellX + signXOffset;
                for (int z = cellZ + 1; z < cellZ + cellSize - 1; z++) {
                    if (!(level.getBlockEntity(new BlockPos(signX, signY, z))
                        instanceof SignBlockEntity sign)) {
                        continue;
                    }
                    String label = sign.getFrontText().getMessage(0, false).getString();
                    String category = sign.getFrontText().getMessage(2, false).getString();
                    if (!label.equals(planned.entry().label())
                        || !category.equals(planned.entry().category())) {
                        continue;
                    }
                    PlannedEntry authored = new PlannedEntry(
                        planned.entry(), row, column, cellX, cellZ,
                        new BlockPos(
                            cellX + (cellSize - planned.entry().size().getX()) / 2,
                            planned.origin().getY(),
                            cellZ + (cellSize - planned.entry().size().getZ()) / 2
                        )
                    );
                    return authored.withOrigin(authoredFootprintOrigin(level, authored));
                }
            }
        }
        LOGGER.warn(
            "Could not find authored plot sign for {}; using current catalog position {}",
            planned.entry().label(), planned.origin()
        );
        return planned.withOrigin(authoredFootprintOrigin(level, planned));
    }

    /**
     * Recovers the origin drawn when this plot was loaded. A web resize changes
     * the catalog size and therefore the calculated centered origin, while the
     * already-authored blocks remain at their old origin. The black/yellow exact
     * footprint border is the durable world-side source of truth in that case.
     */
    private static BlockPos authoredFootprintOrigin(ServerLevel level, PlannedEntry planned) {
        int cellSize = planned.entry().category().equals("interiors")
            ? INTERIOR_CELL_SIZE : 80;
        int groundY = planned.origin().getY() - 1;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x = planned.cellX() + 1; x < planned.cellX() + cellSize - 1; x++) {
            for (int z = planned.cellZ() + 1; z < planned.cellZ() + cellSize - 1; z++) {
                BlockState state = level.getBlockState(new BlockPos(x, groundY, z));
                if (!state.is(Blocks.BLACK_CONCRETE) && !state.is(Blocks.YELLOW_CONCRETE)) {
                    continue;
                }
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
            }
        }
        if (minX == Integer.MAX_VALUE || maxX - minX < 2 || maxZ - minZ < 2) {
            return planned.origin();
        }
        BlockPos recovered = new BlockPos(minX + 1, planned.origin().getY(), minZ + 1);
        if (!recovered.equals(planned.origin())) {
            LOGGER.info(
                "Recovered authored origin for {} after size change: planned={}, authored={}",
                planned.entry().label(), planned.origin(), recovered
            );
        }
        return recovered;
    }

    private static void exportAnchors(MinecraftServer server, PlannedEntry planned) {
        String resourcePath = planned.entry().exportId().split(":", 2)[1];
        String relative = resourcePath.startsWith("export/")
            ? resourcePath.substring("export/".length())
            : resourcePath;
        exportMetadata(
            server, planned.entry().exportId(), relative,
            planned.entry().source(), planned.entry().interior(),
            planned.entry().interiorStructure(),
            !planned.entry().anchors().isEmpty()
                || !planned.entry().npcs().isEmpty()
                || !planned.entry().points().isEmpty()
                || planned.entry().interior() != null
                || planned.entry().interiorStructure() != null
        );
    }

    private static List<String> exportInterior(ServerLevel level, InteriorPlot plot) {
        List<String> removedDoorAnchors = reconcileDoorAnchors(
            level, plot.key(), plot.origin()
        );
        String relative = "interiors/" + plot.id();
        ResourceLocation exportId = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure_builder", "export/" + relative
        );
        var manager = level.getStructureManager();
        var template = manager.getOrCreate(exportId);
        template.fillFromWorld(level, plot.origin(), plot.size(), false, Blocks.STRUCTURE_VOID);
        template.setAuthor("Cobbleventure Structure Builder");
        if (!manager.save(exportId)) {
            throw new BuilderException("내부 NBT 파일 저장에 실패했습니다: " + exportId);
        }
        exportMetadata(
            level.getServer(), plot.key(), relative,
            "content/structures/interiors/" + plot.id() + ".nbt",
            plot.spec(), null, true
        );
        return removedDoorAnchors;
    }

    private static List<String> reconcileDoorAnchors(
        ServerLevel level, String key, BlockPos origin
    ) {
        BuilderData builderData = data(level.getServer());
        List<String> removed = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (DoorAnchor anchor : builderData.anchors(key)) {
            BlockPos position = origin.offset(anchor.position());
            BlockPos lower = lowerDoorPosition(level, position);
            if (lower == null) {
                if (builderData.removeAnchor(key, anchor.label())) {
                    removed.add(key + "/" + anchor.label());
                }
            } else if (!canonicalDoorPosition(level, lower).equals(lower)) {
                invalid.add(anchor.label() + "=" + format(anchor.position()) + " (양문형 반대 문짝)");
            }
        }
        if (!removed.isEmpty()) {
            LOGGER.warn("Removed stale door anchors before exporting {}: {}", key, removed);
        }
        if (!invalid.isEmpty()) {
            throw new BuilderException(
                "문 앵커 위치가 실제 문과 일치하지 않습니다: " + String.join(", ", invalid)
                    + ". 양문형 문의 대표 문짝을 막대기로 다시 지정하세요."
            );
        }
        return List.copyOf(removed);
    }

    private static String removedDoorAnchorNotice(List<String> removed) {
        return removed.isEmpty()
            ? ""
            : " 사라진 문 앵커 자동 해제: " + String.join(", ", removed);
    }

    private static void exportMetadata(
        MinecraftServer server, String key, String relative,
        String source, InteriorSpec interior, String interiorStructure,
        boolean forceMetadata
    ) {
        Path target = server.getWorldPath(LevelResource.ROOT)
            .resolve("generated/cobbleventure_builder/structure_metadata/export")
            .resolve(relative + ".structure.json");
        List<DoorAnchor> anchors = data(server).anchors(key);
        List<NpcAnchor> npcs = data(server).npcAnchors(key);
        List<PointAnchor> points = data(server).pointAnchors(key);
        try {
            if (anchors.isEmpty() && npcs.isEmpty() && points.isEmpty()
                && interior == null && !forceMetadata) {
                Files.deleteIfExists(target);
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("structure", source);
            if (interiorStructure != null) {
                root.addProperty("interior_structure", interiorStructure);
            }
            if (interior != null) {
                JsonObject workspace = new JsonObject();
                workspace.addProperty("id", interior.id());
                workspace.addProperty("width", interior.width());
                workspace.addProperty("depth", interior.depth());
                workspace.addProperty("floor_height", interior.floorHeight());
                workspace.addProperty("floors", interior.floors());
                root.add("interior", workspace);
            }
            JsonArray values = new JsonArray();
            for (DoorAnchor anchor : anchors) {
                JsonObject value = new JsonObject();
                value.addProperty("id", anchor.label());
                value.addProperty("label", anchor.label());
                value.addProperty("type", anchor.role());
                value.add("position", vector(anchor.position()));
                value.add("safe_spawn", vector(anchor.safeSpawn()));
                value.addProperty("door_facing", anchor.doorFacing());
                value.addProperty("safe_side", anchor.safeSide());
                if (anchor.sealEntry()) {
                    value.addProperty("seal_entry", true);
                }
                values.add(value);
            }
            for (NpcAnchor anchor : npcs) {
                JsonObject value = new JsonObject();
                value.addProperty("label", anchor.label());
                value.addProperty("type", "npc_position");
                value.add("position", vector(anchor.position()));
                values.add(value);
            }
            for (PointAnchor anchor : points) {
                JsonObject value = new JsonObject();
                value.addProperty("id", anchor.id());
                value.addProperty("type", anchor.type());
                value.add("position", vector(anchor.position()));
                value.addProperty("facing", anchor.facing());
                values.add(value);
            }
            root.add("anchors", values);
            Files.createDirectories(target.getParent());
            Files.writeString(
                target, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new BuilderException("출입구 메타데이터 저장에 실패했습니다: " + target, error);
        }
    }

    private static JsonArray vector(BlockPos position) {
        JsonArray result = new JsonArray();
        result.add(position.getX());
        result.add(position.getY());
        result.add(position.getZ());
        return result;
    }

    private static List<PlannedEntry> plan(Catalog catalog, int groundY) {
        List<PlannedEntry> result = new ArrayList<>(catalog.entries().size());
        planCategory(
            result,
            catalog.entries().stream()
                .filter(entry -> !entry.category().equals("interiors")).toList(),
            catalog.columns(), catalog.cellSize(), ORIGIN_X, ORIGIN_Z, groundY
        );
        planCategory(
            result,
            catalog.entries().stream()
                .filter(entry -> entry.category().equals("interiors")).toList(),
            catalog.columns(), INTERIOR_CELL_SIZE,
            INTERIOR_ORIGIN_X, INTERIOR_ORIGIN_Z, groundY
        );
        if (result.size() != catalog.entries().size()) {
            throw new BuilderException(
                "카탈로그 배치 공간이 부족합니다: 구조물 " + catalog.entries().size()
                    + "개 중 " + result.size() + "개만 계획됨"
            );
        }
        return List.copyOf(result);
    }

    private static List<PlannedEntry> editablePlan(Catalog catalog, BuilderData data) {
        return plan(catalog, data.groundY).stream()
            .map(planned -> planned.withOrigin(data.movedOrigin(
                planned.entry().exportId(), planned.origin()
            )))
            .toList();
    }

    private static void planCategory(
        List<PlannedEntry> result, List<Entry> entries, int columns, int cellSize,
        int originX, int originZ, int groundY
    ) {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            int row = index / columns;
            int column = index % columns;
            int cellX = originX + column * cellSize;
            int cellZ = originZ + row * cellSize;
            result.add(new PlannedEntry(
                entry, row, column, cellX, cellZ,
                new BlockPos(
                    cellX + (cellSize - entry.size().getX()) / 2,
                    groundY + 1,
                    cellZ + (cellSize - entry.size().getZ()) / 2
                )
            ));
        }
    }

    private static void outlineCell(
        ServerLevel level, int cellX, int cellZ, int groundY,
        int cellSize, boolean occupied
    ) {
        var border = occupied
            ? Blocks.BLACK_CONCRETE.defaultBlockState()
            : Blocks.WHITE_CONCRETE.defaultBlockState();
        for (int offset = 0; offset < cellSize; offset++) {
            level.setBlock(new BlockPos(cellX + offset, groundY, cellZ), border, 2);
            level.setBlock(
                new BlockPos(cellX + offset, groundY, cellZ + cellSize - 1), border, 2
            );
            level.setBlock(new BlockPos(cellX, groundY, cellZ + offset), border, 2);
            level.setBlock(
                new BlockPos(cellX + cellSize - 1, groundY, cellZ + offset), border, 2
            );
        }
    }

    /** Draws the exact exported X/Z bounds one block outside the NBT footprint. */
    private static void outlineNbtFootprint(ServerLevel level, BlockPos origin, Vec3i size) {
        int minX = origin.getX() - 1;
        int maxX = origin.getX() + size.getX();
        int minZ = origin.getZ() - 1;
        int maxZ = origin.getZ() + size.getZ();
        int groundY = origin.getY() - 1;
        for (int x = minX; x <= maxX; x++) {
            level.setBlock(new BlockPos(x, groundY, minZ), footprintBorder(x, minZ), 2);
            level.setBlock(new BlockPos(x, groundY, maxZ), footprintBorder(x, maxZ), 2);
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            level.setBlock(new BlockPos(minX, groundY, z), footprintBorder(minX, z), 2);
            level.setBlock(new BlockPos(maxX, groundY, z), footprintBorder(maxX, z), 2);
        }
    }

    private static BlockState footprintBorder(int x, int z) {
        return ((x + z) & 1) == 0
            ? Blocks.BLACK_CONCRETE.defaultBlockState()
            : Blocks.YELLOW_CONCRETE.defaultBlockState();
    }

    private static void placeLabel(ServerLevel level, PlannedEntry planned) {
        BlockPos signPosition = new BlockPos(
            planned.cellX() + (planned.entry().category().equals("interiors")
                ? INTERIOR_CELL_SIZE / 2 : 40),
            planned.origin().getY(),
            planned.origin().getZ() - 3
        );
        level.setBlock(signPosition, Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(signPosition) instanceof SignBlockEntity sign) {
            Vec3i size = planned.entry().size();
            SignText text = sign.getFrontText()
                .setMessage(0, Component.literal(planned.entry().label()))
                .setMessage(1, Component.literal(
                    size.getX() + "x" + size.getY() + "x" + size.getZ()
                ))
                .setMessage(2, Component.literal(planned.entry().category()))
                .setMessage(3, Component.literal(
                    "row " + planned.row() + " / col " + planned.column()
                ));
            sign.setText(text, true);
            sign.setChanged();
        }
    }

    private static void loadChunks(ServerLevel level, BlockPos origin, Vec3i size) {
        int minChunkX = origin.getX() >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkX = (origin.getX() + size.getX() - 1) >> 4;
        int maxChunkZ = (origin.getZ() + size.getZ() - 1) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static BlockPos spawnPosition(int groundY) {
        return new BlockPos(0, groundY + 1, ORIGIN_Z - 20);
    }

    private static Catalog loadCatalog(MinecraftServer server) {
        Optional<Resource> resource = server.getResourceManager().getResource(CATALOG);
        if (resource.isEmpty()) {
            throw new BuilderException("구조물 카탈로그가 없습니다: " + CATALOG);
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int schemaVersion = root.get("schema_version").getAsInt();
            if (schemaVersion != 1) {
                throw new BuilderException("지원하지 않는 카탈로그 버전: " + schemaVersion);
            }
            int columns = root.get("columns").getAsInt();
            int cellSize = root.get("cell_size").getAsInt();
            String catalogHash = root.get("catalog_hash").getAsString();
            JsonArray values = root.getAsJsonArray("entries");
            List<Entry> entries = new ArrayList<>(values.size());
            Map<String, Boolean> ids = new LinkedHashMap<>();
            for (JsonElement value : values) {
                JsonObject entry = value.getAsJsonObject();
                JsonArray size = entry.getAsJsonArray("size");
                List<DoorAnchor> anchors = new ArrayList<>();
                List<NpcAnchor> npcs = new ArrayList<>();
                List<PointAnchor> points = new ArrayList<>();
                InteriorSpec interior = null;
                if (entry.has("interior") && !entry.get("interior").isJsonNull()) {
                    JsonObject valueInterior = entry.getAsJsonObject("interior");
                    interior = new InteriorSpec(
                        valueInterior.get("id").getAsString(),
                        valueInterior.get("width").getAsInt(),
                        valueInterior.get("depth").getAsInt(),
                        valueInterior.get("floor_height").getAsInt(),
                        valueInterior.get("floors").getAsInt()
                    );
                }
                if (entry.has("anchors")) {
                    for (JsonElement anchorElement : entry.getAsJsonArray("anchors")) {
                        JsonObject anchor = anchorElement.getAsJsonObject();
                        String role = anchor.get("type").getAsString();
                        if (role.equals("npc_position") || role.equals("easy_npc_spawn")) {
                            String label = anchor.has("label")
                                ? anchor.get("label").getAsString()
                                : anchor.get("id").getAsString();
                            npcs.add(new NpcAnchor(
                                label,
                                parsePosition(anchor.getAsJsonArray("position"))
                            ));
                        } else if (role.equals("door")) {
                            anchors.add(new DoorAnchor(
                                anchor.has("label") ? anchor.get("label").getAsString()
                                    : anchor.has("id") ? anchor.get("id").getAsString() : role,
                                role,
                                parsePosition(anchor.getAsJsonArray("position")),
                                parsePosition(anchor.getAsJsonArray("safe_spawn")),
                                anchor.get("door_facing").getAsString(),
                                anchor.get("safe_side").getAsString(),
                                anchor.has("seal_entry")
                                    && anchor.get("seal_entry").getAsBoolean()
                            ));
                        } else {
                            points.add(new PointAnchor(
                                anchor.get("id").getAsString(),
                                role,
                                parsePosition(anchor.getAsJsonArray("position")),
                                anchor.get("facing").getAsString()
                            ));
                        }
                    }
                }
                Entry parsed = new Entry(
                    entry.get("source").getAsString(),
                    entry.get("structure").getAsString(),
                    entry.get("export").getAsString(),
                    entry.get("label").getAsString(),
                    entry.get("category").getAsString(),
                    new Vec3i(
                        size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt()
                    ),
                    List.copyOf(anchors),
                    List.copyOf(npcs),
                    List.copyOf(points),
                    interior,
                    entry.has("interior_structure")
                        && !entry.get("interior_structure").isJsonNull()
                            ? entry.get("interior_structure").getAsString() : null
                );
                if (ids.put(parsed.exportId(), true) != null) {
                    throw new BuilderException("중복 내보내기 ID: " + parsed.exportId());
                }
                entries.add(parsed);
            }
            return new Catalog(catalogHash, columns, cellSize, List.copyOf(entries));
        } catch (IOException | RuntimeException error) {
            if (error instanceof BuilderException builderError) {
                throw builderError;
            }
            throw new BuilderException("구조물 카탈로그를 읽지 못했습니다.", error);
        }
    }

    private static BuilderData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BuilderData::new, BuilderData::load), DATA_FILE
        );
    }

    private static BlockPos parsePosition(JsonArray value) {
        if (value.size() != 3) {
            throw new BuilderException("앵커 좌표는 정수 3개여야 합니다.");
        }
        return new BlockPos(
            value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt()
        );
    }

    private record Catalog(
        String catalogHash, int columns, int cellSize, List<Entry> entries
    ) {
        int exteriorCount() {
            return (int) entries.stream()
                .filter(entry -> !entry.category().equals("interiors")).count();
        }

        int interiorCount() {
            return entries.size() - exteriorCount();
        }

        int exteriorRows() {
            return rows(exteriorCount());
        }

        int interiorRows() {
            return rows(interiorCount());
        }

        private int rows(int count) {
            return (count + columns - 1) / columns;
        }
    }

    private record Entry(
        String source, String structureId, String exportId,
        String label, String category, Vec3i size,
        List<DoorAnchor> anchors, List<NpcAnchor> npcs,
        List<PointAnchor> points, InteriorSpec interior,
        String interiorStructure
    ) {
    }

    private record PlannedEntry(
        Entry entry, int row, int column, int cellX, int cellZ, BlockPos origin
    ) {
        PlannedEntry withOrigin(BlockPos value) {
            return new PlannedEntry(entry, row, column, cellX, cellZ, value);
        }
    }

    private record DoorAnchor(
        String label, String role, BlockPos position, BlockPos safeSpawn,
        String doorFacing, String safeSide, boolean sealEntry
    ) {
    }

    private record NpcAnchor(String label, BlockPos position) {
    }

    private record PointAnchor(
        String id, String type, BlockPos position, String facing
    ) {
    }

    private record EditContext(
        String key, String label, BlockPos origin, Vec3i size, boolean interior
    ) {
    }

    private record InteriorPlot(
        String id, BlockPos origin, int width, int depth, int floorHeight, int floors
    ) {
        String key() {
            return "cobbleventure_builder:export/interiors/" + id;
        }

        Vec3i size() {
            return new Vec3i(width, floorHeight * floors, depth);
        }

        boolean contains(BlockPos position) {
            Vec3i bounds = size();
            return position.getX() >= origin.getX()
                && position.getX() < origin.getX() + bounds.getX()
                && position.getY() >= origin.getY()
                && position.getY() < origin.getY() + bounds.getY()
                && position.getZ() >= origin.getZ()
                && position.getZ() < origin.getZ() + bounds.getZ();
        }

        EditContext context() {
            return new EditContext(key(), id, origin, size(), true);
        }

        InteriorSpec spec() {
            return new InteriorSpec(id, width, depth, floorHeight, floors);
        }
    }

    private record InteriorSpec(
        String id, int width, int depth, int floorHeight, int floors
    ) {
    }

    private enum ToolMode {
        ENTRY("entry", "외부 입장문"),
        EXIT("exit", "내부 퇴장문"),
        DOOR("door", "이름 있는 문"),
        TELEPORT("teleport", "연결 이동"),
        SPAWN("spawn", "도착 위치"),
        ARRIVAL("arrival", "이름 있는 도착 지점"),
        NPC("npc", "NPC 위치"),
        INTERACTION("interaction", "상호작용 지점"),
        PATROL("patrol", "순찰 지점"),
        INSPECT("inspect", "구조물 정보");

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

        static ToolMode parse(String value) {
            return byId(value).orElse(ENTRY);
        }

        static Optional<ToolMode> byId(String value) {
            for (ToolMode mode : values()) {
                if (mode.id.equals(value)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    private static final class BuilderData extends SavedData {
        private boolean prepared;
        private String catalogHash = "";
        private int groundY;
        private final Map<String, Map<String, DoorAnchor>> doorAnchors = new LinkedHashMap<>();
        private final Map<String, Map<String, NpcAnchor>> npcAnchors = new LinkedHashMap<>();
        private final Map<String, Map<String, PointAnchor>> pointAnchors = new LinkedHashMap<>();
        private final Map<String, InteriorPlot> interiorPlots = new LinkedHashMap<>();
        private final Map<String, BlockPos> movedOrigins = new LinkedHashMap<>();

        static BuilderData load(CompoundTag tag, HolderLookup.Provider registries) {
            BuilderData data = new BuilderData();
            data.prepared = tag.getBoolean("prepared");
            data.catalogHash = tag.getString("catalogHash");
            data.groundY = tag.getInt("groundY");
            CompoundTag structures = tag.getCompound("doorAnchors");
            for (String structureId : structures.getAllKeys()) {
                CompoundTag roles = structures.getCompound(structureId);
                Map<String, DoorAnchor> anchors = new LinkedHashMap<>();
                for (String role : roles.getAllKeys()) {
                    CompoundTag value = roles.getCompound(role);
                    anchors.put(role, new DoorAnchor(
                        role,
                        value.contains("role") ? value.getString("role") : role,
                        readPosition(value, "position"),
                        readPosition(value, "safeSpawn"),
                        value.getString("doorFacing"),
                        value.getString("safeSide"),
                        value.getBoolean("sealEntry")
                    ));
                }
                data.doorAnchors.put(structureId, anchors);
            }
            CompoundTag npcStructures = tag.getCompound("npcAnchors");
            for (String structureId : npcStructures.getAllKeys()) {
                CompoundTag ids = npcStructures.getCompound(structureId);
                Map<String, NpcAnchor> anchors = new LinkedHashMap<>();
                for (String label : ids.getAllKeys()) {
                    CompoundTag value = ids.getCompound(label);
                    anchors.put(label, new NpcAnchor(
                        label, readPosition(value, "position")
                    ));
                }
                data.npcAnchors.put(structureId, anchors);
            }
            CompoundTag interiors = tag.getCompound("interiorPlots");
            for (String id : interiors.getAllKeys()) {
                CompoundTag value = interiors.getCompound(id);
                data.interiorPlots.put(id, new InteriorPlot(
                    id,
                    readPosition(value, "origin"),
                    value.getInt("width"),
                    value.getInt("depth"),
                    value.getInt("floorHeight"),
                    value.getInt("floors")
                ));
            }
            CompoundTag origins = tag.getCompound("movedOrigins");
            for (String key : origins.getAllKeys()) {
                data.movedOrigins.put(key, readPosition(origins, key));
            }
            CompoundTag pointStructures = tag.getCompound("pointAnchors");
            for (String structureId : pointStructures.getAllKeys()) {
                CompoundTag ids = pointStructures.getCompound(structureId);
                Map<String, PointAnchor> anchors = new LinkedHashMap<>();
                for (String id : ids.getAllKeys()) {
                    CompoundTag value = ids.getCompound(id);
                    anchors.put(id, new PointAnchor(
                        id,
                        value.getString("type"),
                        readPosition(value, "position"),
                        value.getString("facing")
                    ));
                }
                data.pointAnchors.put(structureId, anchors);
            }
            return data;
        }

        void complete(String hash, int groundY) {
            this.prepared = true;
            this.catalogHash = hash;
            this.groundY = groundY;
            setDirty();
        }

        void putAnchor(String structureId, DoorAnchor anchor) {
            doorAnchors.computeIfAbsent(structureId, ignored -> new LinkedHashMap<>())
                .put(anchor.label(), anchor);
            setDirty();
        }

        boolean removeAnchor(String structureId, String label) {
            Map<String, DoorAnchor> anchors = doorAnchors.get(structureId);
            if (anchors == null || anchors.remove(label) == null) {
                return false;
            }
            if (anchors.isEmpty()) {
                doorAnchors.remove(structureId);
            }
            setDirty();
            return true;
        }

        void replaceCatalogAnchors(Catalog catalog) {
            for (Entry entry : catalog.entries()) {
                doorAnchors.remove(entry.exportId());
                npcAnchors.remove(entry.exportId());
                pointAnchors.remove(entry.exportId());
                for (DoorAnchor anchor : entry.anchors()) {
                    doorAnchors.computeIfAbsent(
                        entry.exportId(), ignored -> new LinkedHashMap<>()
                    ).put(anchor.label(), anchor);
                }
                for (NpcAnchor anchor : entry.npcs()) {
                    npcAnchors.computeIfAbsent(
                        entry.exportId(), ignored -> new LinkedHashMap<>()
                    ).put(anchor.label(), anchor);
                }
                for (PointAnchor anchor : entry.points()) {
                    pointAnchors.computeIfAbsent(
                        entry.exportId(), ignored -> new LinkedHashMap<>()
                    ).put(anchor.id(), anchor);
                }
            }
            setDirty();
        }

        int removeAnchorsAt(String structureId, BlockPos position) {
            Map<String, DoorAnchor> anchors = doorAnchors.get(structureId);
            if (anchors == null) {
                return 0;
            }
            int before = anchors.size();
            anchors.values().removeIf(anchor -> anchor.position().equals(position));
            if (anchors.isEmpty()) {
                doorAnchors.remove(structureId);
            }
            int removed = before - anchors.size();
            if (removed > 0) {
                setDirty();
            }
            return removed;
        }

        List<DoorAnchor> anchors(String structureId) {
            Map<String, DoorAnchor> anchors = doorAnchors.get(structureId);
            return anchors == null ? List.of() : List.copyOf(anchors.values());
        }

        void putNpcAnchor(String structureId, NpcAnchor anchor) {
            npcAnchors.computeIfAbsent(structureId, ignored -> new LinkedHashMap<>())
                .put(anchor.label(), anchor);
            setDirty();
        }

        int removeNpcAnchorsAt(String structureId, BlockPos position) {
            Map<String, NpcAnchor> anchors = npcAnchors.get(structureId);
            if (anchors == null) {
                return 0;
            }
            int before = anchors.size();
            anchors.values().removeIf(anchor -> anchor.position().equals(position));
            int removed = before - anchors.size();
            if (anchors.isEmpty()) {
                npcAnchors.remove(structureId);
            }
            if (removed > 0) {
                setDirty();
            }
            return removed;
        }

        List<NpcAnchor> npcAnchors(String structureId) {
            Map<String, NpcAnchor> anchors = npcAnchors.get(structureId);
            return anchors == null ? List.of() : List.copyOf(anchors.values());
        }

        void putPointAnchor(String structureId, PointAnchor anchor) {
            pointAnchors.computeIfAbsent(structureId, ignored -> new LinkedHashMap<>())
                .put(anchor.id(), anchor);
            setDirty();
        }

        String nextPointId(String structureId, String type) {
            Map<String, PointAnchor> anchors = pointAnchors.get(structureId);
            String prefix = type.equals("patrol_point") ? "patrol_" : "interaction_";
            int index = 1;
            while (anchors != null && anchors.containsKey(prefix + index)) {
                index++;
            }
            return prefix + index;
        }

        int removePointAnchorsAt(String structureId, BlockPos position) {
            Map<String, PointAnchor> anchors = pointAnchors.get(structureId);
            if (anchors == null) {
                return 0;
            }
            int before = anchors.size();
            anchors.values().removeIf(anchor -> anchor.position().equals(position));
            int removed = before - anchors.size();
            if (anchors.isEmpty()) {
                pointAnchors.remove(structureId);
            }
            if (removed > 0) {
                setDirty();
            }
            return removed;
        }

        List<PointAnchor> pointAnchors(String structureId) {
            Map<String, PointAnchor> anchors = pointAnchors.get(structureId);
            return anchors == null ? List.of() : List.copyOf(anchors.values());
        }

        void moveSpace(String key, BlockPos origin) {
            for (Map.Entry<String, InteriorPlot> entry : interiorPlots.entrySet()) {
                InteriorPlot plot = entry.getValue();
                if (!plot.key().equals(key)) continue;
                entry.setValue(new InteriorPlot(
                    plot.id(), origin, plot.width(), plot.depth(),
                    plot.floorHeight(), plot.floors()
                ));
                setDirty();
                return;
            }
            movedOrigins.put(key, origin.immutable());
            setDirty();
        }

        BlockPos movedOrigin(String key, BlockPos fallback) {
            return movedOrigins.getOrDefault(key, fallback);
        }

        void addInterior(InteriorPlot plot) {
            interiorPlots.put(plot.id(), plot);
            setDirty();
        }

        void removeInterior(String id) {
            InteriorPlot removed = interiorPlots.remove(id);
            if (removed == null) {
                return;
            }
            doorAnchors.remove(removed.key());
            npcAnchors.remove(removed.key());
            pointAnchors.remove(removed.key());
            setDirty();
        }

        Optional<InteriorPlot> interior(String id) {
            return Optional.ofNullable(interiorPlots.get(id));
        }

        List<InteriorPlot> interiors() {
            return List.copyOf(interiorPlots.values());
        }

        int interiorCount() {
            return interiorPlots.size();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("prepared", prepared);
            tag.putString("catalogHash", catalogHash);
            tag.putInt("groundY", groundY);
            CompoundTag structures = new CompoundTag();
            for (Map.Entry<String, Map<String, DoorAnchor>> structure
                : doorAnchors.entrySet()) {
                CompoundTag roles = new CompoundTag();
                for (DoorAnchor anchor : structure.getValue().values()) {
                    CompoundTag value = new CompoundTag();
                    writePosition(value, "position", anchor.position());
                    writePosition(value, "safeSpawn", anchor.safeSpawn());
                    value.putString("role", anchor.role());
                    value.putString("doorFacing", anchor.doorFacing());
                    value.putString("safeSide", anchor.safeSide());
                    value.putBoolean("sealEntry", anchor.sealEntry());
                    roles.put(anchor.label(), value);
                }
                structures.put(structure.getKey(), roles);
            }
            tag.put("doorAnchors", structures);
            CompoundTag npcStructures = new CompoundTag();
            for (Map.Entry<String, Map<String, NpcAnchor>> structure
                : npcAnchors.entrySet()) {
                CompoundTag ids = new CompoundTag();
                for (NpcAnchor anchor : structure.getValue().values()) {
                    CompoundTag value = new CompoundTag();
                    writePosition(value, "position", anchor.position());
                    ids.put(anchor.label(), value);
                }
                npcStructures.put(structure.getKey(), ids);
            }
            tag.put("npcAnchors", npcStructures);
            CompoundTag pointStructures = new CompoundTag();
            for (Map.Entry<String, Map<String, PointAnchor>> structure
                : pointAnchors.entrySet()) {
                CompoundTag ids = new CompoundTag();
                for (PointAnchor anchor : structure.getValue().values()) {
                    CompoundTag value = new CompoundTag();
                    value.putString("type", anchor.type());
                    writePosition(value, "position", anchor.position());
                    value.putString("facing", anchor.facing());
                    ids.put(anchor.id(), value);
                }
                pointStructures.put(structure.getKey(), ids);
            }
            tag.put("pointAnchors", pointStructures);
            CompoundTag interiors = new CompoundTag();
            for (InteriorPlot plot : interiorPlots.values()) {
                CompoundTag value = new CompoundTag();
                writePosition(value, "origin", plot.origin());
                value.putInt("width", plot.width());
                value.putInt("depth", plot.depth());
                value.putInt("floorHeight", plot.floorHeight());
                value.putInt("floors", plot.floors());
                interiors.put(plot.id(), value);
            }
            tag.put("interiorPlots", interiors);
            CompoundTag origins = new CompoundTag();
            for (Map.Entry<String, BlockPos> entry : movedOrigins.entrySet()) {
                writePosition(origins, entry.getKey(), entry.getValue());
            }
            tag.put("movedOrigins", origins);
            return tag;
        }

        private static BlockPos readPosition(CompoundTag parent, String key) {
            CompoundTag value = parent.getCompound(key);
            return new BlockPos(value.getInt("x"), value.getInt("y"), value.getInt("z"));
        }

        private static void writePosition(CompoundTag parent, String key, BlockPos position) {
            CompoundTag value = new CompoundTag();
            value.putInt("x", position.getX());
            value.putInt("y", position.getY());
            value.putInt("z", position.getZ());
            parent.put(key, value);
        }
    }

    private static final class BuilderException extends RuntimeException {
        BuilderException(String message) {
            super(message);
        }

        BuilderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
