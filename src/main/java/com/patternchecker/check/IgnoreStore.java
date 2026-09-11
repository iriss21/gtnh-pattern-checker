package com.patternchecker.check;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.Loader;

/**
 * Per-player set of ignored pattern fingerprints (see {@link PatternKey}).
 *
 * <p>Ignored patterns are still scanned and still counted as patterns, but
 * their issues are neither reported in chat nor counted in the error/warning
 * totals, and the panel keeps them out of the list until the player asks to see
 * them (so they can be un-ignored again).
 *
 * <p>The set lives in {@code config/patternchecker-ignored.txt} (one
 * {@code player<TAB>fingerprint} line each), so it survives a restart. File
 * access is best effort: if the file cannot be read or written the feature
 * still works for the running session.
 */
public final class IgnoreStore {

    private static final String FILE_NAME = "patternchecker-ignored.txt";

    private static final Map<String, Set<String>> BY_PLAYER = new ConcurrentHashMap<>();

    private static final Object IO_LOCK = new Object();

    private static volatile boolean loaded;

    private IgnoreStore() {
    }

    /** The live set for a player; never null, safe to read while the player scans. */
    public static Set<String> keys(EntityPlayerMP player) {
        ensureLoaded();
        return keysOf(player.getCommandSenderName());
    }

    public static boolean isIgnored(String playerName, String key) {
        if (playerName == null || key == null) {
            return false;
        }
        ensureLoaded();
        return keysOf(playerName).contains(key);
    }

    /** @return true when the fingerprint was not ignored before. */
    public static boolean add(String playerName, String key) {
        if (playerName == null || key == null) {
            return false;
        }
        ensureLoaded();
        if (!keysOf(playerName).add(key)) {
            return false;
        }
        save();
        return true;
    }

    /** @return true when the fingerprint was ignored before. */
    public static boolean remove(String playerName, String key) {
        if (playerName == null || key == null) {
            return false;
        }
        ensureLoaded();
        if (!keysOf(playerName).remove(key)) {
            return false;
        }
        save();
        return true;
    }

    public static int count(String playerName) {
        if (playerName == null) {
            return 0;
        }
        ensureLoaded();
        return keysOf(playerName).size();
    }

    public static void clear(String playerName) {
        if (playerName == null) {
            return;
        }
        ensureLoaded();
        if (keysOf(playerName).isEmpty()) {
            return;
        }
        keysOf(playerName).clear();
        save();
    }

    private static Set<String> keysOf(String playerName) {
        Set<String> set = BY_PLAYER.get(playerName);
        if (set != null) {
            return set;
        }
        Set<String> created = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        Set<String> existing = BY_PLAYER.putIfAbsent(playerName, created);
        return existing != null ? existing : created;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (IO_LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
            load();
        }
    }

    private static File file() {
        try {
            File dir = Loader.instance().getConfigDir();
            return dir == null ? null : new File(dir, FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void load() {
        File f = file();
        if (f == null || !f.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) {
                    continue;
                }
                keysOf(line.substring(0, tab)).add(line.substring(tab + 1));
            }
        } catch (Throwable t) {
            // unreadable list: run with an empty one
        } finally {
            close(reader);
        }
    }

    private static void save() {
        File f = file();
        if (f == null) {
            return;
        }
        synchronized (IO_LOCK) {
            PrintWriter out = null;
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                out = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8")));
                out.println("# PatternChecker ignored patterns: <player>\\t<fingerprint>");
                for (Map.Entry<String, Set<String>> e : BY_PLAYER.entrySet()) {
                    for (String key : e.getValue()) {
                        out.print(e.getKey());
                        out.print('\t');
                        out.println(key);
                    }
                }
            } catch (Throwable t) {
                // read-only instance or similar: keep the in-memory state
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        }
    }

    private static void close(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (Throwable ignore) {
            // nothing useful to do
        }
    }
}
