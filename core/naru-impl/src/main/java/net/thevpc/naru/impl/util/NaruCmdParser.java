package net.thevpc.naru.impl.util;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

public class NaruCmdParser {
    private String name;
    private final List<NElement> args = new ArrayList<>();

    public NaruCmdParser(String command) {
        String c = command == null ? "" : command.trim();
        int i = NStringUtils.firstIndexOf(c, ' ', '\t', '(');
        if (i >= 0) {
            if (c.charAt(i) == '(') {
                //it all tson
                NElement u = NElementReader.ofTson().read(c);
                if (u.isNamedUplet()) {
                    NUpletElement uu = u.asUplet().get();
                    name = uu.name().get();
                    args.addAll(uu.params());
                } else {
                    args.add(u);
                }
            } else {
                name = c.substring(0, i).trim();
                String rest = c.substring(i + 1).trim();
                if (!rest.isEmpty()) {
                    if (NaruUtils.isPath(rest)) {
                        args.add(NElement.ofString(rest.trim()));
                    } else {
                        NElement u = NElementReader.ofTson().read(rest);
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
            }
        } else {
            name = "";
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
    }


    public String name() {
        return name;
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
