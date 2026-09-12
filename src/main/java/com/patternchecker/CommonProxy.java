package com.patternchecker;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

import com.patternchecker.check.EditStore;
import com.patternchecker.check.HighlightStore;
import com.patternchecker.check.PanelStore;
import com.patternchecker.network.ServerTaskQueue;

/**
 * Common proxy. Client-side rendering and GUI construction happen in ClientProxy.
 */
public class CommonProxy {

    public void registerRenderers() {
    }

    /**
     * Server-side wiring: drops the per-player scan / edit / highlight sessions
     * when somebody disconnects, so the stores cannot grow without bound.
     */
    public void registerEventHandlers() {
        FMLCommonHandler.instance().bus().register(this);
        FMLCommonHandler.instance().bus().register(ServerTaskQueue.INSTANCE);
    }

    /** Client-only NEI integration; overridden by ClientProxy. */
    public void registerNeiHandlers() {
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer player = event.player;
        if (player == null) {
            return;
        }
        String name = player.getCommandSenderName();
        PanelStore.clear(name);
        EditStore.clear(name);
        HighlightStore.clear(name);
    }

    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
