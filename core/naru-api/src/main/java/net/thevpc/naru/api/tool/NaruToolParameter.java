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
    public enum Type {
        STRING, INTEGER, BOOLEAN
    }

    private final String name;
    private final Type type;
    private final String description;
    private final Object defaultValue;
    //    private final Map<String, Object> schema;
    private final boolean required;

    private NaruToolParameter(String name, Type type, String description, Object defaultValue, boolean required) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
    }

    public Type getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public static NaruToolParameter string(String name, String description, boolean required) {
        return new NaruToolParameter(name, Type.STRING, description, null, required);
    }

    public static NaruToolParameter string(String name, String description, boolean required, String defaultValue) {
        return new NaruToolParameter(name, Type.STRING, description, defaultValue, required);
    }

    public static NaruToolParameter integer(String name, String description, boolean required) {
        return new NaruToolParameter(name, Type.INTEGER, description, null, required);
    }

    public static NaruToolParameter integer(String name, String description, boolean required, int defaultValue) {
        return new NaruToolParameter(name, Type.INTEGER, description, defaultValue, required);
    }

    public static NaruToolParameter bool(String name, String description, boolean required) {
        return new NaruToolParameter(name, Type.BOOLEAN, description, null, required);
    }

    public static NaruToolParameter bool(String name, String description, boolean required, boolean defaultValue) {
        return new NaruToolParameter(name, Type.BOOLEAN, description, defaultValue, required);
    }

    public String getName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }
}
