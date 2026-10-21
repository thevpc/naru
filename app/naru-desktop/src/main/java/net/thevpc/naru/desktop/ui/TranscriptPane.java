package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.desktop.model.BlockStyle;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.TranscriptBlock;
import net.thevpc.naru.desktop.model.TranscriptModel;
import net.thevpc.naru.desktop.render.MarkdownRenderer;
import net.thevpc.naru.desktop.render.TextHtml;
import net.thevpc.naru.desktop.util.PermissionsText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** Scrollable, selectable chat cards backed by a bounded moving window of transcript blocks. */
public final class TranscriptPane extends JPanel implements TranscriptModel.Listener {
    // Each row contains selectable Swing/HTML components. A bounded window keeps restore and
    // history-page updates responsive even when a session contains many rich messages.
    private static final int WINDOW_SIZE = 40;
    private static final int WINDOW_STEP = 20;
    private static final int STICK_MARGIN = 48;

    private final TranscriptModel model;
    private final NaruSessionModel sessionModel;
    private final boolean showChatMessages;
    private final CardsPanel cards = new CardsPanel();
    private final JScrollPane scroll = new JScrollPane(cards);
    private final JLabel counter = new JLabel(" ");
    private final javax.swing.JTextField search = new javax.swing.JTextField(18);
    private Timer searchTimer;
    private final javax.swing.JToggleButton internalEvents;
    private final javax.swing.JToggleButton traceEvents;
    private final javax.swing.JToggleButton debugEvents;
    private final JButton older = new JButton("↑ Earlier");
    private final JButton newer = new JButton("Later ↓");
    private final JButton moreResults = new JButton("More results");
    private final ChatPartRenderers partRenderers = new ChatPartRenderers();
    private final MarkdownRenderer markdown;
    private final List<TranscriptBlock> matching = new ArrayList<>();
    private final java.util.Map<Long, String> engineCursors = new java.util.LinkedHashMap<>();
    private final java.util.Map<TranscriptBlock, Long> searchSequences = new java.util.IdentityHashMap<>();
    private int first;
    private int last;
    private long taskFilter = Long.MIN_VALUE;
    private boolean adjustingWindow;
    private boolean initial = true;
    private boolean engineSearch;
    private boolean engineHasMore;
    private String query = "";
    private java.util.function.Consumer<String> onJumpToPermissions;
    private java.util.function.Consumer<String> onMessageAction;
    private final javax.swing.JPopupMenu denialMenu = new javax.swing.JPopupMenu();

    public TranscriptPane(NaruSessionModel session) {
        this(session, true);
    }

    /** Build a transcript with a preset message filter so hidden panes do not build unused cards. */
    public TranscriptPane(NaruSessionModel session, boolean showChatMessages) {
        super(new BorderLayout());
        this.sessionModel = session;
        this.model = session.transcript();
        this.showChatMessages = showChatMessages;
        internalEvents = new javax.swing.JToggleButton(showChatMessages ? "Activity" : "Logs", !showChatMessages);
        traceEvents = showChatMessages ? null : new javax.swing.JToggleButton("Trace", false);
        debugEvents = showChatMessages ? null : new javax.swing.JToggleButton("Debug", false);
        this.markdown = new MarkdownRenderer(this::htmlArrived);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        cards.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        cards.setBackground(javax.swing.UIManager.getColor("List.background"));
        internalEvents.setToolTipText(showChatMessages
                ? "Show engine activity between messages (progress notes, host events). Answers and tool calls are always shown."
                : "Show regular session output and logs");
        internalEvents.setFocusable(false);
        internalEvents.setMargin(new java.awt.Insets(2, 6, 2, 6));
        internalEvents.addActionListener(e -> syncRows(true));
        if (traceEvents != null) {
            traceEvents.setToolTipText("Include low-level execution trace events");
            traceEvents.setFocusable(false);
            debugEvents.setToolTipText("Include diagnostic debug output");
            debugEvents.setFocusable(false);
            traceEvents.addActionListener(e -> syncRows(true));
            debugEvents.addActionListener(e -> syncRows(true));
        }
        search.putClientProperty("JTextField.placeholderText", "Search conversation…");
        searchTimer = new Timer(300, e -> runEngineSearch(session));
        searchTimer.setRepeats(false);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { searchChanged(searchTimer); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { searchChanged(searchTimer); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { searchChanged(searchTimer); }
        });
        older.setFocusable(false);
        newer.setFocusable(false);
        older.addActionListener(e -> loadEarlier());
        newer.addActionListener(e -> loadLater());
        moreResults.setVisible(false);
        moreResults.setFocusable(false);
        moreResults.addActionListener(e -> runEngineSearch(session, true));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.add(internalEvents);
        if (traceEvents != null) { controls.add(traceEvents); controls.add(debugEvents); }
        controls.add(search);
        controls.add(older);
        controls.add(newer);
        controls.add(moreResults);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bar.add(controls, BorderLayout.WEST);
        bar.add(counter, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        scroll.setMinimumSize(new Dimension(240, 120));
        // Message cards always wrap at the panel width; horizontal overflow is hosted by the
        // message items themselves, and only those that explicitly ask for it.
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.addMouseWheelListener(e -> {
            JScrollBar scrollBar = scroll.getVerticalScrollBar();
            if (e.getWheelRotation() < 0 && scrollBar.getValue() <= 4
                    && (first > 0 || (!engineSearch && sessionModel.hasOlderTranscript()))) loadEarlier();
            else if (e.getWheelRotation() > 0
                    && scrollBar.getValue() + scrollBar.getVisibleAmount() >= scrollBar.getMaximum() - 4
                    && last < matching.size()) loadLater();
        });
        model.addListener(this);
        syncRows(true);
    }

    private void searchChanged(Timer timer) {
        String next = search.getText().trim();
        if (!next.equals(query)) {
            query = next;
            if (query.isEmpty()) {
                engineSearch = false;
                engineHasMore = false;
                engineCursors.clear();
                searchSequences.clear();
                syncRows(true);
            } else {
                engineSearch = true;
                engineHasMore = false;
                engineCursors.clear();
                searchSequences.clear();
                moreResults.setVisible(false);
                counter.setText("Searching all saved messages…");
                timer.restart();
            }
        }
    }

    private void runEngineSearch(NaruSessionModel session) { runEngineSearch(session, false); }

    private void runEngineSearch(NaruSessionModel session, boolean append) {
        String requested = query;
        session.searchMessages(requested, 200, append ? engineCursors : java.util.Map.of(), result -> {
            if (!requested.equals(query)) return;
            if (!append) {
                matching.clear();
                searchSequences.clear();
                engineCursors.clear();
            }
            for (var hit : result.hits()) {
                var message = hit.stored().message();
                if (message == null || message.getRole() == NaruRole.system) continue;
                TranscriptBlock block = new TranscriptBlock("search-" + hit.taskId() + "-" + hit.stored().id(),
                        message.getRole() == NaruRole.user ? net.thevpc.naru.api.agent.NaruLogMode.SCRIPT
                                : net.thevpc.naru.api.agent.NaruLogMode.AGENT_RESPONSE,
                        hit.taskId(), matching.size());
                block.setMessage(message);
                String body = message.getContent() == null ? "" : message.getContent();
                for (var part : message.getParts()) body += "\n" + part.type() + ": " + part.payload();
                block.append(body);
                block.close();
                matching.add(block);
                searchSequences.put(block, hit.stored().creationSequence());
            }
            engineCursors.clear();
            engineCursors.putAll(result.nextCursors());
            engineHasMore = result.hasMore();
            matching.sort(java.util.Comparator.comparingLong(TranscriptBlock::taskId)
                    .thenComparingLong(b -> searchSequences.getOrDefault(b, (long) b.index())));
            moreResults.setVisible(engineHasMore);
            last = matching.size();
            first = Math.max(0, last - WINDOW_SIZE);
            renderWindow(-1);
            SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
        });
    }

    private void loadEarlier() {
        if (first <= 0) {
            if (!engineSearch && sessionModel.hasOlderTranscript()) {
                older.setEnabled(false);
                sessionModel.loadOlderTranscript(loaded -> {
                    older.setEnabled(first > 0 || sessionModel.hasOlderTranscript());
                    if (!loaded) older.setEnabled(false);
                });
            }
            return;
        }
        int oldFirst = first;
        first = Math.max(0, first - WINDOW_STEP);
        last = Math.min(matching.size(), first + WINDOW_SIZE);
        renderWindow(oldFirst - first);
    }

    private void loadLater() {
        if (last >= matching.size()) return;
        int oldCount = last - first;
        last = Math.min(matching.size(), last + WINDOW_STEP);
        first = Math.max(0, last - WINDOW_SIZE);
        renderWindow(Math.max(0, oldCount - WINDOW_STEP));
    }

    private void syncRows(boolean resetToTail) {
        if (engineSearch && !query.isEmpty()) return;
        // The stick decision has to be taken against the OLD content: isNearBottom() reads the
        // bar's maximum, and once the new rows are added the maximum grows and the bar is no
        // longer near the bottom even though the user never moved. When the user has scrolled
        // up, the reveal below must not drag them back down.
        boolean wasAtTail = resetToTail || initial || isNearBottom();
        matching.clear();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        for (TranscriptBlock b : model.allBlocks()) {
            if (!shouldDisplay(b)) continue;
            if (taskFilter != Long.MIN_VALUE && b.taskId() != taskFilter) continue;
            if (!needle.isEmpty() && !b.text().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            matching.add(b);
        }
        if (initial || resetToTail || wasAtTail) {
            last = matching.size();
            first = Math.max(0, last - WINDOW_SIZE);
            initial = false;
            renderWindow(-1);
            if (wasAtTail) followTail();
        } else {
            first = Math.min(first, Math.max(0, matching.size() - 1));
            last = Math.min(matching.size(), Math.max(first, last));
            renderWindow(-1);
        }
    }

    private boolean shouldDisplay(TranscriptBlock block) {
        BlockStyle style = BlockStyle.forBlock(block);
        if (showChatMessages) {
            // The Conversation tab: everything written for the user, plus engine mechanics only
            // when Activity is on. A directive result is conversation whatever mode carried it.
            return style.audience() == BlockStyle.Audience.CONVERSATION || internalEvents.isSelected();
        }
        // The Logs tab is the whole record, so it keeps the stored conversation too: after a restart
        // only durable messages exist, and hiding them left the tab blank — the raw engine output
        // that made it useful is not persisted. Messages and tool calls are always shown; the
        // toggles below still decide the engine's quieter output.
        if (block.isChatMessage() || block.isToolCard()) return true;
        if (block.mode() == net.thevpc.naru.api.agent.NaruLogMode.TRACE
                || block.mode() == net.thevpc.naru.api.agent.NaruLogMode.SCHEDULER) {
            return traceEvents.isSelected();
        }
        if (block.mode() == net.thevpc.naru.api.agent.NaruLogMode.DEBUG) return debugEvents.isSelected();
        return internalEvents.isSelected();
    }

    private void renderWindow(int anchorIndex) {
        adjustingWindow = true;
        cards.removeAll();
        for (int i = first; i < last; i++) {
            TranscriptBlock block = matching.get(i);
            JPanel card = createCard(block);
            card.putClientProperty("transcript.index", i);
            cards.add(card);
            cards.add(Box.createVerticalStrut(8));
        }
        cards.add(Box.createVerticalGlue());
        int total = matching.size();
        older.setEnabled(first > 0 || (!engineSearch && sessionModel.hasOlderTranscript()));
        newer.setEnabled(last < total);
        int chars = 0;
        for (TranscriptBlock b : matching) chars += b.length();
        counter.setText(matching.size() + " messages"
                + (model.trimmedBlocks() > 0 ? " · " + model.trimmedBlocks() + " earlier trimmed" : "")
                + " · " + chars + " chars" + (engineHasMore ? " · more results available" : ""));
        cards.revalidate();
        cards.repaint();
        requestRenderedBlocks();
        SwingUtilities.invokeLater(() -> {
            if (anchorIndex >= 0 && anchorIndex < cards.getComponentCount()) {
                Component c = cards.getComponent(anchorIndex * 2);
                scroll.getViewport().setViewPosition(new java.awt.Point(0, Math.max(0, c.getY())));
            }
            adjustingWindow = false;
        });
    }

    private JPanel createCard(TranscriptBlock block) {
        JPanel card = buildCard(block);
        // A card built now is not in the tree the last scale pass walked, so it would keep the
        // absolute font it was constructed with and ignore the interface size. Join it to the same
        // base/scale scheme the rest of the window uses.
        net.thevpc.naru.desktop.NaruDesktop.scaleNewComponent(card);
        return card;
    }

    private JPanel buildCard(TranscriptBlock block) {
        if (block.isChatMessage()) {
            ChatMessageCard card = new ChatMessageCard(block.message(), partRenderers, onMessageAction,
                    block.htmlIsCurrent() ? block.html() : null);
            card.putClientProperty("transcript.block", block);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            return card;
        }
        if (block.mode() == net.thevpc.naru.api.agent.NaruLogMode.AGENT_RESPONSE) {
            ChatMessageCard card = new ChatMessageCard(
                    net.thevpc.naru.api.model.NaruMessage.assistant(block.text()),
                    partRenderers, onMessageAction,
                    block.htmlIsCurrent() ? block.html() : null);
            card.putClientProperty("transcript.block", block);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            return card;
        }
        return engineCard(block);
    }

    /** A non-message block: a tool card, thinking, or one of the verbose log modes. */
    private JPanel engineCard(TranscriptBlock block) {
        BlockStyle style = BlockStyle.forBlock(block);
        Color baseBg = javax.swing.UIManager.getColor("Panel.background");
        Color accent = style.foreground();
        Color bg = blend(baseBg, accent, 0.05f);
        Color border = blend(baseBg, accent, 0.35f);

        JPanel card = new JPanel(new BorderLayout(6, 4)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        String labelText = (block.mode() == net.thevpc.naru.api.agent.NaruLogMode.MODEL_THINKING ? "🧠 "
                : block.mode() == net.thevpc.naru.api.agent.NaruLogMode.MODEL_RESPONSE ? "✨ "
                : block.isToolCard() ? "🔧 " : "⚡ ") + style.label() + (block.isToolCard() ? " · Tool" : "");
        JLabel title = new JLabel(labelText);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(accent);
        card.add(title, BorderLayout.NORTH);

        JEditorPane body = HtmlPanes.create(bodyHtml(block), 13f, 0);
        card.putClientProperty("transcript.block", block);
        // the body is what a streaming row refreshes in place; see refreshRow
        card.putClientProperty("transcript.body", body);
        card.add(body, BorderLayout.CENTER);
        if (block.isToolCard() && PermissionsText.isDenial(block.text())) {
            card.setComponentPopupMenu(denialPopup(block.text()));
        }
        return card;
    }

    private static String bodyHtml(TranscriptBlock block) {
        return "<html><body>"
                + (block.htmlIsCurrent() ? block.html() : TextHtml.codeBlock(block.text()))
                + "</body></html>";
    }

    private javax.swing.JPopupMenu denialPopup(String text) {
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        String id = PermissionsText.matchedRuleId(text);
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(id == null
                ? "Open Permissions (default effect)" : "Open rule " + id + " in Permissions");
        item.setEnabled(onJumpToPermissions != null);
        item.addActionListener(e -> { if (onJumpToPermissions != null) onJumpToPermissions.accept(id); });
        popup.add(item);
        return popup;
    }

    private boolean isNearBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar == null || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - STICK_MARGIN;
    }

    private static Color blend(Color a, Color b, float mix) {
        if (a == null) a = Color.WHITE;
        return new Color((int) (a.getRed() * (1 - mix) + b.getRed() * mix),
                (int) (a.getGreen() * (1 - mix) + b.getGreen() * mix),
                (int) (a.getBlue() * (1 - mix) + b.getBlue() * mix));
    }

    private void requestRenderedBlocks() {
        for (int i = first; i < last; i++) {
            TranscriptBlock block = matching.get(i);
            if (block.isClosed()) markdown.request(block);
        }
    }

    private void htmlArrived(List<TranscriptBlock> changed) {
        for (TranscriptBlock block : changed) {
            int index = matching.indexOf(block);
            if (index >= first && index < last) {
                int componentIndex = (index - first) * 2;
                if (componentIndex < cards.getComponentCount()) {
                    cards.remove(componentIndex);
                    cards.add(createCard(block), componentIndex);
                }
            }
        }
        cards.revalidate();
        cards.repaint();
    }

    @Override
    public void transcriptChanged(boolean structural) {
        if (structural || engineSearch) {
            syncRows(false);
            return;
        }
        // Text-only batch: refresh the rows that grew and nothing else. Rebuilding the column
        // here used to run on every flush tick (30 Hz), and for a block this pane filters out
        // it threw away and re-created every visible card for text it never shows, which is the
        // flicker.
        // The stick decision is taken before the rows are touched: streaming text makes the
        // column taller, and a pane the user left at the bottom has to keep following it, while
        // one the user scrolled up must be left where it is.
        boolean wasAtTail = isNearBottom();
        boolean followed = false;
        for (TranscriptBlock block : model.lastTextChanged()) {
            if (refreshRow(block)) {
                followed = true;
            }
        }
        if (followed && wasAtTail) {
            followTail();
        }
    }

    /** Swap one row's content, keeping the card already on screen whenever it can be reused. */
    private boolean refreshRow(TranscriptBlock block) {
        int index = matching.indexOf(block);
        if (index < first || index >= last) {
            // hidden by a filter or outside the window: nothing on screen changed
            return false;
        }
        int componentIndex = (index - first) * 2;
        if (componentIndex >= cards.getComponentCount()) {
            return false;
        }
        Component current = cards.getComponent(componentIndex);
        if (current instanceof JPanel card && block == card.getClientProperty("transcript.block")
                && card.getClientProperty("transcript.body") instanceof JEditorPane body) {
            // same block, same card, only more text: re-set it in place. Destroying and
            // re-creating the card per tick re-laid out (and re-painted) the whole column.
            body.setText(bodyHtml(block));
            cards.revalidate();
            cards.repaint();
            return true;
        }
        // A chat card owns its body layout (parts, forms, a scroller), so it is rebuilt rather
        // than re-texted; that is the same work createCard did when the row first appeared, and
        // it only happens for the rows whose text actually changed in this batch.
        cards.remove(componentIndex);
        JPanel replacement = createCard(block);
        replacement.putClientProperty("transcript.index", index);
        cards.add(replacement, componentIndex);
        cards.revalidate();
        cards.repaint();
        return true;
    }

    public void dispose() {
        model.removeListener(this);
        searchTimer.stop();
        markdown.shutdown();
        partRenderers.close();
    }
    public void setJumpToPermissions(java.util.function.Consumer<String> jump) { onJumpToPermissions = jump; }
    public void setMessageActionHandler(java.util.function.Consumer<String> handler) {
        onMessageAction = handler;
        renderWindow(-1);
    }
    public void registerPartRenderer(ChatPartRenderer renderer) { partRenderers.register(renderer); syncRows(false); }
    public void setTaskFilter(long taskId) { taskFilter = taskId < 0 ? Long.MIN_VALUE : taskId; syncRows(true); }
    /**
     * Jump to the newest row. Deferred by one EDT turn so it runs after the card column has been
     * re-validated and the scrollbar's maximum reflects the rows that were just added — setting
     * the value against the old maximum lands one card short of the bottom.
     */
    public void followTail() { SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum())); }

    // Harness-facing reads retained for existing transcript smoke diagnostics.
    public int rowCount() { return matching.size(); }
    public String cellTextAt(int i) {
        TranscriptBlock b = matching.get(i);
        return b.isChatMessage() ? b.message().getContent() : b.text();
    }
    public String visibleRange() { return matching.isEmpty() ? "none" : first + "-" + (last - 1); }
    public String scrollState() { JScrollBar b = scroll.getVerticalScrollBar(); return b.getValue() + "/" + b.getMaximum(); }
    public boolean isStuckToTail() { return isNearBottom(); }

    /**
     * The card column always takes the viewport's width, whatever the cards' natural width is.
     * That width bound is what makes every text card wrap at the panel edge — and re-wrap when
     * the panel resizes — instead of stretching the conversation into a horizontal scrollbar.
     */
    private static final class CardsPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, (orientation == javax.swing.SwingConstants.VERTICAL
                    ? visibleRect.height : visibleRect.width) - 16);
        }
    }
}
