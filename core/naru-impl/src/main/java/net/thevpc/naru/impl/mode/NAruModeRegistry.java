package net.thevpc.naru.impl.mode;

import net.thevpc.naru.api.mode.NaruMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.*;

public class NAruModeRegistry {
    private final Map<String, NaruMode> modes = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();

    public NAruModeRegistry() {
        register(NaruStandardModeImpl.ASK);
        register(NaruStandardModeImpl.AUDIT);
        register(NaruStandardModeImpl.DEBUG);
        register(NaruStandardModeImpl.PLANNING);
        register(NaruStandardModeImpl.IMPLEMENT);
        register(NaruStandardModeImpl.REVIEW);
    }

    public void register(NaruMode mode) {
        NAssert.requireNamedNonNull(mode, "mode");
        String m = NNameFormat.LOWER_KEBAB_CASE.format(mode.name());
        if (modes.containsKey(m)) {
            throw new NIllegalArgumentException(NMsg.ofC("mode %s already registered", m));
        }
        if (aliases.containsKey(m)) {
            throw new NIllegalArgumentException(NMsg.ofC("mode %s already registered", m));
        }
        Set<String> a = new HashSet<>();
        for (String alias : mode.aliases()) {
            String v = NNameFormat.LOWER_KEBAB_CASE.format(alias);
            if (modes.containsKey(m)) {
                throw new NIllegalArgumentException(NMsg.ofC("mode %s already registered", v));
            }
            if (aliases.containsKey(m)) {
                throw new NIllegalArgumentException(NMsg.ofC("mode %s already registered", v));
            }
            a.add(v);
        }
        modes.put(m, mode);
        for (String s : a) {
            aliases.put(s, m);
        }
    }


    public List<NaruMode> modes() {
        return new ArrayList<>(modes.values());
    }

    public NOptional<NaruMode> mode(NaruStandardMode name) {
        return mode(name.name());
    }

    public NOptional<NaruMode> mode(String name) {
        String n = NNameFormat.LOWER_KEBAB_CASE.format(name);
        NaruMode a = modes.get(n);
        if (a != null) {
            return NOptional.of(a);
        }
        String s = aliases.get(n);
        if (s != null) {
            a = modes.get(s);
            if (a != null) {
                return NOptional.of(a);
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("mode '%s'", name));
    }
}
