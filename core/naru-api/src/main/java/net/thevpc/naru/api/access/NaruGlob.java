package net.thevpc.naru.api.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Glob matching for resource locators, with {@code /} as the segment separator.
 *
 * <p>Rules are written by people, so the syntax is the one people already know:
 *
 * <ul>
 *   <li>{@code *} — any run of characters within one segment (no {@code /});</li>
 *   <li>{@code **} — any run of characters across segments; {@code **} then
 *       {@code /} also matches no segment at all, so {@code **}{@code /.env} matches both
 *       {@code /proj/.env} and {@code /a/b/.env};</li>
 *   <li>{@code ?} — one character within a segment;</li>
 *   <li>{@code [abc]} / {@code [!abc]} — a character class;</li>
 *   <li>{@code {a,b}} — alternation.</li>
 * </ul>
 *
 * <p>The separator rule is what keeps {@code src/*} from quietly matching
 * {@code src/a/b.java}: a rule that means "directly under src" must not be read
 * as "anywhere under src". The {@code **} form is the explicit way to say the
 * latter.
 *
 * <p>Compiled patterns are cached, because a policy is checked on every tool,
 * directive, file and network call and the same handful of patterns are matched
 * again and again.
 */
public final class NaruGlob {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private NaruGlob() {
    }

    /** Whether the pattern contains any glob metacharacter. */
    public static boolean isGlob(String pattern) {
        if (pattern == null) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{' || c == '\\') {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code value} matches {@code pattern}. A null pattern matches nothing. */
    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (!isGlob(pattern)) {
            return pattern.equals(value);
        }
        return toPattern(pattern).matcher(value).matches();
    }

    /** Whether any of {@code patterns} matches {@code value}. An empty list matches nothing. */
    public static boolean matchesAny(List<String> patterns, String value) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String p : patterns) {
            if (matches(p, value)) {
                return true;
            }
        }
        return false;
    }

    public static Pattern toPattern(String pattern) {
        return CACHE.computeIfAbsent(pattern, NaruGlob::compile);
    }

    private static Pattern compile(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '\\': {
                    if (i + 1 < n) {
                        sb.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
                    } else {
                        sb.append("\\\\");
                    }
                    break;
                }
                case '*': {
                    if (i + 1 < n && pattern.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < n && pattern.charAt(i + 1) == '/') {
                            i++;
                            sb.append("(?:.*/)?");
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                    break;
                }
                case '?': {
                    sb.append("[^/]");
                    break;
                }
                case '[': {
                    int end = findClassEnd(pattern, i + 1);
                    if (end < 0) {
                        sb.append("\\[");
                    } else {
                        String body = pattern.substring(i + 1, end);
                        if (body.startsWith("!")) {
                            body = "^" + body.substring(1);
                        }
                        sb.append('[').append(body).append(']');
                        i = end;
                    }
                    break;
                }
                case '{': {
                    int end = findBraceEnd(pattern, i + 1);
                    if (end < 0) {
                        sb.append("\\{");
                    } else {
                        String body = pattern.substring(i + 1, end);
                        List<String> alternatives = splitTopLevel(body);
                        sb.append("(?:");
                        for (int k = 0; k < alternatives.size(); k++) {
                            if (k > 0) {
                                sb.append('|');
                            }
                            sb.append(compileBody(alternatives.get(k)));
                        }
                        sb.append(')');
                        i = end;
                    }
                    break;
                }
                default:
                    sb.append(Pattern.quote(String.valueOf(c)));
                    break;
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }

    /** Compiles the body of an alternation without the outer anchors. */
    private static String compileBody(String pattern) {
        String full = compile(pattern).pattern();
        if (full.length() >= 2 && full.startsWith("^") && full.endsWith("$")) {
            return full.substring(1, full.length() - 1);
        }
        return full;
    }

    private static int findClassEnd(String pattern, int from) {
        for (int i = from; i < pattern.length(); i++) {
            if (pattern.charAt(i) == ']') {
                return i;
            }
        }
        return -1;
    }

    private static int findBraceEnd(String pattern, int from) {
        int depth = 0;
        for (int i = from; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                depth++;
                cur.append(c);
            } else if (c == '}') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }
}
