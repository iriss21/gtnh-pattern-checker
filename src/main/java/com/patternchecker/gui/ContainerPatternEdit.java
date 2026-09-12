package com.patternchecker.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Container for the per-slot pattern editor. Only the player inventory is
 * exposed as real slots (so items can be picked up as ghost-slot sources);
 * the pattern slots themselves are rendered and hit-tested by the GUI.
 *
 * <p>The slot coordinates must stay in sync with the cell backgrounds drawn by
 * {@link com.patternchecker.client.gui.GuiPatternEdit}.
 */
public class ContainerPatternEdit extends Container {

    private static final int INV_TOP = 162;
    private static final int HOTBAR_Y = 216;

    public ContainerPatternEdit(InventoryPlayer inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlotToContainer(new Slot(inv, 9 + row * 9 + col, 38 + col * 18, INV_TOP + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlotToContainer(new Slot(inv, col, 38 + col * 18, HOTBAR_Y));
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotIndex) {
        return null;
    }
}
