package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public final class Chat {
    private Chat() {}

    public static void msg(String s) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText("§a[GM] §f" + s));
        }
    }
}
