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
    /**
     * How close to the end still counts as auto-following the stream. A generous margin bridges
     * the moment between content growth and the deferred scroll that lands on the new maximum.
     */
    private static final int STICK_MARGIN = 48;
    /**
     * How close to the end counts as "at the end" for the reader. This is deliberately small: the
     * down button must appear as soon as the reader is off the tail, but a couple of pixels of
     * layout rounding should not make a pane sitting at the bottom look scrolled away.
     */
    private static final int BOTTOM_MARGIN = 4;

    private final TranscriptModel model;
    private final NaruSessionModel sessionModel;
    private final boolean showChatMessages;
    private final CardsPanel cards = new CardsPanel();
    private final TailOverlay scrollHost = new TailOverlay(cards, this::jumpToLatest,
            this::updateTailButton);
    private final JScrollPane scroll = scrollHost.scrollPane();
    private final TurnActivityBar activityBar;
    private final JLabel counter = new JLabel(" ");
    // ten columns is a command and a half to type and still leaves the filter row room for the
    // message counter beside it; eighteen pushed the counter out of the panel at a normal width
    private final javax.swing.JTextField search = new javax.swing.JTextField(10);
    private Timer searchTimer;
    private final javax.swing.JToggleButton internalEvents;
    private final javax.swing.JToggleButton traceEvents;
    private final javax.swing.JToggleButton debugEvents;
    private final JButton older = new JButton("↑");
    private final JButton newer = new JButton("↓");
    private final JButton moreResults = new JButton("More results");
    private final ChatPartRenderers partRenderers = new ChatPartRenderers();
    private final MarkdownRenderer markdown;
    private final List<TranscriptBlock> matching = new ArrayList<>();
    /** The blocks the card column currently shows, in window order; see {@link #renderWindow}. */
    private final List<TranscriptBlock> rendered = new ArrayList<>();
    /**
     * One card per block, reused when the column is rebuilt. Building a row hands a fresh HTML
     * document to Swing's renderer, and a tool-calling turn makes the column structural on every
     * round: rebuilt whole, a window of those documents was parsed on each flush and the
     * interface stopped responding for seconds at a time.
     */
    private final java.util.Map<TranscriptBlock, JPanel> cardCache =
            new java.util.LinkedHashMap<TranscriptBlock, JPanel>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<TranscriptBlock, JPanel> eldest) {
                    return size() > WINDOW_SIZE * 2;
                }
            };
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
        cards.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
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
        older.setToolTipText("Load the messages before this window");
        older.getAccessibleContext().setAccessibleName("Earlier messages");
        newer.setToolTipText("Load the messages after this window");
        newer.getAccessibleContext().setAccessibleName("Later messages");
        ActionButtons.glyphSized(older, newer);
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
        // the count is a readout, not a headline: it should recede behind the filters
        counter.setForeground(UiPalette.muted());
        bar.add(controls, BorderLayout.WEST);
        bar.add(counter, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);
        add(scrollHost, BorderLayout.CENTER);
        // The live turn line belongs to the conversation, not the raw Logs tab. It sits below the
        // scrolling cards so a reader who is deep in history still sees that work is happening.
        activityBar = showChatMessages ? new TurnActivityBar(session) : null;
        if (activityBar != null) {
            add(activityBar, BorderLayout.SOUTH);
        }
        scroll.setMinimumSize(new Dimension(240, 120));
        scrollHost.setMinimumSize(scroll.getMinimumSize());
        // Message cards always wrap at the panel width; horizontal overflow is hosted by the
        // message items themselves, and only those that explicitly ask for it.
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Track a deliberate scroll-away so followTail() can be suppressed even if a stale
        // deferred runnable is still queued on the EDT.
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (e.getValueIsAdjusting()) {
                // the user is dragging the thumb: decide from where they are
                userScrolledAway = !isAtBottom();
            } else if (isAtBottom()) {
                // any way back to the tail resumes following, including a wheel-down or a click
                userScrolledAway = false;
            }
            updateTailButton();
        });
        scroll.addMouseWheelListener(e -> {
            JScrollBar scrollBar = scroll.getVerticalScrollBar();
            // The viewport's own handler already moved the bar before this listener runs, so the
            // wheel never shows up as a drag (valueIsAdjusting stays false). Without this, a
            // reader scrolling up mid-stream is pulled back to the bottom by the next flush.
            userScrolledAway = !isAtBottom();
            updateTailButton();
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
        boolean wasAtTail = resetToTail || initial || (isNearBottom() && !userScrolledAway);
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
            // A window that holds no rows is not a position the user chose — there is nothing there
            // to read. Once content matches again it has to come back, or a transcript that was
            // filtered to empty stays blank for the rest of the session.
            if (first == last && !matching.isEmpty()) {
                last = Math.min(matching.size(), first + WINDOW_SIZE);
            }
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
        List<TranscriptBlock> want = new ArrayList<>(matching.subList(first, last));
        // A structural batch often changes nothing about which rows are on screen — a card closed,
        // or a block trimmed above the window — and rebuilding the column for it used to re-add
        // every row and re-measure every HTML body.
        boolean sameRows = anchorIndex < 0 && want.equals(rendered);
        if (!sameRows) {
            cards.removeAll();
            for (int i = 0; i < want.size(); i++) {
                TranscriptBlock block = want.get(i);
                JPanel card = cachedCard(block);
                card.putClientProperty("transcript.index", first + i);
                cards.add(card);
                cards.add(Box.createVerticalStrut(TranscriptLayer.GAP));
            }
            cards.add(Box.createVerticalGlue());
            rendered.clear();
            rendered.addAll(want);
        }
        int total = matching.size();
        older.setEnabled(first > 0 || (!engineSearch && sessionModel.hasOlderTranscript()));
        newer.setEnabled(last < total);
        int chars = 0;
        for (TranscriptBlock b : matching) chars += b.length();
        counter.setText(matching.size() + " messages"
                + (model.trimmedBlocks() > 0 ? " · " + model.trimmedBlocks() + " earlier trimmed" : "")
                + " · " + chars + " chars" + (engineHasMore ? " · more results available" : ""));
        if (!sameRows) {
            cards.revalidate();
            cards.repaint();
        }
        requestRenderedBlocks();
        SwingUtilities.invokeLater(() -> {
            if (anchorIndex >= 0 && anchorIndex < cards.getComponentCount()) {
                Component c = cards.getComponent(anchorIndex * 2);
                scroll.getViewport().setViewPosition(new java.awt.Point(0, Math.max(0, c.getY())));
            }
            adjustingWindow = false;
            updateTailButton();
        });
    }

    private JPanel createCard(TranscriptBlock block) {
        JPanel card = buildCard(block);
        // the row the pointer is on glows and thickens its gutter, so a reader scanning a long
        // transcript can see which one they are on
        HoverFader.install(card);
        return card;
    }

    /** The row for a block: built once, then re-texted in place whenever its body grew. */
    private JPanel cachedCard(TranscriptBlock block) {
        JPanel card = cardCache.get(block);
        int textLength = block.text().length();
        if (card != null && Integer.valueOf(textLength).equals(card.getClientProperty("transcript.textLength"))) {
            return card;
        }
        // A chat card owns its body layout (parts, forms, a scroller of its own), so it is rebuilt
        // rather than re-texted; an engine row is a single HTML pane and can just be refilled.
        boolean rebuild = card == null || block.isChatMessage()
                || block.mode() == net.thevpc.naru.api.agent.NaruLogMode.AGENT_RESPONSE;
        if (rebuild) {
            card = createCard(block);
            cardCache.put(block, card);
        } else {
            applyBody(card, block);
        }
        card.putClientProperty("transcript.textLength", textLength);
        return card;
    }

    private JPanel buildCard(TranscriptBlock block) {
        if (block.isChatMessage()) {
            ChatMessageCard card = new ChatMessageCard(block.message(), partRenderers, onMessageAction,
                    block.htmlIsCurrent() ? block.html() : null);
            keepFold(card, block);
            card.putClientProperty("transcript.block", block);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            return card;
        }
        if (block.mode() == net.thevpc.naru.api.agent.NaruLogMode.AGENT_RESPONSE) {
            ChatMessageCard card = new ChatMessageCard(
                    net.thevpc.naru.api.model.NaruMessage.assistant(block.text()),
                    partRenderers, onMessageAction,
                    block.htmlIsCurrent() ? block.html() : null);
            keepFold(card, block);
            card.putClientProperty("transcript.block", block);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            return card;
        }
        return engineCard(block);
    }

    /**
     * A chat card is rebuilt whenever its row is re-rendered, so whether the user unfolded it lives
     * on the block; the length rule in {@link #folded} decides the default.
     */
    private void keepFold(ChatMessageCard card, TranscriptBlock block) {
        card.setOnFolded(value -> block.setExpanded(!value));
        card.setFolded(folded(block));
    }

    /** A non-message row: a tool card, thinking, or one of the verbose log modes. */
    private JPanel engineCard(TranscriptBlock block) {
        BlockStyle style = BlockStyle.forBlock(block);
        // Engine mechanics get no hue of their own: nine mode colours on the rows behind Activity
        // turn the detail column into a rainbow, and none of them carries state the reader acts on.
        boolean detail = style.audience() == BlockStyle.Audience.ENGINE_DETAIL;
        Color stripe = detail ? UiPalette.IDLE : style.gutter();
        // A fixed-width body is data, not prose; the faint wash keeps one long dump readable as
        // one stretch against the rows around it.
        boolean tinted = !detail && style.monospace();

        JPanel card = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                TranscriptLayer.paint(this, g, stripe, tinted);
                super.paintComponent(g);
            }
        };
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setOpaque(false);
        card.setBorder(TranscriptLayer.border());

        String labelText = style.label().toUpperCase(java.util.Locale.ROOT);
        String toolName = block.isToolCard() && block.toolCall() != null
                ? block.toolCall().toolName() : null;
        if (toolName != null && !toolName.isBlank()) {
            labelText += " · " + toolName;
        }
        JLabel title = new JLabel(labelText);
        UiFonts.apply(title, UiFonts.CAPTION, Font.BOLD);
        title.setForeground(detail ? UiPalette.muted() : UiPalette.readable(style.gutter()));
        // The meta label carries the facts a collapsed row still owes the reader: how big it is
        // and how long it took. It is not part of the copyable body.
        JLabel meta = new JLabel(" ");
        UiFonts.apply(meta, UiFonts.CAPTION, Font.PLAIN);
        meta.setForeground(UiPalette.muted());
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.setOpaque(false);
        titleRow.add(title);
        titleRow.add(meta);

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(titleRow, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(disclosureButton(card));
        // an engine row is often the only place a tool's output exists; without the control the
        // reader selects it off the screen, and a folded row cannot be selected whole at all
        actions.add(CopyButton.create(block::text, style.label() + " output"));
        heading.add(actions, BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH);
        card.putClientProperty("transcript.meta", meta);

        // The body may be inline or inside its own capped scroller. The host keeps the card's
        // outer layout stable while the reader moves between the two.
        JPanel bodyHost = new JPanel(new BorderLayout());
        bodyHost.setOpaque(false);
        bodyHost.putClientProperty("transcript.scrolled", Boolean.FALSE);
        card.putClientProperty("transcript.bodyHost", bodyHost);

        float ratio = style.monospace() ? UiFonts.CAPTION : UiFonts.SECONDARY;
        JEditorPane body = HtmlPanes.create("", ratio, 0);
        if (style.monospace()) {
            UiFonts.applyMono(body, ratio, Font.PLAIN);
        }
        bodyHost.add(body, BorderLayout.CENTER);
        card.putClientProperty("transcript.block", block);
        // the body is what a streaming row refreshes in place; see refreshRow
        card.putClientProperty("transcript.body", body);
        card.add(bodyHost, BorderLayout.CENTER);
        card.add(showMoreButton(card), BorderLayout.SOUTH);
        applyBody(card, block);
        if (block.isToolCard() && PermissionsText.isDenial(block.text())) {
            card.setComponentPopupMenu(denialPopup(block.text()));
        }
        return card;
    }

    /** The chevron that puts an item away, independent of how much of its body is unfolded. */
    private JButton disclosureButton(JPanel card) {
        JButton disclosure = new JButton("▾");
        disclosure.setBorderPainted(false);
        disclosure.setContentAreaFilled(false);
        disclosure.setForeground(UiPalette.muted());
        disclosure.setFocusable(false);
        disclosure.setMargin(new java.awt.Insets(0, 2, 0, 0));
        disclosure.setToolTipText("Collapse this item");
        disclosure.getAccessibleContext().setAccessibleName("Collapse this item");
        disclosure.addActionListener(e -> {
            if (!(card.getClientProperty("transcript.block") instanceof TranscriptBlock block)) {
                return;
            }
            boolean collapse = !block.isCollapsed();
            block.setCollapsed(collapse);
            if (!collapse) {
                // Reopening shows the block's normal head, not a full-body request from earlier.
                block.setExpanded(false);
                // The reader has moved the item's baseline from the short preview to the head;
                // a later "view less" should return here rather than to the preview.
                block.setPreviewReturn(false);
            }
            applyBody(card, block);
            card.revalidate();
            card.repaint();
        });
        card.putClientProperty("transcript.disclosure", disclosure);
        return disclosure;
    }

    /**
     * Lines a body shows before the rest is folded away. Swing's HTML renderer measures every line
     * of a body on each layout pass, so a four-thousand-line tool log costs seconds per interface
     * size change; a folded head keeps that cost bounded whatever the session accumulates.
     */
    private static final int INLINE_BODY_LINES = HtmlPanes.FOLD_AFTER_LINES;

    /**
     * A body is shown as its head whenever it has lines to hide. This is not a preference the user
     * can lose: a long block is folded from the moment it turns long, streaming included, and the
     * only override is the show-all control on the card itself.
     */
    private static boolean folded(TranscriptBlock block) {
        return HtmlPanes.hiddenLines(block.text(), INLINE_BODY_LINES) > 0 && !block.isExpanded();
    }

    /** The control that unfolds a folded body, or folds it back. Hidden while the body fits. */
    private JButton showMoreButton(JPanel card) {
        JButton more = new JButton();
        more.setBorderPainted(false);
        more.setContentAreaFilled(false);
        more.setForeground(UiPalette.muted());
        more.setFocusable(false);
        more.setMargin(new java.awt.Insets(4, 0, 0, 0));
        more.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        more.addActionListener(e -> {
            if (!(card.getClientProperty("transcript.block") instanceof TranscriptBlock block)) {
                return;
            }
            if (block.isCollapsed()) {
                // "view more" from the short preview goes all the way to the body. Remember that
                // this item's normal state was the preview, because a tool card is collapsed for
                // its size rather than because its log mode defaults to collapsed.
                block.setPreviewReturn(true);
                block.setCollapsed(false);
                block.setExpanded(true);
            } else if (!block.isExpanded()) {
                // Expanding from the folded head makes the head the baseline, not the preview.
                block.setPreviewReturn(false);
                block.setExpanded(true);
            } else {
                block.setExpanded(false);
                // Return to the preview the reader expanded from; otherwise to the head they saw.
                if (block.isPreviewReturn() || BlockStyle.forBlock(block).collapsedByDefault()) {
                    block.setCollapsed(true);
                }
                block.setPreviewReturn(false);
            }
            applyBody(card, block);
            card.revalidate();
            card.repaint();
        });
        card.putClientProperty("transcript.more", more);
        return more;
    }

    /** Re-text a card's body from its block, honouring collapse, fold and the item's own scroll. */
    private static void applyBody(JPanel card, TranscriptBlock block) {
        if (!(card.getClientProperty("transcript.body") instanceof JEditorPane body)
                || !(card.getClientProperty("transcript.bodyHost") instanceof JPanel host)) {
            return;
        }
        BlockStyle style = BlockStyle.forBlock(block);
        int lines = HtmlPanes.lineCount(block.text());
        int previewHidden = HtmlPanes.hiddenLines(block.text(), HtmlPanes.PREVIEW_LINES);
        boolean collapsed = block.isCollapsed() && previewHidden > 0;
        boolean folded = !collapsed
                && HtmlPanes.hiddenLines(block.text(), INLINE_BODY_LINES) > 0
                && !block.isExpanded();
        // A full body longer than a comfortable column gets its own scroll, so the conversation
        // still scrolls as one column and one expanded dump cannot push everything else away. A
        // body that merely crossed the scroll threshold is not "expanded" yet: it stays inline
        // until the reader asks for a folded tail, otherwise every 25-line card becomes a second
        // scrollbar.
        boolean scrolled = !collapsed && !folded && block.isExpanded()
                && lines > HtmlPanes.SCROLL_AFTER_LINES;

        String text = block.text();
        boolean sliced = collapsed || folded;
        if (collapsed) {
            text = HtmlPanes.head(text, HtmlPanes.PREVIEW_LINES);
        } else if (folded) {
            text = HtmlPanes.head(text, INLINE_BODY_LINES);
        }
        // A streaming expanded body re-texts on every batch. setText resets the document's carets
        // and the scrollbar model, so remember where the reader had scrolled to before replacing
        // it; otherwise the inner viewport snaps back to the first line eight times a second.
        int innerOffset = -1;
        if (host.getComponentCount() == 1 && host.getComponent(0) instanceof JScrollPane innerScroller) {
            innerOffset = innerScroller.getVerticalScrollBar().getValue();
        }
        body.setText(shownHtml(block, text, sliced));
        if (innerOffset >= 0 && host.getComponentCount() == 1
                && host.getComponent(0) instanceof JScrollPane innerScroller) {
            JScrollBar innerBar = innerScroller.getVerticalScrollBar();
            innerBar.setValue(Math.min(innerOffset, innerBar.getMaximum()));
        }
        // Data lives on the caption rung, prose on secondary; a body the reader has opened into its
        // own scrollport is inspection material, so prose drops to the caption rung too.
        float ratio = scrolled || style.monospace() ? UiFonts.CAPTION : UiFonts.SECONDARY;
        if (style.monospace()) {
            UiFonts.applyMono(body, ratio, Font.PLAIN);
        } else {
            UiFonts.apply(body, ratio, Font.PLAIN);
        }
        boolean wasScrolled = Boolean.TRUE.equals(host.getClientProperty("transcript.scrolled"));
        if (scrolled != wasScrolled) {
            host.removeAll();
            host.add(scrolled ? HtmlPanes.scroller(body) : body, BorderLayout.CENTER);
            host.putClientProperty("transcript.scrolled", scrolled);
            host.revalidate();
            host.repaint();
        }

        if (card.getClientProperty("transcript.more") instanceof JButton more) {
            // Any expanded item has a way back: the footer is the labelled control, the chevron is
            // the quick one. Hiding the footer on a 30-line item that was expanded from its preview
            // left "view less" nowhere but the unlabelled chevron.
            boolean show = collapsed || folded || block.isExpanded();
            more.setVisible(show);
            if (collapsed) {
                more.setText("⌄ view more · " + previewHidden + " more lines");
            } else if (folded) {
                more.setText("⌄ view more · " + (lines - INLINE_BODY_LINES) + " more lines");
            } else {
                more.setText("⌃ view less");
            }
        }
        if (card.getClientProperty("transcript.disclosure") instanceof JButton disclosure) {
            boolean shownCollapsed = block.isCollapsed() && previewHidden > 0;
            // A one-line row has no hidden preview to put away; a chevron that changes nothing is
            // worse than no chevron at all.
            disclosure.setVisible(previewHidden > 0);
            disclosure.setText(shownCollapsed ? "▸" : "▾");
            disclosure.setToolTipText(shownCollapsed ? "Expand this item" : "Collapse this item");
            disclosure.getAccessibleContext().setAccessibleName(
                    shownCollapsed ? "Expand this item" : "Collapse this item");
        }
        if (card.getClientProperty("transcript.meta") instanceof JLabel meta) {
            StringBuilder summary = new StringBuilder(" ");
            if (lines > 1 && style.monospace()) {
                summary.append("· ").append(lines).append(" lines");
            }
            String elapsed = block.elapsedLabel();
            if (elapsed != null) {
                summary.append(summary.length() > 1 ? " · " : "· ").append(elapsed);
            }
            meta.setText(summary.toString());
        }
    }

    private static String shownHtml(TranscriptBlock block, String text, boolean sliced) {
        String fragment;
        if (!sliced && block.htmlIsCurrent()) {
            fragment = block.html();
        } else {
            BlockStyle style = BlockStyle.forBlock(block);
            // Markdown only exists for the whole closed body; a preview is escaped plain text in
            // the same face, never a half-parsed document.
            fragment = style.monospace() ? TextHtml.codeBlock(text) : TextHtml.paragraphs(text);
        }
        return "<html><body>" + fragment + "</body></html>";
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

    private boolean isAtBottom() {
        return isWithinBottom(BOTTOM_MARGIN);
    }

    /**
     * Auto-follow is more forgiving than the button: a stream that grows the column by a line or
     * two before the deferred scroll runs must not count as the reader having left the tail.
     */
    private boolean isNearBottom() {
        return isWithinBottom(STICK_MARGIN);
    }

    private boolean isWithinBottom(int margin) {
        JScrollBar bar = scroll.getVerticalScrollBar();
        if (bar == null) {
            return true;
        }
        // Before the first layout the scrollbar still carries Swing's default model (0–100), so the
        // arithmetic below reads a pane nobody could have scrolled as sitting far above its tail —
        // and the tail it is pointing at is empty. A viewport that has not been given a height yet
        // is the same thing measured directly: nothing has been laid out, so there is no tail to
        // have left, and the pane follows the newest row by definition.
        if (scroll.getViewport().getExtentSize().height <= 0) {
            return true;
        }
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - margin;
    }

    /**
     * The down button is a readout of the reader's actual position: it appears as soon as they are
     * off the tail, with only a few pixels of tolerance. It never changes scroll position by
     * itself.
     */
    private void updateTailButton() {
        if (scroll == null) {
            // The overlay can be laid out once while TranscriptPane's fields are still being
            // initialized; there is no viewport to measure yet.
            return;
        }
        scrollHost.setTailButtonVisible(!isAtBottom());
    }

    /** True while the floating "jump to latest" button is on screen; package-visible for tests. */
    boolean isJumpToLatestVisible() {
        return scrollHost.isTailButtonVisible();
    }

    /** The floating button, so a headless test can click the real control. */
    JButton jumpToLatestButton() {
        return scrollHost.tailButton();
    }

    /** The scrolling transcript; package-visible for tests. */
    JScrollPane scrollPane() {
        return scroll;
    }

    /** Return to the newest message and resume following the stream. */
    public void jumpToLatest() {
        userScrolledAway = false;
        // Hide immediately; followTail() re-checks after the column has been laid out and will
        // put the button back if the jump could not reach the end.
        scrollHost.setTailButtonVisible(false);
        followTail();
    }

    /**
     * True while the user has scrolled away from the tail on purpose. A deliberate scroll-up
     * suppresses {@link #followTail()} even if a stale deferred runnable from an earlier stream
     * is still queued on the EDT — that is the cause of the bar being yanked back to the bottom
     * while the user is reading earlier content. Cleared again when the user returns to the tail.
     */
    private boolean userScrolledAway = false;

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
                    // the rendered text is the same length as before, only the markup changed, so
                    // the cache is dropped for this row and the card rebuilt from the new html
                    cardCache.remove(block);
                    JPanel replacement = cachedCard(block);
                    replacement.putClientProperty("transcript.index", index);
                    cards.remove(componentIndex);
                    cards.add(replacement, componentIndex);
                }
            }
        }
        cards.revalidate();
        cards.repaint();
        updateTailButton();
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
        boolean wasAtTail = isNearBottom() && !userScrolledAway;
        boolean followed = false;
        for (TranscriptBlock block : model.lastTextChanged()) {
            if (refreshRow(block)) {
                followed = true;
            }
        }
        if (followed && wasAtTail) {
            // followTail() updates the button after the deferred scroll lands. Checking here would
            // measure the old maximum against the grown column and flash the button for one frame
            // on every streamed batch.
            followTail();
        } else {
            updateTailButton();
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
        // the cached card re-texts an engine row where it stands and rebuilds a chat row, so the
        // only component work here is swapping in the card when that rebuild made a new one
        JPanel fresh = cachedCard(block);
        if (fresh != current) {
            cards.remove(componentIndex);
            fresh.putClientProperty("transcript.index", index);
            cards.add(fresh, componentIndex);
        }
        cards.revalidate();
        cards.repaint();
        return true;
    }

    public void dispose() {
        model.removeListener(this);
        if (activityBar != null) {
            activityBar.dispose();
        }
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
    public void followTail() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            if (bar == null || userScrolledAway) return;
            // The caller already decided this pane was sitting at the tail before the content
            // changed; re-checking the geometry here measures it against the NEW maximum, which a
            // stream always grew, so the check failed and the view never followed at all.
            bar.setValue(bar.getMaximum());
            updateTailButton();
        });
    }

    // Harness-facing reads retained for existing transcript smoke diagnostics.
    public int rowCount() { return matching.size(); }
    public String cellTextAt(int i) {
        TranscriptBlock b = matching.get(i);
        return b.isChatMessage() ? b.message().getContent() : b.text();
    }
    public String visibleRange() { return matching.isEmpty() ? "none" : first + "-" + (last - 1); }
    public String scrollState() { JScrollBar b = scroll.getVerticalScrollBar(); return b.getValue() + "/" + b.getMaximum(); }
    public boolean isStuckToTail() { return isAtBottom(); }

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
