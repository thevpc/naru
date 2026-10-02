package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryInfo;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruSummaryState;
import net.thevpc.naru.api.model.NaruSummaryTrigger;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo and staleness against a real task, because both rewrite history and neither can be
 * checked by reading the code.
 *
 * <p>No summarizer and no model: a summary item is written directly, which is the state the
 * compactor produces anyway. What is under test is what happens to the task afterwards.
 */
class NaruCompactCompactionStateTest {

    private NaruSession session;
    private NaruCompactContextCompactor compactor;

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
        agent.projectDirectory(NPath.ofTempFolder("naru-compact-state"));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
        compactor = new NaruCompactContextCompactor();
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private NaruTask taskWith(List<NaruMessage> history) {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setHistory(history);
        return task;
    }

    private static List<NaruMessage> covered(String... texts) {
        List<NaruMessage> out = new ArrayList<>();
        for (String t : texts) {
            out.add(NaruMessage.user(t));
        }
        return out;
    }

    private static NaruSummaryInfo info(String id, List<NaruMessage> covered, boolean truncated) {
        return new NaruSummaryInfo(id, null, null, covered.size(),
                NaruCompactTokens.estimate(covered), 42,
                NaruCompactCacheKey.contentHash(covered),
                NaruSummaryLevel.NORMAL, null, null, "test/model", Instant.now(),
                NaruSummaryTrigger.MANUAL, NaruSummaryState.ACTIVE, truncated);
    }

    /** A history with a summary standing in for two earlier items. */
    private static List<NaruMessage> compacted(String id, String... texts) {
        List<NaruMessage> covered = covered(texts);
        List<NaruMessage> history = new ArrayList<>(covered);
        for (NaruMessage m : covered) {
            m.setExcludedBy(id);
        }
        history.add(NaruMessage.summary("the gist", info(id, covered, false)));
        history.add(NaruMessage.user("recent"));
        return history;
    }

    // ── undo ─────────────────────────────────────────────────────────────────

    @Test
    void undoRestoresEveryCoveredItemAndRetiresTheSummary() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        NaruCompactionResult r = compactor.undo(task, "S1");
        assertNotNull(r);

        List<NaruMessage> history = task.history();
        assertEquals(4, history.size(), "nothing is ever removed, so undo adds items back");
        assertTrue(history.stream().noneMatch(NaruMessage::isExcluded));
        assertEquals(3, net.thevpc.naru.api.model.NaruContextViews.contextView(history).size());
        for (NaruMessage m : history) {
            if (m.isSummary()) {
                assertEquals(NaruSummaryState.UNDONE, m.getSummary().state(),
                        "a summary left ACTIVE would be sent alongside the items it replaced");
            }
        }
    }

    @Test
    void undoIsKeyedByIdNotPosition() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        compactor.undo(task, "S1");
        // a second, older summary in the same history must survive
        NaruTask twoSummaries = taskWith(compacted("S1", "one"));
        List<NaruMessage> more = new ArrayList<>(twoSummaries.history());
        more.addAll(compacted("S2", "three", "four"));
        twoSummaries.setHistory(more);
        compactor.undo(twoSummaries, "S2");
        assertEquals(1, net.thevpc.naru.api.model.NaruContextViews
                .activeSummaries(twoSummaries.history()).size());
        assertTrue(twoSummaries.history().stream()
                        .anyMatch(m -> m.isSummary() && "S1".equals(m.getSummary().id())),
                "the untouched summary must still be there");
    }

    @Test
    void undoingAnUnknownIdFailsWithoutTouchingTheHistory() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        List<NaruMessage> before = task.history();
        NaruCompactionResult r = compactor.undo(task, "nope");
        assertEquals(NaruCompactionResult.Outcome.FAILED, r.outcome());
        assertEquals(before.size(), task.history().size());
        assertEquals(1, net.thevpc.naru.api.model.NaruContextViews
                .activeSummaries(task.history()).size());
    }

    @Test
    void undoNeedsBothATaskAndAnId() {
        assertNotNull(compactor.undo(null, "S1"));
        assertNotNull(compactor.undo(taskWith(compacted("S1", "a")), null));
    }

    // ── staleness ────────────────────────────────────────────────────────────

    @Test
    void anUntouchedSummaryIsNotStale() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        assertEquals(List.of(), compactor.reconcileStale(task, NaruCompactConfig.StalePolicy.deactivate));
        assertEquals(NaruSummaryState.ACTIVE, summaryOf(task).getSummary().state());
    }

    @Test
    void editingACoveredItemMakesTheSummaryStale() {
        // This is the case the content hash exists for: the summary describes text that is no
        // longer in the conversation, so continuing to send it silently loses the edit.
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        List<NaruMessage> edited = new ArrayList<>(task.history());
        for (int i = 0; i < edited.size(); i++) {
            NaruMessage m = edited.get(i);
            if (m.getExcludedBy() != null && "S1".equals(m.getExcludedBy())) {
                edited.set(i, m.withContent(m.getContent() + " (edited)").setExcludedBy("S1"));
            }
        }
        task.setHistory(edited);

        List<String> stale = compactor.reconcileStale(task, NaruCompactConfig.StalePolicy.deactivate);
        assertEquals(List.of("S1"), stale);
        assertEquals(NaruSummaryState.UNDONE, summaryOf(task).getSummary().state(),
                "deactivate must stop it standing in for content that changed");
        assertTrue(summaryOf(task).getSummary().stale());
        assertEquals(3, net.thevpc.naru.api.model.NaruContextViews
                .contextView(task.history()).size(), "the edited items are back in the view");
    }

    @Test
    void keepPolicyFlagsTheSummaryWithoutRetiringIt() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        List<NaruMessage> edited = new ArrayList<>(task.history());
        for (int i = 0; i < edited.size(); i++) {
            NaruMessage m = edited.get(i);
            if ("S1".equals(m.getExcludedBy())) {
                edited.set(i, m.withContent(m.getContent() + " changed").setExcludedBy("S1"));
            }
        }
        task.setHistory(edited);

        assertEquals(List.of("S1"),
                compactor.reconcileStale(task, NaruCompactConfig.StalePolicy.keep));
        NaruMessage summary = summaryOf(task);
        assertEquals(NaruSummaryState.ACTIVE, summary.getSummary().state(),
                "keep means keep: the summary stays in the view");
        assertTrue(summary.getSummary().stale(), "but it is labelled so the user can see it");
        assertTrue(summary.getContent().length() > 0);
    }

    @Test
    void aSummaryWithNothingBehindItIsStale() {
        // Either it was undone or the exclusion flags were lost. Either way it stands in for
        // nothing, so it must not be rendered into the context view as though it did.
        NaruMessage summary = NaruMessage.summary("the gist", info("S1", covered("a"), false));
        NaruTask task = taskWith(List.of(summary, NaruMessage.user("recent")));
        assertTrue(NaruCompactContextCompactor.isStale(task.history(), summary));
    }

    @Test
    void aSummaryWithNoRecordedHashIsTreatedAsFresh() {
        // It predates the field. Deactivating every such summary would silently empty the
        // context view of every existing session, which is a far worse failure than a summary
        // that cannot be verified.
        NaruSummaryInfo noHash = new NaruSummaryInfo("S1", null, null, 1, 10, 5, null,
                NaruSummaryLevel.NORMAL, null, null, "m", Instant.now(),
                NaruSummaryTrigger.MANUAL, NaruSummaryState.ACTIVE, false);
        NaruMessage summary = NaruMessage.summary("gist", noHash);
        NaruMessage item = NaruMessage.user("one").setExcludedBy("S1");
        assertFalse(NaruCompactContextCompactor.isStale(List.of(item, summary), summary));
    }

    @Test
    void anUndoneSummaryIsNotReconciledAgain() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        compactor.undo(task, "S1");
        assertEquals(List.of(), compactor.reconcileStale(task, NaruCompactConfig.StalePolicy.deactivate));
    }

    // ── reporting ────────────────────────────────────────────────────────────

    @Test
    void theStatusLineCountsSummariesCoverageAndView() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        String status = NaruCompactContextCompactor.describe(task, null,
                new NaruCompactCache(10, true));
        assertTrue(status.contains("1 active summary"), status);
        assertTrue(status.contains("2 of 4 history items covered"), status);
        assertTrue(status.contains("context view: 2 items"), status);
    }

    @Test
    void theLastResultAppearsInTheStatusLine() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        NaruMessage lastSummary = NaruMessage.summary("the gist",
                info("S9", covered("a", "b"), false));
        NaruCompactionResult last = NaruCompactionResult.produced(lastSummary, 100, 42, 2,
                "test/model", List.of("other/model: rate limited"));
        String status = NaruCompactContextCompactor.describe(task, last, null);
        // A successful result carries no message, so the numbers are what the user gets.
        assertTrue(status.contains("last: PRODUCED"), status);
        assertTrue(status.contains("2 items"), status);
        assertTrue(status.contains("test/model"), status);
        // and a model that was passed over is worth saying out loud
        assertTrue(status.contains("skipped models: other/model: rate limited"), status);
        assertFalse(status.contains("compaction cache"), "no cache means no cache line");
    }

    @Test
    void aFailedResultShowsWhy() {
        NaruTask task = taskWith(compacted("S1", "one", "two"));
        String status = NaruCompactContextCompactor.describe(task,
                NaruCompactionResult.failed("no usable model"), null);
        assertTrue(status.contains("last: FAILED - no usable model"), status);
    }

    private static NaruMessage summaryOf(NaruTask task) {
        return task.history().stream()
                .filter(NaruMessage::isSummary)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no summary in history"));
    }

    /** A listener that ignores everything, so the tests can focus on history rewriting. */
    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }

        @Override
        public void sessionStopped(NaruSession naruSession) {
        }

        @Override
        public void sessionStarted(NaruSession naruSession) {
        }
    };

}