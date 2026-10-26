package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruMessagePart;
import net.thevpc.naru.desktop.render.TextHtml;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.Consumer;

/** A selectable, role-aware chat row capable of hosting typed interactive message parts. */
public final class ChatMessageCard extends JPanel {
    private final NaruMessage message;
    private final Color stripe;
    private final boolean tinted;
    private final String preparedHtml;
    private JEditorPane contentBody;
    private JPanel contentHost;
    private javax.swing.JButton fold;
    private boolean folded;
    private boolean scrolled;
    private Consumer<Boolean> onFolded;

    public ChatMessageCard(NaruMessage message, ChatPartRenderers renderers,
                           Consumer<String> actionHandler) {
        this(message, renderers, actionHandler, null);
    }

    public ChatMessageCard(NaruMessage message, ChatPartRenderers renderers,
                           Consumer<String> actionHandler, String preparedHtml) {
        super(new BorderLayout(0, 5));
        this.preparedHtml = preparedHtml;
        this.message = message == null ? NaruMessage.assistant("") : message.copy();
        NaruRole role = this.message.getRole();
        // No emoji in the caption: it is a label, and the gutter stripe already says who is
        // speaking by the hue it borrows from that caption.
        String speaker = role == NaruRole.user ? "YOU"
                : role == NaruRole.tool ? "TOOL · " + nonBlank(this.message.getToolName(), "call")
                : role == NaruRole.summary ? "SUMMARY"
                : role == NaruRole.system ? "NOTICE" : "NARU";
        Color accent = role == NaruRole.user ? new Color(59, 130, 246)
                : role == NaruRole.tool ? new Color(139, 92, 246)
                : role == NaruRole.system ? new Color(245, 158, 11) : UiPalette.LIVE;
        this.stripe = accent;
        // Only a tool transcript is a wall of fixed-width data worth setting off from the prose.
        this.tinted = role == NaruRole.tool;

        setOpaque(false);
        setBorder(TranscriptLayer.border());

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);

        JLabel who = new JLabel(speaker);
        UiFonts.apply(who, UiFonts.CAPTION, java.awt.Font.BOLD);
        who.setForeground(UiPalette.readable(accent));
        heading.add(who, BorderLayout.WEST);

        String textToCopy = this.message.getContent();
        boolean hasParts = !this.message.getParts().isEmpty();
        if (textToCopy != null && !textToCopy.isBlank() || hasParts) {
            // A row with only parts — a form, an image — still holds text worth taking, and a card
            // without the control looks like an answer the reader cannot copy.
            JButton copy = CopyButton.create(() -> copyableText(this.message),
                    hasParts ? "message and attachments" : "message text");
            heading.add(copy, BorderLayout.EAST);
        }
        add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        if (this.message.getContent() != null && !this.message.getContent().isBlank()) {
            body.add(foldableBody());
        }
        if (!this.message.getParts().isEmpty()) {
            for (NaruMessagePart part : this.message.getParts()) {
                ChatPartRenderer renderer = renderers == null ? null : renderers.find(part.type());
                JComponent partComponent = renderer != null ? renderer.render(part, actionHandler)
                        : selectableText(part.type() + "\n" + part.payload());
                // Parts that ask for it (forms, …) keep their natural width in a horizontal
                // scroller; everything else wraps at the card width so the conversation panel
                // only ever scrolls vertically.
                if (renderer != null && renderer.horizontalScrollRequested()) {
                    partComponent = horizontalScroller(partComponent);
                }
                partComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(partComponent);
            }
        }
        if (this.message.getToolCalls() != null) {
            this.message.getToolCalls().forEach(call -> body.add(selectableMonoText("Tool · " + call.getName()
                    + "\n" + String.valueOf(call.getArguments()))));
        }
        add(body, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        TranscriptLayer.paint(this, g, stripe, tinted);
        super.paintComponent(g);
    }

    public NaruMessage message() { return message.copy(); }

    /**
     * The message text, with everything past the first {@code HtmlPanes.FOLD_AFTER_LINES} lines
     * folded behind a control. A directive result or a tool dump is thousands of lines, and Swing's
     * HTML renderer measures every one of them on each layout pass — see the fold in the engine
     * cards, which exists for the same reason.
     */
    private JPanel foldableBody() {
        JPanel stack = new JPanel(new BorderLayout());
        stack.setOpaque(false);
        stack.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentBody = HtmlPanes.create(contentHtml(), UiFonts.BODY, 2);
        // The body may live inline or inside its own capped scrollport. A host panel lets the card
        // switch between the two without rebuilding the HTML document when the reader unfolds it.
        contentHost = new JPanel(new BorderLayout());
        contentHost.setOpaque(false);
        contentHost.add(contentBody, BorderLayout.CENTER);
        stack.add(contentHost, BorderLayout.CENTER);
        fold = new javax.swing.JButton();
        fold.setBorderPainted(false);
        fold.setContentAreaFilled(false);
        fold.setForeground(UiPalette.muted());
        fold.setFocusable(false);
        fold.setMargin(new java.awt.Insets(4, 0, 0, 0));
        fold.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        fold.addActionListener(e -> setFolded(!folded));
        stack.add(fold, BorderLayout.SOUTH);
        refreshBody();
        return stack;
    }

    private String contentHtml() {
        String content = message.getContent();
        if (!folded || HtmlPanes.hiddenLines(content, HtmlPanes.FOLD_AFTER_LINES) == 0) {
            return "<html><body>" + (preparedHtml == null ? TextHtml.paragraphs(content) : preparedHtml)
                    + "</body></html>";
        }
        return "<html><body>" + TextHtml.paragraphs(
                HtmlPanes.head(content, HtmlPanes.FOLD_AFTER_LINES)) + "</body></html>";
    }

    private void updateFold() {
        int hidden = HtmlPanes.hiddenLines(message.getContent(), HtmlPanes.FOLD_AFTER_LINES);
        fold.setVisible(hidden > 0);
        fold.setText(folded ? "⌄ view more · " + hidden + " more lines" : "⌃ view less");
    }

    /**
     * Fold a long body to its head, or show all of it. An unfolded body that is still taller than
     * one comfortable column gets its own capped scroll region, so one long answer cannot push the
     * rest of the conversation off the screen.
     */
    public void setFolded(boolean value) {
        if (contentBody == null || folded == value) {
            return;
        }
        folded = value;
        refreshBody();
        if (onFolded != null) {
            onFolded.accept(value);
        }
    }

    private void refreshBody() {
        if (contentBody == null) {
            return;
        }
        contentBody.setText(contentHtml());
        // A body that needs its own scroll is a document the reader is inspecting, not the flow of
        // the conversation; one step down the ladder keeps it visible without competing with prose.
        // Only a body past the fold threshold can be "unfolded" into this state, so a merely long
        // message stays inline instead of opening a second scrollbar unasked.
        boolean longExpanded = !folded
                && HtmlPanes.lineCount(message.getContent()) > HtmlPanes.SCROLL_AFTER_LINES;
        UiFonts.apply(contentBody, longExpanded ? UiFonts.SECONDARY : UiFonts.BODY, java.awt.Font.PLAIN);
        if (longExpanded != scrolled) {
            scrolled = longExpanded;
            contentHost.removeAll();
            if (scrolled) {
                contentHost.add(HtmlPanes.scroller(contentBody), BorderLayout.CENTER);
            } else {
                contentHost.add(contentBody, BorderLayout.CENTER);
            }
            contentHost.revalidate();
            contentHost.repaint();
        }
        updateFold();
    }

    public void setOnFolded(Consumer<Boolean> listener) {
        this.onFolded = listener;
    }

    /** True while part of the body is folded away behind the show-all control. */
    public boolean isFolded() {
        return folded && fold != null && fold.isVisible();
    }

    /** The text the body is showing: the head of a long message while it is folded. */
    public String bodyText() {
        return contentBody == null ? "" : contentBody.getText();
    }

    private static JEditorPane selectableText(String text) {
        return HtmlPanes.create("<html><body>" + TextHtml.paragraphs(text)
                + "</body></html>", -1f, 4);
    }

    /** A selectable body for data: a tool call's arguments are JSON, not prose. */
    private static JEditorPane selectableMonoText(String text) {
        return HtmlPanes.create("<html><body>" + TextHtml.codeBlock(text)
                + "</body></html>", UiFonts.BODY, 4);
    }

    /** Hosts a natural-width part so wide content scrolls inside the card instead of widening it. */
    private static JComponent horizontalScroller(JComponent content) {
        JScrollPane scroller = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.setViewportBorder(BorderFactory.createEmptyBorder());
        scroller.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroller.getHorizontalScrollBar().setUnitIncrement(16);
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        return scroller;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** What the copy control takes: the message text, then any attachment on a line of its own. */
    private static String copyableText(NaruMessage message) {
        StringBuilder sb = new StringBuilder();
        if (message.getContent() != null) {
            sb.append(message.getContent());
        }
        for (NaruMessagePart part : message.getParts()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.type()).append(": ").append(part.payload());
        }
        return sb.toString();
    }
}
