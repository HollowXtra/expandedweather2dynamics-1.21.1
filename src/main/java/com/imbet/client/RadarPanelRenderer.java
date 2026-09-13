package com.imbet.client;

import com.imbet.Config;
import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.weather.SpcRisk;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;

import java.util.List;

public class RadarPanelRenderer implements BlockEntityRenderer<RadarBlockEntity> {
    public RadarPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RadarBlockEntity radar, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!Config.RADAR_WORLD_PANEL_ENABLED.get() || RenderingCompat.isShaderPackActive()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(RadarShader.radarPanel());
        PoseStack.Pose pose = stack.last();
        float y = 1.014F;
        RenderQuads.topQuadUv(consumer, pose, 0.055F, 0.055F, 0.945F, 0.945F, y, 0xEE020303);
        drawRadarMapGrid(consumer, pose, y + 0.001F);

        List<RadarStormSnapshot> storms = ClientRadarStore.stormsFor(radar.getBlockPos());
        int range = Math.max(64, radar.getRange());
        int mode = radar.getMode();
        for (RadarStormSnapshot storm : storms) {
            float rx = radarX(radar, storm.x(), range);
            float rz = radarZ(radar, storm.z(), range);
            float size = returnSize(storm);
            float circulationX = radarX(radar, storm.circulationX(), range);
            float circulationZ = radarZ(radar, storm.circulationZ(), range);
            float hookX = radarX(radar, storm.hookX(), range);
            float hookZ = radarZ(radar, storm.hookZ(), range);
            if (!insideScope(circulationX, circulationZ)) {
                circulationX = rx;
                circulationZ = rz;
            }
            float displayX = storm.hasConfirmedCirculation() ? circulationX : storm.hookEcho() ? hookX : rx;
            float displayZ = storm.hasConfirmedCirculation() ? circulationZ : storm.hookEcho() ? hookZ : rz;
            boolean returnInside = insideScope(rx, rz);
            boolean displayInside = insideScope(displayX, displayZ);
            if (!returnInside && !displayInside) {
                continue;
            }
            if (returnInside) {
                if (mode == 1) {
                    drawVelocityReturn(consumer, pose, storm, hookX, hookZ, size, y + 0.007F);
                } else {
                    if (mode == 2) {
                        drawRiskPixels(consumer, pose, storm, rx, rz, size, y + 0.007F);
                    } else {
                        drawReflectivityReturn(consumer, pose, storm, rx, rz, size, y + 0.007F);
                    }
                }
            }
            float trackX = displayInside ? displayX : rx;
            float trackZ = displayInside ? displayZ : rz;
            drawMotionTrack(consumer, pose, storm, trackX, trackZ, range, y + 0.014F);
            if (storm.subVortices() > 0) {
                drawSubVortexMarkers(consumer, pose, storm, circulationX, circulationZ, size, y + 0.016F);
            }
            if (storm.hasConfirmedCirculation() && Config.RADAR_CONFIRMED_CIRCULATION_MARKER.get() && displayInside) {
                drawTornadoMarker(consumer, pose, circulationX, circulationZ, size, y + 0.017F);
            } else if (storm.hookEcho() && displayInside) {
                drawMesocycloneMarker(consumer, pose, hookX, hookZ, size, y + 0.016F);
            }
        }
    }

    private static float returnSize(RadarStormSnapshot storm) {
        float dbzSize = (float) Mth.clamp((storm.reflectivityDbz() - 15.0D) / 60.0D, 0.0D, 1.0D);
        float widthSize = Mth.clamp(storm.width() / 180.0F, 0.0F, 0.45F);
        float base = storm.vortex() ? 0.078F : 0.054F;
        return base + dbzSize * 0.048F + widthSize * 0.044F;
    }

    private static float radarX(RadarBlockEntity radar, double worldX, int range) {
        float dx = (float) ((worldX - (radar.getBlockPos().getX() + 0.5D)) / range * 0.84D);
        return snapToRadarPixel(0.5F + Mth.clamp(dx, -0.42F, 0.42F));
    }

    private static float radarZ(RadarBlockEntity radar, double worldZ, int range) {
        float dz = (float) ((worldZ - (radar.getBlockPos().getZ() + 0.5D)) / range * 0.84D);
        return snapToRadarPixel(0.5F + Mth.clamp(dz, -0.42F, 0.42F));
    }

    private static void drawRadarMapGrid(VertexConsumer consumer, PoseStack.Pose pose, float y) {
        RenderQuads.topQuadUv(consumer, pose, 0.064F, 0.064F, 0.936F, 0.936F, y, 0xF5040505);
        for (int i = 0; i <= 8; i++) {
            float p = 0.08F + i * 0.105F;
            int color = i % 2 == 0 ? 0x3332D657 : 0x2422A445;
            RenderQuads.topLineUv(consumer, pose, p, 0.075F, p, 0.925F, 0.0008F, y + 0.001F, color);
            RenderQuads.topLineUv(consumer, pose, 0.075F, p, 0.925F, p, 0.0008F, y + 0.001F, color);
        }
        RenderQuads.topLineUv(consumer, pose, 0.08F, 0.24F, 0.37F, 0.18F, 0.0011F, y + 0.002F, 0x5FCB9B12);
        RenderQuads.topLineUv(consumer, pose, 0.37F, 0.18F, 0.69F, 0.31F, 0.0011F, y + 0.002F, 0x5FCB9B12);
        RenderQuads.topLineUv(consumer, pose, 0.69F, 0.31F, 0.91F, 0.27F, 0.0011F, y + 0.002F, 0x5FCB9B12);
        RenderQuads.topLineUv(consumer, pose, 0.22F, 0.86F, 0.45F, 0.66F, 0.0010F, y + 0.002F, 0x55B11818);
        RenderQuads.topLineUv(consumer, pose, 0.45F, 0.66F, 0.78F, 0.72F, 0.0010F, y + 0.002F, 0x55B11818);
        RenderQuads.topLineUv(consumer, pose, 0.50F, 0.075F, 0.50F, 0.925F, 0.0013F, y + 0.003F, 0x482EC4FF);
        RenderQuads.topLineUv(consumer, pose, 0.075F, 0.50F, 0.925F, 0.50F, 0.0013F, y + 0.003F, 0x482EC4FF);
    }

    private static void drawMotionTrack(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, int range, float y) {
        double speed = Math.sqrt(storm.motionX() * storm.motionX() + storm.motionZ() * storm.motionZ());
        if (speed < 0.001D) {
            return;
        }
        float mx = (float) Mth.clamp(storm.motionX() * 34.0D / Math.max(128.0D, range), -0.07D, 0.07D);
        float mz = (float) Mth.clamp(storm.motionZ() * 34.0D / Math.max(128.0D, range), -0.07D, 0.07D);
        RenderQuads.topLineUv(consumer, pose, rx - mx, rz - mz, rx, rz, 0.004F, y, 0x82B7FFF2);
    }

    private static void drawReflectivityReturn(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        float angle = motionAngle(storm);
        float cell = pixelCell();
        int cells = Mth.clamp(Mth.ceil(size * 2.85F / cell), 8, 32);
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);
        for (int ix = -cells; ix <= cells; ix++) {
            for (int iz = -cells; iz <= cells; iz++) {
                float sx = snapToRadarPixel(rx + ix * cell);
                float sz = snapToRadarPixel(rz + iz * cell);
                if (!insidePanel(sx, sz)) {
                    continue;
                }
                float dx = sx - rx;
                float dz = sz - rz;
                float forward = dx * cos + dz * sin;
                float right = -dx * sin + dz * cos;
                double dbz = reflectivityAtPixel(storm, forward, right, size, ix, iz);
                if (dbz < 7.0D) {
                    continue;
                }
                drawPixelCell(consumer, pose, sx, sz, cell, y + dbzLayer(dbz), dbzColor(dbz, 230));
            }
        }
    }

    private static double reflectivityAtPixel(RadarStormSnapshot storm, float forward, float right, float size, int gridX, int gridZ) {
        double core = Math.pow((forward - size * 0.20F) / (size * 1.42F), 2.0D) + Math.pow(right / (size * 0.76F), 2.0D);
        double dbz = storm.reflectivityDbz() - core * 26.0D + pixelNoise(storm.id(), gridX, gridZ) * 7.0D;
        if (storm.hookEcho() || storm.severity() >= 3) {
            double forwardFlank = Math.pow((forward - size * 0.34F) / (size * 1.08F), 2.0D) + Math.pow((right + size * 0.22F) / (size * 0.56F), 2.0D);
            dbz = Math.max(dbz, storm.reflectivityDbz() - 8.0D - forwardFlank * 17.0D + pixelNoise(storm.id() ^ 0x51A7L, gridX, gridZ) * 5.0D);
            double hailCore = Math.pow((forward - size * 0.40F) / (size * 0.38F), 2.0D) + Math.pow((right + size * 0.08F) / (size * 0.26F), 2.0D);
            if (hailCore < 1.0D && storm.reflectivityDbz() >= 48.0D) {
                dbz = Math.max(dbz, storm.reflectivityDbz() + (1.0D - hailCore) * 10.0D);
            }
            double weakEchoRegion = Math.pow((forward + size * 0.02F) / (size * 0.38F), 2.0D) + Math.pow((right - size * 0.26F) / (size * 0.32F), 2.0D);
            if (weakEchoRegion < 1.0D) {
                dbz -= (1.0D - weakEchoRegion) * 18.0D;
            }
        }
        if (forward > size * 0.25F) {
            double vNotch = Math.max(0.0D, 1.0D - Math.abs(right) / Math.max(0.001D, (forward - size * 0.15F) * 0.48D));
            dbz -= vNotch * 14.0D;
        }
        if (storm.vortex() || storm.hookEcho()) {
            double notch = Math.pow((forward + size * 0.24F) / (size * 0.42F), 2.0D) + Math.pow((right - size * 0.28F) / (size * 0.34F), 2.0D);
            if (notch < 1.0D) {
                dbz -= (1.0D - notch) * 32.0D;
            }
            double hookF = forward + size * 0.78F;
            double hookR = right - size * 0.56F;
            double hookRadius = Math.sqrt(hookF * hookF + hookR * hookR);
            double hookAngle = Math.atan2(hookR, hookF);
            if (hookRadius > size * 0.35D && hookRadius < size * 0.86D && hookAngle > -2.80D && hookAngle < 0.85D) {
                double band = 1.0D - Math.abs(hookRadius - size * 0.58D) / (size * 0.25D);
                dbz = Math.max(dbz, storm.reflectivityDbz() - 10.0D + Math.max(0.0D, band) * 16.0D);
            }
            double debrisF = forward + size * 1.10F;
            double debrisR = right - size * 0.82F;
            double debris = Math.sqrt(debrisF * debrisF + debrisR * debrisR);
            if (storm.vortex() && debris < size * 0.18D && storm.reflectivityDbz() >= 48.0D) {
                dbz = Math.max(dbz, 67.0D + pixelNoise(storm.id() ^ 0xD36B, gridX, gridZ) * 8.0D);
            }
        }
        return dbz;
    }

    private static void drawVelocityReturn(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        float cell = pixelCell();
        int cells = Mth.clamp(Mth.ceil(size * 2.20F / cell), 7, 28);
        float rangeDistance = Math.max(0.001F, Mth.sqrt((rx - 0.5F) * (rx - 0.5F) + (rz - 0.5F) * (rz - 0.5F)));
        float beamX = (rx - 0.5F) / rangeDistance;
        float beamZ = (rz - 0.5F) / rangeDistance;
        float crossX = -beamZ;
        float crossZ = beamX;
        for (int ix = -cells; ix <= cells; ix++) {
            for (int iz = -cells; iz <= cells; iz++) {
                float sx = snapToRadarPixel(rx + ix * cell);
                float sz = snapToRadarPixel(rz + iz * cell);
                if (!insidePanel(sx, sz)) {
                    continue;
                }
                float px = sx - rx;
                float pz = sz - rz;
                float dist = Mth.sqrt(px * px + pz * pz);
                if (dist > size * 1.45F) {
                    continue;
                }
                float velocity = (float) storm.radialVelocityMps() * Mth.clamp(1.0F - dist / (size * 1.6F), 0.0F, 1.0F);
                if (storm.vortex()) {
                    float acrossBeam = px * crossX + pz * crossZ;
                    float alongBeam = px * beamX + pz * beamZ;
                    float coupletFalloff = Mth.clamp(1.0F - (Math.abs(alongBeam) / (size * 0.82F) + Math.abs(acrossBeam) / (size * 1.12F)) * 0.54F, 0.0F, 1.0F);
                    if (coupletFalloff > 0.05F) {
                        velocity = Math.signum(acrossBeam == 0.0F ? 0.001F : acrossBeam) * (float) Math.max(12.0D, storm.rotationMps()) * coupletFalloff;
                    }
                }
                if (Math.abs(velocity) < 1.5F) {
                    continue;
                }
                drawPixelCell(consumer, pose, sx, sz, cell, y + 0.001F + Math.min(0.006F, Math.abs(velocity) * 0.00012F), velocityColorMps(velocity));
            }
        }
    }

    private static void drawRiskPixels(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        float cell = pixelCell();
        int cells = Mth.clamp(Mth.ceil(size * 1.7F / cell), 5, 18);
        int color = riskColor(storm);
        for (int ix = -cells; ix <= cells; ix++) {
            for (int iz = -cells; iz <= cells; iz++) {
                if (ix * ix + iz * iz > cells * cells) {
                    continue;
                }
                float sx = snapToRadarPixel(rx + ix * cell);
                float sz = snapToRadarPixel(rz + iz * cell);
                if (insidePanel(sx, sz) && pixelNoise(storm.id(), ix, iz) > -0.35F) {
                    drawPixelCell(consumer, pose, sx, sz, cell, y + 0.001F, color);
                }
            }
        }
    }

    private static void drawSubVortexMarkers(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        int count = Mth.clamp(storm.subVortices(), 1, 10);
        for (int i = 0; i < count; i++) {
            float angle = (float) (i * Math.PI * 2.0D / count);
            float ox = Mth.cos(angle) * size * 1.55F;
            float oz = Mth.sin(angle) * size * 1.55F;
            float dot = Math.max(0.006F, size * 0.22F);
            RenderQuads.topQuadUv(consumer, pose, rx + ox - dot, rz + oz - dot, rx + ox + dot, rz + oz + dot, y, 0xEFFFF4B8);
        }
    }

    private static void drawTornadoMarker(VertexConsumer consumer, PoseStack.Pose pose, float rx, float rz, float size, float y) {
        int color = 0xD9FFF9C8;
        RenderQuads.topLineUv(consumer, pose, rx - size * 1.35F, rz, rx + size * 1.35F, rz, 0.0035F, y, color);
        RenderQuads.topLineUv(consumer, pose, rx, rz - size * 1.35F, rx, rz + size * 1.35F, 0.0035F, y + 0.001F, color);
        float ring = Math.max(0.010F, size * 0.42F);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz - ring, rx + ring, rz - ring, 0.0025F, y + 0.002F, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz - ring, rx + ring, rz + ring, 0.0025F, y + 0.002F, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz + ring, rx - ring, rz + ring, 0.0025F, y + 0.002F, color);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz + ring, rx - ring, rz - ring, 0.0025F, y + 0.002F, color);
    }

    private static void drawMesocycloneMarker(VertexConsumer consumer, PoseStack.Pose pose, float rx, float rz, float size, float y) {
        int color = 0xCFC6F6FF;
        float ring = Math.max(0.010F, size * 0.34F);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz - ring, rx + ring, rz - ring, 0.0022F, y, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz - ring, rx + ring, rz + ring, 0.0022F, y + 0.001F, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz + ring, rx - ring, rz + ring, 0.0022F, y + 0.001F, color);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz + ring, rx - ring, rz - ring, 0.0022F, y, color);
    }

    private static int dbzColor(double dbz, int alpha) {
        if (dbz < 8.0D) {
            return withAlpha(0x3A6EE8, alpha);
        }
        if (dbz < 18.0D) {
            return withAlpha(0x04E9E7, alpha);
        }
        if (dbz < 28.0D) {
            return withAlpha(0x02FD02, alpha);
        }
        if (dbz < 38.0D) {
            return withAlpha(0x008E00, alpha);
        }
        if (dbz < 45.0D) {
            return withAlpha(0xFDF802, alpha);
        }
        if (dbz < 52.0D) {
            return withAlpha(0xFD9500, alpha);
        }
        if (dbz < 60.0D) {
            return withAlpha(0xFD0000, alpha);
        }
        if (dbz < 68.0D) {
            return withAlpha(0xBC0000, alpha);
        }
        if (dbz < 74.0D) {
            return withAlpha(0xF800FD, alpha);
        }
        return withAlpha(0xFDFDFD, alpha);
    }

    private static int riskColor(RadarStormSnapshot storm) {
        return switch (Mth.clamp(storm.severity(), 0, SpcRisk.maxSeverity())) {
            case 8 -> withAlpha(0xE9D9FF, 236);
            case 7 -> withAlpha(0x9B1FE0, 230);
            case 6 -> withAlpha(0xFF5BFF, 226);
            case 5 -> withAlpha(0xE83B2F, 220);
            case 4 -> withAlpha(0xF08E24, 214);
            case 3 -> withAlpha(0xF5D742, 206);
            case 2 -> withAlpha(0x48D55D, 198);
            case 1 -> withAlpha(0x8FD05F, 188);
            default -> withAlpha(0x40505A, 160);
        };
    }

    private static int velocityColorMps(float radialMotion) {
        float strength = Mth.clamp(Math.abs(radialMotion) / 45.0F, 0.18F, 1.0F);
        int alpha = 198 + Math.round(strength * 42.0F);
        if (radialMotion < 0.0F) {
            return withAlpha(lerpColor(0x70D878, 0x00FF27, strength), alpha);
        }
        return withAlpha(lerpColor(0xDA6A6A, 0xFF1414, strength), alpha);
    }

    private static float motionAngle(RadarStormSnapshot storm) {
        double speed = Math.sqrt(storm.motionX() * storm.motionX() + storm.motionZ() * storm.motionZ());
        if (speed > 0.001D) {
            return (float) Math.atan2(storm.motionZ(), storm.motionX());
        }
        return (float) (storm.id() * 0.017453292519943295D);
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = a >> 16 & 255;
        int ag = a >> 8 & 255;
        int ab = a & 255;
        int br = b >> 16 & 255;
        int bg = b >> 8 & 255;
        int bb = b & 255;
        int r = Mth.clamp(Math.round(Mth.lerp(t, ar, br)), 0, 255);
        int g = Mth.clamp(Math.round(Mth.lerp(t, ag, bg)), 0, 255);
        int bl = Mth.clamp(Math.round(Mth.lerp(t, ab, bb)), 0, 255);
        return r << 16 | g << 8 | bl;
    }

    private static int withAlpha(int rgb, int alpha) {
        return Mth.clamp(alpha, 0, 255) << 24 | rgb & 0xFFFFFF;
    }

    private static void drawPixelCell(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float cell, float y, int color) {
        float half = cell * 0.50F;
        RenderQuads.topQuadUv(consumer, pose, centerX - half, centerZ - half, centerX + half, centerZ + half, y, color);
    }

    private static float pixelCell() {
        return 0.01075F;
    }

    private static float snapToRadarPixel(float value) {
        float min = 0.075F;
        float cell = pixelCell();
        return min + Math.round((value - min) / cell) * cell;
    }

    private static boolean insidePanel(float x, float z) {
        return x >= 0.070F && x <= 0.930F && z >= 0.070F && z <= 0.930F;
    }

    private static boolean insideScope(float x, float z) {
        float dx = x - 0.5F;
        float dz = z - 0.5F;
        return dx * dx + dz * dz <= 0.43F * 0.43F;
    }

    private static float pixelNoise(long stormId, int x, int z) {
        long n = stormId * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L + z * 0x94D049BB133111EBL;
        n ^= n >>> 30;
        n *= 0xBF58476D1CE4E5B9L;
        n ^= n >>> 27;
        n *= 0x94D049BB133111EBL;
        n ^= n >>> 31;
        return ((n & 0xFFFF) / 32767.5F) - 1.0F;
    }

    private static float dbzLayer(double dbz) {
        return (float) Mth.clamp((dbz - 20.0D) * 0.00008D, 0.0D, 0.006D);
    }

    @Override
    public boolean shouldRenderOffScreen(RadarBlockEntity blockEntity) {
        return true;
    }
}
