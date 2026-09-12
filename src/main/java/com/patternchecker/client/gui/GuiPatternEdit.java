package com.patternchecker.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Mouse;

import com.patternchecker.check.ItemName;
import com.patternchecker.network.PacketEditCommit;
import com.patternchecker.network.PacketEditData;
import com.patternchecker.network.PatternCheckerNetwork;

/**
 * Per-slot pattern editor (ghost slots) laid out like AE2's pattern terminal: a
 * 3x3 input grid plus the output row beside it.
 *
 * <p>Left-click with an item sets the slot (count = held stack size), empty-hand
 * left-click clears, mouse wheel adjusts the hovered count (Shift = x10),
 * right-click with the same item adds one held stack, and items can be dragged in
 * straight from the NEI item panel. Nothing is consumed until "encode and upload",
 * which submits the whole layout to the server for validation and re-encoding.
 */
public class GuiPatternEdit extends GuiContainer {

    private static final int BTN_DIV10 = 0;
    private static final int BTN_DIV2 = 1;
    private static final int BTN_MINUS = 2;
    private static final int BTN_PLUS = 3;
    private static final int BTN_MUL2 = 4;
    private static final int BTN_MUL10 = 5;
    private static final int BTN_UPLOAD = 6;
    private static final int BTN_CANCEL = 7;

    private static final int INPUT_SLOTS = 9;
    private static final int OUTPUT_SLOTS = 3;
    private static final int GRID_COLS = 3;
    private static final int CELL = 18;
    /** Cells touch, exactly like AE2's own crafting/pattern grid. */
    private static final int PITCH = 18;
    private static final long MAX_COUNT = 1_000_000_000_000_000L;

    /**
     * Input grid origin, GUI-relative. Public because NEI needs it to line its recipe
     * overlay up with the 3x3 grid (see {@code PatternCheckNei}).
     */
    public static final int GRID_ORIGIN_X = 44;
    public static final int GRID_ORIGIN_Y = 52;
    private static final int OUT_X = 140;
    private static final int OUT_Y = 52;
    private static final int LABEL_Y = 42;
    private static final int HINT_Y = 34;
    private static final int SUM_Y = 110;
    private static final int INV_TOP = 162;
    private static final int HOTBAR_Y = 216;

    private static final RenderItem itemRender = RenderItem.getInstance();

    private static final class EditSlot {

        ItemStack type;
        long count;
    }

    private final PacketEditData data;
    private final EditSlot[] inputs = new EditSlot[INPUT_SLOTS];
    private final EditSlot[] outputs = new EditSlot[OUTPUT_SLOTS];
    /** Localized pattern name (the server only sends a descriptor). */
    private String displayName = "";
    private boolean actionSent;
    /**
     * Set when NEI's drag &amp; drop placed an item during the current click. NEI's
     * drop is dispatched from inside {@code GuiContainer.mouseClicked}, so the click
     * reaches us through {@code super.mouseClicked} first; when that already handled
     * the slot, applying the cursor-based logic on top would clear it again (the
     * player's cursor is empty while dragging out of the NEI panel).
     */
    private boolean neiDropHandled;

    public GuiPatternEdit(PacketEditData data, Container container) {
        super(container);
        this.data = data;
        this.xSize = 236;
        // 238, not 244: GTNH's GUI auto-scale leaves a 240px tall canvas on very
        // common setups (854x480, 1280x720), and anything taller hangs off the top.
        this.ySize = 238;
        this.displayName = ItemName.resolve(data.name);
        copyInto(this.inputs, data.inputs);
        copyInto(this.outputs, data.outputs);
    }

    /**
     * Replaces the editor contents in place. Used when the server's edit payload
     * arrives after the screen is already open (the window packet and the payload
     * travel on different channels, so either order is possible).
     */
    public void applyData(PacketEditData packet) {
        if (packet == null) {
            return;
        }
        this.data.name = packet.name;
        this.data.targetDesc = packet.targetDesc;
        this.displayName = ItemName.resolve(packet.name);
        this.actionSent = false;
        copyInto(this.inputs, packet.inputs);
        copyInto(this.outputs, packet.outputs);
    }

    private static void copyInto(EditSlot[] target, List<PacketEditData.EditEntry> source) {
        for (int i = 0; i < target.length; i++) {
            target[i] = new EditSlot();
            if (source != null && i < source.size()) {
                PacketEditData.EditEntry e = source.get(i);
                if (!e.empty && e.icon != null) {
                    target[i].type = e.icon;
                    target[i].count = Math.max(1L, e.count);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Geometry (also read by the NEI integration)
    // ------------------------------------------------------------------

    @Override
    public void initGui() {
        super.initGui();
        int left = this.guiLeft;
        int top = this.guiTop;
        int by = top + 120;
        this.buttonList.add(new GuiButton(BTN_DIV10, left + 6, by, 34, 18, "\u00f710"));
        this.buttonList.add(new GuiButton(BTN_DIV2, left + 42, by, 26, 18, "\u00f72"));
        this.buttonList.add(new GuiButton(BTN_MINUS, left + 70, by, 26, 18, "-1"));
        this.buttonList.add(new GuiButton(BTN_PLUS, left + 98, by, 26, 18, "+1"));
        this.buttonList.add(new GuiButton(BTN_MUL2, left + 126, by, 26, 18, "\u00d72"));
        this.buttonList.add(new GuiButton(BTN_MUL10, left + 154, by, 34, 18, "\u00d710"));
        this.buttonList.add(new GuiButton(BTN_UPLOAD, left + 6, top + 140, 110, 18,
                I18n.format("patternchecker.edit.upload")));
        this.buttonList.add(new GuiButton(BTN_CANCEL, left + 120, top + 140, 110, 18,
                I18n.format("patternchecker.edit.cancel")));
    }

    /**
     * The six amount buttons act on <b>every filled slot at once</b>, so the
     * effect is immediate and visible in the grid (a deferred global multiplier
     * that bottoms out at x1 looked like a dead button whenever a slot's amount
     * was 1). Per-slot tuning is still done with the mouse wheel.
     */
    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
        case BTN_DIV10:
            scaleAll(10, true);
            break;
        case BTN_DIV2:
            scaleAll(2, true);
            break;
        case BTN_MINUS:
            offsetAll(-1);
            break;
        case BTN_PLUS:
            offsetAll(1);
            break;
        case BTN_MUL2:
            scaleAll(2, false);
            break;
        case BTN_MUL10:
            scaleAll(10, false);
            break;
        case BTN_UPLOAD:
            this.actionSent = true;
            PatternCheckerNetwork.INSTANCE.sendToServer(buildCommit(false));
            this.mc.displayGuiScreen(null);
            return;
        case BTN_CANCEL:
            this.actionSent = true;
            PatternCheckerNetwork.INSTANCE.sendToServer(buildCommit(true));
            this.mc.displayGuiScreen(null);
            return;
        default:
            return;
        }
    }

    /** Multiplies (or divides, flooring at 1) every filled slot's amount. */
    private void scaleAll(int factor, boolean divide) {
        for (EditSlot s : this.inputs) {
            scale(s, factor, divide);
        }
        for (EditSlot s : this.outputs) {
            scale(s, factor, divide);
        }
    }

    private static void scale(EditSlot slot, int factor, boolean divide) {
        if (slot.type == null) {
            return;
        }
        if (divide) {
            slot.count = clampCount(slot.count / factor);
            return;
        }
        if (slot.count > MAX_COUNT / factor) {
            slot.count = MAX_COUNT;
        } else {
            slot.count = clampCount(slot.count * factor);
        }
    }

    /** Adds/subtracts the same amount on every filled slot. */
    private void offsetAll(long delta) {
        for (EditSlot s : this.inputs) {
            offset(s, delta);
        }
        for (EditSlot s : this.outputs) {
            offset(s, delta);
        }
    }

    private static void offset(EditSlot slot, long delta) {
        if (slot.type == null) {
            return;
        }
        slot.count = clampCount(slot.count + delta);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (!this.actionSent) {
            // closed with ESC/inventory key: release the server session
            this.actionSent = true;
            if (PatternCheckerNetwork.INSTANCE != null) {
                PatternCheckerNetwork.INSTANCE.sendToServer(buildCommit(true));
            }
        }
    }

    private PacketEditCommit buildCommit(boolean cancel) {
        PacketEditCommit commit = new PacketEditCommit();
        commit.cancel = cancel;
        // Amounts are already final in the slots (the buttons edit them in place),
        // so no extra scaling happens server side.
        commit.multiplier = 1;
        fillStates(commit.inputs, this.inputs);
        fillStates(commit.outputs, this.outputs);
        return commit;
    }

    private static void fillStates(List<PacketEditCommit.SlotState> target, EditSlot[] slots) {
        for (EditSlot s : slots) {
            PacketEditCommit.SlotState st = new PacketEditCommit.SlotState();
            if (s.type != null && s.count >= 1) {
                st.empty = false;
                st.itemId = Item.getIdFromItem(s.type.getItem());
                st.damage = s.type.getItemDamage();
                st.count = s.count;
                st.tag = s.type.getTagCompound();
            } else {
                st.empty = true;
            }
            target.add(st);
        }
    }

    private static long clampCount(long v) {
        return Math.max(1L, Math.min(MAX_COUNT, v));
    }

    // ------------------------------------------------------------------
    // Ghost slot interaction
    // ------------------------------------------------------------------

    /**
     * @return 0..8 for the input grid (row-major, matching AE2's slot order),
     *         100 + i for the output row, or -1 when nothing is hovered.
     */
    private int hitSlot(int mouseX, int mouseY) {
        int relX = mouseX - this.guiLeft;
        int relY = mouseY - this.guiTop;

        int col = (relX - GRID_ORIGIN_X) / PITCH;
        int row = (relY - GRID_ORIGIN_Y) / PITCH;
        if (col >= 0 && col < GRID_COLS && row >= 0 && row < GRID_COLS
                && relX >= GRID_ORIGIN_X + col * PITCH && relX < GRID_ORIGIN_X + col * PITCH + CELL
                && relY >= GRID_ORIGIN_Y + row * PITCH && relY < GRID_ORIGIN_Y + row * PITCH + CELL) {
            return row * GRID_COLS + col;
        }

        int oCol = (relX - OUT_X) / PITCH;
        if (oCol >= 0 && oCol < OUTPUT_SLOTS && relY >= OUT_Y && relY < OUT_Y + CELL
                && relX >= OUT_X + oCol * PITCH && relX < OUT_X + oCol * PITCH + CELL) {
            return 100 + oCol;
        }
        return -1;
    }

    private EditSlot slotAt(int hit) {
        return hit < 100 ? this.inputs[hit] : this.outputs[hit - 100];
    }

    private void handleGhostClick(EditSlot slot, int button) {
        ItemStack cursor = this.mc.thePlayer.inventory.getItemStack();
        if (cursor == null) {
            slot.type = null;
            slot.count = 0;
            return;
        }
        applyStackToSlot(slot, cursor, button);
    }

    /**
     * Shared by the cursor-click path and the NEI drag path: button 0 replaces the
     * slot, any other button adds a hand of the same item.
     */
    private static void applyStackToSlot(EditSlot slot, ItemStack source, int button) {
        boolean sameItem = slot.type != null && slot.type.isItemEqual(source)
                && ItemStack.areItemStackTagsEqual(slot.type, source);
        if (button != 0 && sameItem) {
            slot.count = clampCount(slot.count + Math.max(1, source.stackSize));
            return;
        }
        ItemStack type = source.copy();
        type.stackSize = 1;
        slot.type = type;
        slot.count = clampCount(Math.max(1, source.stackSize));
    }

    // ------------------------------------------------------------------
    // NEI integration hooks (NEI is the 1.7.10 recipe viewer; GTNH has no JEI)
    // ------------------------------------------------------------------

    /**
     * Called by the NEI relay while an item dragged out of the NEI item panel is
     * being released over this screen. NEI hands the dragged stack to every
     * registered {@code INEIGuiHandler} through {@code handleDragNDrop} and stops
     * at the first one that returns true.
     *
     * @return true when the drop landed in one of the editor's ghost slots.
     */
    public boolean handleNeiDrag(int mouseX, int mouseY, ItemStack dragged, int button) {
        if (dragged == null || dragged.getItem() == null) {
            return false;
        }
        int hit = hitSlot(mouseX, mouseY);
        if (hit < 0) {
            return false;
        }
        applyStackToSlot(slotAt(hit), dragged, button);
        this.neiDropHandled = true;
        return true;
    }

    /** Tells NEI whether its item panel slot at this screen rect sits under this GUI. */
    public boolean isRegionOverGui(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        return x + w > this.guiLeft && x < this.guiLeft + this.xSize && y + h > this.guiTop
                && y < this.guiTop + this.ySize;
    }

    /**
     * Fills the editor from a recipe (NEI's recipe overlay transfer). The grid is
     * row-major 3x3; anything the recipe does not cover is cleared, so the result is
     * exactly the recipe being shown. A slot that already holds the same item keeps
     * its count, so a previously tuned amount is not thrown away.
     */
    public void fillFromRecipe(ItemStack[] grid, ItemStack output) {
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack src = grid != null && i < grid.length ? grid[i] : null;
            setFromRecipe(this.inputs[i], src);
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            setFromRecipe(this.outputs[i], i == 0 ? output : null);
        }
    }

    private static void setFromRecipe(EditSlot slot, ItemStack source) {
        if (source == null || source.getItem() == null) {
            slot.type = null;
            slot.count = 0;
            return;
        }
        if (slot.type != null && slot.type.isItemEqual(source)
                && ItemStack.areItemStackTagsEqual(slot.type, source)) {
            return;
        }
        ItemStack type = source.copy();
        type.stackSize = 1;
        slot.type = type;
        slot.count = clampCount(Math.max(1, source.stackSize));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Must go through super first: NEI reaches a GUI through
        // GuiContainer.mouseClicked, and that is where it dispatches a drop from its
        // item panel (LayoutManager.mouseClicked -> Widget.handleClickExt ->
        // PanelWidget.handleDraggedClick -> INEIGuiHandler.handleDragNDrop). Returning
        // early here would swallow exactly the clicks that have to reach NEI.
        this.neiDropHandled = false;
        super.mouseClicked(mouseX, mouseY, button);

        int hit = hitSlot(mouseX, mouseY);
        if (hit < 0 || this.neiDropHandled) {
            return;
        }
        handleGhostClick(slotAt(hit), button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || this.mc.thePlayer == null) {
            return;
        }
        int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int hit = hitSlot(mx, my);
        if (hit < 0) {
            return;
        }
        EditSlot slot = slotAt(hit);
        if (slot.type == null) {
            return;
        }
        long delta = wheel > 0 ? 1 : -1;
        if (isShiftKeyDown()) {
            delta *= 10;
        }
        slot.count = clampCount(slot.count + delta);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * The ghost slots are not real {@code Slot}s, so neither vanilla nor NEI ever
     * produce a tooltip for them; show the entry name and its exact amount (the
     * in-cell amount is abbreviated for space).
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        int hit = hitSlot(mouseX, mouseY);
        if (hit < 0) {
            return;
        }
        EditSlot slot = slotAt(hit);
        if (slot.type == null || slot.type.getItem() == null) {
            return;
        }
        List<String> tip = new ArrayList<>(2);
        tip.add(slot.type.getDisplayName());
        tip.add(I18n.format("patternchecker.edit.countTip", slot.count));
        this.drawHoveringText(tip, mouseX, mouseY, this.fontRendererObj);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // Flat fills only. This pass still runs with GL_DEPTH_TEST enabled and the
        // world's depth values in the buffer, so anything drawn here can z-fight with
        // what is around it and flicker; the ghost cells are painted in the foreground
        // pass instead, which MC runs with depth testing off.
        int left = this.guiLeft;
        int top = this.guiTop;
        drawRect(left, top, left + this.xSize, top + this.ySize, 0xF0101014);
        drawRect(left, top, left + this.xSize, top + 1, 0xFF3A3A4A);
        drawRect(left, top + this.ySize - 1, left + this.xSize, top + this.ySize, 0xFF3A3A4A);
        drawRect(left, top, left + 1, top + this.ySize, 0xFF3A3A4A);
        drawRect(left + this.xSize - 1, top, left + this.xSize, top + this.ySize, 0xFF3A3A4A);

        // player inventory cell background
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int x = left + 38 + col * 18;
                int y = top + INV_TOP + row * 18;
                drawRect(x - 1, y - 1, x + 17, y + 17, 0xFF26262E);
            }
        }
        for (int col = 0; col < 9; col++) {
            int x = left + 38 + col * 18;
            int y = top + HOTBAR_Y;
            drawRect(x - 1, y - 1, x + 17, y + 17, 0xFF26262E);
        }
    }

    /**
     * Ghost cells: background fill, icon and amount, all painted in the foreground
     * pass where MC has depth testing disabled. Drawn in three sweeps because the
     * phases need different GL state: flat fills and text must not run with GUI item
     * lighting on, and icons should.
     */
    private void paintGhostCells() {
        for (int i = 0; i < INPUT_SLOTS; i++) {
            drawCellBackground(GRID_ORIGIN_X + (i % GRID_COLS) * PITCH, GRID_ORIGIN_Y + (i / GRID_COLS) * PITCH);
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            drawCellBackground(OUT_X + i * PITCH, OUT_Y);
        }

        RenderHelper.enableGUIStandardItemLighting();
        for (int i = 0; i < INPUT_SLOTS; i++) {
            drawCellIcon(this.inputs[i], GRID_ORIGIN_X + (i % GRID_COLS) * PITCH,
                    GRID_ORIGIN_Y + (i / GRID_COLS) * PITCH);
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            drawCellIcon(this.outputs[i], OUT_X + i * PITCH, OUT_Y);
        }
        RenderHelper.disableStandardItemLighting();

        for (int i = 0; i < INPUT_SLOTS; i++) {
            drawCellCount(this.inputs[i], GRID_ORIGIN_X + (i % GRID_COLS) * PITCH,
                    GRID_ORIGIN_Y + (i / GRID_COLS) * PITCH);
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            drawCellCount(this.outputs[i], OUT_X + i * PITCH, OUT_Y);
        }
    }

    private void drawCellBackground(int x, int y) {
        drawRect(x - 1, y - 1, x + CELL, y + CELL, 0xFF2A2A34);
    }

    private void drawCellIcon(EditSlot slot, int x, int y) {
        ItemStack icon = slot.type;
        if (icon == null || icon.getItem() == null) {
            return;
        }
        itemRender.renderItemAndEffectIntoGUI(this.fontRendererObj, this.mc.getTextureManager(), icon, x, y);
        itemRender.renderItemOverlayIntoGUI(this.fontRendererObj, this.mc.getTextureManager(), icon, x, y, null);
    }

    private void drawCellCount(EditSlot slot, int x, int y) {
        if (slot.type == null || slot.count <= 1) {
            return;
        }
        this.fontRendererObj.drawStringWithShadow(
                this.fontRendererObj.trimStringToWidth(formatCount(slot.count), CELL - 1), x + 1, y + 9, 0x9ADB9A);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        paintGhostCells();

        int width = this.xSize;
        String title = I18n.format("patternchecker.edit.title");
        this.fontRendererObj.drawStringWithShadow(title, width / 2 - this.fontRendererObj.getStringWidth(title) / 2,
                6, 0xFFFFFF);

        String name = this.fontRendererObj.trimStringToWidth(this.displayName, width - 12);
        this.fontRendererObj.drawStringWithShadow(name, 6, 16, 0xFFFFFF);
        String target = I18n.format("patternchecker.location.provider", this.data.targetDesc);
        this.fontRendererObj.drawStringWithShadow(
                this.fontRendererObj.trimStringToWidth(target, width - 12), 6, 25, 0x8A8A9A);
        this.fontRendererObj.drawStringWithShadow(
                this.fontRendererObj.trimStringToWidth(I18n.format("patternchecker.edit.hint"), width - 12),
                6, HINT_Y, 0x6A7A6A);

        this.fontRendererObj.drawStringWithShadow(I18n.format("patternchecker.edit.inputs"), GRID_ORIGIN_X, LABEL_Y,
                0xAAAAAA);
        this.fontRendererObj.drawStringWithShadow(I18n.format("patternchecker.edit.outputs"), OUT_X, LABEL_Y,
                0xAAAAAA);

        this.fontRendererObj.drawStringWithShadow(
                I18n.format("patternchecker.edit.total", formatCount(total(this.inputs)),
                        formatCount(total(this.outputs))),
                6, SUM_Y, 0x55FF55);
    }

    /** Sum of the amounts of every filled slot in the array. */
    private static long total(EditSlot[] slots) {
        long sum = 0;
        for (EditSlot s : slots) {
            if (s.type == null || s.count <= 0) {
                continue;
            }
            sum += s.count;
            if (sum < 0) {
                return Long.MAX_VALUE;
            }
        }
        return sum;
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000_000_000L) {
            return String.format("%.1fT", count / 1_000_000_000_000.0);
        }
        if (count >= 1_000_000_000L) {
            return String.format("%.1fG", count / 1_000_000_000.0);
        }
        if (count >= 1_000_000L) {
            return String.format("%.1fM", count / 1_000_000.0);
        }
        if (count >= 10_000L) {
            return String.format("%.0fK", count / 1_000.0);
        }
        return String.valueOf(count);
    }
}
