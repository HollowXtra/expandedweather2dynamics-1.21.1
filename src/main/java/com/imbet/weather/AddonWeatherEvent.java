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
            motion = motion.scale(0.985D);
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
}
