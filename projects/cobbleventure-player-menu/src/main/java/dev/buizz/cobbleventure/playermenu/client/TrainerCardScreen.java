package dev.buizz.cobbleventure.playermenu.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
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
    private final TrainerCardProgress progress;
    private int pageIndex;
    private int cardX;
    private int cardY;
    private int cardWidth;
    private int cardHeight;

    public TrainerCardScreen(Screen parent) {
        this(parent, TrainerCardProgress.current());
    }

    TrainerCardScreen(Screen parent, TrainerCardProgress progress) {
        super(Component.translatable("screen.cobbleventure_player_menu.trainer_card.title"));
        this.parent = parent;
        this.progress = progress;
    }

    @Override
    protected void init() {
        super.init();
        cardWidth = Math.min(CARD_MAX_WIDTH, Math.max(180, width - 24));
        cardHeight = Math.min(CARD_MAX_HEIGHT, Math.max(150, height - 42));
        cardX = (width - cardWidth) / 2;
        cardY = Math.max(8, (height - cardHeight) / 2 - 5);

        int buttonY = Math.min(height - 24, cardY + cardHeight + 6);
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.trainer_card.back"),
            ignored -> onClose()
        ).bounds(cardX + cardWidth - 70, buttonY, 70, 20).build());

        if (progress.pages().size() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), ignored -> changePage(-1))
                .bounds(cardX, buttonY, 24, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), ignored -> changePage(1))
                .bounds(cardX + 28, buttonY, 24, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, PAGE_BACKGROUND);
        drawCard(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 카드 바깥으로 월드가 비치도록 기본 블러를 사용하지 않는다.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT && progress.pages().size() > 1) {
            changePage(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT && progress.pages().size() > 1) {
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

    private void drawCard(GuiGraphics graphics, int mouseX, int mouseY) {
        drawCardFrame(graphics);

        int padding = 8;
        int headerHeight = 22;
        int badgeHeight = Math.max(49, cardHeight / 3);
        int contentTop = cardY + headerHeight;
        int badgeTop = cardY + cardHeight - badgeHeight;
        int portraitWidth = Math.max(76, cardWidth / 4);
        int infoRight = cardX + cardWidth - portraitWidth - padding;

        drawHeader(graphics, padding, headerHeight);
        drawIdentityRows(graphics, cardX + padding, contentTop + 5, infoRight - cardX - padding);
        drawPortrait(graphics, infoRight + 2, contentTop + 2, cardX + cardWidth - padding, badgeTop - 4, mouseX, mouseY);
        drawBadgeCase(graphics, cardX + padding, badgeTop, cardWidth - padding * 2, badgeHeight - padding);
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

    private void drawHeader(GuiGraphics graphics, int padding, int headerHeight) {
        graphics.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + headerHeight, CARD_RED_DARK);
        graphics.fill(cardX + 4, cardY + 4, cardX + cardWidth - 4, cardY + headerHeight - 2, CARD_RED);
        graphics.drawString(font, title, cardX + padding, cardY + 8, 0xFFFFFFFF, true);

        String page = (pageIndex + 1) + "/" + progress.pages().size();
        graphics.drawString(font, page, cardX + cardWidth - padding - font.width(page), cardY + 8, 0xFFFFE8DC, false);
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

    private void drawBadgeCase(GuiGraphics graphics, int x, int y, int caseWidth, int caseHeight) {
        TrainerCardProgress.LeaguePage page = progress.pages().get(pageIndex);
        graphics.fill(x, y, x + caseWidth, y + caseHeight, CARD_CREAM);
        graphics.fill(x, y, x + caseWidth, y + 16, CARD_RED_DARK);
        graphics.drawString(font, page.title(), x + 5, y + 4, 0xFFFFFFFF, false);

        Component state = Component.translatable(
            page.leagueCleared()
                ? "screen.cobbleventure_player_menu.trainer_card.league.cleared"
                : "screen.cobbleventure_player_menu.trainer_card.league.pending"
        );
        graphics.drawString(font, state, x + caseWidth - 5 - font.width(state), y + 4,
            page.leagueCleared() ? LEAGUE_COMPLETE : 0xFFFFD8CA, false);

        List<TrainerCardProgress.Challenge> challenges = page.challenges();
        if (challenges.isEmpty()) {
            graphics.drawCenteredString(font,
                Component.translatable("screen.cobbleventure_player_menu.trainer_card.badges.empty"),
                x + caseWidth / 2, y + 27, MUTED_INK);
            return;
        }

        int availableWidth = caseWidth - 12;
        int slotSize = Math.max(13, Math.min(22, availableWidth / Math.max(1, challenges.size())));
        int columns = Math.max(1, Math.min(challenges.size(), availableWidth / slotSize));
        int rows = (challenges.size() + columns - 1) / columns;
        int usableHeight = Math.max(15, caseHeight - 20);
        int rowStep = Math.max(12, usableHeight / Math.max(1, rows));
        for (int index = 0; index < challenges.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            int rowStart = row * columns;
            int itemsInRow = Math.min(columns, challenges.size() - rowStart);
            int rowWidth = itemsInRow * slotSize;
            int centerX = x + (caseWidth - rowWidth) / 2 + column * slotSize + slotSize / 2;
            int centerY = y + 18 + row * rowStep + Math.min(rowStep, 16) / 2;
            drawChallengeMark(graphics, centerX, centerY, challenges.get(index));
        }
    }

    private void drawChallengeMark(
        GuiGraphics graphics,
        int centerX,
        int centerY,
        TrainerCardProgress.Challenge challenge
    ) {
        int color = challenge.completed()
            ? (challenge.kind() == TrainerCardProgress.ChallengeKind.GYM ? BADGE_COMPLETE : LEAGUE_COMPLETE)
            : BADGE_EMPTY;
        if (challenge.kind() == TrainerCardProgress.ChallengeKind.GYM) {
            graphics.fill(centerX - 5, centerY - 3, centerX + 6, centerY + 4, 0x503E2D2B);
            graphics.fill(centerX - 3, centerY - 5, centerX + 4, centerY + 6, color);
            graphics.fill(centerX - 4, centerY - 2, centerX + 5, centerY + 3, color);
        } else {
            graphics.fill(centerX - 5, centerY - 5, centerX + 6, centerY + 6, 0x503E2D2B);
            graphics.fill(centerX - 3, centerY - 3, centerX + 4, centerY + 4, color);
        }
    }

    private void changePage(int delta) {
        pageIndex = Math.floorMod(pageIndex + delta, progress.pages().size());
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
}
