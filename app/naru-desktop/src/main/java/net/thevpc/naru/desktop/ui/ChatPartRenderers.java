package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruMessagePart;
import net.thevpc.naru.desktop.render.MarkdownHtml;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JLabel;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Extensible registry for message parts that need actual controls rather than flattened text. */
public final class ChatPartRenderers implements AutoCloseable {
    private final Map<String, ChatPartRenderer> renderers = new LinkedHashMap<>();
    private final java.util.concurrent.ExecutorService markdownWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "naru-chat-part-markdown");
        t.setDaemon(true);
        return t;
    });

    public ChatPartRenderers() {
        register(new TextRenderer("markdown", true, markdownWorker));
        register(new TextRenderer("text", false, markdownWorker));
        register(new ActionRenderer("download", "Download"));
        register(new ActionRenderer("button", "Open"));
        register(new ChoiceRenderer());
        register(new FormRenderer());
    }

    public void register(ChatPartRenderer renderer) {
        if (renderer == null || renderer.type() == null || renderer.type().isBlank()) {
            throw new IllegalArgumentException("renderer and type are required");
        }
        renderers.put(renderer.type().toLowerCase(java.util.Locale.ROOT), renderer);
    }

    public ChatPartRenderer find(String type) {
        return type == null ? null : renderers.get(type.toLowerCase(java.util.Locale.ROOT));
    }

    @Override public void close() { markdownWorker.shutdownNow(); }

    private static final class TextRenderer implements ChatPartRenderer {
        private final String type;
        private final boolean markdown;
        private final java.util.concurrent.ExecutorService worker;

        private TextRenderer(String type, boolean markdown, java.util.concurrent.ExecutorService worker) {
            this.type = type;
            this.markdown = markdown;
            this.worker = worker;
        }

        public String type() { return type; }

        public javax.swing.JComponent render(NaruMessagePart part, Consumer<String> actionHandler) {
            String raw = part.payload().asStringValue().orNull();
            final String value = raw == null ? part.payload().toString() : raw;
            JEditorPane pane = new JEditorPane("text/html", "<html><body><pre>" + escape(value) + "</pre></body></html>");
            pane.setEditable(false);
            pane.setOpaque(false);
            pane.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            if (markdown) worker.execute(() -> {
                String html = MarkdownHtml.toHtml(value);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    pane.setText("<html><body>" + html + "</body></html>");
                    pane.setCaretPosition(0);
                });
            });
            return pane;
        }
    }

    private static final class ActionRenderer implements ChatPartRenderer {
        private final String type;
        private final String fallback;
        private ActionRenderer(String type, String fallback) { this.type = type; this.fallback = fallback; }
        public String type() { return type; }

        public javax.swing.JComponent render(NaruMessagePart part, Consumer<String> actionHandler) {
            String label = field(part, "label", fallback);
            String target = field(part, "url", field(part, "path", ""));
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            panel.setOpaque(false);
            panel.add(new JLabel(type.equals("download") ? "↓" : "↗"));
            JButton button = new JButton(label);
            button.setFocusable(true);
            button.addActionListener(e -> {
                if (type.equals("download")) download(target, field(part, "filename", "download"));
                else if (actionHandler != null) actionHandler.accept(target);
            });
            panel.add(button);
            String filename = field(part, "filename", "");
            if (!filename.isBlank()) panel.add(new JLabel(filename));
            return panel;
        }
    }

    private static final class ChoiceRenderer implements ChatPartRenderer {
        public String type() { return "choices"; }
        public javax.swing.JComponent render(NaruMessagePart part, Consumer<String> actionHandler) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            panel.setOpaque(false);
            for (String choice : fieldList(part, "options")) {
                JButton button = new JButton(choice);
                button.addActionListener(e -> { if (actionHandler != null) actionHandler.accept(choice); });
                panel.add(button);
            }
            if (panel.getComponentCount() == 0) panel.add(new JLabel(part.payload().toString()));
            return panel;
        }
    }

    private static final class FormRenderer implements ChatPartRenderer {
        public String type() { return "form"; }
        public javax.swing.JComponent render(NaruMessagePart part, Consumer<String> actionHandler) {
            JPanel panel = new JPanel(new BorderLayout(8, 4));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(panel.getForeground().darker()),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            panel.add(new JLabel(field(part, "title", "Input required")), BorderLayout.NORTH);
            JPanel fields = new JPanel(new java.awt.GridLayout(0, 2, 8, 5));
            fields.setOpaque(false);
            Map<String, JTextField> values = new LinkedHashMap<>();
            for (String name : fieldList(part, "fields")) {
                fields.add(new JLabel(name));
                JTextField value = new JTextField(18);
                values.put(name, value);
                fields.add(value);
            }
            panel.add(fields, BorderLayout.CENTER);
            JButton submit = new JButton(field(part, "submitLabel", "Submit"));
            submit.addActionListener(e -> {
                if (actionHandler != null) {
                    StringBuilder result = new StringBuilder();
                    values.forEach((key, value) -> result.append(key).append('=').append(value.getText()).append('\n'));
                    actionHandler.accept(result.toString());
                }
            });
            panel.add(submit, BorderLayout.SOUTH);
            return panel;
        }
    }

    private static String field(NaruMessagePart part, String name, String fallback) {
        if (part.payload() != null && part.payload().isAnyObject()) {
            String value = part.payload().asObject().get().getStringValue(name).orNull();
            if (value != null) return value;
        }
        return fallback;
    }

    private static java.util.List<String> fieldList(NaruMessagePart part, String name) {
        if (part.payload() == null || !part.payload().isAnyObject()) return java.util.List.of();
        var value = part.payload().asObject().get().get(name).orNull();
        if (value == null || !value.isAnyArray()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var item : value.asArray().get()) {
            String text = item.asStringValue().orNull();
            if (text != null) out.add(text);
        }
        return out;
    }

    private static void download(String target, String filename) {
        try {
            URI uri = URI.create(target);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Only HTTP and HTTPS download links are supported");
            }
            JFileChooser chooser = new JFileChooser();
            String suggested = filename;
            if (suggested == null || suggested.isBlank() || "download".equals(suggested)) {
                String path = uri.getPath();
                suggested = path == null || path.isBlank() ? "download" : new File(path).getName();
            }
            chooser.setSelectedFile(new File(suggested));
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
            java.nio.file.Path destination = chooser.getSelectedFile().toPath();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var in = uri.toURL().openStream()) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                            "Download failed: " + ex.getMessage(), "Download", JOptionPane.ERROR_MESSAGE));
                }
            });
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Invalid download link: " + ex.getMessage(),
                    "Download", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
