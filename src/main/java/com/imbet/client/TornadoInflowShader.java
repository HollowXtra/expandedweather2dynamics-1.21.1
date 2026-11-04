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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        // Classic look: 1.12.2 veiled nothing. There is no inflow streak field and no dust veil drawn
        // over the view, so being inside the funnel is left to Weather2's own particles.
        if (Config.classicLook()) {
            return;
        }
        float whiteout = Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get() ? TornadoFearClient.whiteoutIntensity() : 0.0F;
        // The full inflow overlay stays opt-in. It used to be force-activated by the whiteout, which
        // veiled the screen across the entire (potentially hundreds-of-blocks-wide) damage path and
        // hid the tornado itself.
        //
        // The whiteout is a different thing, and is deliberately not behind that opt-in: it answers a
        // narrower question - not "is the player somewhere in this storm" but "is the player inside
        // the funnel". Because it ramps in at the funnel wall rather than across the damage swath, it
        // cannot veil the tornado from a distance, so being engulfed is free to full the view with dust.
        // It is not this program at all any more: the engulfing view is a volume in the world, drawn by
        // TornadoWhiteoutShader, so that being inside the wall of grit has depth and parallax instead of
        // being a picture of a wall. What is left here is the overlay's streaks, and - if the volume's
        // program is missing and no pack can shade it as geometry - a flat veil of last resort.
        boolean overlay = Config.TORNADO_IMMERSION_INSIDE_CORE_EFFECTS.get()
                && Config.TORNADO_IMMERSION_INFLOW_SHADER_OVERLAY.get();
        boolean veil = !overlay && whiteout > 0.01F;
        if (!Config.TORNADO_IMMERSION_ENABLED.get() || (!overlay && !veil)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (minecraft.level == null || player == null) {
            return;
        }

        // The engulfing view first. It has its own depth now, so it is asked to draw before any of the
        // screen-space work below - and it can, in either of two ways: its own program, or, with an Iris
        // pack active, as world geometry the pack shades. False means neither could, and the flat veil at
        // the bottom of this method is the floor. That floor is the point of the split: the volume may not
        // exist on a given machine, but the whiteout may not silently not appear.
        if (veil && TornadoWhiteoutShader.draw(event, minecraft, whiteout)) {
            return;
        }
        // Below here is the screen-space program, and only it, so this is where its own state is required.
        // It is checked late on purpose: the volume above loads independently, and a failure to load the
        // streak program must not take the dust volume down with it.
        if (shader == null) {
            return;
        }

        float path = TornadoFearClient.insideDamagePathIntensity();
        float core = TornadoFearClient.insideCoreIntensity();
        float eye = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? TornadoFearClient.insideEyeIntensity() : 0.0F;
        float opacity = overlay ? Config.TORNADO_IMMERSION_INFLOW_OVERLAY_OPACITY.get().floatValue()
                : Config.TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY.get().floatValue();
        if (overlay && whiteout > 0.01F) {
            opacity = Math.max(opacity, Config.TORNADO_IMMERSION_WHITEOUT_VEIL_OPACITY.get().floatValue() * whiteout);
        }
        float strength = Mth.clamp((path * 0.58F + core * 0.72F + whiteout * 0.42F) * opacity, 0.0F, 1.0F);
        if (!veil && strength <= 0.01F) {
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
        setUniform("VeilOnly", veil ? 1.0F : 0.0F);
        setUniform("FunnelViolence", TornadoFearClient.funnelViolence());
        setUniform("DebrisIntensity", Config.TORNADO_IMMERSION_WHITEOUT_DEBRIS.get().floatValue());

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
