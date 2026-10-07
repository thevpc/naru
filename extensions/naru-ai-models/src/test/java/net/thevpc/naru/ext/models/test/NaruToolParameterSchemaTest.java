package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.anthropic.NaruAnthropicRequestSerializer;
import net.thevpc.naru.ext.models.gemini.NaruGeminiNativeRequestSerializer;
import net.thevpc.naru.ext.models.ollama.NaruOllamaNativeRequestSerializer;
import net.thevpc.naru.ext.models.openai.NaruOpenAiRequestSerializer;
import net.thevpc.naru.api.registry.NaruToolSchema;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * A tool definition is the contract the model reads, so a parameter declared with a
 * nested shape has to arrive at the server as a nested schema.
 *
 * <p>These tests use the {@code plan_create} shape -- an array whose elements are
 * objects, one of whose fields is itself an array and one an enum -- because that is
 * the shape that exposes a shallow serialiser: a flat emitter reduces every parameter
 * to {@code {type, description}}, and the model then sees {@code items: []} with no
 * element schema, no {@code dependsOn}, no {@code validator} enum, and no way to
 * discover that a plan item takes an object at all.
 *
 * <p>Every protocol is covered because the loss is a property of the code, not of one
 * provider: three of the four serializers share one schema writer and must not drift
 * from the fourth.
 */
public class NaruToolParameterSchemaTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    /**
     * The exact parameter tree {@code PlanCreateTool} builds. Kept as a literal rather
     * than importing the tool so this module keeps its dependency surface, and so a
     * change to the tool's definition cannot silently redefine what "correct" means.
     */
    private static NaruToolDefinition planCreate() {
        NaruToolParameter item = NaruToolParameter.object("item", "A single plan item", true,
                NaruToolParameter.string("description", "What this item must accomplish", true).build(),
                NaruToolParameter.string("key", "Short local name other items can reference in dependsOn", false).build(),
                NaruToolParameter.array("dependsOn",
                        "Keys of the items that must finish before this one can start", false,
                        NaruToolParameter.string("depends_on", "Key of a preceding item", true).build()).build(),
                NaruToolParameter.string("validator",
                        "Gate that must pass before this item counts as done", false)
                        .enumValues(Arrays.asList("none", "model_review", "user_approval")).build()
        ).build();
        return new NaruToolDefinitionFunction("plan_create", "Create a new execution plan",
                NaruToolParameter.string("goal", "Overall goal of the plan", true).build(),
                NaruToolParameter.array("items", "The items of the plan", true, item).build()
        );
    }

    // ── the four wire formats, reduced to "where does the schema live" ──────────

    private static NObjectElement ollamaSchema(NaruModelRequest request) {
        return new NaruOllamaNativeRequestSerializer()
                .serialize(request, model(), null).asObject().get()
                .getArray("tools").get().children().get(0).asObject().get()
                .getObject("function").get()
                .getObject("parameters").get();
    }

    private static NObjectElement openApiSchema(NaruModelRequest request) {
        return new NaruOpenAiRequestSerializer()
                .serialize(request, model(), null).asObject().get()
                .getArray("tools").get().children().get(0).asObject().get()
                .getObject("function").get()
                .getObject("parameters").get();
    }

    private static NObjectElement anthropicSchema(NaruModelRequest request) {
        return new NaruAnthropicRequestSerializer()
                .serialize(request, model(), null, null).asObject().get()
                .getArray("tools").get().children().get(0).asObject().get()
                .getObject("input_schema").get();
    }

    private static NObjectElement geminiSchema(NaruModelRequest request) {
        return new NaruGeminiNativeRequestSerializer()
                .serialize(request, model(), null, null).asObject().get()
                .getArray("tools").get().children().get(0).asObject().get()
                .getArray("functionDeclarations").get().children().get(0).asObject().get()
                .getObject("parameters").get();
    }

    private static NaruModelConfig model() {
        return new NaruModelConfig("test", "test-model");
    }

    private static NaruModelRequest requestWith(NaruToolDefinition tool) {
        return new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("make a plan")),
                Collections.singletonList(tool),
                Collections.emptyMap());
    }

    private static List<Function<NaruModelRequest, NObjectElement>> allProtocols() {
        return Arrays.asList(
                NaruToolParameterSchemaTest::ollamaSchema,
                NaruToolParameterSchemaTest::openApiSchema,
                NaruToolParameterSchemaTest::anthropicSchema,
                NaruToolParameterSchemaTest::geminiSchema
        );
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * The reported bug: {@code items} reached Ollama as a bare {@code {"type":"array"}}
     * with no element schema, so the nested item object never left the JVM.
     */
    @Test
    public void arrayParameterKeepsItsElementSchemaOnEveryProtocol() {
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement items = protocol.apply(requestWith(planCreate()))
                    .getObject("properties").get()
                    .get("items").get().asObject().get();
            Assertions.assertEquals("array", items.getStringValue("type").get());
            Assertions.assertEquals("The items of the plan", items.getStringValue("description").get());

            NObjectElement element = items.getObject("items").orNull();
            Assertions.assertNotNull(element,
                    "array parameter lost its element schema: the model cannot tell what a plan item looks like");
            Assertions.assertEquals("object", element.getStringValue("type").get());
            Assertions.assertEquals("A single plan item", element.getStringValue("description").get());
        }
    }

    /**
     * One level deeper: {@code dependsOn} is an array of strings inside an object
     * inside an array, which is the shape most likely to be flattened on the way out.
     */
    @Test
    public void nestedArrayParameterKeepsItsStringElementOnEveryProtocol() {
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement dependsOn = protocol.apply(requestWith(planCreate()))
                    .getObject("properties").get()
                    .get("items").get().asObject().get()
                    .getObject("items").get()
                    .getObject("properties").get()
                    .get("dependsOn").get().asObject().get();

            Assertions.assertEquals("array", dependsOn.getStringValue("type").get());
            NObjectElement key = dependsOn.getObject("items").orNull();
            Assertions.assertNotNull(key, "nested array lost its element type");
            Assertions.assertEquals("string", key.getStringValue("type").get());
            Assertions.assertEquals("Key of a preceding item", key.getStringValue("description").get());
        }
    }

    /**
     * An enum that is dropped becomes a free-text field, so the model is free to
     * invent a validator the tool will reject at execution time.
     */
    @Test
    public void enumValuesSurviveOnEveryProtocol() {
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement validator = protocol.apply(requestWith(planCreate()))
                    .getObject("properties").get()
                    .get("items").get().asObject().get()
                    .getObject("items").get()
                    .getObject("properties").get()
                    .get("validator").get().asObject().get();

            List<String> values = validator.getArray("enum").orNull() == null
                    ? null
                    : validator.getArray("enum").get().children().stream()
                    .map(x -> x.asStringValue().get()).toList();
            Assertions.assertEquals(Arrays.asList("none", "model_review", "user_approval"), values,
                    "enum values were dropped from the wire schema");
        }
    }

    /**
     * Requiredness must be tracked at every depth: a nested object whose own
     * required list is missing lets the model send an item with no description, which
     * the tool then rejects at runtime.
     */
    @Test
    public void requiredListsExistAtEveryDepth() {
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement schema = protocol.apply(requestWith(planCreate()));
            Assertions.assertEquals(Arrays.asList("goal", "items"), strings(schema.getArray("required").get()));

            NObjectElement element = schema.getObject("properties").get()
                    .get("items").get().asObject().get()
                    .getObject("items").get();
            Assertions.assertEquals(Collections.singletonList("description"),
                    strings(element.getArray("required").get()),
                    "nested object lost its own required list");
        }
    }

    @Test
    public void optionalParameterIsNotRequired() {
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement schema = protocol.apply(requestWith(planCreate()));
            List<String> required = strings(schema.getArray("required").get());
            Assertions.assertFalse(required.contains("dependsOn"));
            Assertions.assertFalse(required.contains("key"));
            Assertions.assertFalse(required.contains("validator"));
        }
    }

    /**
     * A tool with no parameters still has to declare a schema. Ollama rejects an empty
     * {@code required} array, which is why the guard below is about {@code required}
     * being absent rather than about {@code properties}.
     */
    @Test
    public void parameterlessToolStillDeclaresAnObjectSchema() {
        NaruToolDefinition noArgs = new NaruToolDefinitionFunction("now", "current time");
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement schema = protocol.apply(requestWith(noArgs));
            Assertions.assertEquals("object", schema.getStringValue("type").get());
            Assertions.assertTrue(schema.getObject("properties").isPresent());
            Assertions.assertFalse(schema.get("required").isPresent(),
                    "an empty required array is rejected by Ollama");
        }
    }

    /**
     * A description is optional on a tool and on a parameter. Emitting a null into
     * the builder is not the same as omitting the key, and Ollama used to take the
     * former path straight from the parameter loop.
     */
    @Test
    public void missingDescriptionsDoNotBreakSerialisation() {
        NaruToolDefinition bare = new NaruToolDefinitionFunction("bare", null,
                NaruToolParameter.string("x", null, false).build());
        for (Function<NaruModelRequest, NObjectElement> protocol : allProtocols()) {
            NObjectElement schema = protocol.apply(requestWith(bare));
            Assertions.assertEquals("object", schema.getStringValue("type").get());
            Assertions.assertEquals("string", schema.getObject("properties").get()
                    .get("x").get().asObject().get().getStringValue("type").get());
            Assertions.assertFalse(schema.getObject("properties").get()
                    .get("x").get().asObject().get().get("description").isPresent());
        }
    }

    // ── constraint keywords: declared on the parameter, dropped by every writer ──

    /**
     * The rest of this class is about the schema reaching the right place. These are
     * about its <em>content</em>: {@link NaruToolParameter} accepts constraints that
     * no writer emitted, so a tool could declare {@code minLength(2)} and the model
     * was told nothing about it.
     */

    @Test
    public void stringConstraintsAreEmitted() {
        NaruToolParameter p = NaruToolParameter.string("name", "a name", false)
                .format("uri").pattern("^[a-z]+$").minLength(2).maxLength(8).build();
        NObjectElement s = NaruToolSchema.paramToSchema(p).asObject().get();
        Assertions.assertEquals("uri", s.getStringValue("format").get());
        Assertions.assertEquals("^[a-z]+$", s.getStringValue("pattern").get());
        Assertions.assertEquals(2, s.getIntValue("minLength").get());
        Assertions.assertEquals(8, s.getIntValue("maxLength").get());
    }

    @Test
    public void numericConstraintsAreEmitted() {
        NaruToolParameter p = NaruToolParameter.number("ratio", "a ratio", false)
                .minimum(0).maximum(1).exclusiveMinimum(0).exclusiveMaximum(1).multipleOf(0.25).build();
        NObjectElement s = NaruToolSchema.paramToSchema(p).asObject().get();
        Assertions.assertEquals(0, s.getIntValue("minimum").get());
        Assertions.assertEquals(1, s.getIntValue("maximum").get());
        Assertions.assertEquals(0, s.getIntValue("exclusiveMinimum").get());
        Assertions.assertEquals(1, s.getIntValue("exclusiveMaximum").get());
        Assertions.assertEquals(0.25, s.getDoubleValue("multipleOf").get());
    }

    @Test
    public void arrayConstraintsAreEmitted() {
        NaruToolParameter p = NaruToolParameter.array("tags", "tags", true,
                        NaruToolParameter.string("tag", "a tag", true).build())
                .minItems(1).maxItems(4).uniqueItems(true).build();
        NObjectElement s = NaruToolSchema.paramToSchema(p).asObject().get();
        Assertions.assertEquals(1, s.getIntValue("minItems").get());
        Assertions.assertEquals(4, s.getIntValue("maxItems").get());
        Assertions.assertEquals(true, s.getBooleanValue("uniqueItems").get());
    }

    /**
     * {@code additionalProperties: false} is the only way to tell a model that an
     * object is closed; without it the model may invent fields.
     */
    @Test
    public void closedObjectIsMarkedClosed() {
        NaruToolParameter p = NaruToolParameter.object("cfg", "config", true,
                        NaruToolParameter.string("a", "a", true).build())
                .additionalProperties(false).build();
        NObjectElement s = NaruToolSchema.paramToSchema(p).asObject().get();
        Assertions.assertEquals(false, s.getBooleanValue("additionalProperties").get());
    }

    @Test
    public void defaultValueIsEmitted() {
        NaruToolParameter p = NaruToolParameter.string("mode", "mode", false, "fast").build();
        NObjectElement s = NaruToolSchema.paramToSchema(p).asObject().get();
        Assertions.assertEquals("fast", s.getStringValue("default").get());
    }

    /**
     * {@code nullable} is emitted as a sibling flag rather than a {@code ["string",
     * "null"]} union: some endpoints reject union types outright, so spelling it the
     * OpenAPI way keeps the request valid everywhere the flag is merely ignored.
     */
    @Test
    public void nullableIsEmittedAsAFlag() {
        NaruToolParameter p = NaruToolParameter.string("opt", "optional", false).nullable().build();
        NObjectElement s = NaruToolSchema.paramToSchema(p).asObject().get();
        Assertions.assertEquals("string", s.getStringValue("type").get());
        Assertions.assertEquals(true, s.getBooleanValue("nullable").get());
    }

    /**
     * Every protocol must agree on the schema, or a tool behaves differently depending
     * on which provider answers. Comparing the writers' output directly is what keeps
     * them from drifting apart again.
     */
    @Test
    public void allProtocolsProduceTheSameParameterSchema() {
        NaruToolParameter tree = NaruToolParameter.string("leaf", "leaf", true).build();
        NaruToolDefinitionFunction tool = new NaruToolDefinitionFunction("t", "d",
                NaruToolParameter.object("obj", "an object", true,
                        NaruToolParameter.array("list", "a list", true, tree).build(),
                        NaruToolParameter.string("kind", "kind", false)
                                .enumValues(Arrays.asList("a", "b")).build()).build());

        String expected = NElementWriter.ofJson()
                .formatPlain(NaruToolSchema.functionSchema(tool.getParams()));
        Assertions.assertEquals(expected, NElementWriter.ofJson().formatPlain(ollamaSchema(requestWith(tool))));
        Assertions.assertEquals(expected, NElementWriter.ofJson().formatPlain(openApiSchema(requestWith(tool))));
        Assertions.assertEquals(expected, NElementWriter.ofJson().formatPlain(anthropicSchema(requestWith(tool))));
        Assertions.assertEquals(expected, NElementWriter.ofJson().formatPlain(geminiSchema(requestWith(tool))));
    }

    private static List<String> strings(NArrayElement array) {
        return array.children().stream().map(x -> x.asStringValue().get()).toList();
    }
}
