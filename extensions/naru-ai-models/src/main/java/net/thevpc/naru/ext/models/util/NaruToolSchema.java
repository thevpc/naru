package net.thevpc.naru.ext.models.util;

import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.List;

/**
 * Turns a {@link NaruToolParameter} tree into the JSON-Schema fragment that every
 * NARU protocol embeds in its tool declaration.
 *
 * <p>This lives in a protocol-neutral package on purpose. The writer used to be a
 * static method on the OpenAI-compatible serializer, and because of that the Ollama
 * native serializer grew its own inline copy instead of calling it -- an inline copy
 * that emitted only {@code type} and {@code description}. Nested structure therefore
 * disappeared for Ollama only: {@code plan_create} reached the model with
 * {@code items: {"type":"array"}} and no element schema at all, so the shape of a plan
 * item was unrecoverable from the request. A schema writer that belongs to no protocol
 * cannot be skipped by one of them.
 */
public class NaruToolSchema {

    private NaruToolSchema() {
    }

    /**
     * The schema for a single parameter, recursively.
     *
     * <p>Every keyword {@link NaruToolParameter} can express is emitted here. A
     * parameter declared with a constraint the writer does not know about is not a
     * harmless no-op: the model is told nothing about it and will happily produce a
     * value the tool rejects at execution time.
     */
    public static NElement paramToSchema(NaruToolParameter p) {
        NObjectElementBuilder schema = NElement.ofObjectBuilder();
        schema.set("type", p.getType() != null ? p.getType().name().toLowerCase() : "string");
        if (p.getDescription() != null) {
            schema.set("description", p.getDescription());
        }
        if (p.getDefaultValue() != null) {
            schema.set("default", NElement.of(p.getDefaultValue()));
        }
        if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
            NArrayElementBuilder enumArr = NElement.ofArrayBuilder();
            for (Object val : p.getEnumValues()) {
                enumArr.add(NElement.of(val));
            }
            schema.set("enum", enumArr.build());
        }
        if (p.isNullable()) {
            // OpenAPI's spelling rather than a ["type","null"] union: some endpoints
            // reject union types outright, and losing "nullable" costs the model a
            // hint while the union costs it the whole request.
            schema.set("nullable", true);
        }

        switch (p.getType()) {
            case ARRAY:
                arraySchema(schema, p);
                break;
            case OBJECT:
                objectSchema(schema, p);
                break;
            case STRING:
                stringSchema(schema, p);
                break;
            case INTEGER:
            case NUMBER:
                numericSchema(schema, p);
                break;
            default:
                break;
        }
        return schema.build();
    }

    private static void arraySchema(NObjectElementBuilder schema, NaruToolParameter p) {
        if (p.getItemType() != null) {
            schema.set("items", paramToSchema(p.getItemType()));
        } else {
            // An array with no declared element type is legal but ambiguous; say
            // "strings" rather than emitting "items":{} and letting the server guess.
            schema.set("items", NElement.ofObjectBuilder().set("type", "string").build());
        }
        if (p.getMinItems() != null) {
            schema.set("minItems", p.getMinItems());
        }
        if (p.getMaxItems() != null) {
            schema.set("maxItems", p.getMaxItems());
        }
        if (p.getUniqueItems() != null) {
            schema.set("uniqueItems", p.getUniqueItems());
        }
    }

    private static void objectSchema(NObjectElementBuilder schema, NaruToolParameter p) {
        NObjectElementBuilder nestedProps = NElement.ofObjectBuilder();
        NArrayElementBuilder nestedRequired = NElement.ofArrayBuilder();
        if (p.getProperties() != null) {
            for (NaruToolParameter np : p.getProperties()) {
                nestedProps.set(np.getName(), paramToSchema(np));
                if (np.isRequired()) {
                    nestedRequired.add(NElement.ofString(np.getName()));
                }
            }
        }
        schema.set("properties", nestedProps.build());
        if (!nestedRequired.children().isEmpty()) {
            schema.set("required", nestedRequired.build());
        }
        if (p.getAdditionalProperties() != null) {
            // false is the load-bearing value: it is the only way to tell the model
            // the object is closed and must not grow invented fields.
            schema.set("additionalProperties", p.getAdditionalProperties());
        }
    }

    private static void stringSchema(NObjectElementBuilder schema, NaruToolParameter p) {
        if (p.getFormat() != null) {
            schema.set("format", p.getFormat());
        }
        if (p.getPattern() != null) {
            schema.set("pattern", p.getPattern());
        }
        if (p.getMinLength() != null) {
            schema.set("minLength", p.getMinLength());
        }
        if (p.getMaxLength() != null) {
            schema.set("maxLength", p.getMaxLength());
        }
    }

    private static void numericSchema(NObjectElementBuilder schema, NaruToolParameter p) {
        if (p.getMinimum() != null) {
            schema.set("minimum", NElement.of(p.getMinimum()));
        }
        if (p.getMaximum() != null) {
            schema.set("maximum", NElement.of(p.getMaximum()));
        }
        if (p.getExclusiveMinimum() != null) {
            schema.set("exclusiveMinimum", NElement.of(p.getExclusiveMinimum()));
        }
        if (p.getExclusiveMaximum() != null) {
            schema.set("exclusiveMaximum", NElement.of(p.getExclusiveMaximum()));
        }
        if (p.getMultipleOf() != null) {
            schema.set("multipleOf", NElement.of(p.getMultipleOf()));
        }
    }

    /**
     * The {@code parameters} / {@code input_schema} block for a whole tool.
     *
     * <p>{@code required} is emitted only when non-empty: Ollama answers 400 Bad
     * Request to an empty {@code required} array.
     */
    public static NElement functionSchema(List<NaruToolParameter> params) {
        NObjectElementBuilder schema = NElement.ofObjectBuilder();
        schema.set("type", "object");
        NObjectElementBuilder properties = NElement.ofObjectBuilder();
        NArrayElementBuilder required = NElement.ofArrayBuilder();
        if (params != null) {
            for (NaruToolParameter p : params) {
                properties.set(p.getName(), paramToSchema(p));
                if (p.isRequired()) {
                    required.add(NElement.ofString(p.getName()));
                }
            }
        }
        schema.set("properties", properties.build());
        if (!required.children().isEmpty()) {
            schema.set("required", required.build());
        }
        return schema.build();
    }
}
