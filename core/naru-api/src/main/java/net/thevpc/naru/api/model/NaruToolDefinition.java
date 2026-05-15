package net.thevpc.naru.api.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible tool definition (Ollama uses the same format).
 *
 * <pre>
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "...",
 *     "description": "...",
 *     "parameters": {
 *       "type": "object",
 *       "properties": { ... },
 *       "required": [...]
 *     }
 *   }
 * }
 * </pre>
 */
public class NaruToolDefinition {

    private String type = "function";
    private FunctionDef function;

    public NaruToolDefinition(String name, String description, Map<String, Object> properties,
                              List<String> required) {
        this.function = new FunctionDef(name, description, properties, required);
    }

    public String getType() { return type; }
    public FunctionDef getFunction() { return function; }

    // ── Serialisable to Gson ──────────────────────────────────────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("function", function.toMap());
        return map;
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    public static class FunctionDef {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public FunctionDef(String name, String description,
                           Map<String, Object> properties, List<String> required) {
            this.name = name;
            this.description = description;

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("properties", properties);
            if (required != null && !required.isEmpty()) {
                params.put("required", required);
            }
            this.parameters = params;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, Object> getParameters() { return parameters; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("description", description);
            map.put("parameters", parameters);
            return map;
        }
    }
}
