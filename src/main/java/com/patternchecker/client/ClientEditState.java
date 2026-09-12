package com.patternchecker.client;

import net.minecraft.client.Minecraft;

import com.patternchecker.client.gui.GuiPatternEdit;
import com.patternchecker.network.PacketEditData;

/**
 * Client-side edit session payload; when it arrives the edit screen opens
 * (or is refreshed) with the latest slot layout.
 */
public final class ClientEditState {

    private static PacketEditData pending;
    private static long stamp;

    private ClientEditState() {
    }

    public static void apply(final PacketEditData packet) {
        pending = packet;
        stamp = System.currentTimeMillis();
        // The screen itself is opened by FML's window path (getClientGuiElement), so
        // that the container and windowId come from the same place the server used -
        // otherwise clicks on the player inventory would go to the wrong window. This
        // payload only has to refresh an editor that is already on screen, and packet
        // handlers run on the Netty thread, so touching the screen must be deferred.
        ClientThreadQueue.submit(new Runnable() {

            @Override
            public void run() {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.currentScreen instanceof GuiPatternEdit) {
                    ((GuiPatternEdit) mc.currentScreen).applyData(packet);
                }
            }
        });
    }

    /** Consumes cached data for the open-window path; falls back to empty content. */
    public static PacketEditData takeOrEmpty() {
        PacketEditData p = pending;
        if (p != null && System.currentTimeMillis() - stamp < 10_000) {
            pending = null;
            return p;
        }
        return new PacketEditData();
    }
}
