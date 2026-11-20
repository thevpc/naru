//package net.thevpc.naru.desktop.spike;
//
//import net.thevpc.naru.api.agent.NaruLogMode;
//import net.thevpc.naru.desktop.host.TranscriptEvent;
//
//import javax.swing.DefaultListModel;
//import javax.swing.JEditorPane;
//import javax.swing.JLabel;
//import javax.swing.JList;
//import javax.swing.JPanel;
//import javax.swing.ListSelectionModel;
//import javax.swing.SwingUtilities;
//import javax.swing.UIManager;
//import java.awt.BorderLayout;
//import java.awt.Color;
//import java.util.List;
//import java.util.concurrent.atomic.AtomicLong;
//
///**
// * A transcript built as one component per block, in a virtualized list.
// * <p>
// * The spike question is not "is it pretty" but "which renderer survives load": a {@code JList}
// * only lays out the cells it shows, so a 2,000-block transcript costs the same as a
// * 20-block one -- <em>if</em> the cell renderer is cheap. Two renderer modes are built in so
// * the same run can measure both.
// */
//public class TranscriptPanel extends JPanel {
//
//    /** One renderable block. */
//    public static final class Row {
//        final String id;
//        final NaruLogMode mode;
//        final StringBuilder text = new StringBuilder();
//        boolean streamed;
//        boolean finished;
//        boolean collapsed;
//
//        Row(String id, NaruLogMode mode) {
//            this.id = id;
//            this.mode = mode;
//        }
//    }
//
//    public enum Renderer {
//        /** Cheap: one JLabel per visible row, plain text, coloured by mode. */
//        LABEL,
//        /** Expensive: a JEditorPane per visible row, so HTML/markdown is possible. */
//        HTML
//    }
//
//    private final DefaultListModel<Row> model = new DefaultListModel<>();
//    private final JList<Row> list = new JList<>(model);
//    private final javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(list);
//    private final java.util.Map<String, Row> rowsById = new java.util.HashMap<>();
//    private final AtomicLong appliedEvents = new AtomicLong();
//    private final AtomicLong maxApplyMillis = new AtomicLong();
//    private final Renderer renderer;
//
//    public TranscriptPanel(Renderer renderer) {
//        super(new BorderLayout());
//        this.renderer = renderer;
//        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
//        list.setCellRenderer(new Renderer1());
//        add(scroll, BorderLayout.CENTER);
//    }
//
//    /** Apply one coalesced batch. Must be called on the EDT. */
//    public void applyBatch(List<TranscriptEvent> batch) {
//        long t0 = System.nanoTime();
//        boolean stick = isNearBottom();
//        for (TranscriptEvent e : batch) {
//            if (e instanceof TranscriptEvent.Chunk c) {
//                Row row = rowsById.get(c.blockId());
//                if (row == null) {
//                    row = new Row(c.blockId(), c.mode());
//                    rowsById.put(c.blockId(), row);
//                    model.addElement(row);
//                }
//                row.text.append(c.text());
//                row.streamed = !c.finalFragment();
//                if (c.finalFragment()) {
//                    row.finished = true;
//                }
//            } else if (e instanceof TranscriptEvent.Closed cl) {
//                Row row = rowsById.get(cl.blockId());
//                if (row != null) {
//                    row.finished = true;
//                    row.streamed = false;
//                }
//            } else if (e instanceof TranscriptEvent.Opened o) {
//                if (!rowsById.containsKey(o.blockId())) {
//                    Row row = new Row(o.blockId(), o.mode());
//                    rowsById.put(o.blockId(), row);
//                    model.addElement(row);
//                }
//            }
//        }
//        appliedEvents.addAndGet(batch.size());
//        if (stick && !model.isEmpty()) {
//            list.ensureIndexIsVisible(model.getSize() - 1);
//        }
//        long dt = (System.nanoTime() - t0) / 1_000_000L;
//        maxApplyMillis.accumulateAndGet(dt, Math::max);
//    }
//
//    public int blockCount() {
//        return model.getSize();
//    }
//
//    public long totalChars() {
//        long total = 0;
//        for (int i = 0; i < model.size(); i++) {
//            total += model.get(i).text.length();
//        }
//        return total;
//    }
//
//    public long appliedEvents() {
//        return appliedEvents.get();
//    }
//
//    public long maxApplyMillis() {
//        return maxApplyMillis.get();
//    }
//
//    /** Scroll around hard, the way a user does while output streams. Returns repaint cost. */
//    public long stressScroll(int jumps) {
//        long t0 = System.nanoTime();
//        int size = model.getSize();
//        for (int i = 0; i < jumps && size > 0; i++) {
//            final int idx = (int) (Math.random() * size);
//            SwingUtilities.invokeLater(() -> list.ensureIndexIsVisible(idx));
//        }
//        return (System.nanoTime() - t0) / 1_000_000L;
//    }
//
//    private boolean isNearBottom() {
//        javax.swing.JScrollBar bar = scrollBar();
//        if (bar == null) {
//            return true;
//        }
//        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
//    }
//
//    private javax.swing.JScrollBar scrollBar() {
//        return scroll.getVerticalScrollBar();
//    }
//
//    private final class Renderer1 extends javax.swing.DefaultListCellRenderer {
//        private final JEditorPane htmlPane = new JEditorPane();
//
//        @Override
//        public java.awt.Component getListCellRendererComponent(JList<?> l, Object value, int index,
//                                                               boolean sel, boolean focus) {
//            Row row = (Row) value;
//            if (renderer == Renderer.LABEL) {
//                JLabel label = (JLabel) super.getListCellRendererComponent(l, oneLine(row), index, sel, focus);
//                label.setForeground(colorFor(row.mode));
//                label.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
//                label.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
//                return label;
//            }
//            htmlPane.setBackground(sel ? l.getSelectionBackground() : l.getBackground());
//            htmlPane.setContentType("text/html");
//            htmlPane.setText("<html><body style='margin:0'>" + escape(row.text.toString()) + "</body></html>");
//            htmlPane.setCaretPosition(0);
//            return htmlPane;
//        }
//    }
//
//    private static String oneLine(Row row) {
//        String s = row.text.toString();
//        s = s.replace('\n', ' ');
//        if (s.length() > 400) {
//            s = s.substring(0, 400) + " …[" + row.text.length() + " chars]";
//        }
//        return row.mode + " " + (row.finished ? "▣" : "▸") + " " + s;
//    }
//
//    private static Color colorFor(NaruLogMode mode) {
//        Color base = UIManager.getColor("textText");
//        if (base == null) {
//            base = Color.DARK_GRAY;
//        }
//        return switch (mode) {
//            case MODEL_THINKING -> new Color(150, 120, 200);
//            case MODEL_RESPONSE, AGENT_RESPONSE -> new Color(60, 140, 90);
//            case PROGRESS -> new Color(200, 160, 60);
//            case DEBUG, TRACE, SCHEDULER -> new Color(130, 130, 140);
//            case SCRIPT -> new Color(90, 120, 190);
//            case RAW -> base;
//        };
//    }
//
//    private static String escape(String s) {
//        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
//    }
//}
