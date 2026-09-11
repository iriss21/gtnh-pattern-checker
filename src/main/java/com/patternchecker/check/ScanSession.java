package com.patternchecker.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

/**
 * Result of one scan run: chat lines plus the structured panel rows and the
 * positions that can be highlighted via /patterncheck highlight.
 */
public final class ScanSession {

    public final int dimension;

    /** Player the scan belongs to; also selects the ignore list. */
    public final String playerName;

    /** Fingerprints of the patterns this player has ignored. */
    public final Set<String> ignored;

    /** Packed [x, y, z] triples referenced by chat highlight links. */
    public final List<int[]> highlightPositions = new ArrayList<>();

    public final List<IChatComponent> lines = new ArrayList<>();

    /** Structured issues for the GUI panel (with server-side originals). */
    public final List<PanelRow> rows = new ArrayList<>();

    public int totalPatterns;
    public int interfacePatterns;
    public int storagePatterns;
    public int errors;
    public int warnings;
    /**
     * Patterns that carry their own NBT schema instead of AE2's {@code in}/{@code out}
     * lists (wildcardpattern templates and similar). They are counted but not
     * validated slot by slot, because only the owning mod knows how to expand them.
     */
    public int thirdPartyPatterns;
    /**
     * Issues that were suppressed because the pattern is on this player's ignore
     * list. They are kept out of {@link #errors}/{@link #warnings} and out of the
     * chat output, but their rows still reach the panel (flagged as ignored) so
     * the player can un-ignore them again.
     */
    public int ignoredIssues;

    public ScanSession(EntityPlayerMP player, int dimension) {
        this.dimension = dimension;
        this.playerName = player == null ? "" : player.getCommandSenderName();
        this.ignored = player == null ? Collections.<String>emptySet() : IgnoreStore.keys(player);
    }

    public int issueCount() {
        return errors + warnings;
    }

    /** True when this pattern fingerprint is on the player's ignore list. */
    public boolean isIgnored(String key) {
        return key != null && this.ignored.contains(key);
    }
}
