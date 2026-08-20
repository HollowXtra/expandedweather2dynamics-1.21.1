package com.imbet.weather.vortex;

import com.imbet.weather.TornadoWindField;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class MutableWindSample {
    public long sourceId = Long.MIN_VALUE;
    public double mphX;
    public double mphY;
    public double mphZ;
    public double speedMph;
    public double strongestLocalMph;
    public double distanceFromCore = Double.MAX_VALUE;
    public double baseRadius;
    public double radiusMaxWind;

    public MutableWindSample clear() {
        sourceId = Long.MIN_VALUE;
        mphX = 0.0D;
        mphY = 0.0D;
        mphZ = 0.0D;
        speedMph = 0.0D;
        strongestLocalMph = 0.0D;
        distanceFromCore = Double.MAX_VALUE;
        baseRadius = 0.0D;
        radiusMaxWind = 0.0D;
        return this;
    }

    public void set(long sourceId, double mphX, double mphY, double mphZ, double speedMph, double strongestLocalMph, double distanceFromCore, double baseRadius, double radiusMaxWind) {
        this.sourceId = sourceId;
        this.mphX = finite(mphX);
        this.mphY = finite(mphY);
        this.mphZ = finite(mphZ);
        this.speedMph = finite(speedMph);
        this.strongestLocalMph = finite(strongestLocalMph);
        this.distanceFromCore = finite(distanceFromCore);
        this.baseRadius = finite(baseRadius);
        this.radiusMaxWind = finite(radiusMaxWind);
    }

    public void setIfStronger(long sourceId, double mphX, double mphY, double mphZ, double speedMph, double strongestLocalMph, double distanceFromCore, double baseRadius, double radiusMaxWind) {
        if (speedMph > this.speedMph) {
            set(sourceId, mphX, mphY, mphZ, speedMph, strongestLocalMph, distanceFromCore, baseRadius, radiusMaxWind);
        }
    }

    public Vec3 mphVector() {
        return new Vec3(mphX, mphY, mphZ);
    }

    public TornadoWindField.WindSample toWindSample() {
        if (speedMph <= 0.0D) {
            return TornadoWindField.WindSample.CALM;
        }
        return new TornadoWindField.WindSample(mphVector(), speedMph, strongestLocalMph, distanceFromCore, baseRadius, radiusMaxWind);
    }

    public int redstonePower() {
        return Mth.clamp((int) Math.round(speedMph / 20.0D), 0, 15);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }
}
