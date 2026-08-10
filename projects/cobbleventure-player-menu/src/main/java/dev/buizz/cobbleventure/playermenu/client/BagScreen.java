package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;

/** 검색, 포켓 분류와 실제 인벤토리 조작을 제공하는 가방 화면. */
public final class BagScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 520;
    private static final int PANEL_MAX_HEIGHT = 300;
    private static final int PANEL_PADDING = 8;
    private static final int TAB_HEIGHT = 22;
    private static final int SLOT_SIZE = 20;
    private static final int LIST_ROW_HEIGHT = 21;
    private static final int DETAIL_HEIGHT = 70;

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
    private final List<BagSlotRef> filteredSlots = new ArrayList<>();

    private BagCategory category = BagCategory.ALL;
    private ViewMode viewMode = ViewMode.GRID;
    private EditBox searchBox;
    private Button useButton;
    private Button shortcutButton;
    private Button discardButton;
    private Button previousPageButton;
    private Button nextPageButton;
    private BagSlotRef selectedSlot;
    private Component statusMessage = Component.empty();
    private String searchValue = "";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentY;
    private int contentColumns;
    private int contentRows;
    private int page;
    private int refreshTicks;
    private int statusTicks;
    private int discardConfirmIndex = -1;
    private int discardConfirmTicks;
    private BagSlotRef draggedSlot;
    private long snapshotRevision = -1L;

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
        panelHeight = Math.min(PANEL_MAX_HEIGHT, Math.max(214, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        int viewWidth = 64;
        int searchWidth = Math.min(150, Math.max(84, panelWidth / 3));
        searchBox = new EditBox(font, panelX + panelWidth - PANEL_PADDING - searchWidth - viewWidth - 4,
            panelY + 6, searchWidth, 18,
            Component.translatable("screen.cobbleventure_player_menu.bag.search"));
        searchBox.setValue(searchValue);
        searchBox.setHint(Component.translatable("screen.cobbleventure_player_menu.bag.search"));
        searchBox.setMaxLength(48);
        searchBox.setResponder(value -> {
            searchValue = value;
            refreshItems(true);
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(viewMode.toggleLabel(), ignored -> toggleView())
            .bounds(panelX + panelWidth - PANEL_PADDING - viewWidth, panelY + 5, viewWidth, 20).build());

        int tabsY = panelY + 29;
        int tabAreaWidth = panelWidth - PANEL_PADDING * 2;
        BagCategory[] categories = BagCategory.values();
        int tabWidth = tabAreaWidth / categories.length;
        for (int index = 0; index < categories.length; index++) {
            int x = panelX + PANEL_PADDING + index * tabWidth;
            int widthForTab = index == categories.length - 1 ? tabAreaWidth - tabWidth * index : tabWidth;
            CategoryButton button = new CategoryButton(categories[index], x, tabsY, widthForTab, TAB_HEIGHT);
            addRenderableWidget(button);
            categoryButtons.add(button);
        }

        contentY = tabsY + TAB_HEIGHT + 5;
        int actionHeight = 27;
        int availableHeight = panelY + panelHeight - PANEL_PADDING - actionHeight - DETAIL_HEIGHT - 6 - contentY;
        if (viewMode == ViewMode.GRID) {
            contentColumns = clamp((panelWidth - PANEL_PADDING * 2) / SLOT_SIZE, 8, 25);
            contentRows = clamp(availableHeight / SLOT_SIZE, 2, 8);
            int gridWidth = contentColumns * SLOT_SIZE;
            contentX = panelX + (panelWidth - gridWidth) / 2;
        } else {
            contentColumns = 1;
            contentRows = clamp(availableHeight / LIST_ROW_HEIGHT, 2, 8);
            contentX = panelX + PANEL_PADDING;
        }

        int capacity = contentColumns * contentRows;
        for (int index = 0; index < capacity; index++) {
            int x = viewMode == ViewMode.GRID
                ? contentX + (index % contentColumns) * SLOT_SIZE
                : contentX;
            int y = viewMode == ViewMode.GRID
                ? contentY + (index / contentColumns) * SLOT_SIZE
                : contentY + index * LIST_ROW_HEIGHT;
            int itemWidth = viewMode == ViewMode.GRID ? SLOT_SIZE - 1 : panelWidth - PANEL_PADDING * 2;
            int itemHeight = viewMode == ViewMode.GRID ? SLOT_SIZE - 1 : LIST_ROW_HEIGHT - 1;
            ItemSlotButton button = new ItemSlotButton(x, y, itemWidth, itemHeight);
            addRenderableWidget(button);
            itemButtons.add(button);
        }

        int detailY = detailY();
        int actionWidth = Math.max(50, Math.min(78, (panelWidth - 156) / 3));
        int actionX = panelX + panelWidth - PANEL_PADDING - actionWidth * 3 - 4;
        useButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.use"), ignored -> useSelectedItem())
            .bounds(actionX, detailY + 8, actionWidth, 20).build());
        shortcutButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.shortcut"), ignored -> assignShortcut())
            .bounds(actionX + actionWidth + 2, detailY + 8, actionWidth, 20).build());
        discardButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.discard"), ignored -> discardSelected())
            .bounds(actionX + (actionWidth + 2) * 2, detailY + 8, actionWidth, 20).build());

        int footerY = panelY + panelHeight - 27;
        previousPageButton = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> changePage(-1))
            .bounds(panelX + PANEL_PADDING, footerY, 20, 20).build());
        nextPageButton = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> changePage(1))
            .bounds(panelX + PANEL_PADDING + 24, footerY, 20, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.back"), ignored -> onClose())
            .bounds(panelX + panelWidth - 74, footerY, 62, 20).build());

        refreshItems(false);
    }

    @Override
    public void tick() {
        super.tick();
        long revision = BagNetwork.clientSnapshot().revision();
        if (revision != snapshotRevision) {
            snapshotRevision = revision;
            refreshItems(false);
        }
        if (statusTicks > 0) statusTicks--;
        if (discardConfirmTicks > 0 && --discardConfirmTicks == 0) resetDiscardConfirmation();
        if (++refreshTicks >= 10) {
            refreshTicks = 0;
            refreshItems(false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, panelX, panelY, panelWidth, panelHeight);
        renderHeader(graphics);
        renderContentBackground(graphics);
        renderDetails(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        for (ItemSlotButton button : itemButtons) {
            if (button.visible && button.isMouseOver(mouseX, mouseY) && !button.stack().isEmpty()) {
                graphics.renderTooltip(font, button.stack(), mouseX, mouseY);
                break;
            }
        }
        if (draggedSlot != null && !draggedSlot.stack().isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 300.0F);
            graphics.renderItem(draggedSlot.stack(), mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(font, draggedSlot.stack(), mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 패널 자체로 대비를 확보하고 월드는 흐리지 않는다.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 || button == 1) {
            ItemSlotButton itemButton = itemButtonAt(mouseX, mouseY);
            if (itemButton != null && itemButton.slot != null) {
                select(itemButton.slot);
                if (!itemButton.slot.stack().isEmpty()) draggedSlot = itemButton.slot;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggedSlot != null && (button == 0 || button == 1)) {
            ItemSlotButton target = itemButtonAt(mouseX, mouseY);
            if (target != null && target.slot != null
                && !target.slot.samePosition(draggedSlot)) {
                PlayerMenuClient.moveBagItem(
                    draggedSlot.extended(), draggedSlot.slot(),
                    target.slot.extended(), target.slot.slot(), button == 1
                );
            }
            draggedSlot = null;
            refreshTicks = 9;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
            if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 && selectedSlot != null) {
                assignShortcut(keyCode - GLFW.GLFW_KEY_1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                changePage(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                changePage(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                toggleView();
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
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderHeader(GuiGraphics graphics) {
        graphics.renderItem(PlayerMenuEntry.BAG.icon(), panelX + 8, panelY + 7);
        graphics.drawString(font, title, panelX + 29, panelY + 11, PRIMARY_TEXT_COLOR, false);
        if (panelWidth >= 430) {
            graphics.drawString(font,
                Component.translatable("screen.cobbleventure_player_menu.bag.capacity", 36, 180),
                panelX + 70, panelY + 11, MUTED_TEXT_COLOR, false);
        }
    }

    private void renderContentBackground(GuiGraphics graphics) {
        if (viewMode != ViewMode.GRID) return;
        for (int row = 0; row < contentRows; row++) {
            for (int column = 0; column < contentColumns; column++) {
                int x = contentX + column * SLOT_SIZE;
                int y = contentY + row * SLOT_SIZE;
                graphics.fill(x, y, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, PANEL_DARK_COLOR);
                graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, SLOT_COLOR);
            }
        }
    }

    private void renderDetails(GuiGraphics graphics) {
        int detailY = detailY();
        graphics.fill(panelX + PANEL_PADDING, detailY, panelX + panelWidth - PANEL_PADDING,
            detailY + DETAIL_HEIGHT, PANEL_DARK_COLOR);
        graphics.fill(panelX + PANEL_PADDING + 1, detailY + 1, panelX + panelWidth - PANEL_PADDING - 1,
            detailY + DETAIL_HEIGHT - 1, 0xE04A4A4A);

        ItemStack stack = selectedStack();
        int textX = panelX + PANEL_PADDING + 34;
        int actionStart = useButton == null ? panelX + panelWidth - 180 : useButton.getX();
        int textWidth = Math.max(60, actionStart - textX - 7);
        if (stack.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.cobbleventure_player_menu.bag.empty_selection"),
                panelX + PANEL_PADDING + 8, detailY + 29, MUTED_TEXT_COLOR, false);
        } else {
            graphics.renderItem(stack, panelX + PANEL_PADDING + 9, detailY + 9);
            graphics.renderItemDecorations(font, stack, panelX + PANEL_PADDING + 9, detailY + 9);
            graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), textWidth),
                textX, detailY + 8, PRIMARY_TEXT_COLOR, false);
            Component source = Component.translatable("screen.cobbleventure_player_menu.bag.source."
                + (selectedSlot.extended() ? "extended" : "inventory"));
            graphics.drawString(font, Component.translatable(
                "screen.cobbleventure_player_menu.bag.count_and_source", stack.getCount(), source),
                textX, detailY + 20, SECONDARY_TEXT_COLOR, false);
            renderDescription(graphics, stack, textX, detailY + 34, textWidth);
        }

        if (statusTicks > 0) {
            graphics.drawString(font, font.plainSubstrByWidth(statusMessage.getString(), panelWidth - 30),
                panelX + PANEL_PADDING + 8, detailY + DETAIL_HEIGHT - 12, ACCENT_COLOR, false);
        }
        Component pageText = Component.translatable("screen.cobbleventure_player_menu.bag.page",
            Math.min(page + 1, pageCount()), pageCount());
        graphics.drawString(font, pageText, panelX + PANEL_PADDING + 51,
            panelY + panelHeight - 21, MUTED_TEXT_COLOR, false);
    }

    private void renderDescription(GuiGraphics graphics, ItemStack stack, int x, int y, int width) {
        List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY, minecraft.player, TooltipFlag.NORMAL);
        int renderedLines = 0;
        for (int index = 1; index < tooltip.size() && renderedLines < 2; index++) {
            for (FormattedCharSequence line : font.split(tooltip.get(index), width)) {
                graphics.drawString(font, line, x, y + renderedLines * 10, MUTED_TEXT_COLOR, false);
                if (++renderedLines >= 2) break;
            }
        }
    }

    private void refreshItems(boolean resetPage) {
        if (minecraft == null || minecraft.player == null || itemButtons.isEmpty()) return;
        boolean selectedExtended = selectedSlot != null && selectedSlot.extended();
        int selectedIndex = selectedSlot == null ? -1 : selectedSlot.slot();
        filteredSlots.clear();

        Inventory inventory = minecraft.player.getInventory();
        String query = searchBox == null ? searchValue : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        for (int inventoryIndex = 9; inventoryIndex < 36; inventoryIndex++) {
            addIfVisible(false, inventoryIndex, inventory.getItem(inventoryIndex), query);
        }
        for (int inventoryIndex = 0; inventoryIndex < 9; inventoryIndex++) {
            addIfVisible(false, inventoryIndex, inventory.getItem(inventoryIndex), query);
        }
        List<ItemStack> extendedSlots = BagNetwork.clientSnapshot().slots();
        for (int slot = 0; slot < extendedSlots.size(); slot++) {
            addIfVisible(true, slot, extendedSlots.get(slot), query);
        }

        if (resetPage) page = 0;
        page = clamp(page, 0, pageCount() - 1);
        selectedSlot = filteredSlots.stream()
            .filter(slot -> slot.extended() == selectedExtended && slot.slot() == selectedIndex && !slot.stack().isEmpty())
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
        updateActionButtons();
    }

    private void addIfVisible(boolean extended, int slot, ItemStack stack, String query) {
        if (stack.isEmpty() && (category != BagCategory.ALL || !query.isEmpty())) return;
        if (!stack.isEmpty() && !category.matches(stack)) return;
        if (!stack.isEmpty() && !query.isEmpty()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY, minecraft.player, TooltipFlag.NORMAL);
            StringBuilder searchable = new StringBuilder(stack.getHoverName().getString().toLowerCase(Locale.ROOT))
                .append(' ').append(itemId.toString().toLowerCase(Locale.ROOT));
            for (Component line : tooltip) searchable.append(' ').append(line.getString().toLowerCase(Locale.ROOT));
            if (!searchable.toString().contains(query)) return;
        }
        filteredSlots.add(new BagSlotRef(extended, slot, stack));
    }

    private void select(BagSlotRef slot) {
        if (slot == null) return;
        selectedSlot = slot.stack().isEmpty() ? null : slot;
        resetDiscardConfirmation();
        updateActionButtons();
    }

    private void useSelectedItem() {
        if (selectedSlot == null || selectedStack().isEmpty()) return;
        PlayerMenuClient.useBagItem(selectedSlot.extended(), selectedSlot.slot());
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.used"));
        refreshTicks = 9;
    }

    private void assignShortcut() {
        if (minecraft != null && minecraft.player != null) assignShortcut(minecraft.player.getInventory().selected);
    }

    private void assignShortcut(int hotbarIndex) {
        if (selectedSlot == null || selectedStack().isEmpty()) return;
        PlayerMenuClient.assignBagItemToHotbar(selectedSlot.extended(), selectedSlot.slot(), hotbarIndex);
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.shortcut_registered", hotbarIndex + 1));
        refreshTicks = 9;
    }

    private void discardSelected() {
        if (selectedSlot == null || selectedStack().isEmpty()) return;
        int identity = selectedSlot.extended() ? 1000 + selectedSlot.slot() : selectedSlot.slot();
        if (discardConfirmIndex != identity || discardConfirmTicks <= 0) {
            discardConfirmIndex = identity;
            discardConfirmTicks = 60;
            discardButton.setMessage(Component.translatable("screen.cobbleventure_player_menu.bag.discard_confirm"));
            return;
        }
        PlayerMenuClient.discardBagItem(selectedSlot.extended(), selectedSlot.slot());
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.discarded"));
        resetDiscardConfirmation();
        refreshTicks = 9;
    }

    private void resetDiscardConfirmation() {
        discardConfirmIndex = -1;
        discardConfirmTicks = 0;
        if (discardButton != null) discardButton.setMessage(Component.translatable("screen.cobbleventure_player_menu.bag.discard"));
    }

    private void updateActionButtons() {
        boolean hasSelection = selectedSlot != null && !selectedStack().isEmpty();
        if (useButton != null) useButton.active = hasSelection;
        if (shortcutButton != null) shortcutButton.active = hasSelection;
        if (discardButton != null) discardButton.active = hasSelection;
    }

    private void showStatus(Component message) {
        statusMessage = message;
        statusTicks = 60;
    }

    private ItemStack selectedStack() {
        if (minecraft == null || minecraft.player == null || selectedSlot == null) return ItemStack.EMPTY;
        if (!selectedSlot.extended()) return minecraft.player.getInventory().getItem(selectedSlot.slot());
        List<ItemStack> slots = BagNetwork.clientSnapshot().slots();
        return selectedSlot.slot() >= 0 && selectedSlot.slot() < slots.size()
            ? slots.get(selectedSlot.slot()) : ItemStack.EMPTY;
    }

    private void toggleView() {
        viewMode = viewMode == ViewMode.GRID ? ViewMode.LIST : ViewMode.GRID;
        page = 0;
        rebuildWidgets();
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

    private int detailY() {
        int contentHeight = viewMode == ViewMode.GRID ? contentRows * SLOT_SIZE : contentRows * LIST_ROW_HEIGHT;
        return contentY + contentHeight + 6;
    }

    private ItemSlotButton itemButtonAt(double mouseX, double mouseY) {
        for (ItemSlotButton button : itemButtons) {
            if (button.visible && button.isMouseOver(mouseX, mouseY)) return button;
        }
        return null;
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

    private enum ViewMode {
        GRID,
        LIST;

        Component toggleLabel() {
            return Component.translatable("screen.cobbleventure_player_menu.bag.view."
                + (this == GRID ? "list" : "grid"));
        }
    }

    private enum BagCategory {
        ALL("all"), RECOVERY("recovery"), BALLS("balls"), BATTLE("battle"),
        MATERIALS("materials"), KEY_ITEMS("key_items");

        private final String id;

        BagCategory(String id) { this.id = id; }

        Component title() {
            return Component.translatable("screen.cobbleventure_player_menu.bag.category." + id);
        }

        boolean matches(ItemStack stack) {
            if (this == ALL) return true;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String namespace = itemId.getNamespace();
            String path = itemId.getPath();
            return switch (this) {
                case RECOVERY -> stack.has(DataComponents.FOOD)
                    || containsAny(path, "potion", "heal", "revive", "ether", "elixir", "berry", "candy");
                case BALLS -> namespace.equals("cobblemon") && (path.endsWith("_ball") || path.contains("poke_ball"));
                case BATTLE -> stack.isDamageableItem()
                    || containsAny(path, "sword", "bow", "shield", "vest", "band", "specs", "scarf", "gem");
                case KEY_ITEMS -> containsAny(path, "pokedex", "exp_share", "key", "badge", "map", "compass");
                case MATERIALS -> !RECOVERY.matches(stack) && !BALLS.matches(stack)
                    && !BATTLE.matches(stack) && !KEY_ITEMS.matches(stack);
                case ALL -> true;
            };
        }

        private static boolean containsAny(String value, String... candidates) {
            for (String candidate : candidates) if (value.contains(candidate)) return true;
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
            if (selected) graphics.fill(getX() + 2, getY() + getHeight() - 3,
                getX() + getWidth() - 2, getY() + getHeight() - 1, ACCENT_COLOR);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(getMessage().getString(), getWidth() - 6),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, text);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class ItemSlotButton extends AbstractButton {
        private BagSlotRef slot;

        private ItemSlotButton(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        void setSlot(BagSlotRef slot) {
            this.slot = slot;
            visible = slot != null;
        }

        ItemStack stack() { return slot == null ? ItemStack.EMPTY : slot.stack(); }

        @Override
        public void onPress() { select(slot); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (slot == null) return;
            boolean selected = selectedSlot != null && selectedSlot.samePosition(slot)
                && !slot.stack().isEmpty();
            int fill = selected ? SLOT_SELECTED_COLOR : (isHovered() ? SLOT_HOVER_COLOR : SLOT_COLOR);
            int text = selected ? PANEL_DARK_COLOR : PRIMARY_TEXT_COLOR;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_DARK_COLOR);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
            if (selected) graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + 2, ACCENT_COLOR);
            if (!slot.stack().isEmpty()) {
                graphics.renderItem(slot.stack(), getX() + 2, getY() + 2);
                graphics.renderItemDecorations(font, slot.stack(), getX() + 2, getY() + 2);
                if (viewMode == ViewMode.LIST) {
                    int nameWidth = getWidth() - 78;
                    graphics.drawString(font, font.plainSubstrByWidth(slot.stack().getHoverName().getString(), nameWidth),
                        getX() + 23, getY() + 6, text, false);
                    String count = "×" + slot.stack().getCount();
                    graphics.drawString(font, count, getX() + getWidth() - font.width(count) - 7, getY() + 6, text, false);
                }
            } else if (viewMode == ViewMode.LIST) {
                Component emptyLabel = Component.translatable("screen.cobbleventure_player_menu.bag.empty_slot",
                    slot.extended() ? slot.slot() + 1 : slot.slot() + 1);
                graphics.drawString(font, emptyLabel, getX() + 7, getY() + 6, MUTED_TEXT_COLOR, false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private record BagSlotRef(boolean extended, int slot, ItemStack stack) {
        boolean samePosition(BagSlotRef other) {
            return other != null && extended == other.extended && slot == other.slot;
        }
    }
}
