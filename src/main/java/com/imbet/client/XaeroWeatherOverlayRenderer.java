package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.TornadoWindField;
import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.ModList;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class XaeroWeatherOverlayRenderer {
    private static final ResourceLocation TORNADO_ICON = new ResourceLocation("weather2", "textures/radar/radar_icon_tornado.png");
    private static final int ICON_SIZE = 12;
    private static final int MINIMAP_ICON_SIZE = 10;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int SHADOW_COLOR = 0xC0000000;

    private XaeroWeatherOverlayRenderer() {
    }

    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!xaeroLoaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        Player player = minecraft.player;
        if (screen == null || player == null || !isXaeroMapScreen(screen)) {
            return;
        }
        List<Entry> entries = liveTornadoEntries(player);
        if (entries.isEmpty()) {
            return;
        }
        drawEntries(event.getGuiGraphics(), screen, minecraft.font, player, entries);
    }

    public static void onHudRenderPost(RenderGuiOverlayEvent.Post event) {
        if (!ModList.get().isLoaded("xaerominimap")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        List<Entry> entries = liveTornadoEntries(player);
        if (entries.isEmpty()) {
            return;
        }
        drawMinimapEntries(event.getGuiGraphics(), minecraft.font, player, entries, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
    }

    private static List<Entry> liveTornadoEntries(Player player) {
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager == null) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        Vec3 playerPos = player.position();
        double range = Math.max(512.0D, Math.min(Config.RADAR_RANGE_BLOCKS.get(), 8192));
        double rangeSqr = range * range;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null) {
                continue;
            }
            if (storm.attrib_waterSpout || DynamicStormController.isAbsorbedOrDecaying(storm) || !DynamicStormController.isDynamicVortex(storm)) {
                continue;
            }
            Vec3 entryCenter = DynamicStormController.tornadoCirculationCenter(storm);
            if (entryCenter.distanceToSqr(playerPos) > rangeSqr) {
                continue;
            }
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (state.subtype() != StormSubtype.NONE && state.subtype() != StormSubtype.TORNADO) {
                continue;
            }
            entries.add(Entry.main(storm, state));
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                if (satellite == null || satellite.absorbed()) {
                    continue;
                }
                Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                if (center.distanceToSqr(playerPos) <= rangeSqr) {
                    entries.add(Entry.satellite(storm, satellite, center));
                }
            }
        }
        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event == null || event.position() == null || event.position().distanceToSqr(playerPos) > rangeSqr) {
                continue;
            }
            if (event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT) {
                entries.add(Entry.addon(event));
            }
        }
        for (RadarStormSnapshot snapshot : ClientRadarStore.allStorms()) {
            if (!isTornadoMapSnapshot(snapshot)) {
                continue;
            }
            double x = snapshot.hasConfirmedCirculation() ? snapshot.circulationX() : snapshot.x();
            double z = snapshot.hasConfirmedCirculation() ? snapshot.circulationZ() : snapshot.z();
            if (new Vec3(x, playerPos.y, z).distanceToSqr(playerPos) > rangeSqr || hasNearbyEntry(entries, x, z, 16.0D)) {
                continue;
            }
            entries.add(Entry.radar(snapshot, x, z));
        }
        entries.sort(Comparator.comparingDouble(entry -> entry.distanceSqr(playerPos)));
        if (entries.size() > 16) {
            return List.copyOf(entries.subList(0, 16));
        }
        return List.copyOf(entries);
    }

    private static boolean isTornadoMapSnapshot(RadarStormSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        String label = snapshot.label() == null ? "" : snapshot.label().toLowerCase(Locale.ROOT);
        if (label.contains("waterspout")
                || label.contains("water spout")
                || label.contains("landspout")
                || label.contains("dust devil")
                || label.contains("gustnado")
                || label.contains("hurricane")
                || label.contains("cyclone")
                || label.contains("tropical")
                || label.contains("low pressure")
                || label.contains("squall")
                || label.contains("front")) {
            return false;
        }
        return label.contains("tornado");
    }

    private static boolean hasNearbyEntry(List<Entry> entries, double x, double z, double radius) {
        double radiusSqr = radius * radius;
        for (Entry entry : entries) {
            double dx = entry.x - x;
            double dz = entry.z - z;
            if (dx * dx + dz * dz <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    private static void drawEntries(GuiGraphics graphics, Screen screen, Font font, Player player, List<Entry> entries) {
        Projection projection = Projection.from(screen, player);
        int drawn = 0;
        for (Entry entry : entries) {
            Point point = projection.project(entry.x, entry.z, screen.width, screen.height);
            if (point == null) {
                continue;
            }
            int x = point.x() - ICON_SIZE / 2;
            int y = point.y() - ICON_SIZE / 2;
            graphics.blit(TORNADO_ICON, x, y, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            String text = formatLabel(entry);
            int labelX = point.x() + 8;
            int labelY = point.y() - 6;
            graphics.fill(labelX - 2, labelY - 1, labelX + font.width(text) + 2, labelY + 10, SHADOW_COLOR);
            graphics.drawString(font, text, labelX, labelY, LABEL_COLOR, false);
            drawn++;
            if (drawn >= 16) {
                return;
            }
        }
    }

    private static void drawMinimapEntries(GuiGraphics graphics, Font font, Player player, List<Entry> entries, int width, int height) {
        int size = Mth.clamp(Math.min(width, height) / 5, 72, 118);
        int margin = 8;
        int centerX = width - margin - size / 2;
        int centerY = margin + size / 2;
        double range = Math.max(256.0D, Math.min(Config.RADAR_RANGE_BLOCKS.get(), 1024));
        double pixelsPerBlock = (size * 0.5D - 8.0D) / range;
        int drawn = 0;
        for (Entry entry : entries) {
            double dx = entry.x - player.getX();
            double dz = entry.z - player.getZ();
            if (dx * dx + dz * dz > range * range) {
                continue;
            }
            int x = centerX + (int) Math.round(dx * pixelsPerBlock);
            int y = centerY + (int) Math.round(dz * pixelsPerBlock);
            int clip = size / 2 - 5;
            x = Mth.clamp(x, centerX - clip, centerX + clip);
            y = Mth.clamp(y, centerY - clip, centerY + clip);
            graphics.blit(TORNADO_ICON, x - MINIMAP_ICON_SIZE / 2, y - MINIMAP_ICON_SIZE / 2, 0.0F, 0.0F, MINIMAP_ICON_SIZE, MINIMAP_ICON_SIZE, MINIMAP_ICON_SIZE, MINIMAP_ICON_SIZE);
            if (drawn < 4) {
                String text = formatCompactLabel(entry);
                int labelX = x + 7;
                int labelY = y - 5;
                graphics.fill(labelX - 1, labelY - 1, labelX + font.width(text) + 1, labelY + 9, SHADOW_COLOR);
                graphics.drawString(font, text, labelX, labelY, LABEL_COLOR, false);
            }
            drawn++;
            if (drawn >= 12) {
                return;
            }
        }
    }

    private static String formatLabel(Entry entry) {
        int wind = (int) Math.round(entry.windMph);
        int blocks = Math.max(0, Math.round(entry.widthBlocks));
        double miles = entry.widthBlocks / Config.BLOCKS_PER_MILE;
        String stage = entry.stageLabel == null || entry.stageLabel.isBlank() ? "" : entry.stageLabel + " ";
        return stage + wind + " mph / " + String.format(Locale.ROOT, "%.3f mi", miles) + " / " + blocks + "b";
    }

    private static String formatCompactLabel(Entry entry) {
        String stage = entry.stageLabel == null || entry.stageLabel.isBlank() ? "TOR" : entry.stageLabel;
        return stage + " " + Math.round(entry.windMph) + "mph";
    }

    private static boolean xaeroLoaded() {
        ModList mods = ModList.get();
        return mods.isLoaded("xaeroworldmap") || mods.isLoaded("xaeroplus") || mods.isLoaded("xaerominimap");
    }

    private static boolean isXaeroMapScreen(Screen screen) {
        String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("xaero") && (name.contains("guimap") || name.contains("worldmap") || name.contains("map"));
    }

    private record Point(int x, int y) {
    }

    private record Entry(double x, double y, double z, double windMph, float widthBlocks, String stageLabel) {
        static Entry main(StormObject storm, DynamicStormState state) {
            float width = state.damagePathWidth();
            if (!Float.isFinite(width) || width <= 0.0F) {
                width = Math.max(8.0F, DynamicStormController.baseRadiusForWind(storm) * 2.0F);
            }
            Vec3 center = DynamicStormController.tornadoCirculationCenter(storm);
            return new Entry(center.x, center.y, center.z, TornadoWindField.maxWindMph(storm), width, XaeroWeatherOverlayRenderer.stageLabel(storm));
        }

        static Entry satellite(StormObject storm, DynamicStormState.TornadoCirculationState satellite, Vec3 center) {
            float width = satellite.damagePathWidth();
            if (!Float.isFinite(width) || width <= 0.0F) {
                width = (float) Math.max(6.0D, DynamicStormController.satelliteRadius(storm, satellite) * 2.0D);
            }
            return new Entry(center.x, center.y, center.z, DynamicStormController.satelliteWindMph(storm, satellite), width, "SAT");
        }

        static Entry addon(AddonWeatherEvent event) {
            float width = Math.max(4.0F, event.radius() * 2.0F);
            String label = event.type() == StormSubtype.WATERSPOUT ? "WSP" : "LSP";
            Vec3 position = event.position();
            return new Entry(position.x, position.y, position.z, event.maxWindMph(), width, label);
        }

        static Entry radar(RadarStormSnapshot snapshot, double x, double z) {
            String label = snapshot.label() == null ? "" : snapshot.label().toLowerCase(Locale.ROOT);
            String stage = label.contains("satellite") ? "SAT" : snapshot.stageLabel() == null || snapshot.stageLabel().isBlank() ? "TOR" : snapshot.stageLabel();
            return new Entry(x, 0.0D, z, snapshot.windMph(), Math.max(4.0F, snapshot.width()), stage);
        }

        double distanceSqr(Vec3 pos) {
            double dx = x - pos.x;
            double dz = z - pos.z;
            return dx * dx + dz * dz;
        }
    }

    private static String stageLabel(StormObject storm) {
        int stage = Math.max(0, storm.levelCurIntensityStage);
        int maxStage = Math.max(0, storm.levelStormIntensityMax);
        return maxStage > 0 ? "Stage " + stage + "/" + maxStage : "Stage " + stage;
    }

    private record Projection(double centerX, double centerZ, double pixelsPerBlock, boolean reflectedXaeroMap) {
        static Projection from(Screen screen, Player player) {
            double cameraX = readDoubleField(screen, "cameraX", Double.NaN);
            double cameraZ = readDoubleField(screen, "cameraZ", Double.NaN);
            double scale = readDoubleField(screen, "scale", Double.NaN);
            if (!Double.isFinite(scale)) {
                scale = readDoubleField(screen, "userScale", Double.NaN);
            }
            if (!Double.isFinite(scale)) {
                scale = readStaticDoubleField(screen.getClass(), "destScale", Double.NaN);
            }
            if (!Double.isFinite(scale)) {
                scale = readDoubleField(screen, "destScale", Double.NaN);
            }
            if (Double.isFinite(cameraX) && Double.isFinite(cameraZ) && Double.isFinite(scale) && scale > 0.001D) {
                double guiScale = readDoubleField(screen, "screenScale", currentGuiScale());
                if (!Double.isFinite(guiScale) || guiScale <= 0.0D) {
                    guiScale = 1.0D;
                }
                return new Projection(cameraX, cameraZ, Mth.clamp(scale / guiScale, 0.005D, 512.0D), true);
            }
            int range = Math.max(512, Math.min(Config.RADAR_RANGE_BLOCKS.get(), 8192));
            double fallbackScale = Math.min(screen.width, screen.height) * 0.42D / range;
            return new Projection(player.getX(), player.getZ(), Math.max(0.025D, fallbackScale), false);
        }

        Point project(double worldX, double worldZ, int width, int height) {
            double dx = (worldX - centerX) * pixelsPerBlock;
            double dz = (worldZ - centerZ) * pixelsPerBlock;
            int x = (int) Math.round(width * 0.5D + dx);
            int y = (int) Math.round(height * 0.5D + dz);
            int margin = reflectedXaeroMap ? 18 : 24;
            if (x < -margin || x > width + margin || y < -margin || y > height + margin) {
                return null;
            }
            return new Point(Mth.clamp(x, margin, width - margin), Mth.clamp(y, margin, height - margin));
        }
    }

    private static double currentGuiScale() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 1.0D : minecraft.getWindow().getGuiScale();
    }

    private static double readDoubleField(Object target, String name, double fallback) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getDouble(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return fallback;
    }

    private static double readStaticDoubleField(Class<?> type, String name, double fallback) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.getDouble(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                current = current.getSuperclass();
            }
        }
        return fallback;
    }
}
