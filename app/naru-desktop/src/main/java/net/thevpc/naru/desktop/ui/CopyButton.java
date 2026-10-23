package net.thevpc.naru.desktop.ui;

import javax.swing.JButton;
import java.awt.datatransfer.StringSelection;

/**
 * The copy control a transcript row carries.
 *
 * <p>Every row that shows text the reader might want elsewhere has one: an answer, a tool result, a
 * progress note. It is a glyph with the text in its tooltip because the row is the width of the
 * window and the button is the only thing in it that is not words.
 */
final class CopyButton {

    private static final String MARK = "⧉";

    private CopyButton() {
    }

    /** A button that puts {@code supplier}'s text on the clipboard, or nothing when there is none. */
    static JButton create(java.util.function.Supplier<String> supplier, String what) {
        JButton copy = new JButton(MARK);
        copy.setToolTipText("Copy " + what);
        copy.getAccessibleContext().setAccessibleName("Copy " + what);
        copy.setFocusable(false);
        copy.putClientProperty("JButton.buttonType", "toolBarButton");
        copy.setForeground(UiPalette.muted());
        ActionButtons.glyphSized(copy);
        copy.addActionListener(e -> {
            String text = supplier.get();
            if (text == null || text.isEmpty()) {
                return;
            }
            try {
                StringSelection selection = new StringSelection(text);
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
                copy.setText("✓");
                javax.swing.Timer restore = new javax.swing.Timer(1500, ev -> copy.setText(MARK));
                restore.setRepeats(false);
                restore.start();
            } catch (Exception ignored) {
                // no clipboard in this environment; the button simply does nothing
            }
        });
        return copy;
    }
}
