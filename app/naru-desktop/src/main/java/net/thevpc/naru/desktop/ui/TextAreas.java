package net.thevpc.naru.desktop.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * The desktop's one text-widget factory, so the composer and every detail pane wrap, scroll and
 * paint identically.
 * <p>
 * {@link RSyntaxTextArea} is used because it <em>is</em> a {@code JTextArea}: {@code getText},
 * {@code setCaretPosition}, {@code modelToView} and {@code replaceRange} all keep their usual
 * meaning, so the widget swap costs nothing at the call sites and buys undo, a real popup position
 * under word wrap, and antialiased drawing. No syntax style is ever applied — the engine's own log
 * modes and tool-card text are the only formats this host renders, and inventing a directive
 * grammar here would be a second one to keep in sync.
 */
public final class TextAreas {

    private TextAreas() {
    }

    /** A detail block: monospaced, wrapped, not editable, no caret line to draw on a live poll. */
    public static RSyntaxTextArea readOnly() {
        RSyntaxTextArea a = monospace(0, 0);
        a.setEditable(false);
        return a;
    }

    /** Fixed-width text: the shape every engine dump, diff and log line in this app wants. */
    public static RSyntaxTextArea monospace(int rows, int columns) {
        RSyntaxTextArea a = plain(rows, columns);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return a;
    }

    /** The composer's starting point: three wrapped lines, proportional font, undo on. */
    public static RSyntaxTextArea plain(int rows, int columns) {
        RSyntaxTextArea a = new RSyntaxTextArea(rows, columns);
        apply(a);
        return a;
    }

    /**
     * The shared configuration, exposed for the one widget that must be a subclass of its own
     * rather than built here.
     */
    static void apply(RSyntaxTextArea a) {
        a.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setCodeFoldingEnabled(false);
        a.setHighlightCurrentLine(false);
        a.setPaintTabLines(false);
        a.setAntiAliasingEnabled(true);
        a.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        // RSTA paints its own background from the syntax scheme; the two colours the PLAF chose for
        // a text area have to be pushed onto it or a flatlaf window shows a white island.
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");
        if (bg != null) {
            a.setBackground(bg);
        }
        if (fg != null) {
            a.setForeground(fg);
        }
    }
}
