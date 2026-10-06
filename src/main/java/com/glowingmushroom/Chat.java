package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class Chat {
    private Chat() {}

    public static void msg(String s) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal("§a[GM] §f" + s), false);
    }
}
