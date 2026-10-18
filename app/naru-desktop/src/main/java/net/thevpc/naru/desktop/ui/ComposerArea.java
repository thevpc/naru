package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.registry.NaruLineCompletion;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * The composer: a multiline area whose completion is the engine's and whose keys are the
 * terminal's.
 * <p>
 * Everything here is a binding on the component rather than a {@code KeyListener}, because Swing
 * asks the input map before it hands the event to a listener: {@code JTextComponent} already binds
 * Enter and Tab, and a listener that wanted those keys for something else would be running after
 * the widget had eaten them.
 * <p>
 * Keys: Ctrl+Space or Tab ask for candidates; with the list open, Up/Down move through it and Enter
 * takes the highlighted row, so the whole interaction stays on the keyboard. With the list closed,
 * Up/Down are input history and Enter sends. Shift+Enter is always a newline.
 */
public final class ComposerArea extends RSyntaxTextArea {

    /** Where candidates come from: {@code NaruSessionModel.complete} is the production answer. */
    public interface Source {
        NaruLineCompletion.Result complete(String rawLine, int caret);
    }

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("os x");

    private final CompletionPopup popup = new CompletionPopup();
    private final Source source;
    private final UndoManager undo = new UndoManager();
    private Runnable onSend = () -> {
    };
    private Supplier<String> historyPrev = () -> null;
    private Supplier<String> historyNext = () -> null;
    private long lastCompletionNanos;
    private int lastCompletionCount;

    public ComposerArea(Source source) {
        super(3, 40);
        this.source = source;
        TextAreas.apply(this);
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));
        popup.setOnPick(this::insertCandidate);
        getDocument().addUndoableEditListener(undo);
        // any edit or caret move makes the engine's replacement span stale — hiding is the only
        // honest response, because inserting at a span computed against a different text is a bug
        // that lands silently in the middle of the user's line
        getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                popup.hide();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                popup.hide();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                popup.hide();
            }
        });
        addCaretListener(e -> popup.hide());
        installKeys();
    }

    private void installKeys() {
        javax.swing.InputMap im = getInputMap(JComponent.WHEN_FOCUSED);
        javax.swing.ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke("control SPACE"), "naru-complete");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "naru-complete");
        am.put("naru-complete", action(this::requestCompletion));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "naru-prev");
        am.put("naru-prev", action(() -> {
            if (popup.isVisible()) {
                popup.moveSelection(false);
            } else if (onFirstLine()) {
                recall(historyPrev.get());
            } else {
                moveCaret(DefaultEditorKit.upAction);
            }
        }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "naru-next");
        am.put("naru-next", action(() -> {
            if (popup.isVisible()) {
                popup.moveSelection(true);
            } else if (onLastLine()) {
                recall(historyNext.get());
            } else {
                moveCaret(DefaultEditorKit.downAction);
            }
        }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "naru-send");
        am.put("naru-send", action(() -> {
            if (popup.isVisible()) {
                acceptCompletion();
            } else {
                onSend.run();
            }
        }));
        // Shift+Enter has no binding of its own in Swing text components, so the newline it means
        // has to be requested rather than left to fall through
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "naru-newline");
        am.put("naru-newline", am.get(DefaultEditorKit.insertBreakAction));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "naru-dismiss");
        am.put("naru-dismiss", action(() -> {
            if (popup.isVisible()) {
                popup.hide();
            }
        }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE,
                MAC ? KeyEvent.META_DOWN_MASK : KeyEvent.CTRL_DOWN_MASK), "naru-undo");
        am.put("naru-undo", action(this::undoLast));
    }

    /** Ask the engine for candidates at the caret and put the list where the caret is. */
    public void requestCompletion() {
        long started = System.nanoTime();
        NaruLineCompletion.Result r = source.complete(getText(), getCaretPosition());
        lastCompletionNanos = System.nanoTime() - started;
        lastCompletionCount = r.candidates() == null ? 0 : r.candidates().size();
        popup.show(this, caretRect(), r);
    }

    /** Move through the list; the text area keeps the caret, so the popup cannot take focus. */
    public void moveCompletionSelection(boolean down) {
        popup.moveSelection(down);
    }

    /**
     * Take the highlighted candidate — the Enter binding and a mouse click both come here. Nothing
     * happens when the list is closed: an edit or a caret move hides it precisely because the span it
     * remembers no longer describes the text, and inserting at a stale offset is a silent bug.
     */
    public void acceptCompletion() {
        if (!popup.isVisible()) {
            return;
        }
        NaruLineCompletion.Candidate c = popup.selected();
        if (c != null) {
            insertCandidate(c);
        }
        popup.hide();
    }

    private void insertCandidate(NaruLineCompletion.Candidate c) {
        String text = getText();
        int start = popup.replaceStart();
        int end = popup.replaceEnd();
        popup.hide();
        if (start < 0 || end > text.length() || start > end) {
            return;
        }
        replaceRange(c.insert(), start, end);
        setCaretPosition(start + c.insert().length());
    }

    private void recall(String value) {
        if (value == null) {
            return;
        }
        setText(value);
        setCaretPosition(value.length());
    }

    /**
     * History only owns Up/Down at the ends of the text. Inside a multi-line draft the caret has to
     * move, which is what the platform action still does when this widget delegates back to it.
     */
    private boolean onFirstLine() {
        return lineOfCaret() == 0;
    }

    private boolean onLastLine() {
        return lineOfCaret() == getLineCount() - 1;
    }

    private int lineOfCaret() {
        try {
            return getLineOfOffset(getCaretPosition());
        } catch (BadLocationException e) {
            return -1;
        }
    }

    private void moveCaret(String actionName) {
        javax.swing.Action a = getActionMap().get(actionName);
        if (a != null) {
            a.actionPerformed(null);
        }
    }

    /** Ctrl+Backspace (Cmd+Backspace on a Mac): the last edit the user did not mean to make. */
    public void undoLast() {
        if (undo.canUndo()) {
            undo.undo();
        }
    }

    private Rectangle caretRect() {
        try {
            Rectangle r = modelToView(getCaretPosition());
            if (r != null) {
                return r;
            }
        } catch (BadLocationException | IllegalArgumentException ignored) {
            // not laid out yet, or a caret the document no longer has
        }
        return new Rectangle(0, 0, 240, getRowHeight());
    }

    private static javax.swing.AbstractAction action(Runnable body) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                body.run();
            }
        };
    }

    public void setOnSend(Runnable onSend) {
        this.onSend = onSend;
    }

    public void setHistory(Supplier<String> prev, Supplier<String> next) {
        this.historyPrev = prev;
        this.historyNext = next;
    }

    /** The line the user means to send, trimmed the way the composer always has. */
    public String composerText() {
        return getText().trim();
    }

    /** Empty the composer and its undo history: what a sent line leaves behind is nothing. */
    public void clearForSend() {
        setText("");
        undo.discardAllEdits();
    }

    // ── harness-facing reads ──

    public boolean isCompletionVisible() {
        return popup.isVisible();
    }

    /**
     * Which window the candidate list is painted in. The answer must be the frame's own class: a
     * {@code JPopupMenu} that does not fit inside the window becomes its own top-level window, and on
     * X11 that window takes the keyboard away from the composer.
     */
    public String completionHostWindow() {
        java.awt.Window w = popup.hostWindow();
        return w == null ? "none" : w.getClass().getSimpleName();
    }

    /** Where the list sits in the window, so a gate run can tell whether it covers the caret. */
    public java.awt.Rectangle completionBounds() {
        return popup.boundsInWindow();
    }

    public int completionCount() {
        return popup.candidateCount();
    }

    public String completionLabelAt(int i) {
        return popup.labelAt(i);
    }

    /** The row Enter would take, as the user reads it. */
    public String selectedCompletionLabel() {
        return popup.selectedLabel();
    }

    /** The exact text the highlighted row inserts, which is what the assertion wants, not the label. */
    public String selectedCompletionInsert() {
        NaruLineCompletion.Candidate c = popup.selected();
        return c == null ? null : c.insert();
    }

    /**
     * Microseconds the last {@link #requestCompletion()} spent inside the engine, for a gate run
     * that has to know whether path completion is cheap enough to keep on the EDT.
     */
    public long lastCompletionMicros() {
        return lastCompletionNanos / 1_000;
    }

    public int lastCompletionCount() {
        return lastCompletionCount;
    }
}
