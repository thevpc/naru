package net.thevpc.naru.ext.budget;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of dimensional keys (labels/tags).
 * Designed with a builder for safe, readable construction.
 */
public final class Labels {
    private final Map<String, String> entries;
    private final int hashCode;

    private Labels(Map<String, String> entries) {
        this.entries = Collections.unmodifiableMap(new HashMap<>(entries));
        this.hashCode = this.entries.hashCode();
    }

    public static Labels of(String k1, String v1) {
        return builder().add(k1, v1).build();
    }

    public static Labels of(String k1, String v1, String k2, String v2) {
        return builder().add(k1, v1).add(k2, v2).build();
    }

    public static Labels of(String k1, String v1, String k2, String v2, String k3, String v3) {
        return builder().add(k1, v1).add(k2, v2).add(k3, v3).build();
    }

    public static Labels of(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return builder().build();
        }
        Builder b = builder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            b.add(e.getKey(), e.getValue());
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(Labels query) {
        if (query == null || query.entries.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> e : query.entries.entrySet()) {
            if (!Objects.equals(e.getValue(), this.entries.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public Map<String, String> asMap() {
        return entries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Labels labels = (Labels) o;
        return entries.equals(labels.entries);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        entries.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
        if (!entries.isEmpty()) sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }

    public static final class Builder {
        private final Map<String, String> entries = new HashMap<>();

        public Builder add(String key, String value) {
            if (key == null || key.trim().isEmpty()) {
                return this;
            }
            if (value != null) {
                entries.put(key, value);
            } else {
                entries.remove(key);
            }
            return this;
        }

        public Labels build() {
            return new Labels(entries);
        }
    }
}
