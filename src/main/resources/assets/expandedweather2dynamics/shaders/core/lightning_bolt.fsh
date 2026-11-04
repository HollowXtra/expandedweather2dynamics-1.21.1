#version 150

uniform vec4 ColorModulator;
uniform float LightningAge;
uniform float LightningSeed;
uniform float LightningGain;
uniform float LightningDistance;
uniform float LightningCore;
uniform float LightningFilaments;
uniform float LightningStrobes;
uniform float LightningHeight;
uniform float LightningRange;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// The V coordinate LightningBoltShape writes for the patch of lit ground. The channel's own V only
// ever runs from 0 to 1, so anything above this is the ground pass rather than the channel.
const float GROUND_GLOW_V = 1.2;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

// One stroke of a flash: it arrives at once and decays. A real strike is not one event but three to
// five of these, forty to eighty milliseconds apart, which is why a bolt flickers instead of glowing
// steadily - and why the envelope below is a sum of them rather than a single fade.
float stroke(float age, float start, float amplitude, float decay) {
    float since = age - start;
    if (since < 0.0) {
        return 0.0;
    }
    return amplitude * exp(-since * decay);
}

float flashEnvelope(float age) {
    float envelope = stroke(age, 0.000, 1.00, 30.0)
            + stroke(age, 0.045, 0.74, 33.0)
            + stroke(age, 0.105, 0.56, 36.0)
            + stroke(age, 0.190, 0.34, 40.0);
    // After the last stroke the channel itself cools over a few hundred milliseconds. That afterglow
    // is what a nearby strike leaves in the air, and without it the bolt would cut to black. Its
    // amplitude is set so the firing point lands on the strike's ceiling rather than over it, and its
    // time constant is a third of a second: at a third of a second the channel is still at six per
    // cent of its peak, which is the faint line a nearby strike leaves behind.
    return min(envelope + stroke(age, 0.000, 0.15, 2.9), 1.15);
}

void main() {
    float along = texCoord0.y;
    bool groundGlow = along > GROUND_GLOW_V;

    // Cross-section: 0 on the channel's axis, 1 at the edge of the ribbon.
    float cross = abs(texCoord0.x * 2.0 - 1.0);

    // The halo pass is wide and soft, the core pass narrow and hard, and the geometry says which is
    // which through its alpha. One profile and two exponents, so one program shades both.
    float passAlpha = clamp(vertexColor.a, 0.02, 1.0);
    float tightness = mix(1.5, 5.2, smoothstep(0.30, 0.95, passAlpha));
    float profile = pow(max(1.0 - cross, 0.0), tightness);

    // Strobe strength of zero means a steady channel - which is what switching the flicker off asks
    // for - and not a bolt that never appears.
    float envelope = mix(1.0, max(flashEnvelope(LightningAge), 0.0), clamp(LightningStrobes, 0.0, 1.0));

    float filament = 1.0;
    float travel = 1.0;
    if (!groundGlow) {
        // The channel's filaments are fixed for the life of the strike: a channel does not writhe, it
        // flickers. Keying them to how far up the channel a fragment is keeps every thread where it
        // started, while the strobe above does the moving. The thread density is scaled by the
        // channel's real height, so a bolt stretched to three hundred blocks has threads the same
        // physical size as a vanilla-height one instead of being smeared along its length.
        float blocks = max(along, 0.0) * max(LightningHeight, 1.0) / 112.0;
        float threads = noise(vec2(blocks * 34.0 + LightningSeed * 19.0, cross * 3.4 + LightningSeed * 37.0));
        float fine = noise(vec2(blocks * 96.0 + LightningSeed * 7.0, cross * 9.0));
        filament = mix(1.0, clamp(0.34 + 1.30 * (threads * 0.68 + fine * 0.32), 0.0, 1.9),
                clamp(LightningFilaments, 0.0, 1.0));

        // The return stroke climbs the channel from the ground, and the strokes after it follow the
        // same path down again, so one bright band travels up and a fainter one runs back down. Both
        // are keyed to the strike's own age rather than to the world clock, so a given strike always
        // propagates the same way.
        float up = fract(along * 1.35 - LightningAge * 2.30 - LightningSeed * 5.0);
        float down = fract(-along * 1.35 + LightningAge * 1.45 + LightningSeed * 3.0);
        float upBand = 1.0 - smoothstep(0.04, 0.26, abs(up - 0.5));
        float downBand = 1.0 - smoothstep(0.05, 0.34, abs(down - 0.5));
        travel = 0.80 + 0.34 * upBand + 0.14 * downBand;
    }

    // Aerial extinction. Past a few hundred blocks a channel is a light source seen through air
    // rather than a crisp white line, so it dims and cools toward the colour of the haze.
    float extinction = 1.0 / (1.0 + LightningDistance * 0.0042);
    float haze = clamp(LightningDistance / 340.0, 0.0, 1.0);

    // The far end of the channel's own fade, which is what makes the pass's cutoff invisible: the
    // strike is down to nothing by the distance the geometry stops being built at, so a bolt does not
    // wink out as the eye crosses a threshold. Past it a distant strike is the sky flash, which is
    // what a real one a kilometre away is.
    float rangeFade = 1.0 - smoothstep(LightningRange * 0.55, LightningRange, LightningDistance);

    // Hot core over a cool halo. The geometry tints the two passes; this lifts whichever pass is on
    // the channel's axis toward white, which is what makes the core read as hotter air rather than as
    // brighter paint.
    float heat = clamp(LightningCore, 0.0, 2.0);
    float coreMix = clamp(pow(profile, 0.85) * heat, 0.0, 1.0);
    vec3 tint = mix(vertexColor.rgb, vec3(1.00, 0.975, 0.935), coreMix);
    tint = mix(tint, tint * vec3(0.74, 0.82, 1.06), haze * 0.65);

    float gain = max(LightningGain, 0.0) * extinction * rangeFade;
    float alpha = passAlpha * profile * filament * travel * envelope * gain;
    if (groundGlow) {
        // Lit ground is a reflection of the channel rather than a second channel: softer, without the
        // threads, and flashing only while the strike is actually burning.
        alpha = passAlpha * profile * envelope * gain * 0.90;
    }
    if (alpha < 0.004) {
        discard;
    }
    fragColor = vec4(tint * (0.62 + 0.38 * coreMix), alpha) * ColorModulator;
}
