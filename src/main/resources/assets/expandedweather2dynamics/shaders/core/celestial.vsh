#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// The vertex stage does nothing but carry the quad through. Which body it is - the sun, one phase of the moon,
// a piece of an eclipse or a slice of the galactic band - is the fragment stage's business, told apart by Mode;
// what that body looks like is its own texture's business. All this stage has to do is place the corners where
// the renderer built them and hand on the two things the fragment stage reads: the texture coordinate, which is
// the whole of a body's appearance, and the colour, which is the light it is drawn with.
out vec2 vUv;
out vec4 vColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vUv = UV0;
    vColor = Color;
}
