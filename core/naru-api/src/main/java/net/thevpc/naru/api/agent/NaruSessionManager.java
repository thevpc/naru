package net.thevpc.naru.api.agent;

import java.util.List;

public interface NaruSessionManager {
    List<NaruSessionInfo> list();

    int clear();

    String findByUuidOrName(String uuidOrName);

    boolean delete(String uuidOrName);

    NaruSessionManager load(String uuid);

    NaruSessionManager copyCurrent();
}
