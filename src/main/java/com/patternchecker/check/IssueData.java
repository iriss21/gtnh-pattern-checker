package com.patternchecker.check;

/**
 * Structured issue record shared with the GUI panel. The chat lines and the
 * panel are both rendered from these.
 */
public final class IssueData {

    /** Editable target: an interface pattern slot. */
    public static final class EditTarget {

        public final int dim;
        public final int x;
        public final int y;
        public final int z;
        public final int slot;
        public final boolean processing;

        public EditTarget(int dim, int x, int y, int z, int slot, boolean processing) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.slot = slot;
            this.processing = processing;
        }
    }

    public final boolean error;
    public final String name;
    /**
     * Lang key of the pattern kind, appended to the name when present:
     * "（合成样板）" / "（处理样板）". Empty when the pattern could not be decoded.
     */
    public final String kind;
    /** null = no location line (pure group issues like duplicates). */
    public final String locKey;
    public final String locArg;
    public final String issueKey;
    public final String[] args;
    public final int[] pos;
    public final int dim;
    /** Present when the pattern sits in an interface slot (edit/extract possible). */
    public final EditTarget edit;
    /** {@link PatternKey} fingerprint of the pattern; null for group issues. */
    public final String key;
    /** True when the pattern is on the player's ignore list. */
    public final boolean ignored;

    public IssueData(boolean error, String name, String kind, String locKey, String locArg, String issueKey,
            String[] args, int[] pos, int dim, EditTarget edit, String key, boolean ignored) {
        this.error = error;
        this.name = name;
        this.kind = kind;
        this.locKey = locKey;
        this.locArg = locArg;
        this.issueKey = issueKey;
        this.args = args;
        this.pos = pos;
        this.dim = dim;
        this.edit = edit;
        this.key = key;
        this.ignored = ignored;
    }
}
