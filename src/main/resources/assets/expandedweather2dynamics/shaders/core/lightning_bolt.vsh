#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // U is the ribbon's cross-section: 0 on the channel's axis, 1 at its edge. V is how far up the
    // channel the fragment is, which is what the return stroke travels along. Neither can come from
    // Position: the ribbon is billboarded, so its width is not aligned to any world axis.
    texCoord0 = UV0;
    vertexColor = Color;
}
