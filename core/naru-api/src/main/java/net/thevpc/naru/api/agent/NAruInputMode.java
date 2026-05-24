package net.thevpc.naru.api.agent;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

public enum NAruInputMode {
    LINE,
    BLOC;

    public static NOptional<NAruInputMode> parse(NElement x) {
        if(x !=null){
            if(x.isAnyStringOrName()){
                return parse(x.asStringValue().get());
            }
        }
        return NOptional.ofNamedEmpty("input mode");
    }

    public String id() {
        return name().toLowerCase();
    }

    public static NOptional<NAruInputMode> parse(String x) {
        if(x !=null){
            switch (x.trim()
                    .replace("_", "")
                    .replace("-", "")
                    .trim()
                    .toLowerCase()){
                case "line": return NOptional.of(LINE);
                case "multiline":
                case "bloc":
                    return NOptional.of(BLOC);
            }
        }
        return NOptional.ofNamedEmpty("input mode");
    }
}
