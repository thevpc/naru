package net.thevpc.naru.api.registry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        STRING, INTEGER, BOOLEAN, NUMBER, ARRAY, OBJECT
    }

    // ── core ────────────────────────────────────────────────────────────────
    private final String name;
    private final Type type;
    private final String description;
    private final Object defaultValue;
    private final boolean required;

    // ── any type ─────────────────────────────────────────────────────────────
    private final List<Object> enumValues;       // allowed values
    private final boolean nullable;              // type | null

    // ── STRING ───────────────────────────────────────────────────────────────
    private final String format;                 // "uri", "date-time", "email", "uuid", ...
    private final Integer minLength;
    private final Integer maxLength;
    private final String pattern;                // regex

    // ── INTEGER / NUMBER ──────────────────────────────────────────────────────
    private final Number minimum;
    private final Number maximum;
    private final Number exclusiveMinimum;
    private final Number exclusiveMaximum;
    private final Number multipleOf;

    // ── ARRAY ────────────────────────────────────────────────────────────────
    private final NaruToolParameter itemType;    // element type
    private final Integer minItems;
    private final Integer maxItems;
    private final Boolean uniqueItems;

    // ── OBJECT ───────────────────────────────────────────────────────────────
    private final List<NaruToolParameter> properties;
    private final Boolean additionalProperties;  // false = closed object

    private NaruToolParameter(Builder b) {
        this.name                = b.name;
        this.type                = b.type;
        this.description         = b.description;
        this.defaultValue        = b.defaultValue;
        this.required            = b.required;
        this.enumValues          = b.enumValues;
        this.nullable            = b.nullable;
        this.format              = b.format;
        this.minLength           = b.minLength;
        this.maxLength           = b.maxLength;
        this.pattern             = b.pattern;
        this.minimum             = b.minimum;
        this.maximum             = b.maximum;
        this.exclusiveMinimum    = b.exclusiveMinimum;
        this.exclusiveMaximum    = b.exclusiveMaximum;
        this.multipleOf          = b.multipleOf;
        this.itemType            = b.itemType;
        this.minItems            = b.minItems;
        this.maxItems            = b.maxItems;
        this.uniqueItems         = b.uniqueItems;
        this.properties          = b.properties;
        this.additionalProperties = b.additionalProperties;
    }

// ── getters ──────────────────────────────────────────────────────────────

    public String getName()                       { return name; }
    public Type getType()                         { return type; }
    public String getDescription()                { return description; }
    public Object getDefaultValue()               { return defaultValue; }
    public boolean isRequired()                   { return required; }
    public List<Object> getEnumValues()           { return enumValues; }
    public boolean isNullable()                   { return nullable; }
    public String getFormat()                     { return format; }
    public Integer getMinLength()                 { return minLength; }
    public Integer getMaxLength()                 { return maxLength; }
    public String getPattern()                    { return pattern; }
    public Number getMinimum()                    { return minimum; }
    public Number getMaximum()                    { return maximum; }
    public Number getExclusiveMinimum()           { return exclusiveMinimum; }
    public Number getExclusiveMaximum()           { return exclusiveMaximum; }
    public Number getMultipleOf()                 { return multipleOf; }
    public NaruToolParameter getItemType()        { return itemType; }
    public Integer getMinItems()                  { return minItems; }
    public Integer getMaxItems()                  { return maxItems; }
    public Boolean getUniqueItems()               { return uniqueItems; }
    public List<NaruToolParameter> getProperties(){ return properties; }
    public Boolean getAdditionalProperties()      { return additionalProperties; }

    ////////////////////////////////

    public static Builder string(String name, String description, boolean required) {
        return new Builder(name, Type.STRING, description, required);
    }

    public static Builder string(String name, String description, boolean required, String defaultValue) {
        return new Builder(name, Type.STRING, description, required).defaultValue(defaultValue);
    }

    public static Builder integer(String name, String description, boolean required) {
        return new Builder(name, Type.INTEGER, description, required);
    }

    public static Builder integer(String name, String description, boolean required, int defaultValue) {
        return new Builder(name, Type.INTEGER, description, required).defaultValue(defaultValue);
    }

    public static Builder number(String name, String description, boolean required) {
        return new Builder(name, Type.NUMBER, description, required);
    }

    public static Builder number(String name, String description, boolean required, Number defaultValue) {
        return new Builder(name, Type.NUMBER, description, required).defaultValue(defaultValue);
    }

    public static Builder bool(String name, String description, boolean required) {
        return new Builder(name, Type.BOOLEAN, description, required);
    }

    public static Builder bool(String name, String description, boolean required, boolean defaultValue) {
        return new Builder(name, Type.BOOLEAN, description, required).defaultValue(defaultValue);
    }

    public static Builder array(String name, String description, boolean required, NaruToolParameter itemType) {
        return new Builder(name, Type.ARRAY, description, required).itemType(itemType);
    }

    public static Builder object(String name, String description, boolean required, NaruToolParameter... properties) {
        return new Builder(name, Type.OBJECT, description, required).properties(Arrays.asList(properties));
    }
    ///////////////////////////////////////////

    public static class Builder {
        private final String name;
        private final Type type;
        private final String description;
        private final boolean required;

        private Object defaultValue;
        private List<Object> enumValues;
        private boolean nullable;
        private String format;
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private Number minimum;
        private Number maximum;
        private Number exclusiveMinimum;
        private Number exclusiveMaximum;
        private Number multipleOf;
        private NaruToolParameter itemType;
        private Integer minItems;
        private Integer maxItems;
        private Boolean uniqueItems;
        private List<NaruToolParameter> properties;
        private Boolean additionalProperties;

        private Builder(String name, Type type, String description, boolean required) {
            this.name        = name;
            this.type        = type;
            this.description = description;
            this.required    = required;
        }

        public Builder defaultValue(Object v)                      { this.defaultValue = v;            return this; }
        public Builder enumValues(List<Object> v)                  { this.enumValues = v;              return this; }
        public Builder enumValues(Object... v)                     { this.enumValues = new ArrayList<>(Arrays.asList(v)); return this; }
        public Builder nullable()                                  { this.nullable = true;             return this; }
        public Builder format(String v)                            { this.format = v;                  return this; }
        public Builder minLength(int v)                            { this.minLength = v;               return this; }
        public Builder maxLength(int v)                            { this.maxLength = v;               return this; }
        public Builder pattern(String v)                           { this.pattern = v;                 return this; }
        public Builder minimum(Number v)                           { this.minimum = v;                 return this; }
        public Builder maximum(Number v)                           { this.maximum = v;                 return this; }
        public Builder exclusiveMinimum(Number v)                  { this.exclusiveMinimum = v;        return this; }
        public Builder exclusiveMaximum(Number v)                  { this.exclusiveMaximum = v;        return this; }
        public Builder multipleOf(Number v)                        { this.multipleOf = v;              return this; }
        public Builder itemType(NaruToolParameter v)               { this.itemType = v;                return this; }
        public Builder minItems(int v)                             { this.minItems = v;                return this; }
        public Builder maxItems(int v)                             { this.maxItems = v;                return this; }
        public Builder uniqueItems(boolean v)                      { this.uniqueItems = v;             return this; }
        public Builder properties(List<NaruToolParameter> v)       { this.properties = v;              return this; }
        public Builder properties(NaruToolParameter... v)          { this.properties = new ArrayList<>(Arrays.asList(v)); return this; }
        public Builder additionalProperties(boolean v)             { this.additionalProperties = v;    return this; }

        public NaruToolParameter build() { return new NaruToolParameter(this); }
    }

}
