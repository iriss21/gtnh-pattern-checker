package com.patternchecker.network;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import com.patternchecker.gui.PanelActions;

/**
 * Client → server: commit (or cancel) a pattern edit. Carries the full edited
 * slot layout (per-slot item + long count) plus the global multiplier. The
 * server re-validates the target slot and rebuilds the pattern NBT itself.
 */
public class PacketEditCommit implements IMessage {

    public boolean cancel;
    public long multiplier;
    public final java.util.List<SlotState> inputs = new java.util.ArrayList<>();
    public final java.util.List<SlotState> outputs = new java.util.ArrayList<>();

    public static final class SlotState {

        public boolean empty;
        public int itemId;
        public int damage;
        public long count;
        public NBTTagCompound tag;
    }

    public PacketEditCommit() {
    }

    private static void writeSlot(ByteBuf buf, SlotState s) {
        if (s.empty || Item.getItemById(s.itemId) == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeInt(s.itemId);
        buf.writeInt(s.damage);
        buf.writeLong(s.count);
        byte[] bytes = null;
        if (s.tag != null) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                CompressedStreamTools.writeCompressed(s.tag, out);
                bytes = out.toByteArray();
            } catch (Exception e) {
                bytes = null;
            }
        }
        buf.writeBoolean(bytes != null);
        if (bytes != null) {
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    private static SlotState readSlot(ByteBuf buf) {
        SlotState s = new SlotState();
        if (!buf.readBoolean()) {
            s.empty = true;
            return s;
        }
        s.itemId = buf.readInt();
        s.damage = buf.readInt();
        s.count = buf.readLong();
        if (buf.readBoolean()) {
            int len = buf.readInt();
            if (len > 0) {
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                try {
                    s.tag = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
                } catch (Exception ignored) {
                    // treated as no NBT
                }
            }
        }
        return s;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.cancel = buf.readBoolean();
        this.multiplier = buf.readLong();
        int in = buf.readInt();
        for (int i = 0; i < in; i++) {
            this.inputs.add(readSlot(buf));
        }
        int out = buf.readInt();
        for (int i = 0; i < out; i++) {
            this.outputs.add(readSlot(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.cancel);
        buf.writeLong(this.multiplier);
        buf.writeInt(this.inputs.size());
        for (SlotState s : this.inputs) {
            writeSlot(buf, s);
        }
        buf.writeInt(this.outputs.size());
        for (SlotState s : this.outputs) {
            writeSlot(buf, s);
        }
    }

    public static class Handler implements IMessageHandler<PacketEditCommit, IMessage> {

        @Override
        public IMessage onMessage(final PacketEditCommit message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            // Writes into an interface pattern slot: must run on the server thread.
            final boolean cancel = message.cancel;
            final long multiplier = message.multiplier;
            ServerTaskQueue.submit(new Runnable() {

                @Override
                public void run() {
                    if (player.playerNetServerHandler == null) {
                        return;
                    }
                    PanelActions.handleCommit(player, cancel, multiplier, message.inputs, message.outputs);
                }
            });
            return null;
        }
    }
}
