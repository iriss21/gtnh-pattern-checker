package com.patternchecker.network;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server → client: edit screen contents — the raw slot layout of the selected
 * processing pattern (empty slots preserved, per-slot long counts).
 */
public class PacketEditData implements IMessage {

    public String name = "";
    public String targetDesc = "";
    /** Dimension of the edited interface, plus the server's own name for it. */
    public int dim;
    public String dimName = "";
    public final java.util.List<EditEntry> inputs = new java.util.ArrayList<>();
    public final java.util.List<EditEntry> outputs = new java.util.ArrayList<>();

    public static final class EditEntry {

        public boolean empty;
        /** Display stack (size 1, with NBT); null when empty. */
        public ItemStack icon;
        public long count;
    }

    public PacketEditData() {
    }

    private static void writeEntry(ByteBuf buf, EditEntry e) {
        if (e.empty || e.icon == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeInt(Item.getIdFromItem(e.icon.getItem()));
        buf.writeInt(e.icon.getItemDamage());
        buf.writeLong(e.count);
        byte[] bytes = null;
        NBTTagCompound tag = e.icon.getTagCompound();
        if (tag != null) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                CompressedStreamTools.writeCompressed(tag, out);
                bytes = out.toByteArray();
            } catch (Exception ex) {
                bytes = null;
            }
        }
        buf.writeBoolean(bytes != null);
        if (bytes != null) {
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    private static EditEntry readEntry(ByteBuf buf) {
        EditEntry e = new EditEntry();
        if (!buf.readBoolean()) {
            e.empty = true;
            return e;
        }
        int id = buf.readInt();
        int dmg = buf.readInt();
        e.count = buf.readLong();
        ItemStack s = new ItemStack(Item.getItemById(id), 1, dmg);
        if (buf.readBoolean()) {
            int len = buf.readInt();
            if (len > 0) {
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                try {
                    s.setTagCompound(CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes)));
                } catch (Exception ignored) {
                    // icon renders without NBT
                }
            }
        }
        e.icon = s;
        return e;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.name = ByteBufUtils.readUTF8String(buf);
        this.targetDesc = ByteBufUtils.readUTF8String(buf);
        this.dim = buf.readInt();
        this.dimName = ByteBufUtils.readUTF8String(buf);
        int in = buf.readInt();
        for (int i = 0; i < in; i++) {
            this.inputs.add(readEntry(buf));
        }
        int out = buf.readInt();
        for (int i = 0; i < out; i++) {
            this.outputs.add(readEntry(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.name);
        ByteBufUtils.writeUTF8String(buf, this.targetDesc);
        buf.writeInt(this.dim);
        ByteBufUtils.writeUTF8String(buf, this.dimName);
        buf.writeInt(this.inputs.size());
        for (EditEntry e : this.inputs) {
            writeEntry(buf, e);
        }
        buf.writeInt(this.outputs.size());
        for (EditEntry e : this.outputs) {
            writeEntry(buf, e);
        }
    }

    public static class Handler implements IMessageHandler<PacketEditData, IMessage> {

        @Override
        public IMessage onMessage(PacketEditData message, MessageContext ctx) {
            com.patternchecker.client.ClientEditState.apply(message);
            return null;
        }
    }
}
