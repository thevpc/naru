package net.thevpc.naru.desktop.spike;

import net.thevpc.common.swing.dock.JDockAnchor;
import net.thevpc.common.swing.dock.JDockPane;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spike 4 -- does {@code JDockPane} hold up, or do we ship {@code JTabbedPane} plus splits?
 * <p>
 * The library's docking has no usage in kifkif, ndb or dbclient, so nothing in the ecosystem
 * has ever stressed it. This exercises the operations a cockpit actually needs -- add to each
 * anchor, re-anchor a panel, remove, resize dividers, and read the layout back so it can be
 * persisted -- and times each. Drag-by-mouse is not automated here; if everything below passes,
 * that is the one thing left to check by hand before committing to it.
 */
public class DockSpike {

    public static void main(String[] args) throws Exception {
        JFrame frame = new JFrame("naru desktop spike — docking");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1400, 900);

        JDockPane dock = new JDockPane();
        AtomicLong addNanos = new AtomicLong();
        AtomicLong relayoutNanos = new AtomicLong();

        SwingUtilities.invokeAndWait(() -> {
            long t0 = System.nanoTime();
            dock.add("transcript", panel("transcript"), "Transcript", null, false, JDockAnchor.CENTER);
            dock.add("tasks", panel("tasks"), "Tasks", null, true, JDockAnchor.RIGHT);
            dock.add("sessions", panel("sessions"), "Sessions", null, true, JDockAnchor.LEFT);
            dock.add("tools", panel("tools"), "Tool calls", null, true, JDockAnchor.BOTTOM);
            addNanos.set(System.nanoTime() - t0);
            frame.getContentPane().add(dock, BorderLayout.CENTER);
            frame.setVisible(true);
            frame.validate();
            t0 = System.nanoTime();
            dock.revalidate();
            dock.repaint();
            relayoutNanos.set(System.nanoTime() - t0);
        });

        long t0 = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> {
            // re-anchor: move the tool panel from bottom to right, the way a user would drop it
            dock.setWindowAnchor("tools", JDockAnchor.RIGHT);
            dock.setWindowAnchor("tasks", JDockAnchor.BOTTOM);
        });
        long reanchorMs = (System.nanoTime() - t0) / 1_000_000L;

        t0 = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 3; i++) {
                dock.setHorizontalDividerLocation(i, 400 + i * 40);
            }
            dock.setVerticalDividerLocation(0, 300);
        });
        long resizeMs = (System.nanoTime() - t0) / 1_000_000L;

        int[] readBack = new int[4];
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 4; i++) {
                readBack[i] = dock.getHorizontalDividerLocation(i);
            }
        });

        t0 = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> dock.remove("tools"));
        long removeMs = (System.nanoTime() - t0) / 1_000_000L;

        t0 = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> dock.add("tools", panel("tools"), "Tool calls", null, true,
                JDockAnchor.BOTTOM));
        long reAddMs = (System.nanoTime() - t0) / 1_000_000L;

        // add/remove churn, which is what a session switch does to a docked panel
        t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            final int n = i;
            SwingUtilities.invokeAndWait(() -> {
                dock.add("temp" + n, panel("temp"), "Temp " + n, null, true, JDockAnchor.RIGHT);
                dock.remove("temp" + n);
            });
        }
        long churnMs = (System.nanoTime() - t0) / 1_000_000L;

        final String[] anchors = new String[1];
        SwingUtilities.invokeAndWait(() -> anchors[0] = "tools=" + dock.lookupAnchor("tools")
                + " tasks=" + dock.lookupAnchor("tasks")
                + " transcript=" + dock.lookupAnchor("transcript"));

        System.out.println("SPIKE4 docking JDockPane");
        System.out.println("  add-4-panels-ms=" + addNanos.get() / 1_000_000);
        System.out.println("  relayout-ms=" + relayoutNanos.get() / 1_000_000);
        System.out.println("  reanchor-pair-ms=" + reanchorMs);
        System.out.println("  divider-set-ms=" + resizeMs
                + " readback=" + readBack[0] + "," + readBack[1] + "," + readBack[2] + "," + readBack[3]);
        System.out.println("  remove-ms=" + removeMs + " re-add-ms=" + reAddMs);
        System.out.println("  churn-50-add-remove-ms=" + churnMs);
        System.out.println("  anchors-after-move " + anchors[0]);
        System.out.println("  NOTE no layout serialisation API on JDockPane: anchors+dividers must be");
        System.out.println("       persisted by the app (lookupAnchor per id, get/set*DividerLocation).");
        System.out.println("  VERDICT=" + (addNanos.get() / 1_000_000 < 2000 && churnMs < 5000
                ? "structurally usable — confirm mouse drag by hand before Phase 2 commits to it"
                : "REJECT — fall back to JTabbedPane + JSplitPane as planned"));

        Thread.sleep(args.length > 0 && "show".equals(args[0]) ? 20_000 : 200);
        frame.dispose();
        System.exit(0);
    }

    private static JPanel panel(String name) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(name));
        p.add(new JScrollPane(new JLabel("  " + name + " placeholder  ", JLabel.CENTER)),
                BorderLayout.CENTER);
        p.setPreferredSize(new java.awt.Dimension(240, 160));
        return p;
    }
}
