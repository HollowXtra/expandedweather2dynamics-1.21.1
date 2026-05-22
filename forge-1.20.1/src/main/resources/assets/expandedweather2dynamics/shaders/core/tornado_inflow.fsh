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
    float strength = clamp((path * 0.55 + core * 0.78) * Opacity, 0.0, 1.0);
    if (strength <= 0.01) {
        discard;
    }

    vec2 dir = WindDirection;
    if (length(dir) < 0.001) {
        dir = vec2(0.75, 0.42);
    }
    dir = normalize(dir);

    float n1 = noise(p * (9.0 + core * 8.0) + vec2(GameTime * 0.42, -GameTime * 0.29));
    float n2 = noise(p * (24.0 + core * 18.0) + vec2(-GameTime * 1.10, GameTime * 0.63));
    float dust = (n1 * 0.70 + n2 * 0.30) * (0.16 + path * 0.22 + core * 0.46);

    float streaks = streakField(p, dir, GameTime, core) * (0.18 + path * 0.42 + core * 0.74);
    float radialWall = smoothstep(0.12, 0.48, radius) * (1.0 - smoothstep(0.84, 1.16, radius));
    float spinWall = radialWall * (0.12 + core * 0.32) * (0.70 + noise(vec2(atan(p.y, p.x) * 3.0, radius * 9.0 - GameTime * 2.1)) * 0.45);

    float clearRadius = clamp(EyeClearRadius, 0.05, 0.90) * (0.30 + eye * 0.58);
    float eyeCenter = eye * (1.0 - smoothstep(clearRadius * 0.42, clearRadius, screenRadius));
    float eyeRing = eye * (1.0 - smoothstep(0.035, 0.105, abs(screenRadius - clearRadius * 1.18)));

    float centerFade = 1.0 - eyeCenter * 0.82;
    dust *= centerFade;
    streaks *= centerFade;
    spinWall = max(spinWall * centerFade, eyeRing * 0.42);

    vec3 dustColor = mix(vec3(0.34, 0.37, 0.36), vec3(0.74, 0.75, 0.69), core);
    vec3 streakColor = mix(vec3(0.72, 0.78, 0.75), vec3(0.93, 0.94, 0.86), core);
    vec3 wallColor = mix(vec3(0.10, 0.14, 0.15), vec3(0.42, 0.47, 0.46), core);
    vec3 eyeColor = vec3(0.55, 0.60, 0.58) * eyeCenter;

    vec3 color = dustColor * dust + streakColor * streaks + wallColor * spinWall + eyeColor;
    float alpha = clamp(dust * 0.72 + streaks * 0.58 + spinWall * 0.54 + eyeCenter * 0.10, 0.0, 0.82) * strength;
    alpha += eyeRing * strength * 0.16;

    if (alpha < 0.015) {
        discard;
    }
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
}
