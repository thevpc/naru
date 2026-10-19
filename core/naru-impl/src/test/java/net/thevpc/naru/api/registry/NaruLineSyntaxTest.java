package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruLineSyntax.Role;
import net.thevpc.naru.api.registry.NaruLineSyntax.Span;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The typed spans of an input line, so a GUI can colour the same grammar the terminal colours.
 * <p>
 * {@link NaruTerminalFormatter} has always painted {@code /help} blue and an unknown command red, but
 * it returns a styled {@code NText} with no offsets, which a Swing widget cannot use. These tests pin
 * the two halves of that gap: that the roles match what the terminal already decided, and that the
 * offsets land on the exact characters, because a colour painted one off is worse than no colour.
 */
public class NaruLineSyntaxTest {

    @BeforeAll
    public static void setUpNuts() {
        Nuts.require();
    }

    private static NaruSession session() {
        NPath dir = NPath.ofTempFolder("naru-line-syntax");
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession().directory(dir).configureDefaults(false).build();
        s.registry().registerDirectiveProvider(new net.thevpc.naru.impl.registry.NaruBuiltinDirectiveProvider());
        return s;
    }

    /** {@code ROLE@start-end('text')} per span: a failure names the span and shows it is well-formed. */
    private static List<String> describe(NaruSession s, String line) {
        List<String> out = new ArrayList<>();
        for (Span sp : NaruLineSyntax.scan(s, line)) {
            out.add(sp.role() + "@" + sp.start() + "-" + sp.end() + "('"
                    + line.substring(sp.start(), sp.end()) + "')");
        }
        return out;
    }

    private static void assertSpans(String line, String... expected) {
        Assertions.assertEquals(List.of(expected), describe(session(), line));
    }

    @Test
    public void aCommentIsTheWholeLineAndNothingElse() {
        assertSpans("# not a command", "COMMENT@0-15('# not a command')");
        assertSpans("// the other prefix the parser accepts",
                "COMMENT@0-38('// the other prefix the parser accepts')");
        // indented, because a routine body is indented and still a comment
        assertSpans("   # spaced out", "COMMENT@0-15('   # spaced out')");
    }

    @Test
    public void aKnownHeadIsBlueAndAnUnknownOneIsRed() {
        assertSpans("/help", "DIRECTIVE@0-5('/help')");
        assertSpans("/naru-no-such-command arg",
                "UNKNOWN_DIRECTIVE@0-21('/naru-no-such-command')", "TEXT@22-25('arg')");
    }

    @Test
    public void aBareSlashIsTheCommandListAndATrailingSlashIsStillTheCommand() {
        assertSpans("/", "DIRECTIVE@0-1('/')");
        // "/help/" is what a user types one keystroke before the space; the name before the slash is
        // what the registry knows, exactly as NaruTerminalFormatter.formatDirective tests it
        assertSpans("/help/", "DIRECTIVE@0-6('/help/')");
        // a half-typed name is red here and red in the terminal: the test is that hosts agree, not
        // that typing never looks wrong
        assertSpans("/hel", "UNKNOWN_DIRECTIVE@0-4('/hel')");
    }

    @Test
    public void anOptionAndItsInlineValueAreTwoSpans() {
        assertSpans("/assert --level=2 boom",
                "DIRECTIVE@0-7('/assert')",
                "OPTION_KEY@8-16('--level=')", "OPTION_VALUE@16-17('2')",
                // the value came after '=', so 'boom' is a positional word, not another value
                "TEXT@18-22('boom')");
        assertSpans("/assert --level 2 boom",
                "DIRECTIVE@0-7('/assert')",
                "OPTION_KEY@8-15('--level')", "OPTION_VALUE@16-17('2')", "TEXT@18-22('boom')");
    }

    @Test
    public void aQuotedArgumentIsAStringEvenWhenItLooksLikeAPath() {
        assertSpans("/assert \"a b\"", "DIRECTIVE@0-7('/assert')", "STRING@8-13('\"a b\"')");
        assertSpans("/assert \"/tmp/x\"", "DIRECTIVE@0-7('/assert')", "STRING@8-16('\"/tmp/x\"')");
    }

    @Test
    public void aNumberIsANumberWhereverItSits() {
        assertSpans("/assert 42", "DIRECTIVE@0-7('/assert')", "NUMBER@8-10('42')");
        assertSpans("/assert -3.5", "DIRECTIVE@0-7('/assert')", "NUMBER@8-12('-3.5')");
    }

    @Test
    public void aPathShapeWinsOverTheSubCommandGuess() {
        // nothing here knows /project's grammar, so the only signal is that the word looks like a path
        assertSpans("/project /tmp/work", "DIRECTIVE@0-8('/project')", "PATH@9-18('/tmp/work')");
        assertSpans("/project ~/work", "DIRECTIVE@0-8('/project')", "PATH@9-15('~/work')");
        assertSpans("/project list", "DIRECTIVE@0-8('/project')", "SUBCOMMAND@9-13('list')");
    }

    @Test
    public void proseHasNoNaruGrammarAndBecomesOneTextSpan() {
        assertSpans("explain the budget", "TEXT@0-18('explain the budget')");
        // a code snippet pasted into the composer is not re-parsed word by word either
        assertSpans("int x = 3 / 2;", "TEXT@0-14('int x = 3 / 2;')");
    }

    @Test
    public void whitespaceBetweenWordsIsLeftToTheCallersDefaultColour() {
        assertSpans("/assert   42", "DIRECTIVE@0-7('/assert')", "NUMBER@10-12('42')");
    }

    @Test
    public void anEmptyLineHasNoSpans() {
        Assertions.assertTrue(NaruLineSyntax.scan(session(), "").isEmpty());
        Assertions.assertTrue(NaruLineSyntax.scan(session(), "   ").isEmpty());
        Assertions.assertTrue(NaruLineSyntax.scan(session(), null).isEmpty());
    }

    @Test
    public void withoutASessionTheHeadIsUnknownButTheRestStillReads() {
        Assertions.assertEquals(
                List.of("UNKNOWN_DIRECTIVE@0-7('/assert')", "OPTION_KEY@8-15('--level')",
                        "OPTION_VALUE@16-17('2')"),
                describe(null, "/assert --level 2"));
    }

    @Test
    public void spansNeverOverlapAndAlwaysRunLeftToRight() {
        for (String line : List.of("/help", "/assert --level=2 \"a b\" /tmp/x 42 trailing",
                "# c", "plain prose here", "/unknown -f", "   ", "/")) {
            int previousEnd = 0;
            for (Span sp : NaruLineSyntax.scan(session(), line)) {
                Assertions.assertTrue(sp.start() >= previousEnd,
                        "spans overlapped or went backwards in '" + line + "': " + sp);
                Assertions.assertTrue(sp.start() <= sp.end() && sp.end() <= line.length(),
                        "span out of bounds in '" + line + "': " + sp);
                previousEnd = sp.end();
            }
        }
    }

    @Test
    public void theScannerAndTheCompleterSplitTheLineTheSameWay() {
        // one grammar, read by two features: the span the caret sits in must be exactly the range the
        // completer replaces, or the popup colours one word and rewrites another
        String line = "/assert --level=2 \"a b\" /tmp/x";
        List<Span> spans = NaruLineSyntax.scan(session(), line);
        Span last = spans.get(spans.size() - 1);
        Assertions.assertEquals(Role.PATH, last.role());
        NaruLineCompletion.Result r = NaruLineCompletion.complete(session(), line, line.length());
        Assertions.assertEquals(last.start(), r.replaceStart(), "same word start");
        Assertions.assertEquals(last.end(), r.replaceEnd(), "same word end");
    }
}
