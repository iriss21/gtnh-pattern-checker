package com.patternchecker.check;

import java.util.Set;
import java.util.TreeSet;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Content fingerprint of an encoded pattern, used as the identity of an
 * "ignored" pattern.
 *
 * <p>The key is deliberately content based rather than position based: an
 * ignore is meant to say "this pattern is known and I do not want to hear about
 * it again", which applies to every copy of it (AE2FC interfaces, duplicated
 * patterns for parallelism, and so on) and survives the provider being moved.
 *
 * <p>The NBT dump is canonicalised - compound keys are visited in sorted order
 * and the volatile {@code author} tag is skipped - so the same pattern hashes
 * to the same key no matter what order the tag map happens to iterate in on a
 * given JVM run. Stored ignores therefore keep matching across restarts.
 */
public final class PatternKey {

    /** Guards against pathological self-referencing tags. */
    private static final int MAX_DEPTH = 12;

    private PatternKey() {
    }

    /** @return the fingerprint, or null when there is nothing to fingerprint. */
    public static String of(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256);
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null) {
            appendCompound(tag, sb, 0);
        }
        return Item.getIdFromItem(stack.getItem()) + ":" + stack.getItemDamage() + ":" + fnv64(sb);
    }

    private static void appendCompound(NBTTagCompound tag, StringBuilder sb, int depth) {
        sb.append('{');
        if (depth < MAX_DEPTH) {
            Set<String> keys = new TreeSet<>(tag.func_150296_c());
            for (String key : keys) {
                if ("author".equals(key)) {
                    // cosmetic signature AE2 rewrites on every encode
                    continue;
                }
                sb.append(key).append('=');
                appendTag(tag, key, sb, depth);
                sb.append(';');
            }
        }
        sb.append('}');
    }

    private static void appendTag(NBTTagCompound tag, String key, StringBuilder sb, int depth) {
        switch (tag.func_150299_b(key)) {
        case 1:
            sb.append(tag.getByte(key));
            break;
        case 2:
            sb.append(tag.getShort(key));
            break;
        case 3:
            sb.append(tag.getInteger(key));
            break;
        case 4:
            sb.append(tag.getLong(key));
            break;
        case 5:
            sb.append(tag.getFloat(key));
            break;
        case 6:
            sb.append(tag.getDouble(key));
            break;
        case 7: {
            appendInts(sb, tag.getByteArray(key));
            break;
        }
        case 8:
            sb.append(tag.getString(key));
            break;
        case 9:
            appendList(tag.getTag(key), sb, depth + 1);
            break;
        case 10:
            appendCompound(tag.getCompoundTag(key), sb, depth + 1);
            break;
        case 11: {
            appendInts(sb, tag.getIntArray(key));
            break;
        }
        default:
            sb.append('?');
            break;
        }
    }

    private static void appendList(net.minecraft.nbt.NBTBase list, StringBuilder sb, int depth) {
        sb.append('[');
        if (list instanceof NBTTagList && depth < MAX_DEPTH) {
            NBTTagList l = (NBTTagList) list;
            if (l.func_150303_d() == 10) {
                // pattern entries (and item NBT sub-lists) are lists of compounds
                for (int i = 0; i < l.tagCount(); i++) {
                    appendCompound(l.getCompoundTagAt(i), sb, depth + 1);
                }
            } else {
                // primitive / string lists: the tag's own rendering is stable
                sb.append(l.toString());
            }
        }
        sb.append(']');
    }

    private static void appendInts(StringBuilder sb, byte[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
    }

    private static void appendInts(StringBuilder sb, int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
    }

    /** 64-bit FNV-1a, rendered as fixed width hex. */
    private static String fnv64(CharSequence text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return String.format("%016x", hash);
    }
}
