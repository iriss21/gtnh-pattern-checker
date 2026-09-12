package com.patternchecker.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;

import com.patternchecker.PatternCheckerMod;

/**
 * The check panel is slot-less; the edit screen exposes the player inventory
 * as real slots. All pattern mutation flows through custom packets.
 */
public class GuiHandler implements IGuiHandler {

    public static final int GUI_PANEL = 0;
    public static final int GUI_EDIT = 1;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_EDIT) {
            return new ContainerPatternEdit(player.inventory);
        }
        return new ContainerEmpty();
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return PatternCheckerMod.proxy.getClientGuiElement(id, player, world, x, y, z);
    }
}
