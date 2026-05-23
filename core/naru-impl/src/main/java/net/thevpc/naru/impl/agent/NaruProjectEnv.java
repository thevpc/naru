package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

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
    public void put(String key, NElement value, NAruVisibility visibility) {
        if(visibility==null||visibility==NAruVisibility.MIXED){
            visibility=NAruVisibility.PRIVATE;
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
}
