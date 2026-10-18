package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruSubject;

import java.util.ArrayList;
import java.util.List;

/**
 * Text for the permissions editor: what a rule means on one line, and how to recognise a denial in
 * something the engine already printed.
 * <p>
 * This is the desktop's own wording on purpose. The {@code naru-permissions} extension has a
 * describe-rule helper, but it is not a compile dependency of the desktop and must not become one:
 * the desktop's pom resolves extensions at runtime through Nuts, and pinning one onto the
 * application classpath is the exact mistake that produced {@code NoSuchMethodError: NaruSession
 * .agent()} in an earlier phase (see the comment in {@code app/naru-desktop/pom.xml}). Everything
 * needed here is in {@code naru-api}.
 */
public final class PermissionsText {

    /**
     * The prefix {@code NaruAccessGuard.denyMessage()} always writes. It is the only thing that
     * turns a failed tool card into a <i>permission</i> failure rather than a broken tool, and the
     * engine returns it as a plain string into model history (plan §3.3), so matching it is the
     * whole detection.
     */
    public static final String DENIAL_PREFIX = "ERROR: permission denied:";

    /** The line {@code denyMessage()} appends with the rule that fired: {@code "  matched rule: x (source=y, effect=DENY)"}. */
    private static final String MATCHED = "matched rule:";

    private PermissionsText() {
    }

    public static boolean isDenial(String text) {
        return text != null && text.contains(DENIAL_PREFIX);
    }

    /**
     * The id of the rule that produced this denial, or null for the fallback default effect —
     * {@code denyMessage()} writes the literal {@code <default>} there, and there is no rule to
     * select in that case.
     * <p>
     * The scan starts after the label's whitespace, because the engine writes
     * {@code "matched rule: id (source=…)"}. A first attempt cut at the very first space, which was
     * the one after the colon, and so returned null for every real denial — the test in
     * {@code PermissionsTextTest} is what caught it.
     */
    public static String matchedRuleId(String text) {
        if (text == null) {
            return null;
        }
        int at = text.indexOf(MATCHED);
        if (at < 0) {
            return null;
        }
        String rest = text.substring(at + MATCHED.length());
        int start = 0;
        while (start < rest.length() && (rest.charAt(start) == ' ' || rest.charAt(start) == '\t')) {
            start++;
        }
        int end = rest.length();
        for (int i = start; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '(' || c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        String id = rest.substring(start, end).trim();
        return id.isEmpty() || "<default>".equals(id) ? null : id;
    }

    /**
     * One line, evaluation-order oriented: {@code DENY agent write,delete file **\/.env — core}.
     * Patterns come last so the part that decides precedence (effect, kind, action, type) stays
     * readable in a narrow column.
     */
    public static String describe(NaruPermissionRule r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.effect() == null ? "?" : r.effect().name());
        if (!r.enabled()) {
            sb.append(" (off)");
        }
        sb.append(' ').append(kind(r));
        sb.append(' ').append(actions(r));
        sb.append(' ').append(r.resourceType() == null ? "*" : r.resourceType().id());
        sb.append(' ').append(patterns(r));
        String subjects = subjects(r);
        if (!subjects.isEmpty()) {
            sb.append(" for ").append(subjects);
        }
        if (r.source() != null && !r.source().isBlank()) {
            sb.append("  [").append(r.source()).append(']');
        }
        return sb.toString();
    }

    private static String kind(NaruPermissionRule r) {
        return r.actorKind() == null ? "anyone" : r.actorKind().name().toLowerCase();
    }

    private static String actions(NaruPermissionRule r) {
        List<String> ids = new ArrayList<>();
        for (NaruAction a : r.actions()) {
            ids.add(a.id());
        }
        return ids.isEmpty() ? "*" : String.join(",", ids);
    }

    private static String patterns(NaruPermissionRule r) {
        List<String> p = r.patterns();
        if (p.isEmpty()) {
            return "*";
        }
        if (p.size() <= 2) {
            return String.join(",", p);
        }
        return p.get(0) + ",+" + (p.size() - 1);
    }

    private static String subjects(NaruPermissionRule r) {
        List<String> s = new ArrayList<>();
        for (NaruSubject x : r.subjects()) {
            s.add(x.type().name().toLowerCase() + ":" + x.id());
        }
        return String.join(",", s);
    }
}
