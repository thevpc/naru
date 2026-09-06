package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

public abstract class AbstractNaruModelProvider implements NaruModelProvider {
    private final String name;
    private final Map<String, String> params = new HashMap<>();
    private final String[] defaultEnvKeys;

    public AbstractNaruModelProvider(String name,String[] defaultEnvKeys) {
        this.name = name;
        this.defaultEnvKeys = defaultEnvKeys;
    }

    public String[] defaultEnvKey() {
        return defaultEnvKeys;
    }

    public NOptional<String> apiKey(NaruSession session) {
        for (String s : new String[]{"apiKey","apikey","key"}) {
            String key = session.agent().env().get(name() + "."+s).flatMap(NElement::asStringValue).orNull();
            if (!NBlankable.isBlank(key)) {
                return NOptional.of(NStringUtils.strip(key));
            }
        }
        String[] de = defaultEnvKey();
        if (de != null) {
            for (String d : de) {
                if(!NBlankable.isBlank(d)){
                    NOptional<String> z = NOptional.of(NStringUtils.stripToNull(System.getenv(d)));
                    if(z.isPresent()){
                        return z;
                    }
                }
            }
        }
        return NOptional.ofNamedEmpty("api key for "+name());
    }


    @Override
    public String name() {
        return name;
    }

    public boolean isEnabled() {
        NOptional<String> p = getParam("enabled");
        if (!p.isPresent()) {
            return true;
        }
        NOptional<Boolean> b = NLiteral.of(p.get()).asBoolean();
        if (b.isPresent()) {
            return b.get();
        }
        return true;
    }

    public void setEnabled(boolean enabled) {
        setParam("enabled", String.valueOf(enabled));
    }

    @Override
    public void setParam(String name, String value) {
        if (name != null) {
            Object old = params.get(name);
            if (!Objects.equals(old, value)) {
                if (value != null) {
                    params.put(name, value);
                } else {
                    params.remove(name);
                }
                onParamChanged(name, value);
            }
        }
    }

    protected void onParamChanged(String name, String value) {

    }

    @Override
    public Set<String> getParamNames() {
        return new HashMap<>(params).keySet();
    }


    protected NOptional<String> getSecureParam(String name) {
        return NOptional.ofNamed(params.get(name), name);
    }

    @Override
    public NOptional<String> getParam(String name) {
        if ("apikey".toLowerCase().equals(name) || "api_key".toLowerCase().equals(name)) {
            String val = params.get(name);
            if (NBlankable.isBlank(val)) {
                return NOptional.ofEmpty();
            }
            return NOptional.of("sk-***" + val.substring(val.length() - 4));
        }
        return NOptional.ofNamed(params.get(name), name);
    }
}
