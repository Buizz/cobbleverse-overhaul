package dev.buizz.cobbleventure.playermenu.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** 검색과 포켓 분류를 제공하는 B안 기반의 1차 가방 화면. */
public final class BagScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 430;
    private static final int PANEL_MAX_HEIGHT = 238;
    private static final int PANEL_PADDING = 8;
    private static final int TAB_HEIGHT = 22;
    private static final int SLOT_SIZE = 20;
    private static final int DETAIL_HEIGHT = 52;

    private static final int SHADOW_COLOR = 0xB0000000;
    private static final int PANEL_COLOR = 0xF05A5A5A;
    private static final int PANEL_DARK_COLOR = 0xFF303030;
    private static final int PANEL_LIGHT_COLOR = 0xFF888888;
    private static final int SLOT_COLOR = 0xE0444444;
    private static final int SLOT_HOVER_COLOR = 0xF05A5A5A;
    private static final int SLOT_SELECTED_COLOR = 0xFFF0F0F0;
    private static final int PRIMARY_TEXT_COLOR = 0xFFF4F4F4;
    private static final int SECONDARY_TEXT_COLOR = 0xFFD0D0D0;
    private static final int MUTED_TEXT_COLOR = 0xFFA6A6A6;
    private static final int ACCENT_COLOR = 0xFF91C7A2;

    private final Screen parent;
    private final List<CategoryButton> categoryButtons = new ArrayList<>();
    private final List<ItemSlotButton> itemButtons = new ArrayList<>();
    private final List<InventorySlotRef> filteredSlots = new ArrayList<>();

    private BagCategory category = BagCategory.ALL;
    private EditBox searchBox;
    private Button useButton;
    private Button previousPageButton;
    private Button nextPageButton;
    private InventorySlotRef selectedSlot;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int gridX;
    private int gridY;
    private int gridColumns;
    private int gridRows;
    private int page;
    private int refreshTicks;

    public BagScreen(Screen parent) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        categoryButtons.clear();
        itemButtons.clear();

        panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(292, width - 16));
        panelHeight = Math.min(PANEL_MAX_HEIGHT, Math.max(204, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        int searchWidth = Math.min(124, panelWidth / 3);
        searchBox = new EditBox(
            font,
            panelX + panelWidth - PANEL_PADDING - searchWidth,
            panelY + 6,
            searchWidth,
            18,
            Component.translatable("screen.cobbleventure_player_menu.bag.search")
        );
        searchBox.setHint(Component.translatable("screen.cobbleventure_player_menu.bag.search"));
        searchBox.setMaxLength(48);
        searchBox.setResponder(ignored -> refreshItems(true));
        addRenderableWidget(searchBox);

        int tabsY = panelY + 29;
        int tabAreaWidth = panelWidth - PANEL_PADDING * 2;
        BagCategory[] categories = BagCategory.values();
        int tabWidth = tabAreaWidth / categories.length;
        for (int index = 0; index < categories.length; index++) {
            int x = panelX + PANEL_PADDING + index * tabWidth;
            int widthForTab = index == categories.length - 1
                ? tabAreaWidth - tabWidth * index
                : tabWidth;
            CategoryButton button = new CategoryButton(categories[index], x, tabsY, widthForTab, TAB_HEIGHT);
            addRenderableWidget(button);
            categoryButtons.add(button);
        }

        gridX = panelX + PANEL_PADDING;
        gridY = tabsY + TAB_HEIGHT + 5;
        int footerHeight = 27;
        int gridAvailableHeight = panelY + panelHeight - PANEL_PADDING - footerHeight
            - DETAIL_HEIGHT - 7 - gridY;
        gridColumns = clamp((panelWidth - PANEL_PADDING * 2) / SLOT_SIZE, 8, 12);
        gridRows = clamp(gridAvailableHeight / SLOT_SIZE, 3, 5);
        int gridWidth = gridColumns * SLOT_SIZE;
        gridX = panelX + (panelWidth - gridWidth) / 2;

        int capacity = gridColumns * gridRows;
        for (int index = 0; index < capacity; index++) {
            ItemSlotButton button = new ItemSlotButton(
                gridX + (index % gridColumns) * SLOT_SIZE,
                gridY + (index / gridColumns) * SLOT_SIZE
            );
            addRenderableWidget(button);
            itemButtons.add(button);
        }

        int detailY = gridY + gridRows * SLOT_SIZE + 6;
        useButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.use"),
            ignored -> useSelectedItem()
        ).bounds(panelX + panelWidth - 74, detailY + 15, 62, 20).build());

        int footerY = panelY + panelHeight - 27;
        previousPageButton = addRenderableWidget(Button.builder(
            Component.literal("<"),
            ignored -> changePage(-1)
        ).bounds(panelX + PANEL_PADDING, footerY, 20, 20).build());
        nextPageButton = addRenderableWidget(Button.builder(
            Component.literal(">"),
            ignored -> changePage(1)
        ).bounds(panelX + PANEL_PADDING + 24, footerY, 20, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.back"),
            ignored -> onClose()
        ).bounds(panelX + panelWidth - 74, footerY, 62, 20).build());

        refreshItems(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks >= 10) {
            refreshTicks = 0;
            refreshItems(false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, panelX, panelY, panelWidth, panelHeight);
        renderHeader(graphics);
        renderGridBackground(graphics);
        renderDetails(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        for (ItemSlotButton button : itemButtons) {
            if (button.visible && button.isMouseOver(mouseX, mouseY) && !button.stack().isEmpty()) {
                graphics.renderTooltip(font, button.stack(), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // B안 패널 자체로 대비를 확보하고 월드는 흐리지 않는다.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            minecraft.setScreen(null);
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (!searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                changePage(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                changePage(1);
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && selectedSlot != null) {
                useSelectedItem();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            changePage(scrollY > 0.0D ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    private void renderHeader(GuiGraphics graphics) {
        ItemStack bagIcon = PlayerMenuEntry.BAG.icon();
        graphics.renderItem(bagIcon, panelX + 8, panelY + 7);
        graphics.drawString(font, title, panelX + 29, panelY + 11, PRIMARY_TEXT_COLOR, false);
    }

    private void renderGridBackground(GuiGraphics graphics) {
        for (int row = 0; row < gridRows; row++) {
            for (int column = 0; column < gridColumns; column++) {
                int x = gridX + column * SLOT_SIZE;
                int y = gridY + row * SLOT_SIZE;
                graphics.fill(x, y, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, PANEL_DARK_COLOR);
                graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, SLOT_COLOR);
            }
        }
    }

    private void renderDetails(GuiGraphics graphics) {
        int detailY = gridY + gridRows * SLOT_SIZE + 6;
        graphics.fill(panelX + PANEL_PADDING, detailY, panelX + panelWidth - PANEL_PADDING,
            detailY + DETAIL_HEIGHT, PANEL_DARK_COLOR);
        graphics.fill(panelX + PANEL_PADDING + 1, detailY + 1, panelX + panelWidth - PANEL_PADDING - 1,
            detailY + DETAIL_HEIGHT - 1, 0xE04A4A4A);

        ItemStack stack = selectedStack();
        if (stack.isEmpty()) {
            graphics.drawString(
                font,
                Component.translatable("screen.cobbleventure_player_menu.bag.empty_selection"),
                panelX + PANEL_PADDING + 8,
                detailY + 21,
                MUTED_TEXT_COLOR,
                false
            );
        } else {
            graphics.renderItem(stack, panelX + PANEL_PADDING + 9, detailY + 9);
            graphics.renderItemDecorations(font, stack, panelX + PANEL_PADDING + 9, detailY + 9);
            String name = font.plainSubstrByWidth(stack.getHoverName().getString(), panelWidth - 132);
            graphics.drawString(font, name, panelX + PANEL_PADDING + 34, detailY + 10,
                PRIMARY_TEXT_COLOR, false);
            Component count = Component.translatable(
                "screen.cobbleventure_player_menu.bag.count",
                stack.getCount()
            );
            graphics.drawString(font, count, panelX + PANEL_PADDING + 34, detailY + 27,
                SECONDARY_TEXT_COLOR, false);
        }

        int pageCount = pageCount();
        Component pageText = Component.translatable(
            "screen.cobbleventure_player_menu.bag.page",
            Math.min(page + 1, pageCount),
            pageCount
        );
        graphics.drawString(font, pageText, panelX + PANEL_PADDING + 51,
            panelY + panelHeight - 21, MUTED_TEXT_COLOR, false);
    }

    private void refreshItems(boolean resetPage) {
        if (minecraft == null || minecraft.player == null || itemButtons.isEmpty()) {
            return;
        }
        int selectedInventoryIndex = selectedSlot == null ? -1 : selectedSlot.inventoryIndex();
        filteredSlots.clear();

        Inventory inventory = minecraft.player.getInventory();
        String query = searchBox == null ? "" : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        // 본래 인벤토리 배열은 핫바가 먼저지만, 가방에서는 주 인벤토리를 먼저 보여준다.
        for (int inventoryIndex = 9; inventoryIndex < 36; inventoryIndex++) {
            addIfVisible(inventory, inventoryIndex, query);
        }
        for (int inventoryIndex = 0; inventoryIndex < 9; inventoryIndex++) {
            addIfVisible(inventory, inventoryIndex, query);
        }

        if (resetPage) {
            page = 0;
        }
        page = clamp(page, 0, pageCount() - 1);

        selectedSlot = filteredSlots.stream()
            .filter(slot -> slot.inventoryIndex() == selectedInventoryIndex && !slot.stack().isEmpty())
            .findFirst()
            .orElseGet(() -> filteredSlots.stream().filter(slot -> !slot.stack().isEmpty()).findFirst().orElse(null));

        int start = page * itemButtons.size();
        for (int index = 0; index < itemButtons.size(); index++) {
            int itemIndex = start + index;
            itemButtons.get(index).setSlot(itemIndex < filteredSlots.size() ? filteredSlots.get(itemIndex) : null);
        }

        boolean multiplePages = pageCount() > 1;
        previousPageButton.visible = multiplePages;
        nextPageButton.visible = multiplePages;
        previousPageButton.active = page > 0;
        nextPageButton.active = page + 1 < pageCount();
        useButton.active = selectedSlot != null && !selectedSlot.stack().isEmpty();
    }

    private void addIfVisible(Inventory inventory, int inventoryIndex, String query) {
        ItemStack stack = inventory.getItem(inventoryIndex);
        if (stack.isEmpty() && (category != BagCategory.ALL || !query.isEmpty())) {
            return;
        }
        if (!stack.isEmpty() && !category.matches(stack)) {
            return;
        }
        if (!stack.isEmpty() && !query.isEmpty()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String searchable = stack.getHoverName().getString().toLowerCase(Locale.ROOT)
                + " " + itemId.toString().toLowerCase(Locale.ROOT);
            if (!searchable.contains(query)) {
                return;
            }
        }
        filteredSlots.add(new InventorySlotRef(inventoryIndex, stack));
    }

    private void select(InventorySlotRef slot) {
        if (slot == null || slot.stack().isEmpty()) {
            return;
        }
        selectedSlot = slot;
        useButton.active = true;
    }

    private void useSelectedItem() {
        if (selectedSlot == null || selectedSlot.stack().isEmpty()) {
            return;
        }
        PlayerMenuClient.useInventoryItem(selectedSlot.inventoryIndex());
    }

    private ItemStack selectedStack() {
        if (minecraft == null || minecraft.player == null || selectedSlot == null) {
            return ItemStack.EMPTY;
        }
        return minecraft.player.getInventory().getItem(selectedSlot.inventoryIndex());
    }

    private void changePage(int delta) {
        int nextPage = clamp(page + delta, 0, pageCount() - 1);
        if (nextPage != page) {
            page = nextPage;
            refreshItems(false);
        }
    }

    private int pageCount() {
        int capacity = Math.max(1, itemButtons.size());
        return Math.max(1, (filteredSlots.size() + capacity - 1) / capacity);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        graphics.fill(x + 3, y + 3, x + panelWidth + 3, y + panelHeight + 3, SHADOW_COLOR);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL_DARK_COLOR);
        graphics.fill(x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1, PANEL_COLOR);
        graphics.fill(x + 2, y + 2, x + panelWidth - 2, y + 3, PANEL_LIGHT_COLOR);
        graphics.fill(x + 2, y + 2, x + 3, y + panelHeight - 2, PANEL_LIGHT_COLOR);
    }

    private enum BagCategory {
        ALL("all"),
        RECOVERY("recovery"),
        BALLS("balls"),
        BATTLE("battle"),
        MATERIALS("materials"),
        KEY_ITEMS("key_items");

        private final String id;

        BagCategory(String id) {
            this.id = id;
        }

        Component title() {
            return Component.translatable("screen.cobbleventure_player_menu.bag.category." + id);
        }

        boolean matches(ItemStack stack) {
            if (this == ALL) {
                return true;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String namespace = itemId.getNamespace();
            String path = itemId.getPath();
            return switch (this) {
                case RECOVERY -> stack.has(DataComponents.FOOD)
                    || containsAny(path, "potion", "heal", "revive", "ether", "elixir", "berry", "candy");
                case BALLS -> namespace.equals("cobblemon")
                    && (path.endsWith("_ball") || path.contains("poke_ball"));
                case BATTLE -> stack.isDamageableItem()
                    || containsAny(path, "sword", "bow", "shield", "vest", "band", "specs", "scarf", "gem");
                case KEY_ITEMS -> containsAny(path, "pokedex", "exp_share", "key", "badge", "map", "compass");
                case MATERIALS -> !RECOVERY.matches(stack)
                    && !BALLS.matches(stack)
                    && !BATTLE.matches(stack)
                    && !KEY_ITEMS.matches(stack);
                case ALL -> true;
            };
        }

        private static boolean containsAny(String value, String... candidates) {
            for (String candidate : candidates) {
                if (value.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final class CategoryButton extends AbstractButton {
        private final BagCategory buttonCategory;

        private CategoryButton(BagCategory category, int x, int y, int width, int height) {
            super(x, y, width, height, category.title());
            this.buttonCategory = category;
        }

        @Override
        public void onPress() {
            category = buttonCategory;
            refreshItems(true);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = category == buttonCategory;
            int fill = selected ? SLOT_SELECTED_COLOR : (isHovered() ? SLOT_HOVER_COLOR : SLOT_COLOR);
            int text = selected ? PANEL_DARK_COLOR : PRIMARY_TEXT_COLOR;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_DARK_COLOR);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
            if (selected) {
                graphics.fill(getX() + 2, getY() + getHeight() - 3,
                    getX() + getWidth() - 2, getY() + getHeight() - 1, ACCENT_COLOR);
            }
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 6);
            graphics.drawCenteredString(font, label, getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2, text);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class ItemSlotButton extends AbstractButton {
        private InventorySlotRef slot;

        private ItemSlotButton(int x, int y) {
            super(x, y, SLOT_SIZE - 1, SLOT_SIZE - 1, Component.empty());
        }

        void setSlot(InventorySlotRef slot) {
            this.slot = slot;
            visible = slot != null;
        }

        ItemStack stack() {
            return slot == null ? ItemStack.EMPTY : slot.stack();
        }

        @Override
        public void onPress() {
            select(slot);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (slot == null) {
                return;
            }
            boolean selected = selectedSlot != null
                && selectedSlot.inventoryIndex() == slot.inventoryIndex()
                && !slot.stack().isEmpty();
            int fill = selected ? SLOT_SELECTED_COLOR : (isHovered() ? SLOT_HOVER_COLOR : SLOT_COLOR);
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_DARK_COLOR);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
            if (selected) {
                graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + 2, ACCENT_COLOR);
            }
            if (!slot.stack().isEmpty()) {
                graphics.renderItem(slot.stack(), getX() + 2, getY() + 2);
                graphics.renderItemDecorations(font, slot.stack(), getX() + 2, getY() + 2);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private record InventorySlotRef(int inventoryIndex, ItemStack stack) {}
}
