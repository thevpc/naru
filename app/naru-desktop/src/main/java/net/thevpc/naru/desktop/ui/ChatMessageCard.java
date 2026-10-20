package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruMessagePart;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.function.Consumer;

/** A selectable, role-aware chat card capable of hosting typed interactive message parts. */
public final class ChatMessageCard extends JPanel {
    private final NaruMessage message;
    private final Color cardBg;
    private final Color cardBorder;

    public ChatMessageCard(NaruMessage message, ChatPartRenderers renderers,
                           Consumer<String> actionHandler) {
        this(message, renderers, actionHandler, null);
    }

    public ChatMessageCard(NaruMessage message, ChatPartRenderers renderers,
                           Consumer<String> actionHandler, String preparedHtml) {
        super(new BorderLayout(8, 6));
        this.message = message == null ? NaruMessage.assistant("") : message.copy();
        NaruRole role = this.message.getRole();
        String speaker = role == NaruRole.user ? "👤 You"
                : role == NaruRole.tool ? "🔧 " + nonBlank(this.message.getToolName(), "Tool")
                : role == NaruRole.summary ? "📑 Conversation summary"
                : role == NaruRole.system ? "ℹ️ NARU · Notice" : "✨ NARU";
        Color accent = role == NaruRole.user ? new Color(59, 130, 246)
                : role == NaruRole.tool ? new Color(139, 92, 246)
                : role == NaruRole.system ? new Color(245, 158, 11) : new Color(16, 185, 129);

        Color baseBg = UIManagerColor.panel(this);
        if (role == NaruRole.user) {
            this.cardBg = blend(baseBg, accent, 0.08f);
            this.cardBorder = blend(baseBg, accent, 0.28f);
        } else {
            Color textBg = javax.swing.UIManager.getColor("TextArea.background");
            this.cardBg = textBg != null ? blend(baseBg, textBg, 0.5f) : baseBg;
            Color border = javax.swing.UIManager.getColor("Component.borderColor");
            this.cardBorder = border != null ? border : blend(baseBg, Color.GRAY, 0.25f);
        }

        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);

        JLabel who = new JLabel(speaker);
        who.setFont(who.getFont().deriveFont(java.awt.Font.BOLD, 12.5f));
        who.setForeground(accent);
        heading.add(who, BorderLayout.WEST);

        String textToCopy = this.message.getContent();
        if (textToCopy != null && !textToCopy.isBlank()) {
            javax.swing.JButton copy = new javax.swing.JButton("📋");
            copy.setToolTipText("Copy message text");
            copy.setMargin(new java.awt.Insets(1, 5, 1, 5));
            copy.setFocusable(false);
            copy.putClientProperty("JButton.buttonType", "toolBarButton");
            copy.addActionListener(e -> {
                try {
                    java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(textToCopy);
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                    copy.setText("✓");
                    new javax.swing.Timer(1500, ev -> copy.setText("📋")).start();
                } catch (Exception ignored) {
                }
            });
            heading.add(copy, BorderLayout.EAST);
        }
        add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        if (this.message.getContent() != null && !this.message.getContent().isBlank()) {
            body.add(preparedHtml == null ? selectableText(this.message.getContent())
                    : selectableHtml(preparedHtml));
        }
        if (!this.message.getParts().isEmpty()) {
            for (NaruMessagePart part : this.message.getParts()) {
                ChatPartRenderer renderer = renderers == null ? null : renderers.find(part.type());
                if (renderer != null) body.add(renderer.render(part, actionHandler));
                else body.add(selectableText(part.type() + "\n" + part.payload()));
            }
        }
        if (this.message.getToolCalls() != null) {
            this.message.getToolCalls().forEach(call -> body.add(selectableText("Tool · " + call.getName()
                    + "\n" + String.valueOf(call.getArguments()))));
        }
        add(body, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(cardBg);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g2.setColor(cardBorder);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.dispose();
        super.paintComponent(g);
    }

    public NaruMessage message() { return message.copy(); }

    private static JEditorPane selectableHtml(String html) {
        JEditorPane pane = new JEditorPane("text/html", "<html><body>" + html + "</body></html>");
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return pane;
    }

    private static JEditorPane selectableText(String text) {
        JEditorPane pane = new JEditorPane("text/html", "<html><body><pre style='font-family:sans-serif;white-space:pre-wrap;margin:0;'>" + escape(text) + "</pre></body></html>");
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return pane;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Color blend(Color a, Color b, float mix) {
        if (a == null) a = Color.WHITE;
        if (b == null) b = Color.GRAY;
        return new Color((int) (a.getRed() * (1 - mix) + b.getRed() * mix),
                (int) (a.getGreen() * (1 - mix) + b.getGreen() * mix),
                (int) (a.getBlue() * (1 - mix) + b.getBlue() * mix));
    }

    private static final class UIManagerColor {
        static Color panel(java.awt.Component c) {
            Color background = javax.swing.UIManager.getColor("Panel.background");
            return background == null ? c.getBackground() : background;
        }
    }
}
