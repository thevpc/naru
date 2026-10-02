package net.thevpc.naru.api.context;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruContextViews;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;

import java.util.List;

/**
 * The one call a caller needs to compact a context without touching the task it came from.
 *
 * <p>This is the reusable entry point: give it a task and a spec, get back the context the
 * model should see -- including a summary item, reused from cache when the same content was
 * summarized before -- with the source task completely unmodified.
 *
 * <p>It exists separately from {@link NaruContextCompactor} because "what should I send?"
 * and "change this task" are different questions. A fork building its child's starting
 * context, a preview in {@code /compact status}, and a dry run all want the first; only a
 * deliberate {@code /compact} wants the second. Keeping them apart is what makes a dry run
 * honest -- it runs the same code path, not a cheaper approximation of it.
 *
 * <p>The lookup is lazy and fails loudly. With no compactor installed the caller gets
 * {@link NaruCompactionException#notInstalled()} rather than an unchanged context that looks
 * like a successful compaction.
 */
public final class NaruCompactors {

    private NaruCompactors() {
    }

    /**
     * Finds the compactor for a session.
     *
     * @throws NaruCompactionException if no implementation is installed
     */
    public static NaruContextCompactor find(NaruSession session) {
        if (session == null) {
            throw new NaruCompactionException(
                    "compaction needs a session to resolve the compactor from");
        }
        return session.registry().compactor()
                .orElseThrow(NaruCompactionException::notInstalled);
    }

    /** Whether compaction is available in this session. */
    public static boolean isInstalled(NaruSession session) {
        return session != null && session.registry().compactor().isPresent();
    }

    /**
     * Produces the context a task should send, without modifying it.
     *
     * <p>Never applies, so the task's history and exclusion flags are exactly as they were.
     * The result carries the summary item a caller would insert, plus what it would save.
     *
     * <p>The task's context view is used as the source -- its history minus what an existing
     * summary already stands in for. Summarizing already-summarized content is the clearest
     * possible way to get compaction wrong, so excluded items never reach the compactor.
     *
     * @param task the task whose context view is the input
     * @param spec what to keep and how hard to summarize
     */
    public static NaruCompactionResult preview(NaruTask task, NaruContextSpec spec) {
        if (task == null) {
            throw new NaruCompactionException("compaction needs a task");
        }
        return preview(task, NaruContextViews.contextView(task.history()), spec);
    }

    /**
     * Produces the context a given view should send, without modifying anything.
     *
     * <p>The overload for callers that already have the view: a fork assembling a child's
     * starting conversation, a test with three hand-built messages, a caller that has
     * rendered a view it wants summarized instead. The task is a handle, not the subject: it
     * is never read from and never written to, and it is what the summarizer runs the model
     * call through -- which is why this overload exists separately from the session-only one
     * below.
     *
     * <p>{@code contextView} must already exclude what existing summaries cover. Passing raw
     * history is not caught here -- it produces a summary of a summary, which is worse than
     * an error -- so use {@link NaruContextViews#contextView} rather than
     * {@link NaruTask#history()} at the call site.
     *
     * @param task the task whose session and models are used, left untouched
     * @param contextView the items a summary would cover, in order
     */
    public static NaruCompactionResult preview(NaruTask task,
                                               List<NaruMessage> contextView,
                                               NaruContextSpec spec) {
        if (task == null) {
            throw new NaruCompactionException("compaction needs a task to run the summarizer through");
        }
        return preview(task.session(), task, contextView, spec);
    }

    /**
     * Produces the context a given view should send, without modifying anything, resolving the
     * compactor from a session.
     *
     * <p>For compactors that need no task. The bundled compactor summarizes through a task,
     * because that is where a model call is made, so it fails on this overload with a message
     * naming the task overload to use instead of a bare "no task" from the inside.
     *
     * @param session where to resolve the compactor from
     * @param contextView the items a summary would cover, in order
     */
    public static NaruCompactionResult preview(NaruSession session,
                                               List<NaruMessage> contextView,
                                               NaruContextSpec spec) {
        return preview(session, null, contextView, spec);
    }

    private static NaruCompactionResult preview(NaruSession session,
                                                NaruTask sourceTask,
                                                List<NaruMessage> contextView,
                                                NaruContextSpec spec) {
        NaruContextCompactor compactor = find(session);
        return compactor.compact(NaruCompactionRequest.builder()
                .session(session)
                .sourceTask(sourceTask)
                .sourceView(contextView)
                .spec(spec)
                .apply(false)
                .trigger("PREVIEW")
                .build());
    }

    /**
     * Produces the context a task should send and writes it: the summary item is inserted
     * at the cut and the covered items are flagged.
     *
     * <p>All-or-nothing. On failure the task is exactly as it was.
     */
    public static NaruCompactionResult compact(NaruTask task, NaruContextSpec spec) {
        if (task == null) {
            throw new NaruCompactionException("compaction needs a task");
        }
        NaruContextCompactor compactor = find(task.session());
        return compactor.compact(NaruCompactionRequest.builder()
                .sourceTask(task)
                .session(task.session())
                // the context view, not history: an already-covered item must not be
                // summarized again
                .sourceView(NaruContextViews.contextView(task.history()))
                .spec(spec)
                .apply(true)
                .trigger("MANUAL")
                .build());
    }

    /**
     * Compacts a view the caller holds, writing the result into a task.
     *
     * <p>The task is the destination, not the source: {@code contextView} is what gets
     * summarized and {@code task} is where the summary item and its exclusion flags land.
     * They are usually the same task's view, but not always -- a fork may compact what it
     * was given before adopting it.
     *
     * <p>All-or-nothing: if this throws, {@code task} is untouched.
     */
    public static NaruCompactionResult compact(NaruTask task,
                                               List<NaruMessage> contextView,
                                               NaruContextSpec spec) {
        if (task == null) {
            throw new NaruCompactionException("compaction needs a task");
        }
        NaruContextCompactor compactor = find(task.session());
        return compactor.compact(NaruCompactionRequest.builder()
                .sourceTask(task)
                .session(task.session())
                .sourceView(contextView)
                .spec(spec)
                .apply(true)
                .trigger("MANUAL")
                .build());
    }
}