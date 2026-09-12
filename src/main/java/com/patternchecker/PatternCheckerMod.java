package com.patternchecker;

import net.minecraft.creativetab.CreativeTabs;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

import com.patternchecker.command.CommandPatternCheck;
import com.patternchecker.gui.GuiHandler;
import com.patternchecker.item.ItemPatternCheckerTool;
import com.patternchecker.network.PatternCheckerNetwork;

/**
 * Pattern Checker — port of 3QQQ/PatternChecker to GTNH 2.8.4 (MC 1.7.10 + AE2 Unofficial rv3).
 *
 * Scans every encoded pattern reachable through an ME network (interface
 * pattern slots and ME storage) and reports broken / unusable patterns,
 * patterns with no output or broken inputs, self-loops, patterns that are no
 * longer backed by a recipe, and duplicated pattern encodings. Results are
 * reported via a GUI panel (tool right-click) or to chat (command), with
 * clickable world highlights and in-panel editing / extraction.
 */
@Mod(
        modid = "gtnhpatternchecker",
        name = "GTNH Pattern Checker",
        version = "0.5.2",
        acceptedMinecraftVersions = "[1.7.10]",
        dependencies = "required-after:appliedenergistics2")
public class PatternCheckerMod {

    public static final String MOD_ID = "gtnhpatternchecker";

    @Mod.Instance(MOD_ID)
    public static PatternCheckerMod instance;

    @SidedProxy(
            clientSide = "com.patternchecker.client.ClientProxy",
            serverSide = "com.patternchecker.CommonProxy")
    public static CommonProxy proxy;

    public static ItemPatternCheckerTool toolItem;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        toolItem = new ItemPatternCheckerTool();
        toolItem.setCreativeTab(CreativeTabs.tabMisc);
        GameRegistry.registerItem(toolItem, "pattern_checker_tool");
        proxy.registerRenderers();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PatternCheckerNetwork.init();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
        proxy.registerEventHandlers();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        com.patternchecker.item.ModRecipes.register();
        // Post-init, so NEI (loaded earlier) has already built its handler list.
        proxy.registerNeiHandlers();
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandPatternCheck());
    }
}
