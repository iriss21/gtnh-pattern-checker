package com.patternchecker.client;

import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

/**
 * Draws wireframe boxes around highlighted positions for 15 seconds.
 */
@SideOnly(Side.CLIENT)
public final class HighlightRenderer {

    public HighlightRenderer() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        List<ClientHighlightState.Highlight> all = ClientHighlightState.current();
        if (all.isEmpty()) {
            return;
        }
        if (ClientHighlightState.currentDimension != mc.thePlayer.dimension) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean expired = false;
        for (ClientHighlightState.Highlight h : all) {
            if (h.expireAtMillis < now) {
                expired = true;
            }
        }
        if (expired) {
            // Drop expired entries in a thread-safe way.
            List<ClientHighlightState.Highlight> keep = new java.util.ArrayList<>();
            long t = now;
            for (Iterator<ClientHighlightState.Highlight> it = all.iterator(); it.hasNext();) {
                ClientHighlightState.Highlight h = it.next();
                if (h.expireAtMillis >= t) {
                    keep.add(h);
                }
            }
            if (keep.isEmpty()) {
                ClientHighlightState.apply(Integer.MIN_VALUE, new int[0][], 0);
                return;
            }
            all = keep;
        }

        Entity camera = mc.renderViewEntity != null ? mc.renderViewEntity : mc.thePlayer;
        float pt = event.partialTicks;
        double cx = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * pt;
        double cy = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * pt;
        double cz = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * pt;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.5F);
        GL11.glColor4f(0.25F, 1.0F, 0.35F, 0.85F);

        for (ClientHighlightState.Highlight h : all) {
            GL11.glPushMatrix();
            GL11.glTranslated(h.x - cx, h.y - cy, h.z - cz);
            drawBox(AxisAlignedBB.getBoundingBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D).expand(0.004D, 0.004D, 0.004D));
            GL11.glPopMatrix();
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopAttrib();
    }

    private static void drawBox(AxisAlignedBB box) {
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        // bottom rectangle
        t.addVertex(box.minX, box.minY, box.minZ);
        t.addVertex(box.maxX, box.minY, box.minZ);
        t.addVertex(box.maxX, box.minY, box.minZ);
        t.addVertex(box.maxX, box.minY, box.maxZ);
        t.addVertex(box.maxX, box.minY, box.maxZ);
        t.addVertex(box.minX, box.minY, box.maxZ);
        t.addVertex(box.minX, box.minY, box.maxZ);
        t.addVertex(box.minX, box.minY, box.minZ);
        // top rectangle
        t.addVertex(box.minX, box.maxY, box.minZ);
        t.addVertex(box.maxX, box.maxY, box.minZ);
        t.addVertex(box.maxX, box.maxY, box.minZ);
        t.addVertex(box.maxX, box.maxY, box.maxZ);
        t.addVertex(box.maxX, box.maxY, box.maxZ);
        t.addVertex(box.minX, box.maxY, box.maxZ);
        t.addVertex(box.minX, box.maxY, box.maxZ);
        t.addVertex(box.minX, box.maxY, box.minZ);
        // verticals
        t.addVertex(box.minX, box.minY, box.minZ);
        t.addVertex(box.minX, box.maxY, box.minZ);
        t.addVertex(box.maxX, box.minY, box.minZ);
        t.addVertex(box.maxX, box.maxY, box.minZ);
        t.addVertex(box.maxX, box.minY, box.maxZ);
        t.addVertex(box.maxX, box.maxY, box.maxZ);
        t.addVertex(box.minX, box.minY, box.maxZ);
        t.addVertex(box.minX, box.maxY, box.maxZ);
        t.draw();
    }
}
