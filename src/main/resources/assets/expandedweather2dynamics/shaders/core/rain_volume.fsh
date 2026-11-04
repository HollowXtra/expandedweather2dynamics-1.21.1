#version 150

// Volumetric rain, drawn as slices.
//
// The geometry this shades is a stack of camera-facing quads cut through the storm's precipitation
// volume, back to front. Each fragment knows its own world position, so the question "how much rain is
// here" is answered analytically in the world rather than painted onto a billboard - which is what
// makes this volumetric instead of a texture: fly through it and the parallax is real, look up into the
// notch and the slot is genuinely clear, put the curtain between you and the funnel and the funnel is
// genuinely behind rain.
//
// The density function is a direct port of PrecipitationField, deliberately: the shapes the client
// already uses to decide where rain goes (forward-flank core, the hook band wrapping the mesocyclone,
// the rain-free notch, rear flank, stratiform tail) are the shapes drawn here, so the volume cannot
// disagree with the particles, the sky shading or the flooding about where a storm's rain is.
//
// Per slice the alpha is 1 - exp(-extinction), i.e. Beer-Lambert over one slab, and the slabs the view
// ray crosses add up to the same optical depth at any slice count, so how much rain there is does not
// depend on how finely it is cut. What does depend on it is the falling pattern, which is sampled once
// per slab: a pattern of thin bright threads separated by clear air is randomised by that sampling and
// comes out as grain, so the modulation below is deliberately a broad, low-contrast one over a veil -
// which is also what a curtain of rain is. Measured: sparse threads over a 40-block slab spacing grained
// the rain by 37%, this veil by 15%, and its mean does not depend on the slice count at all.

uniform vec4 ColorModulator;

uniform vec3 StormCentre;      // world position of the storm cell
uniform vec2 StormForward;     // its motion direction, unit, on the ground plane
uniform vec2 StormRight;       // its right axis, unit, on the ground plane
uniform float StormRadius;     // its precipitation radius, in blocks

uniform vec4 Anchor0;          // mesocyclone forward/right, forward flank forward/right
uniform vec4 Anchor1;          // notch forward/right, wrap curtain forward/right
uniform vec4 Anchor2;          // rear flank forward/right, unused, unused
uniform vec4 Strengths;        // core, hook, notch, rear flank
uniform float Stratiform;      // the light rain trailing under the anvil

uniform vec2 Vertical;         // ground level, top of the rain column
uniform vec3 BoxCentre;        // centre of the volume being drawn
uniform vec3 BoxHalf;          // its half extents
uniform float SliceExtinction; // optical depth per block at full density, over this slab
uniform float Virga;           // how much of the column's foot evaporates before it lands
uniform float StreakScale;     // how fine the falling threads are
uniform float FallSpeed;       // how fast they fall
uniform float GameTime;

// The world's fog, supplied by the shader loader from whatever FogRenderer chose for this frame: its
// colour is the sky's, which this mod drives, and its reach is the render distance, the player's own
// effects and anything a pack has done with it.
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// How much of that fog to apply at all. The three above are written for the frame by the shader loader
// rather than by this mod, so the only way to stand the whole feature down - which is what the config
// switch does - is a weight of the mod's own on the result.
uniform float FogAmount;

// The storm's own mist. The rain is its own fog: the air inside a supercell is not the air outside it,
// and the mist follows the rain's density so the clear slot stays clear.
uniform float StormMist;       // light taken out per block, at the thickest part of the storm
uniform float MistDensity;     // how much the mist follows the rain's own density

// What lights the rain: the sky it stands against, and the strike lighting this frame, if any.
uniform vec3 RainLight;        // the sky over the storm, already floored against the night
uniform vec3 FlashCentre;      // where this frame's strike is, at the middle of its channel
uniform float FlashStrength;   // how bright it is at its own channel, 0 when there is no strike
uniform float FlashGain;       // the user's weight on the light a strike gives the rain
uniform vec3 CameraPos;        // where the eye is, for the strike's own distance term

in vec3 worldPos;
in vec4 vertexColor;
in float fogDistance;

out vec4 fragColor;

const float PI = 3.14159265;
const float TWO_PI = 6.28318531;

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

/** 1 inside the plateau, smoothly to 0 at 1: PrecipitationField.falloff. */
float falloff(float q, float plateau) {
    if (q <= plateau) {
        return 1.0;
    }
    if (q >= 1.0) {
        return 0.0;
    }
    float t = (1.0 - q) / max(1.0E-4, 1.0 - plateau);
    return t * t * (3.0 - 2.0 * t);
}

/** A lobe: solid inside a fraction of its size, thinning to nothing at its edge. */
float lobe(float forward, float right, float halfForward, float halfRight, float plateau) {
    float q = length(vec2(forward / max(1.0, halfForward), right / max(1.0, halfRight)));
    return falloff(q, plateau);
}

/** Density along a straight run between two points, so the hook is joined to the core. */
float segment(float forward, float right, float startForward, float startRight, float endForward, float endRight, float width) {
    vec2 d = vec2(endForward - startForward, endRight - startRight);
    float lengthSqr = dot(d, d);
    if (lengthSqr < 1.0E-4) {
        return falloff(length(vec2(forward - startForward, right - startRight)) / max(1.0, width), 0.0);
    }
    float t = clamp(dot(vec2(forward - startForward, right - startRight), d) / lengthSqr, 0.0, 1.0);
    vec2 closest = vec2(startForward, startRight) + d * t;
    return falloff(length(vec2(forward, right) - closest) / max(1.0, width), 0.0);
}

/**
 * The wrapped band around the mesocyclone: an arc at the radius the wrapped rain actually sits at,
 * limited to the sector it occupies, plus the corridor joining it back to the core.
 */
float hookBand(float forward, float right) {
    float strength = Strengths.y;
    if (strength <= 0.0) {
        return 0.0;
    }
    vec2 fromMeso = vec2(forward - Anchor0.x, right - Anchor0.y);
    float distance = length(fromMeso);
    vec2 wrapRel = vec2(Anchor1.z - Anchor0.x, Anchor1.w - Anchor0.y);
    float ringRadius = max(StormRadius * 0.14, length(wrapRel));
    float ring = falloff(abs(distance - ringRadius) / (StormRadius * 0.17), 0.15);

    float wrapAngle = atan(wrapRel.y, wrapRel.x);
    float spread = 1.25 + 0.70 * clamp(strength, 0.0, 1.0);
    float gap = mod(abs(atan(fromMeso.y, fromMeso.x) - wrapAngle), TWO_PI);
    gap = gap > PI ? TWO_PI - gap : gap;
    float sector = falloff(gap / spread, 0.35);

    float shaft = segment(forward, right, Anchor1.z, Anchor1.w, Anchor0.z, Anchor0.w, StormRadius * 0.13);
    return max(ring * sector, shaft * 0.85);
}

/**
 * The clear slot: a wedge opening from the mesocyclone through the notch anchor and out toward where
 * the inflow comes from. One-sided on purpose - the rain behind the mesocyclone is not scoured.
 */
float notchCut(float forward, float right) {
    vec2 dir = vec2(Anchor1.x - Anchor0.x, Anchor1.y - Anchor0.y);
    float len = length(dir);
    // An anchor sitting exactly on the mesocyclone has no bearing, so the slot is aimed upshear. This
    // mirrors PrecipitationField.notch: the two must not disagree about the degenerate case, or the
    // one place the notch is allowed to be invisible is the one place the volume would go quiet.
    if (len < 1.0E-4) {
        dir = vec2(-1.0, 0.0);
    } else {
        dir /= len;
    }
    vec2 fromMeso = vec2(forward - Anchor0.x, right - Anchor0.y);
    float along = dot(fromMeso, dir);
    if (along <= 0.0) {
        return 0.0;
    }
    float lateral = abs(dot(fromMeso, vec2(-dir.y, dir.x)));
    float wedge = falloff(lateral / (StormRadius * 0.34), 0.30);
    float reach = falloff(along / (StormRadius * 1.05), 0.0);
    return wedge * reach;
}

/** The storm's precipitation density at a point, 0 to 1, in its own frame. */
float fieldDensity(vec3 world) {
    vec2 ground = world.xz;
    vec2 offset = ground - StormCentre.xz;
    float forward = dot(offset, StormForward);
    float right = dot(offset, StormRight);
    float radius = max(1.0, StormRadius);

    float core = Strengths.x * lobe(forward - Anchor0.z, right - Anchor0.w, radius * 0.62, radius * 0.44, 0.42);
    float hook = Strengths.y * hookBand(forward, right);
    float rear = Strengths.w * lobe(forward - Anchor2.x, right - Anchor2.y, radius * 0.62, radius * 0.55, 0.55);
    float stratiform = Stratiform * lobe(forward - radius * 0.95, right - radius * 0.12, radius * 0.95, radius * 0.75, 0.20);

    float density = max(max(core, hook), max(rear, stratiform));
    density *= 1.0 - 0.95 * Strengths.z * notchCut(forward, right);
    return clamp(density, 0.0, 1.0);
}

/**
 * The vertical profile: rain leaves the cloud compressed and fine, and thins as it falls, and with
 * virga in the air below the base the foot of the column evaporates instead of arriving.
 */
float heightProfile(float worldY) {
    float span = max(1.0, Vertical.y - Vertical.x);
    float hf = clamp((worldY - Vertical.x) / span, 0.0, 1.0);
    // Dense under the cloud base, sparser at the foot, because rain has had longer to fall and thin by
    // the time it arrives.
    float shape = mix(0.40, 1.0, smoothstep(0.0, 0.86, hf));
    // Nothing above the rain column's top, and a soft shoulder so it does not end on a line.
    shape *= 1.0 - smoothstep(0.90, 1.0, hf);
    // Virga eats the bottom of the column, and only when there is virga to eat it: the cut has to go
    // away entirely at zero, or the whole weight of this term lands on the one height the player is
    // standing at and the rain they are looking through is a tenth of what it should be.
    float eaten = clamp(Virga, 0.0, 1.0);
    float cut = eaten * 0.62;
    shape *= mix(1.0, smoothstep(cut, cut + 0.16, hf), eaten);
    return shape;
}

/**
 * Vanilla's own fog, transcribed from fog.glsl: nothing before the start, a smoothstep to the end, all
 * of it past the end. Reading the frame's real fog rather than deriving one is what lets the rain follow
 * a tornado's collapsed whiteout, morning fog and a shader pack's own fog without knowing about any of
 * them - and it is the same curve, so rain and terrain cannot disagree about where the horizon is.
 */
float worldFog(float distance, float fogStart, float fogEnd) {
    if (distance <= fogStart) {
        return 0.0;
    }
    return distance < fogEnd ? smoothstep(fogStart, fogEnd, distance) : 1.0;
}

/**
 * The fog the rain is in: the world's and the storm's own, because transmittances multiply.
 *
 * <p>Which is a physical statement rather than a second fade stacked on the first: what reaches the eye
 * is what the world's air passed, times what the storm's air passed. The mist therefore vanishes
 * <em>exactly</em> at zero, so rain in clear air fogs no differently from anything else in it, and the two
 * can never double-count - at full fog the answer is full fog and not twice over.
 */
float rainFog(float distance, float fogStart, float fogEnd, float mist) {
    float travelled = max(0.0, distance);
    return clamp(1.0 - (1.0 - worldFog(distance, fogStart, fogEnd)) * exp(-max(0.0, mist) * travelled), 0.0, 1.0);
}

/** How misty the air is where the rain is: 1 in the thickest part of the storm, less where it is thin. */
float rainMistScale(float density, float weight) {
    return 1.0 - weight + weight * clamp(density, 0.0, 1.0);
}

/**
 * How much of a strike's light reaches a place, by how far it is from the channel.
 *
 * <p>A floor rather than a fade to nothing, and the same curve the sky's own flash uses, so a strike
 * cannot light the rain and the sky by two different ones - the rain would glow where the sky is not.
 */
float flashFalloff(float distance) {
    return 0.30 + (1.0 - 0.30) / (1.0 + max(0.0, distance) * 0.0022);
}

/**
 * The falling structure of the rain, anchored in the world rather than on the screen.
 *
 * <p>Lanes are columns of heavier rain in world XZ and brightness runs down them, so the pattern does
 * not slide with the camera the way a screen-space effect does - look at the same piece of rain from two
 * angles and it is the same piece of rain, and flying past it has parallax. A slow lateral warp keeps
 * the lanes from marching in a grid, and each lane carries its own fall rate, which is what stops the
 * whole sky pulsing in time.
 *
 * <p>Two things are deliberate. It is a <em>veil with structure in it</em> rather than threads in empty
 * air, because that is what a curtain of rain looks like from any distance you can see a tornado from -
 * rain is a nearly continuous medium, and the visible structure is where it is heavier. And the swing is
 * about one and a half to one rather than from nothing to everything, because each slab is measured once:
 * a sparse bright pattern is at the mercy of where those measurements land and reads as grain, while this
 * one's mean is the same at any slice count (measured: 37% grain for threads, 15% for this).
 */
float rainThreads(vec3 world) {
    vec2 warp = vec2(vnoise(world.xz * 0.045 + GameTime * 0.05), vnoise(world.zx * 0.045 - GameTime * 0.04)) - 0.5;
    vec2 uv = (world.xz + warp * 7.0) * StreakScale;
    vec2 cell = floor(uv);
    vec2 lane = fract(uv) - 0.5;
    float jitter = hash21(cell);
    float laneWeight = 1.0 - smoothstep(0.16, 0.62, length(lane));
    // The brightness travels down the column: the visible signature of falling water.
    float fall = fract(world.y * 0.055 - GameTime * FallSpeed * (0.85 + jitter * 0.45) + jitter);
    float pulse = smoothstep(0.0, 0.30, fall) * (1.0 - smoothstep(0.62, 1.0, fall));
    return 0.70 + 0.42 * (0.30 + 0.70 * laneWeight) * (0.50 + 0.50 * pulse);
}

void main() {
    // Clip to the volume's own box: the slice quads are sized to the bounding sphere, so most of a
    // quad's area is outside the storm and is thrown away here rather than blended.
    vec3 offset = abs(worldPos - BoxCentre);
    if (offset.x > BoxHalf.x || offset.y > BoxHalf.y || offset.z > BoxHalf.z) {
        discard;
    }

    float density = fieldDensity(worldPos);
    if (density <= 0.004) {
        discard;
    }
    float profile = heightProfile(worldPos.y);
    if (profile <= 0.004) {
        discard;
    }

    float extinction = density * profile * rainThreads(worldPos) * SliceExtinction;
    if (extinction <= 0.002) {
        discard;
    }
    float alpha = 1.0 - exp(-extinction);

    // Rain in the air reads as a cool grey veil, darker where the storm's mass is thickest and lighter
    // near the top of the column where the light still gets into it. Deliberately desaturated: a
    // saturated blue would read as water rather than as weather.
    float thickness = clamp(density * profile, 0.0, 1.0);
    vec3 thin = vec3(0.60, 0.63, 0.68);
    vec3 thick = vec3(0.34, 0.37, 0.42);
    vec3 color = mix(thin, thick, thickness * 0.85);
    color += vec3(0.05, 0.05, 0.04) * (1.0 - thickness) * smoothstep(0.55, 1.0, worldPos.y / max(1.0, Vertical.y));

    // What lights it: the sky over the storm - the sun's angle, the day, the deck's darkening and the
    // flash of any strike, all of which this mod drives - and, where a strike is nearer the rain than the
    // eye is, the extra that strike gives the water it is standing in. The subtraction is the point of the
    // second term: the sky already carries the strike's light on everything, so a fragment is owed only
    // the difference between the light where it is and the light where the player is watching from.
    vec3 lit = color * RainLight;
    if (FlashStrength > 0.0) {
        float here = flashFalloff(distance(worldPos, FlashCentre));
        float eye = flashFalloff(distance(CameraPos, FlashCentre));
        lit += vec3(0.80, 0.80, 1.00) * (max(0.0, here - eye) * FlashStrength * FlashGain);
    }

    // And the fog it is in. The strike is added before the fog rather than after it, because its light
    // travels through the same air everything else in the fragment does.
    float mist = StormMist * rainMistScale(density, MistDensity);
    float fogValue = clamp(rainFog(fogDistance, FogStart, FogEnd, mist) * FogColor.a * FogAmount, 0.0, 1.0);
    lit = mix(lit, FogColor.rgb, fogValue);

    fragColor = vec4(clamp(lit, 0.0, 1.0), alpha) * vertexColor * ColorModulator;
}
