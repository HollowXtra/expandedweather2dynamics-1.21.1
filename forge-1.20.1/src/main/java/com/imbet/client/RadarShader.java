package com.imbet.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RegisterShadersEvent;

public final class RadarShader {
    private RadarShader() {
    }

    public static void register(RegisterShadersEvent event) {
    }

    public static RenderType radarPanel() {
        return RenderType.lightning();
    }
}
