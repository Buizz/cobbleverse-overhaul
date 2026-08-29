package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.ShopNetwork;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/** Blue-and-orange shop UI sharing the visual language of the Cobbleventure bag. */
public final class ShopScreen extends Screen {
    private static final int PANEL_DARK = 0xFF356A98;
    private static final int PANEL_LIGHT = 0xFFA9DCEE;
    private static final int PANEL_FILL = 0xF4D9EDF7;
    private static final int CARD = 0xFFF8FCFF;
    private static final int CARD_HOVER = 0xFFE2F4FB;
    private static final int CARD_SELECTED = 0xFFFFD27A;
    private static final int TEXT = 0xFF17324A;
    private static final int MUTED = 0xFF587388;
    private static final int ACCENT = 0xFFF08C2E;
    private static final int ERROR = 0xFFB83A3A;
    private static final int SUCCESS = 0xFF24764D;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 2;

    private final UUID token;
    private final String shopName;
    private final String role;
    private final MenuTheme theme;
    private List<ShopNetwork.ClientOffer> offers;
    private List<ShopNetwork.ClientOffer> filtered = List.of();
    private String balance;
    private String category = "";
    private String searchValue = "";
    private boolean selling;
    private int selectedIndex = -1;
    private int quantity = 1;
    private int scrollRow;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private EditBox searchBox;
    private ActionButton transactionButton;
    private Component status = Component.empty();
    private int statusColor = MUTED;
    private boolean closed;

    public ShopScreen(ShopNetwork.OpenPayload payload) {
        super(Component.literal(payload.name()));
        token = payload.token();
        shopName = payload.name();
        role = payload.role();
        balance = payload.balance();
        offers = new ArrayList<>(payload.offers());
        theme = MenuTheme.load(net.minecraft.client.Minecraft.getInstance());
        if (!offers.isEmpty()) selectedIndex = offers.getFirst().index();
    }

    @Override
    protected void init() {
        ShopLayout.Panel panel = ShopLayout.panel(width, height);
        panelWidth = panel.width();
        panelHeight = panel.height();
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        rebuildShopWidgets();
    }

    private void rebuildShopWidgets() {
        clearWidgets();
        int leftWidth = ShopLayout.contentWidth(panelWidth);
        int tabsY = panelY + 41;
        int bodyY = panelY + 68;

        addRenderableWidget(new TabButton(false, panelX + 12, tabsY, (panelWidth - 24) / 2, 22));
        addRenderableWidget(new TabButton(true, panelX + 12 + (panelWidth - 24) / 2,
            tabsY, (panelWidth - 24) / 2, 22));

        Set<String> categories = new LinkedHashSet<>();
        for (ShopNetwork.ClientOffer offer : offers) categories.add(offer.category());
        List<String> categoryList = new ArrayList<>();
        categoryList.add("");
        categoryList.addAll(categories.stream().limit(4).toList());
        int categoryWidth = Math.max(28, Math.min(76, (leftWidth - 28) / categoryList.size()));
        for (int index = 0; index < categoryList.size(); index++) {
            addRenderableWidget(new CategoryButton(
                categoryList.get(index),
                categoryIcon(categoryList.get(index)),
                panelX + 12 + index * categoryWidth,
                bodyY,
                categoryWidth - 3,
                19
            ));
        }

        int searchX = panelX + 12;
        int searchY = bodyY + 23;
        int searchWidth = leftWidth - 24;
        searchBox = new InvisibleEditBox(font, searchX, searchY, searchWidth, 18,
            Component.translatable("screen.cobbleventure_player_menu.shop.search"));
        searchBox.setBordered(false);
        searchBox.setValue(searchValue);
        searchBox.setResponder(value -> {
            searchValue = value;
            refreshOffers(true);
            rebuildShopWidgets();
            searchBox.setFocused(true);
            searchBox.setCursorPosition(searchValue.length());
        });
        addRenderableWidget(searchBox);

        refreshOffers(false);
        int gridX = panelX + 12;
        int gridY = bodyY + 45;
        int cardWidth = Math.max(42, (leftWidth - 27) / GRID_COLUMNS);
        int cardHeight = Math.max(44, (panelHeight - (gridY - panelY) - 20) / GRID_ROWS);
        int start = scrollRow * GRID_COLUMNS;
        for (int visible = 0; visible < GRID_COLUMNS * GRID_ROWS; visible++) {
            int offerPosition = start + visible;
            if (offerPosition >= filtered.size()) break;
            ShopNetwork.ClientOffer offer = filtered.get(offerPosition);
            addRenderableWidget(new OfferButton(
                offer,
                gridX + (visible % GRID_COLUMNS) * (cardWidth + 1),
                gridY + (visible / GRID_COLUMNS) * (cardHeight + 1),
                cardWidth,
                cardHeight
            ));
        }

        int detailX = panelX + leftWidth + 3;
        int detailWidth = panelWidth - leftWidth - 15;
        int detailBottom = panelY + panelHeight - 10;
        int stepY = detailBottom - 80;
        addRenderableWidget(new StepButton(-1, detailX + 12, stepY, 28, 22));
        addRenderableWidget(new StepButton(1, detailX + detailWidth - 40, stepY, 28, 22));
        transactionButton = addRenderableWidget(new ActionButton(
            detailX + 10,
            detailBottom - 25,
            detailWidth - 20,
            22,
            this::transact
        ));
        updateTransactionButton();
    }

    private ItemStack categoryIcon(String categoryValue) {
        if (categoryValue.isBlank()) return new ItemStack(Items.CHEST);
        return offers.stream()
            .filter(offer -> categoryValue.equals(offer.category()))
            .map(ShopNetwork.ClientOffer::stack)
            .filter(stack -> !stack.isEmpty())
            .findFirst()
            .map(ItemStack::copy)
            .orElseGet(() -> new ItemStack(Items.CHEST));
    }

    private void refreshOffers(boolean resetScroll) {
        String query = searchValue.strip().toLowerCase(Locale.ROOT);
        filtered = offers.stream().filter(offer -> category.isBlank() || category.equals(offer.category()))
            .filter(offer -> query.isBlank()
                || offer.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query))
            .toList();
        if (resetScroll) scrollRow = 0;
        int maxRow = Math.max(0, (filtered.size() - 1) / GRID_COLUMNS - (GRID_ROWS - 1));
        scrollRow = Math.clamp(scrollRow, 0, maxRow);
        if (selectedOffer() == null && !filtered.isEmpty()) selectedIndex = filtered.getFirst().index();
    }

    private ShopNetwork.ClientOffer selectedOffer() {
        for (ShopNetwork.ClientOffer offer : offers) {
            if (offer.index() == selectedIndex) return offer;
        }
        return null;
    }

    private void transact() {
        ShopNetwork.ClientOffer offer = selectedOffer();
        if (offer == null || !canTransact(offer)) return;
        transactionButton.active = false;
        status = Component.translatable("screen.cobbleventure_player_menu.shop.processing");
        statusColor = MUTED;
        ShopNetwork.requestTransaction(token, offer.index(), quantity, selling);
    }

    private boolean canTransact(ShopNetwork.ClientOffer offer) {
        if (quantity < 1) return false;
        if (!selling) return new BigInteger(balance).compareTo(totalPrice(offer)) >= 0;
        return new BigInteger(offer.sellPrice()).signum() > 0
            && offer.owned() >= (long) offer.count() * quantity;
    }

    private BigInteger totalPrice(ShopNetwork.ClientOffer offer) {
        String unit = selling ? offer.sellPrice() : offer.buyPrice();
        return new BigInteger(unit).multiply(BigInteger.valueOf(quantity));
    }

    private void updateTransactionButton() {
        if (transactionButton == null) return;
        transactionButton.setMessage(Component.translatable(
            selling ? "screen.cobbleventure_player_menu.shop.sell" : "screen.cobbleventure_player_menu.shop.buy"
        ));
        ShopNetwork.ClientOffer offer = selectedOffer();
        transactionButton.active = offer != null && canTransact(offer);
    }

    public void update(ShopNetwork.TransactionResultPayload payload) {
        if (!token.equals(payload.token())) return;
        balance = payload.balance();
        if (!payload.offers().isEmpty()) offers = new ArrayList<>(payload.offers());
        status = Component.translatable(payload.message());
        statusColor = payload.success() ? SUCCESS : ERROR;
        rebuildShopWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x70101720);
        drawPanel(graphics, panelX, panelY, panelWidth, panelHeight);
        drawHeader(graphics, panelWidth);
        drawSearchBox(graphics);
        drawDetail(graphics, panelWidth);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The shop draws its own translucent scrim; keep the live world sharp.
    }

    private void drawHeader(GuiGraphics graphics, int actualWidth) {
        fillRoundedRect(graphics, panelX + 10, panelY + 8,
            panelX + actualWidth - 10, panelY + 40, 7, CARD);
        fillRoundedRect(graphics, panelX + 16, panelY + 13, panelX + 40, panelY + 36, 6, PANEL_LIGHT);
        graphics.fill(panelX + 23, panelY + 18, panelX + 33, panelY + 31, ACCENT);
        graphics.fill(panelX + 20, panelY + 21, panelX + 36, panelY + 32, ACCENT);
        graphics.drawString(font, font.plainSubstrByWidth(shopName, actualWidth - 210),
            panelX + 47, panelY + 13, TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(role, actualWidth - 210),
            panelX + 47, panelY + 26, MUTED, false);
        String balanceText = Component.translatable(
            "screen.cobbleventure_player_menu.shop.balance", format(balance)
        ).getString();
        int balanceWidth = font.width(balanceText) + 18;
        fillRoundedRect(graphics, panelX + actualWidth - balanceWidth - 18, panelY + 14,
            panelX + actualWidth - 12, panelY + 34, 6, PANEL_DARK);
        graphics.drawString(font, balanceText, panelX + actualWidth - balanceWidth - 9,
            panelY + 20, 0xFFFFFFFF, false);
    }

    private void drawSearchBox(GuiGraphics graphics) {
        if (searchBox == null) return;
        fillRoundedRect(graphics, searchBox.getX() - 2, searchBox.getY() - 1,
            searchBox.getX() + searchBox.getWidth() + 2,
            searchBox.getY() + searchBox.getHeight() + 1, 4,
            searchBox.isFocused() ? ACCENT : PANEL_LIGHT);
        fillRoundedRect(graphics, searchBox.getX() - 1, searchBox.getY(),
            searchBox.getX() + searchBox.getWidth() + 1,
            searchBox.getY() + searchBox.getHeight(), 3, CARD);
        String value = searchValue.isEmpty()
            ? Component.translatable("screen.cobbleventure_player_menu.shop.search").getString()
            : searchValue;
        int color = searchValue.isEmpty() ? MUTED : TEXT;
        String visible = font.plainSubstrByWidth(value, searchBox.getWidth() - 8);
        graphics.drawString(font, visible, searchBox.getX() + 4,
            searchBox.getY() + (searchBox.getHeight() - font.lineHeight) / 2, color, false);
        if (searchBox.isFocused() && !searchValue.isEmpty()
            && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = searchBox.getX() + 4 + font.width(visible);
            graphics.fill(cursorX, searchBox.getY() + 4,
                cursorX + 1, searchBox.getY() + searchBox.getHeight() - 4, TEXT);
        }
    }

    private void drawDetail(GuiGraphics graphics, int actualWidth) {
        int leftWidth = ShopLayout.contentWidth(actualWidth);
        int detailX = panelX + leftWidth + 3;
        int detailWidth = actualWidth - leftWidth - 15;
        int detailTop = panelY + 68;
        int detailBottom = panelY + panelHeight - 10;
        fillRoundedRect(graphics, detailX, detailTop,
            detailX + detailWidth, detailBottom, 8, PANEL_LIGHT);
        fillRoundedRect(graphics, detailX + 2, detailTop + 2,
            detailX + detailWidth - 2, detailBottom - 2, 7, CARD);

        ShopNetwork.ClientOffer offer = selectedOffer();
        if (offer == null) return;
        ItemStack stack = offer.stack();
        graphics.renderItem(stack, detailX + detailWidth / 2 - 8, detailTop + 10);
        String itemName = font.plainSubstrByWidth(stack.getHoverName().getString(), detailWidth - 20);
        graphics.drawString(font, itemName,
            detailX + (detailWidth - font.width(itemName)) / 2, detailTop + 31, TEXT, false);
        String unitPrice = format(selling ? offer.sellPrice() : offer.buyPrice());
        boolean compact = panelHeight < 250;
        if (compact) {
            String summary = Component.translatable(
                "screen.cobbleventure_player_menu.shop.compact_summary", unitPrice, offer.owned()
            ).getString();
            graphics.drawString(font, font.plainSubstrByWidth(summary, detailWidth - 20),
                detailX + 10, detailTop + 49, MUTED, false);
            graphics.fill(detailX + 10, detailTop + 60, detailX + detailWidth - 10,
                detailTop + 61, 0x55356A98);
        } else {
            graphics.drawString(font,
                Component.translatable("screen.cobbleventure_player_menu.shop.unit_price", unitPrice),
                detailX + 10, detailTop + 49, MUTED, false);
            graphics.drawString(font,
                Component.translatable("screen.cobbleventure_player_menu.shop.owned", offer.owned()),
                detailX + 10, detailTop + 63, MUTED, false);
            graphics.drawString(font,
                Component.translatable("screen.cobbleventure_player_menu.shop.bundle", offer.count()),
                detailX + 10, detailTop + 77, MUTED, false);
            graphics.fill(detailX + 10, detailTop + 92, detailX + detailWidth - 10,
                detailTop + 93, 0x55356A98);
        }
        String quantityText = Integer.toString(quantity);
        int stepY = detailBottom - 80;
        graphics.drawString(font, quantityText,
            detailX + detailWidth / 2 - font.width(quantityText) / 2,
            stepY + 7, TEXT, false);
        int totalY = detailBottom - 49;
        fillRoundedRect(graphics, detailX + 8, totalY,
            detailX + detailWidth - 8, totalY + 20, 5, CARD_SELECTED);
        graphics.drawString(font, Component.translatable("screen.cobbleventure_player_menu.shop.total"),
            detailX + 14, totalY + 6, TEXT, false);
        String total = "₽ " + format(totalPrice(offer).toString());
        graphics.drawString(font, total, detailX + detailWidth - font.width(total) - 14,
            totalY + 6, TEXT, false);
        if (!status.getString().isBlank()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), leftWidth - 28),
                panelX + 14, panelY + panelHeight - 12, statusColor, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxRow = Math.max(0, (filtered.size() - 1) / GRID_COLUMNS - (GRID_ROWS - 1));
        int next = Math.clamp(scrollRow + (scrollY < 0 ? 1 : -1), 0, maxRow);
        if (next != scrollRow) {
            scrollRow = next;
            rebuildShopWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            transact();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        if (!closed) {
            closed = true;
            ShopNetwork.close(token);
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static String format(String amount) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(new BigInteger(amount));
    }

    private void drawCenteredNoShadow(
        GuiGraphics graphics, Component text, int centerX, int y, int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void drawCenteredNoShadow(
        GuiGraphics graphics, String text, int centerX, int y, int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        fillRoundedRect(graphics, x + theme.shadowOffset, y + theme.shadowOffset,
            x + width + theme.shadowOffset, y + height + theme.shadowOffset,
            theme.cornerRadius, 0x7A17324A);
        fillRoundedRect(graphics, x, y, x + width, y + height, theme.cornerRadius, PANEL_DARK);
        fillRoundedRect(graphics, x + 2, y + 2, x + width - 2, y + height - 2,
            Math.max(0, theme.cornerRadius - 2), PANEL_FILL);
        graphics.fill(x + 12, y + 2, x + width - 12, y + 4, PANEL_LIGHT);
        graphics.fill(x + width - 52, y + height - 4, x + width - 10, y + height - 2, ACCENT);
    }

    private static void fillRoundedRect(
        GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color
    ) {
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);
        int effectiveRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int edgeDistance = Math.min(row, height - 1 - row);
            int inset = 0;
            if (edgeDistance < effectiveRadius) {
                double vertical = effectiveRadius - edgeDistance - 0.5D;
                inset = effectiveRadius - (int) Math.floor(Math.sqrt(Math.max(
                    0.0D, effectiveRadius * effectiveRadius - vertical * vertical
                )));
            }
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
        }
    }

    private final class TabButton extends AbstractButton {
        private final boolean sellTab;

        private TabButton(boolean sellTab, int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable(
                sellTab ? "screen.cobbleventure_player_menu.shop.tab.sell"
                    : "screen.cobbleventure_player_menu.shop.tab.buy"
            ));
            this.sellTab = sellTab;
        }

        @Override public void onPress() {
            selling = sellTab;
            quantity = 1;
            status = Component.empty();
            rebuildShopWidgets();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = selling == sellTab;
            int fill = selected ? ACCENT : (isHovered() ? CARD_HOVER : PANEL_DARK);
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 5, fill);
            int color = selected ? 0xFFFFFFFF : PANEL_LIGHT;
            drawCenteredNoShadow(graphics, getMessage(),
                getX() + getWidth() / 2, getY() + 7, color);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class CategoryButton extends AbstractButton {
        private final String value;
        private final ItemStack icon;

        private CategoryButton(
            String value, ItemStack icon, int x, int y, int width, int height
        ) {
            super(x, y, width, height, value.isBlank()
                ? Component.translatable("screen.cobbleventure_player_menu.shop.category.all")
                : Component.literal(value));
            this.value = value;
            this.icon = icon;
            setTooltip(Tooltip.create(getMessage()));
        }

        @Override public void onPress() {
            category = value;
            refreshOffers(true);
            rebuildShopWidgets();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = category.equals(value);
            int border = selected ? ACCENT : PANEL_LIGHT;
            int fill = selected ? CARD_SELECTED : (isHovered() ? CARD_HOVER : CARD);
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 5, border);
            fillRoundedRect(graphics, getX() + 1, getY() + 1, getX() + getWidth() - 1,
                getY() + getHeight() - 1, 4, fill);
            String label = getWidth() >= 44
                ? font.plainSubstrByWidth(getMessage().getString(), getWidth() - 23)
                : "";
            int contentWidth = 16 + (label.isBlank() ? 0 : 2 + font.width(label));
            int contentX = getX() + (getWidth() - contentWidth) / 2;
            graphics.renderItem(icon, contentX, getY() + 2);
            if (!label.isBlank()) {
                graphics.drawString(font, label, contentX + 18, getY() + 5, TEXT, false);
            }
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class OfferButton extends AbstractButton {
        private final ShopNetwork.ClientOffer offer;

        private OfferButton(ShopNetwork.ClientOffer offer, int x, int y, int width, int height) {
            super(x, y, width, height, offer.stack().getHoverName());
            this.offer = offer;
        }

        @Override public void onPress() {
            selectedIndex = offer.index();
            quantity = 1;
            updateTransactionButton();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = selectedIndex == offer.index();
            boolean unavailable = selling && (new BigInteger(offer.sellPrice()).signum() <= 0
                || offer.owned() < offer.count());
            int border = selected ? ACCENT : PANEL_LIGHT;
            int fill = selected ? CARD_SELECTED : (isHovered() ? CARD_HOVER : CARD);
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 5, border);
            fillRoundedRect(graphics, getX() + 1, getY() + 1, getX() + getWidth() - 1,
                getY() + getHeight() - 1, 4, fill);
            graphics.renderItem(offer.stack(), getX() + getWidth() / 2 - 8, getY() + 6);
            String name = font.plainSubstrByWidth(offer.stack().getHoverName().getString(), getWidth() - 6);
            int color = unavailable ? MUTED : TEXT;
            drawCenteredNoShadow(graphics, name,
                getX() + getWidth() / 2, getY() + 23, color);
            String price = "₽ " + format(selling ? offer.sellPrice() : offer.buyPrice());
            drawCenteredNoShadow(graphics, price,
                getX() + getWidth() / 2, getY() + 35, color);
            if (getHeight() >= 58) {
                String owned = Component.translatable(
                    "screen.cobbleventure_player_menu.shop.card_owned", offer.owned()
                ).getString();
                drawCenteredNoShadow(graphics, owned,
                    getX() + getWidth() / 2, getY() + 48, MUTED);
            }
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class StepButton extends AbstractButton {
        private final int delta;

        private StepButton(int delta, int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(delta < 0 ? "−" : "+"));
            this.delta = delta;
        }

        @Override public void onPress() {
            quantity = Math.clamp(quantity + delta, 1, 999);
            updateTransactionButton();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                5, isHovered() ? ACCENT : PANEL_DARK);
            drawCenteredNoShadow(graphics, getMessage(),
                getX() + getWidth() / 2, getY() + 7, 0xFFFFFFFF);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class ActionButton extends AbstractButton {
        private final Runnable action;

        private ActionButton(int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, Component.empty());
            this.action = action;
        }

        @Override public void onPress() { if (active) action.run(); }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            int fill = !active ? 0xFF9AA9B4 : isHovered() ? 0xFFFFA243 : ACCENT;
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 5, fill);
            drawCenteredNoShadow(graphics, getMessage(),
                getX() + getWidth() / 2, getY() + 7, 0xFFFFFFFF);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Keeps EditBox input behavior while this screen draws crisp shadow-free text itself. */
    private static final class InvisibleEditBox extends EditBox {
        private InvisibleEditBox(
            Font font, int x, int y, int width, int height, Component message
        ) {
            super(font, x, y, width, height, message);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    }
}
