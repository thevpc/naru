package net.thevpc.naru.ext.mcp.cli;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class McpBackedTool implements NaruTool {

    private final McpSchema.Tool mcpTool;
    private final McpSyncClient client;

    public McpBackedTool(McpSchema.Tool mcpTool, McpSyncClient client) {
        this.mcpTool = mcpTool;
        this.client = client;
    }

    @Override
    public String name() { return mcpTool.name(); }

    @Override
    public String getDescription(NaruSession session) { return mcpTool.description(); }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        // bridge MCP inputSchema → NaruToolDefinition
        List<NaruToolParameter> params = parseProperties(
                mcpTool.inputSchema().properties(),
                mcpTool.inputSchema().required()
        );
        return new NaruToolDefinitionFunction(
                mcpTool.name(),
                mcpTool.description(),
                params
        );
    }

    private static List<NaruToolParameter> parseProperties(
            Map<String, Object> properties, List<String> required) {

        if (properties == null) return Collections.emptyList();
        List<String> req = required != null ? required : Collections.emptyList();

        List<NaruToolParameter> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> prop = (Map<String, Object>) entry.getValue();
            result.add(parseParam(entry.getKey(), prop, req.contains(entry.getKey())));
        }
        return result;
    }

    private static NaruToolParameter parseParam(String name, Map<String, Object> s, boolean required) {
        String type = str(s, "type", "string");
        String desc = str(s, "description", "");

        NaruToolParameter.Builder b;
        switch (type) {
            case "integer": b = parseInteger(name, desc, required, s); break;
            case "number":  b = parseNumber(name, desc, required, s);  break;
            case "boolean": b = NaruToolParameter.bool(name, desc, required); break;
            case "array":   b = parseArray(name, desc, required, s);   break;
            case "object":  b = parseObject(name, desc, required, s);  break;
            default:        b = parseString(name, desc, required, s);  break;
        }

        // ── common fields ─────────────────────────────────────────────────────
        Object defaultVal = s.get("default");
        if (defaultVal != null) b.defaultValue(defaultVal);

        List<Object> enumVals = (List<Object>) s.get("enum");
        if (enumVals != null && !enumVals.isEmpty()) b.enumValues(enumVals);

        // nullable: JSON Schema draft 2020 uses "type": ["string","null"]
        // older drafts use "nullable": true
        Object rawType = s.get("type");
        if (rawType instanceof List && ((List<?>) rawType).contains("null")) b.nullable();
        Boolean nullable = (Boolean) s.get("nullable");
        if (Boolean.TRUE.equals(nullable)) b.nullable();

        return b.build();
    }

    // ── per-type parsers ──────────────────────────────────────────────────────

    private static NaruToolParameter.Builder parseString(String name, String desc, boolean required, Map<String, Object> s) {
        NaruToolParameter.Builder b = NaruToolParameter.string(name, desc, required);
        if (s.containsKey("format"))    b.format(str(s, "format", null));
        if (s.containsKey("pattern"))   b.pattern(str(s, "pattern", null));
        if (s.containsKey("minLength")) b.minLength(num(s, "minLength").intValue());
        if (s.containsKey("maxLength")) b.maxLength(num(s, "maxLength").intValue());
        return b;
    }

    private static NaruToolParameter.Builder parseInteger(String name, String desc, boolean required, Map<String, Object> s) {
        NaruToolParameter.Builder b = NaruToolParameter.integer(name, desc, required);
        applyNumericConstraints(b, s);
        return b;
    }

    private static NaruToolParameter.Builder parseNumber(String name, String desc, boolean required, Map<String, Object> s) {
        NaruToolParameter.Builder b = NaruToolParameter.number(name, desc, required);
        applyNumericConstraints(b, s);
        return b;
    }

    private static void applyNumericConstraints(NaruToolParameter.Builder b, Map<String, Object> s) {
        if (s.containsKey("minimum"))          b.minimum(num(s, "minimum"));
        if (s.containsKey("maximum"))          b.maximum(num(s, "maximum"));
        if (s.containsKey("exclusiveMinimum")) b.exclusiveMinimum(num(s, "exclusiveMinimum"));
        if (s.containsKey("exclusiveMaximum")) b.exclusiveMaximum(num(s, "exclusiveMaximum"));
        if (s.containsKey("multipleOf"))       b.multipleOf(num(s, "multipleOf"));
    }
    private static NaruToolParameter.Builder parseArray(String name, String desc, boolean required, Map<String, Object> s) {
        Map<String, Object> items = (Map<String, Object>) s.get("items");
        NaruToolParameter itemType = items != null
                ? parseParam("item", items, true)
                : NaruToolParameter.string("item", "", true).build();

        NaruToolParameter.Builder b = NaruToolParameter.array(name, desc, required, itemType);
        if (s.containsKey("minItems"))   b.minItems(num(s, "minItems").intValue());
        if (s.containsKey("maxItems"))   b.maxItems(num(s, "maxItems").intValue());
        if (s.containsKey("uniqueItems")) b.uniqueItems((Boolean) s.get("uniqueItems"));
        return b;
    }


    private static NaruToolParameter.Builder parseObject(String name, String desc, boolean required, Map<String, Object> s) {
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        List<String> req = (List<String>) s.get("required");
        List<NaruToolParameter> children = parseProperties(props, req);

        NaruToolParameter.Builder b = NaruToolParameter.object(name, desc, required)
                .properties(children);
        if (s.containsKey("additionalProperties")) {
            b.additionalProperties((Boolean) s.get("additionalProperties"));
        }
        return b;
    }


    // ── raw map helpers ───────────────────────────────────────────────────────

    private static String str(Map<String, Object> s, String key, String def) {
        Object v = s.get(key);
        return v instanceof String ? (String) v : def;
    }

    private static Number num(Map<String, Object> s, String key) {
        Object v = s.get(key);
        // Jackson deserializes numbers as Integer, Long, or Double
        return v instanceof Number ? (Number) v : 0;
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return client.callTool(new McpSchema.CallToolRequest(mcpTool.name(), context.arguments()))
                .content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
