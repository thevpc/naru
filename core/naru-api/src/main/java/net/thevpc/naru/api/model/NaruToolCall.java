package net.thevpc.naru.api.model;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * A tool call requested by the model inside an assistant message.
 */
public class NaruToolCall {

    private String id;
    private String name;
    private Map<String, Object> arguments;

    public NaruToolCall() {}

    public NaruToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments != null ? arguments : new LinkedHashMap<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }

    /** Convenience: get a string argument value */
    public String getString(String key) {
        Object v = arguments == null ? null : arguments.get(key);
        return v == null ? null : v.toString();
    }

    /** Convenience: get an integer argument value */
    public int getInt(String key, int defaultValue) {
        Object v = arguments == null ? null : arguments.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public String toString() {
        return name + "(" + arguments + ")";
    }
}
