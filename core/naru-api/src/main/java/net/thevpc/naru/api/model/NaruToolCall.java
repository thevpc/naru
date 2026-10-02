package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NCopiable;

import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * A tool call requested by the model inside an assistant message.
 */
public class NaruToolCall implements NToElement, NCopiable, Cloneable {

    private String id;
    private String name;
    private Map<String, Object> arguments;

    public NaruToolCall() {
    }

    public NaruToolCall(NElement other) {
        NObjectElement o = other.asObject().get();
        id = o.getStringValue("id").orNull();
        name = o.getStringValue("name").orNull();
        NElement ar = o.get("arguments").orNull();
        if (ar != null && ar.isAnyObject()) {
            arguments = new LinkedHashMap<>();
            for (NElement child : ar.asObject().get().children()) {
                if (child.isNamedPair()) {
                    NPairElement p = child.asPair().get();
                    String k = p.key().asStringValue().orNull();
                    arguments.put(k, readArgumentValue(p.value()));
                }
            }
        }
    }

    /**
     * Reads one argument value back to the {@link Object} it was written from.
     *
     * <p>{@link NElement#simpleOf} is not usable here: on a string value it hands back the
     * node's rendered form, so {@code {path:"A.java"}} reads as {@code "\"A.java\""} and a
     * tool is dispatched with quotes glued to its path. Numbers and booleans come back as
     * nodes too, which leaves {@code getString}/{@code getInt} parsing text they should
     * not have to. Each type is therefore read through its own accessor, and anything
     * richer than a scalar is kept as the element it is -- an argument may legitimately be
     * a nested object, and flattening it would change what the model asked for.
     */
    private static Object readArgumentValue(NElement value) {
        if (value == null) {
            return null;
        }
        if (value.isString()) {
            return value.asStringValue().orNull();
        }
        if (value.isBoolean()) {
            return value.asBooleanValue().orNull();
        }
        if (value.isNumber()) {
            // integral values come back as Integer: model-written line numbers and indices
            // are compared against int-typed APIs far more often than anything uses a Long
            Double d = value.asDoubleValue().orNull();
            if (d != null && d == Math.rint(d) && !d.isInfinite()
                    && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return (int) (double) d;
            }
            return d;
        }
        return value;
    }

    public NaruToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments != null ? arguments : new LinkedHashMap<>();
    }

    @Override
    public NaruToolCall copy() {
        return clone();
    }

    @Override
    protected NaruToolCall clone() {
        try {
            NaruToolCall cloned = (NaruToolCall) super.clone();
            if (cloned.arguments != null) {
                cloned.arguments = new LinkedHashMap<>(arguments);
            }
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public NElement toElement() {
        return NObjectElementBuilder.of()
                .set("id", id)
                .set("name", name)
                // a LinkedHashMap, not a HashMap: argument order must depend on the model,
                // not on hash order, or the same call would hash differently between runs
                .set("arguments", NElement.of(arguments == null ? new LinkedHashMap<>() : arguments))
                .build();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }

    /**
     * Convenience: get a string argument value
     */
    public String getString(String key) {
        Object v = arguments == null ? null : arguments.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * Convenience: get an integer argument value
     */
    public int getInt(String key, int defaultValue) {
        Object v = arguments == null ? null : arguments.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return name + "(" + arguments + ")";
    }
}
