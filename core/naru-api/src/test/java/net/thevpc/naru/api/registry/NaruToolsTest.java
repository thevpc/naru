package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.annotations.NaruArg;
import net.thevpc.naru.api.annotations.NaruToolSpec;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class NaruToolsTest {

    @BeforeAll
    static void setUpWorkspace() {
        Nuts.require();
    }

    public enum Priority {LOW, HIGH}

    public record Item(String sku, int quantity) {
    }

    public static class Shop {
        static final AtomicInteger CREATED = new AtomicInteger();

        public Shop() {
            CREATED.incrementAndGet();
        }

        @NaruToolSpec(description = "Place an order.", tags = "write")
        public String placeOrder(@NaruArg(name = "customer", description = "Who orders") String customer,
                                 @NaruArg(name = "items") List<Item> items,
                                 @NaruArg(name = "priority") Priority priority,
                                 @NaruArg(name = "note") Optional<String> note,
                                 @NaruArg(name = "copies", defaultValue = "1") int copies,
                                 NaruToolCallContext context) {
            int units = items.stream().mapToInt(Item::quantity).sum();
            return customer + ":" + units + ":" + priority + ":" + note.orElse("-") + ":" + copies
                    + ":" + (context != null);
        }

        @NaruToolSpec(name = "stock", description = "Stock of a product.", tags = "fs")
        public Item stock(@NaruArg(name = "sku") String sku) {
            return new Item(sku, 7);
        }

        @NaruToolSpec(description = "Fails.", tags = "fs")
        public void explode() {
            throw new IllegalStateException("boom");
        }
    }

    public static class StaticOnly {
        public StaticOnly() {
            throw new AssertionError("a class of static tools must never be instantiated");
        }

        @NaruToolSpec(description = "Add two numbers.", essential = true)
        public static long add(@NaruArg(name = "a") long a, @NaruArg(name = "b") long b) {
            return a + b;
        }
    }

    public static class Untagged {
        @NaruToolSpec(description = "Nobody could ever see me.")
        public static void hidden() {
        }
    }

    public static class Private {
        @NaruToolSpec(description = "Not public.", tags = "fs")
        static void secret() {
        }
    }

    @Test
    public void theSchemaComesFromTheSignature() {
        Map<String, NaruTool> tools = byName(NaruTools.tools(Shop.class, Shop::new));
        Assertions.assertEquals(List.of("explode", "place_order", "stock"), List.copyOf(tools.keySet()),
                "names default to the method name in snake_case, explicit names win");
        NaruToolDefinitionFunction def = (NaruToolDefinitionFunction) tools.get("place_order").getDefinition(null);
        Map<String, NaruToolParameter> params = def.getParams().stream()
                .collect(Collectors.toMap(NaruToolParameter::getName, p -> p, (a, b) -> a, java.util.LinkedHashMap::new));
        Assertions.assertEquals(List.of("customer", "items", "priority", "note", "copies"), List.copyOf(params.keySet()),
                "the injected context is not shown to the model");
        Assertions.assertEquals(NaruToolParameter.Type.STRING, params.get("customer").getType());
        Assertions.assertEquals("Who orders", params.get("customer").getDescription());
        Assertions.assertTrue(params.get("customer").isRequired());
        Assertions.assertEquals(NaruToolParameter.Type.ARRAY, params.get("items").getType());
        NaruToolParameter item = params.get("items").getItemType();
        Assertions.assertEquals(NaruToolParameter.Type.OBJECT, item.getType());
        Assertions.assertEquals(List.of("sku", "quantity"),
                item.getProperties().stream().map(NaruToolParameter::getName).toList());
        Assertions.assertEquals(List.of("LOW", "HIGH"),
                params.get("priority").getEnumValues().stream().map(e -> e.asStringValue().get()).toList());
        Assertions.assertFalse(params.get("note").isRequired(), "Optional is optional");
        Assertions.assertFalse(params.get("copies").isRequired(), "a default makes it optional");
        Assertions.assertEquals(NaruToolParameter.Type.INTEGER, params.get("copies").getType());
        Assertions.assertEquals(Set_of("write"), tools.get("place_order").tags());
    }

    @Test
    public void argumentsAreConvertedAndInjected() {
        Map<String, NaruTool> tools = byName(NaruTools.tools(Shop.class, Shop::new));
        String out = tools.get("place_order").execute(call(Map.of(
                "customer", NElement.ofString("ada"),
                "items", NElement.of(List.of(Map.of("sku", "a", "quantity", 2), Map.of("sku", "b", "quantity", 3))),
                "priority", NElement.ofString("high"))));
        Assertions.assertEquals("ada:5:HIGH:-:1:true", out);
    }

    @Test
    public void resultsErrorsAndMissingArgumentsBecomeText() {
        Map<String, NaruTool> tools = byName(NaruTools.tools(Shop.class, Shop::new));
        String json = tools.get("stock").execute(call(Map.of("sku", NElement.ofString("x1"))));
        Assertions.assertTrue(json.contains("\"sku\"") && json.contains("x1") && json.contains("7"), json);
        Assertions.assertEquals("ERROR: boom", tools.get("explode").execute(call(Map.of())));
        Assertions.assertTrue(tools.get("stock").execute(call(Map.of())).startsWith("ERROR: missing required argument 'sku'"));
        String bad = tools.get("place_order").execute(call(Map.of(
                "customer", NElement.ofString("ada"), "items", NElement.of(List.of()),
                "priority", NElement.ofString("urgent"))));
        Assertions.assertTrue(bad.startsWith("ERROR: invalid value for argument 'priority'"), bad);
    }

    @Test
    public void staticToolsNeverInstantiateTheirClass() {
        NaruToolsetProvider provider = NaruTools.provider(StaticOnly.class);
        NaruToolset toolset = provider.createToolset(StaticOnly.class.getName(), null);
        NaruTool add = toolset.tools().get(0);
        Assertions.assertTrue(add.isEssential());
        Assertions.assertEquals("5", add.execute(call(Map.of("a", NElement.of(2), "b", NElement.ofString("3")))));
    }

    @Test
    public void instanceToolsShareOneLazyInstancePerToolset() {
        Shop.CREATED.set(0);
        NaruToolset toolset = NaruTools.provider(Shop.class).createToolset(Shop.class.getName(), null);
        Assertions.assertEquals(0, Shop.CREATED.get(), "nothing is created before a call");
        Map<String, NaruTool> tools = byName(toolset.tools());
        tools.get("stock").execute(call(Map.of("sku", NElement.ofString("a"))));
        tools.get("stock").execute(call(Map.of("sku", NElement.ofString("b"))));
        Assertions.assertEquals(1, Shop.CREATED.get());
    }

    @Test
    public void mistakesAreReportedWhenTheToolsAreBuilt() {
        IllegalArgumentException untagged = Assertions.assertThrows(IllegalArgumentException.class,
                () -> NaruTools.provider(Untagged.class));
        Assertions.assertTrue(untagged.getMessage().contains("fail-closed"), untagged.getMessage());
        IllegalArgumentException hidden = Assertions.assertThrows(IllegalArgumentException.class,
                () -> NaruTools.provider(Private.class));
        Assertions.assertTrue(hidden.getMessage().contains("must be public"), hidden.getMessage());
    }

    @Test
    public void snakeCaseNames() {
        Assertions.assertEquals("lookup_order", NaruMethodTool.snakeCase("lookupOrder"));
        Assertions.assertEquals("parse_url_now", NaruMethodTool.snakeCase("parseURLNow"));
        Assertions.assertEquals("add", NaruMethodTool.snakeCase("add"));
    }

    private static java.util.Set<String> Set_of(String... s) {
        return java.util.Set.of(s);
    }

    private static Map<String, NaruTool> byName(List<NaruTool> tools) {
        Map<String, NaruTool> m = new java.util.LinkedHashMap<>();
        tools.forEach(t -> m.put(t.name(), t));
        return m;
    }

    private static NaruToolCallContext call(Map<String, NElement> args) {
        return new NaruToolCallContext() {
            @Override
            public Map<String, NElement> arguments() {
                return args;
            }

            @Override
            public NaruTask task() {
                return null;
            }

            @Override
            public NOptional<NElement> arg(String name) {
                return NOptional.ofNamed(args.get(name), name);
            }

            @Override
            public NOptional<String> stringArg(String name) {
                return arg(name).flatMap(NElement::asStringValue);
            }

            @Override
            public NOptional<Number> numberArg(String name) {
                return NOptional.ofNamedEmpty(name);
            }

            @Override
            public NOptional<Integer> intArg(String name) {
                return NOptional.ofNamedEmpty(name);
            }

            @Override
            public NOptional<Long> longArg(String name) {
                return NOptional.ofNamedEmpty(name);
            }

            @Override
            public NOptional<Boolean> booleanArg(String name) {
                return NOptional.ofNamedEmpty(name);
            }
        };
    }
}
