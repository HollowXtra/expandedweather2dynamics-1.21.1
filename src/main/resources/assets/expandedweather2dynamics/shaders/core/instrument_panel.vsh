#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec2 panelCoord;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    // The block-local position within the panel's plane, which the renderer writes into the UVs. Every
    // plate, bar and border a panel draws is addressed in this same space, so the shading runs
    // continuously across them where per-quad 0..1 UVs would restart in each quad. It has to be the
    // renderer's own coordinate rather than Position: block entity geometry is camera-relative, so a
    // program keyed on Position would pin its grain and sheen to the player instead of to the panel.
    panelCoord = UV0;
    vertexColor = Color;
}
