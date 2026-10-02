package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionStoreFactory;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a session store, the way every other NARU extension is found.
 *
 * <p>Implementations are discovered through {@code NExtensions} -- a class listed in
 * {@code META-INF/services/net.thevpc.nuts.spi.NComponent} -- so a backend is added by
 * dropping a jar on the classpath and naming it in configuration, with nothing in the
 * engine changed and no branch anywhere that says "if the store is a file store".
 *
 * <p>Resolution is ordered and deterministic:
 * <ol>
 *   <li>a factory whose {@link NaruSessionStoreFactory#name()} equals the configured name,
 *       if there is one;</li>
 *   <li>otherwise the factory that answers {@link NaruSessionStoreFactory#isDefault()},
 *       ties broken by name so two machines with the same classpath agree.</li>
 * </ol>
 */
public final class NaruStoreFactory {

    private NaruStoreFactory() {
    }

    /** Opens the store for a config, as named. Falls back to the default factory. */
    public static NaruSessionStore open(NaruStoreConfig config, String configuredName) {
        List<NaruSessionStoreFactory> factories = factories();
        NaruSessionStoreFactory chosen = null;
        if (configuredName != null && !configuredName.isBlank()) {
            for (NaruSessionStoreFactory f : factories) {
                if (configuredName.equals(f.name())) {
                    chosen = f;
                    break;
                }
            }
            if (chosen == null) {
                // explicitly named and not present: fail loudly. Falling back silently would
                // write a user's session to a store they did not ask for, possibly on a
                // different machine.
                throw new IllegalStateException("no session store named '" + configuredName
                        + "' is available; found " + names(factories));
            }
        }
        if (chosen == null) {
            List<NaruSessionStoreFactory> defaults = new ArrayList<>();
            for (NaruSessionStoreFactory f : factories) {
                if (f.isDefault()) {
                    defaults.add(f);
                }
            }
            defaults.sort(Comparator.comparing(NaruSessionStoreFactory::name));
            chosen = defaults.isEmpty() ? null : defaults.get(0);
        }
        if (chosen == null) {
            throw new IllegalStateException("no session store is available; found " + names(factories));
        }
        NOptional<NaruSessionStore> store = chosen.open(config);
        if (!store.isPresent()) {
            throw new IllegalStateException("session store '" + chosen.name()
                    + "' cannot serve " + config);
        }
        return store.get();
    }

    /**
     * Every discovered factory, whether or not it can serve a given config.
     *
     * <p>Used by the store test suite: a contract suite that only ever runs against the
     * file store tests one implementation, and a second backend would arrive already
     * broken.
     */
    public static List<NaruSessionStoreFactory> factories() {
        return NExtensions.of().createAllSupported(NaruSessionStoreFactory.class, null);
    }

    private static String names(List<NaruSessionStoreFactory> factories) {
        List<String> n = new ArrayList<>();
        for (NaruSessionStoreFactory f : factories) {
            n.add(f.name());
        }
        return n.toString();
    }
}