package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruLineSyntax;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;
import java.util.List;
import java.util.function.Supplier;

/**
 * Paints one composer line.
 * <p>
 * Two modes, chosen by {@link ComposerSyntax} once per document edit rather than per line: the line
 * is a naru command, in which case the engine's own {@link NaruLineSyntax} says what every word is,
 * or it is a draft of something else — Java, a shell script, prose — in which case a stock RSTA
 * token maker for that language paints it and this class contributes nothing.
 * <p>
 * The offsets come from the engine and are handed to RSTA unchanged, so a colour cannot drift off the
 * word it belongs to: {@link NaruLineSyntax.Span} is in line coordinates and the conversion below is
 * the same arithmetic the generated RSTA makers do it in (array index plus a shift from the document
 * offset), not a second interpretation of it.
 */
public final class ComposerTokenMaker extends TokenMakerBase {

    /** The roles a colour is wanted for. Anything else is painted the component's own foreground. */
    private static final int TYPE_TEXT = TokenTypes.IDENTIFIER;

    private final Supplier<NaruSession> session;
    private TokenMaker draft;
    private java.util.function.Consumer<RuntimeException> onFailure = e -> {
    };

    ComposerTokenMaker(Supplier<NaruSession> session) {
        this.session = session;
    }

    /** Called with the exception the engine scan threw, so the widget can report it instead of losing it. */
    void setOnFailure(java.util.function.Consumer<RuntimeException> onFailure) {
        this.onFailure = onFailure;
    }

    /**
     * Null means "this is a command line, use the naru grammar". A non-null maker takes every line,
     * which is what a multi-line Java draft needs.
     */
    void setDraftMaker(TokenMaker draft) {
        this.draft = draft;
    }

    boolean isDraftMode() {
        return draft != null;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        if (draft != null) {
            return draft.getTokenList(text, initialTokenType, startOffset);
        }
        resetTokenList();
        int count = text.count;
        if (count <= 0) {
            addNullToken();
            return firstToken;
        }
        int base = text.offset;
        int shift = startOffset - base;
        String line = new String(text.array, base, count);
        List<NaruLineSyntax.Span> spans;
        try {
            spans = NaruLineSyntax.scan(session.get(), line);
        } catch (RuntimeException e) {
            // a composer that stops painting is worse than one that paints plain; the failure is
            // reported through onFailure rather than swallowed
            onFailure.accept(e);
            addToken(text.array, base, base + count - 1, TYPE_TEXT, base + shift);
            addNullToken();
            return firstToken;
        }
        int cursor = 0;
        for (NaruLineSyntax.Span sp : spans) {
            if (sp.start() < cursor || sp.end() > count || sp.start() >= sp.end()) {
                continue; // a span that does not describe this line is not worth painting over
            }
            if (sp.start() > cursor) {
                addToken(text.array, base + cursor, base + sp.start() - 1, TokenTypes.WHITESPACE,
                        base + cursor + shift);
            }
            addToken(text.array, base + sp.start(), base + sp.end() - 1, typeOf(sp.role()),
                    base + sp.start() + shift);
            cursor = sp.end();
        }
        if (cursor < count) {
            addToken(text.array, base + cursor, base + count - 1, TYPE_TEXT, base + cursor + shift);
        }
        addNullToken();
        return firstToken;
    }

    @Override
    public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
        if (draft != null) {
            return draft.getLastTokenTypeOnLine(text, initialTokenType);
        }
        // The default implementation tokenizes the whole line to find out what its last token was.
        // A naru line has no state to carry into the next one — no block comment, no open string — so
        // the answer is known without the scan, and that halves the work on every keystroke.
        return Token.NULL;
    }

    /**
     * Which of RSTA's token types a role is painted as. Standard types, not a private range: the
     * theme already knows how to draw these, and {@link ComposerSyntax} only overrides the handful
     * of colours this widget cares about.
     */
    static int typeOf(NaruLineSyntax.Role role) {
        return switch (role) {
            case COMMENT -> TokenTypes.COMMENT_EOL;
            case DIRECTIVE -> TokenTypes.RESERVED_WORD;
            case UNKNOWN_DIRECTIVE -> TokenTypes.ERROR_IDENTIFIER;
            case SUBCOMMAND -> TokenTypes.FUNCTION;
            case OPTION_KEY -> TokenTypes.ANNOTATION;
            case OPTION_VALUE -> TokenTypes.VARIABLE;
            case STRING -> TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
            case NUMBER -> TokenTypes.LITERAL_NUMBER_DECIMAL_INT;
            case PATH -> TokenTypes.DATA_TYPE;
            case TEXT -> TYPE_TEXT;
        };
    }

    /** The token at the given document offset, for a test or a harness that wants the painted type. */
    Token tokenAt(Token list, int documentOffset) {
        for (Token t = list; t != null && t.isPaintable(); t = t.getNextToken()) {
            if (t.containsPosition(documentOffset)) {
                return t;
            }
        }
        return new TokenImpl();
    }
}
