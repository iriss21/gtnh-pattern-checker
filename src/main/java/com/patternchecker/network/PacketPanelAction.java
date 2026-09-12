package com.patternchecker.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import com.patternchecker.gui.PanelActions;

/**
 * Client → server: panel actions (scan, highlight, edit, extract, ignore)
 * addressed by row index into the server-side PanelStore.
 */
public class PacketPanelAction implements IMessage {

    public static final byte SCAN = 0;
    public static final byte SCAN_ALL = 1;
    public static final byte HIGHLIGHT = 2;
    public static final byte EDIT = 3;
    public static final byte EXTRACT = 4;
    public static final byte IGNORE = 5;
    public static final byte UNIGNORE = 6;

    public byte action;
    public int row;

    public PacketPanelAction() {
    }

    public PacketPanelAction(byte action, int row) {
        this.action = action;
        this.row = row;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.action = buf.readByte();
        this.row = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.action);
        buf.writeInt(this.row);
    }

    public static class Handler implements IMessageHandler<PacketPanelAction, IMessage> {

        @Override
        public IMessage onMessage(final PacketPanelAction message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            // Scanning touches loaded tile entities and inventories: never off the
            // Netty thread this handler is called on.
            ServerTaskQueue.submit(new Runnable() {

                @Override
                public void run() {
                    if (player.playerNetServerHandler == null) {
                        return;
                    }
                    PanelActions.handle(player, message.action, message.row);
                }
            });
            return null;
        }
    }
}
