#version 150

// The radar program. Two passes share it, and the vertex alpha chooses between them:
//
//   * below PASS_THROUGH_ALPHA - the scope face, generated here: a green phosphor screen inside a round
//     rim, with range rings, a crosshair, a rotating sweep and a dark bezel around it. The colour the
//     renderer wrote is ignored, because the face is produced rather than drawn.
//   * at or above it - pass-through: the map grid, the echo, the motion track and the markers, in
//     exactly the colour the renderer asked for.
//
// Every coordinate comes from panelCoord: the vertex's block-local top-face position, which the renderer
// writes into UV0 and which is remapped here into the box it draws the panel into. It is neither a
// per-quad UV nor the position attribute. A per-quad 0..1 UV restarts in every quad, which made each quad
// its own little panel: the echo's rim mask was evaluated inside every single cell, vignetting each cell of
// a shield and leaving the dark lattice that showed between them, and each thin map line painted a complete
// miniature scope inside its own strip, which is where the dotted grid came from. The position attribute is
// worse still, because block entity geometry is camera-relative - a scope keyed on it follows the player
// around the block, with the rings sliding and the round mask cutting the echo off-centre. One shared,
// block-local coordinate is what makes the face read as one screen that stays on its block.

uniform vec4 ColorModulator;
uniform float GameTime;

in vec2 texCoord0;
in vec2 panelCoord;
in vec4 vertexColor;

out vec4 fragColor;

/** The box the renderer draws the panel into, in block-local top-face coordinates. */
const vec2 PANEL_MIN = vec2(0.055, 0.055);
const vec2 PANEL_SPAN = vec2(0.890, 0.890);

/** Radius of the round screen, in panel units. Just past half, so the screen meets the panel's edges. */
const float SCOPE_RADIUS = 0.500;

/**
 * Radius the configured radar range is mapped onto, in panel units.
 *
 * <p>It is RadarPanelRenderer's SCOPE_RADIUS (0.43 blocks) expressed in this coordinate, which spans 0.89
 * blocks: 0.43 / 0.89. The range rings are placed at fractions of it, so they land on round ranges -
 * quarter range, half, three quarters - the way a real display's rings do, instead of on the arbitrary
 * radii they used to sit at.
 */
const float RANGE_RADIUS = 0.4831;

/** How opaque the face is, and the alpha the pass-through path has to stay above. */
const float SCOPE_ALPHA = 0.80;
const float PASS_THROUGH_ALPHA = 0.48;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

/**
 * Smooth value noise: bilinear across an integer lattice.
 *
 * <p>The screen's grain used to be a hash of the floored coordinate, so it was a board of hard cells
 * roughly a fifth of a millimetre across on the panel - visible squares, and ones that flickered
 * whenever the clock stepped the cell index. Interpolating across the lattice gives a haze with no cell
 * edges in it to see or to alias.
 */
float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

/**
 * A hairline at a target value, never thinner than a pixel.
 *
 * <p>The width is measured in screen pixels through the derivative of the coordinate, so a ring stays
 * one line at any distance from the panel instead of shrinking into a row of dots when the panel is
 * small and shimmering when it is not.
 */
float hairline(float value, float target, float pixel) {
    float width = max(0.0028, pixel * 1.6);
    return 1.0 - smoothstep(width, width * 2.2, abs(value - target));
}

/**
 * The face: the screen inside the rim and the bezel around it.
 *
 * <p>It covers the whole panel rather than discarding everything outside a circle, because the block's
 * own texture is a bright green screen and leaving it bare in the corners read as four lit triangles
 * around a dark screen. The bezel darkens it into a frame instead, with the rim itself drawn as one
 * antialiased line.
 */
vec4 scopeColor(vec2 face) {
    vec2 centered = face - vec2(0.5);
    float radius = length(centered);
    float pixel = max(fwidth(radius), 0.0009);

    float inside = 1.0 - smoothstep(SCOPE_RADIUS - pixel, SCOPE_RADIUS + pixel * 1.5, radius);

    float rings = max(max(hairline(radius, RANGE_RADIUS * 0.25, pixel),
                          hairline(radius, RANGE_RADIUS * 0.50, pixel)),
                      hairline(radius, RANGE_RADIUS * 0.75, pixel));
    float cross = max(hairline(face.x, 0.5, pixel), hairline(face.y, 0.5, pixel));

    // The beam leads and the phosphor decays behind it, all the way round. An exponential tail is what a
    // real PPI looks like, and it is also what stops the sweep flickering: the old wedge ended at a hard
    // angle, so its edge stepped from frame to frame as it turned.
    float spoke = atan(centered.y, centered.x);
    float sweep = mod(GameTime * 24000.0 * 0.055, 6.2831853);
    float delta = abs(atan(sin(spoke - sweep), cos(spoke - sweep)));
    float angular = max(fwidth(spoke), 0.012);
    float leading = 1.0 - smoothstep(0.0, angular * 2.0, delta);
    float afterglow = exp(-delta * 3.2);
    float reach = smoothstep(0.02, 0.09, radius) * (1.0 - smoothstep(RANGE_RADIUS * 0.86, SCOPE_RADIUS, radius));
    float beam = (afterglow * 0.42 + leading * 0.55) * reach;

    float grain = (valueNoise(face * 22.0 + vec2(GameTime * 24000.0 * 0.0035)) - 0.5) * 0.030;
    float rim = hairline(radius, SCOPE_RADIUS - pixel, pixel);

    vec3 screen = vec3(0.010, 0.052, 0.047)
            + vec3(0.09, 0.58, 0.50) * (rings * 0.40 + cross * 0.20)
            + vec3(0.30, 0.90, 0.76) * beam
            + vec3(grain);
    vec3 bezel = vec3(0.006, 0.022, 0.020) + vec3(0.10, 0.42, 0.36) * rim * 0.55;
    vec3 color = mix(bezel, screen, inside) + vec3(0.16, 0.62, 0.54) * rim * 0.35;

    return vec4(color, SCOPE_ALPHA);
}

/** A return, in the colour the renderer wrote, dimmed only by its distance from the radar. */
vec4 returnColor(vec2 face, vec4 source) {
    vec2 centered = face - vec2(0.5);
    float radius = length(centered);
    float pixel = max(fwidth(radius), 0.0009);

    // A return belongs to the screen: past the rim there is only bezel, so an echo or a map line must not
    // spill onto it. This is also the mask that used to be applied per quad, and applying it per quad is
    // the whole of why a shield looked like a grid of separate squares.
    float onScope = 1.0 - smoothstep(SCOPE_RADIUS - pixel, SCOPE_RADIUS + pixel, radius);

    // Beam spreading: power falls with range, so a return dims a little toward the rim. That is the only
    // place the distance from the radar belongs.
    float rangeGain = mix(1.0, 0.82, smoothstep(0.12, 0.50, radius));

    return vec4(source.rgb * rangeGain, source.a * onScope);
}

void main() {
    vec2 face = (panelCoord - PANEL_MIN) / PANEL_SPAN;
    vec4 color = vertexColor.a < PASS_THROUGH_ALPHA ? scopeColor(face) : returnColor(face, vertexColor);

    if (color.a < 0.03) {
        discard;
    }
    fragColor = color * ColorModulator;
}
