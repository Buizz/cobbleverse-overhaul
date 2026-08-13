package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.buizz.cobbleventure.playermenu.BadgeProgressNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import org.lwjgl.glfw.GLFW;

/** 본가 포켓몬의 트레이너 카드를 바탕으로 한 프로필 및 리그 진행 화면. */
public final class TrainerCardScreen extends Screen {
    private static final int CARD_RED = 0xFFF06F5E;
    private static final int CARD_RED_DARK = 0xFF9D392F;
    private static final int CARD_RED_LIGHT = 0xFFFF9A83;
    private static final int CARD_CREAM = 0xFFFFE9D5;
    private static final int CARD_CREAM_ALT = 0xFFFFDCCA;
    private static final int INK = 0xFF492F32;
    private static final int MUTED_INK = 0xFF8A5B59;
    private static final int PAGE_BACKGROUND = 0x9A18202A;
    private static final int BADGE_EMPTY = 0xFFB9A69D;
    private static final int BADGE_COMPLETE = 0xFFFFCB49;
    private static final int LEAGUE_COMPLETE = 0xFF75C6C8;
    private static final int CARD_MAX_WIDTH = 386;
    private static final int CARD_MAX_HEIGHT = 224;

    private final Screen parent;
    private TrainerCardProgress progress;
    private final boolean liveProgress;
    private final Map<String, CardLeader> leaderModels = new HashMap<>();
    private int badgeSnapshotHash;
    private int pageIndex;
    private int animationTick;
    private boolean showingBack;
    private Button flipButton;
    private Button previousButton;
    private Button nextButton;
    private int cardX;
    private int cardY;
    private int cardWidth;
    private int cardHeight;

    public TrainerCardScreen(Screen parent) {
        this(parent, TrainerCardProgress.current(), true);
    }

    TrainerCardScreen(Screen parent, TrainerCardProgress progress) {
        this(parent, progress, false);
    }

    private TrainerCardScreen(Screen parent, TrainerCardProgress progress, boolean liveProgress) {
        super(Component.translatable("screen.cobbleventure_player_menu.trainer_card.title"));
        this.parent = parent;
        this.progress = progress;
        this.liveProgress = liveProgress;
    }

    @Override
    protected void init() {
        super.init();
        if (liveProgress) BadgeProgressNetwork.requestSnapshot();
        cardWidth = Math.min(CARD_MAX_WIDTH, Math.max(180, width - 24));
        cardHeight = Math.min(CARD_MAX_HEIGHT, Math.max(150, height - 42));
        cardX = (width - cardWidth) / 2;
        cardY = Math.max(8, (height - cardHeight) / 2 - 5);

        int buttonY = Math.min(height - 24, cardY + cardHeight + 6);
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.trainer_card.back"),
            ignored -> onClose()
        ).bounds(cardX + cardWidth - 64, buttonY, 64, 20).build());

        flipButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.trainer_card.show_back"),
            ignored -> flipCard()
        ).bounds(cardX + cardWidth - 138, buttonY, 70, 20).build());
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> changePage(-1))
            .bounds(cardX, buttonY, 24, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> changePage(1))
            .bounds(cardX + 28, buttonY, 24, 20).build());
        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        animationTick++;
        if (!liveProgress) return;
        int currentHash = BadgeProgressNetwork.clientBadges().hashCode();
        if (currentHash != badgeSnapshotHash) {
            badgeSnapshotHash = currentHash;
            progress = TrainerCardProgress.current();
            pageIndex = Math.min(pageIndex, Math.max(0, progress.pages().size() - 1));
            updateButtons();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, PAGE_BACKGROUND);
        drawCard(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 카드 바깥으로 월드가 비치도록 기본 블러를 사용하지 않는다.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT && showingBack && progress.pages().size() > 1) {
            changePage(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT && showingBack && progress.pages().size() > 1) {
            changePage(1);
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawCard(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawCardFrame(graphics);
        if (showingBack) {
            drawBackCard(graphics, mouseX, mouseY, partialTick);
        } else {
            drawFrontCard(graphics, mouseX, mouseY);
        }
    }

    private void drawFrontCard(GuiGraphics graphics, int mouseX, int mouseY) {
        int padding = 8;
        int headerHeight = 22;
        int contentTop = cardY + headerHeight;
        int portraitWidth = Math.max(76, cardWidth / 4);
        int infoRight = cardX + cardWidth - portraitWidth - padding;

        drawHeader(graphics, title, Component.translatable("screen.cobbleventure_player_menu.trainer_card.front"), padding, headerHeight);
        drawIdentityRows(graphics, cardX + padding, contentTop + 5, infoRight - cardX - padding);
        drawPortrait(graphics, infoRight + 2, contentTop + 2, cardX + cardWidth - padding, cardY + cardHeight - padding, mouseX, mouseY);
        graphics.drawString(font, Component.translatable("screen.cobbleventure_player_menu.trainer_card.flip_hint"),
            cardX + padding + 5, cardY + cardHeight - 18, 0xFFFFD8CA, false);
    }

    private void drawBackCard(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TrainerCardProgress.LeaguePage page = progress.pages().get(pageIndex);
        int padding = 8;
        int headerHeight = 22;
        drawHeader(graphics, page.title(), Component.literal((pageIndex + 1) + "/" + progress.pages().size()), padding, headerHeight);
        drawLeaderBadgeGrid(graphics, cardX + padding, cardY + headerHeight + 5,
            cardWidth - padding * 2, cardHeight - headerHeight - 13, page.challenges(), mouseX, mouseY, partialTick);
    }

    private void drawCardFrame(GuiGraphics graphics) {
        graphics.fill(cardX + 3, cardY + 4, cardX + cardWidth + 3, cardY + cardHeight + 4, 0x90000000);
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, CARD_RED_DARK);
        graphics.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + cardHeight - 2, CARD_RED);
        graphics.fill(cardX + 4, cardY + 3, cardX + cardWidth - 4, cardY + 5, CARD_RED_LIGHT);

        int motifX = cardX + cardWidth / 2;
        int motifY = cardY + cardHeight / 2;
        drawRing(graphics, motifX, motifY, Math.min(cardWidth, cardHeight) / 4, 0x22FFFFFF);
        graphics.fill(cardX + 2, motifY - 2, cardX + cardWidth - 2, motifY + 2, 0x18FFFFFF);
    }

    private void drawHeader(GuiGraphics graphics, Component heading, Component rightText, int padding, int headerHeight) {
        graphics.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + headerHeight, CARD_RED_DARK);
        graphics.fill(cardX + 4, cardY + 4, cardX + cardWidth - 4, cardY + headerHeight - 2, CARD_RED);
        graphics.drawString(font, heading, cardX + padding, cardY + 8, 0xFFFFFFFF, true);
        graphics.drawString(font, rightText, cardX + cardWidth - padding - font.width(rightText), cardY + 8, 0xFFFFE8DC, false);
    }

    private void drawIdentityRows(GuiGraphics graphics, int x, int y, int rowWidth) {
        String playerName = minecraft != null && minecraft.player != null
            ? minecraft.player.getGameProfile().getName()
            : "—";
        String trainerId = minecraft != null && minecraft.player != null
            ? minecraft.player.getUUID().toString().replace("-", "").substring(0, 8).toUpperCase()
            : "--------";
        int playTicks = minecraft != null && minecraft.player != null
            ? minecraft.player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME))
            : 0;
        String playTime = formatPlayTime(playTicks);
        String score = minecraft != null && minecraft.player != null
            ? Integer.toString(minecraft.player.getScore())
            : "0";

        drawInfoRow(graphics, x, y, rowWidth, "screen.cobbleventure_player_menu.trainer_card.name", playerName, false);
        drawInfoRow(graphics, x, y + 18, rowWidth, "screen.cobbleventure_player_menu.trainer_card.id", trainerId, true);
        drawInfoRow(graphics, x, y + 36, rowWidth, "screen.cobbleventure_player_menu.trainer_card.play_time", playTime, false);
        drawInfoRow(graphics, x, y + 54, rowWidth, "screen.cobbleventure_player_menu.trainer_card.score", score, true);
    }

    private void drawInfoRow(
        GuiGraphics graphics,
        int x,
        int y,
        int rowWidth,
        String labelKey,
        String value,
        boolean alternate
    ) {
        graphics.fill(x, y, x + rowWidth, y + 16, alternate ? CARD_CREAM_ALT : CARD_CREAM);
        graphics.fill(x, y + 15, x + rowWidth, y + 16, 0x35A5483F);
        graphics.drawString(font, Component.translatable(labelKey), x + 5, y + 4, MUTED_INK, false);
        String clipped = font.plainSubstrByWidth(value, Math.max(24, rowWidth / 2 - 8));
        graphics.drawString(font, clipped, x + rowWidth - 5 - font.width(clipped), y + 4, INK, false);
    }

    private void drawPortrait(
        GuiGraphics graphics,
        int left,
        int top,
        int right,
        int bottom,
        int mouseX,
        int mouseY
    ) {
        graphics.fill(left, top, right, bottom, 0x55FFF3E5);
        graphics.fill(left, bottom - 1, right, bottom, 0x80A5483F);
        if (minecraft == null || minecraft.player == null || bottom - top < 36) {
            return;
        }
        int scale = Math.max(22, Math.min(42, bottom - top - 12));
        InventoryScreen.renderEntityInInventoryFollowsMouse(
            graphics,
            left,
            top,
            right,
            bottom,
            scale,
            0.0625F,
            mouseX,
            mouseY,
            minecraft.player
        );
    }

    private void drawLeaderBadgeGrid(
        GuiGraphics graphics, int x, int y, int gridWidth, int gridHeight,
        List<TrainerCardProgress.Challenge> challenges, int mouseX, int mouseY, float partialTick
    ) {
        if (challenges.isEmpty()) {
            graphics.fill(x, y, x + gridWidth, y + gridHeight, CARD_CREAM);
            graphics.drawCenteredString(font,
                Component.translatable("screen.cobbleventure_player_menu.trainer_card.badges.empty"),
                x + gridWidth / 2, y + gridHeight / 2 - 4, MUTED_INK);
            return;
        }
        int gap = 4;
        int slotWidth = (gridWidth - gap * 3) / 4;
        int slotHeight = (gridHeight - gap) / 2;
        for (int index = 0; index < 8; index++) {
            int column = index % 4;
            int row = index / 4;
            int left = x + column * (slotWidth + gap);
            int top = y + row * (slotHeight + gap);
            TrainerCardProgress.Challenge challenge = index < challenges.size() ? challenges.get(index) : null;
            drawLeaderBadgeSlot(graphics, left, top, slotWidth, slotHeight, challenge, index, mouseX, mouseY, partialTick);
        }
    }

    private void drawLeaderBadgeSlot(
        GuiGraphics graphics, int left, int top, int slotWidth, int slotHeight,
        TrainerCardProgress.Challenge challenge, int index, int mouseX, int mouseY, float partialTick
    ) {
        int right = left + slotWidth;
        int bottom = top + slotHeight;
        int background = index < 4 ? 0xFFE7F5F1 : 0xFFFFE6D1;
        graphics.fill(left, top, right, bottom, 0xFF71443F);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, background);
        if (challenge == null) {
            graphics.fill(left + 4, top + 4, right - 4, bottom - 4, 0x18A06B62);
            return;
        }

        int nameHeight = 13;
        renderLeaderModel(graphics, challenge, left + 2, top + 2, right - 2, bottom - nameHeight);
        if (!challenge.completed()) {
            graphics.fill(left + 1, top + 1, right - 1, bottom - nameHeight, 0x725B6668);
        }
        graphics.fill(left + 1, bottom - nameHeight, right - 1, bottom - 1,
            challenge.completed() ? 0xE93C7390 : 0xD8534D4A);
        String name = font.plainSubstrByWidth(challenge.name().getString(), Math.max(12, slotWidth - 8));
        graphics.drawCenteredString(font, name, left + slotWidth / 2, bottom - 10, 0xFFFFFFFF);

        if (challenge.completed() && challenge.texture() != null) {
            int badgeSize = Math.min(30, Math.max(20, slotWidth / 3));
            drawRotatingBadge(graphics, right - badgeSize / 2 - 5, bottom - nameHeight - badgeSize / 2 - 3,
                badgeSize, challenge, index, partialTick);
        }
        if (mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom) {
            String detail = challenge.completed()
                ? challenge.name().getString() + " · " + challenge.badgeName().getString() + " · " + challenge.tooltip()
                : challenge.name().getString() + " · " + Component.translatable("screen.cobbleventure_player_menu.trainer_card.badge_locked").getString();
            graphics.renderTooltip(font, Component.literal(detail), mouseX, mouseY);
        }
    }

    private void renderLeaderModel(
        GuiGraphics graphics, TrainerCardProgress.Challenge challenge,
        int left, int top, int right, int bottom
    ) {
        CardLeader leader = leaderModel(challenge);
        if (leader == null || right <= left || bottom <= top) {
            drawLeaderSilhouette(graphics, left, top, right, bottom);
            return;
        }
        graphics.enableScissor(left, top, right, bottom);
        int scale = Math.max(20, Math.min(38, bottom - top - 8));
        InventoryScreen.renderEntityInInventoryFollowsMouse(
            graphics, left, top, right, bottom + 8, scale, 0.0625F,
            left + (right - left) / 2, top + (bottom - top) / 3, leader
        );
        graphics.disableScissor();
    }

    private CardLeader leaderModel(TrainerCardProgress.Challenge challenge) {
        if (minecraft == null || minecraft.level == null || challenge.leaderSkin() == null) return null;
        String key = challenge.leaderSkin() + ":" + challenge.slimModel();
        return leaderModels.computeIfAbsent(key, ignored -> {
            UUID uuid = UUID.nameUUIDFromBytes(("cobbleventure-card:" + key).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, "gym_" + Integer.toHexString(key.hashCode()));
            return new CardLeader(minecraft.level, profile, challenge.leaderSkin(), challenge.slimModel());
        });
    }

    private static void drawLeaderSilhouette(GuiGraphics graphics, int left, int top, int right, int bottom) {
        int centerX = (left + right) / 2;
        int head = Math.max(6, Math.min(12, (bottom - top) / 5));
        int centerY = top + head + 3;
        drawRing(graphics, centerX, centerY, head, 0x66575F66);
        graphics.fill(centerX - head - 3, centerY + head, centerX + head + 4, bottom + 4, 0x66575F66);
    }

    private void drawRotatingBadge(
        GuiGraphics graphics, int centerX, int centerY, int drawSize,
        TrainerCardProgress.Challenge challenge, int index, float partialTick
    ) {
        double angle = (animationTick + partialTick) * 0.095D + index * 0.72D;
        float widthScale = (float)Math.cos(angle);
        graphics.fill(centerX - drawSize / 2 + 2, centerY + drawSize / 2 - 2,
            centerX + drawSize / 2 + 2, centerY + drawSize / 2 + 2, 0x55000000);
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 300.0F);
        graphics.pose().scale(widthScale, 1.0F, 1.0F);
        graphics.blit(challenge.texture(), -drawSize / 2, -drawSize / 2,
            (float)challenge.textureU(), (float)challenge.textureV(), drawSize, drawSize,
            challenge.atlasWidth(), challenge.atlasHeight());
        graphics.pose().popPose();
        if (Math.abs(widthScale) < 0.16F) {
            graphics.fill(centerX - 1, centerY - drawSize / 2, centerX + 2, centerY + drawSize / 2, 0xFFFFF0A8);
        }
    }

    private void changePage(int delta) {
        pageIndex = Math.floorMod(pageIndex + delta, progress.pages().size());
    }

    private void flipCard() {
        showingBack = !showingBack;
        updateButtons();
    }

    private void updateButtons() {
        if (flipButton != null) {
            flipButton.setMessage(Component.translatable(showingBack
                ? "screen.cobbleventure_player_menu.trainer_card.show_front"
                : "screen.cobbleventure_player_menu.trainer_card.show_back"));
        }
        boolean showPageButtons = showingBack && progress.pages().size() > 1;
        if (previousButton != null) previousButton.visible = showPageButtons;
        if (nextButton != null) nextButton.visible = showPageButtons;
    }

    private static String formatPlayTime(int ticks) {
        long totalMinutes = Math.max(0L, ticks) / (20L * 60L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return String.format("%d:%02d", hours, minutes);
    }

    private static void drawRing(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        int radiusSquared = radius * radius;
        int innerRadius = Math.max(0, radius - 3);
        int innerSquared = innerRadius * innerRadius;
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int outerX = (int) Math.sqrt(Math.max(0, radiusSquared - offsetY * offsetY));
            int innerX = Math.abs(offsetY) < innerRadius
                ? (int) Math.sqrt(Math.max(0, innerSquared - offsetY * offsetY))
                : 0;
            graphics.fill(centerX - outerX, centerY + offsetY, centerX - innerX, centerY + offsetY + 1, color);
            graphics.fill(centerX + innerX, centerY + offsetY, centerX + outerX, centerY + offsetY + 1, color);
        }
    }

    private static final class CardLeader extends RemotePlayer {
        private final PlayerSkin cardSkin;

        private CardLeader(ClientLevel level, GameProfile profile, ResourceLocation texture, boolean slimModel) {
            super(level, profile);
            cardSkin = new PlayerSkin(texture, null, null, null,
                slimModel ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, false);
        }

        @Override
        public PlayerSkin getSkin() {
            return cardSkin;
        }
    }
}
