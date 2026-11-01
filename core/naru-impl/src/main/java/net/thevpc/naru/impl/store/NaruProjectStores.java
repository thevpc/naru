package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.naru.api.store.NaruProjectStoreConfig;
import net.thevpc.naru.api.store.NaruProjectStoreFactory;
import net.thevpc.nuts.ext.NServiceLoader;
import net.thevpc.nuts.reflect.NClassLoader;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a project store the way {@link NaruStoreFactory} finds a session store: named
 * factory first (a name that is not present fails loudly, because silently writing to a
 * different backend is worse than not starting), then the default factories sorted by
 * name. Implementations come from {@code META-INF/services/net.thevpc.nuts.spi.NComponent}.
 */
public final class NaruProjectStores {

    private NaruProjectStores() {
    }

    public static NaruProjectStore open(NaruProjectStoreConfig config, String configuredName,
                                        NClassLoader classLoader) {
        List<NaruProjectStoreFactory> factories = factories(classLoader);
        NaruProjectStoreFactory chosen = null;
        if (configuredName != null && !configuredName.isBlank()) {
            for (NaruProjectStoreFactory f : factories) {
                if (configuredName.equals(f.name())) {
                    chosen = f;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("no project store named '" + configuredName
                        + "' is available; found " + names(factories));
            }
        }
        if (chosen == null) {
            List<NaruProjectStoreFactory> defaults = new ArrayList<>();
            for (NaruProjectStoreFactory f : factories) {
                if (f.isDefault()) {
                    defaults.add(f);
                }
            }
            defaults.sort(Comparator.comparing(NaruProjectStoreFactory::name));
            chosen = defaults.isEmpty() ? null : defaults.get(0);
        }
        if (chosen == null) {
            throw new IllegalStateException("no project store is available; found " + names(factories));
        }
        NOptional<NaruProjectStore> store = chosen.open(config);
        if (!store.isPresent()) {
            throw new IllegalStateException("project store '" + chosen.name()
                    + "' cannot serve " + config);
        }
        return store.get();
    }

    public static List<NaruProjectStoreFactory> factories(NClassLoader classLoader) {
        return NServiceLoader.of(NaruProjectStoreFactory.class, null, classLoader.asClassLoader()).loadAll(null);
    }

    private static String names(List<NaruProjectStoreFactory> factories) {
        List<String> out = new ArrayList<>();
        for (NaruProjectStoreFactory f : factories) {
            out.add(f.name());
        }
        return out.toString();
    }
}
