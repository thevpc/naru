package net.thevpc.naru.api.store;

import net.thevpc.nuts.util.NOptional;

import java.util.List;

/** Task skeletons and position-ordered history for one session. */
public interface NaruTaskStore {
    List<Long> list();
    NOptional<NaruTaskState> load(long taskId);
    NOptional<NaruTaskState> loadSkeleton(long taskId);
    void save(NaruTaskState task);
    void delete(long taskId);
    NaruMessageStore messages(long taskId);
}
