#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;
uniform vec2 FoliageWindVector;
uniform float FoliageWindStrength;
uniform float FoliageWindTime;
uniform float FoliageWindMaxOffset;
uniform float FoliageWindGust;
uniform float FoliageGustWavelength;
uniform float FoliageGustSpeed;
uniform float FoliageRestLean;
uniform float FoliageSkyFloor;
uniform float FoliageTrailStrength;
uniform float FoliageWaveLag;
uniform vec3 FoliageWorldAnchor;
uniform vec4 FoliageTrailP0;
uniform vec4 FoliageTrailP1;
uniform vec4 FoliageTrailP2;
uniform vec4 FoliageTrailP3;
uniform vec2 FoliageTrailD0;
uniform vec2 FoliageTrailD1;
uniform vec2 FoliageTrailD2;
uniform vec2 FoliageTrailD3;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

const float TAU = 6.28318530718;

// The band of vertex alphas this shader treats as its own, and how many height steps one
// marker carries. These mirror FoliageWindVertexConsumer, which writes them.
const float MARK_BASE = 208.0;
const float MARK_LEVELS = 8.0;
const float MARK_SPAN = 40.0;

// The plant classes, in the order FoliageWindBlocks declares them.
const float CLASS_LEAVES = 1.0;
const float CLASS_PLANT = 2.0;
const float CLASS_PLANT_LOWER = 3.0;
const float CLASS_PLANT_UPPER = 4.0;
const float CLASS_HANGING = 5.0;

// How sharply a plant's lean builds with height, as a polynomial that is exactly 0 at the root and
// exactly 1 at the tip. This is a curve rather than a straight line because a stem carrying its own
// weight stays straighter near the ground and leans most in its top third.
//
// It is deliberately not pow(): the base vertex of every plant sits at exactly zero, and that is the
// most common vertex in the scene, so the bend must be exact and finite there rather than relying on
// a corner of the spec that has caught drivers out before.
const float BEND_LINEAR = 1.7;
const float BEND_SQUARE = 0.7;

// A leaf block is not rooted in the ground - it is part of a canopy - so it drifts as a body
// with a little give across its height rather than pivoting on its bottom face.
const float LEAF_BASE_FOLLOW = 0.32;

// How much of a stem's travel a leaf block is allowed. A leaf block is a cube drawn hard against the
// blocks beside it and the trunk beneath it, so it cannot shear the way a blade can: at a stem's
// amplitude its lower edge slides out from under the wood it is meant to be growing from and the canopy
// opens up. A canopy that visibly moves is worth less than a canopy that stays whole.
const float LEAF_TRAVEL_SHARE = 0.32;

// A blown plant does two things at once, and old Weather2's foliage split them the same way: it
// holds a lean downwind, and it stirs around that lean. The lean is much the larger part, because a
// field reads as being blown when the plants are visibly held over rather than when they are
// vibrating, and the stir is the smaller motion happening on top of that posture. Swapping this
// ratio is what makes grass look nervous instead of windy.
const float LEAN_BASE = 0.58;
const float LEAN_FRONT = 0.08;

// The stir is two waves travelling at different speeds, and the slower one carries most of it.
const float STIR_SLOW = 0.66;
const float STIR_FAST = 0.34;

// How much sooner the fast part of the stir moves than the slow part over the same height. A higher
// mode has more of the blade doing the moving, so it lags further along the stem.
const float FAST_LAG_RATIO = 1.7;

// The stir traces an ellipse rather than a circle: a blade gives far more to the wind pushing it
// than to a shove from the side, so the across-wind axis of that ellipse is the shorter one.
const float STIR_ELLIPSE = 0.55;

// A plant is never quite upright and never all leaning the same way, even in still air.
const float REST_LEAN = 0.12;

// The gust front never takes the drive to nothing, or the field would visibly stop and start.
const float FRONT_FLOOR = 0.55;

// How far a plant's own arc spreads from its neighbour's. Small enough that a patch a few blocks
// across sways together, large enough that no two plants are doing the same thing.
const float PHASE_SCALE = 1.1;

// A walking player lands near three quarters of a print; grass is only thrown the full
// distance by something moving fast.
const float TRAIL_WIND_HOLD = 0.9;
const float TRAIL_LAY_PLANT = 1.25;
const float TRAIL_LAY_LEAF = 0.45;

float ewd_cell_hash(vec2 cell) {
    return fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
}

// One track's contribution where this vertex stands: how hard the grass is laid over, and
// which way. The direction is a blend of "away from whatever made it" and the direction it
// was travelling, so a path opens up the way a path does rather than as a crater. Pushes
// accumulate rather than replace, so two tracks crossing do not cancel each other out.
float ewd_trail(vec4 source, vec2 lay, vec2 at, vec2 fallback, inout vec2 push) {
    if (source.w <= 0.001) {
        return 0.0;
    }
    vec2 away = at - source.xy;
    float distance = length(away);
    float radius = max(0.35, source.z);
    float influence = 1.0 - smoothstep(0.0, radius, distance);
    influence *= influence * source.w;
    if (influence <= 0.001) {
        return 0.0;
    }
    vec2 radial = distance > 0.001 ? away / distance : fallback;
    vec2 direction = length(lay) > 0.001 ? normalize(radial * 0.35 + normalize(lay) * 0.65) : radial;
    push += direction * influence;
    return influence;
}

void main() {
    float payload = floor(Color.a * 255.0 + 0.5);
    float marked = payload >= MARK_BASE && payload < MARK_BASE + MARK_SPAN ? 1.0 : 0.0;
    float slot = payload - MARK_BASE;
    float foliageClass = floor(slot / MARK_LEVELS) + 1.0;
    float level = (slot - floor(slot / MARK_LEVELS) * MARK_LEVELS) / (MARK_LEVELS - 1.0);

    vec3 pos = Position + ChunkOffset;

    if (marked > 0.5 && FoliageWindMaxOffset > 0.0) {
        // Where this vertex really is. Chunk vertices arrive relative to the frame's camera, so a
        // gust band or a plant's character read from them alone would be nailed to the moving
        // viewpoint and travel with the player; adding the camera back puts the field on the ground.
        vec2 world = pos.xz + FoliageWorldAnchor.xz;

        vec2 wind = FoliageWindVector;
        wind = dot(wind, wind) < 0.0001 ? vec2(1.0, 0.0) : normalize(wind);
        vec2 crossAxis = vec2(-wind.y, wind.x);

        // Each plant's own character: which way it happens to lean, how limber it is, and the phase
        // offset that keeps neighbours from moving in lockstep.
        vec2 cell = floor(world);
        float identity = ewd_cell_hash(cell);
        float alternate = ewd_cell_hash(cell + 19.7);

        // How far up the plant this vertex is: 0 at the root, 1 at the tip. A two block plant is
        // one stem, so its upper half continues from the lower half's tip instead of hinging on
        // its own base - the seam is exactly what a player watching tall grass would see.
        float fraction = level;
        float plantHeight = 1.0;
        if (foliageClass == CLASS_PLANT_LOWER) {
            fraction = level * 0.5;
            plantHeight = 2.0;
        } else if (foliageClass == CLASS_PLANT_UPPER) {
            fraction = 0.5 + level * 0.5;
            plantHeight = 2.0;
        }

        // Which end is anchored, and how each half of the motion runs along the plant. Grass is
        // rooted at the bottom and its base has to stay exactly where it is - that is the whole
        // difference between bending and sliding. A vine hangs from the block above it, so for it the
        // anchored end is the top. A leaf block is a cube that drifts as a body. A vertex whose
        // height could not be measured arrives marked at the tip, which moves the plant whole rather
        // than bending it on a number that means nothing.
        float leanWeight;
        float stirWeight;
        float lagHeight;
        if (foliageClass == CLASS_HANGING) {
            float fromAnchor = 1.0 - fraction;
            leanWeight = fromAnchor;
            stirWeight = fromAnchor;
            lagHeight = fromAnchor;
        } else if (foliageClass == CLASS_LEAVES) {
            leanWeight = LEAF_BASE_FOLLOW + (1.0 - LEAF_BASE_FOLLOW) * fraction;
            stirWeight = leanWeight;
            lagHeight = fraction;
        } else {
            // The lean is quadratic in height, so the root barely moves and the top third does the
            // work; the stir is linear, so the middle of the blade waves as well. That split is what
            // old Weather2's foliage did, and it is why its grass rippled down the length of a blade
            // instead of rocking on a hinge.
            leanWeight = fraction * fraction * (BEND_LINEAR - BEND_SQUARE * fraction);
            stirWeight = fraction;
            lagHeight = fraction * plantHeight;
        }

        // Shelter: grass under a roof or deep in a canopy is not in the wind, so the sway is scaled
        // by the sky the vertex can actually see.
        float sky = clamp(float(UV2.y) / 240.0, 0.0, 1.0);
        float exposure = mix(clamp(FoliageSkyFloor, 0.0, 1.0), 1.0, sky);

        // The gust train: bands of moving air cross the ground at the wind's own speed, and each
        // plant keeps a faster flutter of its own on top of it.
        float gust = clamp(FoliageWindGust, 0.0, 1.0);
        float strength = clamp(FoliageWindStrength, 0.0, 1.0);

        // The gust front: one band of moving air crossing the ground downwind at the wind's own
        // speed. It sets how hard this part of the field is being pushed, so the strength of the
        // motion sweeps across the grass in waves rather than rising and falling everywhere at once.
        float wavelength = max(4.0, FoliageGustWavelength);
        float along = dot(world, wind);
        float across = dot(world, crossAxis);
        float front = sin(TAU * (along - FoliageGustSpeed * FoliageWindTime) / wavelength + across * 0.055);
        float front01 = 0.5 + 0.5 * front;
        float drive = strength * exposure * mix(FRONT_FLOOR, 1.0, front01);

        // The lean itself is local, and that is the whole difference between grass swaying and a
        // meadow rocking on a hinge. Neighbouring plants sit at different points in their own arc,
        // in patches a few blocks wide, each with a faster flutter of its own on top. Give the whole
        // field one phase and it moves back and forth as a single sheet, which is not sway at all.
        float phase = dot(world, vec2(1.7, 1.15)) + identity * PHASE_SCALE;

        // The wave that runs up a blade. Every vertex's motion is delayed by how far it stands from
        // the anchored end, so the root starts to move and the tip follows a moment later. This is
        // what separates a plant flexing from a plant rotating, and no amount of gusting substitutes
        // for it: without the lag every part of a blade swings together, which reads as the whole
        // plant ticking back and forth. A harder wind runs the wave faster along the stem, so tips
        // whip in a gale and merely nod in a breeze, exactly as they do in life.
        float lag = radians(FoliageWaveLag) * lagHeight * (0.35 + 0.65 * strength);
        float slowPhase = FoliageWindTime * (1.6 + gust * 2.2) + phase - lag;
        float fastPhase = FoliageWindTime * (4.3 + gust * 3.6) + phase * 1.9 + alternate * 4.0
                - lag * FAST_LAG_RATIO;

        // The stir is a pair of waves in quadrature rather than one wave sliding along a line, so the
        // tip traces an ellipse and the plant stirs instead of ticking. Pairing them this way also
        // keeps the combined offset at unit length or less, whatever the two of them happen to do.
        vec2 stir = wind * (sin(slowPhase) * STIR_SLOW + sin(fastPhase) * STIR_FAST)
                + crossAxis * ((cos(slowPhase) * STIR_SLOW + cos(fastPhase) * STIR_FAST) * STIR_ELLIPSE);

        // What a plant does with the wind and what it does without it are scaled by different things, so
        // they are built separately. The wind's part is budgeted so the tip can never pass the configured
        // maximum: the lean it holds downwind and the stir around it add to exactly 1, and the peak of the
        // stir above is 1 by construction rather than by luck, so the stir takes whatever the lean leaves.
        float lean = LEAN_BASE + LEAN_FRONT * front01;
        float stirBudget = max(0.0, 1.0 - lean);

        // The posture it keeps when nothing is blowing: a small permanent tilt, different for every plant.
        // This one is deliberately not scaled by the drive. A still day is exactly when a field of perfectly
        // upright decals looks wrong, and the drive is zero on a still day - so a posture scaled by it would
        // vanish in the one weather it exists to survive.
        float restAngle = identity * TAU;
        float restBudget = clamp(FoliageRestLean, 0.0, 1.0) * REST_LEAN;
        vec2 rest = vec2(cos(restAngle), sin(restAngle)) * (alternate * 2.0 - 1.0);

        // Leaves are held to a fraction of a stem's travel, for the reason LEAF_TRAVEL_SHARE gives.
        float typeScale = foliageClass == CLASS_LEAVES ? LEAF_TRAVEL_SHARE : 1.0;
        vec2 winded = (wind * (lean * leanWeight) + stir * (stirBudget * stirWeight)) * drive
                + rest * (restBudget * leanWeight);
        vec2 displacement = winded * (FoliageWindMaxOffset * typeScale);

        // Grass something has just pushed through: laid over along the direction of travel, with the
        // wind held off it while the print is fresh. The print fades on the client's clock, so the
        // path closes behind whoever made it instead of snapping straight at their heels.
        if (FoliageTrailStrength > 0.001) {
            vec2 push = vec2(0.0);
            float laid = 0.0;
            laid = max(laid, ewd_trail(FoliageTrailP0, FoliageTrailD0, world, wind, push));
            laid = max(laid, ewd_trail(FoliageTrailP1, FoliageTrailD1, world, wind, push));
            laid = max(laid, ewd_trail(FoliageTrailP2, FoliageTrailD2, world, wind, push));
            laid = max(laid, ewd_trail(FoliageTrailP3, FoliageTrailD3, world, wind, push));
            laid = clamp(laid * FoliageTrailStrength, 0.0, 1.0);
            if (laid > 0.001) {
                vec2 trailDirection = length(push) > 0.001 ? normalize(push) : wind;
                float trailScale = foliageClass == CLASS_LEAVES ? TRAIL_LAY_LEAF : TRAIL_LAY_PLANT;
                displacement *= 1.0 - TRAIL_WIND_HOLD * laid;
                displacement += trailDirection * (FoliageWindMaxOffset * trailScale * laid) * leanWeight;
            }
        }

        pos.xz += displacement;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vec4 safeColor = vec4(Color.rgb, marked > 0.5 ? 1.0 : Color.a);
    vertexColor = safeColor * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
