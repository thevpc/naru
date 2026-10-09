package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.routine.NaruStmtResultType;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.cmdline.NaruNArgCompleteResolver;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives /session the way the REPL does, so a subcommand that is broken is caught here
 * rather than by typing it at a prompt.
 */
@Timeout(60)
public class NaruSessionDirectiveTest {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private NPath projectDir;
    private NaruAgentImpl agent;
    private NaruSessionImpl session;

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Throwable e) {
            System.err.println("[first workspace attempt failed]");
            e.printStackTrace();
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Throwable ignored) {
                System.err.println("[second workspace attempt failed]");
                ignored.printStackTrace();
            }
        }
    }

    @BeforeEach
    public void setUp() {
        projectDir = NPath.ofTempFolder("naru-session-directive-" + System.nanoTime());
        agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        // configureDefaults=false: no SPI discovery, no network, no real models. The test
        // registers exactly the directive provider and the offline model it needs, so a
        // task can always be spawned and nothing here depends on the machine it runs on.
        session = newSession("arch-review");
    }

    /** A session sharing this test's project directory, and so its saved-session catalog. */
    private NaruSessionImpl newSession(String name) {
        NaruSessionImpl s = new NaruSessionImpl(agent, projectDir, new NaruStreamInteraction(o -> {
        }), false, NOOP_LISTENER, null, null, null);
        s.registry().registerDirectiveProvider(new NaruSessionsDirectiveProvider());
        s.registry().registerModelProvider(new OfflineProvider());
        s.name(name);
        return s;
    }

    /**
     * A session that exists only in the on-disk catalog: it is created and saved, but never
     * started, so {@link NaruAgent#sessions()} does not know it. This is the fallback half
     * of the id/name selection.
     */
    private NaruSessionImpl savedSession(String name) {
        NaruSessionImpl s = newSession(name);
        s.save();
        return s;
    }

    /** The shortest prefix of {@code uuid} that does not also prefix {@code other}. */
    private static String uniquePrefix(String uuid, String other) {
        int n = 1;
        while (n < uuid.length()
                && other.toLowerCase().startsWith(uuid.substring(0, n).toLowerCase())) {
            n++;
        }
        return uuid.substring(0, n);
    }

    private NaruResourceInfo savedEntry(String uuid) {
        return session.sessionStoreManager().list().stream()
                .filter(x -> uuid.equals(x.getUuid()))
                .findFirst().orElse(null);
    }

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruStmtResult call(String argument) {
        NaruTask task = task();
        NaruDirective directive = session.registry().findDirective("session")
                .orElseThrow(() -> new AssertionError("no /session directive"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("session", argument, task);
        return directive.execute(ctx);
    }

    @Test
    public void listingSavedSessionsSucceeds() {
        session.save();
        assertFalse(session.sessionStoreManager().list().isEmpty(), "fixture should save a session");

        NaruStmtResult r = call("list");

        assertEquals(0, r.exitCode(), "/session list must not fail: " + r.errorValue());
        assertTrue(r.successValue() instanceof String, "expected the listing as a string value");
        assertFalse(((String) r.successValue()).isEmpty(), "an empty listing means nothing was printed");
    }

    /**
     * The catalogue is shared by delete/load/continue: if one unreadable session file on
     * disk can break listing, every one of those stops working too.
     */
    @Test
    public void listingSurvivesAnUnreadableSessionFolder() {
        session.save();
        NPath junk = projectDir.resolve(".naru/local/sessions/aaaaaaaa-bogus");
        junk.mkParentDirs().mkdirs();
        junk.resolve("session.tson").writeText(net.thevpc.nuts.text.NText.ofPlain("{ this is not tson"));

        NaruStmtResult r = call("list");

        assertEquals(0, r.exitCode(), "one bad folder must not break /session list: " + r.errorValue());
    }

    @Test
    public void loadAndDeleteCompleteTheirSessionArgument() {
        session.save();

        for (String sub : new String[]{"load", "delete"}) {
            List<String> values = complete("/session", sub, "");
            assertTrue(values.contains("arch-review"),
                    "/" + sub + " must offer saved session names, got " + values);
        }
    }

    private List<String> complete(String... words) {
        NCmdLine cmdLine = NCmdLine.of(words);
        int last = words.length - 1;
        NArgCompletePosition pos = NArgCompletePosition.of(last, words[last].length(), 0);
        NArgCompleteResult result = new NaruNArgCompleteResolver(session)
                .resolveCandidates(cmdLine.completePosition(pos), pos);
        List<String> out = new ArrayList<>();
        for (NArgCompleteCandidate c : result.candidates()) {
            out.add(c.value());
        }
        return out;
    }

    /**
     * A stop-the-world that waits for the workers cannot be requested by a worker: the
     * thread it is waiting for is the one waiting for it. /session restore used to do
     * exactly that and wedged the whole scheduler.
     */
    @Test
    public void restoreFromInsideATaskDoesNotDeadlock() {
        // a fresh task starts held (the session builder is what releases it); release it
        // here so the scheduler actually runs the statement
        NaruTask t = session.newTask(NaruTaskSpec.of().statements("/session restore")).unhold();
        session.start();
        session.waitFor();
        assertEquals(NaruTaskStatus.DONE, t.status(),
                "the restore task must complete instead of hanging the scheduler");
    }

    @Test
    public void saveFromInsideATaskDoesNotDeadlock() {
        NaruTask t = session.newTask(NaruTaskSpec.of().statements("/session save")).unhold();
        session.start();
        session.waitFor();
        assertEquals(NaruTaskStatus.DONE, t.status(),
                "the save task must complete instead of hanging the scheduler");
    }

    @Test
    public void listFromInsideATaskWorks() {
        session.save();
        NaruTask t = session.newTask(NaruTaskSpec.of().statements("/session list")).unhold();
        session.start();
        session.waitFor();
        assertEquals(NaruTaskStatus.DONE, t.status(),
                "the list task must complete");
    }

    // ── --id / --name selection ──────────────────────────────────────────────

    /**
     * The user's literal example: rename a saved session by a unique id prefix without
     * having to start it first.
     */
    @Test
    public void renameSavedSessionByUniqueIdPrefix() {
        NaruSessionImpl saved = savedSession("old-name");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("rename --id " + prefix + " new-name");

        assertEquals(0, r.exitCode(), () -> "rename by id must succeed: " + r.errorValue());
        assertEquals("new-name", savedEntry(saved.uuid()).getName(),
                "the saved session must have been renamed in the catalog");
        assertEquals("arch-review", session.name(),
                "renaming another session must not touch the current one");
    }

    /**
     * When the same uuid is both live and saved, the live object is the one renamed: the
     * catalog entry follows it, and no second copy is left behind. The current session is
     * such a case as soon as it has been saved.
     */
    @Test
    public void renamePrefersTheLiveSessionOverItsSavedEntry() {
        session.save();
        assertNotNull(savedEntry(session.uuid()), "fixture must be saved as well as live");
        String prefix = session.uuid().substring(0, 8);

        NaruStmtResult r = call("rename --id " + prefix + " live-renamed");

        assertEquals(0, r.exitCode(), () -> "rename by id must succeed: " + r.errorValue());
        assertEquals("live-renamed", session.name(), "the live object must carry the new name");
        // renaming a live session persists asynchronously; flush it before reading the disk
        session.save();
        assertEquals("live-renamed", savedEntry(session.uuid()).getName(),
                "the catalog entry must follow the live object");
    }

    @Test
    public void renameSavedSessionByName() {
        NaruSessionImpl saved = savedSession("old-name");

        NaruStmtResult r = call("rename --name old-name new-name");

        assertEquals(0, r.exitCode(), () -> "rename by name must succeed: " + r.errorValue());
        assertEquals("new-name", savedEntry(saved.uuid()).getName(),
                "the saved session must have been renamed in the catalog");
    }

    /** The terse spelling from the request ({@code -id aa}) must work too. */
    @Test
    public void terseIdOptionIsAccepted() {
        NaruSessionImpl saved = savedSession("old-name");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("rename -id " + prefix + " new-name");

        assertEquals(0, r.exitCode(), () -> "the terse -id spelling must work: " + r.errorValue());
        assertEquals("new-name", savedEntry(saved.uuid()).getName(),
                "the saved session must have been renamed in the catalog");
    }

    /** The same terse spelling for {@code -name}. */
    @Test
    public void terseNameOptionIsAccepted() {
        NaruSessionImpl saved = savedSession("old-name");

        NaruStmtResult r = call("rename -name old-name new-name");

        assertEquals(0, r.exitCode(), () -> "the terse -name spelling must work: " + r.errorValue());
        assertEquals("new-name", savedEntry(saved.uuid()).getName(),
                "the saved session must have been renamed in the catalog");
    }

    @Test
    public void unknownIdIsAnErrorNotAGuess() {
        NaruSessionImpl saved = savedSession("old-name");

        NaruStmtResult r = call("rename --id deadbeef ghost");

        assertEquals(NaruStmtResultType.ERROR, r.type(),
                () -> "an id that matches nothing must fail, got: " + r);
        assertEquals("old-name", savedEntry(saved.uuid()).getName(),
                "a failed rename must leave the catalog alone");
    }

    /**
     * An ambiguous name must be reported, never resolved to whichever session happens to
     * be first: silently renaming the wrong session is worse than refusing.
     */
    @Test
    public void ambiguousNameIsReported() {
        NaruSessionImpl first = savedSession("dup");
        NaruSessionImpl second = savedSession("dup");

        NaruStmtResult r = call("rename --name dup ghost");

        assertEquals(NaruStmtResultType.ERROR, r.type(), () -> "ambiguous name must fail: " + r);
        assertTrue(String.valueOf(r.errorValue()).contains("ambiguous"),
                () -> "the error must say it is ambiguous: " + r.errorValue());
        assertEquals("dup", savedEntry(first.uuid()).getName(), "the first must be untouched");
        assertEquals("dup", savedEntry(second.uuid()).getName(), "the second must be untouched");
    }

    @Test
    public void currentShowsASessionPickedByIdPrefix() {
        NaruSessionImpl saved = savedSession("other");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("current --id " + prefix);

        assertEquals(0, r.exitCode(), () -> "current by id must succeed: " + r.errorValue());
        assertTrue(String.valueOf(r.successValue()).contains(saved.uuid()),
                () -> "the selected session's uuid must be shown: " + r.successValue());
    }

    @Test
    public void visibilityOfASavedSessionCanBeChanged() {
        NaruSessionImpl saved = savedSession("share-me");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());
        assertEquals(NaruVisibility.PRIVATE, savedEntry(saved.uuid()).getMode());

        NaruStmtResult toPublic = call("public --id " + prefix);
        assertEquals(0, toPublic.exitCode(), () -> "make public must succeed: " + toPublic.errorValue());
        assertEquals(NaruVisibility.PUBLIC, savedEntry(saved.uuid()).getMode(),
                "the saved session must now be public");

        NaruStmtResult back = call("private --name share-me");
        assertEquals(0, back.exitCode(), () -> "make private must succeed: " + back.errorValue());
        assertEquals(NaruVisibility.PRIVATE, savedEntry(saved.uuid()).getMode(),
                "the saved session must be private again");
    }

    @Test
    public void deleteSelectsASavedSessionByIdPrefix() {
        NaruSessionImpl saved = savedSession("gone");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("delete --id " + prefix);

        assertEquals(0, r.exitCode(), () -> "delete by id must succeed: " + r.errorValue());
        assertNull(savedEntry(saved.uuid()), "the saved session must be gone from the catalog");
    }

    @Test
    public void loadSelectsASavedSessionByIdPrefix() {
        NaruSessionImpl saved = savedSession("destination");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("load --id " + prefix);

        assertEquals(0, r.exitCode(), () -> "load by id must succeed: " + r.errorValue());
        assertEquals(saved.uuid(), session.uuid(), "the current session must have loaded the target");
        assertEquals("destination", session.name(), "the loaded session's name must be adopted");
    }

    @Test
    public void subcommandsThatActOnEverythingRejectSelectors() {
        savedSession("anything");
        for (String sub : new String[]{"list", "purge", "new"}) {
            NaruStmtResult r = call(sub + " --id deadbeef");
            assertEquals(NaruStmtResultType.ERROR, r.type(),
                    () -> "/session " + sub + " must reject --id, got: " + r);
            assertTrue(String.valueOf(r.errorValue()).contains("does not accept"),
                    () -> "the rejection must say why: " + r.errorValue());
        }
    }

    @Test
    public void saveOnASavedOnlySessionIsANoOp() {
        NaruSessionImpl saved = savedSession("already-saved");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("save --id " + prefix);

        assertEquals(0, r.exitCode(), () -> "save on a saved session must succeed: " + r.errorValue());
        assertNotNull(savedEntry(saved.uuid()), "the saved session must still be there");
    }

    @Test
    public void resetOnASavedOnlySessionAsksForARunningOne() {
        NaruSessionImpl saved = savedSession("not-running");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        NaruStmtResult r = call("reset --id " + prefix);

        assertEquals(NaruStmtResultType.ERROR, r.type(),
                () -> "reset needs a running session, got: " + r);
        assertTrue(String.valueOf(r.errorValue()).contains("not running"),
                () -> "the error must say the session is not running: " + r.errorValue());
    }

    // ── autocomplete ─────────────────────────────────────────────────────────

    /** The request's exact case: {@code --id=} must complete the id from the catalog. */
    @Test
    public void inlineIdOptionCompletesTheUuid() {
        NaruSessionImpl saved = savedSession("inline-id");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        List<String> values = complete("/session", "current", "--id=" + prefix);

        assertTrue(values.contains("--id=" + saved.uuid()),
                "typing --id=<prefix> must offer the full uuid, got " + values);
    }

    /** {@code --id <prefix>} as two words must complete the id too. */
    @Test
    public void separateIdOptionCompletesTheUuid() {
        NaruSessionImpl saved = savedSession("separate-id");
        String prefix = uniquePrefix(saved.uuid(), session.uuid());

        List<String> values = complete("/session", "current", "--id", prefix);

        assertTrue(values.contains(saved.uuid()),
                "typing --id <prefix> must offer the uuid, got " + values);
    }

    @Test
    public void inlineNameOptionCompletesTheName() {
        savedSession("comp-inline");

        List<String> values = complete("/session", "current", "--name=comp");

        assertTrue(values.contains("--name=comp-inline"),
                "typing --name=<prefix> must offer the name, got " + values);
    }

    /**
     * Every subcommand that accepts a target offers the selectors, not just the three that
     * happened to register completion before.
     */
    @Test
    public void everyTargetSubcommandOffersTheSelectors() {
        session.save();
        for (String sub : new String[]{"current", "save", "restore", "reset",
                "copy", "public", "private", "delete", "load", "rename"}) {
            List<String> values = complete("/session", sub, "");
            assertTrue(values.contains("--id") && values.contains("--name"),
                    "/session " + sub + " must offer --id/--name, got " + values);
        }
    }

    /** A non-target subcommand must keep delegating to the base resolver. */
    @Test
    public void listDoesNotOfferSelectors() {
        List<String> values = complete("/session", "list", "");

        assertFalse(values.contains("--id"), "/session list must not offer --id, got " + values);
    }

    // ── list rendering ───────────────────────────────────────────────────────

    @Test
    public void listMarksTheCurrentSessionAndCountsLiveTasks() {
        session.save();
        // a held task keeps the session alive for the duration of the listing; the
        // /session list task itself is a second registered task
        NaruTask held = session.newTask(NaruTaskSpec.of().statements("/return 1"));
        session.start();
        try {
            NaruStmtResult r = call("list");
            String s = String.valueOf(r.successValue());
            assertTrue(s.contains("*"), "the current session must be marked with '*': " + s);
            assertTrue(s.contains("live"), "a running session must be marked live: " + s);
            assertTrue(s.contains("task"), "a live session must report its task count: " + s);
            assertFalse(s.contains("()"), "an unknown age must not print empty parentheses: " + s);
        } finally {
            held.kill();
            session.stop();
        }
    }

    @Test
    public void listShowsSavedOnlySessions() {
        savedSession("only-saved");

        NaruStmtResult r = call("list");
        String s = String.valueOf(r.successValue());

        assertTrue(s.contains("only-saved"), "a saved session must be listed: " + s);
        assertTrue(s.contains("saved"), "a saved-only session must be marked saved: " + s);
        assertFalse(s.contains("()"), "no row may print empty parentheses: " + s);
    }

    /**
     * A single offline, tools-capable model so that {@code newTask} always finds something
     * to attach to a task. It never chats: the tests here drive /session, not the model.
     */
    private static final class OfflineProvider extends AbstractNaruModelProvider {
        OfflineProvider() {
            super("offline", new String[0]);
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of("offline-model");
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.of(new NaruModelProtocol() {
                @Override
                public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                    throw new UnsupportedOperationException("offline test model never chats");
                }

                @Override
                public NaruModelCapabilities getCapabilities() {
                    return new NaruModelCapabilities() {
                        @Override
                        public long contextLength() {
                            return 4096;
                        }

                        @Override
                        public boolean isVision() {
                            return false;
                        }

                        @Override
                        public boolean isTools() {
                            return true;
                        }

                        @Override
                        public boolean isThinking() {
                            return false;
                        }

                        @Override
                        public boolean isEmbedding() {
                            return false;
                        }

                        @Override
                        public boolean isTextOnly() {
                            return true;
                        }

                        @Override
                        public NaruCachingMode cachingMode() {
                            return NaruCachingMode.NONE;
                        }

                        @Override
                        public Set<String> keys() {
                            return Set.of();
                        }

                        @Override
                        public NElement toElement() {
                            return NElement.ofNull();
                        }
                    };
                }
            });
        }
    }
}
