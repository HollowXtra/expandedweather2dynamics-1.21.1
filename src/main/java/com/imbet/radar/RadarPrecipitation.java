package com.imbet.radar;

import com.imbet.weather.PrecipitationField;
import net.minecraft.world.phys.Vec3;

/**
 * A storm's precipitation field as it travels to a radar display: the field's own numbers and the storm's frame.
 *
 * <p>The field is what the rain in the world is drawn from, so a scope that draws its reflectivity from the same field
 * shows the rain that is really falling - the forward-flank core where the curtains hang, the hook where the rain wraps
 * the tornado, the notch where the inflow is clear - instead of a shape of its own that only looked like a storm.
 */
public final class RadarPrecipitation {
    /** Fifteen numbers of field, then the storm's forward and right axes. */
    public static final int LENGTH = 20;

    private RadarPrecipitation() {
    }

    public static float[] pack(PrecipitationField.Geometry field, Vec3 forward, Vec3 right) {
        if (field == null || forward == null || right == null) {
            return new float[0];
        }
        return new float[] {
                (float) field.radius(),
                (float) field.mesoForward(), (float) field.mesoRight(),
                (float) field.coreForward(), (float) field.coreRight(),
                (float) field.notchForward(), (float) field.notchRight(),
                (float) field.wrapForward(), (float) field.wrapRight(),
                (float) field.rearFlankForward(), (float) field.rearFlankRight(),
                (float) field.coreStrength(), (float) field.hookStrength(), (float) field.notchStrength(),
                (float) field.rearStrength(), (float) field.stratiformStrength(),
                (float) forward.x, (float) forward.z,
                (float) right.x, (float) right.z
        };
    }

    public static PrecipitationField.Geometry geometry(float[] packed) {
        return new PrecipitationField.Geometry(packed[0], packed[1], packed[2], packed[3], packed[4], packed[5], packed[6],
                packed[7], packed[8], packed[9], packed[10], packed[11], packed[12], packed[13], packed[14], packed[15]);
    }

    public static double forwardX(float[] packed) {
        return packed[16];
    }

    public static double forwardZ(float[] packed) {
        return packed[17];
    }

    public static double rightX(float[] packed) {
        return packed[18];
    }

    public static double rightZ(float[] packed) {
        return packed[19];
    }

    // ---------------------------------------------------------------- a squall line

    /** A squall line instead of a cell: its two ends, its thickness, how far its middle bows forward, and its heading. */
    public static final int LINE_LENGTH = 8;

    public static float[] packLine(Vec3 start, Vec3 end, double thickness, double bow, Vec3 forward) {
        if (start == null || end == null || forward == null) {
            return new float[0];
        }
        return new float[] {
                (float) start.x, (float) start.z, (float) end.x, (float) end.z,
                (float) thickness, (float) bow, (float) forward.x, (float) forward.z
        };
    }
}
