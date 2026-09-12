package com.patternchecker.client;

import java.util.ArrayList;
import java.util.List;

import com.patternchecker.network.PacketPanelData;

/**
 * Client-side snapshot of the last panel scan, read by GuiPatternCheckPanel.
 */
public final class ClientPanelState {

    public static final class Snapshot {

        public final int status;
        public final int totalPatterns;
        public final int interfacePatterns;
        public final int storagePatterns;
        public final int errors;
        public final int warnings;
        public final int thirdPartyPatterns;
        public final int ignoredPatterns;
        public final List<PacketPanelData.Row> rows;

        Snapshot(int status, int totalPatterns, int interfacePatterns, int storagePatterns, int errors,
                int warnings, int thirdPartyPatterns, int ignoredPatterns, List<PacketPanelData.Row> rows) {
            this.status = status;
            this.totalPatterns = totalPatterns;
            this.interfacePatterns = interfacePatterns;
            this.storagePatterns = storagePatterns;
            this.errors = errors;
            this.warnings = warnings;
            this.thirdPartyPatterns = thirdPartyPatterns;
            this.ignoredPatterns = ignoredPatterns;
            this.rows = rows;
        }
    }

    private static volatile Snapshot state = new Snapshot(PacketPanelData.STATUS_NO_NETWORK, 0, 0, 0, 0, 0, 0, 0,
            new ArrayList<PacketPanelData.Row>());

    private ClientPanelState() {
    }

    public static void apply(PacketPanelData packet) {
        state = new Snapshot(packet.status, packet.totalPatterns, packet.interfacePatterns, packet.storagePatterns,
                packet.errors, packet.warnings, packet.thirdPartyPatterns, packet.ignoredPatterns, packet.rows);
    }

    public static Snapshot current() {
        return state;
    }
}
