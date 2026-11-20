package net.thevpc.naru.desktop.ui;

import javax.swing.Icon;
import javax.swing.JTabbedPane;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * The rail's tab glyphs: four shapes that say what a tab is for before it is opened.
 * <p>
 * Drawn rather than shipped as an icon dependency or as emoji, because a glyph in a tab strip has to
 * take the colour the tab already has — the selected one, in whichever theme is showing — and an
 * image cannot. Each is one weight with no fills, so at 16 px it reads as a shape and not as a smudge.
 * <p>
 * The word beside the glyph stays: it is what the window's by-title tab selection, the gate harness
 * and a screen reader all address the tab by. The glyph is a second way to find a tab, not a
 * replacement for naming it.
 */
public final class TabGlyphs {

    /** The four first-level tabs of the right-hand rail. */
    public enum Kind {
        PLAN,
        PERMISSIONS,
        INSPECT,
        WORKSPACE
    }

    /** One weight, round caps: a hairline at 100 % and still a hairline at 160 %. */
    private static final float STROKE = 1.4f;

    /** The box every shape is drawn in, before it is scaled to the interface size. */
    private static final int DESIGN = 16;

    private TabGlyphs() {
    }

    public static Icon of(Kind kind) {
        return new Glyph(kind);
    }

    /**
     * Give one tab its glyph and a tooltip that says what the tab holds.
     * <p>
     * The title is left exactly as it was on purpose: {@code MainWindow.selectSideTab} and the harness
     * screenshots reach tabs by that string, and a tab whose name changed under them selects nothing.
     */
    public static void decorate(JTabbedPane tabs, int index, Kind kind, String holds) {
        tabs.setIconAt(index, of(kind));
        tabs.setToolTipTextAt(index, tabs.getTitleAt(index) + " — " + holds);
    }

    /** The glyph's box: it grows with the type ladder so the pair reads as one label. */
    static int edge() {
        return edgeFor((float) UiFonts.base().getSize2D());
    }

    /** The rule on its own, so the size a tab strip is given can be checked without a theme loaded. */
    static int edgeFor(float uiPoints) {
        return Math.max(12, Math.round(uiPoints * 1.22f));
    }

    private static final class Glyph implements Icon {

        private final Kind kind;

        Glyph(Kind kind) {
            this.kind = kind;
        }

        @Override
        public int getIconWidth() {
            return edge();
        }

        @Override
        public int getIconHeight() {
            return edge();
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float scale = edge() / (float) DESIGN;
            g.translate(x, y);
            g.scale(scale, scale);
            g.setColor(c.getForeground());
            g.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case PLAN -> paintPlan(g);
                case PERMISSIONS -> paintPermissions(g);
                case INSPECT -> paintInspect(g);
                case WORKSPACE -> paintWorkspace(g);
            }
            g.dispose();
        }

        /** A list with one line checked off: the plan is steps, and one of them is happening. */
        private static void paintPlan(Graphics2D g) {
            g.draw(new Line2D.Float(2, 4, 9, 4));
            g.draw(new Line2D.Float(2, 8, 9, 8));
            g.draw(new Line2D.Float(2, 12, 6.5f, 12));
            Path2D.Float tick = new Path2D.Float();
            tick.moveTo(10, 11.5f);
            tick.lineTo(11.8f, 13.4f);
            tick.lineTo(14.8f, 9.6f);
            g.draw(tick);
        }

        /** A shield: what may and may not be done, rather than a padlock, which says only "locked". */
        private static void paintPermissions(Graphics2D g) {
            Path2D.Float shield = new Path2D.Float();
            shield.moveTo(8, 2.2f);
            shield.curveTo(10, 3.3f, 12, 3.7f, 13.9f, 3.9f);
            shield.lineTo(13.9f, 8.8f);
            shield.curveTo(13.9f, 11.8f, 11.4f, 13.5f, 8, 14.8f);
            shield.curveTo(4.6f, 13.5f, 2.1f, 11.8f, 2.1f, 8.8f);
            shield.lineTo(2.1f, 3.9f);
            shield.curveTo(4, 3.7f, 6, 3.3f, 8, 2.2f);
            shield.closePath();
            g.draw(shield);
        }

        /** A glass: this is where a run is looked at. */
        private static void paintInspect(Graphics2D g) {
            g.draw(new Ellipse2D.Float(3, 3, 7, 7));
            g.draw(new Line2D.Float(9.5f, 9.5f, 13.6f, 13.6f));
        }

        /** An open folder: the workspace is the files the run is working in. */
        private static void paintWorkspace(Graphics2D g) {
            Path2D.Float box = new Path2D.Float();
            box.moveTo(2, 5);
            box.lineTo(6, 5);
            box.lineTo(7.6f, 3);
            box.lineTo(14, 3);
            box.lineTo(14, 13);
            box.lineTo(2, 13);
            box.closePath();
            g.draw(box);
        }
    }
}
