package com.patternchecker.client.nei;

import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.API;
import codechicken.nei.api.INEIGuiAdapter;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.guihook.GuiContainerManager;
import codechicken.nei.guihook.IContainerObjectHandler;
import codechicken.nei.recipe.IRecipeHandler;

import com.patternchecker.client.ClientProxy;
import com.patternchecker.client.gui.GuiPatternCheckPanel;
import com.patternchecker.client.gui.GuiPatternEdit;

/**
 * NEI relay for the two Pattern Checker screens.
 *
 * <p>GTNH 2.8.4 is a Minecraft 1.7.10 pack, so the recipe viewer is
 * NotEnoughItems (NEI), not JEI. This class provides the hooks that make the
 * editor feel like a native crafting GUI:
 *
 * <ul>
 * <li>{@code handleDragNDrop} — items dragged out of the NEI item panel land in the
 * ghost slots;</li>
 * <li>{@code registerGuiOverlay} — pressing R/U draws the recipe aligned with the 3x3
 * grid instead of in the GUI's top-left corner;</li>
 * <li>{@code registerGuiOverlayHandler} — the overlay's transfer button fills the grid
 * with the recipe being shown;</li>
 * <li>{@code getStackUnderMouse} (IContainerObjectHandler) — the hovered ghost slot
 * reports its stack, which lets NEI's whole shortcut chain run on the editor: R
 * recipe, U usage, A bookmark, copy name, ... (bindings read from the NEI config).</li>
 * </ul>
 *
 * <p>It references NEI types, so it is only loaded through {@code Class.forName} from
 * {@link com.patternchecker.client.ClientProxy#registerNeiHandlers()} after a
 * {@code Loader.isModLoaded("NotEnoughItems")} check — the same approach AE2 uses for
 * its own NEI module. Without NEI the rest of the mod is unaffected.
 */
public final class PatternCheckNei extends INEIGuiAdapter implements IContainerObjectHandler {

    /**
     * NEI positions a recipe handler's items relative to this origin: the vanilla
     * crafting GUI is registered with offset (5, 11) and its 3x3 grid sits at (30, 17),
     * so a handler's grid origin is (25, 6). Subtracting it from our own grid origin
     * makes the overlay land exactly on the ghost slots.
     */
    private static final int NEI_CRAFTING_ORIGIN_X = 25;
    private static final int NEI_CRAFTING_ORIGIN_Y = 6;
    private static final String CRAFTING_OVERLAY = "crafting";

    private static PatternCheckNei instance;

    private PatternCheckNei() {
    }

    /** Idempotent; called once from the client proxy during post-init. */
    public static void register() {
        if (instance == null) {
            instance = new PatternCheckNei();
            API.registerNEIGuiHandler(instance);
            // object handlers are asked for the stack under the mouse before NEI
            // falls back to real container slots; that is what lights up the
            // shortcut chain (R/U/A/...) on the editor's ghost slots.
            GuiContainerManager.addObjectHandler(instance);
            registerOverlay();
            ClientProxy.neiKeyHintProvider = PatternCheckNei::keyHintLine;
        }
    }

    private static void registerOverlay() {
        API.registerGuiOverlay(GuiPatternEdit.class, CRAFTING_OVERLAY,
                GuiPatternEdit.GRID_ORIGIN_X - NEI_CRAFTING_ORIGIN_X,
                GuiPatternEdit.GRID_ORIGIN_Y - NEI_CRAFTING_ORIGIN_Y);
        API.registerGuiOverlayHandler(GuiPatternEdit.class, new OverlayTransfer(), CRAFTING_OVERLAY);
    }

    /** Tooltip line naming the actually bound NEI keys, or "" when they resolve to nothing. */
    private static String keyHintLine() {
        String recipe = keyName("gui.recipe");
        String usage = keyName("gui.usage");
        String bookmark = keyName("gui.bookmark");
        if (recipe.isEmpty() || usage.isEmpty() || bookmark.isEmpty()) {
            return "";
        }
        return net.minecraft.client.resources.I18n.format("patternchecker.edit.neiKeys",
                recipe, usage, bookmark);
    }

    private static String keyName(String binding) {
        int code = NEIClientConfig.getKeyBinding(binding);
        return code >= 0 ? Keyboard.getKeyName(code) : "";
    }

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int mouseX, int mouseY, ItemStack draggedStack, int button) {
        if (gui instanceof GuiPatternEdit) {
            return ((GuiPatternEdit) gui).handleNeiDrag(mouseX, mouseY, draggedStack, button);
        }
        return false;
    }

    @Override
    public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) {
        if (gui instanceof GuiPatternEdit) {
            return ((GuiPatternEdit) gui).isRegionOverGui(x, y, w, h);
        }
        if (gui instanceof GuiPatternCheckPanel) {
            return ((GuiPatternCheckPanel) gui).isRegionOverGui(x, y, w, h);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // IContainerObjectHandler: the bridge that lets NEI's shortcut chain
    // (lastKeyTyped -> getStackMouseOver -> ShortcutInputHandler.handleKeyEvent)
    // see the editor's ghost slots. Neutral implementations everywhere else.
    // ------------------------------------------------------------------

    @Override
    public ItemStack getStackUnderMouse(GuiContainer gui, int mouseX, int mouseY) {
        return gui instanceof GuiPatternEdit ? ((GuiPatternEdit) gui).getHoverStack(mouseX, mouseY) : null;
    }

    @Override
    public boolean objectUnderMouse(GuiContainer gui, int mouseX, int mouseY) {
        // False: the vanilla slot behaviour must keep working untouched; the stack
        // we hand out is only for NEI's key/tooltip chain.
        return false;
    }

    @Override
    public boolean shouldShowTooltip(GuiContainer gui) {
        // False for the editor: the editor paints its own ghost-slot tooltip (name
        // + amount + key hints); a second NEI tooltip would render on top of it.
        return !(gui instanceof GuiPatternEdit);
    }

    @Override
    public void guiTick(GuiContainer gui) {
    }

    @Override
    public void refresh(GuiContainer gui) {
    }

    @Override
    public void load(GuiContainer gui) {
    }

    /** Copies the recipe NEI is currently showing into the editor's ghost slots. */
    private static final class OverlayTransfer implements IOverlayHandler {

        private static final int GRID_PITCH = 18;

        @Override
        public void overlayRecipe(GuiContainer gui, IRecipeHandler handler, int recipeIndex, boolean shift) {
            if (!(gui instanceof GuiPatternEdit) || handler == null) {
                return;
            }
            ItemStack[] grid = new ItemStack[9];
            List<PositionedStack> ingredients = handler.getIngredientStacks(recipeIndex);
            if (ingredients != null) {
                for (PositionedStack positioned : ingredients) {
                    place(grid, positioned);
                }
            }
            PositionedStack result = handler.getResultStack(recipeIndex);
            ItemStack output = result == null || result.item == null ? null : result.item.copy();
            ((GuiPatternEdit) gui).fillFromRecipe(grid, output);
        }

        /**
         * Maps one recipe stack onto the 3x3 grid from its overlay position, so shaped
         * recipes keep their layout. A handler with a different geometry (a machine
         * recipe, say) contributes nothing rather than filling the wrong slots.
         */
        private static void place(ItemStack[] grid, PositionedStack positioned) {
            if (positioned == null || positioned.item == null) {
                return;
            }
            int col = Math.round((positioned.relx - NEI_CRAFTING_ORIGIN_X) / (float) GRID_PITCH);
            int row = Math.round((positioned.rely - NEI_CRAFTING_ORIGIN_Y) / (float) GRID_PITCH);
            if (col < 0 || col > 2 || row < 0 || row > 2) {
                return;
            }
            int index = row * 3 + col;
            if (grid[index] == null) {
                grid[index] = positioned.item.copy();
            }
        }
    }
}
