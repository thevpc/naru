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
        // Unmodifiable map ensures immutability and safe hashCode/equals behavior
        this.entries = Collections.unmodifiableMap(new HashMap<>(entries));
        this.hashCode = this.entries.hashCode();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Checks if these recorded labels contain all key-value pairs of the query.
     * Example: recorded {app="a", module="b"} matches query {app="a"}.
     */
    public boolean matches(Labels query) {
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
                throw new IllegalArgumentException("Label key cannot be null or empty");
            }
            if (value != null) {
                entries.put(key, value);
            }
            return this;
        }

        public Labels build() {
            return new Labels(entries);
        }
    }
}
