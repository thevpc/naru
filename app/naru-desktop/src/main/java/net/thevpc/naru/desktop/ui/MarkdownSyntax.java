package net.thevpc.naru.desktop.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.modes.MarkdownTokenMaker;

import javax.swing.text.Segment;
import java.awt.Color;
import java.awt.Font;
import java.util.Arrays;

/**
 * The palette the plan editor paints Markdown with.
 * <p>
 * RSTA's stock scheme was written for a white editor: its keywords (which is how the Markdown
 * grammar classifies every heading) are pure blue, and its plain text is black. On a dark window
 * that blue sinks into the background instead of reading against it, and the grammar gives all six
 * heading levels the same token, so {@code #}, {@code ##} and {@code ###} are literally the same
 * colour.
 * <p>
 * This class fixes both: the heading token is split into six types of its own, and every role gets a
 * hue adjusted by {@link UiPalette#readable(Color)} for the background the window is painted on. The
 * roles are the ones the Markdown grammar actually emits — headings, emphasis, code, lists, quotes,
 * links, rules and the HTML it passes through — rather than every token RSTA knows, so a theme
 * change moves the rest of the widget without this class having an opinion about it.
 */
public final class MarkdownSyntax {

    /**
     * RSTA's syntax scheme stops at {@link TokenTypes#DEFAULT_NUM_TOKEN_TYPES}. Six slots after it
     * carry the heading levels: the library documents that an application may add token types, and
     * its own {@code restoreDefaults} already copes with a scheme longer than the built-in list.
     */
    static final int HEADING_1 = TokenTypes.DEFAULT_NUM_TOKEN_TYPES;
    static final int HEADING_2 = HEADING_1 + 1;
    static final int HEADING_3 = HEADING_1 + 2;
    static final int HEADING_4 = HEADING_1 + 3;
    static final int HEADING_5 = HEADING_1 + 4;
    static final int HEADING_6 = HEADING_1 + 5;

    /**
     * The base hues. They are deliberately mid-toned rather than electron-bright: {@link
     * UiPalette#readable(Color)} lifts them on dark backgrounds and lowers them on light ones, so
     * the same six families work in every bundled theme without becoming a neon rainbow in one of
     * them.
     */
    private static final Color[] HEADING_HUES = {
            new Color(0x4E, 0xA6, 0xFF), // H1 blue
            new Color(0x2F, 0xBD, 0xBD), // H2 teal
            new Color(0x6F, 0xBF, 0x4A), // H3 green
            new Color(0xD6, 0xA5, 0x4A), // H4 amber
            new Color(0xD0, 0x7A, 0xB5), // H5 rose
            new Color(0x8A, 0x8A, 0x8A), // H6 grey
    };

    private static final Color INLINE_CODE = new Color(0xC5, 0x8A, 0x3D);
    private static final Color LIST_MARKER = new Color(0xC9, 0xA2, 0x27);
    private static final Color HTML_TAG = new Color(0x2F, 0xBD, 0xBD);
    private static final Color HTML_ATTRIBUTE = new Color(0xD6, 0xA5, 0x4A);
    private static final Color HTML_VALUE = new Color(0x6F, 0xBF, 0x4A);

    /** Marks the areas this class owns, so a theme pass does not repaint every other editor. */
    private static final String INSTALLED = "naru.markdown.syntax";

    private MarkdownSyntax() {
    }

    /**
     * Color a plan editor's Markdown and keep the six heading levels apart. Call this once, after
     * the area has its syntax style and its ladder font; a later theme or size pass reaches it
     * again through {@link TextAreas#refreshThemeColors(java.awt.Container)}.
     */
    public static void install(RSyntaxTextArea area) {
        if (area.getDocument() instanceof RSyntaxDocument document) {
            document.setSyntaxStyle(new HeadingMarkdownTokenMaker());
        }
        area.putClientProperty(INSTALLED, Boolean.TRUE);
        applyPalette(area);
    }

    static boolean isInstalled(RSyntaxTextArea area) {
        return Boolean.TRUE.equals(area.getClientProperty(INSTALLED));
    }

    /** Re-resolve the hues against the current look-and-feel without touching the token maker. */
    static void applyPalette(RSyntaxTextArea area) {
        SyntaxScheme scheme = area.getSyntaxScheme();
        if (scheme == null) {
            return;
        }
        ensureStyles(scheme, HEADING_6);

        Font base = area.getFont();
        Font bold = base.deriveFont(Font.BOLD);
        Font italic = base.deriveFont(Font.ITALIC);
        Font boldItalic = base.deriveFont(Font.BOLD | Font.ITALIC);
        Font mono = Typefaces.mono(Font.PLAIN, base.getSize2D());

        Color muted = UiPalette.muted();
        Color accent = UiPalette.accent();
        Color code = readable(INLINE_CODE);

        for (int level = 1; level <= 6; level++) {
            set(scheme, headingType(level), readable(HEADING_HUES[level - 1]), bold);
        }
        // The stock grammar still classifies a heading as RESERVED_WORD; keep that fallback in the
        // first heading's colour so an area that loses its custom maker does not fall back to blue.
        set(scheme, TokenTypes.RESERVED_WORD, readable(HEADING_HUES[0]), bold);

        set(scheme, TokenTypes.RESERVED_WORD_2, null, bold);
        set(scheme, TokenTypes.FUNCTION, null, boldItalic);
        set(scheme, TokenTypes.DATA_TYPE, null, italic);
        set(scheme, TokenTypes.PREPROCESSOR, code, mono);
        set(scheme, TokenTypes.VARIABLE, muted, italic);
        set(scheme, TokenTypes.LITERAL_NUMBER_DECIMAL_INT, readable(LIST_MARKER), base);
        set(scheme, TokenTypes.REGEX, accent, base);
        set(scheme, TokenTypes.ANNOTATION, muted, italic);
        set(scheme, TokenTypes.OPERATOR, accent, base);
        set(scheme, TokenTypes.COMMENT_EOL, muted, italic);
        set(scheme, TokenTypes.COMMENT_DOCUMENTATION, muted, base);
        set(scheme, TokenTypes.WHITESPACE, null, base);
        set(scheme, TokenTypes.MARKUP_TAG_DELIMITER, muted, mono);
        set(scheme, TokenTypes.MARKUP_TAG_NAME, readable(HTML_TAG), mono);
        set(scheme, TokenTypes.MARKUP_TAG_ATTRIBUTE, readable(HTML_ATTRIBUTE), mono);
        set(scheme, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, readable(HTML_VALUE), mono);
        set(scheme, TokenTypes.MARKUP_COMMENT, muted, italic);
        set(scheme, TokenTypes.MARKUP_ENTITY_REFERENCE, readable(HTML_TAG), mono);

        // The fonts above are new even when the colours are not; asking the widget to adopt the
        // scheme is what refreshes its cached font metrics and line height.
        area.setSyntaxScheme(scheme);
    }

    static int headingType(int level) {
        return HEADING_1 + Math.max(0, Math.min(5, level - 1));
    }

    private static Color readable(Color hue) {
        return UiPalette.readable(hue);
    }

    /**
     * Grow the scheme to cover the custom heading types. {@code Style[]} is the library's documented
     * extension point; growing it once is cheaper and safer than borrowing six token types the
     * grammar may use for something else later.
     */
    private static void ensureStyles(SyntaxScheme scheme, int lastType) {
        int needed = lastType + 1;
        if (scheme.getStyleCount() >= needed) {
            return;
        }
        Style[] styles = scheme.getStyles();
        Style[] grown = Arrays.copyOf(styles, needed);
        for (int i = styles.length; i < grown.length; i++) {
            grown[i] = new Style();
        }
        scheme.setStyles(grown);
    }

    private static void set(SyntaxScheme scheme, int type, Color fg, Font font) {
        // A fresh Style also builds the matching FontMetrics; reusing the old object would keep the
        // metrics of whatever font the previous scheme used for this token.
        scheme.setStyle(type, new Style(fg, null, font));
    }

    /**
     * The stock Markdown maker emits an entire {@code #...} line as one {@code RESERVED_WORD}. This
     * subclass only relabels that token according to the number of leading hashes; everything else
     * is the library's own scanner, so code fences, emphasis and links keep working exactly as
     * before.
     */
    private static final class HeadingMarkdownTokenMaker extends MarkdownTokenMaker {

        @Override
        public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
            Token tokens = super.getTokenList(text, initialTokenType, startOffset);
            int level = headingLevel(text);
            if (level < 1) {
                return tokens;
            }
            int type = headingType(level);
            for (Token token = tokens; token != null && token.isPaintable(); token = token.getNextToken()) {
                if (token.getOffset() == startOffset && token.getType() == TokenTypes.RESERVED_WORD) {
                    token.setType(type);
                    break;
                }
            }
            return tokens;
        }

        /** The number of leading {@code #} characters, capped at the six levels Markdown defines. */
        private static int headingLevel(Segment text) {
            if (text == null || text.array == null) {
                return 0;
            }
            int level = 0;
            while (level < 6 && level < text.count && text.array[text.offset + level] == '#') {
                level++;
            }
            return level;
        }
    }
}
