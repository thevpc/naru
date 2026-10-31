package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruContextSegment;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruSegmentContent;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.desktop.model.ModelCallLog;
import net.thevpc.naru.desktop.util.ToolCardText;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Requests tab: every model call this session made, newest first, with the whole request and
 * the whole response of whichever one is selected.
 * <p>
 * Three zones, each with a reason. The summary strip answers "is this session healthy": call count,
 * token totals, latency, and a dot for the prompt cache, because a cache that never hits is
 * invisible in a text dump. The list answers "which call". The detail answers the question that
 * actually costs money to get wrong — <em>what did we send, and what came back</em> — so bodies are
 * never truncated and every message of a request is its own selectable row.
 * <p>
 * The view it replaces put all of that in one {@code JTextArea} with every message cut to 200
 * characters, which is the one thing an inspector must not do: a prompt that grew too large, a tool
 * result that should have been cached, and an instruction that never reached the model are all only
 * visible in the whole.
 * <p>
 * Zones are stacked rather than side by side because this pane lives in a rail about 280 pixels
 * wide, where two panes next to each other leave neither enough room to read a line of prose. Every
 * split is draggable and one-touch collapsible, so the message rail can be given the whole detail
 * when a call is long.
 */
public final class RequestsPane extends JPanel implements ModelCallLog.Listener {

    /** {@code HH:mm:ss} in the machine's zone: these rows are read against a clock, not against a run. */
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Segment groups list this many messages individually; a request with a 200-turn history is not
     * a 200-row rail. The folded tail is still its own row, and selecting it shows every one.
     */
    private static final int RAIL_ROWS_PER_GROUP = 20;

    private final ModelCallLog log;
    private final DefaultListModel<ModelCallLog.Call> rows = new DefaultListModel<>();
    private final JList<ModelCallLog.Call> list = new JList<>(rows);
    private final JLabel countLine = new JLabel(" ");
    private final JLabel tokenLine = new JLabel(" ");
    private final JLabel cacheText = new JLabel(" ");
    private final StatusDot cacheDot = new StatusDot();
    private final javax.swing.JTextField filter = new javax.swing.JTextField();
    private final javax.swing.JToggleButton rawJson = rawToggle();
    private final JLabel requestTitle = new JLabel(" ");
    private final JLabel requestFacts = new JLabel(" ");
    private final DefaultListModel<RailRow> railRows = new DefaultListModel<>();
    private final JList<RailRow> rail = new JList<>(railRows);
    private final RSyntaxTextArea requestBody = TextAreas.readOnly();
    private final JLabel responseTitle = new JLabel(" ");
    private final RSyntaxTextArea responseBody = TextAreas.readOnly();
    private JSplitPane listAndDetail;
    private JSplitPane detail;
    private JSplitPane railAndBody;

    /** True while this pane rewrites its own list models, so the selection listeners keep out. */
    private boolean refreshing;
    private boolean sharesApplied;

    public RequestsPane(ModelCallLog log) {
        super(new BorderLayout());
        this.log = log;
        buildUi();
        log.addListener(this);
        modelCallsChanged();
    }

    private void buildUi() {
        UiFonts.apply(countLine, UiFonts.SECONDARY, Font.PLAIN);
        UiFonts.apply(tokenLine, UiFonts.SECONDARY, Font.PLAIN);
        UiFonts.apply(cacheText, UiFonts.SECONDARY, Font.PLAIN);
        UiFonts.applyMono(requestFacts, UiFonts.CAPTION, Font.PLAIN);
        countLine.setForeground(UiPalette.muted());
        tokenLine.setForeground(UiPalette.muted());
        cacheText.setForeground(UiPalette.muted());

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new CallCell());
        UiFonts.applyMono(list, UiFonts.BODY, Font.PLAIN);
        list.addListSelectionListener(e -> {
            if (!refreshing && !e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        rail.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rail.setCellRenderer(new RailCell());
        UiFonts.applyMono(rail, UiFonts.BODY, Font.PLAIN);
        rail.addListSelectionListener(e -> {
            if (refreshing || e.getValueIsAdjusting()) {
                return;
            }
            // a group header is a label, not a choice: move to the first row under it
            int moved = nextSelectable(rail.getSelectedIndex());
            if (moved >= 0 && moved != rail.getSelectedIndex()) {
                rail.setSelectedIndex(moved);
                return;
            }
            showRailBody();
        });

        // three stacked lines, not a value and an east-side badge: in the 280-pixel inspector rail an
        // EAST label takes the width the numbers need and both end up clipped
        JPanel cache = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
        cache.add(cacheDot);
        cache.add(cacheText);
        JPanel strip = new JPanel();
        strip.setLayout(new javax.swing.BoxLayout(strip, javax.swing.BoxLayout.Y_AXIS));
        // a JLabel's default X alignment is centre, which a BoxLayout honours: in a stack that makes
        // the summary read as a pyramid of half-lines instead of a column of numbers
        for (JComponent line : new JComponent[]{countLine, tokenLine, cacheText, cache}) {
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        strip.add(countLine);
        strip.add(tokenLine);
        strip.add(cache);
        strip.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

        filter.putClientProperty("JTextField.placeholderText", "Filter model, #task, stop reason…");
        filter.setToolTipText("Match the model name, a task id like #2, or the stop reason");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { modelCallsChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { modelCallsChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { modelCallsChanged(); }
        });
        javax.swing.JButton copyRequest = CopyButton.create(
                () -> list.getSelectedValue() == null ? null : json(list.getSelectedValue().request()),
                "the selected request as provider-facing JSON");
        javax.swing.JButton copyResponse = CopyButton.create(
                () -> list.getSelectedValue() == null ? null : json(list.getSelectedValue().response()),
                "the selected response as JSON");
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
        actions.add(rawJson);
        actions.add(copyRequest);
        actions.add(copyResponse);
        // the toggle re-renders both bodies: the rail keeps its selection through the rebuild, so
        // flipping to JSON and back lands the reader on the message they were reading
        rawJson.addActionListener(e -> showSelected());
        JPanel controls = new JPanel(new BorderLayout(2, 0));
        controls.add(filter, BorderLayout.CENTER);
        controls.add(actions, BorderLayout.EAST);
        controls.setBorder(BorderFactory.createEmptyBorder(0, 4, 3, 4));

        JPanel head = new JPanel(new BorderLayout());
        head.add(strip, BorderLayout.NORTH);
        head.add(controls, BorderLayout.SOUTH);

        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.add(zoneHead(requestTitle, requestFacts), BorderLayout.NORTH);
        railAndBody = SplitPanes.vertical(new javax.swing.JScrollPane(rail), scroll(requestBody));
        railAndBody.setResizeWeight(0.4);
        requestPanel.add(railAndBody, BorderLayout.CENTER);

        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.add(zoneHead(responseTitle, null), BorderLayout.NORTH);
        responsePanel.add(scroll(responseBody), BorderLayout.CENTER);

        detail = SplitPanes.vertical(requestPanel, responsePanel);
        detail.setResizeWeight(0.55);
        listAndDetail = SplitPanes.vertical(new javax.swing.JScrollPane(list), detail);
        listAndDetail.setResizeWeight(0.34);

        add(head, BorderLayout.NORTH);
        add(listAndDetail, BorderLayout.CENTER);
        applySharesOnFirstLayout();
        fixHeights();
    }

    /**
     * The three zones' shares of the height. A proportional divider is only honoured on a resize,
     * and the size the pane is built with is zero, so these land on the first real layout: without
     * this the splits open at 50/50 and the response — the thing the reader opened this tab to
     * read — gets a strip two lines tall.
     * <p>
     * Once, deliberately. A divider the reader drags is theirs to keep.
     */
    private void applySharesOnFirstLayout() {
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (sharesApplied || getHeight() <= 0 || getWidth() <= 0) {
                    return;
                }
                sharesApplied = true;
                // one share per event turn, outside in: setting a split's divider re-lays out its
                // children at the end of the turn, so a share asked for in the same turn is computed
                // against the size the split no longer has
                later(() -> {
                    listAndDetail.setDividerLocation(0.34);
                    later(() -> {
                        detail.setDividerLocation(0.55);
                        later(() -> railAndBody.setDividerLocation(0.4));
                    });
                });
            }
        });
    }

    private static void later(Runnable body) {
        javax.swing.SwingUtilities.invokeLater(body);
    }

    private static JComponent zoneHead(JLabel title, JLabel facts) {
        JPanel head = new JPanel(new BorderLayout());
        title.setBorder(BorderFactory.createEmptyBorder(2, 6, facts == null ? 2 : 0, 6));
        head.add(title, BorderLayout.NORTH);
        if (facts != null) {
            facts.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));
            head.add(facts, BorderLayout.CENTER);
        }
        head.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiPalette.border()));
        return head;
    }

    private static JComponent scroll(JComponent body) {
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    /**
     * Fixed-height cells: a variable-height renderer makes a {@code JList} jump while it is being
     * scrolled, and every row here is the same two lines anyway. The height comes from the font and
     * is recomputed when the font changes, which is what an interface-size change does.
     */
    private void fixHeights() {
        applyRowHeights();
        list.addPropertyChangeListener("font", e -> applyRowHeights());
        rail.addPropertyChangeListener("font", e -> applyRowHeights());
    }

    private void applyRowHeights() {
        FontMetrics fm = list.getFontMetrics(list.getFont());
        list.setFixedCellHeight(fm.getHeight() * 2 + 8);
        FontMetrics rf = rail.getFontMetrics(rail.getFont());
        rail.setFixedCellHeight(rf.getHeight() + 4);
    }

    private static javax.swing.JToggleButton rawToggle() {
        javax.swing.JToggleButton b = new javax.swing.JToggleButton("{}");
        b.setToolTipText("Show the provider-facing JSON instead of the message text");
        b.getAccessibleContext().setAccessibleName("Show provider JSON");
        b.setFocusable(false);
        ActionButtons.glyphSized(b);
        b.setBorderPainted(false);
        return b;
    }

    @Override
    public void modelCallsChanged() {
        ModelCallLog.Call selected = list.getSelectedValue();
        // "at the head" is where the reader was before this batch, not where the model now points:
        // a new call is inserted above the selection, so reading the index after the rebuild would
        // report the row that just moved and never follow the traffic
        boolean atHead = list.getSelectedIndex() <= 0;
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        refreshing = true;
        try {
            rows.clear();
            for (ModelCallLog.Call c : log.calls()) {
                if (query.isEmpty() || c.filterKey().contains(query) || matchesTask(c, query)) {
                    rows.addElement(c);
                }
            }
            if (rows.isEmpty()) {
                // nothing to point at; the detail zones say so rather than keep the old call open
            } else if (selected == null || atHead) {
                // the reader was watching the live end, so the newest call is the one they want
                list.setSelectedIndex(0);
                list.ensureIndexIsVisible(0);
            } else {
                int idx = rows.indexOf(selected);
                if (idx >= 0) {
                    list.setSelectedIndex(idx);
                }
            }
        } finally {
            refreshing = false;
        }
        showSummary();
        showSelected();
    }

    private static boolean matchesTask(ModelCallLog.Call c, String query) {
        if (!query.startsWith("#")) {
            return false;
        }
        try {
            return c.taskId() == Long.parseLong(query.substring(1));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void showSummary() {
        ModelCallLog.Summary s = log.summary();
        StringBuilder counts = new StringBuilder();
        counts.append(s.calls()).append(" call").append(s.calls() == 1 ? "" : "s");
        if (rows.getSize() != s.calls()) {
            counts.append(" · ").append(rows.getSize()).append(" shown");
        }
        if (s.droppedCalls() > 0) {
            counts.append(" · ").append(s.droppedCalls()).append(" older dropped");
        }
        counts.append(" · ").append(s.tasks()).append(" task").append(s.tasks() == 1 ? "" : "s")
                .append(" · ").append(s.models()).append(" model").append(s.models() == 1 ? "" : "s");
        countLine.setText(counts.toString());
        // the slowest call belongs in the tooltip, not on the line: this strip is read in a
        // 280-pixel rail, and a fourth number is the one that gets cut off
        tokenLine.setText("in " + compact(s.promptTokens()) + " · out " + compact(s.completionTokens())
                + " · avg " + (s.averageMillis() < 0 ? "—" : ToolCardText.duration(s.averageMillis())));
        tokenLine.setToolTipText("Total input " + s.promptTokens() + " tokens, output "
                + s.completionTokens() + ". Slowest call "
                + (s.slowestMillis() <= 0 ? "—" : ToolCardText.duration(s.slowestMillis())) + ".");
        String tip = "Input tokens served from cache, over all input tokens. Red means nothing has"
                + " ever been read back, which is what a cache that never hits looks like.";
        double share = s.cacheReadShare();
        if (share < 0) {
            cacheText.setText("cache n/a");
            cacheDot.setColor(UiPalette.IDLE);
        } else {
            cacheText.setText(Math.round(share * 100) + "% cache");
            cacheDot.setColor(share == 0 ? UiPalette.ATTENTION
                    : share >= 0.5 ? UiPalette.LIVE : UiPalette.PENDING);
        }
        cacheText.setToolTipText(tip);
        cacheDot.setToolTipText(tip);
    }

    /** Rebuild the detail zones for whichever call is selected now. */
    private void showSelected() {
        ModelCallLog.Call call = list.getSelectedValue();
        if (call == null) {
            requestTitle.setText("Request");
            responseTitle.setText("Response");
            requestFacts.setText(" ");
            railRows.clear();
            String empty = log.calls().isEmpty()
                    ? "No model calls recorded yet."
                    : "Select a call to see its full request and response.";
            requestBody.setText(empty);
            responseBody.setText(empty);
            style(requestBody, false);
            style(responseBody, false);
            return;
        }
        boolean raw = rawJson.isSelected();
        requestTitle.setText("Request · " + call.messageCount() + " messages · "
                + call.toolDefinitionCount() + " tools");
        requestFacts.setText(html(summarize(call)));
        responseTitle.setText("Response · " + tokensOr(call.completionTokens()) + " out · "
                + (call.stopReason() == null ? "—" : call.stopReason())
                + (call.latencyLabel() == null ? "" : " · " + call.latencyLabel()));
        buildRail(call);
        responseBody.setText(raw ? json(call.response()) : responseBody(call));
        style(responseBody, raw);
        responseBody.setCaretPosition(0);
    }

    private static void style(RSyntaxTextArea area, boolean json) {
        area.setSyntaxEditingStyle(json ? SyntaxConstants.SYNTAX_STYLE_JSON
                : SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    /**
     * The facts of a call on one wrapped line: when, who, how long, what it cost, and what the cache
     * did with it. A reader checks these before opening any message.
     */
    private static String summarize(ModelCallLog.Call c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.at() == null ? "—" : CLOCK.format(c.at())).append(" · task #").append(c.taskId())
                .append(" · ").append(c.modelLabel());
        if (c.latencyLabel() != null) {
            sb.append(" · ").append(c.latencyLabel());
        }
        sb.append(" · in ").append(tokensOr(c.promptTokens()))
                .append(" · out ").append(tokensOr(c.completionTokens()));
        if (c.hasCacheAccounting()) {
            sb.append(" · cache read ").append(tokensOr(c.cacheReadTokens()))
                    .append(" · written ").append(tokensOr(c.cacheWriteTokens()));
        } else {
            sb.append(" · cache not reported");
        }
        return sb.toString();
    }

    /** {@code -1} is "the provider never said", which must not be drawn as a zero. */
    private static String tokensOr(int value) {
        return value < 0 ? "?" : compact(value);
    }

    private void buildRail(ModelCallLog.Call call) {
        RailRow keep = rail.getSelectedValue();
        refreshing = true;
        try {
            railRows.clear();
            NaruModelRequest request = call.request();
            List<NaruMessage> messages = request == null || request.messages() == null
                    ? List.of() : request.messages();
            NaruCacheableContext plan = request == null ? null : request.cacheableContext();
            if (plan != null && !plan.isEmpty() && plan.segments() != null) {
                addSegmented(messages, plan);
            } else {
                for (int i = 0; i < messages.size(); i++) {
                    railRows.addElement(new MessageRow(i, messages.get(i), false));
                }
            }
            List<NaruToolDefinition> tools = request == null || request.tools() == null
                    ? List.of() : request.tools();
            if (!tools.isEmpty()) {
                // the count belongs to the row that opens the list, not to both it and its header
                railRows.addElement(new GroupRow("tools", null));
                railRows.addElement(new ToolsRow(tools));
            }
            Map<String, NElement> env = request == null ? null : request.env();
            if (env != null && !env.isEmpty()) {
                railRows.addElement(new GroupRow("env", null));
                railRows.addElement(new EnvRow(env));
            }
        } finally {
            refreshing = false;
        }
        if (keep != null) {
            int idx = indexOfRail(keep);
            if (idx >= 0) {
                rail.setSelectedIndex(idx);
                showRailBody();
                return;
            }
        }
        int first = nextSelectable(0);
        if (first >= 0) {
            rail.setSelectedIndex(first);
        }
        showRailBody();
    }

    /**
     * The messages in the order the provider sees them, grouped by the cache plan that produced
     * them. This is where a caching bug becomes visible: the segment the model was told to reuse,
     * its size, and whether the read counter came back for it.
     * <p>
     * A message that belongs to no segment is listed under {@code unsegmented} rather than dropped.
     * An inspector must not hide a message just because the planner did not claim it.
     */
    private void addSegmented(List<NaruMessage> messages, NaruCacheableContext plan) {
        Map<NaruMessage, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            positions.put(messages.get(i), i);
        }
        List<NaruMessage> claimed = new ArrayList<>();
        for (NaruContextSegment segment : plan.segments()) {
            if (!(segment.content() instanceof NaruSegmentContent.Messages content)) {
                continue;
            }
            List<NaruMessage> part = content.messages();
            if (part == null || part.isEmpty()) {
                continue;
            }
            railRows.addElement(new GroupRow(
                    segment.id() + (segment.cacheable() ? " · cached" : " · volatile"),
                    part.size() + " msgs · " + sizeOf(part)));
            int shown = Math.min(RAIL_ROWS_PER_GROUP, part.size());
            for (int i = 0; i < shown; i++) {
                NaruMessage m = part.get(i);
                railRows.addElement(new MessageRow(positions.getOrDefault(m, -1), m, segment.cacheable()));
            }
            if (part.size() > shown) {
                railRows.addElement(new OverflowRow(part, shown));
            }
            claimed.addAll(part);
        }
        List<NaruMessage> rest = new ArrayList<>(messages);
        rest.removeAll(claimed);
        if (!rest.isEmpty()) {
            railRows.addElement(new GroupRow("unsegmented", rest.size() + " msgs · " + sizeOf(rest)));
            for (NaruMessage m : rest) {
                railRows.addElement(new MessageRow(positions.getOrDefault(m, -1), m, false));
            }
        }
    }

    private int indexOfRail(RailRow row) {
        for (int i = 0; i < railRows.size(); i++) {
            if (railRows.get(i).sameAs(row)) {
                return i;
            }
        }
        return -1;
    }

    private int nextSelectable(int from) {
        for (int i = Math.max(0, from); i < railRows.size(); i++) {
            if (!(railRows.get(i) instanceof GroupRow)) {
                return i;
            }
        }
        return -1;
    }

    private void showRailBody() {
        RailRow row = rail.getSelectedValue();
        boolean raw = rawJson.isSelected();
        if (row == null) {
            requestBody.setText(list.getSelectedValue() == null
                    ? "No call selected." : "This request carried no messages.");
            style(requestBody, false);
            return;
        }
        if (row instanceof MessageRow m) {
            requestBody.setText(raw ? json(m.message()) : messageBody(m.message()));
        } else if (row instanceof ToolsRow t) {
            requestBody.setText(toolList(t.tools(), raw));
        } else if (row instanceof EnvRow e) {
            requestBody.setText(envBlock(e.env()));
        } else if (row instanceof OverflowRow o) {
            requestBody.setText(overflowBody(o, raw));
        }
        style(requestBody, raw);
        requestBody.setCaretPosition(0);
    }

    /** Everything one message carries: text, reasoning, images, and any calls it answers for. */
    private static String messageBody(NaruMessage m) {
        StringBuilder sb = new StringBuilder();
        if (m.getToolName() != null && !m.getToolName().isBlank()) {
            sb.append("tool · ").append(m.getToolName());
            if (m.getToolCallId() != null) {
                sb.append(" (").append(m.getToolCallId()).append(')');
            }
            sb.append("\n\n");
        }
        String thinking = m.getThinking();
        if (thinking != null && !thinking.isBlank()) {
            sb.append("── thinking · ").append(thinking.length()).append(" chars ──\n")
                    .append(thinking).append("\n\n");
        }
        String content = m.getFilteredContent();
        if (content != null && !content.isEmpty()) {
            sb.append(content);
        } else if (thinking == null || thinking.isBlank()) {
            sb.append("(no text)");
        }
        List<String> images = m.getImages();
        if (images != null) {
            for (int i = 0; i < images.size(); i++) {
                String data = images.get(i);
                sb.append("\nimage ").append(i).append(" · base64 ")
                        .append(compact(data == null ? 0 : data.length())).append(" chars");
            }
        }
        if (m.hasToolCalls()) {
            for (NaruToolCall tc : m.getToolCalls()) {
                sb.append("\n\n── tool call ──\n").append(pretty(tc.toElement()));
            }
        }
        return sb.toString();
    }

    private static String toolList(List<NaruToolDefinition> tools, boolean raw) {
        StringBuilder sb = new StringBuilder();
        for (NaruToolDefinition t : tools) {
            sb.append(t.getName());
            String d = t.getDescription();
            if (d != null && !d.isBlank()) {
                sb.append(raw ? "\n" + d : " — " + singleLine(d));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String envBlock(Map<String, NElement> env) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, NElement> e : new java.util.TreeMap<>(env).entrySet()) {
            sb.append(e.getKey()).append(" = ").append(pretty(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    private static String overflowBody(OverflowRow o, boolean raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = o.hiddenFrom(); i < o.messages().size(); i++) {
            NaruMessage m = o.messages().get(i);
            sb.append("── message ").append(i).append(" · ")
                    .append(m.getRole() == null ? "?" : m.getRole().id()).append(" ──\n")
                    .append(raw ? json(m) : nullSafe(m.getFilteredContent())).append('\n');
        }
        return sb.toString();
    }

    private static String responseBody(ModelCallLog.Call call) {
        NaruResponse r = call.response();
        if (r == null || r.getMessage() == null) {
            return "(no content)";
        }
        String body = messageBody(r.getMessage());
        return body.isEmpty() ? "(no content)" : body;
    }

    private static String json(Object value) {
        if (value instanceof NaruMessage msg) {
            return pretty(msg.toElement());
        }
        if (value instanceof NaruResponse r) {
            return json(r.getMessage());
        }
        if (value instanceof NaruModelRequest req) {
            List<NElement> messages = new ArrayList<>();
            if (req.messages() != null) {
                for (NaruMessage m : req.messages()) {
                    messages.add(m.toElement());
                }
            }
            List<NElement> tools = new ArrayList<>();
            if (req.tools() != null) {
                for (NaruToolDefinition t : req.tools()) {
                    tools.add(NElement.ofObjectBuilder().set("name", t.getName())
                            .set("description", t.getDescription()).build());
                }
            }
            NObjectElementBuilder ob = NElement.ofObjectBuilder()
                    .set("messages", NElement.ofArrayBuilder().addAll(messages).build())
                    .set("tools", NElement.ofArrayBuilder().addAll(tools).build());
            if (req.env() != null && !req.env().isEmpty()) {
                NObjectElementBuilder env = NElement.ofObjectBuilder();
                for (Map.Entry<String, NElement> e : req.env().entrySet()) {
                    env.set(e.getKey(), e.getValue());
                }
                ob.set("env", env.build());
            }
            return pretty(ob.build());
        }
        return String.valueOf(value);
    }

    private static String pretty(NElement element) {
        if (element == null) {
            return "null";
        }
        try {
            return NElementWriter.ofJson().formatter(NElementFormatterStyle.PRETTY).formatPlain(element);
        } catch (RuntimeException ex) {
            return String.valueOf(element);
        }
    }

    private static String singleLine(String text) {
        int cut = text.indexOf('\n');
        return cut < 0 ? text : text.substring(0, cut);
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }

    /** A label only wraps when it is HTML, which is the sole reason this line is HTML. */
    private static String html(String text) {
        return "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>";
    }

    private static String sizeOf(List<NaruMessage> messages) {
        long chars = 0;
        for (NaruMessage m : messages) {
            String c = m == null ? null : m.getFilteredContent();
            if (c != null) {
                chars += c.length();
            }
        }
        return compact(chars) + "c";
    }

    /** {@code 128400} as {@code 128k}: these numbers are compared in dozens, not read exactly. */
    private static String compact(long value) {
        if (value < 0) {
            return "?";
        }
        if (value < 1000) {
            return String.valueOf(value);
        }
        if (value < 1000L * 1000) {
            return trim(value / 100.0 / 10) + "k";
        }
        return trim(value / 100000.0) + "M";
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    public void dispose() {
        log.removeListener(this);
    }

    // ── rail rows ────────────────────────────────────────────────────────────

    /**
     * One line of the request rail. Identity is the underlying object, so a rebuild can put the
     * selection back on the same message rather than on whatever now sits at that index.
     */
    private sealed interface RailRow permits GroupRow, MessageRow, ToolsRow, EnvRow, OverflowRow {
        boolean sameAs(RailRow other);
    }

    private record GroupRow(String title, String detail) implements RailRow {
        public boolean sameAs(RailRow other) {
            return other instanceof GroupRow g && g.title.equals(title);
        }
    }

    /**
     * @param cached whether the cache plan put this message inside a segment it claims is stable —
     *               the one fact on this row that a reader cannot get from the message itself
     */
    private record MessageRow(int position, NaruMessage message, boolean cached) implements RailRow {
        public boolean sameAs(RailRow other) {
            return other instanceof MessageRow m && m.message == message;
        }
    }

    private record ToolsRow(List<NaruToolDefinition> tools) implements RailRow {
        public boolean sameAs(RailRow other) {
            return other instanceof ToolsRow t && t.tools == tools;
        }
    }

    private record EnvRow(Map<String, NElement> env) implements RailRow {
        public boolean sameAs(RailRow other) {
            return other instanceof EnvRow e && e.env == env;
        }
    }

    /** The tail of a long segment, folded so the rail stays scanable; selecting it shows them all. */
    private record OverflowRow(List<NaruMessage> messages, int hiddenFrom) implements RailRow {
        public boolean sameAs(RailRow other) {
            return other instanceof OverflowRow o && o.messages == messages && o.hiddenFrom == hiddenFrom;
        }
    }

    // ── renderers ────────────────────────────────────────────────────────────

    /**
     * Two lines: who answered, when, and for which task; then what it cost. The cost line is the one
     * a reader scans for the outlier, so the cache sits on it — a call that re-sent its whole prefix
     * looks exactly like a cached one until the numbers say otherwise.
     */
    private static final class CallCell extends JPanel
            implements javax.swing.ListCellRenderer<ModelCallLog.Call> {
        private final JLabel top = new JLabel();
        private final JLabel bottom = new JLabel();
        private final StatusDot dot = new StatusDot();

        CallCell() {
            super(new BorderLayout(3, 1));
            JPanel text = new JPanel(new BorderLayout());
            text.add(top, BorderLayout.NORTH);
            text.add(bottom, BorderLayout.SOUTH);
            add(dot, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 6));
            UiFonts.applyMono(top, UiFonts.BODY, Font.PLAIN);
            UiFonts.applyMono(bottom, UiFonts.CAPTION, Font.PLAIN);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ModelCallLog.Call> l,
                                                      ModelCallLog.Call value, int index,
                                                      boolean selected, boolean focus) {
            if (value == null) {
                return this;
            }
            top.setText(ModelIcons.glyph(value.provider()) + " " + shortModel(value)
                    + "  #" + value.taskId() + "  " + CLOCK.format(value.at()));
            // the row is read in a 280-pixel rail, so the cost line is four facts at most and the
            // reason a call ended is only worth a slot when it is not the ordinary one
            StringBuilder cost = new StringBuilder();
            cost.append(tokensOr(value.promptTokens())).append("→")
                    .append(tokensOr(value.completionTokens()))
                    .append(" · ").append(value.messageCount()).append(" msgs");
            if (value.hasCacheAccounting()) {
                cost.append(" · cache ").append(tokensOr(value.cacheReadTokens()));
            }
            if (value.toolCallCount() > 0) {
                cost.append(" · ").append(value.toolCallCount()).append(" calls");
            }
            String reason = value.stopReason();
            if (reason != null && !"end_turn".equals(reason)) {
                cost.append(" · ").append(reason);
            }
            bottom.setText(cost.toString());
            setOpaque(true);
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            Color ink = selected ? l.getSelectionForeground() : l.getForeground();
            // The hue on the first line is the provider's, decided in one place. The only state
            // colour on the row is the dot, and it means "this call produced nothing at all".
            top.setForeground(selected ? ink : ModelIcons.glyphColor(value.provider()));
            bottom.setForeground(selected ? ink : UiPalette.muted());
            dot.setColor(!selected && value.producedNothing() ? UiPalette.ATTENTION : null);
            return this;
        }

        private static String shortModel(ModelCallLog.Call c) {
            String model = c.model() == null ? "?" : c.model().model();
            return model.length() > 18 ? model.substring(0, 18) : model;
        }
    }

    /** The rail line: position, role, tool name when it has one, then size. */
    private static final class RailCell extends JPanel implements javax.swing.ListCellRenderer<RailRow> {
        private final JLabel label = new JLabel();

        RailCell() {
            setLayout(new BorderLayout());
            add(label, BorderLayout.CENTER);
            UiFonts.applyMono(label, UiFonts.BODY, Font.PLAIN);
            setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends RailRow> l, RailRow value,
                                                      int index, boolean selected, boolean focus) {
            label.setText(text(value));
            setOpaque(true);
            boolean group = value instanceof GroupRow;
            setBackground(selected ? l.getSelectionBackground()
                    : group ? UiPalette.mix(l.getBackground(), l.getForeground(),
                            UiPalette.dark() ? 0.08f : 0.04f) : l.getBackground());
            Color ink = selected ? l.getSelectionForeground() : l.getForeground();
            if (!selected) {
                // a hue on a row means it sits inside the stable prefix the provider was told to reuse
                ink = group ? UiPalette.muted()
                        : value instanceof MessageRow m && m.cached() ? UiPalette.LIVE : l.getForeground();
            }
            label.setForeground(ink);
            return this;
        }

        private static String text(RailRow value) {
            if (value instanceof GroupRow g) {
                return g.detail() == null ? g.title() : g.title() + "  ·  " + g.detail();
            }
            if (value instanceof MessageRow m) {
                NaruMessage msg = m.message();
                String role = msg.getRole() == null ? "?" : msg.getRole().id();
                String tool = msg.getToolName() == null || msg.getToolName().isBlank()
                        ? "" : ":" + msg.getToolName();
                String position = m.position() < 0 ? "?" : String.valueOf(m.position());
                return "[" + position + "] " + role + tool + "  ·  " + sizeOf(List.of(msg));
            }
            if (value instanceof ToolsRow t) {
                return t.tools().size() + " definitions";
            }
            if (value instanceof EnvRow e) {
                return e.env().size() + " entries";
            }
            if (value instanceof OverflowRow o) {
                return "… " + (o.messages().size() - o.hiddenFrom()) + " more";
            }
            return "";
        }
    }
}
