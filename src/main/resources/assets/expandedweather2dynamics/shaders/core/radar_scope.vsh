#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// The face coordinate, 0..1 across the box the scope is drawn in. It comes from the UVs and never from the position:
// a block's geometry is camera-relative and a GUI's is in screen pixels, and the program is the same for both.
out vec2 face;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    face = UV0;
    vertexColor = Color;
}
