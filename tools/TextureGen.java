import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Generates the mod's machine textures.
 *
 * <p>They are generated rather than painted so they can be retuned as a set and so every material is seamless by
 * construction: all the noise here wraps at the tile edge. Nothing in the game runs this; its output is checked in
 * under {@code assets/expandedweather2dynamics/textures}. Regenerate from the repository root with:
 *
 * <pre>java tools/TextureGen.java src/main/resources/assets/expandedweather2dynamics/textures</pre>
 */
public final class TextureGen {
    private static File root;

    public static void main(String[] args) throws Exception {
        root = new File(args[0]);
        materials();
        instrumentSheets();
        blockFaces();
        items();
        villagers();
        weatherPuffs();
        debrisSheets();
    }

    // ------------------------------------------------------------------------------------------------
    // Wrapping noise.
    // ------------------------------------------------------------------------------------------------

    private static final class Noise {
        private final float[] lattice;
        private final int size;

        Noise(long seed, int size) {
            this.size = size;
            lattice = new float[size * size];
            Random random = new Random(seed);
            for (int i = 0; i < lattice.length; i++) {
                lattice[i] = random.nextFloat();
            }
        }

        private float at(int x, int y) {
            return lattice[Math.floorMod(y, size) * size + Math.floorMod(x, size)];
        }

        /** Value noise over one tile of unit coordinates, at {@code cells} lattice cells across. Seamless. */
        float sample(float u, float v, int cells) {
            float x = u * cells;
            float y = v * cells;
            int ix = (int) Math.floor(x);
            int iy = (int) Math.floor(y);
            float fx = x - ix;
            float fy = y - iy;
            fx = fx * fx * (3 - 2 * fx);
            fy = fy * fy * (3 - 2 * fy);
            int wrap = cells;
            float a = at(Math.floorMod(ix, wrap), Math.floorMod(iy, wrap));
            float b = at(Math.floorMod(ix + 1, wrap), Math.floorMod(iy, wrap));
            float c = at(Math.floorMod(ix, wrap), Math.floorMod(iy + 1, wrap));
            float d = at(Math.floorMod(ix + 1, wrap), Math.floorMod(iy + 1, wrap));
            return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
        }

        float fbm(float u, float v, int cells, int octaves) {
            float sum = 0;
            float amp = 0.5F;
            float total = 0;
            for (int i = 0; i < octaves; i++) {
                sum += sample(u, v, cells << i) * amp;
                total += amp;
                amp *= 0.5F;
            }
            return sum / total;
        }
    }

    private interface Shader {
        int shade(float u, float v, int x, int y);
    }

    private static int rgb(float r, float g, float b) {
        return rgba(r, g, b, 1);
    }

    private static int rgba(float r, float g, float b, float a) {
        return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static void tile(String path, int size, Shader shader) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, shader.shade((x + 0.5F) / size, (y + 0.5F) / size, x, y));
            }
        }
        write(path, image);
    }

    private static void write(String path, BufferedImage image) throws Exception {
        File file = new File(root, path);
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
    }

    // ------------------------------------------------------------------------------------------------
    // The materials the machines are made of.
    // ------------------------------------------------------------------------------------------------

    private static void materials() throws Exception {
        int s = 128;
        Noise n1 = new Noise(11, 512);
        Noise n2 = new Noise(23, 512);
        Noise n3 = new Noise(37, 512);

        // Hot-dip galvanising: the big crystalline spangle is the whole of what makes it read as zinc.
        BufferedImage spangle = spangle(s, 64, 91);
        tile("block/machinery/galvanized.png", s, (u, v, x, y) -> {
            float crystal = (spangle.getRGB(x, y) & 255) / 255F;
            float grime = n1.fbm(u, v, 3, 4);
            float streak = n2.fbm(u * 1, v, 8, 3);
            float base = 0.60F + (crystal - 0.5F) * 0.16F + (grime - 0.5F) * 0.13F;
            base -= smooth(0.56F, 0.78F, streak) * 0.07F;
            return rgb(base * 0.97F, base * 0.99F, base * 1.01F);
        });
        tile("block/machinery/steel_dark.png", s, (u, v, x, y) -> {
            float scuff = n1.fbm(u, v, 6, 4);
            float fine = n2.sample(u, v, 48);
            float base = 0.21F + (scuff - 0.5F) * 0.10F + (fine - 0.5F) * 0.03F;
            return rgb(base * 0.96F, base, base * 1.05F);
        });
        tile("block/machinery/paint_gray.png", s, (u, v, x, y) -> paint(n1, n2, n3, u, v, 0.56F, 0.58F, 0.57F));
        tile("block/machinery/paint_yellow.png", s, (u, v, x, y) -> paint(n1, n2, n3, u, v, 0.80F, 0.63F, 0.16F));
        tile("block/machinery/paint_olive.png", s, (u, v, x, y) -> paint(n1, n2, n3, u, v, 0.42F, 0.45F, 0.30F));
        tile("block/machinery/paint_white.png", s, (u, v, x, y) -> paint(n1, n2, n3, u, v, 0.80F, 0.81F, 0.80F));
        tile("block/machinery/aluminum.png", s, (u, v, x, y) -> {
            // Brushed: long fine lines one way, and a broad unevenness over them.
            float brush = n1.sample(u * 0.06F, v, 128) * 0.6F + n2.sample(u * 0.12F, v, 64) * 0.4F;
            float broad = n3.fbm(u, v, 4, 3);
            float base = 0.68F + (brush - 0.5F) * 0.13F + (broad - 0.5F) * 0.07F;
            return rgb(base * 0.98F, base, base * 1.02F);
        });
        tile("block/machinery/wood_pole.png", s, (u, v, x, y) -> {
            // A creosoted utility pole: nearly black brown, long grain, and the checks a pole dries out with.
            float grain = n1.sample(u, v * 0.10F, 40) * 0.55F + n2.sample(u, v * 0.18F, 24) * 0.45F;
            float fibre = n3.sample(u, v * 0.04F, 96);
            float check = smooth(0.73F, 0.80F, n2.sample(u + n1.sample(u, v, 8) * 0.03F, v * 0.05F, 16));
            float tone = 0.26F + (grain - 0.5F) * 0.20F + (fibre - 0.5F) * 0.08F;
            tone *= 1.0F - check * 0.78F;
            return rgb(tone * 1.18F, tone * 0.92F, tone * 0.66F);
        });
        tile("block/machinery/concrete.png", s, (u, v, x, y) -> {
            float blot = n1.fbm(u, v, 5, 4);
            float grit = n2.sample(u, v, 64);
            float pit = smooth(0.82F, 0.88F, n3.sample(u, v, 40));
            float tone = 0.60F + (blot - 0.5F) * 0.16F + (grit - 0.5F) * 0.07F - pit * 0.16F;
            return rgb(tone, tone * 0.985F, tone * 0.95F);
        });
        tile("block/machinery/rubber.png", s, (u, v, x, y) -> {
            float tone = 0.085F + (n1.sample(u, v, 64) - 0.5F) * 0.03F + (n2.fbm(u, v, 4, 3) - 0.5F) * 0.03F;
            return rgb(tone, tone, tone * 1.05F);
        });
        tile("block/machinery/copper.png", s, (u, v, x, y) -> {
            // Weathered copper: the verdigris gathers in patches rather than evenly.
            float patina = smooth(0.46F, 0.64F, n1.fbm(u, v, 5, 4));
            float tone = n2.fbm(u, v, 10, 3);
            float r = mix(0.62F + tone * 0.18F, 0.30F, patina);
            float g = mix(0.36F + tone * 0.12F, 0.56F, patina);
            float b = mix(0.20F + tone * 0.08F, 0.48F, patina);
            return rgb(r, g, b);
        });
        tile("block/machinery/brass.png", s, (u, v, x, y) -> {
            float brush = n1.sample(u * 0.08F, v, 96);
            float tarnish = n2.fbm(u, v, 5, 4);
            float tone = 0.74F + (brush - 0.5F) * 0.12F - smooth(0.45F, 0.75F, tarnish) * 0.20F;
            return rgb(tone, tone * 0.77F, tone * 0.40F);
        });
        tile("block/machinery/louver.png", s, (u, v, x, y) -> {
            // Rows of slots with a lit lip over each one: the chopper's ports.
            float row = v * 6 % 1;
            float slot = smooth(0.12F, 0.2F, row) * (1 - smooth(0.72F, 0.8F, row));
            float column = u * 8 % 1;
            float bar = smooth(0.05F, 0.1F, column) * (1 - smooth(0.9F, 0.95F, column));
            float open = slot * bar;
            float lip = (1 - smooth(0.0F, 0.12F, row)) * 0.25F;
            float metal = 0.50F + lip + (n1.fbm(u, v, 6, 3) - 0.5F) * 0.12F;
            float tone = mix(metal, 0.035F, open);
            return rgb(tone, tone, tone * 1.02F);
        });
        tile("block/machinery/bore.png", s, (u, v, x, y) -> {
            float tone = 0.17F + (n1.fbm(u, v, 8, 3) - 0.5F) * 0.08F;
            return rgb(tone * 0.95F, tone, tone * 1.05F);
        });
        tile("block/machinery/lens.png", s, (u, v, x, y) -> {
            // Fresnel ribs on a white base, so a lamp tints to whatever colour its state asks for.
            float rib = 0.5F + 0.5F * (float) Math.sin(v * Math.PI * 2 * 7);
            float tone = 0.72F + rib * 0.28F;
            return rgb(tone, tone, tone);
        });
        tile("block/machinery/solar_cell.png", s, (u, v, x, y) -> {
            float gridU = u * 4 % 1;
            float gridV = v * 4 % 1;
            float seam = 1 - smooth(0.02F, 0.05F, Math.min(Math.min(gridU, 1 - gridU), Math.min(gridV, 1 - gridV)));
            float finger = smooth(0.90F, 0.97F, 0.5F + 0.5F * (float) Math.cos(u * Math.PI * 2 * 24));
            float crystal = n1.fbm(u, v, 12, 3);
            float blue = 0.28F + (crystal - 0.5F) * 0.16F;
            float r = blue * 0.26F;
            float g = blue * 0.42F;
            r = mix(r, 0.72F, Math.max(seam * 0.85F, finger * 0.28F));
            g = mix(g, 0.74F, Math.max(seam * 0.85F, finger * 0.28F));
            float b = mix(blue, 0.76F, Math.max(seam * 0.85F, finger * 0.28F));
            return rgb(r, g, b);
        });
        tile("block/machinery/dish.png", s, (u, v, x, y) -> {
            float tone = 0.84F + (n1.fbm(u, v, 8, 3) - 0.5F) * 0.07F - smooth(0.62F, 0.8F, n2.fbm(u, v, 3, 4)) * 0.10F;
            return rgb(tone, tone * 1.005F, tone * 1.01F);
        });
        tile("block/machinery/cup.png", s, (u, v, x, y) -> {
            float tone = 0.78F + (n1.sample(u, v, 32) - 0.5F) * 0.05F;
            return rgb(tone, tone, tone * 1.01F);
        });
        cabinetDoor();
    }

    private static int paint(Noise n1, Noise n2, Noise n3, float u, float v, float r, float g, float b) {
        // Orange-peel in the coat, chalking where the weather has had it, rust through the worst of the chips, and
        // the streaks rain leaves running down from them.
        float peel = n1.sample(u, v, 42) - 0.5F;
        float chalk = n2.fbm(u, v, 4, 4);
        float chips = n3.fbm(u, v, 18, 3);
        float chip = smooth(0.79F, 0.83F, chips);
        float run = smooth(0.62F, 0.78F, n2.fbm(u, v * 0.25F + 0.3F, 12, 2));
        float tone = 1.0F + peel * 0.05F - smooth(0.45F, 0.8F, chalk) * 0.12F - run * 0.06F;
        float pr = r * tone;
        float pg = g * tone;
        float pb = b * tone;
        return rgb(mix(pr, 0.40F, chip), mix(pg, 0.27F, chip), mix(pb, 0.17F, chip));
    }

    /** The cabinet door: a weatherproof enclosure with its gasket seam, hinges, latch and warning plate. */
    private static void cabinetDoor() throws Exception {
        int size = 128;
        Noise n1 = new Noise(41, 512);
        Noise n2 = new Noise(43, 512);
        Noise n3 = new Noise(47, 512);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, paint(n1, n2, n3, (x + 0.5F) / size, (y + 0.5F) / size, 0.58F, 0.60F, 0.59F));
            }
        }
        Graphics2D g = pen(image);
        g.setColor(new Color(0, 0, 0, 120));
        g.setStroke(new BasicStroke(2F));
        g.draw(new RoundRectangle2D.Float(7, 7, 114, 114, 8, 8));
        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(1F));
        g.draw(new RoundRectangle2D.Float(9, 9, 110, 110, 7, 7));
        // Hinges.
        for (int hy : new int[]{22, 92}) {
            g.setColor(new Color(60, 62, 64));
            g.fillRect(2, hy, 10, 14);
            g.setColor(new Color(150, 152, 154));
            g.fillRect(2, hy + 1, 10, 3);
        }
        // Latch.
        g.setColor(new Color(40, 42, 44));
        g.fill(new RoundRectangle2D.Float(104, 52, 11, 26, 3, 3));
        g.setColor(new Color(150, 152, 156));
        g.fill(new Ellipse2D.Float(106, 61, 7, 7));
        // Warning plate: black over yellow, with the lines of print a plate has, too small to read.
        g.setColor(new Color(214, 170, 34));
        g.fillRect(30, 24, 62, 30);
        g.setColor(new Color(30, 30, 30));
        g.fillRect(30, 24, 62, 9);
        g.setColor(new Color(214, 170, 34));
        g.fillRect(40, 27, 42, 3);
        g.setColor(new Color(30, 30, 30));
        for (int line = 0; line < 4; line++) {
            g.fillRect(34, 37 + line * 4, 54 - line * 5, 2);
        }
        // Vent louvres low on the door.
        for (int row = 0; row < 5; row++) {
            int ly = 80 + row * 7;
            g.setColor(new Color(22, 24, 26));
            g.fillRect(34, ly, 58, 3);
            g.setColor(new Color(255, 255, 255, 60));
            g.fillRect(34, ly - 1, 58, 1);
        }
        g.dispose();
        write("block/machinery/cabinet_door.png", image);
    }

    private static BufferedImage spangle(int size, int crystals, long seed) {
        Random random = new Random(seed);
        float[] px = new float[crystals];
        float[] py = new float[crystals];
        float[] tone = new float[crystals];
        float[] angle = new float[crystals];
        for (int i = 0; i < crystals; i++) {
            px[i] = random.nextFloat();
            py[i] = random.nextFloat();
            tone[i] = random.nextFloat();
            angle[i] = random.nextFloat() * 6.283F;
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float best = 9;
                int who = 0;
                for (int i = 0; i < crystals; i++) {
                    float dx = Math.abs(u - px[i]);
                    float dy = Math.abs(v - py[i]);
                    dx = Math.min(dx, 1 - dx);
                    dy = Math.min(dy, 1 - dy);
                    float d = dx * dx + dy * dy;
                    if (d < best) {
                        best = d;
                        who = i;
                    }
                }
                // Each crystal has a sheen of its own, running one way across it.
                float sheen = (float) Math.sin((u * Math.cos(angle[who]) + v * Math.sin(angle[who])) * 90) * 0.5F + 0.5F;
                float t = tone[who] * 0.8F + sheen * 0.2F;
                int level = clamp(t);
                image.setRGB(x, y, level << 16 | level << 8 | level);
            }
        }
        return image;
    }

    // ------------------------------------------------------------------------------------------------
    // The shared instrument sheets every JSON machine model wears.
    // ------------------------------------------------------------------------------------------------

    private static void instrumentSheets() throws Exception {
        int s = 64;
        Noise n1 = new Noise(101, 512);
        Noise n2 = new Noise(103, 512);
        tile("block/instrument_metal.png", s, (u, v, x, y) -> {
            float brush = n1.sample(u * 0.08F, v, 64) * 0.6F + n2.sample(u * 0.14F, v, 32) * 0.4F;
            float base = 0.64F + (brush - 0.5F) * 0.14F + (n2.fbm(u, v, 3, 3) - 0.5F) * 0.08F;
            return rgb(base * 0.97F, base, base * 1.03F);
        });
        tile("block/instrument_dark.png", s, (u, v, x, y) -> {
            // Powder coat: a fine even orange-peel, and nothing else, which is why it reads as a coating.
            float peel = n1.sample(u, v, 32) * 0.6F + n2.sample(u, v, 16) * 0.4F;
            float base = 0.145F + (peel - 0.5F) * 0.05F + (n2.fbm(u, v, 2, 2) - 0.5F) * 0.03F;
            return rgb(base * 0.97F, base, base * 1.06F);
        });
        tile("block/instrument_brass.png", s, (u, v, x, y) -> {
            float brush = n1.sample(u * 0.1F, v, 48);
            float tarnish = n2.fbm(u, v, 3, 4);
            float tone = 0.76F + (brush - 0.5F) * 0.12F - smooth(0.42F, 0.72F, tarnish) * 0.24F;
            return rgb(tone, tone * 0.76F, tone * 0.38F);
        });
        tile("block/instrument_glass.png", s, (u, v, x, y) -> {
            // Faintly blue, nearly clear, with the diagonal sheen a pane has and a little dust on it.
            float sheen = smooth(0.42F, 0.5F, (u + v) * 0.5F) * (1 - smooth(0.56F, 0.64F, (u + v) * 0.5F));
            float dust = n1.fbm(u, v, 6, 3);
            float alpha = 0.34F + sheen * 0.24F + (dust - 0.5F) * 0.08F;
            return rgba(0.74F + sheen * 0.2F, 0.84F + sheen * 0.12F, 0.92F, alpha);
        });
        tile("block/charred_log.png", s, (u, v, x, y) -> {
            // Alligatored char: the block-pattern cracking a burnt trunk has, with a hint of ember in the cracks.
            float grain = n1.sample(u, v * 0.14F, 24);
            float crackV = Math.abs((float) Math.sin((v + n2.sample(u, v, 6) * 0.08F) * Math.PI * 8));
            float crackU = Math.abs((float) Math.sin((u + n2.sample(u, v, 6) * 0.08F) * Math.PI * 6));
            float crack = 1 - smooth(0.0F, 0.12F, Math.min(crackU, crackV));
            float tone = 0.11F + (grain - 0.5F) * 0.07F;
            tone *= 1 - crack * 0.75F;
            return rgb(tone * 1.08F + crack * 0.03F, tone, tone * 0.96F);
        });
        tile("block/charred_log_top.png", s, (u, v, x, y) -> {
            float dx = u - 0.5F;
            float dy = v - 0.5F;
            float r = (float) Math.sqrt(dx * dx + dy * dy);
            float ring = 0.5F + 0.5F * (float) Math.sin((r + n1.sample(u, v, 5) * 0.04F) * Math.PI * 22);
            float split = 1 - smooth(0.0F, 0.03F, Math.abs((float) Math.atan2(dy, dx) % 1.05F));
            float tone = 0.12F + ring * 0.05F;
            tone *= 1 - split * 0.6F;
            return rgb(tone * 1.06F, tone, tone * 0.95F);
        });
        tile("block/structural_rebar.png", s, (u, v, x, y) -> {
            // Ribbed reinforcing bar on a dark ground, with the mill scale and flash rust it always has.
            float rowV = v * 4 % 1;
            float rod = 1 - smooth(0.26F, 0.36F, Math.abs(rowV - 0.5F));
            float rib = 0.5F + 0.5F * (float) Math.sin((u + rowV * 0.12F) * Math.PI * 2 * 14);
            float rust = smooth(0.45F, 0.7F, n1.fbm(u, v, 8, 3));
            float height = 1 - Math.abs(rowV - 0.5F) / 0.36F;
            float tone = 0.34F + rib * 0.16F + height * 0.14F;
            float r = mix(tone, tone * 0.98F + 0.10F, rust);
            float g = mix(tone, tone * 0.62F, rust);
            float b = mix(tone * 1.03F, tone * 0.40F, rust);
            float ground = 0.12F + (n2.fbm(u, v, 8, 2) - 0.5F) * 0.05F;
            return rgb(mix(ground, r, rod), mix(ground, g, rod), mix(ground, b, rod));
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Block faces with something on them.
    // ------------------------------------------------------------------------------------------------

    private static Graphics2D pen(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static BufferedImage ground(int size, long seed, float base, float tintR, float tintG, float tintB) {
        Noise n1 = new Noise(seed, 512);
        Noise n2 = new Noise(seed + 2, 512);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float t = base + (n1.sample(u, v, 32) - 0.5F) * base * 0.26F + (n2.fbm(u, v, 3, 3) - 0.5F) * base * 0.34F;
                image.setRGB(x, y, rgb(t * tintR, t * tintG, t * tintB));
            }
        }
        return image;
    }

    private static void frame(Graphics2D g, int size, int inset, Color dark, Color light) {
        g.setStroke(new BasicStroke(1.2F));
        g.setColor(dark);
        g.drawRect(inset, inset, size - inset * 2 - 1, size - inset * 2 - 1);
        g.setColor(light);
        g.drawLine(inset + 1, inset + 1, size - inset - 2, inset + 1);
        g.drawLine(inset + 1, inset + 1, inset + 1, size - inset - 2);
    }

    private static void screw(Graphics2D g, float x, float y, float r) {
        g.setColor(new Color(36, 38, 40));
        g.fill(new Ellipse2D.Float(x - r, y - r, r * 2, r * 2));
        g.setColor(new Color(150, 154, 158));
        g.fill(new Ellipse2D.Float(x - r * 0.72F, y - r * 0.72F, r * 1.44F, r * 1.44F));
        g.setColor(new Color(50, 52, 54));
        g.setStroke(new BasicStroke(Math.max(0.6F, r * 0.3F)));
        g.draw(new Line2D.Float(x - r * 0.6F, y - r * 0.2F, x + r * 0.6F, y + r * 0.2F));
    }

    private static void blockFaces() throws Exception {
        radarBlock();
        rainGauge();
        barometer();
        weatherRadio();
        spcRisk();
        surveyStation();
        tornadoProbe();
        weatherAlertSiren();
        weatherStation();
        weatherVane();
        sawmill();
        buildersBench();
        reinforcedGlass();
    }

    /**
     * The radar's sides: an equipment rack, with its vents, status lamps and a stencil. The sweep lives on the top
     * face now, drawn by the program, so this does not need to animate - it used to be a four-frame strip of a green
     * scope on every side of the block.
     */
    private static void radarBlock() throws Exception {
        int s = 128;
        BufferedImage image = ground(s, 201, 0.17F, 0.96F, 1.0F, 1.06F);
        Graphics2D g = pen(image);
        frame(g, s, 3, new Color(0, 0, 0, 200), new Color(255, 255, 255, 34));
        for (float[] c : new float[][]{{11, 11}, {117, 11}, {11, 117}, {117, 117}}) {
            screw(g, c[0], c[1], 3.2F);
        }
        // Vent louvres.
        for (int i = 0; i < 9; i++) {
            int y = 62 + i * 6;
            g.setColor(new Color(6, 7, 8));
            g.fillRect(26, y, 76, 3);
            g.setColor(new Color(255, 255, 255, 34));
            g.fillRect(26, y + 3, 76, 1);
        }
        // Status strip: power, sweep, data.
        g.setColor(new Color(10, 12, 14));
        g.fill(new RoundRectangle2D.Float(26, 22, 76, 28, 4, 4));
        Color[] lamps = {new Color(70, 220, 110), new Color(70, 220, 110), new Color(240, 170, 50)};
        for (int i = 0; i < 3; i++) {
            float x = 36 + i * 14;
            g.setColor(lamps[i].darker());
            g.fill(new Ellipse2D.Float(x - 3.4F, 32, 6.8F, 6.8F));
            g.setColor(lamps[i]);
            g.fill(new Ellipse2D.Float(x - 2F, 33.4F, 4, 4));
        }
        g.setColor(new Color(140, 148, 152));
        g.fillRect(78, 30, 18, 2);
        g.fillRect(78, 35, 14, 2);
        g.fillRect(78, 40, 18, 2);
        g.dispose();
        write("block/radar_block.png", image);
        File meta = new File(root, "block/radar_block.png.mcmeta");
        if (meta.exists()) {
            meta.delete();
        }
    }

    private static void rainGauge() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 203, 0.60F, 0.97F, 1.0F, 1.02F);
        Graphics2D g = pen(image);
        // Glass tube with water in it, graduated like the standard eight-inch gauge.
        g.setColor(new Color(214, 228, 236));
        g.fill(new RoundRectangle2D.Float(18, 7, 22, 50, 6, 6));
        g.setPaint(new GradientPaint(18, 0, new Color(56, 124, 206), 40, 0, new Color(118, 182, 238)));
        g.fill(new RoundRectangle2D.Float(18, 33, 22, 24, 6, 6));
        g.setColor(new Color(255, 255, 255, 110));
        g.fillRect(22, 10, 3, 44);
        g.setColor(new Color(26, 30, 34));
        for (int i = 0; i < 9; i++) {
            int y = 11 + i * 5;
            g.fillRect(40, y, i % 2 == 0 ? 8 : 5, 1);
        }
        g.setStroke(new BasicStroke(1F));
        g.setColor(new Color(40, 44, 48));
        g.draw(new RoundRectangle2D.Float(18, 7, 22, 50, 6, 6));
        screw(g, 54, 8, 2.2F);
        screw(g, 54, 56, 2.2F);
        g.dispose();
        write("block/rain_gauge.png", image);
    }

    private static void barometer() throws Exception {
        int s = 64;
        Noise n1 = new Noise(205, 512);
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float u = (x + 0.5F) / s;
                float v = (y + 0.5F) / s;
                // Oiled hardwood, the way an aneroid's case is.
                float grain = n1.sample(u + n1.sample(u, v, 4) * 0.05F, v * 0.2F, 24);
                float t = 0.34F + (grain - 0.5F) * 0.22F;
                image.setRGB(x, y, rgb(t * 1.20F, t * 0.82F, t * 0.50F));
            }
        }
        Graphics2D g = pen(image);
        g.setColor(new Color(176, 136, 64));
        g.fill(new Ellipse2D.Float(4, 4, 56, 56));
        g.setColor(new Color(232, 200, 120));
        g.setStroke(new BasicStroke(1.2F));
        g.draw(new Ellipse2D.Float(5, 5, 54, 54));
        g.setColor(new Color(234, 229, 214));
        g.fill(new Ellipse2D.Float(9, 9, 46, 46));
        g.setColor(new Color(40, 34, 28));
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2 * i / 36;
            float r0 = i % 3 == 0 ? 17.5F : 20;
            g.setStroke(new BasicStroke(i % 3 == 0 ? 1.1F : 0.6F));
            g.draw(new Line2D.Float(32 + (float) Math.cos(a) * r0, 32 + (float) Math.sin(a) * r0, 32 + (float) Math.cos(a) * 22, 32 + (float) Math.sin(a) * 22));
        }
        g.setColor(new Color(170, 30, 26));
        g.setStroke(new BasicStroke(1.4F));
        g.draw(new Line2D.Float(32, 32, 22, 44));
        g.setColor(new Color(30, 28, 26));
        g.setStroke(new BasicStroke(1.2F));
        g.draw(new Line2D.Float(32, 32, 44, 20));
        g.fill(new Ellipse2D.Float(29.5F, 29.5F, 5, 5));
        g.dispose();
        write("block/barometer.png", image);
    }

    private static void weatherRadio() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 207, 0.16F, 0.98F, 1.0F, 1.04F);
        Graphics2D g = pen(image);
        frame(g, s, 1, new Color(0, 0, 0, 180), new Color(255, 255, 255, 30));
        // Speaker grille.
        for (int yy = 0; yy < 7; yy++) {
            for (int xx = 0; xx < 7; xx++) {
                g.setColor(new Color(6, 6, 7));
                g.fill(new Ellipse2D.Float(7 + xx * 4.2F, 20 + yy * 5, 2.6F, 2.6F));
            }
        }
        // Tuning display.
        g.setColor(new Color(12, 30, 18));
        g.fill(new RoundRectangle2D.Float(38, 12, 21, 18, 3, 3));
        g.setColor(new Color(116, 226, 142));
        g.fillRect(41, 16, 13, 2);
        g.fillRect(41, 21, 9, 2);
        g.setColor(new Color(60, 66, 70));
        g.setStroke(new BasicStroke(1F));
        g.draw(new RoundRectangle2D.Float(38, 12, 21, 18, 3, 3));
        // Alert lamp and buttons.
        g.setColor(new Color(214, 54, 44));
        g.fill(new Ellipse2D.Float(40, 36, 6, 6));
        g.setColor(new Color(140, 146, 150));
        for (int i = 0; i < 3; i++) {
            g.fill(new RoundRectangle2D.Float(38 + i * 7, 47, 5.5F, 4, 1, 1));
        }
        // Whip antenna base.
        g.setColor(new Color(170, 174, 178));
        g.fillRect(12, 3, 3, 13);
        g.dispose();
        write("block/weather_radio.png", image);
    }

    private static void spcRisk() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 209, 0.13F, 0.96F, 1.0F, 1.05F);
        Graphics2D g = pen(image);
        frame(g, s, 1, new Color(0, 0, 0, 200), new Color(255, 255, 255, 30));
        g.setColor(new Color(4, 6, 8));
        g.fill(new RoundRectangle2D.Float(7, 8, 50, 30, 3, 3));
        Color[] cats = {new Color(150, 214, 150), new Color(78, 168, 88), new Color(246, 226, 94), new Color(236, 152, 58), new Color(222, 62, 52), new Color(216, 70, 206)};
        for (int i = 0; i < cats.length; i++) {
            g.setColor(cats[i]);
            g.fillRect(11 + i * 7, 14, 6, 19);
        }
        g.setColor(new Color(255, 255, 255, 30));
        g.fillRect(7, 8, 50, 6);
        Color[] lamps = {new Color(70, 214, 110), new Color(236, 180, 60), new Color(222, 64, 54)};
        for (int i = 0; i < 3; i++) {
            g.setColor(lamps[i]);
            g.fill(new Ellipse2D.Float(14 + i * 14, 45, 5, 5));
        }
        g.setColor(new Color(20, 22, 24));
        g.fillRect(9, 54, 46, 2);
        g.dispose();
        write("block/spc_risk_block.png", image);
    }

    private static void surveyStation() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 211, 0.22F, 0.92F, 1.02F, 1.04F);
        Graphics2D g = pen(image);
        frame(g, s, 2, new Color(0, 0, 0, 200), new Color(255, 255, 255, 40));
        for (float[] c : new float[][]{{7, 7}, {57, 7}, {7, 57}, {57, 57}}) {
            screw(g, c[0], c[1], 2F);
        }
        // Total-station optic: a machined ring with a lit reticle in it.
        g.setColor(new Color(16, 20, 24));
        g.fill(new Ellipse2D.Float(14, 14, 36, 36));
        g.setColor(new Color(150, 158, 164));
        g.setStroke(new BasicStroke(2.2F));
        g.draw(new Ellipse2D.Float(15, 15, 34, 34));
        g.setColor(new Color(120, 226, 236));
        g.setStroke(new BasicStroke(1F));
        g.draw(new Ellipse2D.Float(23, 23, 18, 18));
        g.draw(new Line2D.Float(32, 18, 32, 46));
        g.draw(new Line2D.Float(18, 32, 46, 32));
        g.dispose();
        write("block/survey_station.png", image);
    }

    private static void tornadoProbe() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 213, 0.30F, 0.98F, 1.0F, 1.02F);
        Graphics2D g = pen(image);
        frame(g, s, 1, new Color(0, 0, 0, 200), new Color(255, 255, 255, 40));
        // Armoured data window.
        g.setColor(new Color(8, 14, 12));
        g.fill(new RoundRectangle2D.Float(14, 11, 36, 21, 3, 3));
        g.setColor(new Color(112, 226, 140));
        g.fillRect(18, 16, 19, 3);
        g.fillRect(18, 22, 12, 3);
        g.setColor(new Color(120, 124, 128));
        g.setStroke(new BasicStroke(1.4F));
        g.draw(new RoundRectangle2D.Float(14, 11, 36, 21, 3, 3));
        // Hazard chevrons along the foot, the way this kind of case is marked.
        g.setClip(2, 44, 60, 18);
        g.setColor(new Color(226, 178, 38));
        g.fillRect(2, 44, 60, 18);
        g.setColor(new Color(20, 20, 20));
        g.setStroke(new BasicStroke(6F));
        for (int i = -4; i < 14; i++) {
            float x = i * 11;
            g.draw(new Line2D.Float(x, 64, x + 18, 42));
        }
        g.setClip(null);
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(2, 44, 60, 1);
        g.dispose();
        write("block/tornado_probe.png", image);
    }

    private static void weatherAlertSiren() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 215, 0.26F, 1.20F, 0.70F, 0.60F);
        Graphics2D g = pen(image);
        frame(g, s, 1, new Color(0, 0, 0, 200), new Color(255, 255, 255, 30));
        // Civil-defence plate: the red-framed yellow cross of an emergency-services panel.
        g.setColor(new Color(186, 42, 34));
        g.setStroke(new BasicStroke(3F));
        g.drawRect(9, 9, 46, 46);
        g.setColor(new Color(230, 186, 52));
        g.fillRect(27, 14, 10, 36);
        g.fillRect(14, 27, 36, 10);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(27, 48, 10, 2);
        g.fillRect(14, 35, 36, 2);
        for (float[] c : new float[][]{{5, 5}, {59, 5}, {5, 59}, {59, 59}}) {
            screw(g, c[0], c[1], 1.9F);
        }
        g.dispose();
        write("block/weather_alert_siren.png", image);
    }

    private static void weatherStation() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 217, 0.28F, 0.78F, 0.60F, 0.42F);
        Graphics2D g = pen(image);
        // Stevenson-screen slats on a framed wooden louvre.
        for (int i = 0; i < 9; i++) {
            int y = 7 + i * 6;
            g.setPaint(new GradientPaint(0, y, new Color(232, 230, 222), 0, y + 5, new Color(166, 164, 156)));
            g.fillRect(7, y, 50, 5);
            g.setColor(new Color(30, 28, 26));
            g.fillRect(7, y + 5, 50, 1);
        }
        g.setColor(new Color(96, 74, 50));
        g.setStroke(new BasicStroke(3F));
        g.drawRect(5, 5, 53, 53);
        g.dispose();
        write("block/weather_station.png", image);
    }

    private static void weatherVane() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 219, 0.62F, 0.97F, 1.0F, 1.03F);
        Graphics2D g = pen(image);
        frame(g, s, 1, new Color(0, 0, 0, 140), new Color(255, 255, 255, 50));
        g.setColor(new Color(30, 32, 36));
        g.setStroke(new BasicStroke(1.5F));
        g.draw(new Ellipse2D.Float(7, 7, 50, 50));
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16;
            float r0 = i % 4 == 0 ? 17 : 21;
            g.setStroke(new BasicStroke(i % 4 == 0 ? 1.4F : 0.7F));
            g.draw(new Line2D.Float(32 + (float) Math.cos(a) * r0, 32 + (float) Math.sin(a) * r0, 32 + (float) Math.cos(a) * 24.5F, 32 + (float) Math.sin(a) * 24.5F));
        }
        g.dispose();
        write("block/weather_vane.png", image);
    }

    private static void sawmill() throws Exception {
        int s = 64;
        BufferedImage image = ground(s, 221, 0.25F, 1.12F, 0.92F, 0.72F);
        Graphics2D g = pen(image);
        // The blade, with its teeth.
        Path2D blade = new Path2D.Float();
        int teeth = 24;
        for (int i = 0; i < teeth * 2; i++) {
            double a = Math.PI * 2 * i / (teeth * 2);
            float r = i % 2 == 0 ? 20 : 17.4F;
            float x = 40 + (float) Math.cos(a) * r;
            float y = 21 + (float) Math.sin(a) * r;
            if (i == 0) {
                blade.moveTo(x, y);
            } else {
                blade.lineTo(x, y);
            }
        }
        blade.closePath();
        g.setPaint(new GradientPaint(22, 4, new Color(214, 218, 222), 58, 40, new Color(120, 124, 128)));
        g.fill(blade);
        g.setColor(new Color(60, 62, 66));
        g.fill(new Ellipse2D.Float(36, 17, 8, 8));
        g.setColor(new Color(176, 180, 184));
        g.fill(new Ellipse2D.Float(38, 19, 4, 4));
        // End grain of a sawn log.
        g.setColor(new Color(150, 106, 58));
        g.fill(new Ellipse2D.Float(4, 38, 22, 22));
        g.setColor(new Color(100, 66, 34));
        g.setStroke(new BasicStroke(0.8F));
        for (float r = 3; r < 11; r += 2.4F) {
            g.draw(new Ellipse2D.Float(15 - r, 49 - r, r * 2, r * 2));
        }
        g.dispose();
        write("block/sawmill.png", image);
    }

    private static void buildersBench() throws Exception {
        int s = 64;
        Noise n1 = new Noise(223, 512);
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float u = (x + 0.5F) / s;
                float v = (y + 0.5F) / s;
                float plank = (float) Math.floor(v * 4) * 0.13F;
                float grain = n1.sample(u * 0.25F + plank, v, 32);
                float seam = 1 - smooth(0.0F, 0.04F, Math.abs(v * 4 % 1 - 0.5F) > 0.48F ? 0.0F : 1.0F);
                float t = 0.62F + (grain - 0.5F) * 0.24F - seam * 0.32F;
                image.setRGB(x, y, rgb(t * 1.10F, t * 0.86F, t * 0.58F));
            }
        }
        Graphics2D g = pen(image);
        g.setColor(new Color(120, 124, 128));
        g.fill(new RoundRectangle2D.Float(6, 7, 52, 7, 2, 2));
        g.setColor(new Color(178, 182, 186));
        g.fillRect(6, 7, 52, 2);
        g.setColor(new Color(70, 72, 76));
        for (int i = 0; i < 8; i++) {
            g.fillRect(10 + i * 6, 11, 1, 3);
        }
        g.setColor(new Color(90, 92, 96));
        g.fill(new RoundRectangle2D.Float(12, 26, 18, 4, 1, 1));
        g.fill(new RoundRectangle2D.Float(38, 48, 20, 6, 1, 1));
        g.setColor(new Color(160, 120, 70));
        g.fill(new RoundRectangle2D.Float(30, 26, 5, 18, 1, 1));
        g.dispose();
        write("block/builders_bench.png", image);
    }

    private static void reinforcedGlass() throws Exception {
        int s = 64;
        Noise n1 = new Noise(225, 512);
        BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float u = (x + 0.5F) / s;
                float v = (y + 0.5F) / s;
                float dust = n1.fbm(u, v, 6, 3);
                float sheen = smooth(0.38F, 0.46F, (u + v) * 0.5F) * (1 - smooth(0.50F, 0.58F, (u + v) * 0.5F));
                image.setRGB(x, y, rgba(0.62F + sheen * 0.3F, 0.70F + sheen * 0.22F, 0.74F + sheen * 0.2F, 0.22F + sheen * 0.16F + (dust - 0.5F) * 0.08F));
            }
        }
        Graphics2D g = pen(image);
        // Wired glass, in the diamond mesh it is actually made with, in a steel frame.
        g.setColor(new Color(60, 66, 70, 200));
        g.setStroke(new BasicStroke(0.7F));
        for (int i = -64; i < 128; i += 10) {
            g.draw(new Line2D.Float(i, 0, i + 64, 64));
            g.draw(new Line2D.Float(i, 64, i + 64, 0));
        }
        g.setColor(new Color(50, 54, 58));
        g.setStroke(new BasicStroke(5F));
        g.drawRect(2, 2, 59, 59);
        g.setColor(new Color(130, 134, 138));
        g.setStroke(new BasicStroke(1F));
        g.drawRect(4, 4, 55, 55);
        g.dispose();
        write("block/reinforced_glass.png", image);
    }

    // ------------------------------------------------------------------------------------------------
    // Items, drawn large and reduced, which is what gets the edges clean.
    // ------------------------------------------------------------------------------------------------

    private interface Painter {
        void paint(Graphics2D g);
    }

    private static void item(String name, Painter painter) throws Exception {
        int big = 256;
        BufferedImage large = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = pen(large);
        painter.paint(g);
        g.dispose();
        BufferedImage small = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D d = small.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        d.drawImage(large, 0, 0, 64, 64, null);
        d.dispose();
        write("item/" + name + ".png", small);
    }

    /** The portable radar's case, at twice its GUI size: rubber corners, a recessed screen and a row of keys. */
    private static void radarDevice() throws Exception {
        int w = 632;
        int h = 416;
        Noise n1 = new Noise(401, 512);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = pen(image);
        g.setPaint(new GradientPaint(0, 0, new Color(62, 66, 72), 0, h, new Color(30, 32, 36)));
        g.fill(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, 44, 44));
        g.dispose();
        // A fine moulded texture over the plastic.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                if (argb >>> 24 == 0) {
                    continue;
                }
                float grain = (n1.sample(x / 512F, y / 512F, 256) - 0.5F) * 0.07F + 1.0F;
                int r = Math.min(255, Math.round((argb >> 16 & 255) * grain));
                int gr = Math.min(255, Math.round((argb >> 8 & 255) * grain));
                int b = Math.min(255, Math.round((argb & 255) * grain));
                image.setRGB(x, y, argb & 0xFF000000 | r << 16 | gr << 8 | b);
            }
        }
        g = pen(image);
        g.setColor(new Color(255, 255, 255, 34));
        g.setStroke(new BasicStroke(2F));
        g.draw(new RoundRectangle2D.Float(4, 4, w - 8, h - 8, 42, 42));

        // Rubber bumpers in safety orange, each with a grip of ribs moulded into it.
        Color bumper = new Color(226, 124, 34);
        Color rib = new Color(160, 80, 18);
        int b = 62;
        int[][] corners = {{2, 2}, {w - b - 2, 2}, {2, h - b - 2}, {w - b - 2, h - b - 2}};
        for (int[] c : corners) {
            g.setColor(bumper);
            g.fill(new RoundRectangle2D.Float(c[0], c[1], b, b, 30, 30));
            g.setColor(rib);
            g.setStroke(new BasicStroke(2F));
            for (int i = 1; i < 5; i++) {
                g.draw(new Line2D.Float(c[0] + 10, c[1] + i * 12, c[0] + b - 10, c[1] + i * 12));
            }
        }

        // The recess round the screen, lit from above.
        int lx = 36 - 6;
        int ly = 32 - 6;
        int lw = 560 + 12;
        int lh = 336 + 12;
        g.setColor(new Color(10, 11, 13));
        g.fill(new RoundRectangle2D.Float(lx, ly, lw, lh, 14, 14));
        g.setColor(new Color(255, 255, 255, 26));
        g.setStroke(new BasicStroke(2F));
        g.draw(new RoundRectangle2D.Float(lx - 1, ly - 1, lw + 2, lh + 2, 15, 15));

        // Keys along the bottom, a power lamp, and the maker's plate.
        String[] keys = {"PROD", "TILT", "RNG", "LOOP"};
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11));
        for (int i = 0; i < keys.length; i++) {
            int kx = 96 + i * 58;
            g.setPaint(new GradientPaint(0, 376, new Color(86, 90, 96), 0, 400, new Color(44, 46, 50)));
            g.fill(new RoundRectangle2D.Float(kx, 378, 48, 22, 6, 6));
            g.setColor(new Color(214, 220, 222));
            g.drawString(keys[i], kx + 24 - g.getFontMetrics().stringWidth(keys[i]) / 2, 393);
        }
        g.setColor(new Color(30, 90, 44));
        g.fill(new Ellipse2D.Float(452, 381, 15, 15));
        g.setColor(new Color(110, 240, 140));
        g.fill(new Ellipse2D.Float(455, 384, 9, 9));
        g.setColor(new Color(170, 176, 180));
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 13));
        g.drawString("FIELD RADAR RX-7", 482, 394);
        g.dispose();
        write("gui/radar_device.png", image);
    }

    private static void items() throws Exception {
        radarDevice();
        item("portable_radar", g -> {
            // A ruggedised handheld: rubber corners, a stub antenna, and a live scope on its screen.
            g.setColor(new Color(36, 38, 42));
            g.fill(new RoundRectangle2D.Float(170, 12, 18, 62, 6, 6));
            g.setPaint(new GradientPaint(30, 40, new Color(66, 70, 76), 226, 230, new Color(34, 36, 40)));
            g.fill(new RoundRectangle2D.Float(26, 44, 204, 192, 28, 28));
            g.setColor(new Color(240, 170, 40));
            g.fill(new RoundRectangle2D.Float(26, 44, 34, 34, 14, 14));
            g.fill(new RoundRectangle2D.Float(196, 44, 34, 34, 14, 14));
            g.fill(new RoundRectangle2D.Float(26, 202, 34, 34, 14, 14));
            g.fill(new RoundRectangle2D.Float(196, 202, 34, 34, 14, 14));
            g.setColor(new Color(8, 10, 12));
            g.fill(new RoundRectangle2D.Float(46, 62, 164, 130, 12, 12));
            g.setColor(new Color(5, 18, 20));
            g.fill(new Ellipse2D.Float(66, 66, 122, 122));
            g.setColor(new Color(40, 150, 130));
            g.setStroke(new BasicStroke(2.4F));
            g.draw(new Ellipse2D.Float(66, 66, 122, 122));
            g.draw(new Ellipse2D.Float(96, 96, 62, 62));
            // A hook echo on the scope, in the real product colours.
            g.setColor(new Color(2, 196, 2));
            g.fill(new Ellipse2D.Float(116, 92, 56, 46));
            g.setColor(new Color(253, 220, 2));
            g.fill(new Ellipse2D.Float(128, 100, 34, 28));
            g.setColor(new Color(253, 60, 2));
            g.fill(new Ellipse2D.Float(138, 106, 16, 14));
            g.setColor(new Color(170, 240, 214));
            g.setStroke(new BasicStroke(3.4F));
            g.draw(new Line2D.Float(127, 127, 170, 78));
            g.setColor(new Color(90, 94, 100));
            for (int i = 0; i < 4; i++) {
                g.fill(new RoundRectangle2D.Float(54 + i * 40, 204, 30, 14, 4, 4));
            }
            g.setColor(new Color(90, 220, 110));
            g.fill(new Ellipse2D.Float(196, 196, 10, 10));
        });
        item("camera", g -> {
            g.setPaint(new GradientPaint(0, 70, new Color(66, 68, 72), 0, 210, new Color(26, 28, 30)));
            g.fill(new RoundRectangle2D.Float(18, 72, 220, 136, 22, 22));
            g.setColor(new Color(40, 42, 44));
            g.fill(new RoundRectangle2D.Float(92, 44, 78, 40, 10, 10));
            g.setColor(new Color(150, 154, 158));
            g.fillRect(18, 96, 220, 8);
            g.setColor(new Color(30, 30, 32));
            g.fill(new RoundRectangle2D.Float(36, 54, 40, 22, 8, 8));
            g.setColor(new Color(16, 18, 20));
            g.fill(new Ellipse2D.Float(78, 86, 112, 112));
            g.setColor(new Color(120, 124, 130));
            g.setStroke(new BasicStroke(5F));
            g.draw(new Ellipse2D.Float(84, 92, 100, 100));
            g.setPaint(new GradientPaint(100, 100, new Color(60, 110, 170), 170, 180, new Color(8, 16, 34)));
            g.fill(new Ellipse2D.Float(104, 112, 60, 60));
            g.setColor(new Color(255, 255, 255, 170));
            g.fill(new Ellipse2D.Float(116, 122, 16, 12));
            g.setColor(new Color(214, 54, 44));
            g.fill(new Ellipse2D.Float(206, 116, 14, 14));
        });
        item("damage_surveyor", g -> {
            g.setPaint(new GradientPaint(0, 30, new Color(140, 96, 52), 0, 240, new Color(92, 60, 30)));
            g.fill(new RoundRectangle2D.Float(44, 26, 168, 212, 16, 16));
            g.setColor(new Color(236, 232, 218));
            g.fill(new RoundRectangle2D.Float(60, 52, 136, 172, 6, 6));
            g.setColor(new Color(170, 174, 178));
            g.fill(new RoundRectangle2D.Float(92, 16, 72, 40, 10, 10));
            g.setColor(new Color(40, 42, 46));
            for (int i = 0; i < 3; i++) {
                g.fillRect(76, 80 + i * 24, 104 - i * 16, 8);
            }
            int[] ef = {0x9AD08A, 0xF2D650, 0xF29A3A, 0xE0463A, 0xB02AAE, 0x6A1FA6};
            for (int i = 0; i < ef.length; i++) {
                g.setColor(new Color(ef[i]));
                g.fillRect(76 + i * 18, 176, 16, 26);
            }
        });
        item("handheld_anemometer", g -> {
            g.setPaint(new GradientPaint(100, 0, new Color(64, 68, 72), 160, 0, new Color(30, 32, 36)));
            g.fill(new RoundRectangle2D.Float(102, 108, 52, 134, 12, 12));
            g.setColor(new Color(10, 26, 16));
            g.fill(new RoundRectangle2D.Float(110, 122, 36, 28, 4, 4));
            g.setColor(new Color(116, 226, 142));
            g.fillRect(116, 130, 22, 5);
            g.fillRect(116, 139, 14, 4);
            g.setColor(new Color(150, 154, 160));
            g.fillRect(122, 62, 12, 48);
            g.setStroke(new BasicStroke(7F));
            for (int i = 0; i < 3; i++) {
                double a = -Math.PI / 2 + i * Math.PI * 2 / 3;
                float x = 128 + (float) Math.cos(a) * 56;
                float y = 62 + (float) Math.sin(a) * 36;
                g.setColor(new Color(140, 144, 150));
                g.draw(new Line2D.Float(128, 62, x, y));
                g.setColor(new Color(232, 234, 236));
                g.fill(new Ellipse2D.Float(x - 19, y - 15, 38, 30));
                g.setColor(new Color(70, 74, 78));
                g.fill(new Ellipse2D.Float(x - 13, y - 9, 26, 18));
            }
        });
        item("handheld_barometer", g -> {
            g.setPaint(new GradientPaint(30, 30, new Color(220, 182, 100), 226, 226, new Color(122, 88, 34)));
            g.fill(new Ellipse2D.Float(26, 26, 204, 204));
            g.setColor(new Color(240, 236, 222));
            g.fill(new Ellipse2D.Float(48, 48, 160, 160));
            g.setColor(new Color(40, 36, 30));
            for (int i = 0; i < 24; i++) {
                double a = Math.PI * 2 * i / 24;
                float r0 = i % 2 == 0 ? 62 : 70;
                g.setStroke(new BasicStroke(i % 2 == 0 ? 3.4F : 2F));
                g.draw(new Line2D.Float(128 + (float) Math.cos(a) * r0, 128 + (float) Math.sin(a) * r0, 128 + (float) Math.cos(a) * 76, 128 + (float) Math.sin(a) * 76));
            }
            g.setColor(new Color(170, 30, 26));
            g.setStroke(new BasicStroke(5F));
            g.draw(new Line2D.Float(128, 128, 90, 176));
            g.setColor(new Color(20, 20, 22));
            g.draw(new Line2D.Float(128, 128, 170, 86));
            g.fill(new Ellipse2D.Float(116, 116, 24, 24));
            g.setColor(new Color(255, 255, 255, 56));
            g.fill(new Ellipse2D.Float(70, 58, 90, 46));
        });
        item("handheld_thermometer", g -> {
            g.setPaint(new GradientPaint(0, 30, new Color(232, 228, 214), 0, 200, new Color(190, 186, 170)));
            g.fill(new RoundRectangle2D.Float(84, 14, 88, 190, 18, 18));
            g.setColor(new Color(216, 226, 232));
            g.fill(new RoundRectangle2D.Float(116, 24, 24, 170, 12, 12));
            g.setColor(new Color(206, 40, 32));
            g.fill(new RoundRectangle2D.Float(120, 90, 16, 110, 8, 8));
            g.fill(new Ellipse2D.Float(100, 176, 56, 56));
            g.setColor(new Color(255, 255, 255, 150));
            g.fill(new Ellipse2D.Float(112, 186, 14, 12));
            g.setColor(new Color(40, 40, 44));
            for (int i = 0; i < 10; i++) {
                g.fillRect(146, 30 + i * 15, i % 2 == 0 ? 18 : 10, 3);
            }
        });
        item("probe_sensor_module", g -> {
            g.setPaint(new GradientPaint(0, 90, new Color(40, 104, 66), 0, 200, new Color(22, 66, 40)));
            g.fill(new RoundRectangle2D.Float(32, 88, 192, 112, 8, 8));
            g.setColor(new Color(20, 22, 24));
            g.fill(new RoundRectangle2D.Float(62, 112, 74, 58, 4, 4));
            g.setColor(new Color(220, 190, 110));
            for (int i = 0; i < 6; i++) {
                g.fillRect(44 + i * 30, 200, 12, 30);
            }
            g.setColor(new Color(170, 176, 180));
            g.fill(new RoundRectangle2D.Float(150, 112, 56, 28, 4, 4));
            g.setColor(new Color(210, 224, 232));
            g.fill(new RoundRectangle2D.Float(96, 30, 64, 62, 12, 12));
            g.setColor(new Color(150, 110, 60));
            g.setStroke(new BasicStroke(5F));
            g.draw(new RoundRectangle2D.Float(96, 30, 64, 62, 12, 12));
        });
        item("reinforced_probe_housing", g -> {
            g.setPaint(new GradientPaint(30, 30, new Color(120, 124, 130), 226, 226, new Color(58, 60, 66)));
            g.fill(new RoundRectangle2D.Float(28, 40, 200, 188, 16, 16));
            g.setColor(new Color(26, 28, 30));
            g.fill(new RoundRectangle2D.Float(52, 64, 152, 140, 8, 8));
            g.setColor(new Color(176, 128, 66));
            g.setStroke(new BasicStroke(16F));
            g.draw(new Line2D.Float(62, 190, 194, 78));
            g.setColor(new Color(200, 204, 208));
            g.fill(new RoundRectangle2D.Float(102, 16, 52, 30, 6, 6));
            g.setColor(new Color(40, 42, 46));
            for (float[] c : new float[][]{{42, 54}, {214, 54}, {42, 214}, {214, 214}}) {
                g.fill(new Ellipse2D.Float(c[0] - 7, c[1] - 7, 14, 14));
            }
        });
        item("spotter_flare", g -> {
            g.setPaint(new GradientPaint(96, 0, new Color(232, 228, 214), 160, 0, new Color(176, 172, 158)));
            g.fill(new RoundRectangle2D.Float(102, 92, 52, 150, 8, 8));
            g.setColor(new Color(196, 44, 36));
            for (int i = 0; i < 3; i++) {
                g.fillRect(102, 120 + i * 40, 52, 16);
            }
            g.setColor(new Color(60, 62, 66));
            g.fillRect(96, 84, 64, 14);
            g.setColor(new Color(255, 170, 40));
            Path2D flame = new Path2D.Float();
            flame.moveTo(128, 8);
            flame.curveTo(160, 40, 170, 60, 150, 86);
            flame.lineTo(106, 86);
            flame.curveTo(86, 60, 100, 40, 128, 8);
            g.fill(flame);
            g.setColor(new Color(255, 236, 170));
            Path2D core = new Path2D.Float();
            core.moveTo(128, 38);
            core.curveTo(144, 56, 150, 66, 140, 84);
            core.lineTo(116, 84);
            core.curveTo(106, 66, 112, 56, 128, 38);
            g.fill(core);
        });
        item("weather_balloon", g -> {
            g.setPaint(new GradientPaint(70, 20, new Color(244, 240, 234), 190, 170, new Color(176, 160, 150)));
            g.fill(new Ellipse2D.Float(52, 10, 152, 164));
            g.setColor(new Color(255, 255, 255, 170));
            g.fill(new Ellipse2D.Float(84, 32, 44, 32));
            g.setColor(new Color(150, 130, 120));
            Path2D neck = new Path2D.Float();
            neck.moveTo(112, 166);
            neck.lineTo(144, 166);
            neck.lineTo(134, 192);
            neck.lineTo(122, 192);
            neck.closePath();
            g.fill(neck);
            g.setColor(new Color(70, 72, 76));
            g.setStroke(new BasicStroke(2.4F));
            g.draw(new Line2D.Float(128, 192, 128, 216));
            g.setColor(new Color(214, 216, 210));
            g.fill(new RoundRectangle2D.Float(104, 214, 48, 34, 5, 5));
            g.setColor(new Color(214, 54, 44));
            g.fillRect(104, 224, 48, 6);
        });
    }

    // ------------------------------------------------------------------------------------------------
    // The meteorologist, who had no clothes at all: a missing profession texture draws as the magenta checker.
    // ------------------------------------------------------------------------------------------------

    private static void villagers() throws Exception {
        write("entity/villager/profession/meteorologist.png", meteorologist(false));
        write("entity/zombie_villager/profession/meteorologist.png", meteorologist(true));
    }

    /** A hi-vis storm jacket and a ball cap, laid out on the vanilla villager skin. */
    private static BufferedImage meteorologist(boolean zombie) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Noise n = new Noise(zombie ? 303 : 301, 256);
        Color jacket = zombie ? new Color(52, 70, 92) : new Color(36, 62, 110);
        Color jacketDark = zombie ? new Color(38, 52, 70) : new Color(24, 44, 82);
        Color band = zombie ? new Color(170, 170, 90) : new Color(216, 224, 70);
        // Robe, the villager's outer body layer.
        region(image, n, 0, 38, 28, 26, jacket, jacketDark, band, 16);
        // Body.
        region(image, n, 16, 20, 28, 18, jacket, jacketDark, band, 12);
        // Arms, in both of the layouts the crossed-arms model reads.
        region(image, n, 40, 38, 24, 10, jacket, jacketDark, null, 0);
        region(image, n, 44, 22, 20, 16, jacket, jacketDark, band, 9);
        // Cap: band round the head, and the brim on the hat layer.
        for (int y = 8; y < 12; y++) {
            for (int x = 32; x < 64; x++) {
                image.setRGB(x, y, jacket.getRGB());
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 40; x < 56; x++) {
                image.setRGB(x, y, jacketDark.getRGB());
            }
        }
        return image;
    }

    private static void region(BufferedImage image, Noise n, int x0, int y0, int w, int h, Color a, Color b, Color band, int bandY) {
        for (int y = y0; y < y0 + h && y < 64; y++) {
            for (int x = x0; x < x0 + w && x < 64; x++) {
                float t = n.sample(x / 64F, y / 64F, 16);
                Color c = t > 0.55F ? b : a;
                if (band != null && (y == y0 + bandY || y == y0 + bandY + 1)) {
                    c = band;
                }
                image.setRGB(x, y, c.getRGB());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The weather's own puffs.
    // ------------------------------------------------------------------------------------------------

    /**
     * The sprites a storm is made of.
     *
     * <p>A puff has to fill its square. The old art was a dense blob in the middle of a mostly empty sprite, which
     * meant a thirty-block particle showed as a ten-block lump, so a funnel had to be built from hard knots rather
     * than from soft overlapping masses. These reach nearly to the edge of the sprite, dissolve into it in a fine
     * grain rather than a smooth falloff, and carry enough internal structure that a large one still has detail. The
     * tornado's own sheet is drawn in long vertical fibres, because a funnel is air being pulled up and round.
     */
    private static void weatherPuffs() throws Exception {
        String sheets = "../../coroutil/textures/particles/";
        write(sheets + "modern/funnel.png", puff(501L, 256, 0.56F, 1.0F, 0.66F, 0.96F, false));
        write(sheets + "cloud256combined.png", puff(503L, 256, 0.56F, 1.0F, 0.66F, 0.94F, false));
        write(sheets + "modern/cloud256.png", puff(505L, 256, 0.30F, 0.0F, 0.55F, 1.00F, true));
        write(sheets + "cloud256deck.png", puff(507L, 256, 0.30F, 0.0F, 0.76F, 0.96F, true));
    }

    /**
     * @param edge where the sprite starts to dissolve, as a share of the way out to its edge
     * @param fibre 0 for round cauliflower, 1 for long vertical fibres
     * @param bright the grey of its lit parts
     * @param body how opaque its middle is
     * @param round a circular outline, for what is seen alone against the sky. A funnel's puffs are always in a crowd and
     *              are better off filling the square.
     */
    private static BufferedImage puff(long seed, int size, float edge, float fibre, float bright, float body, boolean round) {
        Noise warp = new Noise(seed, 512);
        Noise billow = new Noise(seed + 1, 512);
        Noise fine = new Noise(seed + 2, 512);
        Noise grain = new Noise(seed + 3, 512);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float dx = Math.abs(u - 0.5F) * 2.0F;
                float dy = Math.abs(v - 0.5F) * 2.0F;
                // A rounded square rather than a circle, pushed about by noise so it has no outline of its own to recognise.
                float shape = round ? 2.0F : 3.6F;
                float r = (float) Math.pow(Math.pow(dx, shape) + Math.pow(dy, shape), 1.0F / shape);
                r += (warp.fbm(u, v, round ? 3 : 5, 3) - 0.5F) * (round ? 0.30F : 0.20F);
                float mask = 1.0F - smooth(edge, 1.02F, r);

                // Many small soft lobes at several sizes, added together. Single large lobes read as blotches, and on a
                // sprite this size a blotch with two others beside it reads as a face.
                float wu = u + (warp.fbm(u + 0.37F, v, 6, 2) - 0.5F) * 0.05F;
                float wv = v + (warp.fbm(u, v + 0.61F, 6, 2) - 0.5F) * 0.05F;
                float lobes = billow.fbm(wu, wv, 9, 5);
                float strand = fine.fbm(wu, wv * 0.22F, 30, 3);
                float structure = mix(lobes, mix(lobes, strand, 0.55F), fibre);
                float detail = fine.fbm(u, v, 40, 3);

                float tone = bright * (0.90F + (structure - 0.5F) * 0.42F) + (detail - 0.5F) * 0.07F;
                // Lit from above, a little: enough that a puff has a top and an underside, and not so much that it
                // reads as lit from one direction once it is turned round a funnel.
                tone *= round ? 1.03F - 0.10F * v : 1.0F;
                tone = Math.max(0.08F, Math.min(1.0F, tone));

                // Close to even through the middle. All of its lobes would otherwise show as a ring or a star on a puff that
                // happens to be alone against the sky, and every puff is the same sprite.
                float alpha = mask * body * (0.84F + (structure - 0.5F) * 0.20F);
                // Dissolves in a grain at the edge rather than a clean ramp: it is what keeps a large puff looking like
                // vapour instead of a blurred disc.
                float speckle = grain.sample(u, v, 128) * 0.6F + grain.sample(u, v, 64) * 0.4F;
                alpha *= smooth(0.0F, 0.34F, mask + (speckle - 0.5F) * 0.22F);
                image.setRGB(x, y, rgba(tone, tone, tone * 1.01F, Math.max(0.0F, Math.min(1.0F, alpha))));
            }
        }
        return image;
    }

    // ------------------------------------------------------------------------------------------------
    // What a tornado throws.
    // ------------------------------------------------------------------------------------------------

    /**
     * A splintered plank, a handful of soil clods, and a torn roof shingle, each filling most of its sprite. They are
     * written to both of the paths they are read from: {@code particles/modern} is what is drawn by default, and
     * {@code particles} is the path Weather2's own debris is read from when its original art is asked for.
     */
    private static void debrisSheets() throws Exception {
        String sheets = "../../coroutil/textures/particles/";
        BufferedImage[] art = {debrisPlank(601L), debrisClods(603L), debrisShingle(605L)};
        for (int i = 0; i < art.length; i++) {
            write(sheets + "debris_" + (i + 1) + ".png", art[i]);
            write(sheets + "modern/debris_" + (i + 1) + ".png", art[i]);
        }
    }

    private static java.awt.geom.Path2D.Float outline(float[][] points) {
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        path.moveTo(points[0][0], points[0][1]);
        for (int i = 1; i < points.length; i++) {
            path.lineTo(points[i][0], points[i][1]);
        }
        path.closePath();
        return path;
    }

    /** Coverage of the outlines, antialiased, in 0 to 1. */
    private static float[] coverage(int size, java.awt.Shape... shapes) {
        BufferedImage mask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = mask.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        for (java.awt.Shape shape : shapes) {
            g.fill(shape);
        }
        g.dispose();
        float[] out = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out[y * size + x] = (mask.getRGB(x, y) >>> 24) / 255.0F;
            }
        }
        return out;
    }

    /** A lit edge along the top left of a shape and a shaded one along the bottom right, which is what gives a flat sprite thickness. */
    private static float edgeLight(float[] mask, int size, int x, int y) {
        float above = x > 0 && y > 0 ? mask[(y - 1) * size + x - 1] : 0.0F;
        float below = x < size - 1 && y < size - 1 ? mask[(y + 1) * size + x + 1] : 0.0F;
        return 1.0F + (1.0F - above) * 0.28F - (1.0F - below) * 0.42F;
    }

    /** A plank snapped at both ends, lying along the sprite's diagonal, with the grain running down its length. */
    private static BufferedImage debrisPlank(long seed) {
        int size = 64;
        Noise grain = new Noise(seed, 512);
        float[][] strip = {{-27, -7}, {-21, -8}, {-23, -4}, {-16, -7.5F}, {12, -7}, {18, -9}, {16, -5}, {27, -6}, {22, -2}, {28, 1}, {21, 2}, {24, 7},
                {13, 5}, {9, 8}, {-14, 7}, {-20, 9}, {-18, 5}, {-28, 6}, {-24, 1}, {-29, -2}};
        float angle = -0.72F;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[][] placed = new float[strip.length][2];
        for (int i = 0; i < strip.length; i++) {
            placed[i][0] = 32.0F + strip[i][0] * cos - strip[i][1] * sin;
            placed[i][1] = 32.0F + strip[i][0] * sin + strip[i][1] * cos;
        }
        float[] mask = coverage(size, outline(placed));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float m = mask[y * size + x];
                if (m <= 0.0F) {
                    continue;
                }
                float dx = x - 32.0F;
                float dy = y - 32.0F;
                float along = dx * cos + dy * sin;
                float across = -dx * sin + dy * cos;
                float rings = 0.5F + 0.5F * (float) Math.sin(across * 1.55F + grain.fbm(along / 64.0F + 0.5F, across / 64.0F + 0.5F, 3, 2) * 7.0F);
                float tone = (0.62F + rings * 0.30F) * edgeLight(mask, size, x, y);
                image.setRGB(x, y, rgba(tone * 0.98F, tone * 0.80F, tone * 0.56F, m));
            }
        }
        return image;
    }

    /** A few clods of soil with stones in them, the biggest the size of the sprite's middle. */
    private static BufferedImage debrisClods(long seed) {
        int size = 64;
        Noise grain = new Noise(seed, 512);
        float[] mask = coverage(size,
                outline(new float[][]{{13, 25}, {22, 13}, {36, 10}, {47, 17}, {50, 30}, {41, 41}, {27, 44}, {15, 38}}),
                outline(new float[][]{{44, 40}, {53, 37}, {60, 44}, {56, 54}, {46, 54}, {41, 47}}),
                outline(new float[][]{{8, 44}, {15, 42}, {21, 48}, {18, 57}, {9, 58}, {4, 51}}));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float m = mask[y * size + x];
                if (m <= 0.0F) {
                    continue;
                }
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float lumps = grain.fbm(u, v, 8, 3);
                float crumbs = grain.sample(u + 0.31F, v, 32);
                float stone = smooth(0.72F, 0.80F, grain.sample(u, v + 0.17F, 14));
                float tone = (0.46F + lumps * 0.44F + (crumbs - 0.5F) * 0.20F) * edgeLight(mask, size, x, y);
                image.setRGB(x, y, rgba(mix(tone * 0.86F, tone * 1.10F, stone), mix(tone * 0.68F, tone * 1.10F, stone), mix(tone * 0.50F, tone * 1.12F, stone), m));
            }
        }
        return image;
    }

    /** A roof shingle torn off along one edge, with its rows of grit and a crease across it where it folded. */
    private static BufferedImage debrisShingle(long seed) {
        int size = 64;
        Noise grain = new Noise(seed, 512);
        float[] mask = coverage(size, outline(new float[][]{{7, 18}, {46, 7}, {58, 38}, {50, 42}, {47, 37}, {41, 46}, {36, 42}, {30, 52}, {24, 47}, {17, 55}}));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float m = mask[y * size + x];
                if (m <= 0.0F) {
                    continue;
                }
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float row = 0.5F + 0.5F * (float) Math.sin((y - x * 0.28F) * 0.62F);
                float crease = 1.0F - 0.30F * (1.0F - smooth(0.0F, 2.2F, Math.abs(x * 0.42F + y - 44.0F)));
                float tone = (0.58F + row * 0.14F + (grain.sample(u, v, 48) - 0.5F) * 0.34F) * crease * edgeLight(mask, size, x, y);
                image.setRGB(x, y, rgba(tone * 0.92F, tone * 0.90F, tone * 0.88F, m));
            }
        }
        return image;
    }

    private TextureGen() {
    }
}
