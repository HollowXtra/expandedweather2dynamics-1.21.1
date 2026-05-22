package com.imbet.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;

public final class TornadoInflowShader {
    private TornadoInflowShader() {
    }

    public static void register(RegisterShadersEvent event) {
    }

    public static RenderType renderType() {
        return RenderType.lightning();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
    }
}
