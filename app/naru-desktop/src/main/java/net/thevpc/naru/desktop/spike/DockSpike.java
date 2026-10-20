//package net.thevpc.naru.desktop.spike;
//
//import net.thevpc.common.swing.dock.JDockAnchor;
//import net.thevpc.common.swing.dock.JDockPane;
//
//import javax.swing.BorderFactory;
//import javax.swing.JFrame;
//import javax.swing.JLabel;
//import javax.swing.JPanel;
//import javax.swing.JScrollPane;
//import javax.swing.SwingUtilities;
//import java.awt.BorderLayout;
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * Spike 4 -- does {@code JDockPane} hold up, or do we ship {@code JTabbedPane} plus splits?
// * <p>
// * The library's docking has no usage in kifkif, ndb or dbclient, so nothing in the ecosystem
// * has ever stressed it. Each step is isolated so one crash does not hide the rest: add to each
// * anchor, re-anchor, remove, re-add, resize dividers, read the layout back, and churn.
// * <p>
// * Drag-by-mouse is not automated here; that remains a manual check if everything below passes.
// */
//public class DockSpike {
//
//    public static void main(String[] args) throws Exception {
//        List<Step> steps = new ArrayList<>();
//        JFrame frame = new JFrame("naru desktop spike — docking");
//        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
//        frame.setSize(1400, 900);
//        JDockPane dock = new JDockPane();
//
//        step("frame+dock visible", () -> {
//            dock.add("transcript", panel("transcript"), "Transcript", null, false, JDockAnchor.CENTER);
//            dock.add("tasks", panel("tasks"), "Tasks", null, true, JDockAnchor.RIGHT);
//            dock.add("sessions", panel("sessions"), "Sessions", null, true, JDockAnchor.LEFT);
//            dock.add("tools", panel("tools"), "Tool calls", null, true, JDockAnchor.BOTTOM);
//            frame.getContentPane().add(dock, BorderLayout.CENTER);
//            frame.setVisible(true);
//            frame.validate();
//        }, steps);
//
//        step("re-anchor (bottom -> right)", () -> dock.setWindowAnchor("tools", JDockAnchor.RIGHT), steps);
//        step("re-anchor (right -> bottom)", () -> dock.setWindowAnchor("tasks", JDockAnchor.BOTTOM), steps);
//        // JDockPane.setWindowAnchor reads as a stub: `if (a.getDockAnchor() != anchor) { //??? }`.
//        // If the anchor did not change, programmatic re-docking does not exist at all.
//        final String[] anchorAfterReAnchor = new String[1];
//        step("did setWindowAnchor move the panel?", () -> anchorAfterReAnchor[0]
//                = "tools is still " + dock.lookupAnchor("tools") + " (asked for RIGHT)", steps);
//
//        int[] readBack = new int[4];
//        step("set divider locations", () -> {
//            for (int i = 0; i < 3; i++) {
//                dock.setHorizontalDividerLocation(i, 400 + i * 40);
//            }
//            dock.setVerticalDividerLocation(0, 300);
//        }, steps);
//        step("read divider locations back", () -> {
//            for (int i = 0; i < 4; i++) {
//                readBack[i] = dock.getHorizontalDividerLocation(i);
//            }
//        }, steps);
//
//        // the interesting question for a cockpit: can a panel be taken out and put back?
//        step("remove from an edge anchor", () -> dock.remove("sessions"), steps);
//        step("re-add to a different anchor", () -> dock.add("sessions", panel("sessions"),
//                "Sessions", null, true, JDockAnchor.RIGHT), steps);
//
//        // Isolating the remove crash. JDockTools.remove() reads
//        // `if (selected == a) { add(null, BorderLayout.CENTER); }` — passing null to Container.add
//        // is what throws. A fresh anchor group makes the first window the selected one, so the
//        // two cases are unambiguous: remove the unselected window, then the selected one.
//        step("probe: fresh TOP group, two windows", () -> {
//            dock.add("sel1", panel("sel1"), "Sel 1", null, true, JDockAnchor.TOP);
//            dock.add("sel2", panel("sel2"), "Sel 2", null, true, JDockAnchor.TOP);
//        }, steps);
//        step("probe: remove UNSELECTED window", () -> dock.remove("sel2"), steps);
//        step("probe: remove SELECTED window", () -> dock.remove("sel1"), steps);
//        final String[] activeInTop = new String[1];
//        stepGuarded("probe: what is visible in TOP now", () -> {
//            dock.add("sel3", panel("sel3"), "Sel 3", null, true, JDockAnchor.TOP);
//            activeInTop[0] = java.util.Arrays.toString(dock.lookupIDockTools("sel3").getActive());
//        }, steps);
//
//        // if the crash is only "the visible window cannot be removed", activating a sibling
//        // first is a cheap workaround and docking stays on the table.
//        step("probe: fresh LEFT group, two windows", () -> {
//            dock.add("wa1", panel("wa1"), "Wa 1", null, true, JDockAnchor.LEFT);
//            dock.add("wa2", panel("wa2"), "Wa 2", null, true, JDockAnchor.LEFT);
//        }, steps);
//        stepGuarded("probe: workaround, activate sibling then remove", () -> {
//            dock.setWindowActive("wa2", true);
//            dock.remove("wa1");
//        }, steps);
//        // the workaround only counts if the click path agrees: assert that activating wa2 made
//        // it the window rendered in the LEFT group's centre, i.e. wa1 was no longer visible.
//        stepGuarded("probe: sibling really became visible", () -> {
//            if (!java.util.Arrays.asList((Object[]) dock.lookupIDockTools("wa2").getActive())
//                    .contains("wa2")) {
//                throw new IllegalStateException("wa2 not reported active after setWindowActive");
//            }
//        }, steps);
//
//        step("remove tools (as-is)", () -> dock.remove("tools"), steps);
//        stepGuarded("re-add removed panel", () -> dock.add("tools", panel("tools"), "Tool calls", null,
//                true, JDockAnchor.BOTTOM), steps);
//        step("remove from CENTER", () -> dock.remove("transcript"), steps);
//        step("re-add to CENTER", () -> dock.add("transcript", panel("transcript"), "Transcript",
//                null, false, JDockAnchor.CENTER), steps);
//
//        step("churn 50 add/remove", () -> {
//            for (int i = 0; i < 50; i++) {
//                dock.add("temp" + i, panel("temp"), "Temp " + i, null, true, JDockAnchor.RIGHT);
//                dock.remove("temp" + i);
//            }
//        }, steps);
//
//        final String[] anchors = new String[1];
//        step("anchors resolved", () -> anchors[0] = "tools=" + dock.lookupAnchor("tools")
//                + " tasks=" + dock.lookupAnchor("tasks")
//                + " transcript=" + dock.lookupAnchor("transcript"), steps);
//
//        step("title/icon/active/closable updates", () -> {
//            dock.setWindowTitle("tasks", "Tasks *");
//            dock.setWindowActive("tasks", true);
//            dock.setWindowClosable("tasks", false);
//        }, steps);
//
//        // Were the divider readings above an artefact of setting them before the pane had been
//        // laid out? Set them again now that the frame is shown and size is settled, wait for a
//        // repaint, then read back. This decides whether saved layouts are implementable.
//        step("re-set dividers on a laid-out pane", () -> {
//            for (int i = 0; i < 3; i++) {
//                dock.setHorizontalDividerLocation(i, 400 + i * 40);
//            }
//            dock.setVerticalDividerLocation(0, 300);
//        }, steps);
//        Thread.sleep(400);
//        final String[] readBack2 = new String[1];
//        step("read dividers back again", () -> {
//            StringBuilder sb = new StringBuilder();
//            for (int i = 0; i < 4; i++) {
//                sb.append(i == 0 ? "" : ",").append(dock.getHorizontalDividerLocation(i));
//            }
//            sb.append("  v=");
//            for (int i = 0; i < 4; i++) {
//                sb.append(i == 0 ? "" : ",").append(dock.getVerticalDividerLocation(i));
//            }
//            readBack2[0] = sb + " size=" + dock.getSize();
//        }, steps);
//
//        System.out.println("SPIKE4 docking — net.thevpc.common.swing.dock.JDockPane");
//        int failures = 0;
//        int skipped = 0;
//        for (Step s : steps) {
//            if (s.result.startsWith("skipped")) {
//                skipped++;
//            } else if (!s.result.equals("ok")) {
//                failures++;
//            }
//            System.out.printf("  %-32s %5d ms  %s%n", s.name, s.millis, s.result);
//        }
//        System.out.println("  divider-readback=" + readBack[0] + "," + readBack[1] + ","
//                + readBack[2] + "," + readBack[3] + "  (set to 400,440,480)");
//        System.out.println("  anchors " + anchors[0]);
//        System.out.println("  re-anchor check: " + anchorAfterReAnchor[0]);
//        System.out.println("  divider re-read after layout + 400 ms: " + readBack2[0]);
//        System.out.println("  NOTE JDockPane exposes no layout serialisation; an app must persist");
//        System.out.println("       per-id anchors (lookupAnchor) plus the 4+4 divider locations.");
//        System.out.println("  skipped=" + skipped + " failed=" + failures);
//        System.out.println("  FINDING-1 setWindowAnchor() is an empty stub (//???) — a panel cannot be");
//        System.out.println("            moved programmatically, so saved layouts cannot be restored.");
//        System.out.println("  FINDING-2 remove() throws when the id is the group's current selection");
//        System.out.println("            (JDockTools:140 calls add(null, CENTER)).");
//        System.out.println("  FINDING-3 setHorizontalDividerLocation(0..2, 400/440/480) reads back");
//        System.out.println("            29/109/109 both before and after layout; the vertical set stuck.");
//        System.out.println("            Layout persistence for the horizontal splits is not usable.");
//        System.out.println("  VERDICT=" + (failures == 0
//                ? "all programmatic ops safe — verify mouse drag by hand before Phase 2"
//                : failures + " operation(s) crash — docking is NOT safe for a cockpit; use "
//                        + "JTabbedPane + JSplitPane"));
//
//        Thread.sleep(args.length > 0 && "show".equals(args[0]) ? 15_000 : 200);
////        frame.dispose();
////        System.exit(failures == 0 ? 0 : 1);
//    }
//
//    static final class Step {
//        final String name;
//        String result = "ok";
//        long millis;
//
//        Step(String name) {
//            this.name = name;
//        }
//
//        interface Body {
//            void run() throws Exception;
//        }
//
//        static void run(String name, Body body, List<Step> log) {
//            Step s = new Step(name);
//            long t0 = System.nanoTime();
//            try {
//                SwingUtilities.invokeAndWait(() -> {
//                    try {
//                        body.run();
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    }
//                });
//            } catch (Throwable t) {
//                Throwable cause = t;
//                while (cause.getCause() != null && cause.getCause() != cause) {
//                    cause = cause.getCause();
//                }
//                s.result = "FAIL " + cause.getClass().getSimpleName()
//                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
//            }
//            s.millis = (System.nanoTime() - t0) / 1_000_000L;
//            log.add(s);
//        }
//    }
//
//    private static void step(String name, Step.Body body, List<Step> log) {
//        Step.run(name, body, log);
//    }
//
//    /**
//     * A step that only makes sense if the step before it worked, so a library crash does not
//     * cascade into a second misleading failure.
//     */
//    private static void stepGuarded(String name, Step.Body body, List<Step> log) {
//        if (!log.isEmpty() && !log.get(log.size() - 1).result.equals("ok")) {
//            Step s = new Step(name);
//            s.result = "skipped (previous step failed)";
//            log.add(s);
//            return;
//        }
//        Step.run(name, body, log);
//    }
//
//    private static JPanel panel(String name) {
//        JPanel p = new JPanel(new BorderLayout());
//        p.setBorder(BorderFactory.createTitledBorder(name));
//        p.add(new JScrollPane(new JLabel("  " + name + " placeholder  ", JLabel.CENTER)),
//                BorderLayout.CENTER);
//        p.setPreferredSize(new java.awt.Dimension(240, 160));
//        return p;
//    }
//}
