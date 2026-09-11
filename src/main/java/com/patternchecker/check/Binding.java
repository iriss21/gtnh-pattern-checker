package com.patternchecker.check;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Binds a pattern checker tool to one ME network by remembering the position
 * of a block on that network. While bound, scans only check this network.
 */
public final class Binding {

    private static final String TAG_ROOT = "patternchecker_binding";
    private static final String TAG_DIM = "dim";
    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_Z = "z";

    private Binding() {
    }

    public static void bind(ItemStack tool, int dimension, int x, int y, int z) {
        NBTTagCompound tag = tool.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            tool.setTagCompound(tag);
        }
        NBTTagCompound b = new NBTTagCompound();
        b.setInteger(TAG_DIM, dimension);
        b.setInteger(TAG_X, x);
        b.setInteger(TAG_Y, y);
        b.setInteger(TAG_Z, z);
        tag.setTag(TAG_ROOT, b);
    }

    /** @return {dim, x, y, z} or null when unbound. */
    public static int[] get(ItemStack tool) {
        if (tool == null || !tool.hasTagCompound()) {
            return null;
        }
        NBTTagCompound b = tool.getTagCompound().getCompoundTag(TAG_ROOT);
        if (b.hasNoTags()) {
            return null;
        }
        return new int[] { b.getInteger(TAG_DIM), b.getInteger(TAG_X), b.getInteger(TAG_Y), b.getInteger(TAG_Z) };
    }

    public static boolean unbind(ItemStack tool) {
        if (tool == null || !tool.hasTagCompound()) {
            return false;
        }
        NBTTagCompound tag = tool.getTagCompound();
        if (!tag.hasKey(TAG_ROOT)) {
            return false;
        }
        tag.removeTag(TAG_ROOT);
        return true;
    }

    public static boolean isBound(ItemStack tool) {
        return get(tool) != null;
    }

    public static String describe(ItemStack tool) {
        int[] b = get(tool);
        if (b == null) {
            return null;
        }
        return b[1] + ", " + b[2] + ", " + b[3] + " (dim " + b[0] + ")";
    }
}
