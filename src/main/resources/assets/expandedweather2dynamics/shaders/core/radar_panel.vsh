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
    // The block-local top-face position, which the renderer writes into the UVs. It cannot come from
    // Position: block entity geometry is camera-relative, so Position.xz is a world coordinate that moves
    // with the player, and a scope keyed on it follows the player around the block instead of sitting on
    // it - the rings drift and the round mask cuts the echo at the wrong place.
    panelCoord = UV0;
    vertexColor = Color;
}
