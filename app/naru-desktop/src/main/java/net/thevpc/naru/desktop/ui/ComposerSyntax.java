package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Font;
import java.util.function.Supplier;

/**
 * The composer's colours: which grammar paints the current text, and which hue each role gets.
 * <p>
 * A {@link ComposerArea} is one widget with two very different things typed into it — a naru command
 * and a draft of something else (Java, a shell script, prose) — and only the first has an engine
 * grammar. {@link #updateMode()} decides which one the document is in from its first line, and that
 * decision is per document, not per line: {@code NaruLineSyntax} knows nothing about Java and a Java
 * token maker knows nothing about {@code /assert}.
 * <p>
 * The colours live here rather than in a theme file because they are the desktop's own palette — the
 * terminal's {@code NTextStyle} names mean nothing to a Swing widget — but the ROLES come from the
 * engine, so the two hosts cannot disagree about which word is a directive.
 */
public final class ComposerSyntax {

    /** The composer's grammar: a naru command line, or a draft in one of RSTA's languages. */
    public static final String NARU = "naru";

    /**
     * Colours chosen to read on both FlatLaf themes: mid-saturation hues that stay legible on white
     * and on the dark grey the app opens with. The directive is the one thing a user has to find at a
     * glance, so it is the only bold-blue token.
     */
    private static final Color COMMENT = new Color(0x7A, 0x82, 0x88);
    private static final Color DIRECTIVE = new Color(0x4E, 0xA6, 0xFF);
    private static final Color UNKNOWN_DIRECTIVE = new Color(0xFF, 0x6B, 0x6B);
    private static final Color SUBCOMMAND = new Color(0x7F, 0xD3, 0x7F);
    private static final Color OPTION_KEY = new Color(0xE8, 0xC5, 0x5A);
    private static final Color OPTION_VALUE = new Color(0xC7, 0x92, 0xEA);
    private static final Color STRING = new Color(0xF0, 0xA3, 0x6F);
    private static final Color NUMBER = new Color(0x6C, 0xD3, 0xC0);
    private static final Color PATH = new Color(0x9C, 0xDC, 0xFE);

    private final RSyntaxTextArea composer;
    private final ComposerTokenMaker maker;
    private String mode = NARU;
    private boolean reinstalling;
    private int failureCount;
    private String firstFailure;

    private ComposerSyntax(RSyntaxTextArea composer, Supplier<NaruSession> session) {
        this.composer = composer;
        this.maker = new ComposerTokenMaker(session);
        this.maker.setOnFailure(this::noteFailure);
        installMakerOnDocument();
        applyPalette();
        updateMode();
        composer.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateMode();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateMode();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // only our own re-install arrives here, and it must not re-enter itself
            }
        });
    }

    /** Colour the composer and keep it coloured as the text changes. */
    public static ComposerSyntax install(RSyntaxTextArea composer, Supplier<NaruSession> session) {
        return new ComposerSyntax(composer, session);
    }

    /** Which grammar is painting the document right now: {@link #NARU} or a {@code SyntaxConstants} id. */
    public String mode() {
        return mode;
    }

    /** How many lines the engine could not scan, and the first reason. Never painted, always reportable. */
    public String describeFailure() {
        return failureCount == 0 ? "none" : failureCount + " scan failures, first: " + firstFailure;
    }

    /**
     * The colour RSTA will paint the given role with, as a harness that reads pixels needs to know
     * what "right" looks like without hard-coding a hue twice.
     */
    public static Color colorFor(net.thevpc.naru.api.registry.NaruLineSyntax.Role role) {
        return switch (role) {
            case COMMENT -> COMMENT;
            case DIRECTIVE -> DIRECTIVE;
            case UNKNOWN_DIRECTIVE -> UNKNOWN_DIRECTIVE;
            case SUBCOMMAND -> SUBCOMMAND;
            case OPTION_KEY -> OPTION_KEY;
            case OPTION_VALUE -> OPTION_VALUE;
            case STRING -> STRING;
            case NUMBER -> NUMBER;
            case PATH -> PATH;
            case TEXT -> null;
        };
    }

    /**
     * Decide the mode from the text and, when it changed, re-tokenize the whole document.
     * <p>
     * The re-tokenize is not decoration. RSTA caches a token list per line and drops it when that line
     * is edited, so a paste that turns a command line into Java would leave every line above the caret
     * painted as naru: the mode is a property of the document, and a document change can be one keystroke.
     */
    private void updateMode() {
        if (reinstalling) {
            return;
        }
        String next = modeFor(lookAt());
        if (next.equals(mode)) {
            return;
        }
        mode = next;
        if (NARU.equals(next)) {
            maker.setDraftMaker(null);
        } else {
            TokenMaker delegate = TokenMakerFactory.getDefaultInstance().getTokenMaker(next);
            if (delegate == null) {
                // an id RSTA does not know falls back to plain rather than to the naru grammar: prose
                // painted as commands is the failure mode this whole class is here to avoid
                delegate = TokenMakerFactory.getDefaultInstance()
                        .getTokenMaker(SyntaxConstants.SYNTAX_STYLE_NONE);
            }
            if (delegate == null) {
                mode = NARU;
                maker.setDraftMaker(null);
                return;
            }
            maker.setDraftMaker(delegate);
        }
        reinstalling = true;
        try {
            installMakerOnDocument();
        } finally {
            reinstalling = false;
        }
    }

    private void installMakerOnDocument() {
        ((RSyntaxDocument) composer.getDocument()).setSyntaxStyle(maker);
    }

    /** The text a mode decision is made from: the whole composer for a one-liner, a bounded prefix otherwise. */
    private String lookAt() {
        int len = Math.min(composer.getDocument().getLength(), 4_000);
        try {
            return composer.getDocument().getText(0, len);
        } catch (javax.swing.text.BadLocationException e) {
            return "";
        }
    }

    /**
     * A line that starts with {@code /} is a command; {@code #} or {@code //} is a naru comment the
     * engine's own scanner already knows how to paint. A shebang is neither — it is a script — and
     * everything else is a draft whose language has to be guessed.
     */
    static String modeFor(String text) {
        for (String line : text.split("\n", 8)) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#!")) {
                return ComposerLanguage.detect(text);
            }
            if (t.startsWith("/") || t.startsWith("#") || t.startsWith("//")) {
                return NARU;
            }
            return ComposerLanguage.detect(text);
        }
        // empty composer: the command grammar paints nothing, and it is the mode the next keystroke wants
        return NARU;
    }

    /**
     * Override only the types the roles use. Everything else keeps the theme's colour, so a detected
     * Java draft still looks like Java and the widget does not arrive with a hand-made scheme bolted
     * over every token RSTA knows.
     */
    private void applyPalette() {
        SyntaxScheme scheme = composer.getSyntaxScheme();
        Font base = composer.getFont();
        set(scheme, TokenTypes.COMMENT_EOL, COMMENT, base.deriveFont(Font.ITALIC));
        set(scheme, TokenTypes.RESERVED_WORD, DIRECTIVE, base.deriveFont(Font.BOLD));
        set(scheme, TokenTypes.ERROR_IDENTIFIER, UNKNOWN_DIRECTIVE, base.deriveFont(Font.BOLD));
        set(scheme, TokenTypes.FUNCTION, SUBCOMMAND, null);
        set(scheme, TokenTypes.ANNOTATION, OPTION_KEY, null);
        set(scheme, TokenTypes.VARIABLE, OPTION_VALUE, null);
        set(scheme, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, STRING, null);
        set(scheme, TokenTypes.LITERAL_NUMBER_DECIMAL_INT, NUMBER, null);
        set(scheme, TokenTypes.DATA_TYPE, PATH, null);
        // an unknown directive is painted red by RSTA's error styles, which also underline it; the
        // underline is a compiler squiggle, and a half-typed command name is not a compiler error
        Style error = scheme.getStyle(TokenTypes.ERROR_IDENTIFIER);
        if (error != null) {
            error.underline = false;
        }
    }

    private static void set(SyntaxScheme scheme, int type, Color fg, Font font) {
        Style style = scheme.getStyle(type);
        if (style == null) {
            scheme.setStyle(type, new Style(fg, null, font));
            return;
        }
        style.foreground = fg;
        if (font != null) {
            style.font = font;
        }
    }

    private void noteFailure(RuntimeException e) {
        failureCount++;
        if (firstFailure == null) {
            firstFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
