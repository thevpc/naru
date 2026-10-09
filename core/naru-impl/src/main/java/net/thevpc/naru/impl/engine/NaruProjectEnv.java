package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The on-disk configuration store: two files, one per visibility.
 *
 * <p>Private is {@code .naru/local/config/env.tson} (untracked, personal), public is
 * {@code .naru/config/env.tson} (checked in, shared). These are not two scopes -- they
 * are the visibility axis of one store, and a key may be present in either or both.
 */
public class NaruProjectEnv implements NaruEnv {
    private final StoredStringMap<NElement> projectPublicEnv;
    private final StoredStringMap<NElement> projectPrivateEnv;

    public NaruProjectEnv(NPath publicPath, NPath privatePath) {
        projectPublicEnv = new StoredStringMap<>(publicPath, NElement.class);
        projectPrivateEnv = new StoredStringMap<>(privatePath, NElement.class);
    }

    @Override
    public NOptional<NElement> get(String key) {
        return projectPrivateEnv.get(key)
                .orElseGetOptionalFrom(
                        () -> projectPublicEnv.get(key)
                )
                ;
    }

    @Override
    public NOptional<NElement> get(String key, NaruVisibility visibility) {
        if (visibility == null) {
            return get(key);
        }
        switch (visibility) {
            case PUBLIC:
                return projectPublicEnv.get(key);
            case PRIVATE:
                return projectPrivateEnv.get(key);
            default:
                // MIXED is not a file, it is "unspecified": resolve it the way put does
                return get(key);
        }
    }

    @Override
    public void put(String key, NElement value, NaruVisibility visibility) {
        if(visibility==null||visibility== NaruVisibility.MIXED){
            visibility= NaruVisibility.PRIVATE;
        }
        switch (visibility){
            case PRIVATE:{
                if (value == null) {
                    projectPrivateEnv.remove(key);
                } else {
                    projectPrivateEnv.put(key, value);
                }
                break;
            }
            case PUBLIC:{
                if (value == null) {
                    projectPublicEnv.remove(key);
                } else {
                    projectPublicEnv.put(key, value);
                }
                break;
            }
        }
    }

    @Override
    public Map<String, NElement> entries() {
        // public first, then private, so a key held in both shows the private value
        Map<String, NElement> out = new LinkedHashMap<>(projectPublicEnv.toMap());
        out.putAll(projectPrivateEnv.toMap());
        return out;
    }
}
