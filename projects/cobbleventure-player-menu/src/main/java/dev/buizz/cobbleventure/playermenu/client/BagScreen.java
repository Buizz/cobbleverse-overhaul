package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.api.item.PokemonSelectingItem;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
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
    private static final int PANEL_MAX_WIDTH = 640;
    private static final int PANEL_MAX_HEIGHT = 360;
    private static final int PANEL_PADDING = 8;
    private static final int TAB_HEIGHT = 22;
    private static final int SLOT_SIZE = 20;
    private static final int LIST_ROW_HEIGHT = 21;
    private static final int DETAIL_HEIGHT = 70;

    private static final int SHADOW_COLOR = 0x99000000;
    private static final int PANEL_COLOR = 0xF01D2630;
    private static final int PANEL_DARK_COLOR = 0xFF10171E;
    private static final int PANEL_LIGHT_COLOR = 0xFF34444F;
    private static final int SLOT_COLOR = 0xD0222D37;
    private static final int SLOT_HOVER_COLOR = 0xE0374650;
    private static final int SLOT_SELECTED_COLOR = 0xFFF0F3F5;
    private static final int PRIMARY_TEXT_COLOR = 0xFFF4F4F4;
    private static final int SECONDARY_TEXT_COLOR = 0xFFD0D0D0;
    private static final int MUTED_TEXT_COLOR = 0xFFA6A6A6;
    private static final int SELECTED_TEXT_COLOR = 0xFF303030;
    private static final int ACCENT_COLOR = 0xFF5EE4E4;
    private static final int SEPARATOR_COLOR = 0x553F505B;

    private final Screen parent;
    private final List<CategoryButton> categoryButtons = new ArrayList<>();
    private final List<ItemSlotButton> itemButtons = new ArrayList<>();
    private final List<BagSlotRef> filteredSlots = new ArrayList<>();

    private BagCategory category = BagCategory.ALL;
    private ViewMode viewMode = ViewMode.GRID;
    private EditBox searchBox;
    private AbstractButton useButton;
    private AbstractButton giveButton;
    private AbstractButton shortcutButton;
    private AbstractButton dropButton;
    private AbstractButton deleteButton;
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
    private int scrollRow;
    private int refreshTicks;
    private int statusTicks;
    private BagSlotRef draggedSlot;
    private boolean scrollbarDragging;
    private boolean contentDragging;
    private double lastDragY;
    private double dragAccumulator;
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
        searchBox = new VerticallyCenteredEditBox(font,
            panelX + panelWidth - PANEL_PADDING - searchWidth - viewWidth - 4,
            panelY + 6, searchWidth, 18,
            Component.translatable("screen.cobbleventure_player_menu.bag.search"));
        searchBox.setValue(searchValue);
        searchBox.setHint(Component.translatable("screen.cobbleventure_player_menu.bag.search"));
        searchBox.setMaxLength(48);
        searchBox.setBordered(false);
        searchBox.setResponder(value -> {
            searchValue = value;
            refreshItems(true);
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(new RibbonButton(viewMode.toggleLabel(),
            panelX + panelWidth - PANEL_PADDING - viewWidth, panelY + 5, viewWidth, 20, this::toggleView));

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
            contentColumns = clamp((panelWidth - PANEL_PADDING * 2 - 8) / SLOT_SIZE, 8, 25);
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
            int itemWidth = viewMode == ViewMode.GRID ? SLOT_SIZE - 1 : panelWidth - PANEL_PADDING * 2 - 8;
            int itemHeight = viewMode == ViewMode.GRID ? SLOT_SIZE - 1 : LIST_ROW_HEIGHT - 1;
            ItemSlotButton button = new ItemSlotButton(x, y, itemWidth, itemHeight);
            addRenderableWidget(button);
            itemButtons.add(button);
        }

        int detailY = detailY();
        int actionWidth = Math.max(46, Math.min(64, (panelWidth - 150) / 3));
        int actionX = panelX + panelWidth - PANEL_PADDING - actionWidth * 3 - 4;
        useButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.use"),
            actionX, detailY + 8, actionWidth, 20, this::useSelectedItem));
        giveButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.give_to_pokemon"),
            actionX + actionWidth + 2, detailY + 8, actionWidth, 20, this::giveToPokemon));
        shortcutButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.shortcut"),
            actionX + (actionWidth + 2) * 2, detailY + 8, actionWidth, 20, this::assignShortcut));
        dropButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.drop"),
            actionX, detailY + 32, actionWidth, 20, this::dropSelected));
        deleteButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.delete"),
            actionX + actionWidth + 2, detailY + 32, actionWidth, 20, this::deleteSelected));

        int footerY = panelY + panelHeight - 27;
        addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.back"),
            panelX + panelWidth - 74, footerY, 62, 20, this::onClose));

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
        renderScrollbar(graphics);
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
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            scrollbarDragging = true;
            updateScrollbarDrag(mouseY);
            return true;
        }
        if (button == 0 || button == 1) {
            ItemSlotButton itemButton = itemButtonAt(mouseX, mouseY);
            if (itemButton != null && itemButton.slot != null) {
                if (button == 0 && itemButton.slot.stack().isEmpty()) {
                    contentDragging = true;
                    lastDragY = mouseY;
                    dragAccumulator = 0.0D;
                    return true;
                }
                select(itemButton.slot);
                if (!itemButton.slot.stack().isEmpty()) draggedSlot = itemButton.slot;
                return true;
            }
        }
        if ((button == 0 || button == 2) && isInsideContent(mouseX, mouseY)) {
            contentDragging = true;
            lastDragY = mouseY;
            dragAccumulator = 0.0D;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollbarDragging && button == 0) {
            updateScrollbarDrag(mouseY);
            return true;
        }
        if (contentDragging && (button == 0 || button == 2)) {
            dragAccumulator += lastDragY - mouseY;
            lastDragY = mouseY;
            int rowHeight = viewMode == ViewMode.GRID ? SLOT_SIZE : LIST_ROW_HEIGHT;
            int rows = (int)(dragAccumulator / Math.max(8.0D, rowHeight * 0.6D));
            if (rows != 0) {
                scrollBy(rows);
                dragAccumulator -= rows * Math.max(8.0D, rowHeight * 0.6D);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrollbarDragging || contentDragging) {
            scrollbarDragging = false;
            contentDragging = false;
            draggedSlot = null;
            return true;
        }
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
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (searchBox != null && searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            minecraft.setScreen(null);
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 && selectedSlot != null) {
            assignShortcut(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_0 && selectedSlot != null) {
            assignShortcut(9);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scrollBy(-contentRows);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollBy(contentRows);
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            scrollBy(scrollY > 0.0D ? -1 : 1);
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
        graphics.fill(panelX + 12, panelY + 3, panelX + 52, panelY + 5, ACCENT_COLOR);
        fillCircle(graphics, panelX + 18, panelY + 15, 11, PANEL_LIGHT_COLOR);
        fillCircle(graphics, panelX + 18, panelY + 15, 9, PANEL_DARK_COLOR);
        graphics.renderItem(PlayerMenuEntry.BAG.icon(), panelX + 10, panelY + 7);
        graphics.drawString(font, title, panelX + 34, panelY + 11, PRIMARY_TEXT_COLOR, false);
        if (panelWidth >= 430) {
            graphics.drawString(font,
                Component.translatable("screen.cobbleventure_player_menu.bag.capacity",
                    36, BagNetwork.extendedSlotCount()),
                panelX + 88, panelY + 11, MUTED_TEXT_COLOR, false);
        }
        int searchLeft = searchBox.getX() - 4;
        int searchTop = searchBox.getY() - 2;
        fillRoundedRect(graphics, searchLeft, searchTop,
            searchLeft + searchBox.getWidth() + 8, searchTop + searchBox.getHeight() + 4,
            8, PANEL_DARK_COLOR);
        graphics.fill(searchLeft + 8, searchTop + searchBox.getHeight() + 2,
            searchLeft + searchBox.getWidth(), searchTop + searchBox.getHeight() + 3,
            searchBox.isFocused() ? ACCENT_COLOR : PANEL_LIGHT_COLOR);
        graphics.fill(panelX + 12, panelY + 27, panelX + panelWidth - 12, panelY + 28,
            PANEL_LIGHT_COLOR);
    }

    private void renderContentBackground(GuiGraphics graphics) {
        if (viewMode != ViewMode.GRID) return;
        for (int row = 0; row < contentRows; row++) {
            for (int column = 0; column < contentColumns; column++) {
                int x = contentX + column * SLOT_SIZE;
                int y = contentY + row * SLOT_SIZE;
                fillRoundedRect(graphics, x, y, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 4,
                    PANEL_DARK_COLOR);
                fillRoundedRect(graphics, x + 1, y + 1, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, 3,
                    SLOT_COLOR);
            }
        }
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int x = scrollbarX();
        int top = contentY;
        int bottom = contentY + contentHeight();
        fillRoundedRect(graphics, x, top, x + 5, bottom, 2, PANEL_DARK_COLOR);
        int maximum = maxScrollRow();
        if (maximum <= 0) {
            fillRoundedRect(graphics, x + 1, top + 1, x + 4, bottom - 1, 1, SLOT_COLOR);
            return;
        }
        int thumbHeight = scrollbarThumbHeight();
        int travel = Math.max(1, bottom - top - thumbHeight);
        int thumbY = top + Math.round((float)scrollRow / maximum * travel);
        fillRoundedRect(graphics, x + 1, thumbY, x + 4, thumbY + thumbHeight, 1, ACCENT_COLOR);
    }

    private void renderDetails(GuiGraphics graphics) {
        int detailY = detailY();
        fillRoundedRect(graphics, panelX + PANEL_PADDING, detailY,
            panelX + panelWidth - PANEL_PADDING, detailY + DETAIL_HEIGHT, 7, PANEL_DARK_COLOR);
        fillRoundedRect(graphics, panelX + PANEL_PADDING + 1, detailY + 1,
            panelX + panelWidth - PANEL_PADDING - 1, detailY + DETAIL_HEIGHT - 1, 6, SLOT_COLOR);
        graphics.fill(panelX + PANEL_PADDING + 10, detailY + 1,
            panelX + PANEL_PADDING + 42, detailY + 3, ACCENT_COLOR);

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
                "screen.cobbleventure_player_menu.bag.count_and_source", selectedSlot.displayCount(), source),
                textX, detailY + 20, SECONDARY_TEXT_COLOR, false);
            renderDescription(graphics, stack, textX, detailY + 34, textWidth);
        }

        if (statusTicks > 0) {
            graphics.drawString(font, font.plainSubstrByWidth(statusMessage.getString(), panelWidth - 30),
                panelX + PANEL_PADDING + 8, detailY + DETAIL_HEIGHT - 12, ACCENT_COLOR, false);
        }
        Component scrollText = Component.translatable("screen.cobbleventure_player_menu.bag.scroll_status",
            filteredSlots.stream().filter(slot -> !slot.stack().isEmpty()).count());
        graphics.drawString(font, font.plainSubstrByWidth(scrollText.getString(), panelWidth - 104),
            panelX + PANEL_PADDING + 8,
            panelY + panelHeight - 21, MUTED_TEXT_COLOR, false);
    }

    private void renderDescription(GuiGraphics graphics, ItemStack stack, int x, int y, int width) {
        List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY, minecraft.player, TooltipFlag.NORMAL);
        List<Component> description = new ArrayList<>();
        for (int index = 1; index < tooltip.size(); index++) {
            if (!tooltip.get(index).getString().isBlank()) description.add(tooltip.get(index));
        }
        if (description.isEmpty()) description.addAll(translatedDescription(stack));
        if (description.isEmpty()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            description.add(Component.translatable(
                "screen.cobbleventure_player_menu.bag.no_description", itemId));
        }

        int renderedLines = 0;
        for (Component component : description) {
            for (FormattedCharSequence line : font.split(component, width)) {
                graphics.drawString(font, line, x, y + renderedLines * 10, MUTED_TEXT_COLOR, false);
                if (++renderedLines >= 2) return;
            }
        }
    }

    private List<Component> translatedDescription(ItemStack stack) {
        String descriptionId = stack.getDescriptionId();
        List<Component> result = new ArrayList<>();

        addTranslatedLine(result, descriptionId + ".tooltip");
        for (int index = 1; index <= 4; index++) {
            addTranslatedLine(result, descriptionId + ".tooltip_" + index);
        }
        if (result.isEmpty()) addTranslatedLine(result, descriptionId + ".description");
        if (result.isEmpty()) addTranslatedLine(result, descriptionId + ".desc");
        return result;
    }

    private static void addTranslatedLine(List<Component> result, String translationKey) {
        if (I18n.exists(translationKey)) result.add(Component.translatable(translationKey));
    }

    private void refreshItems(boolean resetScroll) {
        if (minecraft == null || minecraft.player == null || itemButtons.isEmpty()) return;
        boolean selectedExtended = selectedSlot != null && selectedSlot.extended();
        int selectedIndex = selectedSlot == null ? -1 : selectedSlot.slot();
        filteredSlots.clear();

        Inventory inventory = minecraft.player.getInventory();
        String query = searchBox == null ? searchValue : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        for (int inventoryIndex = 9; inventoryIndex < 36; inventoryIndex++) {
            ItemStack stack = inventory.getItem(inventoryIndex);
            addIfVisible(false, inventoryIndex, stack, stack.getCount(), query);
        }
        for (int inventoryIndex = 0; inventoryIndex < 9; inventoryIndex++) {
            ItemStack stack = inventory.getItem(inventoryIndex);
            addIfVisible(false, inventoryIndex, stack, stack.getCount(), query);
        }
        List<ItemStack> extendedSlots = BagNetwork.clientSnapshot().slots();
        List<BagSlotRef> groups = new ArrayList<>();
        Map<Integer, List<Integer>> groupBuckets = new HashMap<>();
        List<Integer> emptySlots = new ArrayList<>();
        for (int slot = 0; slot < extendedSlots.size(); slot++) {
            ItemStack stack = extendedSlots.get(slot);
            if (stack.isEmpty()) {
                if (emptySlots.size() < Math.max(64, itemButtons.size())) emptySlots.add(slot);
                continue;
            }
            int hash = ItemStack.hashItemAndComponents(stack);
            List<Integer> candidates = groupBuckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
            BagSlotRef matched = null;
            int matchedIndex = -1;
            for (int candidate : candidates) {
                BagSlotRef group = groups.get(candidate);
                if (ItemStack.isSameItemSameComponents(group.stack(), stack)) {
                    matched = group;
                    matchedIndex = candidate;
                    break;
                }
            }
            if (matched == null) {
                candidates.add(groups.size());
                groups.add(new BagSlotRef(true, slot, stack, stack.getCount()));
            } else {
                groups.set(matchedIndex, new BagSlotRef(true, matched.slot(), matched.stack(),
                    matched.displayCount() + stack.getCount()));
            }
        }
        for (BagSlotRef group : groups) {
            addIfVisible(true, group.slot(), group.stack(), group.displayCount(), query);
        }
        if (category == BagCategory.ALL && query.isEmpty()) {
            for (int emptySlot : emptySlots) addIfVisible(true, emptySlot, ItemStack.EMPTY, 0, query);
        }

        filteredSlots.sort(Comparator
            .comparing((BagSlotRef slot) -> slot.stack().isEmpty())
            .thenComparing(BagScreen::sortName)
            .thenComparing(slot -> slot.extended() ? 1 : 0)
            .thenComparingInt(BagSlotRef::slot));

        if (resetScroll) scrollRow = 0;
        scrollRow = clamp(scrollRow, 0, maxScrollRow());
        selectedSlot = filteredSlots.stream()
            .filter(slot -> slot.extended() == selectedExtended && slot.slot() == selectedIndex && !slot.stack().isEmpty())
            .findFirst()
            .orElseGet(() -> filteredSlots.stream().filter(slot -> !slot.stack().isEmpty()).findFirst().orElse(null));

        int start = scrollRow * contentColumns;
        for (int index = 0; index < itemButtons.size(); index++) {
            int itemIndex = start + index;
            itemButtons.get(index).setSlot(itemIndex < filteredSlots.size() ? filteredSlots.get(itemIndex) : null);
        }
        updateActionButtons();
    }

    private void addIfVisible(boolean extended, int slot, ItemStack stack, int displayCount, String query) {
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
        filteredSlots.add(new BagSlotRef(extended, slot, stack, displayCount));
    }

    private void select(BagSlotRef slot) {
        if (slot == null) return;
        selectedSlot = slot.stack().isEmpty() ? null : slot;
        updateActionButtons();
    }

    private void useSelectedItem() {
        if (selectedSlot == null || selectedStack().isEmpty()) return;
        if (selectedStack().getItem() instanceof PokemonSelectingItem) {
            if (minecraft != null) minecraft.setScreen(new BagPokemonSelectScreen(
                this, selectedSlot.extended(), selectedSlot.slot(), selectedStack().copy(),
                BagPokemonSelectScreen.Action.USE
            ));
            return;
        }
        PlayerMenuClient.useBagItem(selectedSlot.extended(), selectedSlot.slot());
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.used"));
        refreshTicks = 9;
    }

    private void assignShortcut() {
        if (minecraft == null || selectedSlot == null || selectedStack().isEmpty()) return;
        minecraft.setScreen(new BagShortcutSelectScreen(
            this, selectedSlot.extended(), selectedSlot.slot(), selectedStack().copy()
        ));
    }

    private void assignShortcut(int shortcutIndex) {
        if (selectedSlot == null || selectedStack().isEmpty()) return;
        PlayerMenuClient.assignBagItemToShortcut(selectedSlot.extended(), selectedSlot.slot(), shortcutIndex);
        shortcutAssigned(shortcutIndex);
        refreshTicks = 9;
    }

    private void dropSelected() {
        if (minecraft == null || selectedSlot == null || selectedStack().isEmpty()) return;
        minecraft.setScreen(new BagDiscardScreen(
            this, selectedSlot.extended(), selectedSlot.slot(), selectedStack().copy(), selectedSlot.displayCount(),
            BagDiscardScreen.Mode.DROP
        ));
    }

    private void deleteSelected() {
        if (minecraft == null || selectedSlot == null || selectedStack().isEmpty()) return;
        minecraft.setScreen(new BagDiscardScreen(
            this, selectedSlot.extended(), selectedSlot.slot(), selectedStack().copy(), selectedSlot.displayCount(),
            BagDiscardScreen.Mode.DELETE
        ));
    }

    private void giveToPokemon() {
        if (minecraft == null || selectedSlot == null || selectedStack().isEmpty()) return;
        minecraft.setScreen(new BagPokemonSelectScreen(
            this, selectedSlot.extended(), selectedSlot.slot(), selectedStack().copy(),
            BagPokemonSelectScreen.Action.GIVE
        ));
    }

    void shortcutAssigned(int shortcutIndex) {
        showStatus(Component.translatable(
            "screen.cobbleventure_player_menu.bag.shortcut_registered", shortcutIndex + 1
        ));
    }

    void dropRequested(int quantity) {
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.dropped", quantity));
        refreshTicks = 9;
    }

    void deleteRequested(int quantity) {
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.deleted", quantity));
        refreshTicks = 9;
    }

    void pokemonGiveRequested(Component pokemonName) {
        showStatus(Component.translatable(
            "screen.cobbleventure_player_menu.bag.given_to_pokemon", pokemonName
        ));
        refreshTicks = 9;
    }

    void pokemonUseRequested() {
        showStatus(Component.translatable("screen.cobbleventure_player_menu.bag.used"));
        refreshTicks = 9;
    }

    private void updateActionButtons() {
        boolean hasSelection = selectedSlot != null && !selectedStack().isEmpty();
        if (useButton != null) useButton.active = hasSelection;
        if (giveButton != null) giveButton.active = hasSelection && CobblemonMenuIntegration.partySize() > 0;
        if (shortcutButton != null) shortcutButton.active = hasSelection;
        if (dropButton != null) dropButton.active = hasSelection;
        if (deleteButton != null) deleteButton.active = hasSelection;
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
        scrollRow = 0;
        rebuildWidgets();
    }

    private void scrollBy(int rows) {
        int nextRow = clamp(scrollRow + rows, 0, maxScrollRow());
        if (nextRow != scrollRow) {
            scrollRow = nextRow;
            refreshItems(false);
        }
    }

    private int maxScrollRow() {
        int totalRows = Math.max(1, (filteredSlots.size() + contentColumns - 1) / contentColumns);
        return Math.max(0, totalRows - contentRows);
    }

    private int detailY() {
        return contentY + contentHeight() + 6;
    }

    private int contentHeight() {
        return viewMode == ViewMode.GRID ? contentRows * SLOT_SIZE : contentRows * LIST_ROW_HEIGHT;
    }

    private int scrollbarX() {
        return panelX + panelWidth - PANEL_PADDING - 5;
    }

    private int scrollbarThumbHeight() {
        int totalRows = Math.max(1, contentRows + maxScrollRow());
        return Math.max(12, contentHeight() * contentRows / totalRows);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        return mouseX >= scrollbarX() - 2 && mouseX <= scrollbarX() + 7
            && mouseY >= contentY && mouseY <= contentY + contentHeight();
    }

    private boolean isInsideContent(double mouseX, double mouseY) {
        return mouseX >= panelX + PANEL_PADDING && mouseX < scrollbarX() - 2
            && mouseY >= contentY && mouseY < contentY + contentHeight();
    }

    private void updateScrollbarDrag(double mouseY) {
        int maximum = maxScrollRow();
        if (maximum <= 0) return;
        int thumbHeight = scrollbarThumbHeight();
        int travel = Math.max(1, contentHeight() - thumbHeight);
        double ratio = (mouseY - contentY - thumbHeight / 2.0D) / travel;
        int nextRow = clamp((int)Math.round(ratio * maximum), 0, maximum);
        if (nextRow != scrollRow) {
            scrollRow = nextRow;
            refreshItems(false);
        }
    }

    private ItemSlotButton itemButtonAt(double mouseX, double mouseY) {
        for (ItemSlotButton button : itemButtons) {
            if (button.visible && button.isMouseOver(mouseX, mouseY)) return button;
        }
        return null;
    }

    private static String sortName(BagSlotRef slot) {
        if (slot.stack().isEmpty()) return "";
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(slot.stack().getItem());
        return slot.stack().getHoverName().getString().toLowerCase(Locale.ROOT) + "\u0000" + id;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        fillRoundedRect(graphics, x + 4, y + 5, x + panelWidth + 4, y + panelHeight + 5,
            9, SHADOW_COLOR);
        fillRoundedRect(graphics, x, y, x + panelWidth, y + panelHeight, 9, PANEL_DARK_COLOR);
        fillRoundedRect(graphics, x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1,
            8, PANEL_COLOR);
        graphics.fill(x + 12, y + 1, x + panelWidth - 12, y + 2, PANEL_LIGHT_COLOR);
        graphics.fill(x + panelWidth - 42, y + panelHeight - 3,
            x + panelWidth - 8, y + panelHeight - 1, ACCENT_COLOR);
    }

    private static void fillRoundedRect(
        GuiGraphics graphics,
        int left,
        int top,
        int right,
        int bottom,
        int radius,
        int color
    ) {
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);
        int effectiveRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int edgeDistance = Math.min(row, height - 1 - row);
            int inset = 0;
            if (edgeDistance < effectiveRadius) {
                double vertical = effectiveRadius - edgeDistance - 0.5D;
                inset = effectiveRadius - (int) Math.floor(Math.sqrt(
                    Math.max(0.0D, effectiveRadius * effectiveRadius - vertical * vertical)
                ));
            }
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
        }
    }

    private static void fillCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            int halfWidth = (int) Math.floor(Math.sqrt(radius * radius - y * y));
            graphics.fill(centerX - halfWidth, centerY + y,
                centerX + halfWidth + 1, centerY + y + 1, color);
        }
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
            int text = selected ? SELECTED_TEXT_COLOR : PRIMARY_TEXT_COLOR;
            if (selected) {
                fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    getHeight() / 2, ACCENT_COLOR);
                fillRoundedRect(graphics, getX() + 1, getY() + 1,
                    getX() + getWidth() - 1, getY() + getHeight() - 1,
                    Math.max(1, getHeight() / 2 - 1), fill);
            } else if (isHovered()) {
                fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    getHeight() / 2, fill);
            }
            graphics.fill(getX() + 8, getY() + getHeight() - 1,
                getX() + getWidth() - 8, getY() + getHeight(), SEPARATOR_COLOR);
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
            int text = selected ? SELECTED_TEXT_COLOR : PRIMARY_TEXT_COLOR;
            if (selected) {
                fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    viewMode == ViewMode.LIST ? getHeight() / 2 : 4, ACCENT_COLOR);
                fillRoundedRect(graphics, getX() + 1, getY() + 1,
                    getX() + getWidth() - 1, getY() + getHeight() - 1,
                    viewMode == ViewMode.LIST ? Math.max(1, getHeight() / 2 - 1) : 3, fill);
            } else if (isHovered()) {
                fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    viewMode == ViewMode.LIST ? getHeight() / 2 : 4, fill);
            }
            if (viewMode == ViewMode.LIST) {
                graphics.fill(getX() + 24, getY() + getHeight() - 1,
                    getX() + getWidth() - 8, getY() + getHeight(), SEPARATOR_COLOR);
            }
            if (!slot.stack().isEmpty()) {
                graphics.renderItem(slot.stack(), getX() + 2, getY() + 2);
                String decoration = slot.displayCount() == slot.stack().getCount()
                    ? null : compactCount(slot.displayCount());
                graphics.renderItemDecorations(font, slot.stack(), getX() + 2, getY() + 2, decoration);
                if (viewMode == ViewMode.LIST) {
                    int nameWidth = getWidth() - 78;
                    graphics.drawString(font, font.plainSubstrByWidth(slot.stack().getHoverName().getString(), nameWidth),
                        getX() + 23, getY() + 6, text, false);
                    String count = "×" + slot.displayCount();
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

    private final class RibbonButton extends AbstractButton {
        private final Runnable action;

        private RibbonButton(Component message, int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
        }

        @Override
        public void onPress() {
            if (active) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = active && isHovered() ? ACCENT_COLOR : PANEL_LIGHT_COLOR;
            int fill = active && isHovered() ? SLOT_HOVER_COLOR : PANEL_DARK_COLOR;
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                getHeight() / 2, border);
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(1, getHeight() / 2 - 1), fill);
            int color = active ? (isHovered() ? ACCENT_COLOR : PRIMARY_TEXT_COLOR) : MUTED_TEXT_COLOR;
            graphics.drawCenteredString(font,
                font.plainSubstrByWidth(getMessage().getString(), getWidth() - 10),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** 테두리를 숨긴 EditBox도 글자가 입력 영역의 세로 중앙에 오도록 보정한다. */
    private static final class VerticallyCenteredEditBox extends EditBox {
        private final Font textFont;

        private VerticallyCenteredEditBox(
            Font font, int x, int y, int width, int height, Component message
        ) {
            super(font, x, y, width, height, message);
            this.textFont = font;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int offsetY = Math.max(0, (getHeight() - textFont.lineHeight) / 2);
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, offsetY, 0.0F);
            super.renderWidget(graphics, mouseX, mouseY - offsetY, partialTick);
            graphics.pose().popPose();
        }
    }

    private record BagSlotRef(boolean extended, int slot, ItemStack stack, int displayCount) {
        boolean samePosition(BagSlotRef other) {
            return other != null && extended == other.extended && slot == other.slot;
        }
    }

    private static String compactCount(int count) {
        if (count < 1_000) return Integer.toString(count);
        if (count < 1_000_000) return (count / 1_000) + "K";
        return (count / 1_000_000) + "M";
    }
}
