package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruLineCompletion.Candidate;
import net.thevpc.naru.api.registry.NaruLineCompletion.Kind;
import net.thevpc.naru.api.registry.NaruLineCompletion.Result;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * E2: completing a raw line at a caret, with no terminal in sight.
 * <p>
 * The console resolver only ever receives a parsed {@code NCmdLine}; a GUI has a string and a
 * caret. These tests pin the three things the desktop input field depends on: an empty field offers
 * commands, a {@code /name} token that is also a real path reports both instead of silently picking
 * one, and an argument falls through to the directive that owns it before anyone guesses at a file.
 */
public class NaruLineCompletionTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSession sessionAt(NPath dir) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .directory(dir)
                .configureDefaults(false)
                .build();
        // completion is a question about the registry, and a session built with
        // configureDefaults(false) has an empty one -- the terminal gets its directives from the
        // profile/extensions the app installs, so the test registers the built-in set itself
        s.registry().registerDirectiveProvider(new net.thevpc.naru.impl.registry.NaruBuiltinDirectiveProvider());
        return s;
    }

    @Test
    public void anEmptyFieldOffersCommandsNotFiles() {
        NaruSession s = sessionAt(NPath.ofTempFolder("naru-complete-empty"));
            Result r = NaruLineCompletion.complete(s, "", 0);
            Assertions.assertFalse(r.isEmpty(), "a blank input field must suggest directives");
            Assertions.assertTrue(r.candidates().stream().allMatch(c -> c.kind() == Kind.DIRECTIVE),
                    "a blank field is a command prompt, not a directory listing");
            Assertions.assertTrue(r.candidates().stream().anyMatch(c -> c.insert().equals("/help")),
                    "/help is registered and must be offered");
    }

    @Test
    public void aPrefixOnlyOffersMatchingCommands() {
        NaruSession s = sessionAt(NPath.ofTempFolder("naru-complete-prefix"));
            Result r = NaruLineCompletion.complete(s, "/he", 3);
            Assertions.assertEquals("/he", r.token());
            Assertions.assertEquals(0, r.replaceStart(), "the whole token is replaced");
            Assertions.assertEquals(3, r.replaceEnd());
            Assertions.assertFalse(r.isEmpty());
            Assertions.assertTrue(r.candidates().stream()
                    .allMatch(c -> c.insert().toLowerCase().startsWith("/he")),
                    "every candidate must extend what the user typed: " + r.candidates());
    }

    @Test
    public void proseIsNotCompleted() {
        NaruSession s = sessionAt(NPath.ofTempFolder("naru-complete-prose"));
            Assertions.assertTrue(NaruLineCompletion.complete(s, "refactor the parser", 19).isEmpty(),
                    "free text goes to the model, it has no completion");
    }

    @Test
    public void aSlashTokenThatIsAlsoAPathReportsBoth() throws IOException {
        NPath dir = NPath.ofTempFolder("naru-complete-ambig");
        dir.mkdirs();
        // a file whose name also names a real directive: /help is a command, /heloc is a file
        dir.resolve("heloc.txt").writeString("x");
        NaruSession s = sessionAt(dir);
            Result r = NaruLineCompletion.complete(s, "/heloc", 6);
            Assertions.assertTrue(r.candidates().stream().anyMatch(c -> c.insert().contains("heloc.txt")),
                    "a token that names an existing file must offer it: " + r.candidates());

            Result exact = NaruLineCompletion.complete(s, "/help", 5);
            Assertions.assertTrue(exact.candidates().stream().anyMatch(c -> c.insert().equals("/help")));
            Assertions.assertTrue(exact.candidates().stream().allMatch(c -> c.kind() == Kind.DIRECTIVE),
                    "an exact directive name wins over anything on disk: " + exact.candidates());
    }

    @Test
    public void caretInMiddleOfAWordOnlyCompletesThatWord() {
        NaruSession s = sessionAt(NPath.ofTempFolder("naru-complete-mid"));
            Result r = NaruLineCompletion.complete(s, "/help /mode", 5);
            Assertions.assertEquals("/help", r.token(), "the caret sits inside the first word");
            Assertions.assertEquals(0, r.replaceStart());
            Assertions.assertEquals(5, r.replaceEnd(), "only the word under the caret is replaced");
    }

    @Test
    public void anArgumentGoesToTheDirectiveThatOwnsIt() {
        NaruSession s = sessionAt(NPath.ofTempFolder("naru-complete-arg"));
            // /help completes directive names at word 1 (NaruHelpDirective.resolveCandidates),
            // and it expects the bare word -- the leading slash belongs to the command, not the arg
            Result r = NaruLineCompletion.complete(s, "/help pro", 9);
            Assertions.assertEquals("pro", r.token());
            Assertions.assertFalse(r.isEmpty(),
                    "the directive's own candidates must be used before falling back to files");
            Assertions.assertEquals(Kind.DIRECTIVE_ARGUMENT, r.candidates().get(0).kind());
            Assertions.assertTrue(r.candidates().stream().anyMatch(c -> c.insert().equals("project")),
                    "the directive's own list must come through: " + r.candidates());
            Assertions.assertEquals(6, r.replaceStart(), "the replacement covers only the argument");
    }

    @Test
    public void anUnknownArgumentFallsBackToTheFileSystem() throws IOException {
        NPath dir = NPath.ofTempFolder("naru-complete-fb");
        dir.mkdirs();
        dir.resolve("notes.txt").writeString("x");
        NaruSession s = sessionAt(dir);
            Result r = NaruLineCompletion.complete(s, "n", 1);
            List<Candidate> inDir = NaruLineCompletion.complete(s, dir.toString() + "/n",
                    (dir.toString() + "/n").length()).candidates();
            Assertions.assertTrue(inDir.stream().anyMatch(c -> c.insert().endsWith("notes.txt")),
                    "a path token completes against the working directory: " + inDir);
            Assertions.assertTrue(r.isEmpty(), "a bare word with no leading / is prose, not a path");
    }

    @Test
    public void theTokenizerTracksWhereTheCaretIs() {
        // "/help " with the caret after the space is word 1 with an empty token: an argument slot
        Assertions.assertEquals(1, NaruLineCompletion.tokenize("/help ").index);
        Assertions.assertEquals("", NaruLineCompletion.tokenize("/help ").currentToken);
        Assertions.assertEquals(1, NaruLineCompletion.tokenize("/help /mod").index);
        Assertions.assertEquals("/mod", NaruLineCompletion.tokenize("/help /mod").currentToken);
        Assertions.assertEquals(1, NaruLineCompletion.tokenize("/help \"a b").index);
        Assertions.assertEquals("\"a b", NaruLineCompletion.tokenize("/help \"a b").currentToken,
                "a quoted argument is one token even with a space inside");
        Assertions.assertEquals(List.of("/help"), NaruLineCompletion.tokenize("/help ").words);
    }

    /**
     * The session method is the shape a host wants to call on every keystroke; the static is the
     * shape a caller with no session-as-receiver wants. They must not drift.
     */
    @Test
    public void theSessionMethodAndTheStaticAgree() {
        NaruSession s = sessionAt(NPath.ofTempFolder("naru-complete-session-method"));
        // called through the interface, which is how a GUI sees it
        NaruSession asInterface = s;
        Assertions.assertEquals(NaruLineCompletion.complete(s, "/he", 3), asInterface.complete("/he", 3),
                "session.complete must be the static with the session as an argument, nothing more");
        Assertions.assertFalse(asInterface.complete("", 0).isEmpty(),
                "the delegation has to reach the command listing, not come back blank");
        Assertions.assertTrue(asInterface.complete("hello there", 11).isEmpty(),
                "prose stays prose through either door");
    }
}
