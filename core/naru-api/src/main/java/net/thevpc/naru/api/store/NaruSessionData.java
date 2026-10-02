package net.thevpc.naru.api.store;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A session's metadata, as a value.
 *
 * <p>No methods that do work, no back-references, no knowledge of where it will be stored.
 * The engine hands one of these to the store and gets one back; neither side has to be
 * constructed before the other.
 */
public class NaruSessionData implements NToElement {

    private String uuid;
    private String name;
    private Instant creationInstant;
    private Instant modificationInstant;
    private NElement model;
    private String projectDir;
    private String workingDir;
    private final Map<String, Object> env = new LinkedHashMap<>();

    public NaruSessionData() {
    }

    public static NaruSessionData of(NElement element) {
        NaruSessionData d = new NaruSessionData();
        NObjectElement o = element.asObject().get();
        d.uuid = o.getStringValue("uuid").orNull();
        d.name = o.getStringValue("name").orNull();
        d.creationInstant = o.getInstantValue("creationDate").orNull();
        d.modificationInstant = o.getInstantValue("modificationDate").orNull();
        d.model = o.get("model").orNull();
        if (d.model != null && d.model.isNull()) {
            d.model = null;
        }
        d.projectDir = o.getStringValue("projectDir").orNull();
        d.workingDir = o.getStringValue("workingDir").orNull();
        NElement e = o.get("env").orNull();
        if (e != null && e.isAnyObject()) {
            for (NElement child : e.asObject().get().children()) {
                if (child.isNamedPair()) {
                    d.env.put(child.asPair().get().key().asStringValue().orNull(),
                            plainValue(child.asPair().get().value()));
                }
            }
        }
        return d;
    }

    /**
     * The Java value behind a stored one.
     *
     * <p>Env entries are stored as TSON, so reading them back naturally yields
     * {@link NElement}s. Handing those to a caller who put in a {@code String} would make
     * {@code env().get(k).equals("x")} silently false, which is the kind of thing that only
     * shows up as a missing environment variable three layers away. Scalars therefore come
     * back as scalars, and anything structured stays an element rather than losing its shape.
     */
    private static Object plainValue(NElement element) {
        if (element.isNull()) {
            return null;
        }
        if (element.isString()) {
            return element.asStringValue().orNull();
        }
        if (element.isNumber()) {
            Object number = element.asNumberValue().orNull();
            if (number instanceof Integer) {
                return ((Integer) number).longValue();
            }
            return number;
        }
        if (element.isBoolean()) {
            return element.asBooleanValue().orNull();
        }
        return NElement.simpleOf(element);
    }

    public String uuid() {
        return uuid;
    }

    public NaruSessionData uuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public String name() {
        return name;
    }

    public NaruSessionData name(String name) {
        this.name = name;
        return this;
    }

    public Instant creationInstant() {
        return creationInstant;
    }

    public NaruSessionData creationInstant(Instant creationInstant) {
        this.creationInstant = creationInstant;
        return this;
    }

    public Instant modificationInstant() {
        return modificationInstant;
    }

    public NaruSessionData modificationInstant(Instant modificationInstant) {
        this.modificationInstant = modificationInstant;
        return this;
    }

    public NElement model() {
        return model;
    }

    public NaruSessionData model(NElement model) {
        this.model = model;
        return this;
    }

    public String projectDir() {
        return projectDir;
    }

    public NaruSessionData projectDir(String projectDir) {
        this.projectDir = projectDir;
        return this;
    }

    public String workingDir() {
        return workingDir;
    }

    public NaruSessionData workingDir(String workingDir) {
        this.workingDir = workingDir;
        return this;
    }

    public Map<String, Object> env() {
        return env;
    }

    public NaruSessionData env(Map<String, Object> env) {
        this.env.clear();
        if (env != null) {
            this.env.putAll(env);
        }
        return this;
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("uuid", uuid);
        o.set("name", name);
        o.set("creationDate", NElement.ofInstant(creationInstant));
        o.set("modificationDate", NElement.ofInstant(modificationInstant));
        o.set("model", model);
        o.set("projectDir", projectDir);
        o.set("workingDir", workingDir);
        NObjectElementBuilder envBuilder = NObjectElementBuilder.of();
        for (Map.Entry<String, Object> e : env.entrySet()) {
            envBuilder.add(e.getKey(), NElement.of(e.getValue()));
        }
        o.set("env", envBuilder.build());
        return o.build();
    }
}