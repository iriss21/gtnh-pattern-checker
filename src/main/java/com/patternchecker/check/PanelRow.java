package com.patternchecker.check;

import net.minecraft.item.ItemStack;

/**
 * One panel row: the client-visible issue data plus the server-side original
 * pattern stack used for edit/extract validation.
 */
public final class PanelRow {

    public final IssueData data;
    /** Copy of the pattern in the interface slot at scan time; null for non-edit rows. */
    public final ItemStack original;

    public PanelRow(IssueData data, ItemStack original) {
        this.data = data;
        this.original = original;
    }
}
