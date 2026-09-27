package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.impl.cmdline.NaruNArgCompleteResolver;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResolver;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NIO;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab completion in the REPL had silently stopped working while the resolver
 * itself kept compiling fine, because nothing was left referencing it: the call
 * that registers it with the console was dropped during a refactor and the class
 * became dead code. Nothing failed, and the only symptom was a dead Tab key.
 *
 * <p>The wiring test at the bottom is the one that matters; the rest pins the
 * behaviour so a future refactor that does drop the registration is noticed here
 * rather than by hand at a prompt.
 */
public class NaruNArgCompleteResolverTest {

    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @BeforeEach
    public void setUp() {
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-complete-test"));
        session = agent.newSession()
                .build();
    }

    private List<String> candidates(String... words) {
        NCmdLine cmdLine = NCmdLine.of(words);
        // completion always happens at the end of what has been typed, so the
        // position has to be built from the words rather than read back
        int last = words.length - 1;
        int cursor = 0;
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                cursor++;
            }
            cursor += words[i].length();
        }
        NArgCompletePosition pos = NArgCompletePosition.of(last, words[last].length(), cursor);
        NArgCompleteResult result = new NaruNArgCompleteResolver(session)
                .resolveCandidates(cmdLine.completePosition(pos), pos);
        List<String> out = new ArrayList<>();
        for (NArgCompleteCandidate c : result.candidates()) {
            out.add(c.value());
        }
        return out;
    }

    @Test
    public void anEmptyLineOffersDirectives() {
        List<String> c = candidates("");
        assertFalse(c.isEmpty(), "a bare prompt must offer something to complete");
        assertTrue(c.contains("/help"), "directives should be offered, got " + c);
    }

    @Test
    public void statementKeywordsAreOffered() {
        // every statement keyword the engine understands has to be reachable from
        // the prompt, or it is effectively undocumented
        List<String> c = candidates("");
        for (String keyword : NaruStatementHelper.STATEMENT_KEYWORDS) {
            assertTrue(c.contains("/" + keyword), "missing keyword /" + keyword + " in " + c);
        }
    }

    @Test
    public void candidatesAreSorted() {
        // an unsorted list is unpleasant to scan in a terminal, and the
        // directives are the part a user actually skims
        List<String> directives = new ArrayList<>();
        for (String c : candidates("")) {
            if (session.registry().directives().containsKey(c.substring(1))) {
                directives.add(c);
            }
        }
        assertFalse(directives.isEmpty(), "expected some directives to be registered");
        List<String> sorted = new ArrayList<>(directives);
        sorted.sort(String::compareTo);
        assertEquals(sorted, directives, "directive candidates should be sorted");
    }

    @Test
    public void anArgumentPositionIsDelegatedToTheDirective() {
        // the first word is a command, so an argument position must hand over to
        // the directive rather than re-offering the top-level command list
        List<String> c = candidates("/help", "");
        assertFalse(c.contains("/assert"),
                "argument position must delegate to the directive, not list commands: " + c);
    }

    @Test
    public void aPartialDirectiveIsCompleted() {
        // typing "/he" then Tab should narrow to /help
        List<String> all = candidates("/he");
        assertTrue(all.contains("/help"), "expected /help among " + all);
        assertFalse(all.contains("/mode"), "a non-matching directive must be filtered out");
    }

    @Test
    public void onlyPrefixMatchesAreOffered() {
        // an exact match is a legitimate candidate to return, but nothing that
        // cannot extend what was typed may be offered
        List<String> c = candidates("/hel");
        assertTrue(c.contains("/help"), "expected /help among " + c);
        for (String candidate : c) {
            assertTrue(candidate.startsWith("/hel"),
                    "candidate " + candidate + " does not extend /hel: " + c);
        }
    }

    @Test
    public void anExactDirectiveMatchIsReturned() {
        assertEquals(List.of("/help"), candidates("/help"));
    }

    @Test
    public void anUnknownDirectiveOffersNothing() {
        assertTrue(candidates("/definitely-not-a-directive").isEmpty());
    }

    @Test
    public void plainTextIsNotCompleted() {
        // this is a REPL where most input is a natural-language task, not a
        // command; offering slash commands for ordinary prose would be noise
        assertTrue(candidates("please", "fix", "the", "bug").isEmpty(),
                "a first word that is not a command must not be completed");
        assertTrue(candidates("hello").isEmpty());
    }

    // ── the wiring itself ───────────────────────────────────────────────────

    @Test
    public void buildingARichTerminalSessionRegistersTheCompleter() {
        NArgCompleteResolver previous = NIO.of().systemTerminal().autoCompleteResolver();
        try {
            NaruAgent agent = new NaruAgentImpl();
            agent.projectDirectory(NPath.ofTempFolder("naru-complete-wiring"));
            agent.newSession()
                    .richTerm(true)
                    .build();
            NArgCompleteResolver registered = NIO.of().systemTerminal().autoCompleteResolver();
            assertNotNull(registered,
                    "no completer on the system terminal means Tab does nothing in the REPL");
            assertTrue(registered instanceof NaruNArgCompleteResolver,
                    "expected the naru resolver, got " + registered.getClass().getName());
        } finally {
            if (previous != null) {
                NIO.of().systemTerminal().commandAutoCompleteResolver(previous);
            }
        }
    }

    @Test
    public void aHeadlessSessionDoesNotGrabTheConsole() {
        // the registration is process-global, so a session that does not own the
        // console must leave it alone
        NArgCompleteResolver previous = NIO.of().systemTerminal().autoCompleteResolver();
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-complete-headless"));
        agent.newSession()
                .richTerm(false)
                .build();
        assertEquals(previous, NIO.of().systemTerminal().autoCompleteResolver(),
                "a headless session must not take over the shared terminal");
    }
}
