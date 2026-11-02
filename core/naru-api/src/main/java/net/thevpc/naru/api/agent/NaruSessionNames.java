package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NStringUtils;

/**
 * The one rule for what a session name may look like, shared by every rename path.
 *
 * <p>Exists because names arrive from four places -- a person at the prompt, an inline
 * edit in the desktop, {@code newSession().name(...)} from embedding code, and the
 * titler's model output -- and a model that answers with two lines, a leading
 * {@code Title:} and a trailing period is not a name until someone says so. If each
 * caller trims and caps on its own, the four rules drift, and the store ends up holding
 * names that only some of the doors produce.
 *
 * <p>Uniqueness is deliberately not checked. Two sessions may carry the same label; the
 * uuid is the identity, and a UI that needs to tell them apart shows the uuid or the
 * project alongside. Refusing a rename that reads as duplicate would be enforcing an
 * integrity constraint on a field that is only a label.
 */
public final class NaruSessionNames {

    /** The longest name a session may carry, in characters. */
    public static final int MAX_LENGTH = 80;

    private NaruSessionNames() {
    }

    /**
     * The stored form of a name: trimmed, one line, at most {@value #MAX_LENGTH}
     * characters.
     *
     * @param name the candidate name
     * @return the normalized name
     * @throws NIllegalArgumentException when nothing is left after normalizing
     */
    public static String normalize(String name) {
        String stripped = (name == null ? "" : name.strip());
        if (stripped.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC("session name is empty"));
        }
        // Internal newlines and tabs are folded rather than rejected: a pasted two-line
        // title is the same intention as a one-line one, and a name is a label, not a
        // document. Other whitespace is left alone, so a double space is not silently eaten.
        StringBuilder sb = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String oneLine = sb.toString().strip();
        if (oneLine.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC("session name is empty"));
        }
        if (oneLine.length() <= MAX_LENGTH) {
            return oneLine;
        }
        return NStringUtils.strip(oneLine.substring(0, MAX_LENGTH));
    }

    /** Whether {@code name} would survive {@link #normalize} unchanged. */
    public static boolean isNormalized(String name) {
        try {
            return normalize(name).equals(name);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
