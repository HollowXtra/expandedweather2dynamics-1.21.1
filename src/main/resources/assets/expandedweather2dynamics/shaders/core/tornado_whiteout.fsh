#version 150

// Dust you are inside, evaluated as a volume rather than painted on the screen.
//
// The veil this replaces was one full-screen quad: a picture of being engulfed, with no parallax at all.
// Turning your head slid the picture with it, walking forward changed nothing, and the only thing that
// ever moved was the pattern's own scroll. That is the honest limit of a screen-space effect, and inside
// a tornado it is the one thing the eye notices - a wall of airborne grit is a place you are standing in,
// and standing in a place means the much of it between you and the sky changes as you move.
//
// So the same dust is defined as a field in the world and cut into camera-facing slices. Each fragment
// asks what the density is at its own world position, converts that to the optical depth of one slab, and
// blends. The field is world-anchored, which is the load-bearing decision: the same feature of the noise
// sits at the same world position on every slice, so the slices *differ*, and it is their differing that
// reads as depth rather than as a sheet of dust at one distance.
//
// The helpers here are the flat veil's, unchanged. hash, noise, streakField and debrisSilhouette are the
// same functions that have been drawing the flat veil, so the volume's dust is the same dust: the same
// tearing lanes, the same dark boards crossing the view squashed along the wind, the same fine grit over
// it. Only the coordinates change - what was screen position is now world position - so the look is
// continuous with what it replaces while the motion is not.

uniform vec4 ColorModulator;
uniform vec3 CameraPos;
uniform vec3 BoxCentre;
uniform vec3 BoxHalf;
uniform float GameTime;
uniform vec2 WindDirection;
uniform float SliceExtinction;
uniform float WhiteoutIntensity;
uniform float FunnelViolence;
uniform float DebrisIntensity;

in vec3 worldPos;
in vec4 vertexColor;

out vec4 fragColor;

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

// Three dimensions from the two-dimensional noise that is already here, rather than a new family of
// hashes: two adjacent xy planes interpolated. A volume needs the third axis and nothing else about the
// noise needed to change, so the cheaper thing to get right is to leave hash and noise exactly as they
// are and stack them.
//
// It is evaluated at the fragment's world position and deliberately not at its position relative to the
// camera. Camera-relative coordinates would keep float precision perfect and would also glue the dust to
// the viewer, which is the flat veil's defect by another route: no parallax. World-anchored, the lattice
// cell a fragment lands in depends on where it is, so the field is stable in the world and the integer
// cell coordinates only need to be *consistent*, not small - the sin-hash loses precision at a few
// thousand blocks from the origin and simply returns a different fixed value per cell, which for noise
// is the same thing as a different seed.
float noiseVolume(vec3 p) {
    float z = floor(p.z);
    float f = p.z - z;
    float a = noise(p.xy + vec2(z * 37.0, z * 17.0));
    float b = noise(p.xy + vec2((z + 1.0) * 37.0, (z + 1.0) * 17.0));
    return mix(a, b, f * f * (3.0 - 2.0 * f));
}

// The lanes of tearing dust. This was a screen-space function when the veil was flat, where an outerGate
// term faded the lanes out toward the middle of the frame. Reproduced against a world position that same
// term became distance from the world origin - it faded the lanes to nothing near spawn and saturated them
// thousands of blocks away, so the tearing in the dust depended on where the storm happened to be rather
// than on the storm. The gate is gone: in a volume the lanes run the whole way through, and what thins the
// dust toward the edges is the shell and height terms in main, which are measured from the box the player
// is inside rather than from a fixed point in the world.
float streakField(vec2 p, vec2 dir, float time, float core) {
    vec2 normal = vec2(-dir.y, dir.x);
    float radius = length(p);
    float angle = atan(p.y, p.x);
    float swirl = sin(radius * 18.0 - time * (2.4 + core * 3.2) + angle * 2.15) * (0.08 + core * 0.11);
    float along = dot(p, dir) + swirl;
    float across = dot(p, normal) + sin(along * 4.2 + time * 2.0) * (0.035 + core * 0.04);
    float spacing = 10.0 + core * 13.0;
    float lane = abs(fract(across * spacing + time * (0.58 + core * 0.9)) - 0.5);
    float stripe = 1.0 - smoothstep(0.020, 0.085, lane);
    float pulse = pow(1.0 - smoothstep(0.06, 0.56, fract(along * (4.0 + core * 3.0) - time * (0.92 + core * 1.35))), 1.7);
    return stripe * (0.36 + pulse * 0.82);
}

// The dark solids in the dust: boards, sheet metal and whole sheets of roofing carried past the camera.
// Sampled in the wind's own frame and squashed across it, so every clump is a streak pointing the way the
// debris is travelling - which matters more here than it did on the screen, because a clump in a volume
// is at a real place and the eye can walk around it.
float debrisSilhouette(vec2 p, vec2 dir, float violence, float debris, float time) {
    vec2 clumpUv = vec2(dot(p, dir) * 2.4, dot(p, vec2(-dir.y, dir.x)) * 7.0);
    clumpUv += vec2(time * (1.9 + violence * 4.4), time * 0.40);
    float edge = 0.84 - violence * 0.06;
    return smoothstep(edge, edge + 0.06, noise(clumpUv)) * (0.16 + violence * 0.62) * debris;
}

void main() {
    float whiteout = clamp(WhiteoutIntensity, 0.0, 1.0);
    if (whiteout < 0.01) {
        discard;
    }
    float violence = clamp(FunnelViolence, 0.0, 1.0);
    float debris = clamp(DebrisIntensity, 0.0, 1.5);

    // The slice quads are wide enough to cover the frustum at any distance inside the box, so most of a
    // slice is outside the volume. This is what trims it back to a box: everything past the faces is
    // thrown away before it is shaded, which is also why the box can be generous without costing fill.
    vec3 local = worldPos - BoxCentre;
    if (any(greaterThan(abs(local), BoxHalf))) {
        discard;
    }

    vec2 dir = WindDirection;
    if (length(dir) < 0.001) {
        dir = vec2(0.75, 0.42);
    }
    dir = normalize(dir);

    // The whole field is translated bodily along the wind, so the dust is not merely flickering in place
    // but being carried past. Two octaves, coarse and fine: the coarse one is the slabs of the wall, the
    // fine one the grain within them.
    vec3 q = worldPos - vec3(dir.x, 0.0, dir.y) * (2.2 + violence * 5.5) * GameTime;
    float churn = noiseVolume(q * 0.55) * 0.62 + noiseVolume(q * 1.45) * 0.38;

    // World-scaled, so the lanes are a fixed width in blocks rather than a fixed width in pixels: the
    // numbers are chosen for a lane every block or so and a clump of boards every few blocks, which is
    // what makes them read as objects at a distance instead of as a texture.
    vec2 planar = worldPos.xz;
    float streaks = streakField(planar * 0.09, dir, GameTime * (1.0 + violence * 3.0), 0.30 + violence * 0.65)
            * (0.20 + violence * 1.05) * debris;
    float silhouette = debrisSilhouette(planar * 0.075, dir, violence, debris, GameTime);
    float grit = noiseVolume(worldPos * 3.4 + vec3(dir.x, 0.0, dir.y) * GameTime * (4.0 + violence * 5.0));

    // Two shapes on top of the noise. A wall of dust is heaviest low and thins with height, which is what
    // makes looking up and looking down differ. And the shell fades over the last fraction of the box, so
    // that the box's own faces can never read as a lid on the weather - at a full whiteout the fog has
    // already closed inside it, but this is a veil that ramps in from nothing and the ramp is exactly when
    // an edge would otherwise be visible.
    float height = clamp((local.y + BoxHalf.y) / max(2.0 * BoxHalf.y, 1.0), 0.0, 1.0);
    float shell = 1.0 - smoothstep(0.85, 1.0, length(local / BoxHalf));
    float density = (0.55 + churn * 0.85 + streaks * 0.70) * mix(1.12, 0.52, height) * shell;

    // One slab's worth of dust, absorbed the way a slab of anything absorbs. This is the difference that
    // makes the stack a volume instead of N copies of a veil: the flat veil set its own alpha near 0.9 and
    // drew once, so a stack of those would be an opaque wall at the nearest slice and the rest of the
    // volume would be invisible behind it. Per-slab alpha times the number of slabs is the total depth, so
    // the count and the thickness trade off against each other and the look does not change when either does.
    float extinction = density * SliceExtinction * whiteout;
    if (extinction <= 0.002) {
        discard;
    }
    float alpha = 1.0 - exp(-extinction);

    vec3 veilColor = mix(vec3(0.46, 0.45, 0.43), vec3(0.88, 0.87, 0.84), 0.45 + churn * 0.55);
    veilColor += vec3(0.10, 0.10, 0.09) * streaks;
    veilColor = mix(veilColor, vec3(0.17, 0.16, 0.15), clamp(silhouette, 0.0, 0.72));
    veilColor += (grit - 0.5) * (0.05 + violence * 0.07) * debris;
    veilColor = min(veilColor, vec3(0.95));

    fragColor = vec4(veilColor, clamp(alpha, 0.0, 0.99)) * vertexColor * ColorModulator;
}
