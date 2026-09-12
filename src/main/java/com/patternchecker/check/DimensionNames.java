package com.patternchecker.check;

import net.minecraft.util.StatCollector;

import net.minecraftforge.common.DimensionManager;

/**
 * Dimension labels for the panel and the editor.
 *
 * <p>The client cannot resolve a name for every dimension (it only carries the
 * provider of the dimension it is in), and the server must not translate, so
 * the server sends both the id and the provider's own name. The client prefers
 * its own translation for the well known ids and otherwise shows the name the
 * server reported.
 */
public final class DimensionNames {

    /** Provider names never change, and instantiating one per row would be waste. */
    private static final java.util.Map<Integer, String> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private DimensionNames() {
    }

    /** Server side: the provider's name, or "DIM&lt;id&gt;" when there is none. */
    public static String serverName(int dim) {
        String cached = CACHE.get(Integer.valueOf(dim));
        if (cached != null) {
            return cached;
        }
        String name = resolve(dim);
        CACHE.put(Integer.valueOf(dim), name);
        return name;
    }

    private static String resolve(int dim) {
        if (dim == 0) {
            return "Overworld";
        }
        if (dim == -1) {
            return "Nether";
        }
        if (dim == 1) {
            return "The End";
        }
        try {
            if (DimensionManager.isDimensionRegistered(dim)) {
                net.minecraft.world.WorldProvider provider = DimensionManager.createProviderFor(dim);
                if (provider != null) {
                    String name = provider.getDimensionName();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable t) {
            // providers without a name, or ones that blow up while instantiating
        }
        return "DIM" + dim;
    }

    /**
     * Either side: localized label for a dimension. On the server
     * {@code StatCollector} has no language files, so this degrades to the name
     * the server reported - which is exactly what gets sent to the client.
     */
    public static String label(int dim, String serverName) {
        String key = "patternchecker.dim." + dim;
        String translated = StatCollector.translateToLocal(key);
        if (translated != null && !translated.equals(key)) {
            return translated;
        }
        if (serverName != null && !serverName.isEmpty()) {
            return serverName;
        }
        return "DIM" + dim;
    }
}
