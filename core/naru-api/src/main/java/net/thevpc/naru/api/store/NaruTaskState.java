package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A task, split into the part that changes constantly and the part that does not.
 *
 * <p>The split exists because of what a turn does to a task. Executing one statement
 * touches a handful of scalars, the call stack and the inbox -- while the conversation
 * grows by one message. Writing the whole task per statement means rewriting the whole
 * conversation per statement, and a task that has run a hundred turns rewrites a hundred
 * turns' worth of history for each of the hundred statements in its hundredth turn. So:
 *
 * <ul>
 *   <li>{@code skeleton} is everything but the history, written as one record;</li>
 *   <li>the history is written as one record per message, and the skeleton only holds the
 *       ids, in order.</li>
 * </ul>
 *
 * <p>What a message is stored as, and under which id, is the store's business and not the
 * caller's. Messages have no identity of their own -- a user can insert, delete and
 * truncate them at any index -- so the store reconciles by content. See
 * {@link NaruSessionStore#saveHistory}.
 */
public class NaruTaskState {

    private final long id;
    private final NElement skeleton;
    private final List<NaruMessage> history;
    private final List<String> historyIds;

    private NaruTaskState(long id, NElement skeleton, List<NaruMessage> history, List<String> historyIds) {
        this.id = id;
        this.skeleton = skeleton;
        this.history = history == null ? new ArrayList<>() : history;
        this.historyIds = historyIds == null ? new ArrayList<>() : historyIds;
    }

    /**
     * A task whose skeleton is already in its stored shape -- that is, whose {@code history}
     * key holds item ids rather than inline messages.
     */
    public static NaruTaskState of(long id, NElement skeleton, List<String> historyIds, List<NaruMessage> history) {
        return new NaruTaskState(id, skeleton, history, historyIds);
    }

    /**
     * A task handed over by an engine that still writes history inline, as every version
     * before the split did.
     *
     * <p>The inline messages are taken as the history and the key is dropped, so the same
     * conversion works for a legacy file being migrated and for an engine that has not
     * switched over yet. Nothing is lost either way; it is only rewritten.
     */
    public static NaruTaskState ofInline(long id, NElement taskElement) {
        List<NaruMessage> messages = new ArrayList<>();
        NElement h = taskElement.asObject().get().get("history").orNull();
        if (h != null && !h.isNull() && h.isAnyArray()) {
            for (NElement m : h.asArray().get()) {
                messages.add(NaruMessage.of(m));
            }
        }
        NElement skeleton = withoutHistory(taskElement);
        return new NaruTaskState(id, skeleton, messages, new ArrayList<>());
    }

    /**
     * The task element with its {@code history} key removed, leaving everything else
     * byte-for-byte as it was.
     */
    public static NElement withoutHistory(NElement taskElement) {
        NObjectElement o = taskElement.asObject().get();
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : o.children()) {
            if (child.isNamedPair()) {
                String key = child.asPair().get().key().asStringValue().orNull();
                if ("history".equals(key)) {
                    continue;
                }
                b.add(child.asPair().get());
            }
        }
        return b.build();
    }

    public long id() {
        return id;
    }

    public NElement skeleton() {
        return skeleton;
    }

    public List<NaruMessage> history() {
        return history;
    }

    /**
     * The ids the stored skeleton refers to, in order. Filled by the store on load; not
     * meaningful on a state that has not been through a store yet.
     */
    public List<String> historyIds() {
        return Collections.unmodifiableList(historyIds);
    }

    /**
     * The task as it is written: the skeleton, with the history ids in place of the
     * messages.
     */
    public NElement toElement() {
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : skeleton.asObject().get().children()) {
            if (child.isNamedPair()) {
                b.add(child.asPair().get());
            }
        }
        NArrayElementBuilder ids = NArrayElementBuilder.of();
        for (String hid : historyIds) {
            ids.add(NElement.ofString(hid));
        }
        b.add("history", ids.build());
        return b.build();
    }

    /**
     * Reads a stored task: a skeleton plus whatever its history ids resolve to.
     *
     * <p>An id that resolves to nothing is skipped rather than turned into a placeholder.
     * A missing message is a store that lost a file; inserting a hole would make the
     * conversation the model sees wrong in a way that reads as coherent, which is worse
     * than a conversation with a gap the user can see.
     */
    public static NaruTaskState ofStored(long id, NElement storedElement, List<NaruMessage> resolved) {
        List<String> ids = new ArrayList<>();
        NElement h = storedElement.asObject().get().get("history").orNull();
        if (h != null && !h.isNull() && h.isAnyArray()) {
            NArrayElement a = h.asArray().get();
            for (NElement e : a) {
                String s = e.asStringValue().orNull();
                if (s != null) {
                    ids.add(s);
                }
            }
        }
        return new NaruTaskState(id, withoutHistory(storedElement),
                resolved == null ? new ArrayList<>() : resolved, ids);
    }
}