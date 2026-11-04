#version 150

// The sun, the moon and the Milky Way: two textured quads and a ribbon of them.
//
// The bodies used to be cut out of their quads here. A disc was the distance from the centre of the quad, the
// moon's phase was a terminator computed per fragment, and an eclipse was two of those discs crossing - so
// everything a body looked like was arithmetic in this program, and changing how the sun looked meant changing
// it. They are pictures now, drawn by the same quads vanilla already placed: the sun is a sun with its limb
// darkening and its layered glare in the pixels, the moon is a tile picked out of an atlas of the game's eight
// phases, and the eclipse is a black disc of art crossing the sun with a corona and a diamond ring of its own.
// So what is left for this stage on a body is the two things a picture cannot do for itself.
//
// The first is the glow. A texture's falloff is baked into its alpha, so the only shape this stage still needs
// is a mask for which pixels are past the body's own edge - and that is all Glow is. Limb says where that edge
// sits in the quad, and the renderer sizes every quad by the same number so a body lands at the size it was
// asked for; everything outside Limb is multiplied by Glow and everything inside it is left alone. That is what
// keeps "sun glow 0" a clean hard-edged disc and "moon glow small" a moon with no halo worth speaking of, out
// of one texture per body, and it is why the mask is a step rather than a gradient: the gradient is the art.
//
// The second is the light. Every body is drawn with a visibility and a brightness, and both arrive as the
// vertex colour - so a body behind a storm's darkening fades to nothing without the picture knowing anything
// about the weather, and a body's own pixels stay the same picture at any brightness.
//
// The third is the shadow. A lunar eclipse is not a body crossing the moon, it is the Earth's shadow falling
// across it, so there is nothing to draw over the moon: what changes is the light the moon is drawn with, and
// that is what this stage multiplies in. Limb already says where the fragment sits on the body's own disc, so a
// distance from the shadow's axis is a distance from the moon's own centre, and the shadow's own two numbers -
// how far off the axis is and how wide it is - arrive as uniforms. It is a multiply rather than a second quad
// drawn over the moon because the alpha is what confines it: the multiply lands on exactly the pixels the moon
// lights, its glare included, and the sky behind the moon is left alone - no mask, no second blend state, and no
// second quad to get the moon's shape wrong.
//
// What leaves here is straight colour and straight alpha, and both passes that read it multiply the colour by
// the alpha: the additive one (which is every body that is a light source) adds colour * alpha to the sky, and
// the alpha-blended one (the eclipse's black disc) mixes it over the sky. That is what makes the alpha the
// body - the glare's falloff is a falloff because the alpha falls off - and it is why the additive pass cannot
// be the game's own additive state, which is blendFunc(ONE, ONE) and would throw the alpha away and draw a
// glow as a flat patch the size of its quad.
//
// The band is still built here, because the Milky Way is not a body and not a sprite: it is a great circle
// across the whole sky. It is a ribbon of quads around the galactic axis, and this stage gives the ribbon its
// structure - a soft core that falls off across its width, cloud from noise along it, and a scatter of resolved
// stars on top. The band is one more Mode through the same program rather than a second shader, because
// everything the two have in common - how a vertex arrives, how it is tinted, how it is blended - is the same,
// and only the drawing differs.

uniform sampler2D Sampler0;  // the body being drawn: the sun, one moon phase, or a piece of the eclipse
uniform vec4 ColorModulator;
uniform float Mode;          // 0 = a textured body, 1 = the galactic band
uniform float Limb;          // where the body's own edge sits inside its texture, in UV from the quad's centre, 1 = the quad's edge
uniform float Glow;          // how much of the glow past that edge is drawn, 0 = the body alone with a clean edge
uniform float Gain;          // the body's own brightness, as a multiplier on its pixels
uniform float ShadowStrength; // 1 while the Earth's shadow is on the body being drawn, 0 for every other body
uniform float ShadowOffset;  // how far the shadow's axis is from the body's centre, in body radii, signed
uniform float ShadowUmbra;   // the umbra's radius, in body radii; the penumbra runs half again as far out
uniform vec3 ShadowTint;     // the colour of the umbra: sunlight filtered through the world's own air

in vec2 vUv;
in vec4 vColor;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

void main() {
    if (Mode < 0.5) {
        // A body. The quad is drawn larger than the body - by the body's radius over Limb - so the texture has
        // somewhere to put its glare, and the space between the limb and the quad's edge is that glare. The
        // pixels are the body's whole look: the disc and its edge, the limb darkening, the moon's phase and the
        // eclipse's black shape are all in the file, which is the point of drawing them rather than computing
        // them. All this stage does is scale the part of that picture which is past the edge, and multiply the
        // light it is drawn with in - and hand the alpha on, because the alpha is the picture: it is the disc's
        // coverage, the glow's falloff and the phase's terminator, and both passes scale their colour by it.
        float r = length(vUv - 0.5) * 2.0;
        float lim = clamp(Limb, 0.02, 1.0);
        float mask = r > lim ? clamp(Glow, 0.0, 4.0) : 1.0;
        vec4 body = texture(Sampler0, vUv);
        float alpha = body.a * mask;
        if (alpha <= 0.002) {
            discard;
        }
        // The Earth's shadow, when this body is the moon and the world is between it and the sun. The fragment's
        // place on the body's own disc is (vUv - 0.5) * 2 / lim, which is the body's own radius as one, so the
        // shadow's axis is a point on the same scale and the distance to it is the distance that decides how
        // much of the sun the world is covering there. It darkens and reddens the light rather than painting a
        // colour on - so the maria stay darker than the highlands through a totality, which is what a photograph
        // of one shows - and it is scaled by the body's own alpha, so nothing behind the moon is touched at all.
        vec3 shade = vec3(1.0);
        if (ShadowStrength > 0.0) {
            vec2 place = (vUv - 0.5) * 2.0 / lim;
            float axis = length(place - vec2(ShadowOffset, 0.0));
            float cover = 1.0 - smoothstep(ShadowUmbra, ShadowUmbra * 1.45, axis);
            // The middle of the shadow is where the light that got through crossed the most air, so the umbra is
            // at its brightest on the axis and dims toward its own edge.
            vec3 umbra = ShadowTint * (1.0 - 0.40 * smoothstep(0.0, ShadowUmbra, axis));
            shade = mix(vec3(1.0), umbra, cover * ShadowStrength);
        }
        fragColor = vec4(body.rgb * vColor.rgb * Gain * shade,
                clamp(alpha * vColor.a, 0.0, 1.0)) * ColorModulator;
        return;
    }

    // The band. uv.y runs across the ribbon, uv.x along it, and uv.x is wrapped so the noise joins where the
    // circle closes.
    float across = abs(vUv.y * 2.0 - 1.0);
    float core = 1.0 - smoothstep(0.12, 1.0, across);
    core *= core;
    float cloud = noise(vec2(vUv.x * 26.0, vUv.y * 7.0)) * 0.60
            + noise(vec2(vUv.x * 74.0, vUv.y * 19.0)) * 0.40;
    float dust = core * (0.30 + 0.70 * cloud);
    // Resolved stars: a sparse sprinkle of pinpricks, only where the band already is, so the galaxy reads
    // as stars packed into a glow rather than as a glow with dust painted on it.
    float stars = step(0.985, hash(floor(vec2(vUv.x * 1400.0, vUv.y * 220.0)))) * core;
    float alpha = vColor.a * (dust * 0.90 + stars * 1.30);
    if (alpha <= 0.002) {
        discard;
    }
    vec3 colour = vColor.rgb * (0.82 + 0.36 * cloud);
    fragColor = vec4(colour, clamp(alpha, 0.0, 1.0)) * ColorModulator;
}
