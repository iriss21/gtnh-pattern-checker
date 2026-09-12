package com.patternchecker.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.common.collect.ImmutableList;

import appeng.api.AEApi;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.api.util.DimensionalCoord;
import appeng.helpers.IInterfaceHost;

import com.patternchecker.item.ItemPatternCheckerTool;
import com.patternchecker.network.PacketPanelData;
import com.patternchecker.network.PatternCheckerNetwork;

/**
 * The scan engine, ported from the 1.21 PatternChecker. Scans encoded patterns
 * reachable through an ME network and reports issues as chat lines (command
 * flow) and as structured rows (GUI panel flow):
 *
 * - patterns that cannot be decoded (blank, corrupted, or crafting recipe gone),
 * - patterns marked InvalidPattern by AE2,
 * - patterns with non-positive quantities,
 * - processing patterns that loop into themselves,
 * - inputs that are neither stocked nor craftable on the network,
 * - interface patterns that are not registered in the crafting cache,
 * - several patterns with the same encoded inputs and outputs.
 */
public final class PatternCheckService {

    /** Fully qualified names of pattern items that carry a non-AE2 NBT layout. */
    private static final String[] THIRD_PARTY_PATTERN_ITEMS = {
            "com.myname.wildcardpattern.item.ItemWildcardPattern" };

    /**
     * Upper bound on the rows pushed to the panel (and kept for row-addressed
     * actions). Rows are sent in pages of {@link #ROWS_PER_PAGE} to respect the
     * 1.7.10 32 KiB packet payload limit; the cap itself only bounds the panel
     * list (and protocol traffic) for pathological bases.
     */
    private static final int MAX_PANEL_ROWS = 600;

    /** Rows per panel-data packet; 120 rows stay well under the 32 KiB payload limit. */
    private static final int ROWS_PER_PAGE = 120;

    /** Issue key of the "nothing wrong" rows listed for interface patterns. */
    public static final String ISSUE_OK = "patternchecker.issue.ok";

    /** Issue key of placeholder rows for ignored fingerprints outside the scan. */
    public static final String ISSUE_GHOST = "patternchecker.issue.ghost";

    private PatternCheckService() {
    }

    // ------------------------------------------------------------------
    // Chat flow entry points (command)
    // ------------------------------------------------------------------

    /** Tool right-click / command: scan bound network, else the nearest one. */
    public static void scanForPlayer(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        ItemStack held = player.getCurrentEquippedItem();
        int[] bound = (held != null && held.getItem() instanceof ItemPatternCheckerTool) ? Binding.get(held) : null;

        if (bound != null) {
            WorldServer world = server.worldServerForDimension(bound[0]);
            IGrid grid = null;
            if (world != null && world.blockExists(bound[1], bound[2], bound[3])) {
                grid = gridAt(world.getTileEntity(bound[1], bound[2], bound[3]));
            }
            if (grid == null) {
                say(player, "patternchecker.bound.unavailable");
                return;
            }
            say(player, "patternchecker.bound.scanning");
            ScanSession session = new ScanSession(player, bound[0]);
            scanGridInto(grid, world, session, 0);
            report(player, session);
            return;
        }

        say(player, "patternchecker.msg.fallback");
        WorldServer world = server.worldServerForDimension(player.dimension);
        IGrid grid = nearestGrid(world, player.posX, player.posY, player.posZ);
        if (grid == null) {
            say(player, "patternchecker.msg.notarget");
            return;
        }
        ScanSession session = new ScanSession(player, player.dimension);
        scanGridInto(grid, world, session, 0);
        report(player, session);
    }

    /** /patterncheck scan all — every loaded dimension, not just the player's. */
    public static void scanAll(EntityPlayerMP player) {
        say(player, "patternchecker.msg.scanningAll");

        Set<IGrid> seen = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
        ScanSession session = new ScanSession(player, player.dimension);
        int index = 0;
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Object o : world.loadedTileEntityList) {
                if (!(o instanceof TileEntity)) {
                    continue;
                }
                IGrid grid = gridAt((TileEntity) o);
                if (grid == null || !seen.add(grid)) {
                    continue;
                }
                scanGridInto(grid, world, session, ++index);
            }
        }
        if (index == 0) {
            say(player, "patternchecker.msg.none");
            return;
        }
        report(player, session);
    }

    // ------------------------------------------------------------------
    // GUI panel flow
    // ------------------------------------------------------------------

    /** Panel 扫描 / 扫描全部 buttons: scan and push structured rows to the client. */
    public static void scanForPanel(EntityPlayerMP player, boolean all) {
        MinecraftServer server = MinecraftServer.getServer();

        if (all) {
            ScanSession session = new ScanSession(player, player.dimension);
            Set<IGrid> seen = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
            int index = 0;
            for (WorldServer world : DimensionManager.getWorlds()) {
                if (world == null) {
                    continue;
                }
                for (Object o : world.loadedTileEntityList) {
                    if (!(o instanceof TileEntity)) {
                        continue;
                    }
                    IGrid grid = gridAt((TileEntity) o);
                    if (grid == null || !seen.add(grid)) {
                        continue;
                    }
                    scanGridInto(grid, world, session, ++index);
                }
            }
            finishPanel(player, session, index == 0 ? PacketPanelData.STATUS_NO_NETWORK : PacketPanelData.STATUS_OK, true);
            return;
        }

        ItemStack held = player.getCurrentEquippedItem();
        int[] bound = (held != null && held.getItem() instanceof ItemPatternCheckerTool) ? Binding.get(held) : null;
        if (bound != null) {
            WorldServer world = server.worldServerForDimension(bound[0]);
            IGrid grid = null;
            if (world != null && world.blockExists(bound[1], bound[2], bound[3])) {
                grid = gridAt(world.getTileEntity(bound[1], bound[2], bound[3]));
            }
            if (grid == null) {
                sendStatus(player, PacketPanelData.STATUS_BOUND_UNAVAILABLE);
                return;
            }
            ScanSession session = new ScanSession(player, bound[0]);
            scanGridInto(grid, world, session, 0);
            finishPanel(player, session, PacketPanelData.STATUS_OK, false);
            return;
        }

        WorldServer world = server.worldServerForDimension(player.dimension);
        IGrid grid = nearestGrid(world, player.posX, player.posY, player.posZ);
        if (grid == null) {
            sendStatus(player, PacketPanelData.STATUS_NO_NETWORK);
            return;
        }
        ScanSession session = new ScanSession(player, player.dimension);
        scanGridInto(grid, world, session, 0);
        finishPanel(player, session, PacketPanelData.STATUS_OK, false);
    }

    private static void finishPanel(EntityPlayerMP player, ScanSession s, int status, boolean all) {
        if (status == PacketPanelData.STATUS_OK && s.totalPatterns == 0) {
            status = PacketPanelData.STATUS_NO_PATTERNS;
        }
        // Errors first, then warnings, then ignored rows, then the healthy ones
        // (which include the crafting patterns stored in ME cells) - before the
        // 150 row cap is applied, so a big base never pushes real problems out of
        // the list. Ignored rows rank above healthy ones because they are how the
        // panel reaches an ignored pattern again ("show ignored"); the healthy
        // rows are the most expendable since the panel hides them by default.
        // TimSort is stable, so same-severity rows keep their scan order.
        List<PanelRow> ordered = new ArrayList<>(s.rows);
        Collections.sort(ordered, new Comparator<PanelRow>() {
            @Override
            public int compare(PanelRow a, PanelRow b) {
                return severity(a) - severity(b);
            }
        });

        int cap = Math.min(ordered.size(), MAX_PANEL_ROWS);
        List<PanelRow> stored = new ArrayList<>(ordered.subList(0, cap));
        // Ghost ignores: fingerprints still on the ignore list for which this scan
        // produced no row. Typical case: the pattern was extracted (or moved out)
        // while ignored, or re-encoded - its content fingerprint changed - or it
        // lives outside the scanned network. Without a placeholder row it would be
        // invisible and impossible to un-ignore from the panel. Appended past the
        // row cap; when the pattern is reachable again ("scan all") it resolves
        // back into a real row.
        int ghostRows = appendGhostRows(stored, s.ignored);

        PanelStore.put(player, stored, all);

        // Toggle button counts are row counts over the full scan (not the capped
        // list), so each number matches how many rows appear when its toggle is
        // turned on. An ignored healthy row counts towards "ignored" only: once
        // ignored, a pattern is governed by that toggle, not by "show healthy".
        int ignoredRows = 0;
        int healthyRows = 0;
        for (PanelRow row : s.rows) {
            if (row.data.ignored) {
                ignoredRows++;
            } else if (ISSUE_OK.equals(row.data.issueKey)) {
                healthyRows++;
            }
        }

        // Rows go out in pages: one payload must stay under the 1.7.10 32 KiB
        // packet limit, and a few hundred rows of names/coordinates/issue text do
        // not. The client accumulates the pages in ClientPanelState.
        int totalPages = Math.max(1, (stored.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        for (int p = 0; p < totalPages; p++) {
            PacketPanelData packet = new PacketPanelData();
            packet.status = status;
            packet.page = p;
            packet.totalPages = totalPages;
            packet.totalPatterns = s.totalPatterns;
            packet.interfacePatterns = s.interfacePatterns;
            packet.storagePatterns = s.storagePatterns;
            packet.errors = s.errors;
            packet.warnings = s.warnings;
            packet.thirdPartyPatterns = s.thirdPartyPatterns;
            packet.ignoredPatterns = ignoredRows + ghostRows;
            packet.healthyPatterns = healthyRows;
            int from = p * ROWS_PER_PAGE;
            int to = Math.min(stored.size(), from + ROWS_PER_PAGE);
            for (PanelRow row : stored.subList(from, to)) {
                packet.rows.add(toPacketRow(row));
            }
            PatternCheckerNetwork.sendPanelData(player, packet);
        }
    }

    /** 0 = error, 1 = warning, 2 = ignored, 3 = healthy. */
    private static int severity(PanelRow row) {
        if (row.data.ignored) {
            return 2;
        }
        if (ISSUE_OK.equals(row.data.issueKey)) {
            return 3;
        }
        return row.data.error ? 0 : 1;
    }

    /**
     * Appends placeholder rows for ignored fingerprints that the rows of this
     * scan do not cover - a pattern that was extracted, moved, re-encoded or
     * scanned in no network would otherwise vanish from "show ignored" and be
     * impossible to un-ignore from the panel.
     *
     * @return how many placeholder rows were appended
     */
    public static int appendGhostRows(List<PanelRow> rows, Set<String> ignoredKeys) {
        Set<String> seenKeys = new HashSet<>();
        for (PanelRow row : rows) {
            if (row.data.key != null) {
                seenKeys.add(row.data.key);
            }
        }
        int ghosts = 0;
        for (String key : ignoredKeys) {
            if (seenKeys.contains(key)) {
                continue;
            }
            rows.add(new PanelRow(new IssueData(false, "", "", null, "", ISSUE_GHOST, new String[0],
                    null, 0, null, key, true), null));
            ghosts++;
        }
        return ghosts;
    }

    private static PacketPanelData.Row toPacketRow(PanelRow row) {
        PacketPanelData.Row r = new PacketPanelData.Row();
        IssueData d = row.data;
        r.error = d.error;
        r.name = d.name;
        // lang key of the kind suffix ("（合成样板）" / "（处理样板）"); "" when unknown
        r.kind = d.kind == null ? "" : d.kind;
        r.hasLoc = d.locKey != null;
        r.locKey = d.locKey == null ? "" : d.locKey;
        r.locArg = d.locArg == null ? "" : d.locArg;
        r.issueKey = d.issueKey;
        r.args = d.args;
        r.canHighlight = d.pos != null;
        // Both processing and crafting patterns are editable: the 3x3 grid maps
        // onto a crafting pattern's slot layout as-is, and the editor keeps the
        // original "crafting" flag when re-encoding.
        r.canEdit = d.edit != null;
        r.canExtract = d.edit != null;
        r.canIgnore = d.key != null;
        r.ignored = d.ignored;
        // "ok" rows are the healthy patterns; the panel hides them by default.
        r.healthy = ISSUE_OK.equals(d.issueKey);
        if (d.locKey != null) {
            // patterns in interface slots and in ME storage both carry a location line
            r.dim = d.dim;
            r.dimName = DimensionNames.serverName(d.dim);
        }
        return r;
    }

    private static void sendStatus(EntityPlayerMP player, int status) {
        PanelStore.put(player, new ArrayList<PanelRow>(), false);
        PacketPanelData packet = new PacketPanelData();
        packet.status = status;
        PatternCheckerNetwork.sendPanelData(player, packet);
    }

    // ------------------------------------------------------------------
    // Shared scanning
    // ------------------------------------------------------------------

    private static IGrid gridAt(TileEntity te) {
        if (!(te instanceof IGridHost)) {
            return null;
        }
        IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    private static IGrid nearestGrid(WorldServer world, double x, double y, double z) {
        IGrid best = null;
        double bestDist = 64.0 * 64.0;
        for (Object o : world.loadedTileEntityList) {
            if (!(o instanceof TileEntity)) {
                continue;
            }
            TileEntity te = (TileEntity) o;
            double dx = te.xCoord + 0.5 - x;
            double dy = te.yCoord + 0.5 - y;
            double dz = te.zCoord + 0.5 - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d >= bestDist) {
                continue;
            }
            IGrid g = gridAt(te);
            if (g != null) {
                best = g;
                bestDist = d;
            }
        }
        return best;
    }

    private static void scanGridInto(IGrid grid, World world, ScanSession s, int gridIndex) {
        final ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        final IStorageGrid storage = grid.getCache(IStorageGrid.class);

        int beforeTotal = s.totalPatterns;
        int beforeInterface = s.interfacePatterns;
        int beforeStorage = s.storagePatterns;
        int beforeErrors = s.errors;
        int beforeWarnings = s.warnings;

        // Patterns registered in the crafting cache + craftable output types.
        Set<IAEItemStack> registered = new HashSet<>();
        Set<IAEItemStack> craftableOutputs = new HashSet<>();
        if (crafting != null) {
            for (ImmutableList<ICraftingPatternDetails> list : registeredPatterns(crafting)) {
                for (ICraftingPatternDetails d : list) {
                    ItemStack p = d.getPattern();
                    if (p != null) {
                        registered.add(typeKey(p));
                    }
                    IAEItemStack[] co = d.getCondensedOutputs();
                    if (co != null) {
                        for (IAEItemStack o : co) {
                            if (o != null) {
                                craftableOutputs.add(o);
                            }
                        }
                    }
                }
            }
        }

        // Item types currently stocked in the network.
        Set<IAEItemStack> stocked = new HashSet<>();
        IItemList<IAEItemStack> storageList = storageList(storage);
        if (storageList != null) {
            for (IAEItemStack is : storageList) {
                stocked.add(is);
            }
        }

        // Patterns with the same encoded inputs/outputs (duplicates).
        Map<DupKey, List<ScannedRef>> duplicateGroups = new HashMap<>();

        // Interfaces: pattern slots hold the active patterns.
        for (IGridNode node : grid.getNodes()) {
            IGridHost machine = node.getMachine();
            if (!(machine instanceof IInterfaceHost)) {
                continue;
            }
            IInterfaceHost host = (IInterfaceHost) machine;
            DimensionalCoord where = host.getLocation();
            if (where == null) {
                continue;
            }
            IInventory inv = host.getInterfaceDuality().getPatterns();
            if (inv == null) {
                continue;
            }
            int[] pos = { where.x, where.y, where.z };
            // the interface's own dimension, not the dimension being scanned from
            int dim = where.getDimension();
            for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (stack == null) {
                    continue;
                }
                checkOne(s, world, stack, true, dim, slot, pos,
                        "patternchecker.location.provider", where.x + ", " + where.y + ", " + where.z,
                        registered, stocked, craftableOutputs, duplicateGroups);
            }
        }

        // ME storage: encoded patterns stored in cells/drives.
        if (storageList != null) {
            List<IAEItemStack> snapshot = new ArrayList<>();
            for (IAEItemStack is : storageList) {
                snapshot.add(is);
            }
            for (IAEItemStack is : snapshot) {
                ItemStack stack = is.getItemStack();
                if (stack == null || !(stack.getItem() instanceof ICraftingPatternItem)) {
                    continue;
                }
                if (isThirdPartyPattern(stack)) {
                    s.totalPatterns++;
                    s.storagePatterns++;
                    s.thirdPartyPatterns++;
                    continue;
                }
                NBTTagCompound tag = stack.getTagCompound();
                if (tag == null || !tag.hasKey("in")) {
                    continue;
                }
                checkOne(s, world, stack, false, world.provider.dimensionId, -1, null,
                        "patternchecker.location.storage", "",
                        registered, stocked, craftableOutputs, duplicateGroups);
            }
        }

        // Duplicates.
        for (Map.Entry<DupKey, List<ScannedRef>> e : duplicateGroups.entrySet()) {
            List<ScannedRef> group = e.getValue();
            if (group.size() < 2) {
                continue;
            }
            int[] pos = group.get(0).pos;
            addBareIssue(s, false, "patternchecker.issue.duplicate",
                    new String[] { String.valueOf(group.size()) }, pos, e.getKey().signature());
        }

        int gridTotal = s.totalPatterns - beforeTotal;
        IChatComponent header = gridIndex > 0
                ? headerLine("patternchecker.msg.networkHeader", gridIndex, gridTotal,
                        s.interfacePatterns - beforeInterface, s.storagePatterns - beforeStorage,
                        s.errors - beforeErrors, s.warnings - beforeWarnings)
                : headerLine("patternchecker.msg.header", gridTotal,
                        s.interfacePatterns - beforeInterface, s.storagePatterns - beforeStorage,
                        s.errors - beforeErrors, s.warnings - beforeWarnings);
        s.lines.add(gridIndex > 0 ? s.lines.size() : 0, header);
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ImmutableList<ICraftingPatternDetails>> registeredPatterns(ICraftingGrid crafting) {
        try {
            return crafting.getCraftingMultiPatterns().values();
        } catch (NoSuchMethodError | AbstractMethodError older) {
            // Older GTNH AE2 without multi-pattern support.
            return crafting.getCraftingPatterns().values();
        }
    }

    private static IItemList<IAEItemStack> storageList(IStorageGrid storage) {
        if (storage == null) {
            return null;
        }
        try {
            IMEMonitor<IAEItemStack> inv = storage.getItemInventory();
            return inv == null ? null : inv.getStorageList();
        } catch (Throwable t) {
            return null;
        }
    }

    private static TileEntity blockEntityOf(IGridHost machine) {
        return machine instanceof TileEntity ? (TileEntity) machine : null;
    }

    // ------------------------------------------------------------------
    // Single pattern check
    // ------------------------------------------------------------------

    private static final class ScannedRef {

        final DupKey key;
        final int[] pos;

        ScannedRef(DupKey key, int[] pos) {
            this.key = key;
            this.pos = pos;
        }
    }

    /** Checks one pattern stack; adds chat lines and structured rows to the session. */
    private static void checkOne(ScanSession s, World world, ItemStack stack, boolean fromInterface, int dim,
            int slot, int[] pos, String locKey, String locArg, Set<IAEItemStack> registered,
            Set<IAEItemStack> stocked, Set<IAEItemStack> craftableOutputs,
            Map<DupKey, List<ScannedRef>> duplicateGroups) {

        Item item = stack.getItem();
        NBTTagCompound tag = stack.getTagCompound();

        if (isBlankPattern(item)) {
            if (fromInterface) {
                countPattern(s, fromInterface);
                addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, null,
                        "patternchecker.issue.blankPattern", new String[0]);
            }
            return;
        }
        if (!(item instanceof ICraftingPatternItem)) {
            return;
        }
        if (tag == null) {
            // an encoded-pattern item with no NBT at all: nothing can read it
            if (fromInterface) {
                countPattern(s, fromInterface);
                addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, null,
                        "patternchecker.issue.blankPattern", new String[0]);
            }
            return;
        }

        // Pattern items from other mods (the wildcardpattern template being the one
        // that shows up in practice) keep their own NBT schema instead of AE2's
        // in/out lists and are expanded by the owning mod. They are counted, but
        // reporting them as blank - or checking them slot by slot - would be wrong.
        if (isThirdPartyPattern(stack)) {
            countPattern(s, fromInterface);
            s.thirdPartyPatterns++;
            return;
        }

        countPattern(s, fromInterface);

        // used at the end of the checks: no new row means this pattern is healthy
        int rowsBefore = s.rows.size();

        if (tag.getBoolean("InvalidPattern")) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, null,
                    "patternchecker.issue.invalid", new String[0]);
            return;
        }

        ICraftingPatternDetails details;
        try {
            details = ((ICraftingPatternItem) item).getPatternForItem(stack.copy(), world);
        } catch (Throwable t) {
            // For crafting patterns this is almost always a recipe that no
            // longer exists; for processing patterns it means corrupted NBT.
            boolean wasCrafting = tag.getBoolean("crafting");
            String why = t.getMessage() != null ? t.getMessage() : t.toString();
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, null,
                    wasCrafting ? "patternchecker.issue.recipeChanged" : "patternchecker.issue.undecodable",
                    new String[] { why });
            return;
        }
        if (details == null) {
            if (!tag.hasKey("in")) {
                // no standard layout and the item cannot decode it either
                addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, null,
                        "patternchecker.issue.blankPattern", new String[0]);
                return;
            }
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, null,
                    "patternchecker.issue.undecodable", new String[] { "null" });
            return;
        }

        boolean processing = !details.isCraftable();
        IAEItemStack[] condensedIn = details.getCondensedInputs();
        IAEItemStack[] condensedOut = details.getCondensedOutputs();

        boolean badOutput = false;
        for (IAEItemStack o : condensedOut) {
            if (o == null || o.getStackSize() <= 0) {
                badOutput = true;
            }
        }
        if (badOutput) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, details,
                    "patternchecker.issue.processing.zeroOutput", new String[0]);
        }

        boolean badInput = false;
        for (IAEItemStack i : condensedIn) {
            if (i == null || i.getStackSize() <= 0) {
                badInput = true;
            }
        }
        if (badInput) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, details,
                    processing ? "patternchecker.issue.processing.zeroInput" : "patternchecker.issue.input.empty",
                    new String[0]);
        }

        if (processing && condensedIn.length == 1 && condensedOut.length == 1
                && condensedOut[0].isSameType(condensedIn[0])
                && condensedOut[0].getStackSize() == condensedIn[0].getStackSize()) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, details,
                    "patternchecker.issue.processing.selfLoop", new String[0]);
        }

        // Input availability: stocked in ME storage or craftable on the network.
        List<String> missing = new ArrayList<>();
        for (IAEItemStack i : condensedIn) {
            if (i == null) {
                continue;
            }
            if (!stocked.contains(i) && !craftableOutputs.contains(i)) {
                missing.add(ItemName.encode(i.getItemStack()));
            }
        }
        if (!missing.isEmpty()) {
            addRowIssue(s, stack, locKey, locArg, pos, dim, slot, false, details,
                    "patternchecker.issue.input.missing", new String[] { ItemName.join(missing) });
        }

        // Registered in the crafting cache?
        if (fromInterface && !registered.contains(typeKey(stack))) {
            addRowIssue(s, stack, locKey, locArg, pos, dim, slot, false, details,
                    "patternchecker.issue.unregistered", new String[0]);
        }

        // Any pattern with nothing to report still gets a row, including the ones
        // sitting in ME storage: the panel is also how a pattern is found and
        // picked, and a healthy crafting pattern would otherwise never show up
        // (its item is named "编码样板" either way, only its output identifies it).
        if (s.rows.size() == rowsBefore) {
            addOkRow(s, stack, locKey, locArg, pos, dim, slot, details);
        }

        DupKey key = new DupKey(processing, condensedIn, condensedOut);
        List<ScannedRef> group = duplicateGroups.get(key);
        if (group == null) {
            group = new ArrayList<>();
            duplicateGroups.put(key, group);
        }
        group.add(new ScannedRef(key, pos));
    }

    /**
     * Row label: the pattern's <em>output</em>, which is what a player recognises
     * the pattern by (every encoded pattern is otherwise just named "编码样板").
     * Falls back to the pattern item itself when it cannot be decoded.
     */
    private static String rowName(ItemStack stack, ICraftingPatternDetails details) {
        if (details != null) {
            IAEItemStack out = firstStack(details.getCondensedOutputs());
            if (out != null && out.getItemStack() != null) {
                return ItemName.encode(out.getItemStack());
            }
        }
        return ItemName.encode(stack);
    }

    /** Lang key of the kind suffix shown after the name. */
    private static String rowKind(ICraftingPatternDetails details) {
        if (details == null) {
            return "";
        }
        return details.isCraftable() ? "patternchecker.pattern.crafting" : "patternchecker.pattern.processing";
    }

    private static IAEItemStack firstStack(IAEItemStack[] stacks) {
        if (stacks == null) {
            return null;
        }
        for (IAEItemStack s : stacks) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /**
     * A pattern with no issue at all. Not counted, not printed to chat - only the
     * panel lists it, so it can be selected (and edited) like any other pattern.
     * Also how a healthy pattern stored in ME cells becomes visible at all.
     *
     * <p>An ignored pattern still gets its row (flagged as ignored), so the panel
     * can list it under "show ignored" and the player can un-ignore it again;
     * dropping the row entirely would leave no path back to the pattern.
     */
    private static void addOkRow(ScanSession s, ItemStack stack, String locKey, String locArg, int[] pos,
            int dim, int slot, ICraftingPatternDetails details) {
        String key = PatternKey.of(stack);
        boolean ignored = s.isIgnored(key);
        boolean processing = details == null || !details.isCraftable();
        IssueData.EditTarget edit = editTarget(dim, slot, pos, processing);
        s.rows.add(new PanelRow(
                new IssueData(false, rowName(stack, details), rowKind(details), locKey, locArg, ISSUE_OK,
                        new String[0], pos, dim, edit, key, ignored),
                edit != null ? stack.copy() : null));
    }

    private static void countPattern(ScanSession s, boolean fromInterface) {
        s.totalPatterns++;
        if (fromInterface) {
            s.interfacePatterns++;
        } else {
            s.storagePatterns++;
        }
    }

    private static boolean isBlankPattern(Item item) {
        Item blank = AEApi.instance().definitions().materials().blankPattern().maybeItem().orNull();
        return blank != null && item == blank;
    }

    /**
     * Pattern items that ship their own NBT schema instead of AE2's {@code in}/{@code out}
     * lists, so neither the "is it encoded" test nor the per-slot checks apply.
     *
     * <p>Concretely this is {@code wildcardpattern}'s configurable template, which the
     * mod expands into real patterns at push time. Detected by its NBT markers first
     * (no compile-time dependency needed) and by class name as a fallback; the item is
     * only ever read, never written, so an unknown item simply skips validation.
     */
    private static boolean isThirdPartyPattern(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && (tag.hasKey("WildcardPattern") || tag.hasKey("WildcardInputComponents")
                || tag.hasKey("WildcardOutputComponents"))) {
            return true;
        }
        Item item = stack.getItem();
        if (item == null) {
            return false;
        }
        for (Class<?> c = item.getClass(); c != null && c != Item.class; c = c.getSuperclass()) {
            for (String known : THIRD_PARTY_PATTERN_ITEMS) {
                if (known.equals(c.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IAEItemStack typeKey(ItemStack stack) {
        ItemStack copy = stack.copy();
        if (copy.getTagCompound() != null) {
            copy.getTagCompound().removeTag("author");
        }
        return AEApi.instance().storage().createItemStack(copy);
    }

    // ------------------------------------------------------------------
    // Issue rows (chat + panel)
    // ------------------------------------------------------------------

    private static IssueData.EditTarget editTarget(int dim, int slot, int[] pos, boolean processing) {
        if (slot < 0 || pos == null) {
            return null;
        }
        return new IssueData.EditTarget(dim, pos[0], pos[1], pos[2], slot, processing);
    }

    /**
     * Pattern-level issue: chat gets a name/location header + issue line; panel gets one row.
     *
     * <p>The row is always created - the panel needs it to be able to show an
     * ignored pattern again - but an ignored pattern's issue is neither counted
     * nor printed to chat.
     */
    private static void addPatternIssue(ScanSession s, ItemStack stack, String locKey, String locArg, int[] pos,
            int dim, int slot, boolean error, ICraftingPatternDetails details, String issueKey, String[] args) {
        String key = PatternKey.of(stack);
        boolean ignored = s.isIgnored(key);
        boolean processing = details == null || !details.isCraftable();
        IssueData.EditTarget edit = editTarget(dim, slot, pos, processing);
        s.rows.add(new PanelRow(
                new IssueData(error, rowName(stack, details), rowKind(details), locKey, locArg, issueKey, args, pos,
                        dim, edit, key, ignored),
                edit != null ? stack.copy() : null));
        if (countIssue(s, error, ignored)) {
            return;
        }

        IChatComponent location = new ChatComponentTranslation(locKey, locArg);
        ChatComponentText name = new ChatComponentText("▪ " + stack.getDisplayName() + " (");
        name.getChatStyle().setColor(EnumChatFormatting.WHITE);
        ChatComponentText close = new ChatComponentText(")");
        IChatComponent head = new ChatComponentText("")
                .appendSibling(name)
                .appendSibling(location)
                .appendSibling(close);
        s.lines.add(head);
        s.lines.add(chatIssueLine(error,
                new ChatComponentTranslation(issueKey, (Object[]) ItemName.plainAll(args)), pos, s));
    }

    /** Sub-issue of a pattern: chat gets only the issue line; panel gets a full row. */
    private static void addRowIssue(ScanSession s, ItemStack stack, String locKey, String locArg, int[] pos,
            int dim, int slot, boolean error, ICraftingPatternDetails details, String issueKey, String[] args) {
        String key = PatternKey.of(stack);
        boolean ignored = s.isIgnored(key);
        boolean processing = details == null || !details.isCraftable();
        IssueData.EditTarget edit = editTarget(dim, slot, pos, processing);
        s.rows.add(new PanelRow(
                new IssueData(error, rowName(stack, details), rowKind(details), locKey, locArg, issueKey, args, pos,
                        dim, edit, key, ignored),
                edit != null ? stack.copy() : null));
        if (countIssue(s, error, ignored)) {
            return;
        }

        s.lines.add(chatIssueLine(error,
                new ChatComponentTranslation(issueKey, (Object[]) ItemName.plainAll(args)), pos, s));
    }

    /** Group issue (duplicates): no name/location context. */
    private static void addBareIssue(ScanSession s, boolean error, String issueKey, String[] args, int[] pos,
            String key) {
        boolean ignored = s.isIgnored(key);
        s.rows.add(new PanelRow(
                new IssueData(error, "", "", null, "", issueKey, args, pos, 0, null, key, ignored), null));
        if (countIssue(s, error, ignored)) {
            return;
        }

        ChatComponentTranslation prefix = new ChatComponentTranslation(
                error ? "patternchecker.chat.error" : "patternchecker.chat.warning");
        prefix.getChatStyle().setColor(error ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW);
        ChatComponentText line = new ChatComponentText("");
        line.appendSibling(prefix).appendText(" ")
                .appendSibling(new ChatComponentTranslation(issueKey, (Object[]) args));
        if (pos != null) {
            appendHighlight(line, pos, s);
        }
        s.lines.add(line);
    }

    /**
     * Counts an issue, or records it as suppressed when its pattern is ignored.
     *
     * @return true when the caller must skip the chat output for this issue.
     */
    private static boolean countIssue(ScanSession s, boolean error, boolean ignored) {
        if (ignored) {
            s.ignoredIssues++;
            return true;
        }
        if (error) {
            s.errors++;
        } else {
            s.warnings++;
        }
        return false;
    }

    private static IChatComponent chatIssueLine(boolean error, IChatComponent message, int[] pos, ScanSession s) {
        ChatComponentTranslation prefix = new ChatComponentTranslation(
                error ? "patternchecker.chat.error" : "patternchecker.chat.warning");
        prefix.getChatStyle().setColor(error ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW);
        ChatComponentText line = new ChatComponentText("");
        line.appendSibling(prefix).appendText(" ").appendSibling(message);
        if (pos != null) {
            appendHighlight(line, pos, s);
        }
        return line;
    }

    private static void appendHighlight(ChatComponentText line, int[] pos, ScanSession s) {
        int index = s.highlightPositions.size();
        s.highlightPositions.add(pos);
        ChatComponentTranslation hl = new ChatComponentTranslation("patternchecker.chat.highlight");
        hl.getChatStyle()
                .setColor(EnumChatFormatting.GREEN)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/patterncheck highlight " + index))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentTranslation("patternchecker.chat.highlight.hover")));
        line.appendText(" ").appendSibling(hl);
    }

    // ------------------------------------------------------------------
    // Duplicate signature
    // ------------------------------------------------------------------

    private static final class DupKey {

        final boolean processing;
        final List<SizeKey> inputs;
        final List<SizeKey> outputs;

        DupKey(boolean processing, IAEItemStack[] in, IAEItemStack[] out) {
            this.processing = processing;
            this.inputs = sortedSizeKeys(in);
            this.outputs = sortedSizeKeys(out);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DupKey)) {
                return false;
            }
            DupKey k = (DupKey) o;
            return k.processing == this.processing && k.inputs.equals(this.inputs) && k.outputs.equals(this.outputs);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * this.inputs.hashCode() + this.outputs.hashCode()) + (this.processing ? 1 : 0);
        }

        /**
         * Fingerprint of the whole group, used as the ignore key of the duplicate
         * warning so that a known duplicate set can be dismissed like any other
         * pattern issue.
         */
        String signature() {
            StringBuilder sb = new StringBuilder("dup:");
            sb.append(this.processing ? 'p' : 'c');
            for (SizeKey k : this.inputs) {
                sb.append('|').append(k.signature());
            }
            sb.append("=>");
            for (SizeKey k : this.outputs) {
                sb.append('|').append(k.signature());
            }
            return sb.toString();
        }

        private static List<SizeKey> sortedSizeKeys(IAEItemStack[] stacks) {
            List<SizeKey> keys = new ArrayList<>();
            if (stacks != null) {
                for (IAEItemStack st : stacks) {
                    if (st != null) {
                        keys.add(new SizeKey(st));
                    }
                }
            }
            Collections.sort(keys, new Comparator<SizeKey>() {

                @Override
                public int compare(SizeKey a, SizeKey b) {
                    return a.compareTo(b);
                }
            });
            return keys;
        }
    }

    private static final class SizeKey implements Comparable<SizeKey> {

        final IAEItemStack stack;
        final String name;
        final int damage;
        final long size;

        SizeKey(IAEItemStack stack) {
            this.stack = stack;
            ItemStack is = stack.getItemStack();
            String n = null;
            try {
                n = (String) Item.itemRegistry.getNameForObject(is.getItem());
            } catch (Throwable ignore) {
                // fall through with the unlocalized name
            }
            this.name = n != null ? n : is.getUnlocalizedName();
            this.damage = is.getItemDamage();
            this.size = stack.getStackSize();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SizeKey)) {
                return false;
            }
            SizeKey k = (SizeKey) o;
            return k.size == this.size && k.damage == this.damage && k.name.equals(this.name)
                    && k.stack.isSameType(this.stack);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * this.name.hashCode() + this.damage) + (int) (this.size ^ (this.size >>> 32));
        }

        @Override
        public int compareTo(SizeKey o) {
            int c = this.name.compareTo(o.name);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(this.damage, o.damage);
            if (c != 0) {
                return c;
            }
            return Long.compare(this.size, o.size);
        }

        String signature() {
            return (this.name == null ? "?" : this.name) + "@" + this.damage + "x" + this.size + ":"
                    + PatternKey.of(this.stack.getItemStack());
        }
    }

    // ------------------------------------------------------------------
    // Chat rendering
    // ------------------------------------------------------------------

    private static void report(EntityPlayerMP player, ScanSession s) {
        HighlightStore.put(player, s);
        for (IChatComponent line : s.lines) {
            player.addChatMessage(line);
        }
        if (s.totalPatterns == 0) {
            say(player, "patternchecker.msg.noPatternsHint");
            return;
        }
        if (s.thirdPartyPatterns > 0) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.thirdParty",
                    s.thirdPartyPatterns));
        }
        if (s.ignoredIssues > 0) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.ignored", s.ignoredIssues));
        }
        if (s.issueCount() == 0) {
            IChatComponent clean = new ChatComponentTranslation("patternchecker.msg.clean");
            clean.getChatStyle().setColor(EnumChatFormatting.GREEN);
            player.addChatMessage(clean);
        }
    }

    private static IChatComponent headerLine(String key, Object... args) {
        ChatComponentTranslation c = new ChatComponentTranslation(key, args);
        c.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
        return c;
    }

    private static void say(EntityPlayerMP player, String key) {
        player.addChatMessage(new ChatComponentTranslation(key));
    }
}
