package net.thevpc.naru.desktop.ui;

/**
 * The lines the plan board sends when a reader clicks something.
 *
 * <p>Every action here is a {@code /plan} directive, so the board has exactly one way to change a
 * plan and it is the way a human typing in the composer already has: the click shows up in the
 * transcript as a statement, and the graph's rules — readiness is derived, completion needs a
 * validator, a running item's text belongs to its task — stay in the extension that owns them
 * rather than being re-implemented, badly, in a widget.
 *
 * <p>Quoting is the part worth testing on its own. Free text arrives from a text field, so it can
 * contain anything, and the parser splits a token at every space: an unquoted sentence would become
 * one positional word per space, and a word that starts with {@code --} would be read as an option
 * rather than prose. So multi-word text always goes as a quoted positional. Inside those quotes a
 * backslash or a double quote has to be escaped or the sentence is cut in half. Whitespace is
 * flattened first because one submission is one statement — the tokenizer itself keeps a newline
 * inside a token, so a pasted paragraph would otherwise arrive whole and end up carrying a second
 * statement with it.
 */
final class PlanDirectives {

    private PlanDirectives() {
    }

    /** Run with a leading slash, so it parses as a directive rather than an instruction to the model. */
    static String create(String text) {
        return withText("/plan create", text);
    }

    /**
     * Restate a plan's goal. The plan is named explicitly so a dormant plan can be edited without
     * being activated first, which is the whole point of the board listing every plan.
     */
    static String goal(String planId, String text) {
        return withText("/plan goal --plan=" + planId, text);
    }

    /** Append an item to the named plan (active or not). */
    static String addItem(String planId, String text) {
        return withText("/plan add --plan=" + planId, text);
    }

    /** Rewrite one item's description; the ref is the id the snapshot carries. */
    static String editItem(String planId, String itemRef, String text) {
        return withText("/plan edit --plan=" + planId + " " + itemRef, text);
    }

    /**
     * A blank sentence is not a change, and a line ending in {@code null} would reach the engine as
     * the word "null" — the callers already refuse blank input, so this only exists to keep a
     * composed line honest if one ever gets through.
     */
    private static String withText(String head, String text) {
        String quoted = quoted(text);
        return quoted == null ? null : head + " " + quoted;
    }

    static String removeItem(String planId, String itemRef) {
        return "/plan remove --plan=" + planId + " " + itemRef;
    }

    static String activate(String planId) {
        return "/plan activate " + planId;
    }

    static String deactivate() {
        return "/plan deactivate";
    }

    /** The quoted form of reader-supplied text, or null when there is nothing to say. */
    static String quoted(String text) {
        String flat = flatten(text);
        return flat.isEmpty() ? null : '"' + flat.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Newlines and tabs to single spaces, trimmed. A composer line is one statement, and a
     * multi-line description would otherwise smuggle a second statement into it.
     */
    static String flatten(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
