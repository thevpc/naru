package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.naru.api.project.NaruProjectStoreConfig;
import net.thevpc.naru.api.project.NaruProjectStoreFactory;
import net.thevpc.nuts.util.NOptional;

/**
 * The filesystem project store.
 *
 * <p>Named {@code file} and the default, exactly like {@link NaruFileSessionStoreFactory}:
 * the workspace asks for a project store by name and never asks which implementation it
 * got, so another backend can replace this one without touching a caller.
 */
public class NaruFileProjectStoreFactory implements NaruProjectStoreFactory {

    public static final String NAME = "file";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public NOptional<NaruProjectStore> open(NaruProjectStoreConfig config) {
        if (config == null || config.root() == null) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(new NaruFileProjectStore(config));
    }

    @Override
    public boolean isDefault() {
        return true;
    }
}
