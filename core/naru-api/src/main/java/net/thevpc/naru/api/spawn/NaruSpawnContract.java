package net.thevpc.naru.api.spawn;

import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The spawn contract of a spawn target (an agent {@code .md} front-matter, or a script /
 * routine front-matter, per the O6 layout): what the target requires, which tools it
 * needs, and which skills it needs.
 * <p>
 * TSON shape (the same keys already merge into the model context as front-matter env):
 *
 * <pre>
 * { requires: "fs &amp; !write &amp; !exec", tools: ["file_read", "git_status"], skills: ["code-review"] }
 * </pre>
 *
 * <ul>
 *   <li>{@code requires} — a section-4 tag expression evaluated against the <em>resolved</em>
 *       tag set <em>after</em> defaults, policy and flags are applied. A contract that asks
 *       for tags the resolution did not grant fails the spawn with a message naming the
 *       flag that would fix it.</li>
 *   <li>{@code tools} — optional tool names the target needs by name. A contract cannot
 *       grant a tool (that is what tags do); it can only state that a name must survive the
 *       resolution. A tool the resolution excludes fails the spawn with a message naming the
 *       exclusion to drop. Names absent from the registry are not a contract violation — the
 *       contract constrains the spawn, it does not police the catalog.</li>
 *   <li>{@code skills} — skill names granted to the spawned task (added at spawn time; this
 *       is the only grant a contract makes).</li>
 * </ul>
 * <p>
 * A contract may <b>not</b> contain tag add: keys that would grant, revoke or inherit tags
 * ({@code tags}, {@code addTags}, {@code revokeTags}) are rejected at parse time. A
 * contract constrains and composes; it never expands permission.
 * <p>
 * Immutable.
 */
public final class NaruSpawnContract {

    private final NaruToolTagExpression requires;
    private final List<String> tools;
    private final List<String> skills;

    private NaruSpawnContract(NaruToolTagExpression requires, List<String> tools, List<String> skills) {
        this.requires = requires;
        this.tools = distinct(tools);
        this.skills = distinct(skills);
    }

    private static List<String> distinct(List<String> values) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (values != null) {
            for (String s : values) {
                if (NBlankable.isBlank(s)) {
                    continue;
                }
                String c = s.trim();
                if (seen.add(c)) {
                    ordered.add(c);
                }
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Parses a contract from a TSON object. Returns null for a blank input.
     *
     * @throws IllegalArgumentException on a syntax error, or when the object tries to add /
     *                                  revoke / inherit tags
     */
    public static NaruSpawnContract parse(String tson) {
        if (NBlankable.isBlank(tson)) {
            return null;
        }
        return parse(NElementReader.ofTson().read(tson));
    }

    /**
     * Parses a contract from a front-matter header (the key/value pairs a
     * {@code MarkdownWithHeader} loaded from an agent {@code .md}). Returns null when the
     * header carries none of the contract keys.
     */
    public static NaruSpawnContract parse(Map<String, NElement> header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (Map.Entry<String, NElement> e : header.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                b.set(e.getKey(), e.getValue());
            }
        }
        return parse(b.build());
    }

    /**
     * Parses a contract from a TSON list/object element, or returns null when the element
     * is null.
     *
     * @throws IllegalArgumentException when the object tries to add / revoke / inherit tags
     */
    public static NaruSpawnContract parse(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        List<NElement> children = element.asListContainer().map(x -> new ArrayList<>(x.children()))
                .orElse(null);
        if (children == null || children.isEmpty()) {
            return null;
        }
        if (children.size() == 1 && children.get(0).isObject()) {
            children = new ArrayList<>(children.get(0).asObject().get().children());
        }
        NaruToolTagExpression requires = null;
        List<String> tools = new ArrayList<>();
        List<String> skills = new ArrayList<>();
        for (NElement child : children) {
            if (!child.isPair()) {
                throw new IllegalArgumentException("invalid spawn contract: expected key-value pairs, got " + child);
            }
            String key = child.asPair().get().key().asStringValue().orNull();
            NElement value = child.asPair().get().value();
            switch (key == null ? "" : key) {
                case "requires": {
                    String expr = value.asStringValue().orNull();
                    if (NBlankable.isBlank(expr)) {
                        requires = null;
                    } else {
                        requires = NaruToolTagExpression.parse(expr);
                    }
                    break;
                }
                case "tools": {
                    NArrayElement arr = value.asArray().orNull();
                    if (arr != null) {
                        for (NElement e : arr.children()) {
                            String n = e.asStringValue().orNull();
                            if (!NBlankable.isBlank(n)) {
                                tools.add(n.trim());
                            }
                        }
                    } else {
                        String n = value.asStringValue().orNull();
                        if (!NBlankable.isBlank(n)) {
                            tools.add(n.trim());
                        }
                    }
                    break;
                }
                case "skills": {
                    NArrayElement arr = value.asArray().orNull();
                    if (arr != null) {
                        for (NElement e : arr.children()) {
                            String n = e.asStringValue().orNull();
                            if (!NBlankable.isBlank(n)) {
                                skills.add(n.trim());
                            }
                        }
                    }
                    break;
                }
                case "tags":
                case "addTags":
                case "revokeTags":
                    throw new IllegalArgumentException(
                            "contract may not contain tag add/revoke ('" + key + "'); "
                                    + "a contract constrains and grants skills only — use --add-tags / --revoke-tags at the call site");
                default:
                    // other front-matter keys belong to the model-context env, not to the contract
                    break;
            }
        }
        return new NaruSpawnContract(requires, tools, skills);
    }

    /**
     * The required-tags expression, or null when the contract imposes none.
     */
    public NaruToolTagExpression requires() {
        return requires;
    }

    /**
     * Tool names the contract expects to survive the resolution, in declared order. This is
     * a constraint, not a grant: the contract cannot add a tool, only state that a name must
     * not be excluded. Empty when the contract names none.
     */
    public List<String> tools() {
        return tools;
    }

    /**
     * Skill names the contract grants to the spawned task, in declared order.
     */
    public List<String> skills() {
        return skills;
    }

    /**
     * Whether this contract is a no-op (no requires, no tools, no skills).
     */
    public boolean isEmpty() {
        return requires == null && tools.isEmpty() && skills.isEmpty();
    }

    /**
     * The tag-expression violations of the contract against a resolved tag set: positive
     * names not granted (as {@code +name}) and negatively referenced names that are granted
     * (as {@code -name}).
     */
    public List<String> violations(Set<String> grantedTags) {
        if (requires == null) {
            return List.of();
        }
        return requires.violations(grantedTags);
    }

    /**
     * Whether the contract holds for the given granted tags.
     */
    public boolean isSatisfiedBy(Set<String> grantedTags) {
        return requires == null || requires.matches(grantedTags);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (requires != null) {
            sb.append("requires: \"").append(requires).append('"');
            first = false;
        }
        if (!tools.isEmpty()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("tools: [").append(String.join(", ", tools)).append(']');
            first = false;
        }
        if (!skills.isEmpty()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("skills: [").append(String.join(", ", skills)).append(']');
        }
        return sb.append('}').toString();
    }
}