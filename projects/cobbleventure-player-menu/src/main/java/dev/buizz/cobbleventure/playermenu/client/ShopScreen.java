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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/** Blue-and-orange shop UI sharing the visual language of the Cobbleventure bag. */
public final class ShopScreen extends Screen {
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 2;
    private static final int SELL_SLOT_SIZE = 20;
    private static final ItemStack SHOP_HEADER_ICON = new ItemStack(Items.CHEST);

    private final UUID token;
    private final String shopName;
    private final String role;
    private final MenuTheme theme;
    private final int PANEL_DARK;
    private final int PANEL_LIGHT;
    private final int PANEL_FILL;
    private final int CARD;
    private final int CARD_HOVER;
    private final int CARD_SELECTED;
    private final int TEXT;
    private final int MUTED;
    private final int ACCENT;
    private final int ERROR;
    private final int SUCCESS;
    private final List<SellItemButton> sellItemButtons = new ArrayList<>();
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
    private int statusColor;
    private boolean closed;

    public ShopScreen(ShopNetwork.OpenPayload payload) {
        super(Component.literal(payload.name()));
        token = payload.token();
        shopName = payload.name();
        role = payload.role();
        balance = payload.balance();
        offers = new ArrayList<>(payload.offers());
        theme = MenuTheme.load(net.minecraft.client.Minecraft.getInstance());
        PANEL_DARK = theme.border;
        PANEL_LIGHT = theme.innerBorder;
        PANEL_FILL = theme.background;
        CARD = theme.cardBackground;
        CARD_HOVER = theme.hoverBackground;
        CARD_SELECTED = theme.selectedBackground;
        TEXT = theme.textColor;
        MUTED = theme.mutedTextColor;
        ACCENT = theme.accent;
        ERROR = theme.danger;
        SUCCESS = theme.success;
        statusColor = MUTED;
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
        sellItemButtons.clear();
        int leftWidth = ShopLayout.contentWidth(panelWidth);
        int tabsY = panelY + 41;
        int bodyY = panelY + 68;

        addRenderableWidget(new MenuBackButton(
            theme, panelX + panelWidth - 72, panelY + 14, 58, 20, this::onClose
        ));

        addRenderableWidget(new TabButton(false, panelX + 12, tabsY, (panelWidth - 24) / 2, 22));
        addRenderableWidget(new TabButton(true, panelX + 12 + (panelWidth - 24) / 2,
            tabsY, (panelWidth - 24) / 2, 22));

        List<String> categoryList;
        if (selling) {
            categoryList = List.of("", "recovery", "balls", "battle", "materials");
        } else {
            Set<String> categories = new LinkedHashSet<>();
            for (ShopNetwork.ClientOffer offer : offers) {
                if (isAvailableInCurrentMode(offer)) categories.add(offer.category());
            }
            List<String> buyCategories = new ArrayList<>();
            buyCategories.add("");
            buyCategories.addAll(categories.stream().limit(4).toList());
            categoryList = List.copyOf(buyCategories);
        }
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
        int gridY = bodyY + 45;
        int columns = currentGridColumns();
        int rows = currentGridRows();
        int start = scrollRow * columns;
        if (selling) {
            int gridWidth = columns * SELL_SLOT_SIZE;
            int gridX = panelX + (leftWidth - gridWidth) / 2;
            for (int visible = 0; visible < columns * rows; visible++) {
                int offerPosition = start + visible;
                if (offerPosition >= filtered.size()) break;
                SellItemButton button = new SellItemButton(
                    filtered.get(offerPosition),
                    gridX + (visible % columns) * SELL_SLOT_SIZE,
                    gridY + (visible / columns) * SELL_SLOT_SIZE
                );
                addRenderableWidget(button);
                sellItemButtons.add(button);
            }
        } else {
            int gridX = panelX + 12;
            int cardWidth = Math.max(42, (leftWidth - 27) / GRID_COLUMNS);
            int cardHeight = Math.max(44, (panelHeight - (gridY - panelY) - 20) / GRID_ROWS);
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
        }

        int detailX = panelX + leftWidth + 3;
        int detailWidth = panelWidth - leftWidth - 15;
        int detailBottom = panelY + panelHeight - 10;
        int stepY = detailBottom - 80;
        if (selling) {
            addRenderableWidget(new StepButton(-1, detailX + 10, stepY, 24, 22));
            addRenderableWidget(new StepButton(1, detailX + detailWidth - 66, stepY, 24, 22));
            addRenderableWidget(new MaxQuantityButton(
                detailX + detailWidth - 39, stepY, 29, 22
            ));
        } else {
            addRenderableWidget(new StepButton(-1, detailX + 12, stepY, 28, 22));
            addRenderableWidget(new StepButton(1, detailX + detailWidth - 40, stepY, 28, 22));
        }
        int actionWidth = Math.max(56, detailWidth - 36);
        transactionButton = addRenderableWidget(new ActionButton(
            detailX + (detailWidth - actionWidth) / 2,
            detailBottom - 22,
            actionWidth,
            18,
            this::transact
        ));
        updateTransactionButton();
    }

    private ItemStack categoryIcon(String categoryValue) {
        if (categoryValue.isBlank()) return new ItemStack(Items.CHEST);
        return offers.stream()
            .filter(this::isAvailableInCurrentMode)
            .filter(offer -> selling
                ? categoryValue.equals(saleCategory(offer.stack()))
                : categoryValue.equals(offer.category()))
            .map(ShopNetwork.ClientOffer::stack)
            .filter(stack -> !stack.isEmpty())
            .findFirst()
            .map(ItemStack::copy)
            .orElseGet(() -> new ItemStack(Items.CHEST));
    }

    private void refreshOffers(boolean resetScroll) {
        String query = searchValue.strip().toLowerCase(Locale.ROOT);
        filtered = offers.stream()
            .filter(this::isAvailableInCurrentMode)
            .filter(offer -> category.isBlank() || category.equals(
                selling ? saleCategory(offer.stack()) : offer.category()
            ))
            .filter(offer -> query.isBlank()
                || offer.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query))
            .toList();
        if (resetScroll) scrollRow = 0;
        int maxRow = Math.max(0,
            (filtered.size() - 1) / currentGridColumns() - (currentGridRows() - 1));
        scrollRow = Math.clamp(scrollRow, 0, maxRow);
        boolean selectedVisible = filtered.stream().anyMatch(offer -> offer.index() == selectedIndex);
        if (!selectedVisible) {
            selectedIndex = filtered.isEmpty() ? -1 : filtered.getFirst().index();
            quantity = 1;
        }
    }

    private boolean isAvailableInCurrentMode(ShopNetwork.ClientOffer offer) {
        return selling == offer.sellCandidate();
    }

    private int currentGridColumns() {
        if (!selling) return GRID_COLUMNS;
        return Math.max(4, (ShopLayout.contentWidth(panelWidth) - 24) / SELL_SLOT_SIZE);
    }

    private int currentGridRows() {
        if (!selling) return GRID_ROWS;
        int gridY = panelY + 113;
        return Math.max(2, (panelY + panelHeight - 18 - gridY) / SELL_SLOT_SIZE);
    }

    private static String saleCategory(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String namespace = id.getNamespace();
        String path = id.getPath();
        if (stack.has(DataComponents.FOOD)
            || containsAny(path, "potion", "heal", "revive", "ether", "elixir", "berry",
                "candy", "rice_cake")) return "recovery";
        if (namespace.equals("cobblemon")
            && (path.endsWith("_ball") || path.contains("poke_ball"))) return "balls";
        if (stack.isDamageableItem()
            || containsAny(path, "sword", "bow", "shield", "vest", "band", "specs", "scarf",
                "gem")) return "battle";
        return "materials";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
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
        ShopNetwork.requestTransaction(token, offer.index(), quantity, selling, offer.stack());
    }

    private boolean canTransact(ShopNetwork.ClientOffer offer) {
        if (quantity < 1) return false;
        if (!selling) return new BigInteger(balance).compareTo(totalPrice(offer)) >= 0;
        return offer.owned() >= quantity;
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
        drawEmptyState(graphics);
        drawDetail(graphics, panelWidth);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (selling) {
            for (SellItemButton button : sellItemButtons) {
                if (button.visible && button.isMouseOver(mouseX, mouseY)) {
                    graphics.renderTooltip(font, button.offer.stack(), mouseX, mouseY);
                    break;
                }
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The shop draws its own translucent scrim; keep the live world sharp.
    }

    private void drawHeader(GuiGraphics graphics, int actualWidth) {
        fillRoundedRect(graphics, panelX + 10, panelY + 8,
            panelX + actualWidth - 10, panelY + 40, 7, CARD);
        fillRoundedRect(graphics, panelX + 16, panelY + 13, panelX + 40, panelY + 36, 6, PANEL_LIGHT);
        graphics.renderItem(SHOP_HEADER_ICON, panelX + 20, panelY + 17);
        theme.drawText(graphics, font, Component.literal(
            font.plainSubstrByWidth(shopName, Math.max(60, actualWidth - 280))
        ), panelX + 47, panelY + 12, MenuTheme.TextRole.HEADING);
        theme.drawText(graphics, font, Component.literal(
            font.plainSubstrByWidth(role, Math.max(60, actualWidth - 280))
        ), panelX + 47, panelY + 26, MenuTheme.TextRole.CAPTION);
        String balanceText = Component.translatable(
            "screen.cobbleventure_player_menu.shop.balance", format(balance)
        ).getString();
        int balanceWidth = font.width(balanceText) + 18;
        int balanceRight = panelX + actualWidth - 78;
        fillRoundedRect(graphics, balanceRight - balanceWidth, panelY + 14,
            balanceRight, panelY + 34, 6, PANEL_DARK);
        graphics.drawString(font, balanceText, balanceRight - balanceWidth + 9,
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

    private void drawEmptyState(GuiGraphics graphics) {
        if (!filtered.isEmpty()) return;
        int leftWidth = ShopLayout.contentWidth(panelWidth);
        Component message = Component.translatable(selling
            ? "screen.cobbleventure_player_menu.shop.sell_empty"
            : "screen.cobbleventure_player_menu.shop.no_results");
        String label = font.plainSubstrByWidth(message.getString(), leftWidth - 32);
        graphics.drawString(font, label,
            panelX + leftWidth / 2 - font.width(label) / 2,
            panelY + 154, MUTED, false);
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
                Component.translatable(selling
                    ? "screen.cobbleventure_player_menu.shop.pending"
                    : "screen.cobbleventure_player_menu.shop.bundle",
                    selling ? quantity : offer.count()),
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
        int maxRow = Math.max(0,
            (filtered.size() - 1) / currentGridColumns() - (currentGridRows() - 1));
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
            category = "";
            selectedIndex = -1;
            quantity = 1;
            status = Component.empty();
            rebuildShopWidgets();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = selling == sellTab;
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), selected
            );
            drawThemedButton(graphics, this, style);
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
            super(x, y, width, height, selling
                ? Component.translatable("screen.cobbleventure_player_menu.bag.category."
                    + (value.isBlank() ? "all" : value))
                : value.isBlank()
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
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), selected
            );
            drawThemedButtonBackground(graphics, this, style);
            String label = getWidth() >= 44
                ? font.plainSubstrByWidth(getMessage().getString(), getWidth() - 23)
                : "";
            int contentWidth = 16 + (label.isBlank() ? 0 : 2 + font.width(label));
            int contentX = getX() + (getWidth() - contentWidth) / 2;
            graphics.renderItem(icon, contentX, getY() + 2);
            if (!label.isBlank()) {
                graphics.drawString(font, label, contentX + 18, getY() + 5, style.text(), false);
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
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, !unavailable, isHovered(), selected
            );
            drawThemedButtonBackground(graphics, this, style);
            graphics.renderItem(offer.stack(), getX() + getWidth() / 2 - 8, getY() + 6);
            String name = font.plainSubstrByWidth(offer.stack().getHoverName().getString(), getWidth() - 6);
            int color = style.text();
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

    /** Compact bag slot used while choosing an item to place in the sale queue. */
    private final class SellItemButton extends AbstractButton {
        private final ShopNetwork.ClientOffer offer;

        private SellItemButton(ShopNetwork.ClientOffer offer, int x, int y) {
            super(x, y, SELL_SLOT_SIZE - 1, SELL_SLOT_SIZE - 1, offer.stack().getHoverName());
            this.offer = offer;
        }

        @Override public void onPress() {
            selectedIndex = offer.index();
            quantity = 1;
            status = Component.empty();
            updateTransactionButton();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = selectedIndex == offer.index();
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), selected
            );
            drawThemedButtonBackground(graphics, this, style);
            graphics.renderItem(offer.stack(), getX() + 2, getY() + 2);
            graphics.renderItemDecorations(font, offer.stack(), getX() + 2, getY() + 2,
                compactCount(offer.owned()));
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
            ShopNetwork.ClientOffer offer = selectedOffer();
            int maximum = selling && offer != null ? offer.owned() : 999;
            maximum = Math.max(1, maximum);
            quantity = delta > 0
                ? (quantity >= maximum ? maximum : quantity + 1)
                : Math.max(1, quantity - 1);
            updateTransactionButton();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            drawThemedButton(graphics, this, theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), false
            ));
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class MaxQuantityButton extends AbstractButton {
        private MaxQuantityButton(int x, int y, int width, int height) {
            super(x, y, width, height,
                Component.translatable("screen.cobbleventure_player_menu.shop.max"));
        }

        @Override public void onPress() {
            ShopNetwork.ClientOffer offer = selectedOffer();
            if (offer == null) return;
            quantity = Math.max(1, offer.owned());
            updateTransactionButton();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            drawThemedButton(graphics, this, theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), false
            ));
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
            drawThemedButton(graphics, this, theme.button(
                MenuTheme.ButtonVariant.PRIMARY, active, isHovered(), false
            ));
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private void drawThemedButton(
        GuiGraphics graphics, AbstractButton button, MenuTheme.ButtonStyle style
    ) {
        drawThemedButtonBackground(graphics, button, style);
        theme.drawCenteredText(
            graphics, font, button.getMessage(),
            button.getX() + button.getWidth() / 2,
            button.getY() + (button.getHeight() - 8) / 2,
            MenuTheme.TextRole.LABEL, style.text()
        );
    }

    private void drawThemedButtonBackground(
        GuiGraphics graphics, AbstractButton button, MenuTheme.ButtonStyle style
    ) {
        int radius = Math.min(theme.rowRadius, button.getHeight() / 2);
        fillRoundedRect(
            graphics, button.getX(), button.getY(),
            button.getX() + button.getWidth(), button.getY() + button.getHeight(),
            radius, style.border()
        );
        fillRoundedRect(
            graphics, button.getX() + 1, button.getY() + 1,
            button.getX() + button.getWidth() - 1, button.getY() + button.getHeight() - 1,
            Math.max(0, radius - 1), style.background()
        );
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

    private static String compactCount(int count) {
        if (count < 1_000) return Integer.toString(count);
        if (count < 1_000_000) return (count / 1_000) + "K";
        return (count / 1_000_000) + "M";
    }
}
