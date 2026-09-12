package com.patternchecker.check;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Server-side store of the last panel scan per player. Rows are addressed by
 * index from the client. The map is written from the network threads and read
 * from the server thread, hence the concurrent map.
 *
 * <p>The scan mode ("scan all" vs "scan the bound/nearest network") is kept
 * alongside the rows so an ignore / un-ignore action can re-run the very same
 * scan and push fresh rows back.
 */
public final class PanelStore {

    private static final class Entry {

        final List<PanelRow> rows;
        final boolean all;

        Entry(List<PanelRow> rows, boolean all) {
            this.rows = rows;
            this.all = all;
        }
    }

    private static final Map<String, Entry> BY_PLAYER = new ConcurrentHashMap<>();

    private PanelStore() {
    }

    public static void put(EntityPlayerMP player, List<PanelRow> rows, boolean all) {
        BY_PLAYER.put(player.getCommandSenderName(), new Entry(rows, all));
    }

    public static List<PanelRow> get(EntityPlayerMP player) {
        Entry e = BY_PLAYER.get(player.getCommandSenderName());
        return e == null ? null : e.rows;
    }

    /** Whether the stored panel came from a "scan all" run. */
    public static boolean isAll(EntityPlayerMP player) {
        Entry e = BY_PLAYER.get(player.getCommandSenderName());
        return e != null && e.all;
    }

    public static void clear(String playerName) {
        BY_PLAYER.remove(playerName);
    }
}
