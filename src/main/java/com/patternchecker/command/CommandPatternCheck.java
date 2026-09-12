package com.patternchecker.command;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;

import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;

import com.patternchecker.check.Binding;
import com.patternchecker.check.HighlightStore;
import com.patternchecker.check.PatternCheckService;
import com.patternchecker.check.ScanSession;
import com.patternchecker.item.ItemPatternCheckerTool;
import com.patternchecker.network.PatternCheckerNetwork;

/**
 * /patterncheck scan [all] | bind <x> <y> <z> | unbind | highlight <n> | clear
 *
 * Permission level 0 so every player can audit their own networks.
 */
public class CommandPatternCheck extends CommandBase {

    private static final List<String> SUBS = Arrays
            .asList("scan", "bind", "unbind", "highlight", "clear");

    @Override
    public String getCommandName() {
        return "patterncheck";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/patterncheck scan [all] | bind <x> <y> <z> | unbind | highlight <n> | clear";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBS.toArray(new String[0]));
        }
        if (args.length == 2 && "scan".equals(args[0])) {
            return getListOfStringsMatchingLastWord(args, "all");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentTranslation("patternchecker.msg.playersOnly"));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String sub = args[0];
        if ("scan".equals(sub)) {
            if (args.length >= 2 && "all".equalsIgnoreCase(args[1])) {
                PatternCheckService.scanAll(player);
            } else {
                PatternCheckService.scanForPlayer(player);
            }
            return;
        }

        if ("bind".equals(sub)) {
            if (args.length < 4) {
                throw new WrongUsageException("/patterncheck bind <x> <y> <z>");
            }
            ItemStack held = player.getCurrentEquippedItem();
            if (!(held != null && held.getItem() instanceof ItemPatternCheckerTool)) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.bound.notTool"));
                return;
            }
            int x = parseInt(sender, args[1]);
            int y = parseInt(sender, args[2]);
            int z = parseInt(sender, args[3]);
            WorldServer world = player.getServerForPlayer();
            TileEntity te = world.getTileEntity(x, y, z);
            IGrid grid = null;
            if (te instanceof IGridHost) {
                IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
                grid = node == null ? null : node.getGrid();
            }
            if (grid == null) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.notNetworkBlock"));
                return;
            }
            Binding.bind(held, player.dimension, x, y, z);
            ChatComponentTranslation msg = new ChatComponentTranslation("patternchecker.bound.done",
                    x + ", " + y + ", " + z);
            msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN));
            player.addChatMessage(msg);
            return;
        }

        if ("unbind".equals(sub)) {
            ItemStack held = player.getCurrentEquippedItem();
            if (held != null && held.getItem() instanceof ItemPatternCheckerTool && Binding.unbind(held)) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.bound.cleared"));
            } else {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.bound.notTool"));
            }
            return;
        }

        if ("highlight".equals(sub)) {
            if (args.length < 2) {
                throw new WrongUsageException("/patterncheck highlight <n>");
            }
            ScanSession session = HighlightStore.get(player);
            if (session == null) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.noScan"));
                return;
            }
            int index = parseInt(sender, args[1]);
            if (index < 0 || index >= session.highlightPositions.size()) {
                player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.noHighlightIndex"));
                return;
            }
            int[] pos = session.highlightPositions.get(index);
            PatternCheckerNetwork.sendHighlight(player, session.dimension,
                    new int[][] { pos }, 15);
            player.addChatMessage(new ChatComponentTranslation("patternchecker.msg.highlighted",
                    pos[0] + ", " + pos[1] + ", " + pos[2]));
            return;
        }

        if ("clear".equals(sub)) {
            HighlightStore.clear(player);
            PatternCheckerNetwork.sendHighlight(player, player.dimension, new int[0][], 0);
            return;
        }

        throw new WrongUsageException(getCommandUsage(sender));
    }
}
