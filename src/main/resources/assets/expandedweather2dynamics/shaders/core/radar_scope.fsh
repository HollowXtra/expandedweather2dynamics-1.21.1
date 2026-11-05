#version 150

// A weather radar display, generated from the data rather than painted cell by cell.
//
// RadarFieldTexture composites everything on a sweep into one sheet of product values; this reads that sheet the way
// a real display reads its radials. The value at a pixel is taken at the centre of its range gate, so the picture has
// the fine wedge-shaped grain of real data, widening with range as a beam does. It is coloured through the actual
// NWS tables rather than through a guessed ramp. The new sweep paints itself in behind the beam: ahead of the beam is
// still the previous scan, which is why a storm on a real scope jumps forward as the antenna comes round to it.
//
// Under it is a map - county lines, roads and a river or two, anchored to the world so they stay put when the range
// changes - and over it is the glass, which is where the panel stops looking like a texture and starts looking lit.

uniform sampler2D Sampler0;
uniform float GameTime;
uniform vec4 ColorModulator;

uniform vec2 WorldCentre;     // the block the radar sweeps from
uniform float RangeBlocks;    // the distance the scope's rim stands for
uniform float Product;        // 0 reflectivity, 1 velocity, 2 risk
uniform float SweepAngle;     // where the beam is now, radians
uniform float ScanStart;      // where the beam was when this scan's data arrived
uniform float ScanCovered;    // how far round it has come since, radians
uniform float RingRadius;     // the rim's radius, as a fraction of half the box
uniform float GlassAmount;    // how much surface the panel has: reflections, grid, vignette
uniform float Chart;          // 1 for a forecaster's map: square, composite, with no beam going round on it

in vec2 face;
in vec4 vertexColor;

out vec4 fragColor;

const float TAU = 6.2831853;
const float NO_DATA = 0.004;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

/** A line of one screen pixel at a target value, whatever the scale, so it never breaks into dots or swells. */
float hairline(float value, float target, float pixel, float weight) {
    float width = pixel * weight;
    return 1.0 - smoothstep(width * 0.5, width * 1.5, abs(value - target));
}

/**
 * The NWS base reflectivity table. Band colours with a one dB blend between them: a real product is banded, and those
 * bands are how the eye reads a gradient of intensity across a storm at a glance.
 */
vec3 reflectivityColour(float dbz) {
    if (dbz < 10.0) return mix(vec3(0.015, 0.45, 0.62), vec3(0.016, 0.914, 0.906), smoothstep(5.0, 10.0, dbz));
    if (dbz < 15.0) return mix(vec3(0.004, 0.624, 0.957), vec3(0.012, 0.0, 0.957), smoothstep(14.0, 15.0, dbz));
    if (dbz < 20.0) return mix(vec3(0.012, 0.0, 0.957), vec3(0.008, 0.992, 0.008), smoothstep(19.0, 20.0, dbz));
    if (dbz < 25.0) return mix(vec3(0.008, 0.992, 0.008), vec3(0.004, 0.773, 0.004), smoothstep(24.0, 25.0, dbz));
    if (dbz < 30.0) return mix(vec3(0.004, 0.773, 0.004), vec3(0.0, 0.557, 0.0), smoothstep(29.0, 30.0, dbz));
    if (dbz < 35.0) return mix(vec3(0.0, 0.557, 0.0), vec3(0.992, 0.973, 0.008), smoothstep(34.0, 35.0, dbz));
    if (dbz < 40.0) return mix(vec3(0.992, 0.973, 0.008), vec3(0.898, 0.737, 0.0), smoothstep(39.0, 40.0, dbz));
    if (dbz < 45.0) return mix(vec3(0.898, 0.737, 0.0), vec3(0.992, 0.584, 0.0), smoothstep(44.0, 45.0, dbz));
    if (dbz < 50.0) return mix(vec3(0.992, 0.584, 0.0), vec3(0.992, 0.0, 0.0), smoothstep(49.0, 50.0, dbz));
    if (dbz < 55.0) return mix(vec3(0.992, 0.0, 0.0), vec3(0.831, 0.0, 0.0), smoothstep(54.0, 55.0, dbz));
    if (dbz < 60.0) return mix(vec3(0.831, 0.0, 0.0), vec3(0.737, 0.0, 0.0), smoothstep(59.0, 60.0, dbz));
    if (dbz < 65.0) return mix(vec3(0.737, 0.0, 0.0), vec3(0.973, 0.0, 0.992), smoothstep(64.0, 65.0, dbz));
    if (dbz < 70.0) return mix(vec3(0.973, 0.0, 0.992), vec3(0.596, 0.329, 0.776), smoothstep(69.0, 70.0, dbz));
    return mix(vec3(0.596, 0.329, 0.776), vec3(0.992, 0.992, 0.992), smoothstep(70.0, 75.0, dbz));
}

/** Base velocity, in m/s: greens are inbound, reds outbound, and the near-zero band goes grey rather than to black. */
vec3 velocityColour(float v) {
    float m = abs(v);
    vec3 weak = v < 0.0 ? vec3(0.33, 0.46, 0.33) : vec3(0.46, 0.31, 0.31);
    vec3 mid = v < 0.0 ? vec3(0.02, 0.56, 0.06) : vec3(0.64, 0.02, 0.02);
    vec3 strong = v < 0.0 ? vec3(0.01, 0.99, 0.02) : vec3(0.99, 0.02, 0.02);
    vec3 extreme = v < 0.0 ? vec3(0.65, 1.0, 0.72) : vec3(1.0, 0.48, 0.72);
    vec3 c = mix(weak, mid, smoothstep(2.0, 12.0, m));
    c = mix(c, strong, smoothstep(12.0, 32.0, m));
    return mix(c, extreme, smoothstep(40.0, 58.0, m));
}

vec3 riskColour(float severity) {
    if (severity < 0.5) return vec3(0.25, 0.31, 0.35);
    if (severity < 1.5) return vec3(0.56, 0.82, 0.37);
    if (severity < 2.5) return vec3(0.28, 0.84, 0.36);
    if (severity < 3.5) return vec3(0.96, 0.84, 0.26);
    if (severity < 4.5) return vec3(0.94, 0.56, 0.14);
    if (severity < 5.5) return vec3(0.91, 0.23, 0.18);
    if (severity < 6.5) return vec3(1.0, 0.36, 1.0);
    if (severity < 7.5) return vec3(0.61, 0.12, 0.88);
    return vec3(0.91, 0.85, 1.0);
}

/** The basemap: world-anchored county lines with a surveyor's wander in them, a road net, and rivers. */
vec3 basemap(vec2 world, float pixelBlocks) {
    vec3 ground = vec3(0.020, 0.027, 0.034);
    // A little relief, so the land is not a flat colour under everything.
    ground += vec3(0.010, 0.012, 0.008) * noise(world / 620.0) ;

    vec2 warp = vec2(noise(world / 760.0 + 3.1), noise(world / 760.0 + 17.7)) * 90.0;
    vec2 county = (world + warp) / 512.0;
    vec2 cell = abs(fract(county + 0.5) - 0.5) * 512.0;
    float counties = max(hairline(cell.x, 0.0, pixelBlocks, 1.0), hairline(cell.y, 0.0, pixelBlocks, 1.0));

    vec2 roadWarp = vec2(noise(world / 340.0 + 41.0), noise(world / 340.0 + 73.0)) * 150.0;
    vec2 road = (world + roadWarp) / 1436.0;
    vec2 roadCell = abs(fract(road + 0.5) - 0.5) * 1436.0;
    float roads = max(hairline(roadCell.x, 0.0, pixelBlocks, 1.3), hairline(roadCell.y, 0.0, pixelBlocks, 1.3));

    float riverField = noise(world / 1150.0 + 9.0) * 0.72 + noise(world / 410.0 + 2.0) * 0.28;
    float riverSlope = max(length(vec2(dFdx(riverField), dFdy(riverField))), 1.0E-5);
    float rivers = 1.0 - smoothstep(0.5, 1.5, abs(riverField - 0.5) / riverSlope);

    vec3 colour = ground;
    colour = mix(colour, vec3(0.05, 0.13, 0.20), rivers * 0.75);
    colour = mix(colour, vec3(0.19, 0.20, 0.21), counties * 0.55);
    colour = mix(colour, vec3(0.34, 0.25, 0.09), roads * 0.55);
    return colour;
}

void main() {
    vec2 centred = face * 2.0 - 1.0;
    float radius = length(centred) / RingRadius;
    float pixel = max(fwidth(radius), 1.0E-4);
    float angle = atan(centred.y, centred.x);
    float bearing = mod(atan(centred.x, -centred.y) + TAU, TAU);

    vec2 scope = centred / RingRadius;
    vec2 world = WorldCentre + scope * RangeBlocks;
    float pixelBlocks = max(fwidth(world.x), fwidth(world.y));

    vec3 colour = basemap(world, pixelBlocks);

    // Range rings at round fractions of the range, and bearings every thirty degrees.
    float rings = 0.0;
    for (int i = 1; i <= 3; i++) {
        rings = max(rings, hairline(radius, float(i) * 0.25, pixel, 1.0));
    }
    float spokeAngle = abs(fract(bearing / (TAU / 12.0) + 0.5) - 0.5) * (TAU / 12.0);
    float spokes = hairline(spokeAngle * radius, 0.0, pixel, 0.9) * smoothstep(0.03, 0.08, radius);
    colour = mix(colour, vec3(0.36, 0.42, 0.44), rings * 0.55);
    colour = mix(colour, vec3(0.28, 0.32, 0.34), spokes * 0.26);

    // The product, read at the centre of its range gate. A beam widens with range, and so do its gates. The
    // grain fades out when the panel is too small for it to do anything but alias.
    float azimuthGate = TAU / 360.0;
    float rangeGate = 1.0 / 230.0;
    float gateAngle = (floor(angle / azimuthGate) + 0.5) * azimuthGate;
    float gateRadius = (floor(length(scope) / rangeGate) + 0.5) * rangeGate;
    vec2 gated = gateRadius * vec2(cos(gateAngle), sin(gateAngle));
    float gateGrain = 1.0 - smoothstep(0.003, 0.009, pixel);
    vec2 sampleAt = mix(scope, gated, gateGrain) * 0.5 + 0.5;

    vec4 field = texture(Sampler0, sampleAt);
    // Which of the two scans this pixel shows depends on whether the beam has been past it yet.
    float sinceStart = mod(angle - ScanStart, TAU);
    float painted = ScanCovered >= TAU || Chart > 0.5 ? 1.0 : 1.0 - smoothstep(ScanCovered - 0.02, ScanCovered + 0.02, sinceStart);
    float value = mix(field.g, field.r, painted);

    float behindBeam = mod(SweepAngle - angle, TAU);
    float afterglow = exp(-behindBeam * 2.6) * (1.0 - Chart);

    float edgeDistance = max(abs(centred.x), abs(centred.y));
    float onScope = Chart > 0.5 ? 1.0 : 1.0 - smoothstep(1.0 - pixel, 1.0 + pixel, radius);
    if (value > NO_DATA) {
        vec3 product;
        float opacity = 0.96;
        if (Product < 0.5) {
            float dbz = value * 80.0 - 5.0;
            product = reflectivityColour(dbz);
            opacity *= smoothstep(4.0, 8.0, dbz);
        } else if (Product < 1.5) {
            float v = value * 128.0 - 64.0;
            product = velocityColour(v);
            opacity *= smoothstep(0.5, 2.5, abs(v) + 1.0);
        } else {
            product = riskColour(value * 8.0 + 0.001);
            opacity = 0.72;
        }
        // The beam leaves a brief brightness on what it has just painted.
        product *= 1.0 + afterglow * 0.34;
        // Beam spreading: power falls with range, so a return dims a little toward the rim.
        product *= mix(1.0, 0.86, smoothstep(0.35, 1.0, radius));
        colour = mix(colour, product, opacity);
    }

    // The beam itself, and the faint fan of phosphor behind it.
    float leading = 1.0 - smoothstep(0.0, max(pixel * 2.4 / max(radius, 0.05), 0.006), min(behindBeam, TAU - behindBeam));
    float reach = smoothstep(0.015, 0.06, radius);
    colour += vec3(0.26, 0.72, 0.60) * (afterglow * 0.055 + leading * 0.42 * (1.0 - Chart)) * reach;

    // Outside the rim is the display's own ground, with bearing ticks standing on it.
    vec3 outside = vec3(0.012, 0.015, 0.019);
    float tickAngle = abs(fract(bearing / (TAU / 36.0) + 0.5) - 0.5) * (TAU / 36.0) * radius;
    float tickMajor = abs(fract(bearing / (TAU / 4.0) + 0.5) - 0.5) * (TAU / 4.0) * radius;
    float ticks = hairline(tickAngle, 0.0, pixel, 1.0) * step(1.0, radius) * (1.0 - smoothstep(1.018, 1.024, radius));
    float major = hairline(tickMajor, 0.0, pixel, 1.6) * step(1.0, radius) * (1.0 - smoothstep(1.038, 1.046, radius));
    outside += vec3(0.46, 0.54, 0.56) * max(ticks * 0.8, major);
    colour = mix(outside, colour, onScope);
    colour = mix(colour, vec3(0.52, 0.60, 0.62), hairline(radius, 1.0, pixel, 1.2) * 0.85 * (1.0 - Chart));

    // The radar's own site: a small marker at the centre.
    colour = mix(colour, vec3(0.92, 0.94, 0.94), 1.0 - smoothstep(0.006, 0.006 + pixel * 1.5, radius));

    if (GlassAmount > 0.0) {
        // A faint pixel grid and a little sensor noise, a vignette from the backlight, and a soft sheet of window
        // reflected across the glass. Each is small on its own; together they are the difference between a picture
        // of a screen and a screen.
        vec2 grid = abs(fract(face * 360.0) - 0.5);
        float cells = smoothstep(0.5, 0.40, max(grid.x, grid.y));
        colour *= mix(1.0, 0.88 + 0.12 * cells, GlassAmount * (1.0 - smoothstep(0.0012, 0.0036, pixel)));
        float flicker = (hash(floor(face * 480.0) + vec2(floor(GameTime * 24000.0 * 2.0), 0.0)) - 0.5) * 0.010;
        colour += flicker * GlassAmount;
        float vignette = smoothstep(1.55, 0.55, length(centred));
        colour *= mix(1.0, 0.72 + 0.28 * vignette, GlassAmount);
        colour += vec3(0.010, 0.018, 0.020) * vignette * GlassAmount;
        float sheet = smoothstep(0.30, 0.36, face.x + face.y * 0.55) * (1.0 - smoothstep(0.60, 0.92, face.x + face.y * 0.55));
        colour += vec3(0.030, 0.034, 0.038) * sheet * (1.0 - face.y) * GlassAmount;
    }

    if (Chart > 0.5) {
        // A projected table: faint raster lines drifting up it, and a brighter frame where the projection ends.
        float raster = 0.5 + 0.5 * sin(face.y * 420.0 - GameTime * 24000.0 * 0.15);
        colour *= 0.93 + 0.07 * raster;
        colour += vec3(0.0, 0.55, 0.62) * smoothstep(0.962, 1.0, edgeDistance) * 0.9;
    }

    fragColor = vec4(colour, vertexColor.a) * ColorModulator;
}
