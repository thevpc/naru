package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruInteractiveTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.util.ArrayList;
import java.util.List;

/** Lets the model pause and ask the user a question, with optional choices and free text. */
public class AskUserTool extends DefaultNaruTool implements NaruInteractiveTool {
    public AskUserTool() {
        super("ask_user", new String[]{NaruToolTags.AI});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Ask the user for information needed to continue. Provide a concise question. "
                + "Optionally include choices; the user may choose one or type a free-text answer. "
                + "Ask only when the answer materially affects the work.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.string("question", "The question to ask the user", true).build(),
                NaruToolParameter.array("choices", "Optional suggested answers. Free-text answers are also accepted.",
                        false, NaruToolParameter.string("choice", "One suggested answer", true).build())
                        .minItems(1).maxItems(12).build());
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String question = context.stringArg("question").orElse("The model needs more information.");
        List<String> choices = strings(context.arg("choices").orNull());
        StringBuilder prompt = new StringBuilder(question);
        if (!choices.isEmpty()) {
            prompt.append("\n");
            for (int i = 0; i < choices.size(); i++) {
                prompt.append(i + 1).append(". ").append(choices.get(i)).append("\n");
            }
            prompt.append("Reply with a number or choice, or type another answer.");
        }
        context.task().requestInput(NMsg.ofC("%s", prompt.toString()));
        return WAITING_FOR_INPUT;
    }

    @Override
    public String complete(NaruToolCall call, String answer) {
        return answer == null ? "" : answer;
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                if (item != null && !item.toString().isBlank()) result.add(item.toString());
            }
        } else if (value != null) {
            result.add(value.toString());
        }
        return result;
    }
}
