package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.elem.NElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete, self-contained snapshot of a session, as values.
 *
 * <p>Whole on purpose. A version that stored "the tasks that changed since the last one"
 * would have to replay every earlier version to be readable, which makes a version's
 * readability depend on versions the user may have collected -- the failure git calls a
 * shallow clone. Storing each version whole costs disk and buys the property that matters
 * for a restore point: restoring the tenth commit does not depend on the first nine still
 * existing.
 *
 * <p>What a version does <em>not</em> contain is the project directory. Versioning is for
 * NARU's own state; a session that edits files is still pointing at the user's checkout,
 * and copying that would be both enormous and not what anyone meant by "save my work".
 */
public class NaruSessionState {

    private final NaruSessionData session;
    private final Map<Long, NaruTaskState> tasks = new LinkedHashMap<>();
    private final Map<String, NElement> extensions = new LinkedHashMap<>();
    private final Map<String, NElement> routines = new LinkedHashMap<>();

    public NaruSessionState(NaruSessionData session) {
        this.session = session;
    }

    public NaruSessionData session() {
        return session;
    }

    public Map<Long, NaruTaskState> tasks() {
        return tasks;
    }

    public Map<String, NElement> extensions() {
        return extensions;
    }

    public Map<String, NElement> routines() {
        return routines;
    }

    public List<NaruMessage> historyOf(long taskId) {
        NaruTaskState t = tasks.get(taskId);
        return t == null ? new ArrayList<>() : t.history();
    }
}