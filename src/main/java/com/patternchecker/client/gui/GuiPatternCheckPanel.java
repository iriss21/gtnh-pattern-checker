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
 * <p>Ignored and healthy patterns are hidden from the list by default; the
 * toggles at the bottom reveal them (dimmed / tagged, respectively) so they can
 * be un-ignored or picked for editing. The packet always carries every row, so
 * {@link #selected} stays an index into the full row list and the row-addressed
 * actions keep working whichever view is shown.
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
    private static final int BTN_SHOW_HEALTHY = 9;

    /** Three text lines per row. 11px line pitch: the 9px glyph plus its drop
     * shadow still clears (10px needed), a touch tighter than the old 12px. The
     * spare 5px under the text keeps consecutive rows visually apart. */
    private static final int ROW_HEIGHT = 36;
    private static final int LINE_LOCATION = 11;
    private static final int LINE_ISSUE = 22;
    /** Height of the three text lines themselves; the rest of the row is spacing. */
    private static final int ROW_TEXT_HEIGHT = LINE_ISSUE + 9;
    /** Summary is two lines now: buttons end at y=40, so the first line starts at 43. */
    private static final int SUMMARY_Y = 43;
    private static final int SUMMARY_ISSUES_Y = 52;
    private static final int LIST_TOP = 62;
    /** Keeps the list clear of the "more rows" line and the two button rows. */
    private static final int LIST_BOTTOM_PAD = 50;
    private static final int BTN_ROW_1 = 44;
    private static final int BTN_ROW_2 = 22;
    /** Scrollbar: 5px wide, 3px off the right border, spanning the row list. */
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLLBAR_PAD = 3;
    private static final int SCROLLBAR_MIN_THUMB = 20;

    private int scroll;
    /** Index into the full row list (not into the filtered view). */
    private int selected = -1;
    private boolean requestedScan;
    private boolean showIgnored;
    /** Healthy ("no issue") rows are hidden by default and can be revealed. */
    private boolean showHealthy;
    /** True while the scrollbar thumb is held down with the mouse. */
    private boolean draggingScrollbar;
    /** Vertical offset from the thumb top to the mouse while dragging. */
    private int scrollbarGrabOffset;

    /** Cached filtered view: full-list indices, rebuilt when data or toggles change. */
    private int[] view = new int[0];
    private ClientPanelState.Snapshot viewSource;
    private boolean viewSourceShowIgnored;
    private boolean viewSourceShowHealthy;

    public GuiPatternCheckPanel(Container container) {
        super(container);
        // 300x340, up from 248x238: the wider canvas keeps names/locations from
        // being truncated and the taller one fits six 38px rows. GTNH's GUI
        // auto-scale drops a scale step when the GUI stops fitting the canvas,
        // so common setups (854x480, 1280x720) still show it in full.
        this.xSize = 300;
        this.ySize = 340;
    }

    @Override
    public void initGui() {
        super.initGui();
        int left = this.guiLeft;
        int top = this.guiTop;
        this.buttonList.add(new GuiButton(BTN_SCAN, left + 6, top + 22, 70, 18, I18n.format("patternchecker.gui.scan")));
        this.buttonList.add(new GuiButton(BTN_SCAN_ALL, left + 80, top + 22, 104, 18,
                I18n.format("patternchecker.gui.scanAll")));
        this.buttonList.add(new GuiButton(BTN_CLEAR, left + 188, top + 22, 106, 18,
                I18n.format("patternchecker.gui.clear")));
        this.buttonList.add(new GuiButton(BTN_HIGHLIGHT, left + 6, top + this.ySize - BTN_ROW_1, 69, 18,
                I18n.format("patternchecker.gui.highlight")));
        this.buttonList.add(new GuiButton(BTN_EDIT, left + 79, top + this.ySize - BTN_ROW_1, 69, 18,
                I18n.format("patternchecker.gui.edit")));
        this.buttonList.add(new GuiButton(BTN_EXTRACT, left + 152, top + this.ySize - BTN_ROW_1, 69, 18,
                I18n.format("patternchecker.gui.extract")));
        this.buttonList.add(new GuiButton(BTN_IGNORE, left + 225, top + this.ySize - BTN_ROW_1, 69, 18,
                I18n.format("patternchecker.gui.ignore")));
        this.buttonList.add(new GuiButton(BTN_UNIGNORE, left + 6, top + this.ySize - BTN_ROW_2, 88, 18,
                I18n.format("patternchecker.gui.unignore")));
        this.buttonList.add(new GuiButton(BTN_SHOW_HEALTHY, left + 98, top + this.ySize - BTN_ROW_2, 96, 18,
                I18n.format("patternchecker.gui.showHealthy", 0)));
        this.buttonList.add(new GuiButton(BTN_SHOW_IGNORED, left + 198, top + this.ySize - BTN_ROW_2, 96, 18,
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
        case BTN_SHOW_HEALTHY:
            if (button.id == BTN_SHOW_IGNORED) {
                this.showIgnored = !this.showIgnored;
            } else {
                this.showHealthy = !this.showHealthy;
            }
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
     * and the healthy ones, unless the player asked to see those too.
     */
    private int[] view() {
        ClientPanelState.Snapshot snap = ClientPanelState.current();
        if (this.viewSource == snap && this.viewSourceShowIgnored == this.showIgnored
                && this.viewSourceShowHealthy == this.showHealthy) {
            return this.view;
        }
        int size = 0;
        for (int i = 0; i < snap.rows.size(); i++) {
            if (show(snap.rows.get(i))) {
                size++;
            }
        }
        int[] built = new int[size];
        int k = 0;
        for (int i = 0; i < snap.rows.size(); i++) {
            if (show(snap.rows.get(i))) {
                built[k++] = i;
            }
        }
        this.view = built;
        this.viewSource = snap;
        this.viewSourceShowIgnored = this.showIgnored;
        this.viewSourceShowHealthy = this.showHealthy;
        return built;
    }

    /** A row belongs to the current view when it is neither ignored nor healthy. */
    private boolean show(PacketPanelData.Row row) {
        return (this.showIgnored || !row.ignored) && (this.showHealthy || !row.healthy);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int left = this.guiLeft;
        int top = this.guiTop;
        // fresh data can shrink the list below the remembered scroll position
        this.scroll = Math.min(this.scroll, maxScroll());
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
                // Span the three text lines with 2px padding top and bottom; the old
                // ROW_HEIGHT-derived box stopped 3px short of the issue line's glyphs.
                drawRect(left + 4, y - 2, left + this.xSize - 10, y + ROW_TEXT_HEIGHT + 2, 0x8032A852);
            }
        }

        // scrollbar (only when the list overflows the visible area)
        int trackHeight = visible * ROW_HEIGHT;
        int thumbHeight = thumbHeight(trackHeight);
        if (thumbHeight > 0) {
            int sx = left + scrollbarLeft();
            drawRect(sx, top + LIST_TOP, sx + SCROLLBAR_WIDTH, top + LIST_TOP + trackHeight, 0xFF14141C);
            int ty = top + thumbTop(trackHeight, thumbHeight);
            drawRect(sx, ty, sx + SCROLLBAR_WIDTH, ty + thumbHeight,
                    this.draggingScrollbar ? 0xFF8080A0 : 0xFF4A4A5E);
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

            String head = row.displayName();
            if (head.isEmpty()) {
                // ghost ignore row: the pattern itself produced no data this scan
                head = I18n.format("patternchecker.gui.ghostName");
            }
            if (row.kind != null && !row.kind.isEmpty()) {
                head = head + I18n.format(row.kind);
            }
            if (row.ignored) {
                head = I18n.format("patternchecker.gui.ignoredTag") + " " + head;
            }
            int headColor = row.ignored ? 0x8A8A9A : (row.error ? 0xFF5555 : 0xFFFFFF);
            this.fontRendererObj.drawStringWithShadow(
                    this.fontRendererObj.trimStringToWidth(head, width - 20), 6, y, headColor);

            // Dimension + coordinates on their own left aligned line, so neither the
            // pattern name nor the location can push the other out of the row.
            if (row.hasLoc) {
                String loc = row.locArg == null || row.locArg.isEmpty()
                        ? row.displayDim() + " · " + I18n.format(row.locKey)
                        : row.displayDim() + " @ " + row.locArg;
                this.fontRendererObj.drawStringWithShadow(
                        this.fontRendererObj.trimStringToWidth(loc, width - 20), 6, y + LINE_LOCATION, 0x7C7C8C);
            }

            String text = I18n.format(row.issueKey, (Object[]) row.displayArgs());
            int issueColor = row.ignored ? 0x707070 : (row.error ? 0xFF7070 : (row.healthy ? 0x60C060 : 0xE8C840));
            this.fontRendererObj.drawStringWithShadow(
                    this.fontRendererObj.trimStringToWidth(text, width - 28), 14, y + LINE_ISSUE, issueColor);
        }

        int moreY = rowTop + visible * ROW_HEIGHT + 1;
        if (rows.length > this.scroll + visible) {
            this.fontRendererObj.drawStringWithShadow(
                    I18n.format("patternchecker.gui.more", rows.length - this.scroll - visible),
                    6, moreY, 0x8A8A9A);
        } else if (rows.length == 0 && snap.status == PacketPanelData.STATUS_OK) {
            // Every row was filtered out: all ignored, all healthy (hidden), or both.
            boolean hasIgnored = false;
            boolean hasHealthy = false;
            for (PacketPanelData.Row r : snap.rows) {
                if (r.ignored) {
                    hasIgnored = true;
                } else if (r.healthy) {
                    hasHealthy = true;
                }
            }
            boolean allHealthy = !hasIgnored && hasHealthy;
            String key = allHealthy ? "patternchecker.gui.allHealthy"
                    : (hasIgnored || hasHealthy) ? "patternchecker.gui.allIgnored" : "patternchecker.gui.none";
            this.fontRendererObj.drawStringWithShadow(
                    I18n.format(key, snap.healthyPatterns), 6, rowTop + 4,
                    (hasIgnored || hasHealthy) ? 0x8A8A9A : 0x55FF55);
        }

        // selection hint + button enable states
        PacketPanelData.Row sel = this.selected >= 0 && this.selected < snap.rows.size()
                ? snap.rows.get(this.selected) : null;
        setEnabled(BTN_HIGHLIGHT, sel != null && sel.canHighlight);
        setEnabled(BTN_EDIT, sel != null && sel.canEdit);
        setEnabled(BTN_EXTRACT, sel != null && sel.canExtract);
        setEnabled(BTN_IGNORE, sel != null && sel.canIgnore && !sel.ignored);
        setEnabled(BTN_UNIGNORE, sel != null && sel.ignored);
        setLabel(BTN_SHOW_HEALTHY,
                this.showHealthy
                        ? I18n.format("patternchecker.gui.hideHealthy", snap.healthyPatterns)
                        : I18n.format("patternchecker.gui.showHealthy", snap.healthyPatterns));
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

    // ------------------------------------------------------------------
    // Scrollbar
    // ------------------------------------------------------------------

    private int maxScroll() {
        return Math.max(0, view().length - visibleRows());
    }

    private int scrollbarLeft() {
        return this.xSize - SCROLLBAR_WIDTH - SCROLLBAR_PAD;
    }

    /** Thumb height in pixels, or 0 when the whole list fits without scrolling. */
    private int thumbHeight(int trackHeight) {
        int total = view().length;
        int visible = visibleRows();
        if (total <= visible) {
            return 0;
        }
        return Math.max(SCROLLBAR_MIN_THUMB, trackHeight * visible / total);
    }

    /** Thumb top in gui-local coordinates; only meaningful when thumbHeight > 0. */
    private int thumbTop(int trackHeight, int thumbHeight) {
        int scrollable = trackHeight - thumbHeight;
        if (scrollable <= 0) {
            return LIST_TOP;
        }
        return LIST_TOP + (int) ((long) this.scroll * scrollable / (view().length - visibleRows()));
    }

    /** Maps a gui-local mouse Y (with the drag offset applied) onto the scroll. */
    private void scrollTo(int relY) {
        int trackHeight = visibleRows() * ROW_HEIGHT;
        int thumbHeight = thumbHeight(trackHeight);
        int scrollable = trackHeight - thumbHeight;
        if (scrollable <= 0) {
            return;
        }
        int rel = Math.max(0, Math.min(scrollable, relY - LIST_TOP - this.scrollbarGrabOffset));
        this.scroll = (int) ((long) rel * maxScroll() / scrollable);
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
        if (button == 0 && this.maxScroll() > 0 && relX >= scrollbarLeft()
                && relX <= scrollbarLeft() + SCROLLBAR_WIDTH
                && relY >= LIST_TOP && relY < LIST_TOP + visibleRows() * ROW_HEIGHT) {
            int trackHeight = visibleRows() * ROW_HEIGHT;
            int thumbHeight = thumbHeight(trackHeight);
            int thumbTop = thumbTop(trackHeight, thumbHeight);
            if (relY < thumbTop || relY > thumbTop + thumbHeight) {
                // click on the empty track: center the thumb under the mouse
                this.scrollbarGrabOffset = thumbHeight / 2;
                scrollTo(relY);
            } else {
                this.scrollbarGrabOffset = relY - thumbTop;
            }
            this.draggingScrollbar = true;
            return;
        }
        if (button == 0 && relX >= 4 && relX <= this.xSize - 10 && relY >= LIST_TOP
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
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (this.draggingScrollbar && button == 0) {
            scrollTo(mouseY - this.guiTop);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        if (this.draggingScrollbar && which == 0) {
            this.draggingScrollbar = false;
            return;
        }
        super.mouseMovedOrUp(mouseX, mouseY, which);
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
