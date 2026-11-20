package net.thevpc.naru.api.store;

import net.thevpc.nuts.io.NPath;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Configuration for one named data-store namespace. */
public final class NaruDataStoreConfig {
    private final String namespace;
    private final NPath projectDir;
    private final NPath scopeDir;
    private final NPath location;
    private final Map<String, String> properties;

    public NaruDataStoreConfig(String namespace, NPath projectDir, NPath scopeDir,
                               NPath location, Map<String, String> properties) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        this.namespace = namespace;
        this.projectDir = projectDir;
        this.scopeDir = scopeDir;
        this.location = location;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties == null ? Map.of() : properties));
    }

    public String namespace() { return namespace; }
    public NPath projectDir() { return projectDir; }
    public NPath scopeDir() { return scopeDir; }
    /** Provider-owned durable location, normally under {@code .naru/stores/<namespace>}. */
    public NPath location() { return location; }
    public Map<String, String> properties() { return properties; }
    public String property(String name) { return properties.get(name); }
}
