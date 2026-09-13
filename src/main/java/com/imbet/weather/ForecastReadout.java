package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.ForecastPolygonSnapshot;
import com.imbet.network.ForecastRiskSnapshot;
import com.imbet.network.ForecastSnapshotPayload;
import com.imbet.network.ForecastTrackSnapshot;
import com.imbet.network.NetworkSafety;
import com.imbet.radar.RadarService;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.survey.TornadoSurveySavedData;
import com.imbet.survey.TornadoSurveySavedData.TrackPoint;
import com.imbet.survey.TornadoSurveySavedData.TornadoTrack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.config.ConfigStorm;
import weather2.weathersystem.WeatherManagerServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ForecastReadout {
    private static final int DEFAULT_RANGE_BLOCKS = 4096;
    private static final int MAX_LINES = 4;
    private static final int LIVE_UPDATE_INTERVAL_TICKS = 2;
    private static final long DISPLAY_TICKS = 10L * 60L * 20L;
    private static final ResourceLocation WEATHER_FORECAST_ID = ResourceLocation.fromNamespaceAndPath("weather2", "weather_forecast");
    private static final Map<ResourceKey<Level>, Map<UUID, ActiveDisplay>> ACTIVE_DISPLAYS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, ForecastModeState>> MODE_STATES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<String, CachedAlertPolygon>> ALERT_POLYGON_CACHE = new HashMap<>();
    private static final double ALERT_POLYGON_REISSUE_DIRECTION_DOT = Math.cos(Math.toRadians(12.0D));
    private static final double ALERT_POLYGON_MIN_LEAD_BLOCKS = 720.0D;
    private static final long ALERT_POLYGON_CACHE_TTL_TICKS = 20L * 60L * 10L;

    private ForecastReadout() {
    }

    public static void extendWeather2Scan(Level level, BlockPos pos, Player player, boolean showTracks) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        int range = Math.max(512, Math.min(Math.max(Config.RADAR_RANGE_BLOCKS.get(), DEFAULT_RANGE_BLOCKS), 8192));
        int mode = modeForUse(serverLevel, pos, serverPlayer, showTracks);
        NetworkSafety.sendToPlayer(serverPlayer, buildPayload(serverLevel, pos, range, mode));
        registerActiveDisplay(serverLevel, pos, range, mode, serverPlayer);
    }

    public static InteractionResult use(Level level, BlockPos pos, Player player, boolean showTracks) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(serverLevel);
        int range = Math.max(512, Math.min(Math.max(Config.RADAR_RANGE_BLOCKS.get(), DEFAULT_RANGE_BLOCKS), 8192));
        ServerPlayer serverPlayer = player instanceof ServerPlayer found ? found : null;
        int mode = modeForUse(serverLevel, pos, serverPlayer, showTracks);
        ForecastSnapshotPayload payload = buildPayload(serverLevel, pos, range, mode);
        List<RadarStormSnapshot> snapshots = payload.storms();
        List<ForecastTrackSnapshot> tracks = payload.tracks();
        if (serverPlayer != null) {
            NetworkSafety.sendToPlayer(serverPlayer, payload);
            registerActiveDisplay(serverLevel, pos, range, mode, serverPlayer);
        }

        player.sendSystemMessage(Component.literal(titleForMode(mode)).withStyle(ChatFormatting.AQUA));
        if (mode == ForecastSnapshotPayload.MODE_TRACKS) {
            sendTrackLines(player, pos, tracks);
            return InteractionResult.CONSUME;
        }
        if (mode == ForecastSnapshotPayload.MODE_RISK) {
            sendRiskLines(player, payload.risk(), snapshots);
            return InteractionResult.CONSUME;
        }
        sendEnvironmentLine(player, manager, pos);
        sendLowPressureLine(serverLevel, player, pos);
        if (snapshots.isEmpty()) {
            player.sendSystemMessage(Component.literal("Radar: no tracked storm/tornado returns inside " + range + " blocks.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.CONSUME;
        }

        RadarStormSnapshot strongest = snapshots.get(0);
        player.sendSystemMessage(Component.literal("Outlook: " + strongest.risk() + " / strongest return " + Math.round(distance(pos, strongest)) + " blocks " + bearing(pos, strongest)).withStyle(colorFor(strongest.severity())));
        int count = Math.min(MAX_LINES, snapshots.size());
        for (int index = 0; index < count; index++) {
            RadarStormSnapshot snapshot = snapshots.get(index);
            player.sendSystemMessage(Component.literal(lineFor(pos, snapshot)).withStyle(colorFor(snapshot.severity())));
        }
        if (snapshots.size() > count) {
            player.sendSystemMessage(Component.literal("+" + (snapshots.size() - count) + " weaker returns in range.").withStyle(ChatFormatting.DARK_GRAY));
        }
        return InteractionResult.CONSUME;
    }

    public static void tick(ServerLevel level) {
        cleanupModeStates(level);
        Map<UUID, ActiveDisplay> displays = ACTIVE_DISPLAYS.get(level.dimension());
        if (displays == null || displays.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        Iterator<Map.Entry<UUID, ActiveDisplay>> iterator = displays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ActiveDisplay> entry = iterator.next();
            ActiveDisplay display = entry.getValue();
            if (gameTime > display.expiresTick() || !isForecastBlock(level, display.origin())) {
                iterator.remove();
                continue;
            }
            if (gameTime - display.lastSyncTick() < LIVE_UPDATE_INTERVAL_TICKS) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) {
                iterator.remove();
                continue;
            }
            if (player.distanceToSqr(display.origin().getX() + 0.5D, display.origin().getY() + 0.5D, display.origin().getZ() + 0.5D) > 128.0D * 128.0D) {
                continue;
            }
            NetworkSafety.sendToPlayer(player, buildPayload(level, display.origin(), display.range(), display.mode()));
            entry.setValue(new ActiveDisplay(display.origin(), display.range(), display.mode(), display.expiresTick(), gameTime));
        }
        if (displays.isEmpty()) {
            ACTIVE_DISPLAYS.remove(level.dimension());
        }
    }

    private static void registerActiveDisplay(ServerLevel level, BlockPos pos, int range, int mode, ServerPlayer player) {
        ACTIVE_DISPLAYS.computeIfAbsent(level.dimension(), key -> new HashMap<>())
                .put(player.getUUID(), new ActiveDisplay(pos.immutable(), range, mode, level.getGameTime() + DISPLAY_TICKS, level.getGameTime()));
    }

    private static int modeForUse(ServerLevel level, BlockPos pos, ServerPlayer player, boolean showTracks) {
        if (showTracks) {
            return ForecastSnapshotPayload.MODE_TRACKS;
        }
        if (player == null) {
            return ForecastSnapshotPayload.MODE_LIVE;
        }
        Map<UUID, ForecastModeState> states = MODE_STATES.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        ForecastModeState previous = states.get(player.getUUID());
        if (previous != null && previous.origin().equals(pos) && previous.lastUseTick() == level.getGameTime()) {
            return previous.mode();
        }
        int nextMode = previous != null
                && previous.origin().equals(pos)
                && level.getGameTime() - previous.lastUseTick() <= DISPLAY_TICKS
                && previous.mode() == ForecastSnapshotPayload.MODE_LIVE
                ? ForecastSnapshotPayload.MODE_RISK
                : ForecastSnapshotPayload.MODE_LIVE;
        states.put(player.getUUID(), new ForecastModeState(pos.immutable(), nextMode, level.getGameTime()));
        return nextMode;
    }

    private static void cleanupModeStates(ServerLevel level) {
        Map<UUID, ForecastModeState> states = MODE_STATES.get(level.dimension());
        if (states == null || states.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        states.entrySet().removeIf(entry -> gameTime - entry.getValue().lastUseTick() > DISPLAY_TICKS);
        if (states.isEmpty()) {
            MODE_STATES.remove(level.dimension());
        }
    }

    private static ForecastSnapshotPayload buildPayload(ServerLevel level, BlockPos pos, int range, int mode) {
        List<RadarStormSnapshot> snapshots = mode == ForecastSnapshotPayload.MODE_TRACKS ? List.of() : RadarService.buildSnapshot(level, pos, range).stream()
                .filter(ForecastReadout::isForecastReturn)
                .sorted(Comparator.comparingInt(RadarStormSnapshot::severity).reversed()
                        .thenComparingDouble(snapshot -> distance(pos, snapshot)))
                .toList();
        List<ForecastTrackSnapshot> tracks = mode == ForecastSnapshotPayload.MODE_TRACKS ? buildTrackSnapshots(level, pos, range) : List.of();
        ForecastRiskSnapshot risk = mode == ForecastSnapshotPayload.MODE_TRACKS ? ForecastRiskSnapshot.EMPTY : buildRiskSnapshot(level, pos, range);
        List<ForecastPolygonSnapshot> polygons = mode == ForecastSnapshotPayload.MODE_TRACKS ? List.of() : buildPolygons(level, pos, range, snapshots, risk);
        return new ForecastSnapshotPayload(pos, range, mode, snapshots, tracks, polygons, risk);
    }

    private static ForecastRiskSnapshot buildRiskSnapshot(ServerLevel level, BlockPos pos, int range) {
        SpcOutlookService.Outlook outlook = SpcOutlookService.compute(level, Vec3.atCenterOf(pos), range);
        return new ForecastRiskSnapshot(
                outlook.categorical().label(),
                outlook.categorical().severity(),
                outlook.categorical().color(),
                outlook.tornado().label(),
                outlook.tornado().severity(),
                outlook.tornado().color(),
                outlook.wind().label(),
                outlook.wind().severity(),
                outlook.wind().color(),
                outlook.hail().label(),
                outlook.hail().severity(),
                outlook.hail().color(),
                outlook.tornadoProbability(),
                outlook.windProbability(),
                outlook.hailProbability(),
                outlook.tornadoCig(),
                outlook.windCig(),
                outlook.hailCig(),
                outlook.issuance(),
                outlook.hazard(),
                outlook.stormCount(),
                outlook.severeCount(),
                outlook.maxWindMph()
        );
    }

    private static List<ForecastTrackSnapshot> buildTrackSnapshots(ServerLevel level, BlockPos pos, int range) {
        TornadoSurveySavedData data = TornadoSurveySavedData.get(level);
        Vec3 origin = Vec3.atCenterOf(pos);
        long gameTime = level.getGameTime();
        long recentWindow = Math.max(20L, Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get());
        long minTick = gameTime - recentWindow;
        return data.tracks().stream()
                .filter(track -> track.endTick() >= minTick)
                .filter(track -> trackDistance(track, origin, minTick) <= range)
                .sorted(Comparator.comparingDouble(track -> trackDistance(track, origin, minTick)))
                .limit(6)
                .map(track -> toTrackSnapshot(track, origin, gameTime, recentWindow, range))
                .toList();
    }

    private static ForecastTrackSnapshot toTrackSnapshot(TornadoTrack track, Vec3 origin, long gameTime, long recentWindow, int range) {
        List<ForecastTrackSnapshot.Point> points = track.compactRecentPoints(42, gameTime, recentWindow, origin, range).stream()
                .map(point -> new ForecastTrackSnapshot.Point(point.x(), point.z()))
                .toList();
        return new ForecastTrackSnapshot(
                track.displayId(),
                track.primarySubtype(),
                track.maxWindMph(),
                track.maxWidthBlocks(),
                Math.max(0L, gameTime - track.endTick()),
                points
        );
    }

    private static double trackDistance(TornadoTrack track, Vec3 origin, long minTick) {
        double best = Double.MAX_VALUE;
        for (TrackPoint point : track.points()) {
            if (point.tick() < minTick) {
                continue;
            }
            best = Math.min(best, point.distanceTo(origin));
        }
        return best;
    }

    private static void sendTrackLines(Player player, BlockPos pos, List<ForecastTrackSnapshot> tracks) {
        if (tracks.isEmpty()) {
            player.sendSystemMessage(Component.literal("Recent tracks: none near this forecaster.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int count = Math.min(MAX_LINES, tracks.size());
        for (int index = 0; index < count; index++) {
            ForecastTrackSnapshot track = tracks.get(index);
            player.sendSystemMessage(Component.literal(trackLine(pos, track)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (tracks.size() > count) {
            player.sendSystemMessage(Component.literal("+" + (tracks.size() - count) + " older/nearby tracks hidden.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void sendRiskLines(Player player, ForecastRiskSnapshot risk, List<RadarStormSnapshot> snapshots) {
        ForecastRiskSnapshot safe = risk == null ? ForecastRiskSnapshot.EMPTY : risk;
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Risk: Categorical %s, Tornado %s, Wind %s, Hail %s (%s)",
                safe.categoricalLabel(),
                safe.tornadoLabel(),
                safe.windLabel(),
                safe.hailLabel(),
                safe.issuance()
        )).withStyle(colorFor(safe.categoricalSeverity())));
        player.sendSystemMessage(Component.literal("Probabilities: " + safe.productLine()
                + (safe.tornadoCig() > 0 || safe.windCig() > 0 || safe.hailCig() > 0 ? " (hatched = significant severe)" : ""))
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Hazard: %s, %d storms, %d severe, max wind %.0f MPH",
                safe.hazard(),
                safe.stormCount(),
                safe.severeCount(),
                safe.maxWindMph()
        )).withStyle(ChatFormatting.GRAY));
        if (outbreakPotential(safe, snapshots)) {
            player.sendSystemMessage(Component.literal("Tornado outbreak setup: multiple tornado-capable storms possible.").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    private static List<ForecastPolygonSnapshot> buildPolygons(ServerLevel level, BlockPos pos, int range, List<RadarStormSnapshot> snapshots, ForecastRiskSnapshot risk) {
        ArrayList<ForecastPolygonSnapshot> result = new ArrayList<>();
        long gameTime = level.getGameTime();
        pruneAlertPolygonCache(level, gameTime);
        long expires = gameTime + Math.max(20L * 60L, Config.ALERT_COOLDOWN_TICKS.get());
        for (RadarStormSnapshot snapshot : snapshots) {
            if (result.size() >= 18) {
                break;
            }
            String label = snapshot.label() == null ? "" : snapshot.label().toLowerCase(Locale.ROOT);
            if (isWaterspoutLabel(label) || label.contains("satellite") || label.contains("landspout") || label.contains("dust devil") || label.contains("gustnado")) {
                continue;
            }
            boolean tornadoWarning = isEasTornado(snapshot, label);
            boolean severeWarning = !tornadoWarning && isSevereWarning(snapshot, label);
            if (tornadoWarning) {
                if (snapshot.windMph() >= 155.0D || snapshot.severity() >= SpcRisk.HIGH.severity()) {
                    result.add(pathPolygon(level, snapshot, "Tornado Emergency", "TOR-E", ForecastPolygonSnapshot.TYPE_WARNING, 0xF5D823FF, expires, 520.0D, 920.0D, 1400.0D));
                } else {
                    result.add(pathPolygon(level, snapshot, "Tornado Warning", "TOR", ForecastPolygonSnapshot.TYPE_WARNING, 0xF5FF3030, expires, 430.0D, 780.0D, 1200.0D));
                }
                continue;
            }
            if (severeWarning) {
                result.add(pathPolygon(level, snapshot, "Severe Thunderstorm Warning", "SVR", ForecastPolygonSnapshot.TYPE_WARNING, 0xF5FFD23F, expires, 520.0D, 980.0D, 1500.0D));
                continue;
            }
            if (snapshot.hookEcho() && snapshot.severity() >= SpcRisk.SLIGHT.severity()) {
                result.add(pathPolygon(level, snapshot, "Tornado Watch", "TOR WATCH", ForecastPolygonSnapshot.TYPE_WATCH, 0xE8F6C945, expires, 900.0D, 1500.0D, Math.min(Config.ALERT_WATCH_RANGE_BLOCKS.get(), 2200.0D)));
            } else if (snapshot.severity() >= SpcRisk.SLIGHT.severity() && (snapshot.windMph() >= 45.0D || snapshot.reflectivityDbz() >= 45.0D || label.contains("squall") || label.contains("supercell") || label.contains("hail"))) {
                result.add(pathPolygon(level, snapshot, "Severe Thunderstorm Watch", "SVR WATCH", ForecastPolygonSnapshot.TYPE_WATCH, 0xE8B8F15A, expires, 980.0D, 1650.0D, Math.min(Config.ALERT_WATCH_RANGE_BLOCKS.get(), 2400.0D)));
            }
        }
        addRiskPolygons(result, pos, range, risk, snapshots);
        return result.stream().limit(32).toList();
    }

    private static boolean isEasTornado(RadarStormSnapshot snapshot, String label) {
        return snapshot.hasConfirmedCirculation()
                && label.contains("tornado")
                && !label.contains("satellite")
                && !label.contains("landspout")
                && !label.contains("waterspout")
                && !label.contains("water spout")
                && !label.contains("gustnado")
                && !label.contains("dust");
    }

    private static boolean isWaterspoutLabel(String label) {
        return label.contains("waterspout") || label.contains("water spout");
    }

    private static boolean isSevereWarning(RadarStormSnapshot snapshot, String label) {
        return snapshot.windMph() >= 58.0D
                || snapshot.reflectivityDbz() >= 55.0D
                || label.contains("squall")
                || label.contains("supercell")
                || label.contains("hail")
                || label.contains("severe");
    }

    private static ForecastPolygonSnapshot pathPolygon(ServerLevel level, RadarStormSnapshot snapshot, String label, String product, int type, int color, long expires, double halfWidth, double futureHalfWidth, double maxHalfWidth) {
        Vec3 start = new Vec3(displayX(snapshot), 0.0D, displayZ(snapshot));
        Vec3 motion = new Vec3(snapshot.motionX(), 0.0D, snapshot.motionZ());
        Vec3 end = motion.horizontalDistanceSqr() < 1.0E-6D
                ? start.add(0.0D, 0.0D, -Math.max(120.0D, halfWidth))
                : start.add(motion.scale(Config.ALERT_WARNING_PATH_LEAD_TICKS.get()));
        Vec3 axis = end.subtract(start);
        if (axis.horizontalDistanceSqr() < 1.0E-6D) {
            axis = new Vec3(0.0D, 0.0D, -1.0D);
        }
        Vec3 direction = axis.normalize();
        double axisLength = Math.sqrt(axis.horizontalDistanceSqr());
        double minimumLead = Math.max(ALERT_POLYGON_MIN_LEAD_BLOCKS, futureHalfWidth * 1.35D);
        if (axisLength < minimumLead) {
            end = start.add(direction.scale(minimumLead));
            axis = end.subtract(start);
            direction = axis.normalize();
        }
        Vec3 normal = new Vec3(-direction.z, 0.0D, direction.x).normalize();
        double currentHalf = Math.min(maxHalfWidth, Math.max(halfWidth, snapshot.width() * 0.55D));
        double futureHalf = Math.min(maxHalfWidth, Math.max(futureHalfWidth, snapshot.width() * 0.85D));
        List<ForecastPolygonSnapshot.Point> points = List.of(
                new ForecastPolygonSnapshot.Point(start.x - normal.x * currentHalf, start.z - normal.z * currentHalf),
                new ForecastPolygonSnapshot.Point(start.x + normal.x * currentHalf, start.z + normal.z * currentHalf),
                new ForecastPolygonSnapshot.Point(end.x + normal.x * futureHalf, end.z + normal.z * futureHalf),
                new ForecastPolygonSnapshot.Point(end.x - normal.x * futureHalf, end.z - normal.z * futureHalf)
        );
        return stableAlertPolygon(level, snapshot, label, product, type, color, expires, start, end, direction, points);
    }

    private static ForecastPolygonSnapshot stableAlertPolygon(ServerLevel level, RadarStormSnapshot snapshot, String label, String product, int type, int color, long expires, Vec3 start, Vec3 end, Vec3 direction, List<ForecastPolygonSnapshot.Point> points) {
        long gameTime = level.getGameTime();
        String cacheKey = snapshot.id() + ":" + product + ":" + type;
        Map<String, CachedAlertPolygon> cache = ALERT_POLYGON_CACHE.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        CachedAlertPolygon cached = cache.get(cacheKey);
        if (cached != null && !alertPathChanged(cached, label, product, type, start, end, direction)) {
            CachedAlertPolygon touched = cached.touch(gameTime);
            cache.put(cacheKey, touched);
            return new ForecastPolygonSnapshot(label, product, type, color, expires, touched.points());
        }

        cache.put(cacheKey, new CachedAlertPolygon(points, start.x, start.z, end.x, end.z, direction.x, direction.z, label, product, type, gameTime));
        return new ForecastPolygonSnapshot(label, product, type, color, expires, points);
    }

    private static boolean alertPathChanged(CachedAlertPolygon cached, String label, String product, int type, Vec3 start, Vec3 end, Vec3 direction) {
        if (cached.type() != type || !cached.label().equals(label) || !cached.product().equals(product)) {
            return true;
        }
        double headingDot = cached.directionX() * direction.x + cached.directionZ() * direction.z;
        if (headingDot < ALERT_POLYGON_REISSUE_DIRECTION_DOT) {
            return true;
        }
        return false;
    }

    private static void pruneAlertPolygonCache(ServerLevel level, long gameTime) {
        Map<String, CachedAlertPolygon> cache = ALERT_POLYGON_CACHE.get(level.dimension());
        if (cache == null || cache.isEmpty()) {
            return;
        }
        cache.entrySet().removeIf(entry -> gameTime - entry.getValue().updatedAt() > ALERT_POLYGON_CACHE_TTL_TICKS);
        if (cache.isEmpty()) {
            ALERT_POLYGON_CACHE.remove(level.dimension());
        }
    }

    private static void addRiskPolygons(List<ForecastPolygonSnapshot> result, BlockPos pos, int range, ForecastRiskSnapshot risk, List<RadarStormSnapshot> snapshots) {
        ForecastRiskSnapshot safe = risk == null ? ForecastRiskSnapshot.EMPTY : risk;
        int severity = Math.max(0, Math.min(safe.categoricalSeverity(), SpcRisk.maxSeverity()));
        double centerX = pos.getX() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        for (int index = 1; index <= severity; index++) {
            SpcRisk spcRisk = SpcRisk.fromSeverity(index);
            double scale = switch (spcRisk) {
                case TSTM -> 0.92D;
                case MARGINAL -> 0.78D;
                case SLIGHT -> 0.63D;
                case ENHANCED -> 0.48D;
                case MODERATE -> 0.34D;
                case HIGH -> 0.22D;
                case EXTREME -> 0.15D;
                case HYPER -> 0.10D;
                default -> 0.0D;
            };
            if (scale <= 0.0D) {
                continue;
            }
            result.add(new ForecastPolygonSnapshot(
                    spcRisk.label() + " Risk",
                    spcRisk.label(),
                    ForecastPolygonSnapshot.TYPE_RISK,
                    withAlpha(spcRisk.color(), 88),
                    0L,
                    ellipse(centerX, centerZ, range * scale, range * scale * 0.62D, Math.toRadians(35.0D), 14)
            ));
        }
        if (safe.tornadoSeverity() >= SpcRisk.SLIGHT.severity()) {
            result.add(new ForecastPolygonSnapshot(
                    "Greatest Tornado Risk",
                    "TOR RISK",
                    ForecastPolygonSnapshot.TYPE_SIGNIFICANT_HAZARD,
                    0xF0D32F2F,
                    0L,
                    ellipse(centerX - range * 0.06D, centerZ - range * 0.02D, range * 0.36D, range * 0.21D, Math.toRadians(35.0D), 12)
            ));
        }
        if (safe.hailSeverity() >= SpcRisk.SLIGHT.severity()) {
            result.add(new ForecastPolygonSnapshot(
                    "Greatest 2+ Inch Hail Risk",
                    "HAIL 2+",
                    ForecastPolygonSnapshot.TYPE_SIGNIFICANT_HAZARD,
                    0xF02DBE59,
                    0L,
                    ellipse(centerX + range * 0.08D, centerZ + range * 0.04D, range * 0.42D, range * 0.24D, Math.toRadians(35.0D), 12)
            ));
        }
        if (outbreakPotential(safe, snapshots)) {
            result.add(new ForecastPolygonSnapshot(
                    "Tornado Outbreak Setup",
                    "OUTBREAK",
                    ForecastPolygonSnapshot.TYPE_SIGNIFICANT_HAZARD,
                    0xF0D828FF,
                    0L,
                    ellipse(centerX - range * 0.02D, centerZ - range * 0.01D, range * 0.52D, range * 0.27D, Math.toRadians(35.0D), 14)
            ));
        }
    }

    private static boolean outbreakPotential(ForecastRiskSnapshot risk, List<RadarStormSnapshot> snapshots) {
        ForecastRiskSnapshot safe = risk == null ? ForecastRiskSnapshot.EMPTY : risk;
        int tornadoCapable = 0;
        int tornadoObserved = 0;
        if (snapshots != null) {
            for (RadarStormSnapshot snapshot : snapshots) {
                if (snapshot == null) {
                    continue;
                }
                String label = snapshot.label() == null ? "" : snapshot.label().toLowerCase(Locale.ROOT);
                if (isSuppressedForecastReturn(label)) {
                    continue;
                }
                if (snapshot.hasConfirmedCirculation() || label.contains("tornado")) {
                    tornadoObserved++;
                }
                if (snapshot.hasConfirmedCirculation() || snapshot.hookEcho() || snapshot.windMph() >= 86.0D || Math.abs(snapshot.rotationMps()) >= 18.0D || label.contains("supercell")) {
                    tornadoCapable++;
                }
            }
        }
        return (safe.categoricalSeverity() >= SpcRisk.MODERATE.severity() && safe.tornadoSeverity() >= SpcRisk.ENHANCED.severity() && tornadoCapable >= 2)
                || safe.categoricalSeverity() >= SpcRisk.HIGH.severity()
                || tornadoObserved >= 3;
    }

    private static List<ForecastPolygonSnapshot.Point> ellipse(double centerX, double centerZ, double major, double minor, double angle, int pointCount) {
        ArrayList<ForecastPolygonSnapshot.Point> points = new ArrayList<>(pointCount);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (int index = 0; index < pointCount; index++) {
            double theta = Math.PI * 2.0D * index / pointCount;
            double localX = Math.cos(theta) * major;
            double localZ = Math.sin(theta) * minor;
            points.add(new ForecastPolygonSnapshot.Point(
                    centerX + localX * cos - localZ * sin,
                    centerZ + localX * sin + localZ * cos
            ));
        }
        return points;
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(alpha, 255)) << 24) | (color & 0x00FFFFFF);
    }

    private static String titleForMode(int mode) {
        return switch (mode) {
            case ForecastSnapshotPayload.MODE_TRACKS -> "Weather Forecast - Recent Tracks";
            case ForecastSnapshotPayload.MODE_RISK -> "Weather Forecast - SPC Risk Outlook";
            default -> "Weather Forecast - Classic Scan";
        };
    }

    private static String trackLine(BlockPos pos, ForecastTrackSnapshot track) {
        ForecastTrackSnapshot.Point last = lastPoint(track);
        int distance = last == null ? 0 : (int) Math.round(Math.sqrt(Math.pow(last.x() - (pos.getX() + 0.5D), 2.0D) + Math.pow(last.z() - (pos.getZ() + 0.5D), 2.0D)));
        return String.format(Locale.ROOT,
                "%s: %s, %d blocks, max %.0f mph, %.0f blocks wide",
                track.tornadoId(),
                track.label().isBlank() ? "Tornado Track" : track.label(),
                distance,
                track.maxWindMph(),
                track.maxWidthBlocks()
        );
    }

    private static ForecastTrackSnapshot.Point lastPoint(ForecastTrackSnapshot track) {
        List<ForecastTrackSnapshot.Point> points = track.points();
        return points == null || points.isEmpty() ? null : points.get(points.size() - 1);
    }

    private static void sendEnvironmentLine(Player player, WeatherManagerServer manager, BlockPos pos) {
        if (manager == null) {
            player.sendSystemMessage(Component.literal("Environment: Weather2 manager unavailable.").withStyle(ChatFormatting.RED));
            return;
        }

        float biomeChance = manager.getBiomeBasedStormSpawnChanceInArea(pos);
        float randomChance = deadlyRandomChancePerDay();
        String environment = biomeChance >= 0.45F ? "favorable" : biomeChance >= 0.20F ? "conditional" : "quiet";
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Environment: %s severe setup, biome %.1f%%, daily roll %.2f%%",
                environment,
                biomeChance * 100.0F,
                randomChance * 100.0F
        )).withStyle(ChatFormatting.GRAY));
    }

    private static void sendLowPressureLine(ServerLevel level, Player player, BlockPos pos) {
        LowPressureService.active(level).stream()
                .filter(system -> horizontalDistance(pos, system.x(), system.z()) <= Config.LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS.get())
                .min(Comparator.comparingDouble(system -> horizontalDistance(pos, system.x(), system.z())))
                .ifPresent(system -> player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "Synoptic: %s %d blocks away, cold/warm fronts, %.0f block circulation",
                        system.label(),
                        Math.round(horizontalDistance(pos, system.x(), system.z())),
                        system.radius()
                )).withStyle(ChatFormatting.RED)));
    }

    private static double horizontalDistance(BlockPos pos, double x, double z) {
        double dx = x - (pos.getX() + 0.5D);
        double dz = z - (pos.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static float deadlyRandomChancePerDay() {
        int day = 20 * 60 * 20;
        int diceRollRate = Math.max(1, ConfigStorm.Storm_AllTypes_TickRateDelay);
        int odds = ConfigStorm.Server_Storm_Deadly_UseGlobalRate
                ? ConfigStorm.Server_Storm_Deadly_OddsTo1_Land_Based
                : ConfigStorm.Player_Storm_Deadly_OddsTo1_Land_Based;
        if (odds <= 0) {
            return 0.0F;
        }
        return ((float) day / (float) diceRollRate) / (float) odds;
    }

    private static String lineFor(BlockPos pos, RadarStormSnapshot snapshot) {
        String signal = snapshot.hasConfirmedCirculation()
                ? " confirmed circulation"
                : snapshot.hookEcho()
                ? " hook/rotation"
                : "";
        String stage = snapshot.stageLabel().isBlank() ? "no stage" : snapshot.stageLabel();
        return String.format(Locale.ROOT,
                "%s: %d blocks %s, %s, %.0f dBZ, %.0f mph wind, %.1f m/s rot%s",
                snapshot.label(),
                Math.round(distance(pos, snapshot)),
                bearing(pos, snapshot),
                stage,
                snapshot.reflectivityDbz(),
                snapshot.windMph(),
                Math.abs(snapshot.rotationMps()),
                signal
        );
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
        return snapshot.severity() >= SpcRisk.TSTM.severity()
                || snapshot.windMph() >= 45.0D
                || Math.abs(snapshot.rotationMps()) >= 8.0D
                || snapshot.reflectivityDbz() >= 14.0D;
    }

    private static boolean isSuppressedForecastReturn(String label) {
        return label.contains("waterspout")
                || label.contains("water spout")
                || label.contains("landspout")
                || label.contains("dust devil");
    }

    private static double distance(BlockPos pos, RadarStormSnapshot snapshot) {
        double dx = displayX(snapshot) - (pos.getX() + 0.5D);
        double dz = displayZ(snapshot) - (pos.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String bearing(BlockPos pos, RadarStormSnapshot snapshot) {
        double dx = displayX(snapshot) - (pos.getX() + 0.5D);
        double dz = displayZ(snapshot) - (pos.getZ() + 0.5D);
        double degrees = Mth.wrapDegrees(Math.toDegrees(Math.atan2(dx, -dz)));
        if (degrees < 0.0D) {
            degrees += 360.0D;
        }
        String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return labels[(int) Math.floor((degrees + 22.5D) / 45.0D) & 7];
    }

    private static double displayX(RadarStormSnapshot snapshot) {
        return snapshot.hasConfirmedCirculation() ? snapshot.circulationX() : snapshot.hookEcho() ? snapshot.hookX() : snapshot.x();
    }

    private static double displayZ(RadarStormSnapshot snapshot) {
        return snapshot.hasConfirmedCirculation() ? snapshot.circulationZ() : snapshot.hookEcho() ? snapshot.hookZ() : snapshot.z();
    }

    private static ChatFormatting colorFor(int severity) {
        SpcRisk risk = SpcRisk.fromSeverity(severity);
        return switch (risk) {
            case HYPER, EXTREME -> ChatFormatting.LIGHT_PURPLE;
            case HIGH, MODERATE -> ChatFormatting.RED;
            case ENHANCED -> ChatFormatting.GOLD;
            case SLIGHT -> ChatFormatting.YELLOW;
            case MARGINAL -> ChatFormatting.GREEN;
            case TSTM -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
    }

    private static boolean isForecastBlock(ServerLevel level, BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) {
            return false;
        }
        var block = level.getBlockState(pos).getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return WEATHER_FORECAST_ID.equals(id) || "weather2.block.ForecastBlock".equals(block.getClass().getName());
    }

    private record CachedAlertPolygon(
            List<ForecastPolygonSnapshot.Point> points,
            double startX,
            double startZ,
            double endX,
            double endZ,
            double directionX,
            double directionZ,
            String label,
            String product,
            int type,
            long updatedAt
    ) {
        private CachedAlertPolygon touch(long gameTime) {
            return new CachedAlertPolygon(points, startX, startZ, endX, endZ, directionX, directionZ, label, product, type, gameTime);
        }
    }

    private record ActiveDisplay(BlockPos origin, int range, int mode, long expiresTick, long lastSyncTick) {
    }

    private record ForecastModeState(BlockPos origin, int mode, long lastUseTick) {
    }
}
