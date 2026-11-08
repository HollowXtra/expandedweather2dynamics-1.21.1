package com.imbet.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * One tongue of a wildfire's flame. It flickers through the flame frames, swells as it catches and shrinks as it dies,
 * rises slowly and leans with whatever motion it was given, and it is drawn at full brightness - flame is its own light,
 * so a fire front at night is the brightest thing in the landscape rather than a row of dark sprites.
 *
 * <p>A negative y speed in the spawn call is the flame's size rather than a speed - no rising flame moves down - and the
 * x and z speeds are kept as its drift, so one particle type serves the little flames at the edge of a grass fire, the
 * tall ones in a burning wood and the ones whipped round a fire whirl.
 */
public final class WildfireFlameParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float fullSize;

    private WildfireFlameParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.sprites = sprites;
        boolean sized = vy < 0.0D;
        this.fullSize = sized ? (float) Mth.clamp(-vy, 0.3D, 12.0D) : 0.9F + random.nextFloat() * 0.8F;
        this.xd = vx;
        // A flame swept sideways is swept up as well: the faster it is carried round, the harder it climbs.
        this.yd = sized ? 0.02D + random.nextDouble() * 0.03D + Math.hypot(vx, vz) * 1.2D : vy;
        this.zd = vz;
        this.lifetime = 14 + random.nextInt(14);
        this.quadSize = fullSize * 0.4F;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 0.96F;
        float warmth = random.nextFloat();
        this.rCol = 1.0F;
        this.gCol = 0.86F + 0.14F * warmth;
        this.bCol = 0.80F + 0.20F * warmth;
        this.alpha = 0.95F;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) {
            return;
        }
        float life = (float) age / Math.max(1, lifetime);
        // Catch, burn, die: swelling over the first quarter of its life and shrinking through the rest.
        float swell = life < 0.25F ? life / 0.25F : 1.0F - (life - 0.25F) / 0.75F * 0.65F;
        quadSize = fullSize * Mth.clamp(swell, 0.2F, 1.0F);
        alpha = life > 0.7F ? 0.95F * (1.0F - (life - 0.7F) / 0.3F) : 0.95F;
        setSpriteFromAge(sprites);
    }

    @Override
    public int getLightColor(float partialTick) {
        return 0xF000F0;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            return new WildfireFlameParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}
