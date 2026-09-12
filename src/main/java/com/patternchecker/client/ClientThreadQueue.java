package com.patternchecker.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-side counterpart of {@link com.patternchecker.network.ServerTaskQueue}:
 * FML delivers packets on the Netty thread, so anything that touches the client
 * game state (notably opening a screen) is queued here and drained at the end of
 * the next client tick.
 */
@SideOnly(Side.CLIENT)
public final class ClientThreadQueue {

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    /** Registering the instance is enough; it hooks itself onto the FML bus. */
    public ClientThreadQueue() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /** Safe to call from any thread. */
    public static void submit(Runnable task) {
        PENDING.add(task);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            task.run();
        }
    }
}
