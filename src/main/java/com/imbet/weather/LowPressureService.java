package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.LowPressureSyncPayload;
import com.imbet.network.LowPressureSyncPayload.LowPressureSnapshot;
import com.imbet.network.NetworkSafety;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class LowPressureService {
    private static final Map<ResourceKey<Level>, List<LowPressureSnapshot>> SYSTEMS = new HashMap<>();

    private LowPressureService() {
    }

    public static void tick(ServerLevel level) {
        if (!Config.LOW_PRESSURE_ENABLED.get()) {
            SYSTEMS.remove(level.dimension());
            return;
        }
        int interval = Math.max(20, Config.LOW_PRESSURE_SCAN_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }

        List<LowPressureSnapshot> systems = derive(level);
        SYSTEMS.put(level.dimension(), systems);
        LowPressureSyncPayload payload = new LowPressureSyncPayload(systems);
        for (var player : level.players()) {
            NetworkSafety.sendToPlayer(player, payload);
        }
    }

    public static List<LowPressureSnapshot> active(ServerLevel level) {
        return SYSTEMS.getOrDefault(level.dimension(), List.of());
    }

    private static List<LowPressureSnapshot> derive(ServerLevel level) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return List.of();
        }
        List<Cell> cells = new ArrayList<>();
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            if (!isOrganizedCell(storm)) {
                continue;
            }
            cells.add(new Cell(storm, severityWeight(storm)));
        }
        int minStorms = Config.LOW_PRESSURE_MIN_STORMS.get();
        boolean intenseSingleCell = cells.stream().anyMatch(cell -> cell.weight() >= 5.8D);
        if (cells.size() < minStorms && !intenseSingleCell) {
            return List.of();
        }

        List<Cluster> clusters = new ArrayList<>();
        for (Cell cell : cells.stream().sorted(Comparator.comparingDouble(Cell::weight).reversed()).toList()) {
            Cluster best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Cluster cluster : clusters) {
                double distance = cluster.distanceTo(cell.storm().pos);
                if (distance < 1425.0D && distance < bestDistance) {
                    best = cluster;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                best = new Cluster();
                clusters.add(best);
            }
            best.add(cell);
        }

        List<LowPressureSnapshot> snapshots = new ArrayList<>();
        for (Cluster cluster : clusters) {
            if (cluster.cells.size() < minStorms && cluster.sumWeight < 5.8D) {
                continue;
            }
            snapshots.add(cluster.toSnapshot(level.getGameTime()));
        }
        snapshots.sort(Comparator.comparingDouble(LowPressureSnapshot::intensity).reversed());
        return snapshots.stream().limit(8).toList();
    }

    private static boolean isOrganizedCell(StormObject storm) {
        return storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                || storm.isPrecipitating()
                || storm.attrib_precipitation
                || DynamicStormController.isDynamicVortex(storm);
    }

    private static double severityWeight(StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double stage = Math.max(0, storm.levelCurIntensityStage - StormObject.STATE_NORMAL);
        double tornado = DynamicStormController.isDynamicVortex(storm) ? 3.2D : 0.0D;
        double hail = storm.levelCurIntensityStage >= StormObject.STATE_HAIL ? 1.4D : 0.0D;
        double meso = Math.max(state.mesocycloneStrength(), state.wallCloudRotationStrength()) * 2.2D;
        return 1.0D + stage * 0.62D + tornado + hail + meso;
    }

    private record Cell(StormObject storm, double weight) {
    }

    private static final class Cluster {
        private final List<Cell> cells = new ArrayList<>();
        private double sumWeight;
        private double x;
        private double z;
        private double motionX;
        private double motionZ;

        private void add(Cell cell) {
            double weight = Math.max(0.1D, cell.weight());
            cells.add(cell);
            sumWeight += weight;
            x += cell.storm().pos.x * weight;
            z += cell.storm().pos.z * weight;
            if (cell.storm().motion != null) {
                motionX += cell.storm().motion.x * weight;
                motionZ += cell.storm().motion.z * weight;
            }
        }

        private double centerX() {
            return sumWeight <= 0.0D ? 0.0D : x / sumWeight;
        }

        private double centerZ() {
            return sumWeight <= 0.0D ? 0.0D : z / sumWeight;
        }

        private double distanceTo(Vec3 pos) {
            double dx = centerX() - pos.x;
            double dz = centerZ() - pos.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        private LowPressureSnapshot toSnapshot(long gameTime) {
            double centerX = centerX();
            double centerZ = centerZ();
            double radius = 560.0D;
            double maxStage = 0.0D;
            Iterator<Cell> iterator = cells.iterator();
            while (iterator.hasNext()) {
                Cell cell = iterator.next();
                double dx = cell.storm().pos.x - centerX;
                double dz = cell.storm().pos.z - centerZ;
                radius = Math.max(radius, Math.sqrt(dx * dx + dz * dz) + Math.max(220.0D, cell.storm().getSize() * 1.35D));
                maxStage = Math.max(maxStage, cell.storm().levelCurIntensityStage);
            }
            float intensity = Mth.clamp((float) ((sumWeight / Math.max(1, cells.size())) / 5.6D + cells.size() * 0.12D), 0.0F, 1.0F);
            String label = intensity >= 0.82F || maxStage >= StormObject.STATE_STAGE1 ? "Cyclone Low" : maxStage >= StormObject.STATE_HAIL || intensity >= 0.62F ? "Deep Low" : "Low";
            long id = 31L * gameTime + Math.round(centerX) * 7349L + Math.round(centerZ) * 9151L;
            return new LowPressureSnapshot(
                    id,
                    centerX,
                    centerZ,
                    (float) Mth.clamp(radius, 520.0D, 2600.0D),
                    intensity,
                    sumWeight <= 0.0D ? 0.0D : motionX / sumWeight,
                    sumWeight <= 0.0D ? 0.0D : motionZ / sumWeight,
                    label
            );
        }
    }
}
