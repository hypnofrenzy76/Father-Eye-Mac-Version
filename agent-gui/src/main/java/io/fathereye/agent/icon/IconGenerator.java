package io.fathereye.agent.icon;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Draws the vintage rainbow Apple logo (1977–1998) as a square PNG at
 * any size. The Apple silhouette is approximated with bezier curves
 * (close enough at icon sizes) and filled with six horizontal rainbow
 * bands clipped to the silhouette.
 *
 * <p>Run from the command line via {@link #main}:
 *
 * <pre>
 *   java -cp ... io.fathereye.agent.icon.IconGenerator out/icon.iconset
 * </pre>
 *
 * <p>Emits {@code icon_16x16.png}, {@code icon_16x16@2x.png}, …
 * {@code icon_512x512@2x.png} into the target dir, in the layout
 * macOS's {@code iconutil -c icns out/icon.iconset} expects.
 *
 * <p><b>Trademark note:</b> the rainbow Apple logo is owned by Apple Inc.
 * Using it for personal builds is common; bundling it in software you
 * distribute publicly is a different matter. Swap in your own icon by
 * replacing {@code agent-gui/icons/icon.icns} (or {@code icon.png} on
 * Linux) before building if you plan to ship.
 */
public final class IconGenerator {

    // 1977 rainbow stripe order, top to bottom.
    private static final Color[] STRIPES = {
            new Color(0x60BB46), // green
            new Color(0xFCB827), // yellow
            new Color(0xF6821F), // orange
            new Color(0xE03A3E), // red
            new Color(0x963D97), // purple
            new Color(0x009DDC)  // blue
    };

    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, size, size);
            g.setComposite(AlphaComposite.SrcOver);

            // Work in a normalized coord system (0..1000), then scale.
            double s = size / 1000.0;
            g.scale(s, s);

            Area apple = appleSilhouette();

            // Stripes
            g.setClip(apple);
            double minY = apple.getBounds2D().getMinY();
            double maxY = apple.getBounds2D().getMaxY();
            double bandH = (maxY - minY) / STRIPES.length;
            for (int i = 0; i < STRIPES.length; i++) {
                g.setColor(STRIPES[i]);
                Rectangle2D band = new Rectangle2D.Double(0, minY + i * bandH, 1000, bandH + 1);
                g.fill(band);
            }
            g.setClip(null);

        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Stylized Apple silhouette in a 1000x1000 normalized box. The shape
     * has the four key visual elements of the 1977 Apple logo:
     *
     * <ol>
     *   <li>An apple-pear body, slightly taller than wide, with the
     *       characteristic two-hump top with a deep notch in the middle
     *       (where the stem sits in the original).</li>
     *   <li>A deep circular bite cut from the right side, with its center
     *       roughly at the right shoulder of the body.</li>
     *   <li>A leaf canted right, attached to the top notch.</li>
     *   <li>Overall ~7:8 width-to-height aspect ratio, centered in the
     *       1000x1000 box with a margin so it doesn't crash into the
     *       icon edges.</li>
     * </ol>
     *
     * <p>This is hand-drawn with cubic beziers — it's not a trace of the
     * actual Apple trademark. It reads as "rainbow Apple" at icon sizes,
     * which is what we need.
     */
    private static Area appleSilhouette() {
        Path2D body = new Path2D.Double();
        // Start at the deep V notch at the top center.
        body.moveTo(500, 270);
        // Right hump rises from the notch, then dives right to the
        // shoulder.
        body.curveTo(530, 220, 590, 200, 660, 215);
        body.curveTo(780, 235, 880, 350, 905, 480);
        // Right side curves down past the bite to the bottom-right.
        body.curveTo(925, 620, 880, 770, 790, 870);
        body.curveTo(720, 935, 620, 940, 560, 905);
        // Center bottom — slight inward curve (the apple's "waist").
        body.curveTo(525, 890, 500, 890, 500, 890);
        body.curveTo(500, 890, 475, 890, 440, 905);
        // Left side mirrors the right.
        body.curveTo(380, 940, 280, 935, 210, 870);
        body.curveTo(120, 770, 75, 620, 95, 480);
        body.curveTo(120, 350, 220, 235, 340, 215);
        body.curveTo(410, 200, 470, 220, 500, 270);
        body.closePath();

        Area shape = new Area(body);

        // Bite: circle subtracted from the upper-right shoulder. The
        // position is what makes the shape unmistakably "apple" — it has
        // to crash into the body, not just nibble at the edge.
        Ellipse2D bite = new Ellipse2D.Double(770, 380, 280, 280);
        shape.subtract(new Area(bite));

        // Leaf: tilted teardrop pointing upper-right from the notch.
        Path2D leaf = new Path2D.Double();
        leaf.moveTo(510, 250);
        leaf.curveTo(530, 120, 640, 60, 760, 90);
        leaf.curveTo(740, 220, 630, 280, 510, 260);
        leaf.closePath();
        shape.add(new Area(leaf));

        return shape;
    }

    /** Produce all macOS iconset PNGs into {@code targetDir}. */
    public static void writeIconset(Path targetDir) throws IOException {
        File dir = targetDir.toFile();
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("could not create " + dir);
        // macOS iconset expects this exact set of file names.
        int[][] specs = {
                {16, 1}, {16, 2},
                {32, 1}, {32, 2},
                {128, 1}, {128, 2},
                {256, 1}, {256, 2},
                {512, 1}, {512, 2}
        };
        for (int[] spec : specs) {
            int base = spec[0];
            int scale = spec[1];
            int px = base * scale;
            BufferedImage img = render(px);
            String name = scale == 1
                    ? String.format("icon_%dx%d.png", base, base)
                    : String.format("icon_%dx%d@2x.png", base, base);
            File out = new File(dir, name);
            ImageIO.write(img, "PNG", out);
        }
    }

    /** Single 1024x1024 PNG (used for Linux jpackage --icon). */
    public static void writePng(Path target, int px) throws IOException {
        File f = target.toFile();
        if (f.getParentFile() != null && !f.getParentFile().exists() && !f.getParentFile().mkdirs())
            throw new IOException("could not create parent of " + f);
        ImageIO.write(render(px), "PNG", f);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: IconGenerator <iconset-dir> [single-png-path]");
            System.exit(2);
        }
        Path iconsetDir = Paths.get(args[0]);
        writeIconset(iconsetDir);
        System.out.println("wrote iconset -> " + iconsetDir);
        if (args.length >= 2) {
            Path png = Paths.get(args[1]);
            writePng(png, 512);
            System.out.println("wrote png -> " + png);
        }
    }
}
