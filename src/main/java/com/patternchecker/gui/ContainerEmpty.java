package com.patternchecker.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

/**
 * Slot-less container backing the checker panel and edit screen.
 */
public class ContainerEmpty extends Container {

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }
}
