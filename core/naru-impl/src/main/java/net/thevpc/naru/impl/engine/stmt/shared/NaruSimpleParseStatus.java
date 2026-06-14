package net.thevpc.naru.impl.engine.stmt.shared;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NOptional;

public enum NaruSimpleParseStatus {
    PENDING, COMPLETE;

    public static NOptional<NaruSimpleParseStatus> parse(String s) {
        if (NBlankable.isBlank(s)) {
            return NOptional.ofNamedEmpty(s);
        }
        switch (NNameFormat.LOWER_KEBAB_CASE.format(s)) {
            case "pending":
                return NOptional.of(PENDING);
            case "complete":
                return NOptional.of(COMPLETE);
        }
        return NOptional.ofNamedError(NMsg.ofC("status %s", s));
    }
}
