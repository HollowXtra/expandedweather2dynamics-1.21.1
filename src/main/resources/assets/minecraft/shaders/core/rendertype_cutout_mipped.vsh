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

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

vec3 expandedweather2dynamics_foliage_offset(vec3 pos, float leafMask, float grassMask) {
    float foliageMask = max(leafMask, grassMask);
    if (foliageMask <= 0.0 || FoliageWindStrength <= 0.001 || FoliageWindMaxOffset <= 0.0) {
        return pos;
    }

    vec2 wind = FoliageWindVector;
    if (dot(wind, wind) < 0.0001) {
        wind = vec2(1.0, 0.0);
    }
    wind = normalize(wind);

    float typeScale = mix(0.78, 1.16, grassMask);
    float gust = clamp(FoliageWindGust, 0.0, 1.0);
    float strength = clamp(FoliageWindStrength, 0.0, 1.0);
    float spatial = dot(pos.xz, wind * 0.115) + (pos.x - pos.z) * 0.035;
    float wave = sin(spatial + FoliageWindTime * (2.8 + gust * 4.2));
    wave += cos(spatial * 0.71 - FoliageWindTime * (1.6 + gust * 2.4)) * 0.42;
    wave *= 0.70;

    float maxOffset = min(FoliageWindMaxOffset, 1.0);
    vec2 offset = wind * wave * maxOffset * strength * typeScale;
    vec2 cross = vec2(-wind.y, wind.x) * sin(spatial * 1.7 + FoliageWindTime * 3.1) * maxOffset * strength * 0.18 * leafMask;
    pos.xz += offset + cross;
    pos.y += sin(spatial * 1.3 + FoliageWindTime * 2.2) * maxOffset * strength * 0.045 * leafMask;
    return pos;
}

void main() {
    float marker = floor(Color.a * 255.0 + 0.5);
    float leafMask = marker == 254.0 ? 1.0 : 0.0;
    float grassMask = marker == 253.0 ? 1.0 : 0.0;

    vec3 pos = Position + ChunkOffset;
    pos = expandedweather2dynamics_foliage_offset(pos, leafMask, grassMask);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vec4 safeColor = vec4(Color.rgb, max(leafMask, grassMask) > 0.0 ? 1.0 : Color.a);
    vertexColor = safeColor * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
