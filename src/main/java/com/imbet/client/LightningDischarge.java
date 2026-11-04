package com.imbet.client;

/**
 * The kind of discharge one strike is.
 *
 * <p>Real storms do not fire one kind of lightning. A cloud-to-ground bolt drives a channel from the
 * cloud to the ground and is what the mod already draws. An <em>anvil crawler</em> is the same
 * ground-reaching current seen from underneath: at the anvil it throws long horizontal tendrils that
 * race out across the cloud base - "spider lightning" - in a fraction of a second. A
 * <em>cloud-to-cloud</em> (intracloud) discharge is the most common of all: a horizontal channel that
 * never touches the ground, lighting the cloud from the inside with no channel to the surface.</p>
 *
 * <p>Which one a strike is has to be decided from something every client already agrees on, because
 * nothing about it is synced. The strike's own seed is exactly that: the vanilla and Weather2 bolt
 * classes both carry it, {@link LightningRevamp} already reads it to build the channel, and two
 * clients - or two frames - derive the same answer from it without a packet. So the roll lives here,
 * over the seed, and the renderer asks it once per strike rather than per frame being told.</p>
 */
public enum LightningDischarge {
    /** A cloud-to-ground strike: the channel climbs from the ground it hit. */
    GROUND,
    /** An anvil crawler: a ground-reaching channel that races out across the cloud base as tendrils. */
    ANVIL_CRAWLER,
    /** A cloud-to-cloud discharge: a horizontal channel that never reaches the ground. */
    CLOUD_TO_CLOUD;

    /** Whether this discharge lights a patch of ground - only a strike that reaches it can. */
    public boolean touchesGround() {
        return this == GROUND;
    }

    /** Whether this discharge hangs in the cloud rather than standing on the ground it hit. */
    public boolean hangsInCloud() {
        return this == CLOUD_TO_CLOUD;
    }

    /**
     * The discharge a strike with this seed is, given how often crawlers and cloud-to-cloud flashes
     * should turn up. The two chances are taken out of the same roll, so the ground share is whatever
     * is left over and the three can never sum to more than one.
     */
    public static LightningDischarge choose(long seed, double crawlerChance, double cloudToCloudChance) {
        double roll = unit(seed);
        double cloud = clamp(cloudToCloudChance);
        double crawler = clamp(crawlerChance);
        if (roll < cloud) {
            return CLOUD_TO_CLOUD;
        }
        if (roll < cloud + crawler) {
            return ANVIL_CRAWLER;
        }
        return GROUND;
    }

    /** A stable 0..1 value for the seed, so the roll is the same on every client and every frame. */
    private static double unit(long seed) {
        long z = seed * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11 & 0xFFFF_FFFFL) / 4294967296.0D;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
