#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// The positions handed in are relative to the camera, which is the convention every level-stage renderer
// in this mod writes to. The dust is evaluated per fragment *in the world*, so the camera's own position
// is added back here and everything downstream is in world coordinates. That is the whole difference
// between this and the flat veil it replaces: a feature of the field sits at one place in the world
// rather than at one place on the screen, so turning your head sweeps past it and moving through it is
// what makes it move. Sampled on the screen, the same dust reproduced itself on every slice and the
// stack read as one sheet however many of them were drawn.
uniform vec3 CameraPos;

// The quad uv is declared because the vertex format carries it and not passed on, because nothing reads
// it: this is the one effect in the mod that is deliberately not a function of where a fragment is on the
// screen. See the fragment stage.

out vec3 worldPos;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    worldPos = Position + CameraPos;
    vertexColor = Color;
}
