package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.CompatHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class CloudShadowShaderRenderer {
    private static final RenderType CLOUD_SHADOW_PASS = RenderType.debugQuads();
    private static final List<ShadowPatch> SHADOWS = new ArrayList<>();
    private static final int MAX_SHADOW_PATCHES = 120;
    private static final float TAU = (float) (Math.PI * 2.0D);

    private CloudShadowShaderRenderer() {
    }

    public static void submit(ClientLevel level, Vec3 pos, float scale, float shade, float alpha, int maxAge) {
        if (level == null || pos == null || !Config.VISUALS_ENABLED.get()) {
            return;
        }
        long gameTime = level.getGameTime();
        while (SHADOWS.size() >= MAX_SHADOW_PATCHES) {
            SHADOWS.remove(0);
        }
        float radiusX = Mth.clamp(scale * (0.72F + level.random.nextFloat() * 0.18F), 24.0F, 166.0F);
        float radiusZ = Mth.clamp(scale * (0.38F + level.random.nextFloat() * 0.18F), 18.0F, 128.0F);
        float angle = level.random.nextFloat() * TAU;
        int seed = level.random.nextInt();
        SHADOWS.add(new ShadowPatch(
                System.identityHashCode(level),
                pos.x,
                pos.y + 0.035D,
                pos.z,
                radiusX,
                radiusZ,
                angle,
                Mth.clamp(shade, 0.010F, 0.070F),
                Mth.clamp(alpha, 0.12F, 0.48F),
                gameTime,
                gameTime + Math.max(32, maxAge),
                seed
        ));
    }

    public static void clear() {
        SHADOWS.clear();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            clear();
            return;
        }
        long gameTime = level.getGameTime();
        int levelId = System.identityHashCode(level);
        for (Iterator<ShadowPatch> iterator = SHADOWS.iterator(); iterator.hasNext(); ) {
            ShadowPatch shadow = iterator.next();
            if (shadow.levelId != levelId || shadow.expiresTick <= gameTime) {
                iterator.remove();
            }
        }
        if (SHADOWS.isEmpty()) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        stack.translate(-camera.x, -camera.y, -camera.z);
        PoseStack.Pose pose = stack.last();

        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffer.getBuffer(CLOUD_SHADOW_PASS);
        boolean shaderPackLoaded = CompatHooks.shaderPackLoaded();
        double range = Math.max(128.0D, Config.SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS.get());
        double rangeSqr = range * range;
        Vec3 playerPos = player.position();

        for (ShadowPatch shadow : SHADOWS) {
            if (playerPos.distanceToSqr(shadow.x, shadow.y, shadow.z) > rangeSqr) {
                continue;
            }
            float fadeIn = Mth.clamp((gameTime - shadow.bornTick) / 18.0F, 0.0F, 1.0F);
            float fadeOut = Mth.clamp((shadow.expiresTick - gameTime) / 36.0F, 0.0F, 1.0F);
            float alpha = shadow.alpha * Math.min(fadeIn, fadeOut) * (shaderPackLoaded ? 0.76F : 1.0F);
            if (alpha > 0.015F) {
                drawShadowPatch(consumer, pose, shadow, alpha, gameTime);
            }
        }

        stack.popPose();
        buffer.endBatch(CLOUD_SHADOW_PASS);
    }

    private static void drawShadowPatch(VertexConsumer consumer, PoseStack.Pose pose, ShadowPatch shadow, float alpha, long gameTime) {
        float drift = (float) Math.sin(gameTime * 0.006D + (shadow.seed & 255) * 0.037D);
        drawSoftEllipse(consumer, pose, shadow, alpha * 0.90F, shadow.x, shadow.z, shadow.radiusX, shadow.radiusZ, shadow.angle + drift * 0.035F);
        double offsetAngle = shadow.angle + 1.87F;
        double offsetX = Math.cos(offsetAngle) * shadow.radiusX * 0.18D;
        double offsetZ = Math.sin(offsetAngle) * shadow.radiusZ * 0.18D;
        drawSoftEllipse(consumer, pose, shadow, alpha * 0.54F, shadow.x + offsetX, shadow.z + offsetZ, shadow.radiusX * 0.76F, shadow.radiusZ * 0.68F, shadow.angle - 0.46F);
    }

    private static void drawSoftEllipse(VertexConsumer consumer, PoseStack.Pose pose, ShadowPatch shadow, float alpha, double centerX, double centerZ, float radiusX, float radiusZ, float angle) {
        final int segments = 18;
        final int rings = 3;
        for (int ring = 0; ring < rings; ring++) {
            float inner = ring / (float) rings;
            float outer = (ring + 1.0F) / rings;
            float ringAlpha = alpha * (float) Math.pow(1.0F - inner * 0.72F, 1.55D);
            int color = shadowColor(shadow.shade, ringAlpha);
            for (int segment = 0; segment < segments; segment++) {
                float a1 = segment * TAU / segments;
                float a2 = (segment + 1) * TAU / segments;
                float warp1 = 0.94F + 0.08F * (float) Math.sin(a1 * 2.0F + shadow.seed * 0.013F);
                float warp2 = 0.94F + 0.08F * (float) Math.sin(a2 * 2.0F + shadow.seed * 0.013F);
                float[] p1 = ellipsePoint(centerX, centerZ, radiusX, radiusZ, angle, inner, a1, warp1);
                float[] p2 = ellipsePoint(centerX, centerZ, radiusX, radiusZ, angle, inner, a2, warp2);
                float[] p3 = ellipsePoint(centerX, centerZ, radiusX, radiusZ, angle, outer, a2, warp2);
                float[] p4 = ellipsePoint(centerX, centerZ, radiusX, radiusZ, angle, outer, a1, warp1);
                RenderQuads.topPolyQuad(consumer, pose, p1[0], p1[1], p2[0], p2[1], p3[0], p3[1], p4[0], p4[1], (float) shadow.y, color);
            }
        }
    }

    private static float[] ellipsePoint(double centerX, double centerZ, float radiusX, float radiusZ, float angle, float fraction, float theta, float warp) {
        float localX = (float) Math.cos(theta) * radiusX * fraction * warp;
        float localZ = (float) Math.sin(theta) * radiusZ * fraction * warp;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float x = (float) centerX + localX * cos - localZ * sin;
        float z = (float) centerZ + localX * sin + localZ * cos;
        return new float[]{x, z};
    }

    private static int shadowColor(float shade, float alpha) {
        int a = Mth.clamp(Math.round(alpha * 150.0F), 0, 78);
        int base = Mth.clamp(Math.round(shade * 255.0F), 3, 16);
        return (a << 24) | (base << 16) | (Mth.clamp(base + 2, 0, 255) << 8) | Mth.clamp(base + 5, 0, 255);
    }

    private record ShadowPatch(int levelId, double x, double y, double z, float radiusX, float radiusZ, float angle, float shade, float alpha, long bornTick, long expiresTick, int seed) {
    }
}
