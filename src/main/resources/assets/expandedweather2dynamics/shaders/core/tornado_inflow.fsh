#version 150

uniform vec4 ColorModulator;
uniform float GameTime;
uniform vec2 ScreenSize;
uniform float DamagePathIntensity;
uniform float CoreIntensity;
uniform float EyeIntensity;
uniform vec2 WindDirection;
uniform float EyeClearRadius;
uniform float Opacity;
uniform float WhiteoutIntensity;
uniform float VeilOnly;
uniform float FunnelViolence;
uniform float DebrisIntensity;

in vec2 texCoord0;
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
    float outerGate = smoothstep(0.08, 0.88, radius);
    return stripe * (0.36 + pulse * 0.82) * outerGate;
}

// The dark solids in the dust: boards, sheet metal and whole sheets of roofing carried past the camera.
// Shared by the veil and the inflow overlay because being inside the funnel is what produces them, not
// whichever of the two paths happens to be drawing.
//
// Two details are load-bearing. The noise is sampled in the wind's own frame and squashed across it, so
// every clump is a streak pointing the way the debris is travelling - scaling on the screen axes instead
// would tilt the debris against the wind whenever the wind was not horizontal. And the threshold sits
// high in the noise's distribution, whose top decile is above 0.80, with a narrow ramp: cut at 0.60 -
// where it looks reasonable on paper - and the clumps merge into one connected mass over half the screen,
// which is a second layer of dust rather than objects passing.
float debrisSilhouette(vec2 p, vec2 dir, float violence, float debris, float time) {
    vec2 clumpUv = vec2(dot(p, dir) * 2.4, dot(p, vec2(-dir.y, dir.x)) * 7.0);
    clumpUv += vec2(time * (1.9 + violence * 4.4), time * 0.40);
    float edge = 0.84 - violence * 0.06;
    return smoothstep(edge, edge + 0.06, noise(clumpUv)) * (0.16 + violence * 0.62) * debris;
}

void main() {
    vec2 uv = texCoord0;
    vec2 centered = uv - vec2(0.5);
    float aspect = max(ScreenSize.x / max(ScreenSize.y, 1.0), 0.25);
    vec2 p = vec2(centered.x * aspect, centered.y);
    float radius = length(p);
    float screenRadius = length(centered);

    float path = clamp(DamagePathIntensity, 0.0, 1.0);
    float core = clamp(CoreIntensity, 0.0, 1.0);
    float eye = clamp(EyeIntensity, 0.0, 1.0);
    float whiteout = clamp(WhiteoutIntensity, 0.0, 1.0);

    vec2 dir = WindDirection;
    if (length(dir) < 0.001) {
        dir = vec2(0.75, 0.42);
    }
    dir = normalize(dir);

    if (VeilOnly > 0.5) {
        // Whiteout veil. The player is inside the funnel, so this is the wall of dust rather than the
        // low-level inflow stream: an opaque fill with three things riding on it, all scaled by how
        // violent the funnel is, so an EF1 is murk you can half see through and an EF4 is a brownout.
        // Nothing here uses the overlay's wall ring or eye - those belong to the inflow overlay, which
        // stays a separate opt-in.
        float violence = clamp(FunnelViolence, 0.0, 1.0);
        float debris = clamp(DebrisIntensity, 0.0, 1.5);

        float churn = noise(p * 7.0 + vec2(GameTime * 1.5, -GameTime * 0.95)) * 0.6
                + noise(p * 19.0 + vec2(-GameTime * 2.7, GameTime * 1.8)) * 0.4;
        float veilAlpha = clamp(whiteout * Opacity * (0.84 + churn * 0.16), 0.0, 0.97);
        if (veilAlpha < 0.015) {
            discard;
        }
        vec3 veilColor = mix(vec3(0.46, 0.45, 0.43), vec3(0.88, 0.87, 0.84), 0.45 + churn * 0.55);

        // 1. Dust tearing past. Debris travels with the air, so these are the same lanes the inflow
        // overlay uses, sped up and thinned: a few faint smears at low violence, a continuous rip at
        // high. Bright, because lit dust against a darker core is what reads as speed.
        float streakSpeed = 1.0 + violence * 3.0;
        float streaks = streakField(p, dir, GameTime * streakSpeed, 0.30 + violence * 0.65)
                * (0.20 + violence * 1.05) * debris;
        veilColor += vec3(0.10, 0.10, 0.09) * streaks;

        // 2. The solids carried through the veil. Darker than the dust around them, but never black - a
        // hole in the veil would read as a rendering fault rather than as debris.
        float silhouette = debrisSilhouette(p, dir, violence, debris, GameTime);
        veilColor = mix(veilColor, vec3(0.17, 0.16, 0.15), clamp(silhouette, 0.0, 0.72));

        // 3. A fine grain over the whole veil, drifting at speed, so it never reads as a flat fill.
        float grit = noise(p * 44.0 + dir * GameTime * (4.0 + violence * 5.0));
        veilColor += (grit - 0.5) * (0.05 + violence * 0.07) * debris;

        // The streaks add on top of the churn, and unbounded they push the veil past pure white, which
        // reads as a blown highlight rather than as lit dust. Held just under full so it stays dust.
        veilColor = min(veilColor, vec3(0.95));

        fragColor = vec4(veilColor, veilAlpha) * vertexColor * ColorModulator;
        return;
    }

    float strength = clamp((path * 0.55 + core * 0.78 + whiteout * 0.42) * Opacity, 0.0, 1.0);
    if (strength <= 0.01) {
        discard;
    }

    // Deep inside the core everything whooshes past faster and denser
    float whoosh = 1.0 + whiteout * 2.4;
    float n1 = noise(p * (9.0 + core * 8.0) + vec2(GameTime * 0.42 * whoosh, -GameTime * 0.29 * whoosh));
    float n2 = noise(p * (24.0 + core * 18.0) + vec2(-GameTime * 1.10 * whoosh, GameTime * 0.63 * whoosh));
    float dust = (n1 * 0.70 + n2 * 0.30) * (0.16 + path * 0.22 + core * 0.46);
    dust = mix(dust, 0.62 + dust * 0.60, whiteout);

    float streaks = streakField(p, dir, GameTime * whoosh, min(1.0, core + whiteout * 0.45)) * (0.18 + path * 0.42 + core * 0.74 + whiteout * 0.50);
    float radialWall = smoothstep(0.12, 0.48, radius) * (1.0 - smoothstep(0.84, 1.16, radius));
    float spinWall = radialWall * (0.12 + core * 0.32 + whiteout * 0.22) * (0.70 + noise(vec2(atan(p.y, p.x) * 3.0, radius * 9.0 - GameTime * 2.1 * whoosh)) * 0.45);

    float clearRadius = clamp(EyeClearRadius, 0.05, 0.90) * (0.30 + eye * 0.58);
    float eyeCenter = eye * (1.0 - smoothstep(clearRadius * 0.42, clearRadius, screenRadius));
    float eyeRing = eye * (1.0 - smoothstep(0.035, 0.105, abs(screenRadius - clearRadius * 1.18)));

    float centerFade = 1.0 - eyeCenter * 0.82 * (1.0 - whiteout * 0.85);
    dust *= centerFade;
    streaks *= centerFade;
    spinWall = max(spinWall * centerFade, eyeRing * 0.42);

    vec3 dustColor = mix(vec3(0.34, 0.37, 0.36), vec3(0.74, 0.75, 0.69), core);
    vec3 streakColor = mix(vec3(0.72, 0.78, 0.75), vec3(0.93, 0.94, 0.86), core);
    vec3 wallColor = mix(vec3(0.10, 0.14, 0.15), vec3(0.42, 0.47, 0.46), core);
    vec3 eyeColor = vec3(0.55, 0.60, 0.58) * eyeCenter;
    vec3 whiteoutColor = vec3(0.79, 0.75, 0.67);

    vec3 color = dustColor * dust + streakColor * streaks + wallColor * spinWall + eyeColor;
    color = mix(color, whiteoutColor, whiteout * 0.88);
    // The solids belong to being engulfed, not to the veil's own branch, so they are drawn here too when
    // the player is deep inside - otherwise switching the inflow overlay on would be the one setting that
    // silently removed the debris.
    if (whiteout > 0.01) {
        float solids = debrisSilhouette(p, dir, clamp(FunnelViolence, 0.0, 1.0),
                clamp(DebrisIntensity, 0.0, 1.5), GameTime) * whiteout;
        color = mix(color, vec3(0.17, 0.16, 0.15), clamp(solids, 0.0, 0.72));
    }
    float alpha = clamp((dust * 0.72 + streaks * 0.58 + spinWall * 0.54 + eyeCenter * 0.10) * (1.0 + whiteout * 0.18), 0.0, 0.82 + whiteout * 0.17) * strength;
    alpha += eyeRing * strength * 0.16;

    if (alpha < 0.015) {
        discard;
    }
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
}
