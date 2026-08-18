package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.authlib.GameProfile;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.buizz.cobbleventure.playermenu.BadgeProgressNetwork;
import dev.buizz.cobbleventure.playermenu.ProgressionNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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
    private static final int CARD_RED_SOFT = 0xFFFFB0A0;
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
    private static final ResourceLocation CARD_BACKGROUND = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure_player_menu", "textures/gui/trainer_card_background.png"
    );

    private final Screen parent;
    private TrainerCardProgress progress;
    private final boolean liveProgress;
    private final Map<String, CardLeader> leaderModels = new HashMap<>();
    private int badgeSnapshotHash;
    private int progressionSnapshotHash;
    private int pageIndex;
    private int animationTick;
    private boolean showingBack;
    private CardButton flipButton;
    private CardButton previousButton;
    private CardButton nextButton;
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
        if (liveProgress) {
            BadgeProgressNetwork.requestSnapshot();
            ProgressionNetwork.requestSnapshot();
        }
        cardWidth = Math.min(CARD_MAX_WIDTH, Math.max(180, width - 24));
        cardHeight = Math.min(CARD_MAX_HEIGHT, Math.max(150, height - 42));
        cardX = (width - cardWidth) / 2;
        cardY = Math.max(8, (height - cardHeight) / 2 - 5);

        int buttonY = Math.min(height - 24, cardY + cardHeight + 6);
        addRenderableWidget(new CardButton(
            Component.translatable("screen.cobbleventure_player_menu.trainer_card.back"),
            cardX + cardWidth - 78, buttonY, 78, 20, this::onClose));

        flipButton = addRenderableWidget(new CardButton(
            Component.translatable("screen.cobbleventure_player_menu.trainer_card.show_back"),
            cardX + cardWidth - 164, buttonY, 82, 20, this::flipCard));
        previousButton = addRenderableWidget(new CardButton(Component.literal("◀"),
            cardX, buttonY, 28, 20, () -> changePage(-1)));
        nextButton = addRenderableWidget(new CardButton(Component.literal("▶"),
            cardX + 32, buttonY, 28, 20, () -> changePage(1)));
        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        animationTick++;
        if (!liveProgress) return;
        int currentHash = BadgeProgressNetwork.clientBadges().hashCode();
        int progressHash = ProgressionNetwork.clientSnapshot().hashCode();
        if (currentHash != badgeSnapshotHash || progressHash != progressionSnapshotHash) {
            badgeSnapshotHash = currentHash;
            progressionSnapshotHash = progressHash;
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
        int padding = 10;
        int headerHeight = 25;
        int contentTop = cardY + headerHeight;
        int portraitWidth = Math.max(84, cardWidth / 3);
        int infoRight = cardX + cardWidth - portraitWidth - padding;

        drawHeader(graphics, title, Component.translatable("screen.cobbleventure_player_menu.trainer_card.front"), padding, headerHeight);
        drawIdentityRows(graphics, cardX + padding, contentTop + 6, infoRight - cardX - padding - 3);
        drawPortrait(graphics, infoRight + 2, contentTop + 6,
            cardX + cardWidth - padding, cardY + cardHeight - 28, mouseX, mouseY);
        drawLevelCapTag(graphics, infoRight + 8, cardY + cardHeight - 49,
            cardX + cardWidth - padding - 6);
        graphics.drawString(font, Component.translatable("screen.cobbleventure_player_menu.trainer_card.flip_hint"),
            cardX + padding + 4, cardY + cardHeight - 18, 0xFFFFEEE7, true);
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
        fillRoundedRect(graphics, cardX + 4, cardY + 5,
            cardX + cardWidth + 4, cardY + cardHeight + 5, 9, 0x90000000);
        fillRoundedRect(graphics, cardX, cardY,
            cardX + cardWidth, cardY + cardHeight, 9, 0xFFFFFFFF);
        fillRoundedRect(graphics, cardX + 2, cardY + 2,
            cardX + cardWidth - 2, cardY + cardHeight - 2, 7, CARD_RED_DARK);
        fillRoundedRect(graphics, cardX + 4, cardY + 4,
            cardX + cardWidth - 4, cardY + cardHeight - 4, 6, CARD_RED);

        graphics.enableScissor(cardX + 5, cardY + 5, cardX + cardWidth - 5, cardY + cardHeight - 5);
        graphics.blit(CARD_BACKGROUND, cardX + 4, cardY + 4,
            0.0F, 0.0F, cardWidth - 8, cardHeight - 8, 256, 256);
        graphics.disableScissor();

        int motifX = cardX + cardWidth / 2;
        int motifY = cardY + cardHeight / 2;
        drawRing(graphics, motifX, motifY, Math.min(cardWidth, cardHeight) / 4, 0x28FFFFFF);
        graphics.fill(cardX + 5, motifY - 2, cardX + cardWidth - 5, motifY + 2, 0x1EFFFFFF);
    }

    private void drawHeader(GuiGraphics graphics, Component heading, Component rightText, int padding, int headerHeight) {
        fillRoundedRect(graphics, cardX + 5, cardY + 5,
            cardX + cardWidth - 5, cardY + headerHeight, 6, 0xD8A93C35);
        graphics.fill(cardX + 11, cardY + headerHeight - 1,
            cardX + cardWidth - 11, cardY + headerHeight, 0x70FFFFFF);
        graphics.drawString(font, "◀ " + heading.getString() + " ▶",
            cardX + padding, cardY + 9, 0xFFFFFFFF, true);
        graphics.drawString(font, rightText, cardX + cardWidth - padding - font.width(rightText),
            cardY + 9, 0xFFFFE8DC, true);
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
        String money = currentMoney();

        drawInfoRow(graphics, x, y, rowWidth, "screen.cobbleventure_player_menu.trainer_card.name", playerName, false);
        drawInfoRow(graphics, x, y + 22, rowWidth, "screen.cobbleventure_player_menu.trainer_card.id", trainerId, true);
        drawInfoRow(graphics, x, y + 44, rowWidth, "screen.cobbleventure_player_menu.trainer_card.money", money, false);
        drawInfoRow(graphics, x, y + 66, rowWidth, "screen.cobbleventure_player_menu.trainer_card.score", score, true);
        drawInfoRow(graphics, x, y + 88, rowWidth, "screen.cobbleventure_player_menu.trainer_card.play_time", playTime, false);
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
        fillRoundedRect(graphics, x, y, x + rowWidth, y + 19, 5,
            alternate ? CARD_CREAM_ALT : CARD_CREAM);
        graphics.fill(x + 6, y + 17, x + rowWidth - 6, y + 18, 0x35A5483F);
        graphics.drawString(font, Component.translatable(labelKey), x + 6, y + 5, MUTED_INK, false);
        String clipped = font.plainSubstrByWidth(value, Math.max(24, rowWidth / 2 - 8));
        graphics.drawString(font, clipped, x + rowWidth - 6 - font.width(clipped), y + 5, INK, false);
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
        fillRoundedRect(graphics, left, top, right, bottom, 8, 0xFFFFFFFF);
        fillRoundedRect(graphics, left + 2, top + 2, right - 2, bottom - 2, 6, CARD_RED_SOFT);
        for (int y = top + 8; y < bottom; y += 12) {
            graphics.fill(left + 5, y, right - 5, y + 1, 0x18FFFFFF);
        }
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

    private void drawLevelCapTag(GuiGraphics graphics, int left, int top, int right) {
        fillRoundedRect(graphics, left, top, right, top + 18, 7, 0xE8FFFFFF);
        fillRoundedRect(graphics, left + 2, top + 2, right - 2, top + 16, 5, 0xE8C85550);
        String label = Component.translatable(
            "screen.cobbleventure_player_menu.trainer_card.level_cap",
            progress.currentLevelCap()
        ).getString();
        graphics.drawCenteredString(font, font.plainSubstrByWidth(label, right - left - 10),
            (left + right) / 2, top + 5, 0xFFFFFFFF);
    }

    private void drawLeaderBadgeGrid(
        GuiGraphics graphics, int x, int y, int gridWidth, int gridHeight,
        List<TrainerCardProgress.Challenge> challenges, int mouseX, int mouseY, float partialTick
    ) {
        if (challenges.isEmpty()) {
            fillRoundedRect(graphics, x, y, x + gridWidth, y + gridHeight, 8, CARD_CREAM);
            graphics.drawCenteredString(font,
                Component.translatable("screen.cobbleventure_player_menu.trainer_card.badges.empty"),
                x + gridWidth / 2, y + gridHeight / 2 - 4, MUTED_INK);
            return;
        }
        fillRoundedRect(graphics, x, y, x + gridWidth, y + gridHeight, 9, 0xFFFFFFFF);
        fillRoundedRect(graphics, x + 2, y + 2, x + gridWidth - 2, y + gridHeight - 2, 7, 0xFFE2F2EC);
        int emblemWidth = Math.min(54, Math.max(38, gridWidth / 6));
        int emblemX = x + emblemWidth / 2 + 5;
        int emblemY = y + gridHeight / 2;
        drawRing(graphics, emblemX, emblemY, Math.min(18, gridHeight / 4), 0xFF6F8EA4);
        drawRing(graphics, emblemX, emblemY, Math.min(12, gridHeight / 6), 0xFFAAC2C9);
        graphics.fill(emblemX - 17, emblemY - 1, emblemX + 18, emblemY + 2, 0xFF6F8EA4);

        int trayX = x + emblemWidth + 10;
        int trayWidth = gridWidth - emblemWidth - 16;
        int gap = 4;
        int slotWidth = (trayWidth - gap * 3) / 4;
        int slotHeight = (gridHeight - 10 - gap) / 2;
        for (int index = 0; index < 8; index++) {
            int column = index % 4;
            int row = index / 4;
            int left = trayX + column * (slotWidth + gap);
            int top = y + 5 + row * (slotHeight + gap);
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
        int background = index < 4 ? 0xFFFFF8EF : 0xFFFFE8E0;
        fillRoundedRect(graphics, left, top, right, bottom, 4, 0xFFFFFFFF);
        fillRoundedRect(graphics, left + 1, top + 1, right - 1, bottom - 1, 3, background);
        if (challenge == null) {
            fillRoundedRect(graphics, left + 4, top + 4, right - 4, bottom - 4, 2, 0x18A06B62);
            return;
        }

        int nameHeight = 12;
        renderLeaderModel(graphics, challenge, left + 2, top + 2, right - 2, bottom - nameHeight);
        if (!challenge.completed()) {
            graphics.fill(left + 1, top + 1, right - 1, bottom - nameHeight, 0x725B6668);
        }
        fillRoundedRect(graphics, left + 1, bottom - nameHeight, right - 1, bottom - 1, 3,
            challenge.completed() ? 0xEE3C7390 : 0xDD777B7D);
        String name = font.plainSubstrByWidth(challenge.name().getString(), Math.max(12, slotWidth - 8));
        graphics.drawCenteredString(font, name, left + slotWidth / 2, bottom - 10, 0xFFFFFFFF);

        if (challenge.completed() && challenge.texture() != null) {
            int badgeSize = Math.min(20, Math.max(13, slotWidth / 3));
            drawRotatingBadge(graphics, right - badgeSize / 2 - 3, top + badgeSize / 2 + 3,
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

    private String currentMoney() {
        if (minecraft == null || minecraft.player == null) return "—";
        BigInteger money = PlayerExtensionKt.getCobbleDollars(minecraft.player);
        return formatNumber(money) + " ₽";
    }

    private static String formatNumber(BigInteger value) {
        String digits = value.max(BigInteger.ZERO).toString();
        StringBuilder formatted = new StringBuilder(digits.length() + digits.length() / 3);
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (digits.length() - index) % 3 == 0) formatted.append(',');
            formatted.append(digits.charAt(index));
        }
        return formatted.toString();
    }

    private static void fillRoundedRect(
        GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color
    ) {
        if (right <= left || bottom <= top) return;
        int safeRadius = Math.min(radius, Math.min((right - left) / 2, (bottom - top) / 2));
        graphics.fill(left + safeRadius, top, right - safeRadius, bottom, color);
        graphics.fill(left, top + safeRadius, right, bottom - safeRadius, color);
        for (int row = 0; row < safeRadius; row++) {
            double normalized = (safeRadius - row - 0.5D) / safeRadius;
            int inset = (int)Math.ceil(safeRadius - Math.sqrt(Math.max(0.0D,
                1.0D - normalized * normalized)) * safeRadius);
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
            graphics.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
        }
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

    private final class CardButton extends AbstractButton {
        private final Runnable action;

        private CardButton(Component message, int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
        }

        @Override
        public void onPress() {
            if (active) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHovered() ? 0xFFFFFFFF : 0xFFFFE2D8;
            int fill = isHovered() ? CARD_RED_LIGHT : CARD_RED_DARK;
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 8, 0xB0000000);
            fillRoundedRect(graphics, getX(), getY() - 1, getX() + getWidth(), getY() + getHeight() - 1, 8, border);
            fillRoundedRect(graphics, getX() + 2, getY() + 1,
                getX() + getWidth() - 2, getY() + getHeight() - 3, 6, fill);
            graphics.fill(getX() + 8, getY() + 2, getX() + getWidth() - 8, getY() + 3, 0x55FFFFFF);
            int color = active ? 0xFFFFFFFF : 0xFFB9938D;
            graphics.drawCenteredString(font,
                font.plainSubstrByWidth(getMessage().getString(), getWidth() - 10),
                getX() + getWidth() / 2, getY() + (getHeight() - font.lineHeight) / 2 - 1, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
