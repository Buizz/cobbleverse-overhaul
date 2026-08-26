package dev.buizz.cobbleventure.liveeditor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.InputConstants;
import dev.buizz.cobbleventure.liveeditor.LiveEditorNetwork;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class LiveEditorClient {
    private static final String KEY_CATEGORY = "key.categories.cobbleventure_live_nbt_editor";
    private static final KeyMapping TOOLS = new KeyMapping(
        "key.cobbleventure_live_nbt_editor.tools", GLFW.GLFW_KEY_V, KEY_CATEGORY
    );
    private static final KeyMapping SAVE = new KeyMapping(
        "key.cobbleventure_live_nbt_editor.save", KeyConflictContext.IN_GAME,
        KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_S, KEY_CATEGORY
    );
    private static final ResourceLocation HUD = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure_live_nbt_editor", "editor_hud"
    );
    private static final ResourceLocation EDIT_DIMENSION = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure_live_nbt_editor", "edit_world"
    );
    private static boolean focusBehaviorConfigured;
    private static boolean active;
    private static String structureId = "";
    private static BlockPos origin = BlockPos.ZERO;
    private static Vec3i size = new Vec3i(0, 0, 0);
    private static List<LiveEditorNetwork.Marker> markers = List.of();

    private LiveEditorClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(LiveEditorClient::registerKeys);
        modBus.addListener(LiveEditorClient::registerHud);
        NeoForge.EVENT_BUS.addListener(LiveEditorClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(LiveEditorClient::renderBounds);
    }

    public static void updateSnapshot(
        boolean hasActiveStructure, String id, BlockPos structureOrigin, Vec3i structureSize,
        List<LiveEditorNetwork.Marker> structureMarkers
    ) {
        active = hasActiveStructure;
        structureId = id;
        origin = structureOrigin;
        size = structureSize;
        markers = List.copyOf(structureMarkers);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOOLS);
        event.register(SAVE);
    }

    private static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD, LiveEditorClient::renderHud);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!focusBehaviorConfigured && minecraft.options != null) {
            focusBehaviorConfigured = true;
            if (minecraft.options.pauseOnLostFocus) {
                minecraft.options.pauseOnLostFocus = false;
                minecraft.options.save();
            }
        }
        if (minecraft.player != null && minecraft.screen == null && TOOLS.consumeClick()) {
            minecraft.setScreen(new EditorToolsScreen());
        }
        if (minecraft.player != null && minecraft.screen == null && SAVE.consumeClick()) {
            PacketDistributor.sendToServer(new LiveEditorNetwork.ManualSavePayload());
        }
    }

    private static void renderHud(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || minecraft.options.hideGui || !inEditDimension(minecraft)) return;
        String dimensions = size.getX() + " × " + size.getY() + " × " + size.getZ();
        String controls = dimensions + "  ·  [V] 영역 도구  ·  [Ctrl+S] 저장";
        int width = Math.max(190, Math.max(
            minecraft.font.width(structureId), minecraft.font.width(controls)
        ) + 26);
        graphics.fill(8, 8, 8 + width, 52, 0xD51A2028);
        graphics.fill(8, 8, 12, 52, 0xFF61D7FF);
        graphics.drawString(minecraft.font, structureId, 18, 15, 0xFFF5F7FA, false);
        graphics.drawString(
            minecraft.font, controls,
            18, 32, 0xFFB9C5D2, false
        );
    }

    private static void renderBounds(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !active) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!inEditDimension(minecraft)) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(
            origin.getX() - camera.x,
            origin.getY() - camera.y,
            origin.getZ() - camera.z
        );
        LevelRenderer.renderLineBox(
            pose, lines,
            new AABB(-0.01D, -0.01D, -0.01D,
                size.getX() + 0.01D, size.getY() + 0.01D, size.getZ() + 0.01D),
            0.38F, 0.84F, 1.0F, 0.95F
        );
        pose.popPose();
        for (LiveEditorNetwork.Marker marker : markers) {
            renderMarker(pose, lines, marker, camera);
        }
        buffers.endBatch(RenderType.lines());
        for (LiveEditorNetwork.Marker marker : markers) {
            renderMarkerName(pose, buffers, event, marker, camera);
        }
        buffers.endBatch();
    }

    private static void renderMarker(
        PoseStack pose, VertexConsumer lines, LiveEditorNetwork.Marker marker, Vec3 camera
    ) {
        BlockPos block = marker.position();
        pose.pushPose();
        pose.translate(block.getX() - camera.x, block.getY() - camera.y, block.getZ() - camera.z);
        switch (marker.type()) {
            case "npc_position" -> {
                renderBox(pose, lines, new AABB(0.22, 0.05, 0.22, 0.78, 1.45, 0.78),
                    0.25F, 0.9F, 1.0F);
                renderBox(pose, lines, new AABB(0.16, 1.45, 0.16, 0.84, 2.12, 0.84),
                    0.25F, 0.9F, 1.0F);
                renderFacing(pose, lines, marker.facing());
            }
            case "door" -> {
                renderBox(
                    pose, lines, new AABB(0.02, 0.02, 0.02, 0.98, 2.0, 0.98),
                    1.0F, 0.72F, 0.18F
                );
                if (marker.pairedPosition() != null) {
                    BlockPos offset = marker.pairedPosition().subtract(block);
                    renderBox(pose, lines, new AABB(
                        offset.getX() + 0.02D, 0.02D, offset.getZ() + 0.02D,
                        offset.getX() + 0.98D, 2.0D, offset.getZ() + 0.98D
                    ), 1.0F, 0.72F, 0.18F);
                }
            }
            case "transition" -> renderBox(
                pose, lines, new AABB(0.02, 0.02, 0.02, 0.98, 0.98, 0.98),
                0.75F, 0.3F, 1.0F
            );
            default -> renderBox(
                pose, lines, new AABB(0.15, 0.02, 0.15, 0.85, 0.18, 0.85),
                0.35F, 1.0F, 0.45F
            );
        }
        pose.popPose();
    }

    private static void renderBox(
        PoseStack pose, VertexConsumer lines, AABB box, float red, float green, float blue
    ) {
        LevelRenderer.renderLineBox(pose, lines, box, red, green, blue, 0.95F);
    }

    private static void renderFacing(PoseStack pose, VertexConsumer lines, String facingName) {
        net.minecraft.core.Direction facing = net.minecraft.core.Direction.byName(facingName);
        if (facing == null || facing.getAxis().isVertical()) {
            facing = net.minecraft.core.Direction.NORTH;
        }
        double endX = 0.5D + facing.getStepX() * 1.25D;
        double endZ = 0.5D + facing.getStepZ() * 1.25D;
        renderBox(pose, lines, new AABB(
            Math.min(0.5D, endX) - 0.045D, 1.08D,
            Math.min(0.5D, endZ) - 0.045D,
            Math.max(0.5D, endX) + 0.045D, 1.17D,
            Math.max(0.5D, endZ) + 0.045D
        ), 0.2F, 1.0F, 0.55F);
    }

    private static void renderMarkerName(
        PoseStack pose, MultiBufferSource.BufferSource buffers, RenderLevelStageEvent event,
        LiveEditorNetwork.Marker marker, Vec3 camera
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos block = marker.position();
        String prefix = switch (marker.type()) {
            case "npc_position" -> "NPC · ";
            case "door" -> marker.pairedPosition() == null
                ? "DOOR · " : "DOUBLE DOOR · ";
            case "transition" -> "TOUCH TRANSITION · ";
            default -> marker.type().toUpperCase(java.util.Locale.ROOT) + " · ";
        };
        String text = prefix + marker.label();
        double height = marker.type().equals("npc_position") ? 2.35D : 2.2D;
        double centerX = block.getX() + 0.5D;
        double centerZ = block.getZ() + 0.5D;
        if (marker.pairedPosition() != null) {
            centerX = (block.getX() + marker.pairedPosition().getX()) / 2.0D + 0.5D;
            centerZ = (block.getZ() + marker.pairedPosition().getZ()) / 2.0D + 0.5D;
        }
        pose.pushPose();
        pose.translate(
            centerX - camera.x,
            block.getY() + height - camera.y,
            centerZ - camera.z
        );
        pose.mulPose(event.getCamera().rotation());
        pose.scale(0.025F, -0.025F, 0.025F);
        float textX = -minecraft.font.width(text) / 2.0F;
        minecraft.font.drawInBatch(
            text, textX, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers,
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            0x80000000, 0x00F000F0
        );
        pose.popPose();
    }

    private static boolean inEditDimension(Minecraft minecraft) {
        return minecraft.level != null
            && minecraft.level.dimension().location().equals(EDIT_DIMENSION);
    }

    public static void openAnchorEditor(
        BlockPos position, boolean door, boolean transition
    ) {
        Minecraft.getInstance().setScreen(new AnchorEditorScreen(position, door, transition));
    }

    public static void openSaveConfirmation(
        String revision, String currentId, String nextId
    ) {
        Minecraft.getInstance().setScreen(
            new SaveBeforeOpenScreen(revision, currentId, nextId)
        );
    }

    private static final class SaveBeforeOpenScreen extends Screen {
        private final String revision;
        private final String currentId;
        private final String nextId;
        private boolean resolved;

        private SaveBeforeOpenScreen(String revision, String currentId, String nextId) {
            super(Component.literal("NBT 전환 확인"));
            this.revision = revision;
            this.currentId = currentId;
            this.nextId = nextId;
        }

        @Override
        protected void init() {
            int x = width / 2 - 160;
            int y = height / 2 - 65;
            addRenderableWidget(Button.builder(Component.literal("저장 후 전환"), button ->
                decide("save")
            ).bounds(x + 12, y + 82, 94, 20).build());
            addRenderableWidget(Button.builder(Component.literal("저장하지 않고 전환"), button ->
                decide("discard")
            ).bounds(x + 113, y + 82, 128, 20).build());
            addRenderableWidget(Button.builder(Component.literal("취소"), button ->
                decide("cancel")
            ).bounds(x + 248, y + 82, 60, 20).build());
        }

        private void decide(String decision) {
            if (resolved) return;
            resolved = true;
            PacketDistributor.sendToServer(
                new LiveEditorNetwork.OpenDecisionPayload(revision, decision)
            );
            super.onClose();
        }

        @Override
        public void onClose() {
            decide("cancel");
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) {}

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
            int x = width / 2 - 160;
            int y = height / 2 - 65;
            graphics.fill(x + 3, y + 3, x + 323, y + 123, 0x80000000);
            graphics.fill(x, y, x + 320, y + 120, 0xF01C222B);
            graphics.fill(x, y, x + 320, y + 2, 0xFFFFB74D);
            graphics.drawCenteredString(font, title, width / 2, y + 13, 0xFFFFFFFF);
            graphics.drawCenteredString(
                font, currentId + " → " + nextId, width / 2, y + 35, 0xFFB9C5D2
            );
            graphics.drawCenteredString(
                font, "현재 NBT의 변경 내용을 저장할까요?", width / 2, y + 55, 0xFFFFFFFF
            );
            super.render(graphics, mouseX, mouseY, tick);
        }
    }

    private static final class EditorToolsScreen extends Screen {
        private EditorToolsScreen() {
            super(Component.literal("라이브 NBT 영역 도구"));
        }

        @Override
        protected void init() {
            int x = width / 2 - 130;
            int y = height / 2 - 45;
            Button select = Button.builder(Component.literal("WorldEdit 영역 선택"), button -> {
                PacketDistributor.sendToServer(new LiveEditorNetwork.SelectWorldEditPayload());
                onClose();
            }).bounds(x + 14, y + 52, 232, 20).build();
            select.active = active;
            addRenderableWidget(select);
            addRenderableWidget(Button.builder(Component.literal("닫기"), button -> onClose())
                .bounds(x + 80, y + 80, 100, 20).build());
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) {}

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
            int x = width / 2 - 130;
            int y = height / 2 - 45;
            graphics.fill(x + 3, y + 3, x + 263, y + 113, 0x80000000);
            graphics.fill(x, y, x + 260, y + 110, 0xF01C222B);
            graphics.fill(x, y, x + 260, y + 2, 0xFF61D7FF);
            graphics.drawCenteredString(font, title, width / 2, y + 13, 0xFFFFFFFF);
            graphics.drawCenteredString(
                font,
                active ? structureId + " · " + size.getX() + "×" + size.getY() + "×" + size.getZ()
                    : "먼저 웹에서 NBT를 여세요.",
                width / 2, y + 31, active ? 0xFFB9C5D2 : 0xFFFF9C79
            );
            super.render(graphics, mouseX, mouseY, tick);
        }
    }

    private static final class AnchorEditorScreen extends Screen {
        private static final int PANEL = 0xF01C222B;
        private static final int BORDER = 0xFF61D7FF;
        private final BlockPos position;
        private final boolean door;
        private final boolean transition;
        private EditBox label;
        private final String type;

        private AnchorEditorScreen(BlockPos position, boolean door, boolean transition) {
            super(Component.literal(
                door ? "연결 문 설정" : transition ? "접촉 전환 영역 설정" : "NPC 위치 설정"
            ));
            this.position = position;
            this.door = door;
            this.transition = transition;
            this.type = door ? "door" : transition ? "transition" : "npc_position";
        }

        @Override
        protected void init() {
            int x = width / 2 - 150;
            int y = height / 2 - 90;
            label = new EditBox(font, x + 14, y + 47, 272, 20, Component.literal("이름"));
            label.setMaxLength(64);
            label.setFilter(value -> value.isEmpty() || value.matches("[a-z0-9_]+"));
            label.setValue(door ? "door" : transition ? "transition" : "npc");
            addRenderableWidget(label);

            if (!door && !transition) {
                addRenderableWidget(Button.builder(Component.literal("관장 NPC"), button ->
                    label.setValue("leader")
                ).bounds(x + 14, y + 76, 272, 20).build());
            }

            int actionsY = y + 134;
            addRenderableWidget(Button.builder(Component.literal("저장"), button -> save())
                .bounds(x + 14, actionsY, 84, 20).build());
            addRenderableWidget(Button.builder(Component.literal("위치 삭제"), button -> {
                send("delete", "anchor");
                onClose();
            }).bounds(x + 108, actionsY, 84, 20).build());
            addRenderableWidget(Button.builder(Component.literal("취소"), button -> onClose())
                .bounds(x + 202, actionsY, 84, 20).build());
            setInitialFocus(label);
        }

        private void save() {
            String value = label.getValue().trim();
            if (value.isEmpty()) return;
            send(type, value);
            onClose();
        }

        private void send(String selectedType, String value) {
            PacketDistributor.sendToServer(
                new LiveEditorNetwork.ApplyAnchorPayload(position, selectedType, value)
            );
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) {}

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
            int x = width / 2 - 150;
            int y = height / 2 - 90;
            graphics.fill(x + 3, y + 3, x + 303, y + 173, 0x80000000);
            graphics.fill(x, y, x + 300, y + 170, PANEL);
            graphics.fill(x, y, x + 300, y + 2, BORDER);
            graphics.drawString(font, title, x + 14, y + 13, 0xFFFFFFFF, false);
            String target = door ? "실제 문 → 연결 문"
                : transition ? "연결된 베리어 → 접촉 이동 영역"
                : "유형을 선택한 뒤 이름 입력";
            graphics.drawString(
                font, target + "  @ " + position.toShortString(),
                x + 14, y + 29, 0xFFB9C5D2, false
            );
            if (!door && !transition) graphics.drawString(
                font, "저장 시 바라보는 방향 = NPC 방향",
                x + 14, y + 110, 0xFF8797A8, false
            );
            super.render(graphics, mouseX, mouseY, tick);
        }
    }
}
