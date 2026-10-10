package net.thevpc.naru.api.model;

import net.thevpc.nuts.text.NText;

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
 *
 * <p>The description is held as an {@link NText} so a tool (or directive) can
 * style it for the terminal help without polluting what the model sees. The
 * plain, style-free text is produced by {@link #getDescription()} (through
 * {@link NText#filteredText()}) and that is what every request serializer embeds
 * in the JSON; {@link #getDescriptionText()} keeps the rich text for display.
 */
public class NaruToolDefinition {
    private final String name;
    private final NText description;

    public NaruToolDefinition(String name, String description) {
        this(name, description == null ? null : NText.ofPlain(description));
    }

    public NaruToolDefinition(String name, NText description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    /**
     * Plain, NTF-free description. This is the text sent to the model: any
     * styling used for the terminal help is stripped here, in one place, so no
     * request serializer can accidentally send escape sequences to an LLM.
     *
     * @return description without any formatting sequence, or {@code null}
     */
    public String getDescription() {
        return description == null ? null : description.filteredText();
    }

    /**
     * Rich description, formatting included, intended for terminal help
     * (e.g. {@code /tools list}). Use {@link #getDescription()} for anything
     * that is handed to a model.
     *
     * @return description as a rich text, never {@code null}
     */
    public NText getDescriptionText() {
        return description == null ? NText.ofBlank() : description;
    }

}
