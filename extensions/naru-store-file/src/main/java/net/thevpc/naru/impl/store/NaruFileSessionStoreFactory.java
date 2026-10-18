package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionStoreFactory;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.nuts.util.NOptional;

/**
 * The filesystem session store.
 *
 * <p>The default, and named {@code file}. Selecting it by name is what leaves room for
 * another backend without changing any caller: everything above this class asks the factory
 * for a store and never asks which one it got.
 */
public class NaruFileSessionStoreFactory implements NaruSessionStoreFactory {

    public static final String NAME = "file";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public NOptional<NaruSessionStore> open(NaruStoreConfig config) {
        if (config == null || config.storeDir() == null) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(new NaruFileSessionStore(config));
    }

    @Override
    public boolean isDefault() {
        return true;
    }
}