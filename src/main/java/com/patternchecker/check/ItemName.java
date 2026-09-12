package com.patternchecker.check;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Localizable item names.
 *
 * <p>The scan runs on the server, and a dedicated server has no language files
 * loaded, so any name produced there is whatever the item's
 * {@code getItemStackDisplayName} yields without translations (English, or a
 * raw key). Names are therefore never sent as final text: the server sends a
 * compact descriptor and the client - which does have the language pack -
 * rebuilds the stack and asks for its localized name.
 *
 * <p>Wire format of one name (control characters keep it unambiguous, and a
 * plain string without any separator is passed straight through):
 *
 * <pre>
 *   &lt;registry name&gt; \u0001 &lt;damage&gt; \u0001 &lt;server side fallback&gt; \u0001 &lt;nbt, base64&gt;
 * </pre>
 *
 * <p>The NBT field carries the stack tag when it is small, so the client can
 * rebuild a faithful stack: AE2FC's fluid drops are named after the fluid in
 * their tag, and without it they render as "???". Larger tags (an encoded
 * pattern's own data) are dropped - both to keep the packet small and because
 * such names do not depend on NBT anyway.
 *
 * <p>Several names in one argument (e.g. the "inputs not available" list) are
 * joined with {@code \u0002}.
 */
public final class ItemName {

    private static final char FIELD_SEP = '\u0001';
    private static final char LIST_SEP = '\u0002';

    /** Serialized-size budget for the NBT field; bigger tags are not sent. */
    private static final int MAX_NBT_CHARS = 320;

    private ItemName() {
    }

    /** Server side: descriptor for one stack. */
    public static String encode(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        String registry = null;
        try {
            registry = (String) Item.itemRegistry.getNameForObject(stack.getItem());
        } catch (Throwable t) {
            // unregistered item: fall back to the display name alone
        }
        String fallback = stack.getDisplayName();
        if (registry == null || registry.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        return registry + FIELD_SEP + stack.getItemDamage() + FIELD_SEP + (fallback == null ? "" : fallback)
                + FIELD_SEP + encodeNbt(stack);
    }

    /** Server side: several descriptors as one argument. */
    public static String join(List<String> encoded) {
        StringBuilder sb = new StringBuilder();
        for (String e : encoded) {
            if (e == null || e.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(LIST_SEP);
            }
            sb.append(e);
        }
        return sb.toString();
    }

    /**
     * Server side: the untranslated text, for the chat path (chat lines are
     * plain strings, so they cannot be localized per recipient).
     */
    public static String plain(String arg) {
        if (arg == null) {
            return "";
        }
        List<String> parts = splitList(arg);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(plainOne(p));
        }
        return sb.toString();
    }

    /** Server side: {@link #plain} for a whole argument array. */
    public static String[] plainAll(String[] args) {
        if (args == null) {
            return new String[0];
        }
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = plain(args[i]);
        }
        return out;
    }

    /** Client side: localized text for one argument (single name or a list). */
    public static String resolve(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        List<String> parts = splitList(arg);
        if (parts.size() == 1) {
            return resolveOne(parts.get(0));
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String n = resolveOne(p);
            if (n.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    /** Client side: {@link #resolve} for a whole argument array. */
    public static String[] resolveAll(String[] args) {
        if (args == null) {
            return new String[0];
        }
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = resolve(args[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Client side: rebuilds the stack so mods that name items by NBT (AE2FC's
     * fluid drops, for one) produce the same name they do in-world.
     */
    private static String resolveOne(String encoded) {
        String[] fields = splitFields(encoded);
        if (fields.length < 3) {
            return fields[fields.length - 1];
        }
        String registry = fields[0];
        String fallback = fields[2];
        if (registry.isEmpty()) {
            return fallback;
        }
        try {
            Object o = Item.itemRegistry.getObject(registry);
            if (o instanceof Item) {
                int damage = 0;
                try {
                    damage = Integer.parseInt(fields[1]);
                } catch (NumberFormatException ignore) {
                    // keep 0
                }
                ItemStack stack = new ItemStack((Item) o, 1, damage);
                NBTTagCompound tag = decodeNbt(fields.length >= 4 ? fields[3] : "");
                if (tag != null) {
                    stack.setTagCompound(tag);
                }
                String name = stack.getDisplayName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable t) {
            // unknown item on this side: fall back to what the server sent
        }
        return fallback;
    }

    private static String plainOne(String encoded) {
        String[] fields = splitFields(encoded);
        if (fields.length >= 3) {
            return fields[2];
        }
        return fields[fields.length - 1];
    }

    private static String encodeNbt(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || tag.hasNoTags()) {
            return "";
        }
        try {
            if (tag.toString().length() > MAX_NBT_CHARS) {
                return "";
            }
            return Base64.getEncoder().encodeToString(CompressedStreamTools.compress(tag));
        } catch (Throwable t) {
            return "";
        }
    }

    private static NBTTagCompound decodeNbt(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(base64);
            return CompressedStreamTools.readCompressed(new ByteArrayInputStream(packed));
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String> splitList(String arg) {
        List<String> out = new ArrayList<>(2);
        int start = 0;
        for (int i = 0; i <= arg.length(); i++) {
            if (i == arg.length() || arg.charAt(i) == LIST_SEP) {
                if (i > start) {
                    out.add(arg.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (out.isEmpty()) {
            out.add(arg);
        }
        return out;
    }

    private static String[] splitFields(String encoded) {
        List<String> out = new ArrayList<>(4);
        int start = 0;
        for (int i = 0; i <= encoded.length(); i++) {
            if (i == encoded.length() || encoded.charAt(i) == FIELD_SEP) {
                out.add(encoded.substring(start, i));
                start = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }
}
