package com.patternchecker.network;

import io.netty.buffer.ByteBuf;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server → client: show (or clear) world-space highlight boxes.
 */
public class PacketHighlight implements IMessage {

    private int dimension;
    private int[][] positions = new int[0][];
    private int durationSeconds;

    public PacketHighlight() {
    }

    public PacketHighlight(int dimension, int[][] positions, int durationSeconds) {
        this.dimension = dimension;
        this.positions = positions;
        this.durationSeconds = durationSeconds;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.dimension = buf.readInt();
        this.durationSeconds = buf.readInt();
        int count = buf.readInt();
        this.positions = new int[count][];
        for (int i = 0; i < count; i++) {
            this.positions[i] = new int[] { buf.readInt(), buf.readInt(), buf.readInt() };
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.dimension);
        buf.writeInt(this.durationSeconds);
        buf.writeInt(this.positions.length);
        for (int[] p : this.positions) {
            buf.writeInt(p[0]);
            buf.writeInt(p[1]);
            buf.writeInt(p[2]);
        }
    }

    public static class Handler implements IMessageHandler<PacketHighlight, IMessage> {

        @Override
        public IMessage onMessage(PacketHighlight message, MessageContext ctx) {
            final int dim = message.dimension;
            final int[][] positions = message.positions;
            final int duration = message.durationSeconds;
            // Body only touches client classes; executed on the client only.
            com.patternchecker.client.ClientHighlightState.apply(dim, positions, duration);
            return null;
        }
    }
}
