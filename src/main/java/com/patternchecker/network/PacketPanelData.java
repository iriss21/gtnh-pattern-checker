package com.patternchecker.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import com.google.common.base.Charsets;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server → client: panel scan results (summary + issue rows).
 */
public class PacketPanelData implements IMessage {

    public static final int STATUS_OK = 0;
    public static final int STATUS_NO_NETWORK = 1;
    public static final int STATUS_BOUND_UNAVAILABLE = 2;
    public static final int STATUS_NO_PATTERNS = 3;

    public int status;
    /** 0-based page index; a scan of more rows than one packet fits is sent in pages. */
    public int page;
    public int totalPages;
    public int totalPatterns;
    public int interfacePatterns;
    public int storagePatterns;
    public int errors;
    public int warnings;
    public int thirdPartyPatterns;
    /** Issues suppressed because the player ignored that pattern. */
    public int ignoredPatterns;
    /** Patterns with no issue at all; the panel hides them unless asked for. */
    public int healthyPatterns;

    public final java.util.List<Row> rows = new java.util.ArrayList<>();

    public static final class Row {

        public boolean error;
        public String name = "";
        /** Lang key of the kind suffix ("（合成样板）" / "（处理样板）"); "" when unknown. */
        public String kind = "";
        public boolean hasLoc;
        public String locKey = "";
        public String locArg = "";
        public String issueKey;
        public String[] args = new String[0];
        public boolean canHighlight;
        public boolean canEdit;
        public boolean canExtract;
        /** False for group rows (duplicates) that have no single pattern to ignore. */
        public boolean canIgnore;
        /** The pattern is on the player's ignore list; hidden unless asked for. */
        public boolean ignored;
        /** The pattern has no issue at all ("ok" row); hidden unless asked for. */
        public boolean healthy;
        /** Dimension id, and the server's own name for it (only valid when hasLoc). */
        public int dim;
        public String dimName = "";

        /** Client-side cache of the localized text (resolved on first use). */
        private String displayName;
        private String[] displayArgs;
        private String displayDim;

        /** Localized dimension label, e.g. "主世界" / "Twilight Forest". */
        public String displayDim() {
            if (this.displayDim == null) {
                this.displayDim = com.patternchecker.check.DimensionNames.label(this.dim, this.dimName);
            }
            return this.displayDim;
        }

        /**
         * Localized pattern name. The server cannot translate (a dedicated server
         * has no language files), so it sends a descriptor and the client resolves
         * it with its own language pack.
         */
        public String displayName() {
            if (this.displayName == null) {
                this.displayName = com.patternchecker.check.ItemName.resolve(this.name);
            }
            return this.displayName;
        }

        /** Localized issue arguments (same decoding as {@link #displayName()}). */
        public String[] displayArgs() {
            if (this.displayArgs == null) {
                this.displayArgs = com.patternchecker.check.ItemName.resolveAll(this.args);
            }
            return this.displayArgs;
        }
    }

    public PacketPanelData() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.status = buf.readInt();
        this.page = buf.readInt();
        this.totalPages = buf.readInt();
        this.totalPatterns = buf.readInt();
        this.interfacePatterns = buf.readInt();
        this.storagePatterns = buf.readInt();
        this.errors = buf.readInt();
        this.warnings = buf.readInt();
        this.thirdPartyPatterns = buf.readInt();
        this.ignoredPatterns = buf.readInt();
        this.healthyPatterns = buf.readInt();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            this.rows.add(readRow(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.status);
        buf.writeInt(this.page);
        buf.writeInt(this.totalPages);
        buf.writeInt(this.totalPatterns);
        buf.writeInt(this.interfacePatterns);
        buf.writeInt(this.storagePatterns);
        buf.writeInt(this.errors);
        buf.writeInt(this.warnings);
        buf.writeInt(this.thirdPartyPatterns);
        buf.writeInt(this.ignoredPatterns);
        buf.writeInt(this.healthyPatterns);
        buf.writeInt(this.rows.size());
        for (Row r : this.rows) {
            writeRow(buf, r);
        }
    }

    private static Row readRow(ByteBuf buf) {
        Row r = new Row();
        r.error = buf.readBoolean();
        r.name = ByteBufUtils.readUTF8String(buf);
        r.kind = ByteBufUtils.readUTF8String(buf);
        r.hasLoc = buf.readBoolean();
        if (r.hasLoc) {
            r.locKey = ByteBufUtils.readUTF8String(buf);
            r.locArg = ByteBufUtils.readUTF8String(buf);
        }
        r.issueKey = ByteBufUtils.readUTF8String(buf);
        int argCount = buf.readInt();
        r.args = new String[argCount];
        for (int a = 0; a < argCount; a++) {
            r.args[a] = ByteBufUtils.readUTF8String(buf);
        }
        r.canHighlight = buf.readBoolean();
        r.canEdit = buf.readBoolean();
        r.canExtract = buf.readBoolean();
        r.canIgnore = buf.readBoolean();
        r.ignored = buf.readBoolean();
        r.healthy = buf.readBoolean();
        r.dim = buf.readInt();
        r.dimName = ByteBufUtils.readUTF8String(buf);
        return r;
    }

    private static void writeRow(ByteBuf buf, Row r) {
        buf.writeBoolean(r.error);
        writeUtfSafe(buf, r.name);
        writeUtfSafe(buf, r.kind);
        buf.writeBoolean(r.hasLoc);
        if (r.hasLoc) {
            writeUtfSafe(buf, r.locKey);
            writeUtfSafe(buf, r.locArg);
        }
        writeUtfSafe(buf, r.issueKey);
        buf.writeInt(r.args.length);
        for (String a : r.args) {
            writeUtfSafe(buf, a);
        }
        buf.writeBoolean(r.canHighlight);
        buf.writeBoolean(r.canEdit);
        buf.writeBoolean(r.canExtract);
        buf.writeBoolean(r.canIgnore);
        buf.writeBoolean(r.ignored);
        buf.writeBoolean(r.healthy);
        buf.writeInt(r.dim);
        writeUtfSafe(buf, r.dimName);
    }

    /**
     * FML's string encoder caps one string at 16383 UTF-8 bytes (2-byte varint
     * length). A hostile issue text (hundreds of missing item names joined into
     * one argument) would trip that and kill the whole scan push, so overlong
     * strings are halved until they fit.
     */
    private static void writeUtfSafe(ByteBuf buf, String s) {
        if (s == null) {
            s = "";
        }
        while (s.getBytes(Charsets.UTF_8).length > 16000 && s.length() > 0) {
            s = s.substring(0, s.length() / 2);
        }
        ByteBufUtils.writeUTF8String(buf, s);
    }

    /**
     * Serialized byte size of one row. The page packer uses it to keep each
     * payload under the 1.7.10 packet limit even when single rows are huge
     * (e.g. a "missing inputs" issue listing many item names).
     */
    public static int rowSize(Row r) {
        ByteBuf buf = Unpooled.buffer();
        try {
            writeRow(buf, r);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    public static class Handler implements IMessageHandler<PacketPanelData, IMessage> {

        @Override
        public IMessage onMessage(PacketPanelData message, MessageContext ctx) {
            com.patternchecker.client.ClientPanelState.apply(message);
            return null;
        }
    }
}
