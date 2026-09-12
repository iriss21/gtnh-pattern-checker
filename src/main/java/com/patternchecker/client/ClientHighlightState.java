package com.patternchecker.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side highlight state. Plain data only (no Minecraft client classes)
 * so the packet handler can touch it from any thread; the renderer reads the
 * volatile snapshot every frame and drops expired entries.
 */
public final class ClientHighlightState {

    public static final class Highlight {

        public final int x;
        public final int y;
        public final int z;
        public final long expireAtMillis;

        Highlight(int x, int y, int z, long expireAtMillis) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.expireAtMillis = expireAtMillis;
        }
    }

    private static volatile List<Highlight> highlights = new ArrayList<>();

    private ClientHighlightState() {
    }

    /** Thread-safe; called from the netty thread via the packet handler. */
    public static void apply(int dimension, int[][] positions, int durationSeconds) {
        List<Highlight> next = new ArrayList<>();
        if (positions != null && durationSeconds > 0) {
            long expire = System.currentTimeMillis() + durationSeconds * 1000L;
            for (int[] p : positions) {
                if (p != null && p.length >= 3) {
                    next.add(new Highlight(p[0], p[1], p[2], expire));
                }
            }
        }
        highlights = next;
        // Dimension is checked by the renderer against the client world.
        currentDimension = dimension;
    }

    public static volatile int currentDimension = Integer.MIN_VALUE;

    public static List<Highlight> current() {
        return highlights;
    }
}
