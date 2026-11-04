#version 150

// The instrument face.
//
// These panels used to be drawn through the radar program, which branches on the vertex alpha and
// paints a circular scope with range rings and a rotating sweep beam. Its plate is drawn at alpha
// 0.40 and its inner plate at 0.82, so both branches of that branch were reached and every
// barometer, rain gauge, radio and probe was wearing a radar scope with a sweep in it. This program
// is the instrument's own face instead: a machined, slightly glassy plate that keeps whatever
// colour the renderer asked for and adds nothing circular, no beam, and no discarded pixels.
//
// Everything is driven by panelCoord - the vertex's block-local position in the panel's plane, which the
// renderer carries in UV0 - so the gradient, the grain and the sheen all run continuously across the
// plate, its border and its bars instead of restarting inside each quad. It is deliberately not the
// position attribute: block entity geometry is camera-relative, so a panel keyed on Position would carry
// its grain and sheen around with the player rather than pinning them to the panel.

uniform vec4 ColorModulator;
uniform float GameTime;

in vec2 texCoord0;
in vec2 panelCoord;
in vec4 vertexColor;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

void main() {
    vec3 tint = vertexColor.rgb;
    float alpha = vertexColor.a;

    vec2 face = clamp(panelCoord, 0.0, 1.0);

    // A face catches the light along its near edge and falls away across it. Small enough that a bar
    // at the top row and one at the bottom row still read as the same colour.
    float gradient = 1.05 - face.y * 0.13;

    // Fine machined grain, pinned to the face so it does not crawl as the player moves. Coarser than
    // a screen pixel on purpose - it reads as brushed metal, not noise.
    float grain = (hash(floor(face * 96.0)) - 0.5) * 0.050;

    // A glass sheen drifting slowly across the plate. The rate is chosen so the sweep completes an
    // exact whole number of passes per Minecraft day (24000 * 0.0005 = 12), which matters because
    // GameTime wraps at the day boundary: a partial pass would snap the reflection back to the edge
    // once a day. One pass every 100 seconds.
    float sweep = fract(GameTime * 24000.0 * 0.0005);
    float band = 1.0 - smoothstep(0.0, 0.28, abs(face.x - (sweep * 1.56 - 0.28)));
    float sheen = band * 0.048;

    vec3 color = tint * gradient + vec3(sheen + grain * 0.5) * (0.35 + 0.65 * max(tint.r, max(tint.g, tint.b)));

    fragColor = vec4(color, alpha) * ColorModulator;
}
