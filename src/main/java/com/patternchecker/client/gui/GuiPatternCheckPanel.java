package com.patternchecker.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Container;

import org.lwjgl.input.Mouse;

import net.minecraft.util.EnumChatFormatting;

import com.patternchecker.client.ClientHighlightState;
import com.patternchecker.client.ClientPanelState;
import com.patternchecker.network.PacketPanelAction;
import com.patternchecker.network.PacketPanelData;
import com.patternchecker.network.PatternCheckerNetwork;

/**
 * The pattern check panel: scan controls, issue list with selection, and
 * per-row actions (highlight / edit / extract / ignore).
 *
 * <p>Ignored patterns are hidden from the list by default; the toggle at the
 * bottom right reveals them (dimmed and tagged) so they can be un-ignored. The
 * packet always carries every row, so {@link #selected} stays an index into the
 * full row list and the row-addressed actions keep working whichever view is
 * shown.
 */
public class GuiPatternCheckPanel extends GuiContainer {

    private static final int BTN_SCAN = 0;
    private static final int BTN_SCAN_ALL = 1;
    private static final int BTN_CLEAR = 2;
    private static final int BTN_HIGHLIGHT = 3;
    private static final int BTN_EDIT = 4;
    private static final int BTN_EXTRACT = 5;
    private static final int BTN_IGNORE = 6;
    private static final int BTN_UNIGNORE = 7;
    private static final int BTN_SHOW_IGNORED = 8;

    private static final int ROW_HEIGHT = 22;
    /** Summary is two lines now: buttons end at y=40, so the first line starts at 43. */
    private static final int SUMMARY_Y = 43;
    private static final int SUMMARY_ISSUES_Y = 52;
    private static final int LIST_TOP = 62;
    /** Keeps the list clear of the "more rows" line and the two button rows. */
    private static final int LIST_BOTTOM_PAD = 48;
    /** Drawn right below the list, above the first button row (y=184). */
    private static final int MORE_Y = 174;
    private static final int BTN_ROW_1 = 44;
    private static final int BTN_ROW_2 = 22;

    private int scroll;
    /** Index into the full row list (not into the filtered view). */
    private int selected = -1;
    private boolean requestedScan;
    private boolean showIgnored;

    /** Cached filtered view: full-list indices, rebuilt when data or toggle changes. */
    private int[] view = new int[0];
    private ClientPanelState.Snapshot viewSource;
    private boolean viewSourceShowIgnored;

    public GuiPatternCheckPanel(Container container) {
        super(container);
        this.xSize = 248;
        this.ySize = 228;
    }

    @Override
    public void initGui() {
        super.initGui();
        int left = this.guiLeft;
        int top = this.guiTop;
        this.buttonList.add(new GuiButton(BTN_SCAN, left + 6, top + 22, 58, 18, I18n.format("patternchecker.gui.scan")));
        this.buttonList.add(new GuiButton(BTN_SCAN_ALL, left + 66, top + 22, 66, 18, I18n.format("patternchecker.gui.scanAll")));
        this.buttonList.add(new GuiButton(BTN_CLEAR, left + 134, top + 22, 66, 18, I18n.format("patternchecker.gui.clear")));
        this.buttonList.add(new GuiButton(BTN_HIGHLIGHT, left + 6, top + this.ySize - BTN_ROW_1, 62, 18,
                I18n.format("patternchecker.gui.highlight")));
        this.buttonList.add(new GuiButton(BTN_EDIT, left + 70, top + this.ySize - BTN_ROW_1, 62, 18,
                I18n.format("patternchecker.gui.edit")));
        this.buttonList.add(new GuiButton(BTN_EXTRACT, left + 134, top + this.ySize - BTN_ROW_1, 62, 18,
                I18n.format("patternchecker.gui.extract")));
        this.buttonList.add(new GuiButton(BTN_IGNORE, left + 6, top + this.ySize - BTN_ROW_2, 62, 18,
                I18n.format("patternchecker.gui.ignore")));
        this.buttonList.add(new GuiButton(BTN_UNIGNORE, left + 70, top + this.ySize - BTN_ROW_2, 62, 18,
                I18n.format("patternchecker.gui.unignore")));
        this.buttonList.add(new GuiButton(BTN_SHOW_IGNORED, left + 134, top + this.ySize - BTN_ROW_2, 108, 18,
                I18n.format("patternchecker.gui.showIgnored", 0)));
        if (!this.requestedScan) {
            this.requestedScan = true;
            PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.SCAN, 0));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
        case BTN_SCAN:
            PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.SCAN, 0));
            this.selected = -1;
            this.scroll = 0;
            break;
        case BTN_SCAN_ALL:
            PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.SCAN_ALL, 0));
            this.selected = -1;
            this.scroll = 0;
            break;
        case BTN_CLEAR:
            ClientHighlightState.apply(Integer.MIN_VALUE, new int[0][], 0);
            break;
        case BTN_HIGHLIGHT:
            if (this.selected >= 0) {
                PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.HIGHLIGHT, this.selected));
            }
            break;
        case BTN_EDIT:
            if (this.selected >= 0) {
                PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.EDIT, this.selected));
            }
            break;
        case BTN_EXTRACT:
            if (this.selected >= 0) {
                PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.EXTRACT, this.selected));
            }
            break;
        case BTN_IGNORE:
            if (this.selected >= 0) {
                PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.IGNORE, this.selected));
            }
            break;
        case BTN_UNIGNORE:
            if (this.selected >= 0) {
                PatternCheckerNetwork.INSTANCE.sendToServer(new PacketPanelAction(PacketPanelAction.UNIGNORE, this.selected));
            }
            break;
        case BTN_SHOW_IGNORED:
            this.showIgnored = !this.showIgnored;
            // the row list changes length; keep the selection in sync with it
            this.selected = -1;
            this.scroll = 0;
            break;
        default:
            break;
        }
    }

    /**
     * Full-list indices of the rows currently shown: everything but the ignored
     * ones, unless the player asked to see those too.
     */
    private int[] view() {
        ClientPanelState.Snapshot snap = ClientPanelState.current();
        if (this.viewSource == snap && this.viewSourceShowIgnored == this.showIgnored) {
            return this.view;
        }
        int size = 0;
        for (int i = 0; i < snap.rows.size(); i++) {
            if (this.showIgnored || !snap.rows.get(i).ignored) {
                size++;
            }
        }
        int[] built = new int[size];
        int k = 0;
        for (int i = 0; i < snap.rows.size(); i++) {
            if (this.showIgnored || !snap.rows.get(i).ignored) {
                built[k++] = i;
            }
        }
        this.view = built;
        this.viewSource = snap;
        this.viewSourceShowIgnored = this.showIgnored;
        return built;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int left = this.guiLeft;
        int top = this.guiTop;
        // panel background
        drawRect(left, top, left + this.xSize, top + this.ySize, 0xF0101014);
        drawRect(left, top, left + this.xSize, top + 1, 0xFF3A3A4A);
        drawRect(left, top + this.ySize - 1, left + this.xSize, top + this.ySize, 0xFF3A3A4A);
        drawRect(left, top, left + 1, top + this.ySize, 0xFF3A3A4A);
        drawRect(left + this.xSize - 1, top, left + this.xSize, top + this.ySize, 0xFF3A3A4A);

        // rows
        int[] rows = view();
        int rowTop = top + LIST_TOP;
        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int vi = this.scroll + i;
            if (vi >= rows.length) {
                break;
            }
            int y = rowTop + i * ROW_HEIGHT;
            if (rows[vi] == this.selected) {
                drawRect(left + 4, y - 2, left + this.xSize - 4, y + ROW_HEIGHT - 4, 0x8032A852);
            }
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        int width = this.xSize;
        ClientPanelState.Snapshot snap = ClientPanelState.current();
        int[] rows = view();

        String title = I18n.format("patternchecker.gui.title");
        this.fontRendererObj.drawStringWithShadow(title, width / 2 - this.fontRendererObj.getStringWidth(title) / 2, 8,
                0xFFFFFF);

        // Two summary lines, below the button row: the old single line was drawn at
        // y=34 and overlapped the buttons (which span y=22..40).
        String summary;
        switch (snap.status) {
        case PacketPanelData.STATUS_NO_NETWORK:
            summary = EnumChatFormatting.RED + I18n.format("patternchecker.gui.noNetwork");
            break;
        case PacketPanelData.STATUS_BOUND_UNAVAILABLE:
            summary = EnumChatFormatting.RED + I18n.format("patternchecker.gui.boundUnavailable");
            break;
        case PacketPanelData.STATUS_NO_PATTERNS:
            summary = EnumChatFormatting.YELLOW + I18n.format("patternchecker.gui.noPatterns");
            break;
        default:
            summary = I18n.format("patternchecker.gui.summaryTop", snap.totalPatterns, snap.interfacePatterns,
                    snap.storagePatterns);
            break;
        }
        this.fontRendererObj.drawStringWithShadow(
                this.fontRendererObj.trimStringToWidth(summary, width - 12), 6, SUMMARY_Y, 0x8A8A9A);

        if (snap.status == PacketPanelData.STATUS_OK) {
            String issues = snap.thirdPartyPatterns > 0
                    ? I18n.format("patternchecker.gui.summaryIssuesThird", snap.errors, snap.warnings,
                            snap.thirdPartyPatterns)
                    : I18n.format("patternchecker.gui.summaryIssues", snap.errors, snap.warnings);
            this.fontRendererObj.drawStringWithShadow(
                    this.fontRendererObj.trimStringToWidth(issues, width - 12), 6, SUMMARY_ISSUES_Y, 0x8A8A9A);
        }

        int rowTop = LIST_TOP;
        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int vi = this.scroll + i;
            if (vi >= rows.length) {
                break;
            }
            PacketPanelData.Row row = snap.rows.get(rows[vi]);
            int y = rowTop + i * ROW_HEIGHT;

            String head = row.name;
            if (row.hasLoc) {
                head = head + " (" + I18n.format(row.locKey, row.locArg) + ")";
            }
            if (row.ignored) {
                head = I18n.format("patternchecker.gui.ignoredTag") + " " + head;
            }
            int headColor = row.ignored ? 0x8A8A9A : (row.error ? 0xFF5555 : 0xFFFFFF);
            this.fontRendererObj.drawStringWithShadow(
                    this.fontRendererObj.trimStringToWidth(head, width - 12), 6, y, headColor);

            String text = I18n.format(row.issueKey, (Object[]) row.args);
            int issueColor = row.ignored ? 0x707070 : (row.error ? 0xFF7070 : 0xE8C840);
            this.fontRendererObj.drawStringWithShadow(
                    this.fontRendererObj.trimStringToWidth(text, width - 12), 14, y + 9, issueColor);
        }

        if (rows.length > this.scroll + visible) {
            this.fontRendererObj.drawStringWithShadow(
                    I18n.format("patternchecker.gui.more", rows.length - this.scroll - visible),
                    6, MORE_Y, 0x8A8A9A);
        } else if (rows.length == 0 && snap.status == PacketPanelData.STATUS_OK) {
            boolean allIgnored = !snap.rows.isEmpty();
            this.fontRendererObj.drawStringWithShadow(
                    I18n.format(allIgnored ? "patternchecker.gui.allIgnored" : "patternchecker.gui.none"),
                    6, rowTop + 4, allIgnored ? 0x8A8A9A : 0x55FF55);
        }

        // selection hint + button enable states
        PacketPanelData.Row sel = this.selected >= 0 && this.selected < snap.rows.size()
                ? snap.rows.get(this.selected) : null;
        setEnabled(BTN_HIGHLIGHT, sel != null && sel.canHighlight);
        setEnabled(BTN_EDIT, sel != null && sel.canEdit);
        setEnabled(BTN_EXTRACT, sel != null && sel.canExtract);
        setEnabled(BTN_IGNORE, sel != null && sel.canIgnore && !sel.ignored);
        setEnabled(BTN_UNIGNORE, sel != null && sel.ignored);
        setLabel(BTN_SHOW_IGNORED,
                this.showIgnored
                        ? I18n.format("patternchecker.gui.hideIgnored", snap.ignoredPatterns)
                        : I18n.format("patternchecker.gui.showIgnored", snap.ignoredPatterns));
    }

    private void setEnabled(int id, boolean enabled) {
        for (Object o : this.buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == id) {
                b.enabled = enabled;
            }
        }
    }

    private void setLabel(int id, String label) {
        for (Object o : this.buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == id) {
                b.displayString = label;
            }
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.ySize - LIST_TOP - LIST_BOTTOM_PAD) / ROW_HEIGHT);
    }

    /**
     * Tells NEI whether its item panel slot at this screen rect sits under this
     * panel, so NEI does not draw over the buttons on the right hand side.
     */
    public boolean isRegionOverGui(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        return x + w > this.guiLeft && x < this.guiLeft + this.xSize && y + h > this.guiTop
                && y < this.guiTop + this.ySize;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        int relX = mouseX - this.guiLeft;
        int relY = mouseY - this.guiTop;
        if (button == 0 && relX >= 4 && relX <= this.xSize - 4 && relY >= LIST_TOP
                && relY < LIST_TOP + visibleRows() * ROW_HEIGHT) {
            int[] rows = view();
            int vi = this.scroll + (relY - LIST_TOP) / ROW_HEIGHT;
            if (vi >= 0 && vi < rows.length) {
                this.selected = rows[vi];
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int max = Math.max(0, view().length - visibleRows());
            this.scroll = wheel > 0 ? Math.max(0, this.scroll - 1) : Math.min(max, this.scroll + 1);
        }
    }
}
