package net.thevpc.naru.api.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder for a single parameter inside a JSON schema "properties" block.
 *
 * <pre>
 *   ToolParameter.string("path", "Absolute or relative file path", true)
 *   ToolParameter.integer("timeout_seconds", "Max wait time", false)
 * </pre>
 */
public class NaruToolParameter {

    private final String name;
    private final Map<String, Object> schema;
    private final boolean required;

    private NaruToolParameter(String name, Map<String, Object> schema, boolean required) {
        this.name = name;
        this.schema = schema;
        this.required = required;
    }

    public static NaruToolParameter string(String name, String description, boolean required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        s.put("description", description);
        return new NaruToolParameter(name, s, required);
    }

    public static NaruToolParameter integer(String name, String description, boolean required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "integer");
        s.put("description", description);
        return new NaruToolParameter(name, s, required);
    }

    public static NaruToolParameter bool(String name, String description, boolean required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "boolean");
        s.put("description", description);
        return new NaruToolParameter(name, s, required);
    }

    public String getName() { return name; }
    public Map<String, Object> getSchema() { return schema; }
    public boolean isRequired() { return required; }
}
