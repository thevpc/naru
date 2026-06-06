package net.thevpc.naru.api.agent;

import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

public enum NAruVisibility {
    PUBLIC, PRIVATE, MIXED;

    public static NOptional<NAruVisibility> parse(String visibility) {
        if(NBlankable.isBlank(visibility)){
            return NOptional.ofNamedEmpty("visibility : '"+visibility+"'");
        }
        switch (visibility.toLowerCase()){
            case "public": return NOptional.of(PUBLIC);
            case "private": return NOptional.of(PRIVATE);
            case "mixed": return NOptional.of(MIXED);
        }
        return NOptional.ofNamedEmpty("visibility : '"+visibility+"'");
    }
}
