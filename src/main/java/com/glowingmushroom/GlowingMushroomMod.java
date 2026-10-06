package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.File;

@Mod(modid = "glowingmushroomauto", name = "Glowing Mushroom Auto", version = "1.0.0", clientSideOnly = true)
public class GlowingMushroomMod {
    public Route route;
    public MushroomTracker tracker;
    public MacroController controller;
    private KeyBinding toggleKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        route = new Route(new File(Minecraft.getMinecraft().mcDataDir, "config/glowingmushroom_route.json"));
        tracker = new MushroomTracker();
        controller = new MacroController(route, tracker);

        toggleKey = new KeyBinding("Toggle Glowing Mushroom Auto", Keyboard.KEY_J, "Glowing Mushroom Auto");
        ClientRegistry.registerKeyBinding(toggleKey);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(tracker);
        MinecraftForge.EVENT_BUS.register(controller);
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(this);
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(tracker);
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(controller);
        ClientCommandHandler.instance.registerCommand(new GmCommand(this));
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (!toggleKey.isPressed()) return;
        if (controller.isRunning()) {
            controller.stop();
            Chat.msg("Stopped.");
        } else if (route.size() > 0) {
            controller.start();
            Chat.msg("Started.");
        } else {
            Chat.msg("Record a route first with /gm add.");
        }
    }

    /** Draws boxes around tracked mushrooms and the route, like SkyHanni's highlighters. */
    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        RenderManager rm = mc.getRenderManager();
        double vx = rm.viewerPosX, vy = rm.viewerPosY, vz = rm.viewerPosZ;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GL11.glLineWidth(2f);

        for (BlockPos bp : tracker.mushrooms.keySet()) {
            boolean cur = bp.equals(controller.currentTarget());
            AxisAlignedBB bb = new AxisAlignedBB(bp, bp.add(1, 1, 1)).offset(-vx, -vy, -vz);
            RenderGlobal.drawOutlinedBoundingBox(bb, cur ? 255 : 50, cur ? 50 : 255, 50, 255);
        }
        for (int i = 0; i < route.size(); i++) {
            Vec3 v = route.get(i);
            AxisAlignedBB bb = new AxisAlignedBB(v.xCoord - 0.2, v.yCoord, v.zCoord - 0.2,
                    v.xCoord + 0.2, v.yCoord + 0.4, v.zCoord + 0.2).offset(-vx, -vy, -vz);
            boolean next = controller.isRunning() && i == controller.waypointIndex();
            RenderGlobal.drawOutlinedBoundingBox(bb, 80, 150, 255, next ? 255 : 120);
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }
}
