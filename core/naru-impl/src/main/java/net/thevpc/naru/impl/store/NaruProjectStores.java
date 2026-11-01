package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.naru.api.store.NaruProjectStoreConfig;
import net.thevpc.naru.api.store.NaruProjectStoreFactory;
import net.thevpc.nuts.ext.NServiceLoader;
import net.thevpc.nuts.reflect.NClassLoader;

import java.util.List;

/**
 * Finds a project store the way {@link NaruStoreFactory} finds a session store. The selection
 * rule itself lives on {@link NaruProjectStore} so that a host reading a foreign folder — the
 * desktop's group form inspects the folder the user picked — can use the same resolver without
 * importing this package. This class keeps the factory-listing surface the engine uses.
 */
public final class NaruProjectStores {

    private NaruProjectStores() {
    }

    public static NaruProjectStore open(NaruProjectStoreConfig config, String configuredName,
                                        NClassLoader classLoader) {
        return NaruProjectStore.open(config, configuredName, classLoader);
    }

    public static List<NaruProjectStoreFactory> factories(NClassLoader classLoader) {
        return NServiceLoader.of(NaruProjectStoreFactory.class, null, classLoader.asClassLoader()).loadAll(null);
    }
}
