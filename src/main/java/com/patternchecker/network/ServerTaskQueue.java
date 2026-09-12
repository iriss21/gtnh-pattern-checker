package com.patternchecker.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * FML 1.7.10 hands {@code SimpleNetworkWrapper} messages to the Netty thread.
 * Everything this mod does with a packet — walking the ME network, reading or
 * writing an interface pattern slot, opening a GUI — belongs on the server
 * thread, so the handlers post their work here and it runs at the end of the
 * next server tick.
 *
 * <p>Register {@link #INSTANCE} on the FML event bus.
 */
public final class ServerTaskQueue {

    public static final ServerTaskQueue INSTANCE = new ServerTaskQueue();

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private ServerTaskQueue() {
    }

    /** Safe to call from any thread. */
    public static void submit(Runnable task) {
        PENDING.add(task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                FMLLog.warning("[GTNHPatternChecker] deferred packet task failed: " + t);
            }
        }
    }
}
