package dev.buizz.cobbleventure.bootstrap.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
import dev.buizz.cobbleventure.playermenu.client.MenuTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;
import org.joml.Matrix4f;

/** Renders NPC names as compact world-space panels using the global menu theme. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class NpcNameTagRenderer {
    private static final ResourceLocation COBBLE_MERCHANT =
        ResourceLocation.fromNamespaceAndPath("cobbledollars", "cobble_merchant");
    private static final ResourceLocation WORLD_NAME_FONT =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "battle");
    private static final float NAME_TAG_SCALE = 0.021F;
    private static final int HORIZONTAL_PADDING = 4;
    private static final int PANEL_TOP = -3;
    private static final int PANEL_BOTTOM = 12;
    private static final float PANEL_DEPTH = 0.01F;

    private static MenuTheme theme;

    private NpcNameTagRenderer() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderNameTag(RenderNameTagEvent event) {
        Entity entity = event.getEntity();
        if (!handles(entity) || !shouldRender(event, entity)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        double distanceSquared = minecraft.getEntityRenderDispatcher().distanceToSqr(entity);
        if (!ClientHooks.isNameplateInRenderDistance(entity, distanceSquared)) {
            return;
        }

        Component name = event.getContent().copy().withStyle(
            style -> style.withFont(WORLD_NAME_FONT)
        );
        renderNameTag(
            entity, name, event.getPoseStack(), event.getMultiBufferSource(),
            event.getPartialTick(), minecraft
        );
        event.setCanRender(TriState.FALSE);
    }

    private static void renderNameTag(
        Entity entity,
        Component name,
        PoseStack poseStack,
        MultiBufferSource buffers,
        float partialTick,
        Minecraft minecraft
    ) {
        Font font = minecraft.font;
        MenuTheme menuTheme = theme();
        Component themedName = name.copy().withStyle(
            style -> style.withColor(menuTheme.textColor() & 0x00FFFFFF)
        );
        int textWidth = font.width(themedName);
        float textX = -textWidth / 2.0F;
        int left = -textWidth / 2 - HORIZONTAL_PADDING;
        int right = (textWidth + 1) / 2 + HORIZONTAL_PADDING;
        Vec3 anchor = entity.getAttachments().getNullable(
            EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick)
        );
        if (anchor == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(anchor.x, anchor.y + 0.5D, anchor.z);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(NAME_TAG_SCALE, -NAME_TAG_SCALE, NAME_TAG_SCALE);
        Matrix4f pose = poseStack.last().pose();
        VertexConsumer background = buffers.getBuffer(RenderType.textBackground());

        fillPanel(background, pose, left, PANEL_TOP, right, PANEL_BOTTOM,
            opaque(menuTheme.border()), PANEL_DEPTH, LightTexture.FULL_BRIGHT);
        fillPanel(background, pose, left + 1, PANEL_TOP + 1, right - 1, PANEL_BOTTOM - 1,
            opaque(menuTheme.innerBorder()), PANEL_DEPTH, LightTexture.FULL_BRIGHT);
        fillPanel(background, pose, left + 2, PANEL_TOP + 2, right - 2, PANEL_BOTTOM - 2,
            opaque(menuTheme.background()), PANEL_DEPTH, LightTexture.FULL_BRIGHT);
        fillPanel(background, pose, left + 4, PANEL_TOP + 1, right - 4, PANEL_TOP + 2,
            opaque(menuTheme.accent()), PANEL_DEPTH, LightTexture.FULL_BRIGHT);
        font.drawInBatch(
            themedName, textX, 1.0F, 0xFFFFFFFF, false, pose, buffers,
            Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT
        );
        poseStack.popPose();
    }

    private static void fillPanel(
        VertexConsumer vertices,
        Matrix4f pose,
        float left,
        float top,
        float right,
        float bottom,
        int color,
        float depth,
        int packedLight
    ) {
        vertices.addVertex(pose, left, top, depth).setColor(color).setLight(packedLight);
        vertices.addVertex(pose, left, bottom, depth).setColor(color).setLight(packedLight);
        vertices.addVertex(pose, right, bottom, depth).setColor(color).setLight(packedLight);
        vertices.addVertex(pose, right, top, depth).setColor(color).setLight(packedLight);
    }

    private static int opaque(int color) {
        return 0xFF000000 | color & 0x00FFFFFF;
    }

    private static MenuTheme theme() {
        if (theme == null) {
            theme = MenuTheme.load(Minecraft.getInstance());
        }
        return theme;
    }

    public static boolean handles(Entity entity) {
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return entityType.getNamespace().equals("easy_npc") || entityType.equals(COBBLE_MERCHANT);
    }

    private static boolean shouldRender(RenderNameTagEvent event, Entity entity) {
        if (event.canRender().isFalse()) {
            return false;
        }
        if (event.canRender().isTrue()) {
            return true;
        }
        return entity.shouldShowName()
            || entity.hasCustomName()
            && entity == Minecraft.getInstance().getEntityRenderDispatcher().crosshairPickEntity;
    }
}
