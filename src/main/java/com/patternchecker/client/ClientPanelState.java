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
        public final int healthyPatterns;
        public final List<PacketPanelData.Row> rows;

        Snapshot(int status, int totalPatterns, int interfacePatterns, int storagePatterns, int errors,
                int warnings, int thirdPartyPatterns, int ignoredPatterns, int healthyPatterns,
                List<PacketPanelData.Row> rows) {
            this.status = status;
            this.totalPatterns = totalPatterns;
            this.interfacePatterns = interfacePatterns;
            this.storagePatterns = storagePatterns;
            this.errors = errors;
            this.warnings = warnings;
            this.thirdPartyPatterns = thirdPartyPatterns;
            this.ignoredPatterns = ignoredPatterns;
            this.healthyPatterns = healthyPatterns;
            this.rows = rows;
        }
    }

    private static volatile Snapshot state = new Snapshot(PacketPanelData.STATUS_NO_NETWORK, 0, 0, 0, 0, 0, 0, 0, 0,
            new ArrayList<PacketPanelData.Row>());

    private ClientPanelState() {
    }

    public static void apply(PacketPanelData packet) {
        if (packet.page > 0) {
            // Follow-up page of a paged scan: append its rows to the accumulating
            // list. Pages are sent in order on one channel, so they arrive in
            // order; the summary fields of page 0 are kept.
            Snapshot current = state;
            List<PacketPanelData.Row> merged = new ArrayList<>(current.rows);
            merged.addAll(packet.rows);
            state = new Snapshot(current.status, current.totalPatterns, current.interfacePatterns,
                    current.storagePatterns, current.errors, current.warnings, current.thirdPartyPatterns,
                    current.ignoredPatterns, current.healthyPatterns, merged);
            return;
        }
        state = new Snapshot(packet.status, packet.totalPatterns, packet.interfacePatterns, packet.storagePatterns,
                packet.errors, packet.warnings, packet.thirdPartyPatterns, packet.ignoredPatterns,
                packet.healthyPatterns, new ArrayList<>(packet.rows));
    }

    public static Snapshot current() {
        return state;
    }
}
