#version 150

uniform vec4 ColorModulator;
uniform float GameTime;

in vec2 texCoord0;
in vec2 panelCoord;
in vec4 vertexColor;

out vec4 fragColor;

float ring(float radius, float target, float width) {
    return 1.0 - smoothstep(width, width * 2.25, abs(radius - target));
}

float line(float value, float target, float width) {
    return 1.0 - smoothstep(width, width * 2.0, abs(value - target));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

vec4 panelColor(vec2 uv) {
    vec2 centered = uv - vec2(0.5);
    float radius = length(centered);
    if (radius > 0.505) {
        discard;
    }

    float rings = max(max(ring(radius, 0.165, 0.0035), ring(radius, 0.305, 0.0035)), ring(radius, 0.440, 0.0035));
    float cross = max(line(uv.x, 0.5, 0.0025), line(uv.y, 0.5, 0.0025));
    float spokeAngle = atan(centered.y, centered.x);
    float sweepAngle = mod(GameTime * 24000.0 * 0.055, 6.2831853);
    float sweepDelta = abs(atan(sin(spokeAngle - sweepAngle), cos(spokeAngle - sweepAngle)));
    float sweep = (1.0 - smoothstep(0.0, 0.085, sweepDelta)) * smoothstep(0.02, 0.49, radius);
    float trailing = (1.0 - smoothstep(0.0, 0.55, sweepDelta)) * smoothstep(0.02, 0.49, radius) * 0.32;
    float grain = hash(floor(uv * 84.0) + floor(GameTime * 24000.0 * 0.08)) * 0.045;

    vec3 base = vec3(0.015, 0.070, 0.064);
    vec3 grid = vec3(0.13, 0.76, 0.68) * (rings * 0.50 + cross * 0.25);
    vec3 beam = vec3(0.42, 1.00, 0.86) * (sweep + trailing);
    vec3 edge = vec3(0.08, 0.38, 0.32) * smoothstep(0.45, 0.505, radius);
    return vec4(base + grid + beam + edge + grain, 0.86);
}

vec4 returnColor(vec2 uv, vec4 source) {
    vec2 centered = uv - vec2(0.5);
    float radius = length(centered);
    float blob = 1.0 - smoothstep(0.12, 0.52, radius);
    float core = 1.0 - smoothstep(0.02, 0.20, radius);
    float scallop = sin((panelCoord.x + panelCoord.y) * 72.0 + GameTime * 24000.0 * 0.23) * 0.045;
    float gate = max(0.0, blob + scallop);

    vec3 color = source.rgb * (0.35 + gate * 0.95) + vec3(0.95, 1.0, 0.78) * core * 0.18;
    float alpha = source.a * gate;
    return vec4(color, alpha);
}

void main() {
    vec4 color;
    if (vertexColor.a < 0.48) {
        color = panelColor(texCoord0);
    } else {
        color = returnColor(texCoord0, vertexColor);
    }

    if (color.a < 0.03) {
        discard;
    }
    fragColor = color * ColorModulator;
}
