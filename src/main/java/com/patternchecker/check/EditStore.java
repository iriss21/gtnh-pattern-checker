package com.patternchecker.check;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * Server-side edit sessions. The original pattern stack is the single source
 * of truth; the client only submits a multiplier, so no item data can be
 * injected through packets. Concurrent because the network threads and the
 * server thread both touch it.
 */
public final class EditStore {

    private static final long EXPIRE_MILLIS = 10 * 60 * 1000L;

    public static final class EditSession {

        public final ItemStack original;
        public final int dim;
        public final int x;
        public final int y;
        public final int z;
        public final int slot;
        public final long created;

        public EditSession(ItemStack original, int dim, int x, int y, int z, int slot) {
            this.original = original;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.slot = slot;
            this.created = System.currentTimeMillis();
        }
    }

    private static final Map<String, EditSession> SESSIONS = new ConcurrentHashMap<>();

    private EditStore() {
    }

    public static void put(EntityPlayerMP player, EditSession session) {
        SESSIONS.put(player.getCommandSenderName(), session);
    }

    public static EditSession get(EntityPlayerMP player) {
        String name = player.getCommandSenderName();
        EditSession s = SESSIONS.get(name);
        if (s != null && System.currentTimeMillis() - s.created > EXPIRE_MILLIS) {
            SESSIONS.remove(name);
            return null;
        }
        return s;
    }

    public static void remove(EntityPlayerMP player) {
        SESSIONS.remove(player.getCommandSenderName());
    }

    public static void clear(String playerName) {
        SESSIONS.remove(playerName);
    }

    /** Drops expired sessions; called opportunistically. */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<EditSession> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().created > EXPIRE_MILLIS) {
                it.remove();
            }
        }
    }
}
