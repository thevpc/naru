package net.thevpc.naru.api.model;

import net.thevpc.naru.api.tool.NaruToolParameter;

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
    private String name;
    private String description;
    public NaruToolDefinition(String name, String description) {
        this.name=name;
        this.description=description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

}
