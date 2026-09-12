package com.patternchecker.gui;

import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;

import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.DimensionalCoord;
import appeng.helpers.IInterfaceHost;
import appeng.util.Platform;

import com.patternchecker.PatternCheckerMod;
import com.patternchecker.check.EditStore;
import com.patternchecker.check.IgnoreStore;
import com.patternchecker.check.ItemName;
import com.patternchecker.check.PanelRow;
import com.patternchecker.check.PanelStore;
import com.patternchecker.check.PatternCheckService;
import com.patternchecker.network.PacketEditCommit;
import com.patternchecker.network.PacketEditData;
import com.patternchecker.network.PacketPanelAction;
import com.patternchecker.network.PatternCheckerNetwork;

/**
 * Server-side handling of panel button presses. The client only ever sends
 * (action, row) or (cancel, multiplier, edited slot layout); every mutation is
 * derived server-side and re-validated against the live interface slot before
 * anything is written.
 */
public final class PanelActions {

    private static final long MAX_MULTIPLIER = 1_000_000_000L;
    private static final long MAX_COUNT = 1_000_000_000_000_000L;
    private static final int MAX_INPUT_SLOTS = 9;
    private static final int MAX_OUTPUT_SLOTS = 3;

    private PanelActions() {
    }

    public static void handle(EntityPlayerMP player, byte action, int row) {
        switch (action) {
        case PacketPanelAction.SCAN:
            PatternCheckService.scanForPanel(player, false);
            break;
        case PacketPanelAction.SCAN_ALL:
            PatternCheckService.scanForPanel(player, true);
            break;
        case PacketPanelAction.HIGHLIGHT:
            highlight(player, row);
            break;
        case PacketPanelAction.EDIT:
            edit(player, row);
            break;
        case PacketPanelAction.EXTRACT:
            extract(player, row);
            break;
        case PacketPanelAction.IGNORE:
            setIgnored(player, row, true);
            break;
        case PacketPanelAction.UNIGNORE:
            setIgnored(player, row, false);
            break;
        default:
            break;
        }
    }

    /**
     * Adds or removes a pattern fingerprint on the player's ignore list, then
     * re-runs the scan the panel is currently showing so the list and the
     * error/warning totals stay consistent with what is displayed.
     */
    private static void setIgnored(EntityPlayerMP player, int row, boolean ignore) {
        List<PanelRow> all = PanelStore.get(player);
        if (all == null || row < 0 || row >= all.size()) {
            return;
        }
        PanelRow pr = all.get(row);
        String key = pr.data.key;
        if (key == null) {
            return;
        }
        String name = ItemName.plain(pr.data.name);
        if (ignore) {
            IgnoreStore.add(player.getCommandSenderName(), key);
            if (!name.isEmpty()) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.action.ignored", name));
            }
        } else {
            IgnoreStore.remove(player.getCommandSenderName(), key);
            if (!name.isEmpty()) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.action.unignored", name));
            }
        }
        PatternCheckService.scanForPanel(player, PanelStore.isAll(player));
    }

    private static void highlight(EntityPlayerMP player, int row) {
        List<PanelRow> all = PanelStore.get(player);
        if (all == null || row < 0 || row >= all.size()) {
            return;
        }
        int[] pos = all.get(row).data.pos;
        if (pos == null) {
            return;
        }
        PatternCheckerNetwork.sendHighlight(player, all.get(row).data.dim, new int[][] { pos }, 15);
        player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.highlighted",
                pos[0] + ", " + pos[1] + ", " + pos[2]));
    }

    /** Opens the per-slot pattern editor for one interface pattern. */
    private static void edit(EntityPlayerMP player, int row) {
        List<PanelRow> all = PanelStore.get(player);
        if (all == null || row < 0 || row >= all.size()) {
            return;
        }
        PanelRow pr = all.get(row);
        if (pr.data.edit == null || !pr.data.edit.processing || pr.original == null
                || pr.original.getTagCompound() == null) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.edit.needProcessing"));
            return;
        }

        NBTTagCompound tag = pr.original.getTagCompound();
        NBTTagList inTag = tag.getTagList("in", 10);
        NBTTagList outTag = tag.getTagList("out", 10);
        if (inTag.tagCount() > MAX_INPUT_SLOTS || outTag.tagCount() > MAX_OUTPUT_SLOTS) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.edit.overflow",
                    inTag.tagCount() + "x" + outTag.tagCount()));
            return;
        }

        PacketEditData packet = new PacketEditData();
        // The client resolves the localized name (a dedicated server has no lang).
        packet.name = ItemName.encode(pr.original);
        packet.targetDesc = pr.data.edit.x + ", " + pr.data.edit.y + ", " + pr.data.edit.z;
        int unsupported = fillEntries(packet.inputs, inTag, MAX_INPUT_SLOTS);
        unsupported += fillEntries(packet.outputs, outTag, MAX_OUTPUT_SLOTS);
        if (unsupported > 0) {
            // Fluid / gas / other non-item entries (AE2FC, ae2thing, ...) cannot be
            // represented as ghost items; editing would silently drop them.
            player.addChatMessage(new ChatComponentTranslation("patternchecker.edit.nonItemEntry", unsupported));
            return;
        }

        // Open the server container first so the player inventory syncs, then
        // push the edit data; the client GUI opens on either arrival order.
        WorldServer world = MinecraftServer.getServer().worldServerForDimension(pr.data.edit.dim);
        player.openGui(PatternCheckerMod.instance, GuiHandler.GUI_EDIT, world,
                pr.data.edit.x, pr.data.edit.y, pr.data.edit.z);
        EditStore.put(player, new EditStore.EditSession(pr.original.copy(), pr.data.edit.dim, pr.data.edit.x,
                pr.data.edit.y, pr.data.edit.z, pr.data.edit.slot));
        PatternCheckerNetwork.sendEditData(player, packet);
    }

    /**
     * Decodes the pattern's in/out tag lists into ghost-slot entries.
     *
     * @return how many non-empty entries could not be read as a plain item stack
     *         (fluid / gas / unknown stack types contributed by AE2 addons). The
     *         caller refuses the edit when this is &gt; 0.
     */
    private static int fillEntries(List<PacketEditData.EditEntry> target, NBTTagList list, int capacity) {
        int unsupported = 0;
        for (int i = 0; i < capacity; i++) {
            PacketEditData.EditEntry e = new PacketEditData.EditEntry();
            if (i < list.tagCount()) {
                NBTTagCompound tag = list.getCompoundTagAt(i);
                if (!tag.hasNoTags()) {
                    ItemStack gs = Platform.loadItemStackFromNBT(tag);
                    if (gs != null && isItemEntry(tag)) {
                        e.count = readEntryCount(tag, gs.stackSize);
                        gs.stackSize = 1;
                        e.icon = gs;
                        e.empty = false;
                    } else {
                        unsupported++;
                    }
                }
            }
            if (e.icon == null) {
                e.empty = true;
                e.count = 0;
            }
            target.add(e);
        }
        return unsupported;
    }

    /**
     * Newer AE2 lines tag every pattern entry with a {@code StackType} id
     * ({@code toNBTGeneric}). Item stacks are fine, anything else (fluids, gases,
     * essentia, ...) would be mangled by an item-only editor. The AE2 build this
     * mod targets does not emit the key at all, so this only ever fires on the
     * generic-format line and never rejects a plain item entry.
     */
    private static boolean isItemEntry(NBTTagCompound tag) {
        if (!tag.hasKey("StackType")) {
            return true;
        }
        return tag.getString("StackType").toLowerCase(Locale.ROOT).contains("item");
    }

    /**
     * Reads an entry's quantity in either of the two formats GTNH AE2 writes:
     * the int {@code Count} used by the pattern terminal, or the long {@code Cnt}
     * used by {@code AEItemStack#writeToNBT} (and by AE2's own pattern multiplier).
     */
    private static long readEntryCount(NBTTagCompound tag, int fromVanillaLoad) {
        if (tag.hasKey("Count")) {
            int count = tag.getInteger("Count");
            if (count > 0) {
                return count;
            }
        }
        if (tag.hasKey("Cnt")) {
            long count = tag.getLong("Cnt");
            if (count > 0) {
                return count;
            }
        }
        return Math.max(1, fromVanillaLoad);
    }

    private static void extract(EntityPlayerMP player, int row) {
        List<PanelRow> all = PanelStore.get(player);
        if (all == null || row < 0 || row >= all.size()) {
            return;
        }
        PanelRow pr = all.get(row);
        if (pr.data.edit == null || pr.original == null) {
            return;
        }
        IInventory inv = slotInventory(pr.data.edit, pr.original);
        if (inv == null) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.action.noProvider"));
            return;
        }
        ItemStack current = inv.getStackInSlot(pr.data.edit.slot);
        if (current == null || !ItemStack.areItemStacksEqual(current, pr.original)) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.action.originalChanged"));
            return;
        }
        inv.setInventorySlotContents(pr.data.edit.slot, null);
        ItemStack give = pr.original.copy();
        if (!player.inventory.addItemStackToInventory(give)) {
            player.dropPlayerItemWithRandomChoice(give, false);
        }
        player.addChatMessage(new ChatComponentTranslation("patternchecker.action.extracted",
                pr.original.getDisplayName()));
    }

    /**
     * Resolves the pattern inventory behind a scan target.
     *
     * <p>A target can be a plain block interface (the tile entity is the host) or a
     * cable-mounted part - which is what AE2FC's fluid "dual" interface and every
     * other cable interface is. For a part the block entity at those coordinates is
     * the cable bus, so the hosts are collected from the grid instead; several
     * interfaces may share one cable, so the candidate that still holds the scanned
     * pattern in the target slot wins.
     */
    private static IInventory slotInventory(com.patternchecker.check.IssueData.EditTarget t, ItemStack expected) {
        WorldServer world = MinecraftServer.getServer().worldServerForDimension(t.dim);
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(t.x, t.y, t.z);
        if (te instanceof IInterfaceHost) {
            IInventory inv = ((IInterfaceHost) te).getInterfaceDuality().getPatterns();
            if (inv != null) {
                return inv;
            }
        }
        if (!(te instanceof IGridHost)) {
            return null;
        }
        IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) {
            return null;
        }
        IInventory fallback = null;
        for (IGridNode n : grid.getNodes()) {
            IGridHost machine = n.getMachine();
            if (!(machine instanceof IInterfaceHost)) {
                continue;
            }
            IInterfaceHost host = (IInterfaceHost) machine;
            if (!isAt(host, t)) {
                continue;
            }
            IInventory inv = host.getInterfaceDuality().getPatterns();
            if (inv == null) {
                continue;
            }
            if (expected != null && t.slot >= 0 && t.slot < inv.getSizeInventory()
                    && ItemStack.areItemStacksEqual(inv.getStackInSlot(t.slot), expected)) {
                return inv;
            }
            if (fallback == null) {
                fallback = inv;
            }
        }
        return fallback;
    }

    private static boolean isAt(IInterfaceHost host, com.patternchecker.check.IssueData.EditTarget t) {
        DimensionalCoord loc = host.getLocation();
        return loc != null && loc.x == t.x && loc.y == t.y && loc.z == t.z
                && (loc.getDimension() == t.dim || loc.getDimension() == 0);
    }

    // ------------------------------------------------------------------
    // Edit commit
    // ------------------------------------------------------------------

    public static void handleCommit(EntityPlayerMP player, boolean cancel, long multiplier,
            List<PacketEditCommit.SlotState> inputs, List<PacketEditCommit.SlotState> outputs) {
        if (cancel) {
            EditStore.remove(player);
            return;
        }
        EditStore.EditSession session = EditStore.get(player);
        if (session == null) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.action.failed", "no session"));
            return;
        }
        long mult = Math.max(1L, Math.min(MAX_MULTIPLIER, multiplier));

        IInventory inv = slotInventory(new com.patternchecker.check.IssueData.EditTarget(
                session.dim, session.x, session.y, session.z, session.slot, true), session.original);
        if (inv == null) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.action.noProvider"));
            return;
        }
        ItemStack current = inv.getStackInSlot(session.slot);
        if (current == null || !ItemStack.areItemStacksEqual(current, session.original)) {
            player.addChatMessage(new ChatComponentTranslation("patternchecker.action.originalChanged"));
            return;
        }

        ItemStack reencoded = reencode(session.original, mult, inputs, outputs);
        if (reencoded == null) {
            return; // specific message already sent
        }
        inv.setInventorySlotContents(session.slot, reencoded);
        EditStore.remove(player);
        player.addChatMessage(new ChatComponentTranslation("patternchecker.action.uploaded",
                reencoded.getDisplayName()));
    }

    /**
     * Rebuilds the pattern NBT from the edited slot layout.
     *
     * <p>Every key the original pattern carried is preserved verbatim (author,
     * substitute flags plus anything AE2 addons put there, such as
     * {@code tunnelUuid} for input-only patterns or AE2FC / ae2thing metadata);
     * only {@code in}, {@code out} and the {@code crafting} flag are rewritten.
     * The pattern stays a processing pattern.
     */
    private static ItemStack reencode(ItemStack original, long mult,
            List<PacketEditCommit.SlotState> inputs, List<PacketEditCommit.SlotState> outputs) {
        if (inputs == null || outputs == null || inputs.size() > MAX_INPUT_SLOTS
                || outputs.size() > MAX_OUTPUT_SLOTS) {
            return null;
        }

        int filledIn = 0;
        int filledOut = 0;
        for (PacketEditCommit.SlotState s : inputs) {
            if (s != null && !s.empty) {
                filledIn++;
            }
        }
        for (PacketEditCommit.SlotState s : outputs) {
            if (s != null && !s.empty) {
                filledOut++;
            }
        }
        if (filledIn == 0 || filledOut == 0) {
            return null;
        }

        NBTTagCompound orig = original.getTagCompound();
        NBTTagCompound root = orig == null ? new NBTTagCompound() : (NBTTagCompound) orig.copy();
        root.removeTag("in");
        root.removeTag("out");
        root.setBoolean("crafting", false);
        root.setBoolean("substitute", orig != null && orig.getBoolean("substitute"));
        root.setBoolean("beSubstitute", orig != null && orig.getBoolean("beSubstitute"));
        root.setTag("in", buildList(inputs, MAX_INPUT_SLOTS, mult));
        root.setTag("out", buildList(outputs, MAX_OUTPUT_SLOTS, mult));

        ItemStack out = original.copy();
        out.setTagCompound(root);
        return out;
    }

    private static NBTTagList buildList(List<PacketEditCommit.SlotState> slots, int capacity, long mult) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < capacity; i++) {
            PacketEditCommit.SlotState s = i < slots.size() ? slots.get(i) : null;
            if (s == null || s.empty || Item.getItemById(s.itemId) == null) {
                result.appendTag(new NBTTagCompound());
                continue;
            }
            long amount = saturatingMultiply(s.count < 1 ? 1L : Math.min(s.count, MAX_COUNT), mult);
            ItemStack gs = new ItemStack(Item.getItemById(s.itemId), 1, s.damage);
            if (s.tag != null) {
                gs.setTagCompound(s.tag);
            }
            NBTTagCompound tag = new NBTTagCompound();
            writeEntry(gs, amount, tag);
            result.appendTag(tag);
        }
        return result;
    }

    /**
     * Serialises one pattern entry in the format the target AE2 build actually
     * reads.
     *
     * <p>The pattern terminal on older GTNH AE2 (e.g. rv3-beta-695) writes and
     * reads the vanilla sheet plus an <b>int</b> {@code Count}
     * ({@code Platform#writeItemStackToNBT} / {@code Platform#loadItemStackFromNBT}),
     * while newer AE2 reads the long {@code Cnt} produced by
     * {@code AEItemStack#writeToNBT} and only falls back to it when the vanilla
     * load yields a size of 0. Older builds have no such fallback, so writing
     * only {@code Cnt} makes every entry deserialize with a quantity of 0.
     *
     * <p>Writing both keys keeps a single jar correct on either version, and matches
     * what AE2's own pattern multiplier expects to find.
     */
    private static void writeEntry(ItemStack stack, long amount, NBTTagCompound tag) {
        long clamped = Math.max(1L, amount);
        int asInt = (int) Math.min(Integer.MAX_VALUE, clamped);
        stack.stackSize = asInt;
        // ItemStack.writeToNBT: id (short) / Count (byte) / Damage (short) / tag
        stack.writeToNBT(tag);
        tag.setInteger("Count", asInt);
        // long-quantity form, for the newer AE2 reader (and AE2's multiplier helper)
        tag.setLong("Cnt", clamped);
    }

    private static long saturatingMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return Math.max(1L, a * Math.max(1L, b));
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }
}
