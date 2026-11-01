package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruLineSyntax.Role;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composer's colours, asked of the document rather than of a screenshot.
 * <p>
 * What a user sees is painted pixels — the smoke run reads those — but the thing that can be wrong is
 * the token list under them: a role assigned to the wrong character range, a second line whose offsets
 * were shifted twice, a draft that stayed grey because nothing recognised it. {@link
 * RSyntaxDocument#getTokenListForLine(int)} hands back exactly what the painter will walk, including
 * the document offsets it paints at, so the arithmetic is checked where it is done.
 */
class ComposerSyntaxTest {

    private static final Map<Integer, String> TYPE_NAMES = new HashMap<>();
    private static NaruSession session;

    @BeforeAll
    static void setUp() {
        Nuts.require();
        for (Field f : TokenTypes.class.getFields()) {
            if (f.getType() == int.class) {
                try {
                    TYPE_NAMES.putIfAbsent(f.getInt(null), f.getName());
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static NaruSession session() {
        if (session == null) {
            NPath dir = NPath.ofTempFolder("naru-desktop-syntax");
            NaruWorkspace agent = NaruWorkspace.of();
            agent.projectDirectory(dir);
            session = agent.newSession().directory(dir).configureDefaults(false).build();
            session.registry().registerDirectiveProvider(
                    new net.thevpc.naru.impl.registry.NaruBuiltinDirectiveProvider());
        }
        return session;
    }

    /**
     * A composer with its syntax installed, holding {@code lines}. Every line goes through the real
     * document, so the segment arithmetic and the non-zero start offset of line 2 are part of the test.
     */
    private static ComposerSyntax wire(RSyntaxTextArea area, String lines) {
        area.setText(lines);
        return ComposerSyntax.install(area, ComposerSyntaxTest::session);
    }

    /** {@code TYPE@start-end('lexeme')} for every painted token on the line. */
    private static List<String> tokensOf(RSyntaxTextArea area, int line) {
        List<String> out = new ArrayList<>();
        Token t = ((RSyntaxDocument) area.getDocument()).getTokenListForLine(line);
        for (; t != null && t.isPaintable(); t = t.getNextToken()) {
            out.add(TYPE_NAMES.getOrDefault(t.getType(), "?" + t.getType()) + "@"
                    + t.getOffset() + "-" + t.getEndOffset() + "('" + t.getLexeme() + "')");
        }
        return out;
    }

    @Test
    void aCommandLineIsSplitIntoTheRolesTheEngineNamed() {
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        wire(area, "/assert --level 2 \"a b\" /tmp/x 42 tail");
        assertEquals(List.of(
                        "RESERVED_WORD@0-7('/assert')", "WHITESPACE@7-8(' ')",
                        "ANNOTATION@8-15('--level')", "WHITESPACE@15-16(' ')",
                        "VARIABLE@16-17('2')", "WHITESPACE@17-18(' ')",
                        "LITERAL_STRING_DOUBLE_QUOTE@18-23('\"a b\"')", "WHITESPACE@23-24(' ')",
                        "DATA_TYPE@24-30('/tmp/x')", "WHITESPACE@30-31(' ')",
                        "LITERAL_NUMBER_DECIMAL_INT@31-33('42')", "WHITESPACE@33-34(' ')",
                        "IDENTIFIER@34-38('tail')"),
                tokensOf(area, 0));
    }

    @Test
    void theSecondLineIsPaintedAtItsOwnOffsets() {
        // the bug this pins: a token maker that forgets the segment shift gets line 0 right and every
        // line below it wrong, because line 0's document offset happens to be its array index
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        wire(area, "/help\n/assert 42");
        List<String> second = tokensOf(area, 1);
        assertEquals(List.of("RESERVED_WORD@6-13('/assert')", "WHITESPACE@13-14(' ')",
                "LITERAL_NUMBER_DECIMAL_INT@14-16('42')"), second);
    }

    @Test
    void anUnknownCommandIsPaintedAsOneAndNotAsThreeWords() {
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        wire(area, "/no-such-command here");
        assertEquals(List.of("ERROR_IDENTIFIER@0-16('/no-such-command')", "WHITESPACE@16-17(' ')",
                "IDENTIFIER@17-21('here')"), tokensOf(area, 0));
    }

    @Test
    void aCommentLineBecomesOneCommentToken() {
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        wire(area, "# why this line is skipped");
        assertEquals(List.of("COMMENT_EOL@0-26('# why this line is skipped')"), tokensOf(area, 0));
    }

    @Test
    void aDirectiveLineKeepsTheModeAndADraftLosesIt() {
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        ComposerSyntax syntax = wire(area, "/help");
        assertEquals(ComposerSyntax.NARU, syntax.mode());
        area.setText("explain the budget");
        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE, syntax.mode());
        area.setText("/help");
        assertEquals(ComposerSyntax.NARU, syntax.mode());
        assertEquals("none", syntax.describeFailure());
    }

    @Test
    void theModeIsDecidedFromTheFirstLineThatHasContent() {
        assertEquals(ComposerSyntax.NARU, ComposerSyntax.modeFor("   \n/assert x"));
        assertEquals(ComposerSyntax.NARU, ComposerSyntax.modeFor("// a naru comment"));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL, ComposerSyntax.modeFor("#!/bin/bash\necho hi"));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE, ComposerSyntax.modeFor("explain the budget"));
        assertEquals(ComposerSyntax.NARU, ComposerSyntax.modeFor(""));
    }

    @Test
    void aKnownSignatureIsColouredByThatLanguageAndProseStaysPlain() {
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA,
                ComposerLanguage.detect("public class Foo {\n  void bar() {\n  }\n}"));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_PYTHON,
                ComposerLanguage.detect("#!/usr/bin/env python3\nprint(1)"));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
                ComposerLanguage.detect("#!/bin/sh\nset -e\nif test -f x; then\nfi"));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_SQL,
                ComposerLanguage.detect("select id from task where state = 'RUNNING'"));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JSON,
                ComposerLanguage.detect("{\"a\": 1, \"b\": 2}"));
        // the honest answer for a sentence: no colour, because a guessed language paints nonsense
        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE,
                ComposerLanguage.detect("summarise what changed last week in the budget file"));
    }

    @Test
    void aJavaDraftIsPaintedByJavasOwnMakerNotByTheNaruGrammar() {
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        ComposerSyntax syntax = wire(area, "public class Foo {\n    int x = 1;\n}");
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, syntax.mode());
        // Java's maker knows `public` is a keyword; the naru grammar would have called the whole line TEXT
        assertTrue(tokensOf(area, 0).stream().anyMatch(s -> s.startsWith("RESERVED_WORD")
                        && s.contains("'public'")),
                "expected Java's keyword token, got " + tokensOf(area, 0));
    }

    @Test
    void thePaletteReachesTheSchemeThatPaintsIt() {
        // applyPalette() is the difference between a role with a name and a role with a colour
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        area.setFont(area.getFont().deriveFont(14f));
        wire(area, "/help");
        for (Role role : List.of(Role.DIRECTIVE, Role.OPTION_KEY, Role.OPTION_VALUE, Role.STRING,
                Role.NUMBER, Role.PATH, Role.SUBCOMMAND, Role.COMMENT, Role.UNKNOWN_DIRECTIVE)) {
            Style style = area.getSyntaxScheme()
                    .getStyle(ComposerTokenMaker.typeOf(role));
            assertNotNull(style, "no style slot for " + role);
            assertEquals(ComposerSyntax.colorFor(role), style.foreground,
                    role + " is not painted with the colour ComposerSyntax claims");
        }
        // and the roles that are NOT coloured stay the component's own foreground
        assertNull(ComposerSyntax.colorFor(Role.TEXT), "TEXT must inherit, not invent a colour");
    }

    @Test
    void nothingIsColouredWhenTheScanCannotRunAndTheFailureIsKept() {
        RSyntaxTextArea area = new RSyntaxTextArea(4, 60);
        area.setText("/assert 42");
        ComposerSyntax syntax = ComposerSyntax.install(area, () -> {
            throw new IllegalStateException("the session went away");
        });
        // the line still paints, in the default colour, and the reason is available rather than lost
        List<String> painted = tokensOf(area, 0);
        assertEquals(List.of("IDENTIFIER@0-10('/assert 42')"), painted);
        // RSTA asks what the last token on a line was as well as painting it, so one line is two scans
        assertTrue(syntax.describeFailure().contains("scan failures"), syntax.describeFailure());
        assertTrue(syntax.describeFailure().contains("the session went away"),
                syntax.describeFailure());
    }
}
