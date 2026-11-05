#version 150

// Weather2's cloud and tornado puffs, drawn soft.
//
// Vanilla's particle program is written for small sprites, and three things it does are wrong at thirty blocks across.
// It throws away anything under a tenth of its alpha, which puts a jagged outline round every faint puff. It lets a
// puff stop dead where it meets the ground, in a line as straight as the terrain is flat. And it has no idea the eye is
// about to fly through one, so the last thing a puff does is fill the screen with a hard-edged square.
//
// These are the same sprites and the same particles, with those three things taken out.

uniform sampler2D Sampler0;
uniform sampler2D DepthSampler;
uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float fogDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

/** How far a puff feathers out where something solid passes through it, in blocks. */
const float GROUND_FEATHER = 7.0;
/** The distance over which a puff fades as the eye reaches it, in blocks. */
const float NEAR_FADE_START = 1.5;
const float NEAR_FADE_END = 9.0;

float viewDepth(float depth) {
    return ProjMat[3][2] / ((depth * 2.0 - 1.0) + ProjMat[2][2]);
}

void main() {
    vec4 colour = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (colour.a < 0.002) {
        discard;
    }
    float here = viewDepth(gl_FragCoord.z);
    float scene = viewDepth(texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r);
    colour.a *= clamp((scene - here) / GROUND_FEATHER, 0.0, 1.0);
    colour.a *= smoothstep(NEAR_FADE_START, NEAR_FADE_END, here);
    if (colour.a < 0.002) {
        discard;
    }
    if (FogEnd > FogStart) {
        float fog = clamp((fogDistance - FogStart) / (FogEnd - FogStart), 0.0, 1.0);
        colour.rgb = mix(colour.rgb, FogColor.rgb, fog * FogColor.a);
    }
    fragColor = colour;
}
