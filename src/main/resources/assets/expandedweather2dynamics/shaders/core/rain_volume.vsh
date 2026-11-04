#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// The positions handed in are relative to the camera, which is the convention every level-stage
// renderer in this mod writes to. The rain is evaluated per fragment *in the world*, so the camera's
// own position is added back here and everything downstream - the field, the volume's box, the vertical
// profile - is in world coordinates.
uniform vec3 CameraPos;

// Vanilla's own fog shape, supplied by the shader loader: 0 is a sphere and anything else a cylinder.
uniform int FogShape;

out vec3 worldPos;
out vec4 vertexColor;
out float fogDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    worldPos = Position + CameraPos;
    vertexColor = Color;

    // How far this vertex is from the eye, measured the way the rest of the world's fog measures it -
    // in view space, with vanilla's own two shapes. It is taken here rather than per fragment because
    // this is the last place the vertex's own position is available before interpolation, and a
    // fragment's distance from the eye is exactly the interpolation of its corners' distances.
    vec3 viewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    fogDistance = FogShape == 0 ? length(viewPos) : max(length(viewPos.xz), abs(viewPos.y));
}
