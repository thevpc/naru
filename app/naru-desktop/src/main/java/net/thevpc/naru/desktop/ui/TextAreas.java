package net.thevpc.naru.desktop.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;

/**
 * The desktop's one text-widget factory, so the composer and every detail pane wrap, scroll and
 * paint identically.
 * <p>
 * {@link RSyntaxTextArea} is used because it <em>is</em> a {@code JTextArea}: {@code getText},
 * {@code setCaretPosition}, {@code modelToView} and {@code replaceRange} all keep their usual
 * meaning, so the widget swap costs nothing at the call sites and buys undo, a real popup position
 * under word wrap, and antialiased drawing. This factory leaves every pane unstyled — the engine's
 * own log modes and tool-card text are the only formats a read-only pane renders, and inventing a
 * directive grammar here would be a second one to keep in sync. The composer is the exception: it
 * arrives plain from here and {@link ComposerSyntax} then installs its own style, because the roles
 * it paints come from the engine's scanner rather than from a grammar this host would own.
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
        UiFonts.applyMono(a, UiFonts.BODY, Font.PLAIN);
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
        // The library still binds ctrl+ADD and ctrl+SUBTRACT on the numeric keypad to expand/collapse
        // a fold, and a focused text component is consulted before the window, so those dead bindings
        // would swallow the interface-size shortcut whenever the composer or a detail pane has focus.
        // A child map only shadows a parent by mapping the key, not by removing it, so each keystroke
        // is pointed at a no-op action and falls through to the window binding.
        javax.swing.InputMap keys = a.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, KeyEvent.CTRL_DOWN_MASK), "empty");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, KeyEvent.CTRL_DOWN_MASK), "empty");
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

    /** RSTA stores its paint colors explicitly, so a look-and-feel switch must refresh them. */
    public static void refreshThemeColors(java.awt.Container root) {
        if (root instanceof RSyntaxTextArea area) {
            Color bg=UIManager.getColor("TextArea.background"), fg=UIManager.getColor("TextArea.foreground");
            if(bg!=null) area.setBackground(bg); if(fg!=null) area.setForeground(fg);
        }
        for(java.awt.Component child:root.getComponents()) if(child instanceof java.awt.Container c) refreshThemeColors(c);
    }
}
