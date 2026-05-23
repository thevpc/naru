package net.thevpc.naru.api.agent;

import java.util.List;

public interface NaruSessionManager {
    List<NaruResourceInfo> list();

    int clear();

    String findByUuidOrName(String uuidOrName);

    boolean delete(String uuidOrName);

    NaruSessionManager restoreSnapshot();

    NaruSessionManager reload();
    NaruSessionManager load(String uuid);

    NaruSessionManager copyCurrent();
}
