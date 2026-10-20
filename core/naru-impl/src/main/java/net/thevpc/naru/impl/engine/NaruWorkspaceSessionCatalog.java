package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSessionStoreManager;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.impl.store.NaruStoreFactory;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The workspace's own view of its saved sessions.
 * <p>
 * Unlike {@link NaruSessionStoreManagerImpl}, which is reached through one live session and
 * falls back to that session's own uuid and name, this catalog belongs to the workspace and
 * has no current session to appeal to. That is the point: a host listing sessions must see
 * every saved conversation, including ones nothing is running, and must be able to delete
 * the exact row it read. The earlier arrangement — a fresh store handle per row — listed a
 * session once per handle and then failed to delete it because the row and the handle were
 * different objects looking at the same folder.
 */
final class NaruWorkspaceSessionCatalog implements NaruSessionStoreManager {

    private final NaruWorkspaceImpl workspace;
    /** Opened lazily: a workspace that never lists sessions pays nothing for this. */
    private volatile NaruSessionStore store;

    NaruWorkspaceSessionCatalog(NaruWorkspaceImpl workspace) {
        this.workspace = workspace;
    }

    private NaruSessionStore store() {
        NaruSessionStore current = store;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (store == null) {
                NPath dir = workspace.projectDirectory() != null
                        ? workspace.projectDirectory()
                        : workspace.defaultSessionDirectory();
                NaruEnv env = workspace.env();
                store = NaruStoreFactory.open(
                        NaruStoreConfigs.forProject(dir, env),
                        NaruStoreConfigs.storeName(env),
                        workspace.classLoader());
            }
            return store;
        }
    }

    @Override
    public List<NaruResourceInfo> list() {
        List<NaruResourceInfo> found = new ArrayList<>();
        List<net.thevpc.naru.api.store.NaruSessionRef> refs = store().list();
        if (refs != null) {
            for (net.thevpc.naru.api.store.NaruSessionRef ref : refs) {
                if (ref == null || ref.uuid() == null) {
                    continue;
                }
                found.add(new NaruResourceInfo()
                        .setUuid(ref.uuid())
                        .setName(ref.name())
                        .setCreationInstant(ref.creationInstant())
                        .setModificationInstant(ref.modificationInstant())
                        .setVisibility(ref.scope() == NaruSessionScope.PUBLIC
                                ? NaruVisibility.PUBLIC : NaruVisibility.PRIVATE));
            }
        }
        // newest modification first. A store is allowed to hand back whatever order it likes;
        // the sidebar must not reorder between two reads of an unchanged catalog, so the
        // ordering lives here rather than in each host.
        found.sort(Comparator.comparing(
                (NaruResourceInfo i) -> i.getModificationInstant() == null ? Instant.EPOCH : i.getModificationInstant())
                .reversed());
        return found;
    }

    @Override
    public int purge() {
        // both scopes, explicitly: a null scope means "this scope" to the store and would
        // purge nothing, or throw, rather than emptying the catalog the caller asked about
        return store().purge(NaruSessionScope.PRIVATE) + store().purge(NaruSessionScope.PUBLIC);
    }

    @Override
    public String findByUuidOrName(String uuidOrName) {
        if (NBlankable.isBlank(uuidOrName)) {
            return null;
        }
        List<NaruResourceInfo> all = list();
        for (NaruResourceInfo s : all) {
            if (Objects.equals(s.getUuid(), uuidOrName)) {
                return s.getUuid();
            }
        }
        for (NaruResourceInfo s : all) {
            if (Objects.equals(NStringUtils.strip(s.getName()), NStringUtils.strip(uuidOrName))) {
                return s.getUuid();
            }
        }
        Integer index = NLiteral.of(uuidOrName).asInt().orNull();
        if (index != null && index - 1 >= 0 && index - 1 < all.size()) {
            return all.get(index - 1).getUuid();
        }
        return null;
    }

    @Override
    public boolean delete(String uuidOrName) {
        String uuid = findByUuidOrName(uuidOrName);
        if (uuid == null) {
            return false;
        }
        NaruSessionScope scope = store().scopeOf(uuid).orNull();
        if (scope == null) {
            return false;
        }
        return store().delete(uuid, scope);
    }

    @Override
    public boolean rename(String uuid, String name) {
        if (NBlankable.isBlank(uuid) || name == null) {
            return false;
        }
        NaruSessionScope scope = store().scopeOf(uuid).orNull();
        if (scope == null) {
            return false;
        }
        NaruSessionData data = store().loadData(uuid, scope).orNull();
        if (data == null) {
            return false;
        }
        data.name(name);
        data.modificationInstant(Instant.now());
        store().saveData(data, scope);
        return true;
    }

    @Override
    public boolean setVisibility(String uuid, NaruVisibility visibility) {
        if (NBlankable.isBlank(uuid) || visibility == null) {
            return false;
        }
        NaruSessionScope target = visibility == NaruVisibility.PUBLIC
                ? NaruSessionScope.PUBLIC : NaruSessionScope.PRIVATE;
        NaruSessionScope scope = store().scopeOf(uuid).orNull();
        if (scope == null) {
            return false;
        }
        if (scope != target) {
            store().move(uuid, scope, target);
        }
        // bump the modification time so the catalog re-sorts around the change
        NaruSessionData data = store().loadData(uuid, target).orNull();
        if (data != null) {
            data.modificationInstant(Instant.now());
            store().saveData(data, target);
        }
        return true;
    }
}
