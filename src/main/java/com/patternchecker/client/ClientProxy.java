package com.patternchecker.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.patternchecker.CommonProxy;
import com.patternchecker.client.gui.GuiPatternCheckPanel;
import com.patternchecker.client.gui.GuiPatternEdit;
import com.patternchecker.gui.ContainerEmpty;
import com.patternchecker.gui.ContainerPatternEdit;
import com.patternchecker.gui.GuiHandler;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void registerRenderers() {
        // Registers itself to the Forge event bus.
        new HighlightRenderer();
        // Drains client work posted from packet handlers back onto the client thread.
        new ClientThreadQueue();
    }

    /**
     * Hooks the check panel and the editor into NEI (the 1.7.10 recipe viewer),
     * which is what GTNH actually ships: it provides the item-panel drag &amp; drop
     * and the "keep the panel out of my GUI" callback.
     *
     * <p>The relay class references NEI types, so it is loaded reflectively and
     * only when NEI is present — the mod keeps working without it.
     */
    @Override
    public void registerNeiHandlers() {
        if (!Loader.isModLoaded("NotEnoughItems")) {
            return;
        }
        try {
            Class.forName("com.patternchecker.client.nei.PatternCheckNei").getMethod("register").invoke(null);
        } catch (Throwable t) {
            FMLLog.warning("[GTNHPatternChecker] NEI integration disabled: " + t);
        }
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GuiHandler.GUI_PANEL) {
            return new GuiPatternCheckPanel(new ContainerEmpty());
        }
        if (id == GuiHandler.GUI_EDIT) {
            return new GuiPatternEdit(com.patternchecker.client.ClientEditState.takeOrEmpty(),
                    new ContainerPatternEdit(player.inventory));
        }
        return null;
    }
}
