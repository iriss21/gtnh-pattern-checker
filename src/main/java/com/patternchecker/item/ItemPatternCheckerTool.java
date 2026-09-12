package com.patternchecker.item;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;

import com.patternchecker.check.Binding;
import com.patternchecker.check.PatternCheckService;

/**
 * The pattern checker tool.
 *
 * Right-click a block that belongs to an ME network: bind the tool to that
 * network. Right-click air: scan the bound network (or the nearest network if
 * unbound) and report results to chat. Sneak + right-click: unbind.
 */
public class ItemPatternCheckerTool extends Item {

    public ItemPatternCheckerTool() {
        super();
        setMaxStackSize(1);
        setUnlocalizedName("gtnhpatternchecker.pattern_checker_tool");
        setTextureName("gtnhpatternchecker:pattern_checker_tool");
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) {
            if (!world.isRemote) {
                if (Binding.unbind(stack)) {
                    player.addChatMessage(new ChatComponentTranslation("patternchecker.bound.cleared"));
                }
            }
            return true;
        }

        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IGridHost)) {
            return false;
        }
        IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
        if (node == null) {
            return false;
        }
        IGrid grid = node.getGrid();
        if (grid == null) {
            return false;
        }
        if (!world.isRemote) {
            Binding.bind(stack, world.provider.dimensionId, x, y, z);
            player.addChatMessage(new ChatComponentTranslation("patternchecker.bound.done",
                    x + ", " + y + ", " + z));
        }
        return true;
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (!world.isRemote && player instanceof EntityPlayerMP) {
            EntityPlayerMP p = (EntityPlayerMP) player;
            if (player.isSneaking()) {
                if (Binding.unbind(stack)) {
                    p.addChatMessage(new ChatComponentTranslation("patternchecker.bound.cleared"));
                }
            } else {
                p.openGui(com.patternchecker.PatternCheckerMod.instance,
                        com.patternchecker.gui.GuiHandler.GUI_PANEL, world,
                        (int) p.posX, (int) p.posY, (int) p.posZ);
            }
        }
        return stack;
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip.add(StatCollector.translateToLocal("item.gtnhpatternchecker.pattern_checker_tool.tooltip"));
        String bound = Binding.describe(stack);
        if (bound != null) {
            tooltip.add(StatCollector.translateToLocalFormatted("patternchecker.bound.status", bound));
        }
    }
}
