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
     * Approximate Apple silhouette. Two overlapping rounded blobs for the
     * body, a circular bite from the right, a leaf at the top. All
     * coordinates are in a 1000x1000 normalized space.
     */
    private static Area appleSilhouette() {
        // Body: two overlapping circles to get the slightly heart-topped
        // outline. The "indent" between the two top humps is implicit
        // from where they overlap.
        Ellipse2D leftBody = new Ellipse2D.Double(150, 240, 480, 660);
        Ellipse2D rightBody = new Ellipse2D.Double(370, 240, 480, 660);
        Area body = new Area(leftBody);
        body.add(new Area(rightBody));

        // Smooth the bottom into a single arc (round the lower edges).
        Ellipse2D bottom = new Ellipse2D.Double(150, 480, 700, 480);
        body.add(new Area(bottom));

        // Bite: circle subtracted from right side.
        Ellipse2D bite = new Ellipse2D.Double(720, 360, 280, 280);
        body.subtract(new Area(bite));

        // Leaf at top: tilted ellipse.
        Path2D.Double leaf = new Path2D.Double();
        leaf.moveTo(540, 230);
        leaf.curveTo(540, 90, 660, 50, 750, 90);
        leaf.curveTo(720, 200, 620, 270, 540, 230);
        leaf.closePath();
        body.add(new Area(leaf));

        return body;
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
