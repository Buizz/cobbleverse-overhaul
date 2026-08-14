package dev.buizz.cobbleventure.structurebuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.buizz.cobbleventure.structurebuilder.BuilderEditorNetwork;
import dev.buizz.cobbleventure.structurebuilder.StructureBuilderMod;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client-only visual editor for the standalone structure builder world. */
public final class BuilderEditorClient {
    private static final String CATEGORY = "key.categories.cobbleventure_structure_builder";
    private static final KeyMapping TOOLBAR = new KeyMapping(
        "key.cobbleventure_structure_builder.toolbar", GLFW.GLFW_KEY_V, CATEGORY
    );
    private static final KeyMapping TRAVEL = new KeyMapping(
        "key.cobbleventure_structure_builder.travel", GLFW.GLFW_KEY_G, CATEGORY
    );
    private static final KeyMapping RESIZE = new KeyMapping(
        "key.cobbleventure_structure_builder.resize", GLFW.GLFW_KEY_H, CATEGORY
    );
    private static final ResourceLocation HUD = ResourceLocation.fromNamespaceAndPath(
        StructureBuilderMod.MOD_ID, "editor_hud"
    );
    private static BuilderEditorNetwork.SnapshotPayload snapshot = emptySnapshot();
    private static int refreshTicks;

    private BuilderEditorClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BuilderEditorClient::registerKeys);
        modBus.addListener(BuilderEditorClient::registerHud);
        NeoForge.EVENT_BUS.addListener(BuilderEditorClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(BuilderEditorClient::renderMarkers);
    }

    public static void update(BuilderEditorNetwork.SnapshotPayload value) {
        snapshot = value;
    }

    public static void openAnchorEditor(BlockPos position, boolean door) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new AnchorEditorScreen(position, door));
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOOLBAR);
        event.register(TRAVEL);
        event.register(RESIZE);
    }

    private static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD, BuilderEditorClient::renderHud);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            BuilderEditorNetwork.requestSnapshot();
        }
        if (minecraft.screen != null) return;
        if (TOOLBAR.consumeClick()) minecraft.setScreen(new ToolbarScreen());
        else if (TRAVEL.consumeClick()) minecraft.setScreen(new TravelScreen());
        else if (RESIZE.consumeClick()) minecraft.setScreen(new ResizeScreen());
    }

    private static void renderHud(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) return;
        Font font = minecraft.font;
        String kind = snapshot.interior() ? "INTERIOR" : "EXTERIOR";
        String name = snapshot.currentLabel().isBlank() ? "체크무늬 작업장" : snapshot.currentLabel();
        String key = snapshot.currentKey().isBlank() ? "공간 밖" : snapshot.currentKey();
        int panelWidth = Math.max(190, Math.min(360, Math.max(font.width(name), font.width(key)) + 28));
        graphics.fill(8, 8, 8 + panelWidth, 55, 0xD51A2028);
        graphics.fill(8, 8, 12, 55, snapshot.interior() ? 0xFF61D7FF : 0xFFA8E65C);
        graphics.drawString(font, kind + "  " + name, 18, 15, 0xFFF5F7FA, false);
        graphics.drawString(font, key, 18, 29, 0xFFB9C5D2, false);
        graphics.drawString(font, snapshot.size().getX() + " x " + snapshot.size().getY() + " x " + snapshot.size().getZ()
            + "  ·  앵커 " + snapshot.markers().size() + "개", 18, 42, 0xFF8797A8, false);

        String hint = "[V] 저장·이동·크기 도구     [G] 공간 이동     [H] 내부 크기 변경";
        int width = font.width(hint) + 24;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 82;
        graphics.fill(x, y, x + width, y + 24, 0xD51A2028);
        graphics.fill(x, y, x + width, y + 2, 0xFF61D7FF);
        graphics.drawString(font, hint, x + 12, y + 8, 0xFFF5F7FA, false);
        graphics.drawCenteredString(font, "막대기 우클릭: 실제 문은 연결 문으로, 일반 블록은 NPC 위치로 설정합니다.",
            graphics.guiWidth() / 2, y - 12, 0xFFE6EEF6);
    }

    private static void renderMarkers(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || snapshot.markers().isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BuilderEditorNetwork.Marker marker : snapshot.markers()) {
            BlockPos block = marker.position();
            if (minecraft.player.distanceToSqr(block.getX(), block.getY(), block.getZ()) > 96.0D * 96.0D) continue;
            pose.pushPose();
            pose.translate(block.getX() - camera.x, block.getY() - camera.y, block.getZ() - camera.z);
            if (marker.type().equals("npc_position")) {
                renderBox(pose, lines, new AABB(0.22, 0.05, 0.22, 0.78, 1.45, 0.78), 0.25F, 0.9F, 1.0F);
                renderBox(pose, lines, new AABB(0.16, 1.45, 0.16, 0.84, 2.12, 0.84), 0.25F, 0.9F, 1.0F);
            } else if (isDoorMarker(marker)) {
                renderBox(pose, lines, new AABB(0.02, 0.02, 0.02, 0.98, 2.0, 0.98), 1.0F, 0.72F, 0.18F);
                if (marker.pairedPosition() != null) {
                    BlockPos offset = marker.pairedPosition().subtract(block);
                    renderBox(pose, lines, new AABB(
                        offset.getX() + 0.02, 0.02, offset.getZ() + 0.02,
                        offset.getX() + 0.98, 2.0, offset.getZ() + 0.98
                    ), 1.0F, 0.72F, 0.18F);
                }
            } else {
                renderBox(pose, lines, new AABB(0.15, 0.02, 0.15, 0.85, 0.18, 0.85), 0.35F, 1.0F, 0.45F);
            }
            pose.popPose();
        }
        buffers.endBatch(RenderType.lines());
        for (BuilderEditorNetwork.Marker marker : snapshot.markers()) {
            BlockPos block = marker.position();
            if (minecraft.player.distanceToSqr(block.getX(), block.getY(), block.getZ()) <= 96.0D * 96.0D) {
                renderMarkerName(pose, buffers, event, marker, camera);
            }
        }
        buffers.endBatch();
    }

    private static void renderBox(PoseStack pose, VertexConsumer lines, AABB box, float r, float g, float b) {
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 0.95F);
    }

    private static void renderMarkerName(
        PoseStack pose, MultiBufferSource.BufferSource buffers, RenderLevelStageEvent event,
        BuilderEditorNetwork.Marker marker, Vec3 camera
    ) {
        BlockPos block = marker.position();
        float height = marker.type().equals("npc_position") ? 2.35F : 2.2F;
        String text = marker.type().equals("npc_position")
            ? "NPC · " + marker.label() : isDoorMarker(marker)
                ? (marker.pairedPosition() == null ? "DOOR · " : "DOUBLE DOOR · ")
                    + marker.label()
                : "ARRIVAL · " + marker.label();
        double centerX = block.getX() + 0.5D;
        double centerZ = block.getZ() + 0.5D;
        if (marker.pairedPosition() != null) {
            centerX = (block.getX() + marker.pairedPosition().getX()) / 2.0D + 0.5D;
            centerZ = (block.getZ() + marker.pairedPosition().getZ()) / 2.0D + 0.5D;
        }
        pose.pushPose();
        pose.translate(centerX - camera.x, block.getY() + height - camera.y, centerZ - camera.z);
        pose.mulPose(event.getCamera().rotation());
        pose.scale(0.025F, -0.025F, 0.025F);
        float x = -minecraftFont().width(text) / 2.0F;
        minecraftFont().drawInBatch(text, x, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers,
            Font.DisplayMode.SEE_THROUGH, 0x80000000, 0x00F000F0);
        pose.popPose();
    }

    private static boolean isDoorMarker(BuilderEditorNetwork.Marker marker) {
        return marker.type().equals("door")
            || marker.type().equals("interior_entry")
            || marker.type().equals("interior_exit");
    }

    private static Font minecraftFont() {
        return Minecraft.getInstance().font;
    }

    private static BuilderEditorNetwork.SnapshotPayload emptySnapshot() {
        return new BuilderEditorNetwork.SnapshotPayload(
            "", "", false, new net.minecraft.core.Vec3i(0, 0, 0), List.of(), List.of()
        );
    }

    private abstract static class EditorScreen extends Screen {
        static final int PANEL = 0xF01C222B;
        static final int BORDER = 0xFF61D7FF;
        EditorScreen(String title) { super(Component.literal(title)); }
        @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) {}
        @Override public boolean isPauseScreen() { return false; }
        void panel(GuiGraphics graphics, int x, int y, int w, int h) {
            graphics.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x80000000);
            graphics.fill(x, y, x + w, y + h, PANEL);
            graphics.fill(x, y, x + w, y + 2, BORDER);
        }
    }

    private static final class ToolbarScreen extends EditorScreen {
        ToolbarScreen() { super("코블벤처 건축 도구"); }
        @Override protected void init() {
            int x = width / 2 - 138;
            int y = height / 2 - 35;
            addRenderableWidget(Button.builder(Component.literal("현재 NBT 저장"), b -> {
                BuilderEditorNetwork.saveCurrent(); onClose();
            }).bounds(x, y, 88, 20).build());
            addRenderableWidget(Button.builder(Component.literal("공간 이동"), b -> minecraft.setScreen(new TravelScreen()))
                .bounds(x + 94, y, 88, 20).build());
            addRenderableWidget(Button.builder(Component.literal("내부 크기 변경"), b -> minecraft.setScreen(new ResizeScreen()))
                .bounds(x + 188, y, 88, 20).build());
            addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
                .bounds(x + 88, y + 30, 100, 20).build());
        }
        @Override public void render(GuiGraphics g, int mx, int my, float tick) {
            int x = width / 2 - 150, y = height / 2 - 73;
            panel(g, x, y, 300, 126);
            g.drawCenteredString(font, title, width / 2, y + 13, 0xFFFFFFFF);
            g.drawCenteredString(font, "막대기 우클릭: 선택한 블록에 앵커 편집", width / 2, y + 31, 0xFFB9C5D2);
            super.render(g, mx, my, tick);
        }
    }

    private static final class AnchorEditorScreen extends EditorScreen {
        private final BlockPos position;
        private final boolean door;
        private EditBox label;
        private String type;
        AnchorEditorScreen(BlockPos position, boolean door) {
            super(door ? "연결 문 설정" : "NPC 위치 설정");
            this.position = position;
            this.door = door;
            this.type = door ? "door" : "npc_position";
        }
        @Override protected void init() {
            int x = width / 2 - 130, y = height / 2 - 66;
            label = new EditBox(font, x + 14, y + 47, 232, 20, Component.literal("이름"));
            label.setMaxLength(64);
            label.setValue(door ? "door" : "npc");
            snapshot.markers().stream()
                .filter(marker -> marker.position().equals(position) || marker.position().equals(position.above())
                    || marker.position().equals(position.below()))
                .findFirst().ifPresent(marker -> {
                    type = marker.type().equals("door") ? "door" : "npc_position";
                    label.setValue(marker.label());
                });
            addRenderableWidget(label);
            if (!door) {
                addRenderableWidget(Button.builder(Component.literal("관장 NPC"), b -> { type = "npc_position"; label.setValue("leader"); })
                    .bounds(x + 14, y + 76, 232, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("저장"), b -> save())
                .bounds(x + 14, y + 106, 72, 20).build());
            addRenderableWidget(Button.builder(Component.literal("위치 삭제"), b -> {
                BuilderEditorNetwork.applyAnchor(position, "delete", "anchor"); onClose();
            }).bounds(x + 94, y + 106, 72, 20).build());
            addRenderableWidget(Button.builder(Component.literal("취소"), b -> onClose())
                .bounds(x + 174, y + 106, 72, 20).build());
            setInitialFocus(label);
        }
        private void save() {
            String value = label.getValue().trim();
            if (value.isEmpty()) return;
            BuilderEditorNetwork.applyAnchor(position, type, value);
            onClose();
        }
        @Override public void render(GuiGraphics g, int mx, int my, float tick) {
            int x = width / 2 - 130, y = height / 2 - 66;
            panel(g, x, y, 260, 142);
            g.drawString(font, title, x + 14, y + 13, 0xFFFFFFFF, false);
            g.drawString(font, "선택: " + (door ? "실제 문 → 연결 문" : "NPC 생성 위치")
                + "  @ " + position.toShortString(), x + 14, y + 29, 0xFFB9C5D2, false);
            super.render(g, mx, my, tick);
        }
    }

    private static final class TravelScreen extends EditorScreen {
        private int page;
        TravelScreen() { super("공간 이동"); }
        @Override protected void init() {
            clearWidgets();
            int x = width / 2 - 150, y = height / 2 - 113;
            List<BuilderEditorNetwork.Space> spaces = snapshot.spaces();
            int from = page * 8;
            for (int i = from; i < Math.min(from + 8, spaces.size()); i++) {
                BuilderEditorNetwork.Space space = spaces.get(i);
                String prefix = space.interior() ? "[내부] " : "[외부] ";
                addRenderableWidget(Button.builder(Component.literal(prefix + space.label()), b -> {
                    BuilderEditorNetwork.teleport(space.key()); onClose();
                }).bounds(x + 12, y + 35 + (i - from) * 23, 276, 20).build());
            }
            if (page > 0) addRenderableWidget(Button.builder(Component.literal("← 이전"), b -> { page--; rebuildWidgets(); })
                .bounds(x + 12, y + 222, 78, 20).build());
            if (from + 8 < spaces.size()) addRenderableWidget(Button.builder(Component.literal("다음 →"), b -> { page++; rebuildWidgets(); })
                .bounds(x + 210, y + 222, 78, 20).build());
            addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
                .bounds(x + 110, y + 222, 80, 20).build());
        }
        @Override public void render(GuiGraphics g, int mx, int my, float tick) {
            int x = width / 2 - 150, y = height / 2 - 113;
            panel(g, x, y, 300, 254);
            g.drawString(font, title + "  ·  " + snapshot.spaces().size() + "개", x + 12, y + 13, 0xFFFFFFFF, false);
            super.render(g, mx, my, tick);
        }
    }

    private static final class ResizeScreen extends EditorScreen {
        private EditBox widthBox, depthBox, heightBox, floorsBox;
        private boolean allowed;
        ResizeScreen() { super("내부 크기 변경"); }
        @Override protected void init() {
            int x = width / 2 - 130, y = height / 2 - 76;
            BuilderEditorNetwork.Space current = snapshot.spaces().stream()
                .filter(s -> s.key().equals(snapshot.currentKey())).findFirst().orElse(null);
            int floorHeight = current == null ? Math.max(3, snapshot.size().getY()) : current.floorHeight();
            int floors = current == null ? 1 : current.floors();
            widthBox = field(x + 14, y + 49, Math.max(5, snapshot.size().getX()));
            depthBox = field(x + 130, y + 49, Math.max(5, snapshot.size().getZ()));
            heightBox = field(x + 14, y + 89, floorHeight);
            floorsBox = field(x + 130, y + 89, floors);
            allowed = current != null && current.resizable();
            Button apply = Button.builder(Component.literal("적용"), b -> apply())
                .bounds(x + 40, y + 126, 82, 20).build();
            apply.active = allowed;
            addRenderableWidget(apply);
            addRenderableWidget(Button.builder(Component.literal("취소"), b -> onClose())
                .bounds(x + 138, y + 126, 82, 20).build());
        }
        private EditBox field(int x, int y, int value) {
            EditBox box = new EditBox(font, x, y, 106, 20, Component.empty());
            box.setFilter(text -> text.matches("\\d*")); box.setValue(Integer.toString(value));
            addRenderableWidget(box); return box;
        }
        private void apply() {
            try {
                BuilderEditorNetwork.resize(Integer.parseInt(widthBox.getValue()), Integer.parseInt(depthBox.getValue()),
                    Integer.parseInt(heightBox.getValue()), Integer.parseInt(floorsBox.getValue()));
                onClose();
            } catch (NumberFormatException ignored) {}
        }
        @Override public void render(GuiGraphics g, int mx, int my, float tick) {
            int x = width / 2 - 130, y = height / 2 - 76;
            panel(g, x, y, 260, 166);
            g.drawString(font, title, x + 14, y + 13, 0xFFFFFFFF, false);
            g.drawString(font, allowed ? snapshot.currentLabel() : "내부 NBT 작업 영역에서만 변경 가능", x + 14, y + 29,
                allowed ? 0xFFB9C5D2 : 0xFFFF9C79, false);
            g.drawString(font, "너비", x + 14, y + 39, 0xFFB9C5D2, false);
            g.drawString(font, "깊이", x + 130, y + 39, 0xFFB9C5D2, false);
            g.drawString(font, "층 높이", x + 14, y + 79, 0xFFB9C5D2, false);
            g.drawString(font, "층수", x + 130, y + 79, 0xFFB9C5D2, false);
            super.render(g, mx, my, tick);
        }
    }
}
