package com.patternchecker.check;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Server-side store of the most recent scan session per player, so chat
 * highlight links can resolve back to positions. Concurrent because the network
 * threads and the server thread both touch it.
 */
public final class HighlightStore {

    private static final Map<String, ScanSession> SESSIONS = new ConcurrentHashMap<>();

    private HighlightStore() {
    }

    public static void put(EntityPlayerMP player, ScanSession session) {
        SESSIONS.put(player.getCommandSenderName(), session);
    }

    public static ScanSession get(EntityPlayerMP player) {
        return SESSIONS.get(player.getCommandSenderName());
    }

    public static void clear(EntityPlayerMP player) {
        SESSIONS.remove(player.getCommandSenderName());
    }

    public static void clear(String playerName) {
        SESSIONS.remove(playerName);
    }
}
