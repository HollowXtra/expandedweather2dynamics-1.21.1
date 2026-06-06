package com.imbet.weather;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

public final class AddonWeatherEvent {
    private final long id;
    private final StormSubtype type;
    private Vec3 position;
    private Vec3 motion;
    private final float radius;
    private final float maxWindMph;
    private final long spawnTick;
    private long expireTick;
    private Vec3 lineStart;
    private Vec3 lineEnd;
    private long lastAlertTick = -1_000_000L;

    public AddonWeatherEvent(long id, StormSubtype type, Vec3 position, Vec3 motion, float radius, float maxWindMph, long spawnTick, long expireTick) {
        this.id = id;
        this.type = type == null ? StormSubtype.NONE : type;
        this.position = safeVec(position);
        this.motion = safeVec(motion);
        this.radius = finite(radius);
        this.maxWindMph = finite(maxWindMph);
        this.spawnTick = spawnTick;
        this.expireTick = expireTick;
    }

    public static AddonWeatherEvent squallLine(long id, Vec3 start, Vec3 end, Vec3 motion, float thickness, float maxWindMph, long gameTime, long expireTick) {
        AddonWeatherEvent event = new AddonWeatherEvent(id, StormSubtype.SQUALL_LINE, start.add(end).scale(0.5D), motion, thickness, maxWindMph, gameTime, expireTick);
        event.lineStart = start;
        event.lineEnd = end;
        return event;
    }

    public long id() {
        return id;
    }

    public StormSubtype type() {
        return type;
    }

    public Vec3 position() {
        return position;
    }

    public Vec3 motion() {
        return motion;
    }

    public void setPosition(Vec3 position) {
        this.position = safeVec(position);
    }

    public void setMotion(Vec3 motion) {
        this.motion = safeVec(motion);
    }

    public float radius() {
        return radius;
    }

    public float maxWindMph() {
        return maxWindMph;
    }

    public long spawnTick() {
        return spawnTick;
    }

    public long expireTick() {
        return expireTick;
    }

    public Vec3 lineStart() {
        return lineStart;
    }

    public Vec3 lineEnd() {
        return lineEnd;
    }

    public boolean isLine() {
        return lineStart != null && lineEnd != null;
    }

    public long lastAlertTick() {
        return lastAlertTick;
    }

    public void setLastAlertTick(long lastAlertTick) {
        this.lastAlertTick = lastAlertTick;
    }

    public void tick() {
        if (!isLine()) {
            position = position.add(motion);
            motion = keepMinimumHorizontalMotion(motion.scale(motionDrag()));
        } else {
            position = position.add(motion);
            lineStart = lineStart.add(motion);
            lineEnd = lineEnd.add(motion);
        }
    }

    public void refreshLine(Vec3 start, Vec3 end, Vec3 newMotion, long newExpireTick) {
        lineStart = start;
        lineEnd = end;
        position = start.add(end).scale(0.5D);
        motion = newMotion;
        expireTick = Math.max(expireTick, newExpireTick);
    }

    public AddonWeatherEvent snapshot() {
        AddonWeatherEvent copy = new AddonWeatherEvent(id, type, position, motion, radius, maxWindMph, spawnTick, expireTick);
        copy.lastAlertTick = lastAlertTick;
        if (isLine()) {
            copy.lineStart = safeVec(lineStart);
            copy.lineEnd = safeVec(lineEnd);
        }
        return copy;
    }

    public boolean expired(long gameTime) {
        return gameTime >= expireTick;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(id);
        buf.writeEnum(type);
        writeVec(buf, position);
        writeVec(buf, motion);
        buf.writeFloat(radius);
        buf.writeFloat(maxWindMph);
        buf.writeLong(spawnTick);
        buf.writeLong(expireTick);
        buf.writeBoolean(isLine());
        if (isLine()) {
            writeVec(buf, lineStart);
            writeVec(buf, lineEnd);
        }
    }

    public static AddonWeatherEvent read(FriendlyByteBuf buf) {
        long id = buf.readLong();
        StormSubtype type = buf.readEnum(StormSubtype.class);
        Vec3 position = readVec(buf);
        Vec3 motion = readVec(buf);
        float radius = buf.readFloat();
        float maxWindMph = buf.readFloat();
        long spawnTick = buf.readLong();
        long expireTick = buf.readLong();
        AddonWeatherEvent event = new AddonWeatherEvent(id, type, position, motion, radius, maxWindMph, spawnTick, expireTick);
        if (buf.readBoolean()) {
            event.lineStart = readVec(buf);
            event.lineEnd = readVec(buf);
        }
        return event;
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 vec) {
        Vec3 safe = safeVec(vec);
        buf.writeDouble(safe.x);
        buf.writeDouble(safe.y);
        buf.writeDouble(safe.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return safeVec(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
    }

    private static Vec3 safeVec(Vec3 vec) {
        if (vec == null || !Double.isFinite(vec.x) || !Double.isFinite(vec.y) || !Double.isFinite(vec.z)) {
            return Vec3.ZERO;
        }
        return vec;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private double motionDrag() {
        return switch (type) {
            case FLAMMAGENITUS -> 0.9996D;
            case HURRICANE -> 0.9995D;
            case WILDFIRE -> 0.999D;
            case FLOATER_TORNADO -> 0.9965D;
            case WATERSPOUT -> 0.9975D;
            case LANDSPOUT -> 0.996D;
            case FIRE_WHIRL -> 0.993D;
            case GUSTNADO -> 0.992D;
            case SNOW_DEVIL -> 0.990D;
            case DUST_DEVIL -> 0.990D;
            default -> 0.985D;
        };
    }

    private Vec3 keepMinimumHorizontalMotion(Vec3 dampedMotion) {
        double minSpeed = switch (type) {
            case GUSTNADO -> 0.034D;
            case FIRE_WHIRL -> 0.020D;
            case FLOATER_TORNADO -> 0.018D;
            case FLAMMAGENITUS -> 0.006D;
            case WILDFIRE -> 0.004D;
            case SNOW_DEVIL -> 0.010D;
            case DUST_DEVIL -> 0.010D;
            case LANDSPOUT -> 0.014D;
            case WATERSPOUT -> 0.012D;
            default -> 0.0D;
        };
        if (minSpeed <= 0.0D || dampedMotion == null) {
            return safeVec(dampedMotion);
        }
        double horizontal = Math.sqrt(dampedMotion.x * dampedMotion.x + dampedMotion.z * dampedMotion.z);
        if (horizontal < 1.0E-5D) {
            return Vec3.ZERO;
        }
        if (horizontal >= minSpeed) {
            return dampedMotion;
        }
        double scale = minSpeed / horizontal;
        return new Vec3(dampedMotion.x * scale, dampedMotion.y, dampedMotion.z * scale);
    }
}
