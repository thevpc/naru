package net.thevpc.naru.api.store;

import net.thevpc.naru.api.agent.NaruSessionNameSource;
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
    private NaruSessionNameSource nameSource;
    private Instant creationInstant;
    private Instant modificationInstant;
    private NElement model;
    private String sessionDir;
    private String workingDir;
    private String projectId;
    private String user;
    private long version;
    private final Map<String, Object> env = new LinkedHashMap<>();

    public NaruSessionData() {
    }

    public static NaruSessionData of(NElement element) {
        NaruSessionData d = new NaruSessionData();
        NObjectElement o = element.asObject().get();
        d.uuid = o.getStringValue("uuid").orNull();
        d.name = o.getStringValue("name").orNull();
        d.nameSource = NaruSessionNameSource.parse(o.getStringValue("nameSource").orNull());
        d.creationInstant = o.getInstantValue("creationDate").orNull();
        d.modificationInstant = o.getInstantValue("modificationDate").orNull();
        d.model = o.get("model").orNull();
        if (d.model != null && d.model.isNull()) {
            d.model = null;
        }
        d.sessionDir = o.getStringValue("sessionDir").orNull();
        d.workingDir = o.getStringValue("workingDir").orNull();
        d.projectId = blankToNull(o.getStringValue("projectId").orNull());
        d.user = blankToNull(o.getStringValue("user").orNull());
        d.version = o.getLongValue("version").orElse(0L);
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

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
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

    /**
     * Who chose {@link #name()}. Null means the record predates the field, which the
     * engine reads as {@link NaruSessionNameSource#USER} rather than as a placeholder --
     * the safest reading, since the cost is only that such a session never gets an
     * automatic title.
     */
    public NaruSessionNameSource nameSource() {
        return nameSource;
    }

    public NaruSessionData nameSource(NaruSessionNameSource nameSource) {
        this.nameSource = nameSource;
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

    public String sessionDir() {
        return sessionDir;
    }

    public NaruSessionData sessionDir(String sessionDir) {
        this.sessionDir = sessionDir;
        return this;
    }

    public String workingDir() {
        return workingDir;
    }

    public NaruSessionData workingDir(String workingDir) {
        this.workingDir = workingDir;
        return this;
    }

    /** The owning project id, or null for a standalone session. A label only: storage never moves with it. */
    public String projectId() {
        return projectId;
    }

    public NaruSessionData projectId(String projectId) {
        this.projectId = blankToNull(projectId);
        return this;
    }

    /** The opaque owner string, or null. Stored and filterable only: no authentication, no permissions. */
    public String user() {
        return user;
    }

    public NaruSessionData user(String user) {
        this.user = blankToNull(user);
        return this;
    }

    /** Monotonic checkpoint version; absent legacy values are interpreted as zero. */
    public long version() { return version; }

    public NaruSessionData version(long version) {
        this.version = version;
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
        if (nameSource != null) {
            o.set("nameSource", nameSource.name());
        }
        o.set("creationDate", NElement.ofInstant(creationInstant));
        o.set("modificationDate", NElement.ofInstant(modificationInstant));
        o.set("model", model);
        o.set("sessionDir", sessionDir);
        o.set("workingDir", workingDir);
        if (projectId != null) {
            // new optional keys are omitted rather than nulled when absent: a missing key
            // and a stored null mean the same thing to readers, and the omission keeps
            // session.tson honest about what the session actually carries
            o.set("projectId", projectId);
        }
        if (user != null) {
            o.set("user", user);
        }
        o.set("version", version);
        NObjectElementBuilder envBuilder = NObjectElementBuilder.of();
        for (Map.Entry<String, Object> e : env.entrySet()) {
            envBuilder.add(e.getKey(), NElement.of(e.getValue()));
        }
        o.set("env", envBuilder.build());
        return o.build();
    }
}
