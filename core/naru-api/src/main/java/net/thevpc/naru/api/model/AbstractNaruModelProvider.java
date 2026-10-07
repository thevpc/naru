package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

public abstract class AbstractNaruModelProvider implements NaruModelProvider {
    private String name;
    private String type;
    private final Map<String, String> params = new HashMap<>();
    private final String[] defaultEnvKeys;

    public AbstractNaruModelProvider(String name,String[] defaultEnvKeys) {
        this.name = name;
        this.type = name;
        this.defaultEnvKeys = defaultEnvKeys;
    }

    public String[] defaultEnvKey() {
        return defaultEnvKeys;
    }

    /**
     * Resolution order for this instance's key (design doc §6): the instance's own
     * value ({@code $NAME} references resolved now against the layered env, so a
     * rotated export needs no re-registration) → the layered env itself — session
     * env first, then agent env, then the system environment — first under the
     * instance-scoped name {@code <instance id>.<param>}, then under the type's
     * default env keys. An unresolved {@code $NAME} is not a value: it falls
     * through, which is how a registration "finds its key by itself".
     */
    public NOptional<String> apiKey(NaruSession session) {
        for (String s : new String[]{"apiKey","apikey","key"}) {
            String own = params.get(s);
            if (!NBlankable.isBlank(own)) {
                NOptional<String> resolved = NaruModelRegistration.interpolate(
                        own, NaruModelRegistration.envResolver(session));
                if (resolved.isPresent() && !NBlankable.isBlank(resolved.get())) {
                    return NOptional.of(NStringUtils.strip(resolved.get()));
                }
            }
            String key = NaruModelRegistration.envValue(session, name() + "." + s);
            if (!NBlankable.isBlank(key)) {
                return NOptional.of(key);
            }
        }
        String[] de = defaultEnvKey();
        if (de != null) {
            for (String d : de) {
                String z = NaruModelRegistration.envValue(session, d);
                if (!NBlankable.isBlank(z)) {
                    return NOptional.of(z);
                }
            }
        }
        return NOptional.ofNamedEmpty("api key for "+name());
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return type;
    }

    /**
     * A fresh instance of the same implementation under another id: constructed
     * from the provider's no-arg constructor (constructor defaults, empty params)
     * — nothing is copied from this instance, registration params are applied by
     * the caller afterwards.
     */
    @Override
    public NaruModelProvider newInstance(String id) {
        String nid = id == null ? null : NStringUtils.stripToNull(id);
        if (nid == null) {
            throw new NIllegalArgumentException(NMsg.ofC("missing instance id"));
        }
        try {
            AbstractNaruModelProvider p = (AbstractNaruModelProvider) getClass().getDeclaredConstructor().newInstance();
            p.name = nid;
            p.type = this.type;
            return p;
        } catch (ReflectiveOperationException e) {
            throw new NIllegalArgumentException(NMsg.ofC("cannot create instance '%s' of provider type '%s' : %s", nid, type, e));
        }
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

    /**
     * The stored parameter, unmasked: this is what config resolution reads
     * (the mask of {@link #getParam(String)} is a display concern).
     */
    @Override
    public NOptional<String> rawParam(String name) {
        return NOptional.ofNamed(name == null ? null : params.get(name), name);
    }

    /**
     * Config resolution order for any key of this instance (design doc §6): the
     * instance's own value — {@code $NAME} references resolved now against the
     * layered env (session → agent → system), so a rotated export needs no
     * re-registration — then that same layered env under
     * {@code <instance id>.<key>}. Empty when neither is set (or a {@code $NAME}
     * references an unset variable: an unresolved reference is not a value), so
     * the caller falls through to its own default.
     */
    public NOptional<String> configValue(String key, NaruSession session) {
        if (key != null) {
            String own = params.get(key);
            if (!NBlankable.isBlank(own)) {
                NOptional<String> resolved = NaruModelRegistration.interpolate(
                        own, NaruModelRegistration.envResolver(session));
                if (resolved.isPresent() && !NBlankable.isBlank(resolved.get())) {
                    return NOptional.of(NStringUtils.strip(resolved.get()));
                }
            }
            String env = NaruModelRegistration.envValue(session, name() + "." + key);
            if (!NBlankable.isBlank(env)) {
                return NOptional.of(env);
            }
        }
        return NOptional.ofEmpty();
    }
}
