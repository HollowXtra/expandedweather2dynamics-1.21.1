package com.imbet.client;

import com.imbet.network.ForecastSnapshotPayload;
import com.imbet.network.ForecastPolygonSnapshot;
import com.imbet.network.ForecastRiskSnapshot;
import com.imbet.network.ForecastTrackSnapshot;
import com.imbet.network.LowPressureSyncPayload;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.weather.ClientForecastStore;
import com.imbet.weather.ClientLowPressureStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.mojang.math.Axis;
import org.joml.Matrix4f;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ForecastIconRenderer {
    private static final RenderType FORECAST_PLATE = RenderType.debugQuads();
    private static final ResourceLocation ICON_TORNADO = weatherIcon("radar_icon_tornado");
    private static final ResourceLocation ICON_HAIL = weatherIcon("radar_icon_hail");
    private static final ResourceLocation ICON_LIGHTNING = weatherIcon("radar_icon_lightning");
    private static final ResourceLocation ICON_CYCLONE = weatherIcon("radar_icon_cyclone");
    private static final ResourceLocation ICON_SANDSTORM = weatherIcon("radar_icon_sandstorm");
    private static final ResourceLocation ICON_WIND = weatherIcon("radar_icon_wind");
    private static final ResourceLocation ICON_RAIN = weatherIcon("radar_icon_rain");
    private static final ResourceLocation ICON_RAIN2 = weatherIcon("radar_icon_rain2");
    private static final ResourceLocation WEATHER_FORECAST_ID = ResourceLocation.fromNamespaceAndPath("weather2", "weather_forecast");
    private static final float PLATE_HALF = 1.55F;
    private static final float RADAR_EXTENT = 1.42F;

    private ForecastIconRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (minecraft.level == null || player == null) {
            return;
        }
        List<ClientForecastStore.Display> displays = ClientForecastStore.active();
        if (displays.isEmpty()) {
            return;
        }

        PoseStack stack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        for (ClientForecastStore.Display display : displays) {
            if (!isForecastBlock(minecraft, display.origin())) {
                ClientForecastStore.discard(display.origin());
                continue;
            }
            Vec3 center = Vec3.atCenterOf(display.origin());
            if (center.distanceToSqr(player.position()) > 96.0D * 96.0D) {
                continue;
            }
            renderDisplay(minecraft, stack, buffer, camera, display);
        }
        buffer.endBatch();
    }

    private static void renderDisplay(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, Vec3 camera, ClientForecastStore.Display display) {
        List<RadarStormSnapshot> storms = display.storms() == null ? List.of() : display.storms().stream()
                .filter(ForecastIconRenderer::isForecastReturn)
                .sorted((first, second) -> compareForTracking(display.origin(), first, second))
                .limit(9)
                .toList();
        List<ForecastTrackSnapshot> tracks = display.tracks() == null ? List.of() : display.tracks().stream()
                .filter(track -> track != null)
                .limit(6)
                .toList();
        List<ForecastPolygonSnapshot> polygons = display.polygons() == null ? List.of() : display.polygons().stream()
                .filter(polygon -> polygon != null && polygon.points() != null && polygon.points().size() >= 3)
                .limit(32)
                .toList();

        stack.pushPose();
        stack.translate(display.origin().getX() + 0.5D - camera.x, display.origin().getY() + 1.04D - camera.y, display.origin().getZ() + 0.5D - camera.z);
        boolean trackMode = display.mode() == ForecastSnapshotPayload.MODE_TRACKS;
        boolean riskMode = display.mode() == ForecastSnapshotPayload.MODE_RISK;
        renderScanPlate(stack, buffer, trackMode ? 0x4A0B090F : riskMode ? 0x4A040806 : storms.isEmpty() ? 0x36000000 : 0x52000000);
        if (riskMode) {
            renderRiskPolygons(stack, buffer, display, polygons);
        } else if (!trackMode) {
            renderAlertPolygons(stack, buffer, display, polygons);
            renderLowPressureMarkers(minecraft, stack, buffer, display);
        }

        if (trackMode) {
            renderTrackLines(stack, buffer, display, tracks);
            renderPanelText(minecraft, stack, buffer, trackLines(display.origin(), tracks));
        } else if (riskMode) {
            renderPanelText(minecraft, stack, buffer, riskLines(display.risk(), storms));
        } else if (storms.isEmpty()) {
            renderWeather2IconScan(minecraft, stack, buffer, display, storms);
            renderPanelText(minecraft, stack, buffer, new String[]{"Weather2 Scan", "No weather returns"});
        } else {
            renderWeather2IconScan(minecraft, stack, buffer, display, storms);
            renderPanelText(minecraft, stack, buffer, liveScanLines(display.origin(), storms.get(0), storms.size()));
        }
        stack.popPose();
    }

    private static boolean isForecastReturn(RadarStormSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        String label = snapshot.label() == null ? "" : snapshot.label().toLowerCase(Locale.ROOT);
        String stage = snapshot.stageLabel() == null ? "" : snapshot.stageLabel().toLowerCase(Locale.ROOT);
        if (isSuppressedForecastReturn(label)) {
            return false;
        }
        if (snapshot.hasConfirmedCirculation() || snapshot.hookEcho() || snapshot.vortex()) {
            return true;
        }
        if (label.contains("tornado") || label.contains("spout") || label.contains("landspout") || label.contains("gustnado")
                || label.contains("dust devil") || label.contains("squall") || label.contains("hurricane") || label.contains("cyclone")
                || label.contains("supercell") || label.contains("hail") || label.contains("severe") || label.contains("rain")
                || label.contains("storm") || label.contains("thunder") || label.contains("cell")) {
            return true;
        }
        if (label.contains("storm") && (!stage.isBlank() && !stage.contains("no stage"))) {
            return true;
        }
        return snapshot.severity() >= 1
                || snapshot.windMph() >= 45.0D
                || Math.abs(snapshot.rotationMps()) >= 8.0D
                || snapshot.reflectivityDbz() >= 14.0D;
    }

    private static int compareForTracking(BlockPos origin, RadarStormSnapshot first, RadarStormSnapshot second) {
        int rank = Integer.compare(trackingRank(first), trackingRank(second));
        if (rank != 0) {
            return rank;
        }
        int distance = Double.compare(distanceBlocks(origin, first), distanceBlocks(origin, second));
        if (distance != 0) {
            return distance;
        }
        return Integer.compare(second.severity(), first.severity());
    }

    private static int trackingRank(RadarStormSnapshot storm) {
        if (storm.hasConfirmedCirculation()) {
            return 0;
        }
        if (storm.hookEcho()) {
            return 1;
        }
        if (storm.vortex()) {
            return 2;
        }
        return 3;
    }

    private static void renderScanPlate(PoseStack stack, MultiBufferSource.BufferSource buffer, int color) {
        VertexConsumer consumer = buffer.getBuffer(FORECAST_PLATE);
        PoseStack.Pose pose = stack.last();
        float half = PLATE_HALF;
        int alpha = Math.max(64, color >>> 24 & 255);
        RenderQuads.topQuad(consumer, pose, -half, -half, half, half, 0.015F, (alpha << 24) | 0x000000);
        RenderQuads.topLine(consumer, pose, -half, -half, half, -half, 0.012F, 0.018F, 0xCC00E4FF);
        RenderQuads.topLine(consumer, pose, half, -half, half, half, 0.012F, 0.018F, 0xCC00E4FF);
        RenderQuads.topLine(consumer, pose, half, half, -half, half, 0.012F, 0.018F, 0xCC00E4FF);
        RenderQuads.topLine(consumer, pose, -half, half, -half, -half, 0.012F, 0.018F, 0xCC00E4FF);
    }

    private static void renderWeather2IconScan(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display, List<RadarStormSnapshot> storms) {
        renderRadarRangeMarks(stack, buffer);
        if (storms.isEmpty()) {
            renderIdleWeatherIcons(minecraft, stack, buffer, display);
            return;
        }
        int count = Math.min(storms.size(), 8);
        for (int i = 0; i < count; i++) {
            RadarStormSnapshot storm = storms.get(i);
            float x = localOffset(displayX(storm) - (display.origin().getX() + 0.5D), display.range());
            float z = localOffset(displayZ(storm) - (display.origin().getZ() + 0.5D), display.range());
            if (Math.abs(x) < 0.12F && Math.abs(z) < 0.12F && i > 0) {
                double angle = i * Math.PI * 0.72D;
                x += (float) Math.cos(angle) * 0.24F;
                z += (float) Math.sin(angle) * 0.24F;
            }
            float y = 0.31F + (i % 3) * 0.045F;
            float size = storm.hasConfirmedCirculation() ? 0.30F : storm.hookEcho() ? 0.27F : storm.severity() >= 3 ? 0.24F : 0.21F;
            drawBillboard(minecraft, stack, buffer, iconFor(storm), x, y, z, size, colorFor(storm));
            if (i < 4) {
                renderFlatText(minecraft, stack, buffer, stageShort(storm), x - 0.075F, z - 0.16F, 0.060F + i * 0.001F, 0.0062F, 0xFFFFFFFF);
            }
        }
    }

    private static void renderRadarRangeMarks(PoseStack stack, MultiBufferSource.BufferSource buffer) {
        VertexConsumer consumer = buffer.getBuffer(FORECAST_PLATE);
        PoseStack.Pose pose = stack.last();
        RenderQuads.topLine(consumer, pose, -1.22F, 0.0F, 1.22F, 0.0F, 0.006F, 0.026F, 0x8A00E4FF);
        RenderQuads.topLine(consumer, pose, 0.0F, -1.22F, 0.0F, 1.22F, 0.006F, 0.026F, 0x8A00E4FF);
        renderTopCircle(consumer, pose, 0.42F, 0.028F, 0xA400E4FF);
        renderTopCircle(consumer, pose, 0.82F, 0.028F, 0x8A00E4FF);
        renderTopCircle(consumer, pose, 1.22F, 0.028F, 0x7600E4FF);
        RenderQuads.topQuad(consumer, pose, -0.035F, -0.035F, 0.035F, 0.035F, 0.034F, 0xD800E4FF);
    }

    private static void renderTopCircle(VertexConsumer consumer, PoseStack.Pose pose, float radius, float y, int color) {
        float previousX = radius;
        float previousZ = 0.0F;
        int segments = 28;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2.0D * i / segments;
            float x = (float) Math.cos(angle) * radius;
            float z = (float) Math.sin(angle) * radius;
            RenderQuads.topLine(consumer, pose, previousX, previousZ, x, z, 0.006F, y, color);
            previousX = x;
            previousZ = z;
        }
    }

    private static void renderIdleWeatherIcons(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display) {
        long seed = display.origin().asLong();
        ResourceLocation[] icons = {ICON_RAIN, ICON_RAIN2, ICON_LIGHTNING, ICON_WIND};
        float[][] points = {
                {-0.66F, -0.34F, 0.20F},
                {0.36F, -0.54F, 0.18F},
                {0.72F, 0.30F, 0.19F},
                {-0.28F, 0.62F, 0.17F}
        };
        for (int i = 0; i < points.length; i++) {
            float bob = (((seed >> (i * 7)) & 3L) - 1.5F) * 0.010F;
            drawBillboard(minecraft, stack, buffer, icons[i], points[i][0], 0.28F + bob, points[i][1], points[i][2], 0xB8FFFFFF);
        }
    }

    private static void renderTrackLines(PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display, List<ForecastTrackSnapshot> tracks) {
        if (tracks.isEmpty()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(FORECAST_PLATE);
        PoseStack.Pose pose = stack.last();
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            ForecastTrackSnapshot track = tracks.get(trackIndex);
            List<ForecastTrackSnapshot.Point> points = track.points() == null ? List.of() : track.points();
            if (points.isEmpty()) {
                continue;
            }
            int color = trackIndex == 0 ? 0xE6FF54D8 : 0xC7B58AFF;
            float previousX = 0.0F;
            float previousZ = 0.0F;
            boolean hasPrevious = false;
            for (ForecastTrackSnapshot.Point point : points) {
                float x = localOffset(point.x() - (display.origin().getX() + 0.5D), display.range());
                float z = localOffset(point.z() - (display.origin().getZ() + 0.5D), display.range());
                if (hasPrevious) {
                    RenderQuads.topLine(consumer, pose, previousX, previousZ, x, z, 0.012F, 0.027F + trackIndex * 0.001F, color);
                }
                previousX = x;
                previousZ = z;
                hasPrevious = true;
            }
            RenderQuads.topQuad(consumer, pose, previousX - 0.035F, previousZ - 0.035F, previousX + 0.035F, previousZ + 0.035F, 0.034F + trackIndex * 0.001F, 0xF0FFFFFF);
        }
    }

    private static void renderAlertPolygons(PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display, List<ForecastPolygonSnapshot> polygons) {
        if (polygons.isEmpty()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(FORECAST_PLATE);
        PoseStack.Pose pose = stack.last();
        polygons.stream()
                .filter(polygon -> polygon.warning() || polygon.watch())
                .sorted(Comparator.comparingInt(ForecastIconRenderer::alertPolygonLayer))
                .forEach(polygon -> renderPolygonFill(
                        consumer,
                        pose,
                        display,
                        polygon,
                        0.034F + alertPolygonLayer(polygon) * 0.002F,
                        alertPolygonFillColor(polygon)
                ));
    }

    private static int alertPolygonLayer(ForecastPolygonSnapshot polygon) {
        if (isEmergencyPolygon(polygon)) {
            return 2;
        }
        if (polygon.warning()) {
            return 1;
        }
        return 0;
    }

    private static int alertPolygonFillColor(ForecastPolygonSnapshot polygon) {
        if (isEmergencyPolygon(polygon)) {
            return 0xB8D823FF;
        }
        if (polygon.watch()) {
            return 0x96F6C945;
        }
        String label = normalized(polygon.label());
        String product = normalized(polygon.productCode());
        if (label.contains("severe thunderstorm") || product.contains("svr")) {
            return 0x9AFFD23F;
        }
        return 0xA8FF3030;
    }

    private static boolean isEmergencyPolygon(ForecastPolygonSnapshot polygon) {
        String label = normalized(polygon.label());
        String product = normalized(polygon.productCode());
        return label.contains("emergency") || product.contains("tor-e") || product.contains("emergency");
    }

    private static String normalized(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static void renderRiskPolygons(PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display, List<ForecastPolygonSnapshot> polygons) {
        if (polygons.isEmpty()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(FORECAST_PLATE);
        PoseStack.Pose pose = stack.last();
        for (ForecastPolygonSnapshot polygon : polygons) {
            if (polygon.risk()) {
                renderPolygonFill(consumer, pose, display, polygon, 0.031F);
                renderPolygonOutline(consumer, pose, display, polygon, false, 0.010F, 0.039F);
            }
        }
        for (ForecastPolygonSnapshot polygon : polygons) {
            if (polygon.significantHazard()) {
                renderPolygonOutline(consumer, pose, display, polygon, true, 0.013F, 0.047F);
            }
        }
    }

    private static void renderPolygonFill(VertexConsumer consumer, PoseStack.Pose pose, ClientForecastStore.Display display, ForecastPolygonSnapshot polygon, float y) {
        renderPolygonFill(consumer, pose, display, polygon, y, withAlpha(polygon.color(), Math.min(84, polygon.color() >>> 24 & 255)));
    }

    private static void renderPolygonFill(VertexConsumer consumer, PoseStack.Pose pose, ClientForecastStore.Display display, ForecastPolygonSnapshot polygon, float y, int fillColor) {
        List<ForecastPolygonSnapshot.Point> points = polygon.points();
        if (points.size() < 3) {
            return;
        }
        float centerX = 0.0F;
        float centerZ = 0.0F;
        for (ForecastPolygonSnapshot.Point point : points) {
            centerX += localOffset(point.x() - (display.origin().getX() + 0.5D), display.range());
            centerZ += localOffset(point.z() - (display.origin().getZ() + 0.5D), display.range());
        }
        centerX /= points.size();
        centerZ /= points.size();
        for (int index = 0; index < points.size(); index++) {
            ForecastPolygonSnapshot.Point first = points.get(index);
            ForecastPolygonSnapshot.Point second = points.get((index + 1) % points.size());
            float x1 = localOffset(first.x() - (display.origin().getX() + 0.5D), display.range());
            float z1 = localOffset(first.z() - (display.origin().getZ() + 0.5D), display.range());
            float x2 = localOffset(second.x() - (display.origin().getX() + 0.5D), display.range());
            float z2 = localOffset(second.z() - (display.origin().getZ() + 0.5D), display.range());
            RenderQuads.topPolyQuad(consumer, pose, centerX, centerZ, x1, z1, x2, z2, centerX, centerZ, y, fillColor);
        }
    }

    private static void renderPolygonOutline(VertexConsumer consumer, PoseStack.Pose pose, ClientForecastStore.Display display, ForecastPolygonSnapshot polygon, boolean dashed, float halfWidth, float y) {
        renderPolygonOutline(consumer, pose, display, polygon, dashed, halfWidth, y, polygon.color());
    }

    private static void renderPolygonOutline(VertexConsumer consumer, PoseStack.Pose pose, ClientForecastStore.Display display, ForecastPolygonSnapshot polygon, boolean dashed, float halfWidth, float y, int color) {
        List<ForecastPolygonSnapshot.Point> points = polygon.points();
        if (points.size() < 2) {
            return;
        }
        for (int index = 0; index < points.size(); index++) {
            ForecastPolygonSnapshot.Point first = points.get(index);
            ForecastPolygonSnapshot.Point second = points.get((index + 1) % points.size());
            float x1 = localOffset(first.x() - (display.origin().getX() + 0.5D), display.range());
            float z1 = localOffset(first.z() - (display.origin().getZ() + 0.5D), display.range());
            float x2 = localOffset(second.x() - (display.origin().getX() + 0.5D), display.range());
            float z2 = localOffset(second.z() - (display.origin().getZ() + 0.5D), display.range());
            if (dashed) {
                renderDashedLine(consumer, pose, x1, z1, x2, z2, halfWidth, y, color);
            } else {
                RenderQuads.topLine(consumer, pose, x1, z1, x2, z2, halfWidth, y, color);
            }
        }
    }

    private static void renderDashedLine(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float halfWidth, float y, int color) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 0.05F) {
            return;
        }
        float dash = 0.052F;
        float gap = 0.034F;
        for (float start = 0.0F; start < length; start += dash + gap) {
            float end = Math.min(length, start + dash);
            float t1 = start / length;
            float t2 = end / length;
            RenderQuads.topLine(
                    consumer,
                    pose,
                    Mth.lerp(t1, x1, x2),
                    Mth.lerp(t1, z1, z2),
                    Mth.lerp(t2, x1, x2),
                    Mth.lerp(t2, z1, z2),
                    halfWidth,
                    y,
                    color
            );
        }
    }

    private static void renderLowPressureMarkers(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display) {
        List<LowPressureSyncPayload.LowPressureSnapshot> systems = ClientLowPressureStore.active();
        if (systems.isEmpty()) {
            return;
        }
        PoseStack.Pose pose = stack.last();
        for (LowPressureSyncPayload.LowPressureSnapshot system : systems) {
            double dx = system.x() - (display.origin().getX() + 0.5D);
            double dz = system.z() - (display.origin().getZ() + 0.5D);
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > Math.min(display.range(), com.imbet.Config.LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS.get())) {
                continue;
            }
            float x = localOffset(dx, display.range());
            float z = localOffset(dz, display.range());
            VertexConsumer consumer = buffer.getBuffer(FORECAST_PLATE);
            renderSynopticLow(consumer, pose, system, x, z);
            renderFlatText(minecraft, stack, buffer, "L", x - 0.034F, z - 0.034F, 0.052F, 0.0064F, 0xFFFF3636);
        }
    }

    private static void renderSynopticLow(VertexConsumer consumer, PoseStack.Pose pose, LowPressureSyncPayload.LowPressureSnapshot system, float x, float z) {
        double mx = system.motionX();
        double mz = system.motionZ();
        double motionLength = Math.sqrt(mx * mx + mz * mz);
        if (motionLength < 1.0E-4D) {
            mx = 1.0D;
            mz = -0.35D;
            motionLength = Math.sqrt(mx * mx + mz * mz);
        }
        float fx = (float) (mx / motionLength);
        float fz = (float) (mz / motionLength);
        renderLowPressureIsobars(consumer, pose, x, z, fx, fz, system.intensity(), 0.037F);

        float warmX = rotateX(fx, fz, 28.0F);
        float warmZ = rotateZ(fx, fz, 28.0F);
        float coldX = rotateX(fx, fz, 145.0F);
        float coldZ = rotateZ(fx, fz, 145.0F);
        renderCurvedFrontArm(consumer, pose, x, z, warmX, warmZ, 0.48F + system.intensity() * 0.34F, 0xE0B13A2D, true, 0.043F, 1.0F);
        renderCurvedFrontArm(consumer, pose, x, z, coldX, coldZ, 0.54F + system.intensity() * 0.40F, 0xE03648B8, false, 0.044F, -1.0F);
    }

    private static void renderLowPressureIsobars(VertexConsumer consumer, PoseStack.Pose pose, float x, float z, float motionX, float motionZ, float intensity, float y) {
        float angle = (float) Math.atan2(motionZ, motionX) + 0.42F;
        float inner = 0.105F + intensity * 0.040F;
        float middle = 0.175F + intensity * 0.060F;
        float outer = 0.255F + intensity * 0.075F;
        renderTopEllipse(consumer, pose, inner, inner * 0.76F, angle, x, z, y + 0.003F, 0x70F8F8F8);
        renderTopEllipse(consumer, pose, middle, middle * 0.72F, angle + 0.18F, x, z, y + 0.002F, 0x54F8F8F8);
        renderTopEllipse(consumer, pose, outer, outer * 0.68F, angle + 0.34F, x, z, y + 0.001F, 0x38F8F8F8);
        renderCyclonicTick(consumer, pose, x, z, outer * 0.75F, angle + 2.25F, y + 0.004F, 0x66F8F8F8);
    }

    private static void renderTopEllipse(VertexConsumer consumer, PoseStack.Pose pose, float radiusX, float radiusZ, float angle, float centerX, float centerZ, float y, int color) {
        float previousX = centerX + (float) Math.cos(angle) * radiusX;
        float previousZ = centerZ + (float) Math.sin(angle) * radiusX;
        int segments = 30;
        for (int i = 1; i <= segments; i++) {
            double theta = Math.PI * 2.0D * i / segments;
            float localX = (float) Math.cos(theta) * radiusX;
            float localZ = (float) Math.sin(theta) * radiusZ;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float x = centerX + localX * cos - localZ * sin;
            float z = centerZ + localX * sin + localZ * cos;
            RenderQuads.topLine(consumer, pose, previousX, previousZ, x, z, 0.0026F, y, color);
            previousX = x;
            previousZ = z;
        }
    }

    private static void renderCyclonicTick(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float radius, float angle, float y, int color) {
        float startX = centerX + (float) Math.cos(angle) * radius;
        float startZ = centerZ + (float) Math.sin(angle) * radius;
        float tangentX = (float) -Math.sin(angle);
        float tangentZ = (float) Math.cos(angle);
        RenderQuads.topLine(consumer, pose, startX, startZ, startX + tangentX * 0.050F, startZ + tangentZ * 0.050F, 0.0032F, y, color);
        RenderQuads.topLine(consumer, pose, startX + tangentX * 0.050F, startZ + tangentZ * 0.050F, startX + tangentX * 0.030F - (float) Math.cos(angle) * 0.030F, startZ + tangentZ * 0.030F - (float) Math.sin(angle) * 0.030F, 0.0032F, y, color);
    }

    private static void renderTopCircle(VertexConsumer consumer, PoseStack.Pose pose, float radius, float centerX, float centerZ, float y, int color) {
        float previousX = centerX + radius;
        float previousZ = centerZ;
        int segments = 24;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2.0D * i / segments;
            float x = centerX + (float) Math.cos(angle) * radius;
            float z = centerZ + (float) Math.sin(angle) * radius;
            RenderQuads.topLine(consumer, pose, previousX, previousZ, x, z, 0.0045F, y, color);
            previousX = x;
            previousZ = z;
        }
    }

    private static void renderCurvedFrontArm(VertexConsumer consumer, PoseStack.Pose pose, float x, float z, float dirX, float dirZ, float length, int color, boolean warm, float y, float curveSide) {
        float dirLength = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLength < 1.0E-4F) {
            return;
        }
        dirX /= dirLength;
        dirZ /= dirLength;
        float normalX = -dirZ;
        float normalZ = dirX;
        float curve = length * (warm ? 0.34F : 0.42F) * curveSide;
        float controlX = x + dirX * length * 0.46F + normalX * curve;
        float controlZ = z + dirZ * length * 0.46F + normalZ * curve;
        float endX = x + dirX * length;
        float endZ = z + dirZ * length;

        float previousX = x;
        float previousZ = z;
        int segments = 28;
        for (int index = 1; index <= segments; index++) {
            float t = index / (float) segments;
            float px = bezier(x, controlX, endX, t);
            float pz = bezier(z, controlZ, endZ, t);
            if (insideRadar(previousX, previousZ) || insideRadar(px, pz)) {
                RenderQuads.topLine(
                        consumer,
                        pose,
                        Mth.clamp(previousX, -RADAR_EXTENT, RADAR_EXTENT),
                        Mth.clamp(previousZ, -RADAR_EXTENT, RADAR_EXTENT),
                        Mth.clamp(px, -RADAR_EXTENT, RADAR_EXTENT),
                        Mth.clamp(pz, -RADAR_EXTENT, RADAR_EXTENT),
                        warm ? 0.0075F : 0.0085F,
                        y,
                        color
                );
            }
            previousX = px;
            previousZ = pz;
        }

        float spacing = warm ? 0.135F : 0.125F;
        float markerSize = warm ? 0.046F : 0.052F;
        float startDistance = warm ? 0.13F : 0.12F;
        for (float distance = startDistance; distance < length - 0.04F; distance += spacing) {
            float t = Mth.clamp(distance / Math.max(0.001F, length), 0.0F, 1.0F);
            float px = bezier(x, controlX, endX, t);
            float pz = bezier(z, controlZ, endZ, t);
            if (!insideRadar(px, pz)) {
                continue;
            }
            float tx = bezierTangent(x, controlX, endX, t);
            float tz = bezierTangent(z, controlZ, endZ, t);
            float tangentLength = (float) Math.sqrt(tx * tx + tz * tz);
            if (tangentLength < 1.0E-4F) {
                tx = dirX;
                tz = dirZ;
            } else {
                tx /= tangentLength;
                tz /= tangentLength;
            }
            float markerSide = curveSide >= 0.0F ? 1.0F : -1.0F;
            if (warm) {
                renderWarmFrontSemicircle(consumer, pose, px, pz, tx, tz, markerSide, markerSize, y + 0.003F, color);
            } else {
                renderColdFrontTriangle(consumer, pose, px, pz, tx, tz, markerSide, markerSize, y + 0.003F, color);
            }
        }
    }

    private static float bezier(float start, float control, float end, float t) {
        float inverse = 1.0F - t;
        return inverse * inverse * start + 2.0F * inverse * t * control + t * t * end;
    }

    private static float bezierTangent(float start, float control, float end, float t) {
        return 2.0F * (1.0F - t) * (control - start) + 2.0F * t * (end - control);
    }

    private static boolean insideRadar(float x, float z) {
        return Math.abs(x) <= RADAR_EXTENT && Math.abs(z) <= RADAR_EXTENT;
    }

    private static void renderWarmFrontSemicircle(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float tangentX, float tangentZ, float side, float radius, float y, int color) {
        float normalX = -tangentZ * side;
        float normalZ = tangentX * side;
        int segments = 8;
        for (int index = 0; index < segments; index++) {
            float a1 = (float) Math.PI * index / segments;
            float a2 = (float) Math.PI * (index + 1) / segments;
            float base1 = (float) Math.cos(a1) * radius;
            float base2 = (float) Math.cos(a2) * radius;
            float arc1 = (float) Math.sin(a1) * radius;
            float arc2 = (float) Math.sin(a2) * radius;
            float b1x = centerX + tangentX * base1;
            float b1z = centerZ + tangentZ * base1;
            float b2x = centerX + tangentX * base2;
            float b2z = centerZ + tangentZ * base2;
            float p1x = b1x + normalX * arc1;
            float p1z = b1z + normalZ * arc1;
            float p2x = b2x + normalX * arc2;
            float p2z = b2z + normalZ * arc2;
            RenderQuads.topPolyQuad(consumer, pose, b1x, b1z, p1x, p1z, p2x, p2z, b2x, b2z, y, color);
        }
        RenderQuads.topLine(consumer, pose, centerX - tangentX * radius, centerZ - tangentZ * radius, centerX + tangentX * radius, centerZ + tangentZ * radius, 0.004F, y + 0.001F, color);
    }

    private static void renderColdFrontTriangle(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float tangentX, float tangentZ, float side, float size, float y, int color) {
        float normalX = -tangentZ * side;
        float normalZ = tangentX * side;
        float baseHalf = size * 0.62F;
        float height = size * 1.12F;
        float b1x = centerX - tangentX * baseHalf;
        float b1z = centerZ - tangentZ * baseHalf;
        float b2x = centerX + tangentX * baseHalf;
        float b2z = centerZ + tangentZ * baseHalf;
        float tipX = centerX + normalX * height;
        float tipZ = centerZ + normalZ * height;
        RenderQuads.topPolyQuad(consumer, pose, b1x, b1z, b2x, b2z, tipX, tipZ, tipX, tipZ, y, color);
    }

    private static float rotateX(float x, float z, float degrees) {
        double radians = Math.toRadians(degrees);
        return (float) (x * Math.cos(radians) - z * Math.sin(radians));
    }

    private static float rotateZ(float x, float z, float degrees) {
        double radians = Math.toRadians(degrees);
        return (float) (x * Math.sin(radians) + z * Math.cos(radians));
    }

    private static void renderPanelText(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, String[] lines) {
        Font font = minecraft.font;
        stack.pushPose();
        stack.translate(-0.44F, 0.055F, -0.36F);
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        stack.scale(0.0050F, 0.0050F, 0.0050F);
        Matrix4f matrix = stack.last().pose();
        int count = Math.min(lines.length, 7);
        for (int index = 0; index < count; index++) {
            String line = fitLine(font, lines[index], 176);
            font.drawInBatch(line, 0.0F, index * 10.0F, 0xE8FFFFFF, false, matrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0x66000000, LightTexture.FULL_BRIGHT);
        }
        stack.popPose();
    }

    private static void renderFlatText(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, String text, float x, float z, float y, float scale, int color) {
        Font font = minecraft.font;
        stack.pushPose();
        stack.translate(x, y, z);
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        stack.scale(scale, scale, scale);
        font.drawInBatch(text, 0.0F, 0.0F, color, false, stack.last().pose(), buffer, Font.DisplayMode.POLYGON_OFFSET, 0x66000000, LightTexture.FULL_BRIGHT);
        stack.popPose();
    }

    private static void drawBillboard(Minecraft minecraft, PoseStack stack, MultiBufferSource.BufferSource buffer, ResourceLocation texture, float x, float y, float z, float size, int color) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(texture));
        stack.pushPose();
        stack.translate(x, y, z);
        stack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        Matrix4f matrix = stack.last().pose();
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        float half = size * 0.5F;
        consumer.addVertex(matrix, -half, -half, 0.0F).setColor(r, g, b, a).setUv(0.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(stack.last(), 0.0F, 1.0F, 0.0F);
        consumer.addVertex(matrix, half, -half, 0.0F).setColor(r, g, b, a).setUv(1.0F, 1.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(stack.last(), 0.0F, 1.0F, 0.0F);
        consumer.addVertex(matrix, half, half, 0.0F).setColor(r, g, b, a).setUv(1.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(stack.last(), 0.0F, 1.0F, 0.0F);
        consumer.addVertex(matrix, -half, half, 0.0F).setColor(r, g, b, a).setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(stack.last(), 0.0F, 1.0F, 0.0F);
        stack.popPose();
    }

    private static ResourceLocation iconFor(RadarStormSnapshot storm) {
        String label = storm.label().toLowerCase(Locale.ROOT);
        if (label.contains("cyclone")) {
            return ICON_CYCLONE;
        }
        if (label.contains("sand") || label.contains("dust")) {
            return ICON_SANDSTORM;
        }
        if (storm.hasConfirmedCirculation() || label.contains("tornado") || label.contains("spout")) {
            return ICON_TORNADO;
        }
        if (label.contains("hail") || storm.reflectivityDbz() >= 58.0D) {
            return ICON_HAIL;
        }
        if (label.contains("rain") || label.contains("storm") || label.contains("thunder") || label.contains("cell") || storm.reflectivityDbz() >= 20.0D) {
            return storm.reflectivityDbz() >= 55.0D ? ICON_RAIN2 : ICON_RAIN;
        }
        if (storm.hookEcho() || Math.abs(storm.rotationMps()) >= 18.0D) {
            return ICON_WIND;
        }
        if (storm.severity() >= 3) {
            return ICON_LIGHTNING;
        }
        return ICON_WIND;
    }

    private static String[] liveScanLines(BlockPos origin, RadarStormSnapshot storm, int returnCount) {
        String stage = storm.stageLabel().isBlank() ? "n/a" : storm.stageLabel();
        return new String[]{
                "Weather2 Scan",
                trackingName(storm) + " " + Math.round(distanceBlocks(origin, storm)) + " blocks " + bearing(origin, storm),
                "Wind " + Math.round(storm.windMph()) + " MPH / " + stage,
                returnCount + " live returns"
        };
    }

    private static String stageShort(RadarStormSnapshot storm) {
        String stage = storm.stageLabel() == null ? "" : storm.stageLabel().trim();
        if (!stage.isBlank() && !stage.equalsIgnoreCase("no stage")) {
            return stage.length() > 5 ? stage.substring(0, 5) : stage;
        }
        String name = trackingName(storm);
        return name.length() > 4 ? name.substring(0, 4).toUpperCase(Locale.ROOT) : name.toUpperCase(Locale.ROOT);
    }

    private static int colorFor(RadarStormSnapshot storm) {
        if (storm.hasConfirmedCirculation()) {
            return 0xF0FFFFFF;
        }
        if (storm.hookEcho()) {
            return 0xF0FFFFFF;
        }
        return 0xDDFFFFFF;
    }

    private static String[] labelLines(BlockPos origin, RadarStormSnapshot storm) {
        String stage = storm.stageLabel().isBlank() ? "n/a" : storm.stageLabel();
        return new String[]{
                trackingName(storm),
                "Distance: " + Math.round(distanceBlocks(origin, storm)) + " blocks",
                "Heading: " + bearing(origin, storm) + " (" + Math.round(headingDegrees(origin, storm)) + "deg)",
                "Wind Speed: " + Math.round(storm.windMph()) + " MPH",
                "Stage: " + stage
        };
    }

    private static String[] trackLines(BlockPos origin, List<ForecastTrackSnapshot> tracks) {
        if (tracks.isEmpty()) {
            return new String[]{"Recent Tracks", "No recent tornado IDs"};
        }
        ForecastTrackSnapshot first = tracks.get(0);
        ForecastTrackSnapshot.Point last = lastPoint(first);
        String distance = last == null ? "n/a" : Math.round(Math.sqrt(Math.pow(last.x() - (origin.getX() + 0.5D), 2.0D) + Math.pow(last.z() - (origin.getZ() + 0.5D), 2.0D))) + " blocks";
        String[] lines = new String[Math.min(7, tracks.size() + 2)];
        lines[0] = "Recent Tracks";
        lines[1] = first.tornadoId() + " " + distance + " max " + Math.round(first.maxWindMph()) + " MPH";
        for (int i = 1; i < tracks.size() && i + 1 < lines.length; i++) {
            ForecastTrackSnapshot track = tracks.get(i);
            lines[i + 1] = track.tornadoId() + " max " + Math.round(track.maxWindMph()) + " MPH";
        }
        return lines;
    }

    private static String[] riskLines(ForecastRiskSnapshot risk, List<RadarStormSnapshot> storms) {
        ForecastRiskSnapshot safe = risk == null ? ForecastRiskSnapshot.EMPTY : risk;
        if (outbreakPotential(safe, storms)) {
            return new String[]{
                    "SPC " + safe.categoricalLabel() + " " + safe.issuance(),
                    "OUTBREAK SETUP",
                    safe.tornadoLabel() + " / " + safe.windLabel() + " / " + safe.hailLabel(),
                    safe.productLine(),
                    safe.hazard(),
                    safe.stormCount() + " storms / " + safe.severeCount() + " severe, max " + Math.round(safe.maxWindMph()) + " MPH"
            };
        }
        return new String[]{
                "SPC " + safe.categoricalLabel() + " " + safe.issuance(),
                safe.tornadoLabel() + " / " + safe.windLabel() + " / " + safe.hailLabel(),
                safe.productLine(),
                safe.hazard(),
                safe.stormCount() + " storms / " + safe.severeCount() + " severe, max " + Math.round(safe.maxWindMph()) + " MPH"
        };
    }

    private static boolean outbreakPotential(ForecastRiskSnapshot risk, List<RadarStormSnapshot> storms) {
        int tornadoCapable = 0;
        int tornadoObserved = 0;
        if (storms != null) {
            for (RadarStormSnapshot storm : storms) {
                if (storm == null) {
                    continue;
                }
                String label = storm.label() == null ? "" : storm.label().toLowerCase(Locale.ROOT);
                if (isSuppressedForecastReturn(label)) {
                    continue;
                }
                if (storm.hasConfirmedCirculation() || label.contains("tornado")) {
                    tornadoObserved++;
                }
                if (storm.hasConfirmedCirculation() || storm.hookEcho() || storm.windMph() >= 86.0D || Math.abs(storm.rotationMps()) >= 18.0D || label.contains("supercell")) {
                    tornadoCapable++;
                }
            }
        }
        return (risk.categoricalSeverity() >= 4 && risk.tornadoSeverity() >= 3 && tornadoCapable >= 2)
                || risk.categoricalSeverity() >= 5
                || tornadoObserved >= 3;
    }

    private static boolean isSuppressedForecastReturn(String label) {
        return label.contains("waterspout")
                || label.contains("water spout")
                || label.contains("landspout")
                || label.contains("dust devil");
    }

    private static String trackingName(RadarStormSnapshot storm) {
        String label = storm.label().toLowerCase(Locale.ROOT);
        if (storm.hasConfirmedCirculation()) {
            if (label.contains("gust")) {
                return "Gustnado";
            }
            if (label.contains("water")) {
                return "Waterspout";
            }
            if (label.contains("land")) {
                return "Landspout";
            }
            if (label.contains("dust")) {
                return "Dust Devil";
            }
            return "Tornado";
        }
        if (label.contains("hurricane")) {
            return "Hurricane";
        }
        if (label.contains("cyclone")) {
            return "Cyclone";
        }
        if (label.contains("squall")) {
            return "Squall Line";
        }
        if (storm.hookEcho()) {
            return "Rotation";
        }
        if (storm.severity() >= 4) {
            return storm.risk();
        }
        return storm.risk().isBlank() ? "WX" : storm.risk();
    }

    private static float localOffset(double distance, int range) {
        return (float) Mth.clamp(distance / Math.max(128.0D, range) * 3.0D, -RADAR_EXTENT, RADAR_EXTENT);
    }

    private static double displayX(RadarStormSnapshot storm) {
        return storm.hasConfirmedCirculation() ? storm.circulationX() : storm.hookEcho() ? storm.hookX() : storm.x();
    }

    private static double displayZ(RadarStormSnapshot storm) {
        return storm.hasConfirmedCirculation() ? storm.circulationZ() : storm.hookEcho() ? storm.hookZ() : storm.z();
    }

    private static double distanceBlocks(BlockPos origin, RadarStormSnapshot storm) {
        double dx = displayX(storm) - (origin.getX() + 0.5D);
        double dz = displayZ(storm) - (origin.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String bearing(BlockPos origin, RadarStormSnapshot storm) {
        double degrees = headingDegrees(origin, storm);
        String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return labels[(int) Math.floor((degrees + 22.5D) / 45.0D) & 7];
    }

    private static double headingDegrees(BlockPos origin, RadarStormSnapshot storm) {
        double dx = displayX(storm) - (origin.getX() + 0.5D);
        double dz = displayZ(storm) - (origin.getZ() + 0.5D);
        double degrees = Mth.wrapDegrees(Math.toDegrees(Math.atan2(dx, -dz)));
        return degrees < 0.0D ? degrees + 360.0D : degrees;
    }

    private static ForecastTrackSnapshot.Point lastPoint(ForecastTrackSnapshot track) {
        List<ForecastTrackSnapshot.Point> points = track.points();
        return points == null || points.isEmpty() ? null : points.get(points.size() - 1);
    }

    private static String fitLine(Font font, String line, int maxWidth) {
        if (line == null) {
            return "";
        }
        if (font.width(line) <= maxWidth) {
            return line;
        }
        String suffix = "...";
        int end = line.length();
        while (end > 0 && font.width(line.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : line.substring(0, end) + suffix;
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(alpha, 255)) << 24) | (color & 0x00FFFFFF);
    }

    private static boolean isForecastBlock(Minecraft minecraft, BlockPos pos) {
        if (minecraft.level == null || pos == null || !minecraft.level.isLoaded(pos)) {
            return false;
        }
        var block = minecraft.level.getBlockState(pos).getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return WEATHER_FORECAST_ID.equals(id) || "weather2.block.ForecastBlock".equals(block.getClass().getName());
    }

    private static ResourceLocation weatherIcon(String name) {
        return ResourceLocation.fromNamespaceAndPath("weather2", "textures/radar/" + name + ".png");
    }
}
