package net.thevpc.naru.desktop.ui;

import java.awt.Color;
import java.util.List;

/**
 * The two menus a group's look is picked from: one glyph and one hue.
 *
 * <p>A group stores the preset <em>id</em> of its glyph and a literal {@code #RRGGBB} for its
 * colour, and that asymmetry is deliberate. A glyph is a shape with no portable meaning, so the
 * row stores which preset was picked and a host that has never seen the table draws its default
 * instead of drawing a random character. A colour is arithmetic, so the row stores the colour:
 * editing {@code #38BDF8} by hand in a project file still paints sky, and a theme switch cannot
 * quietly redefine what "sky" meant when the group was made.
 *
 * <p>Every glyph here is a monochrome codepoint, never an emoji: the hue a group carries has to
 * tint its own icon, and a coloured emoji ignores the text colour the renderer asks for — the
 * same reason {@link TabGlyphs} draws its shapes instead of shipping images.
 */
public final class GroupPresets {

    /** One entry of the icon menu: what is stored, what the picker says, what is painted. */
    public record Glyph(String id, String label, String symbol) {
    }

    /** One entry of the colour menu: a name to pick by and the hex that is stored. */
    public static final class Swatch {
        private final String label;
        private final String hex;

        Swatch(String label, String hex) {
            this.label = label;
            this.hex = hex;
        }

        public String label() {
            return label;
        }

        public String hex() {
            return hex;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Glyph> GLYPHS = List.of(
            new Glyph("folder", "Folder", "⌂"),
            new Glyph("tag", "Tag", "⚑"),
            new Glyph("cube", "Cube", "⬢"),
            new Glyph("spark", "Spark", "✦"),
            new Glyph("diamond", "Diamond", "◆"),
            new Glyph("disc", "Disc", "●"),
            new Glyph("square", "Square", "■"),
            new Glyph("triangle", "Triangle", "▲"),
            new Glyph("star", "Star", "★"),
            new Glyph("target", "Target", "◉"),
            new Glyph("stripes", "Stripes", "☰"),
            new Glyph("gear", "Gear", "⚙"),
            new Glyph("plus", "Plus", "✚"),
            new Glyph("hash", "Hash", "⌗")
    );

    /** Ordered by hue so the picker reads as a wheel, not as a bag of hex digits. */
    private static final List<Swatch> SWATCHES = List.of(
            new Swatch("Sky", "#38BDF8"),
            new Swatch("Indigo", "#6366F1"),
            new Swatch("Violet", "#8B5CF6"),
            new Swatch("Pink", "#EC4899"),
            new Swatch("Red", "#EF4444"),
            new Swatch("Orange", "#F97316"),
            new Swatch("Amber", "#F59E0B"),
            new Swatch("Lime", "#84CC16"),
            new Swatch("Emerald", "#10B981"),
            new Swatch("Teal", "#14B8A6"),
            new Swatch("Slate", "#9CA3AF")
    );

    private GroupPresets() {
    }

    public static List<Glyph> glyphs() {
        return GLYPHS;
    }

    public static List<Swatch> swatches() {
        return SWATCHES;
    }

    /** The symbol to paint for a stored icon id, or null when the row should carry no glyph. */
    public static String symbolOf(String id) {
        if (id == null) {
            return null;
        }
        for (Glyph g : GLYPHS) {
            if (g.id().equals(id)) {
                return g.symbol();
            }
        }
        // an id this build does not know (a hand-edited file, an older preset table) stays
        // silent rather than painting the key as text in the middle of the title
        return null;
    }

    /** The picker's word for a stored icon id, or the id itself when it is unknown. */
    public static String labelOf(String id) {
        for (Glyph g : GLYPHS) {
            if (g.id().equals(id)) {
                return g.label();
            }
        }
        return id == null ? "None" : id;
    }

    /** Parse a stored {@code #RRGGBB}; null for absent or unreadable, so the theme default shows. */
    public static Color colorOf(String hex) {
        if (hex == null) {
            return null;
        }
        String value = hex.trim();
        if (value.length() != 7 || value.charAt(0) != '#') {
            return null;
        }
        for (int i = 1; i < 7; i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return null;
            }
        }
        return new Color(Integer.parseInt(value.substring(1), 16), true);
    }

    /**
     * The solid chip the colour picker shows. An {@code Icon} rather than a button background
     * because the look-and-feel owns a button's fill and would paint over the hue being picked.
     */
    public static final class ColorIcon implements javax.swing.Icon {
        private final Color color;
        private final int width;
        private final int height;

        public ColorIcon(Color color, int width, int height) {
            this.color = color;
            this.width = width;
            this.height = height;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                java.awt.Color border = javax.swing.UIManager.getColor("Component.borderColor");
                g2.setColor(color);
                g2.fillRect(x, y, width, height);
                g2.setColor(border == null ? color.darker() : border);
                g2.drawRect(x, y, width - 1, height - 1);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }

    /** The entry to preselect in the picker for a stored colour, matching case-insensitively. */
    public static Swatch swatchOf(String hex) {
        if (hex == null) {
            return null;
        }
        String upper = hex.trim().toUpperCase(java.util.Locale.ROOT);
        for (Swatch s : SWATCHES) {
            if (s.hex().equals(upper)) {
                return s;
            }
        }
        return null;
    }

    /** The picker's word for a stored colour, or the hex itself when no preset matches. */
    public static String colorLabel(String hex) {
        Swatch s = swatchOf(hex);
        return s != null ? s.label() : (hex == null ? "Theme default" : hex);
    }
}
