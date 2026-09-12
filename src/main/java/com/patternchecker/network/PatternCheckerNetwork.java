package com.patternchecker.network;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * Network channel: highlight rendering (S2C), panel data & edit data (S2C),
 * panel actions & edit commits (C2S).
 */
public final class PatternCheckerNetwork {

    private static final String CHANNEL = "gtnhpatternchecker";
    private static final int ID_HIGHLIGHT = 0;
    private static final int ID_PANEL_DATA = 1;
    private static final int ID_PANEL_ACTION = 2;
    private static final int ID_EDIT_DATA = 3;
    private static final int ID_EDIT_COMMIT = 4;

    public static SimpleNetworkWrapper INSTANCE;

    private PatternCheckerNetwork() {
    }

    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        INSTANCE.registerMessage(PacketHighlight.Handler.class, PacketHighlight.class, ID_HIGHLIGHT, Side.CLIENT);
        INSTANCE.registerMessage(PacketPanelData.Handler.class, PacketPanelData.class, ID_PANEL_DATA, Side.CLIENT);
        INSTANCE.registerMessage(PacketPanelAction.Handler.class, PacketPanelAction.class, ID_PANEL_ACTION,
                Side.SERVER);
        INSTANCE.registerMessage(PacketEditData.Handler.class, PacketEditData.class, ID_EDIT_DATA, Side.CLIENT);
        INSTANCE.registerMessage(PacketEditCommit.Handler.class, PacketEditCommit.class, ID_EDIT_COMMIT,
                Side.SERVER);
    }

    /** Sends highlight positions to the player; empty array clears highlights. */
    public static void sendHighlight(EntityPlayerMP player, int dimension, int[][] positions, int durationSeconds) {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.sendTo(new PacketHighlight(dimension, positions, durationSeconds), player);
    }

    public static void sendPanelData(EntityPlayerMP player, PacketPanelData data) {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.sendTo(data, player);
    }

    public static void sendEditData(EntityPlayerMP player, PacketEditData data) {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.sendTo(data, player);
    }
}
