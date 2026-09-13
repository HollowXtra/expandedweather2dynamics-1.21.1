package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.IOException;

public final class TornadoInflowShader {
    private static ShaderInstance shader;
    private static final RenderStateShard.ShaderStateShard SHADER_STATE = new RenderStateShard.ShaderStateShard(() -> shader);
    private static final RenderType TORNADO_INFLOW = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_tornado_inflow",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(SHADER_STATE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)
    );

    private TornadoInflowShader() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_inflow");
        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR), loaded -> shader = loaded);
    }

    public static RenderType renderType() {
        return TORNADO_INFLOW;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || shader == null) {
            return;
        }
        float whiteout = Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get() ? TornadoFearClient.whiteoutIntensity() : 0.0F;
        // The inflow overlay is opt-in only. It used to be force-activated by the whiteout, which
        // veiled the whole screen across the entire (potentially hundreds-of-blocks-wide) damage
        // path and hid the tornado itself - so it could never be turned off. Now it only draws when
        // the player explicitly enables inflowShaderOverlay; the whiteout keeps its fog/color work.
        if (!Config.TORNADO_IMMERSION_ENABLED.get()
                || !Config.TORNADO_IMMERSION_INSIDE_CORE_EFFECTS.get()
                || !Config.TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY.get()) {
            return;
        }
        // With an Iris shaderpack active the custom program is replaced by Iris's fallback; skip rather than render wrong.
        if (RenderingCompat.isShaderPackActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (minecraft.level == null || player == null) {
            return;
        }

        float path = TornadoFearClient.insideDamagePathIntensity();
        float core = TornadoFearClient.insideCoreIntensity();
        float eye = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? TornadoFearClient.insideEyeIntensity() : 0.0F;
        float opacity = Config.TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY.get().floatValue();
        if (whiteout > 0.01F) {
            opacity = Math.max(opacity, (float) (Config.TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY.get() * whiteout));
        }
        float strength = Mth.clamp((path * 0.58F + core * 0.72F + whiteout * 0.42F) * opacity, 0.0F, 1.0F);
        if (strength <= 0.01F) {
            return;
        }

        Vec3 wind = TornadoFearClient.activeWindVector();
        if (wind.horizontalDistanceSqr() < 1.0E-5D) {
            wind = TornadoFearClient.activeCenter().subtract(player.position());
        }
        double worldAngle = Math.atan2(wind.z, wind.x);
        double yaw = Math.toRadians(player.getYRot());
        double screenAngle = worldAngle - yaw + Math.PI * 0.5D;
        float windX = (float) Math.cos(screenAngle);
        float windY = (float) Math.sin(screenAngle);
        float time = (minecraft.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0F;

        setUniform("GameTime", time);
        setUniform("ScreenSize", minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        setUniform("DamagePathIntensity", path);
        setUniform("CoreIntensity", core);
        setUniform("EyeIntensity", eye);
        setUniform("WindDirection", windX, windY);
        setUniform("EyeClearRadius", Config.TORNADO_IMMERSION_EYE_CLEAR_RADIUS.get().floatValue());
        setUniform("Opacity", opacity);
        setUniform("WhiteoutIntensity", whiteout);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 1.0F).setColor(255, 255, 255, 255);
        builder.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 1.0F).setColor(255, 255, 255, 255);
        builder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 0.0F).setColor(255, 255, 255, 255);
        builder.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 0.0F).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void setUniform(String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniform(String name, float first, float second) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second);
        }
    }
}
