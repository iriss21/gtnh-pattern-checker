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

    /** Upper bound on the rows pushed to the panel (and kept for row-addressed actions). */
    private static final int MAX_PANEL_ROWS = 150;

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

    /** /patterncheck scan all */
    public static void scanAll(EntityPlayerMP player) {
        WorldServer world = MinecraftServer.getServer().worldServerForDimension(player.dimension);
        say(player, "patternchecker.msg.scanningAll");

        Set<IGrid> seen = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
        ScanSession session = new ScanSession(player, player.dimension);
        int index = 0;
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
            WorldServer world = server.worldServerForDimension(player.dimension);
            ScanSession session = new ScanSession(player, player.dimension);
            Set<IGrid> seen = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
            int index = 0;
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
        // Non-ignored rows first: the panel hides ignored ones by default and
        // lists them at the end when the player asks to see them.
        List<PanelRow> ordered = new ArrayList<>(s.rows.size());
        for (PanelRow row : s.rows) {
            if (!row.data.ignored) {
                ordered.add(row);
            }
        }
        for (PanelRow row : s.rows) {
            if (row.data.ignored) {
                ordered.add(row);
            }
        }
        int cap = Math.min(ordered.size(), MAX_PANEL_ROWS);
        List<PanelRow> stored = new ArrayList<>(ordered.subList(0, cap));
        PanelStore.put(player, stored, all);
        PacketPanelData packet = new PacketPanelData();
        packet.status = status;
        packet.totalPatterns = s.totalPatterns;
        packet.interfacePatterns = s.interfacePatterns;
        packet.storagePatterns = s.storagePatterns;
        packet.errors = s.errors;
        packet.warnings = s.warnings;
        packet.thirdPartyPatterns = s.thirdPartyPatterns;
        packet.ignoredPatterns = s.ignoredIssues;
        for (PanelRow row : stored) {
            packet.rows.add(toPacketRow(row));
        }
        PatternCheckerNetwork.sendPanelData(player, packet);
    }

    private static PacketPanelData.Row toPacketRow(PanelRow row) {
        PacketPanelData.Row r = new PacketPanelData.Row();
        IssueData d = row.data;
        r.error = d.error;
        r.name = d.name;
        r.hasLoc = d.locKey != null;
        r.locKey = d.locKey == null ? "" : d.locKey;
        r.locArg = d.locArg == null ? "" : d.locArg;
        r.issueKey = d.issueKey;
        r.args = d.args;
        r.canHighlight = d.pos != null;
        r.canEdit = d.edit != null && d.edit.processing;
        r.canExtract = d.edit != null;
        r.canIgnore = d.key != null;
        r.ignored = d.ignored;
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
            for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (stack == null) {
                    continue;
                }
                checkOne(s, world, stack, true, s.dimension, slot, pos,
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
                checkOne(s, world, stack, false, s.dimension, -1, null,
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
                addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, false,
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
                addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, false,
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

        boolean nbtProcessing = !tag.getBoolean("crafting");

        if (tag.getBoolean("InvalidPattern")) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, nbtProcessing,
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
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, nbtProcessing,
                    wasCrafting ? "patternchecker.issue.recipeChanged" : "patternchecker.issue.undecodable",
                    new String[] { why });
            return;
        }
        if (details == null) {
            if (!tag.hasKey("in")) {
                // no standard layout and the item cannot decode it either
                addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, nbtProcessing,
                        "patternchecker.issue.blankPattern", new String[0]);
                return;
            }
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, nbtProcessing,
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
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, processing,
                    "patternchecker.issue.processing.zeroOutput", new String[0]);
        }

        boolean badInput = false;
        for (IAEItemStack i : condensedIn) {
            if (i == null || i.getStackSize() <= 0) {
                badInput = true;
            }
        }
        if (badInput) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, true, processing,
                    processing ? "patternchecker.issue.processing.zeroInput" : "patternchecker.issue.input.empty",
                    new String[0]);
        }

        if (processing && condensedIn.length == 1 && condensedOut.length == 1
                && condensedOut[0].isSameType(condensedIn[0])
                && condensedOut[0].getStackSize() == condensedIn[0].getStackSize()) {
            addPatternIssue(s, stack, locKey, locArg, pos, dim, slot, false, processing,
                    "patternchecker.issue.processing.selfLoop", new String[0]);
        }

        // Input availability: stocked in ME storage or craftable on the network.
        List<String> missing = new ArrayList<>();
        for (IAEItemStack i : condensedIn) {
            if (i == null) {
                continue;
            }
            if (!stocked.contains(i) && !craftableOutputs.contains(i)) {
                missing.add(i.getItemStack().getDisplayName());
            }
        }
        if (!missing.isEmpty()) {
            addRowIssue(s, stack, locKey, locArg, pos, dim, slot, false, processing,
                    "patternchecker.issue.input.missing", new String[] { join(missing) });
        }

        // Registered in the crafting cache?
        if (fromInterface && !registered.contains(typeKey(stack))) {
            addRowIssue(s, stack, locKey, locArg, pos, dim, slot, false, processing,
                    "patternchecker.issue.unregistered", new String[0]);
        }

        DupKey key = new DupKey(processing, condensedIn, condensedOut);
        List<ScannedRef> group = duplicateGroups.get(key);
        if (group == null) {
            group = new ArrayList<>();
            duplicateGroups.put(key, group);
        }
        group.add(new ScannedRef(key, pos));
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

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p);
        }
        return sb.toString();
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
            int dim, int slot, boolean error, boolean processing, String issueKey, String[] args) {
        String key = PatternKey.of(stack);
        boolean ignored = s.isIgnored(key);
        IssueData.EditTarget edit = editTarget(dim, slot, pos, processing);
        s.rows.add(new PanelRow(
                new IssueData(error, stack.getDisplayName(), locKey, locArg, issueKey, args, pos, dim, edit, key,
                        ignored),
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
        s.lines.add(chatIssueLine(error, new ChatComponentTranslation(issueKey, (Object[]) args), pos, s));
    }

    /** Sub-issue of a pattern: chat gets only the issue line; panel gets a full row. */
    private static void addRowIssue(ScanSession s, ItemStack stack, String locKey, String locArg, int[] pos,
            int dim, int slot, boolean error, boolean processing, String issueKey, String[] args) {
        String key = PatternKey.of(stack);
        boolean ignored = s.isIgnored(key);
        IssueData.EditTarget edit = editTarget(dim, slot, pos, processing);
        s.rows.add(new PanelRow(
                new IssueData(error, stack.getDisplayName(), locKey, locArg, issueKey, args, pos, dim, edit, key,
                        ignored),
                edit != null ? stack.copy() : null));
        if (countIssue(s, error, ignored)) {
            return;
        }

        s.lines.add(chatIssueLine(error, new ChatComponentTranslation(issueKey, (Object[]) args), pos, s));
    }

    /** Group issue (duplicates): no name/location context. */
    private static void addBareIssue(ScanSession s, boolean error, String issueKey, String[] args, int[] pos,
            String key) {
        boolean ignored = s.isIgnored(key);
        s.rows.add(new PanelRow(new IssueData(error, "", null, "", issueKey, args, pos, 0, null, key, ignored), null));
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
