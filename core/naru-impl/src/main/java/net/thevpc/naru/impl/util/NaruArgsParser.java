package net.thevpc.naru.impl.util;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NaruArgsParser {
    private final List<NElement> args = new ArrayList<>();

    public NaruArgsParser(String command) {
        String c = command == null ? "" : command.trim();
        if (NaruUtils.isPath(c)) {
            args.add(NElement.ofString(c.trim()));
        } else {
            NElement u = NElementReader.ofTson().read(c);
            if (u.isFragment()) {
                args.addAll(u.asFragment().get().children());
            } else if (u.isArray()) {
                args.addAll(u.asArray().get().children());
            } else if (u.isObject()) {
                args.addAll(u.asObject().get().children());
            } else {
                args.add(u);
            }
        }
    }

    public NOptional<NElement> argByName(String name) {
        List<NElement> all = argsByName(name);
        return NOptional.ofNamedSingleton(all, name);
    }

    public List<NElement> argsByName(String name) {
        List<NElement> ok = new ArrayList<>();
        for (NElement param : args) {
            if (param.isNamedPair()) {
                NPairElement p = param.asPair().get();
                String k = p.key().asStringValue().get();
                if (Objects.equals(k, name)) {
                    ok.add(p.value());
                }
            }
        }
        return ok;
    }

    public List<NElement> args() {
        return args;
    }
}
