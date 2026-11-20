package net.thevpc.naru.desktop.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@code requestInput} prompt actually contains.
 * <p>
 * The engine's only interactive tool today, {@code AskUserTool}, builds its prompt as
 * "question\\n1. choice\\n2. choice\\n…\\nReply with a number or choice, or type another
 * answer." and the API ({@code NaruInputRequest}) carries no structured kind or choices field,
 * so the numbers have to be read back out of the text. This is the single place that does it:
 * the dialog then renders buttons for the choices and a free-text field, and delivers either the
 * number or the typed answer — exactly what the tool's {@code complete()} expects.
 * <p>
 * Deliberately conservative: a line only counts as a choice when it starts with {@code n.} and
 * the numbering runs 1,2,3… from the first numbered line, so prose that happens to contain
 * "2. " mid-paragraph is not mistaken for a button.
 */
public record InputPrompt(String question, List<String> choices, String trailingHint) {

    public static InputPrompt parse(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").trim();
        if (text.isEmpty()) {
            return new InputPrompt("", List.of(), null);
        }
        String[] lines = text.split("\n");
        int first = -1;
        for (int i = 0; i < lines.length; i++) {
            if (numbered(lines[i], 1)) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return new InputPrompt(text, List.of(), null);
        }
        List<String> choices = new ArrayList<>();
        String hint = null;
        int expect = 1;
        int i = first;
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (numbered(line, expect)) {
                choices.add(line.substring(line.indexOf('.') + 1).trim());
                expect++;
                continue;
            }
            // the first non-numbered line after the list is the instruction, not a choice
            hint = line;
            i++;
            break;
        }
        StringBuilder rest = new StringBuilder();
        for (int k = i; k < lines.length; k++) {
            if (!lines[k].isBlank()) {
                rest.append(lines[k].trim()).append(' ');
            }
        }
        if (hint == null && rest.length() > 0) {
            hint = rest.toString().trim();
        }
        StringBuilder question = new StringBuilder();
        for (int k = 0; k < first; k++) {
            if (question.length() > 0) {
                question.append(' ');
            }
            question.append(lines[k].trim());
        }
        return new InputPrompt(question.toString(), List.copyOf(choices), hint);
    }

    private static boolean numbered(String line, int expected) {
        String t = line.trim();
        String prefix = expected + ".";
        return t.startsWith(prefix) && t.length() > prefix.length() && t.charAt(prefix.length()) == ' ';
    }

    public boolean hasChoices() {
        return !choices.isEmpty();
    }
}
