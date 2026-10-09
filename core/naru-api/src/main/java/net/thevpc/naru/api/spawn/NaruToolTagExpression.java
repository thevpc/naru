package net.thevpc.naru.api.spawn;

import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The section-4 spawn tag-expression evaluator.
 * <p>
 * A tag expression describes a constraint over a set of granted tool tags, for example
 * the contract clause {@code requires: "fs &amp; !write &amp; !exec"} on an agent
 * {@code .md} front-matter or a routine file. Grammar:
 *
 * <pre>
 * expr    := orExpr
 * orExpr  := andExpr ('|' andExpr)*
 * andExpr := unary ('&amp;' unary)*
 * unary   := '!' unary | primary
 * primary := NAME | '(' expr ')'
 * NAME    := [a-zA-Z0-9_.-]+        normalized to lower kebab-case
 * </pre>
 * <p>
 * An expression evaluates against the set of tags the spawned task resolves to. It is also
 * how a caller discovers which names an expression mentions, which the consistency
 * checking uses to know which tags a contract needs.
 * <p>
 * Immutable and safe to share.
 */
public final class NaruToolTagExpression {

    private final Node root;

    private NaruToolTagExpression(Node root) {
        this.root = root;
    }

    /**
     * Parses an expression, throwing {@link IllegalArgumentException} with the offending
     * position on syntax errors, or on a blank/empty input.
     */
    public static NaruToolTagExpression parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("empty tag expression");
        }
        Parser parser = new Parser(expression);
        Node root = parser.parse();
        parser.expectEnd();
        return new NaruToolTagExpression(root);
    }

    /**
     * Whether the expression holds for the given granted tags. Names are compared in
     * lower kebab-case.
     */
    public boolean matches(Set<String> grantedTags) {
        return root.eval(normalized(grantedTags));
    }

    /** Equivalent to {@code !matches(...)} but cheaper to read at call sites. */
    public boolean isSatisfiedBy(Set<String> grantedTags) {
        return matches(grantedTags);
    }

    /**
     * The positive names the expression references, in first-use order. Used to compute a
     * contract's required tags (a {@code requires} clause lists what must be granted) and
     * to check that the resolved tag set covers them.
     */
    public Set<String> positiveTagNames() {
        Set<String> out = new LinkedHashSet<>();
        root.collectPositive(out);
        return out;
    }

    /**
     * The negative names (those behind a {@code !}) the expression references, in
     * first-use order. A composed expression like {@code fs &amp; !write} references
     * {@code write} negatively: the task must <em>not</em> hold it.
     */
    public Set<String> negativeTagNames() {
        Set<String> out = new LinkedHashSet<>();
        root.collectNegative(out);
        return out;
    }

    /**
     * The tags the resolved set would need to gain or un-grant for the expression to
     * hold. Positive names not granted are listed as {@code +name}; negatively referenced
     * names that are granted are listed as {@code -name}.
     */
    public List<String> violations(Set<String> grantedTags) {
        Set<String> tags = normalized(grantedTags);
        List<String> out = new ArrayList<>();
        for (String p : positiveTagNames()) {
            if (!tags.contains(p)) {
                out.add("+" + p);
            }
        }
        for (String n : negativeTagNames()) {
            if (tags.contains(n)) {
                out.add("-" + n);
            }
        }
        return out;
    }

    private static Set<String> normalized(Set<String> grantedTags) {
        Set<String> out = new LinkedHashSet<>();
        if (grantedTags != null) {
            for (String t : grantedTags) {
                if (t != null && !t.isBlank()) {
                    String n = NNameFormat.LOWER_KEBAB_CASE.format(t.trim());
                    if (!n.isEmpty()) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        root.write(sb);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruToolTagExpression that)) {
            return false;
        }
        return Objects.equals(toString(), that.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(toString());
    }

    // ── AST ────────────────────────────────────────────────────────────────

    private interface Node {
        boolean eval(Set<String> tags);

        void collectPositive(Set<String> out);

        void collectNegative(Set<String> out);

        void write(StringBuilder sb);
    }

    private record NameNode(String name) implements Node {
        @Override
        public boolean eval(Set<String> tags) {
            return tags.contains(name);
        }

        @Override
        public void collectPositive(Set<String> out) {
            out.add(name);
        }

        @Override
        public void collectNegative(Set<String> out) {
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append(name);
        }
    }

    private record NotNode(Node child) implements Node {
        @Override
        public boolean eval(Set<String> tags) {
            return !child.eval(tags);
        }

        @Override
        public void collectPositive(Set<String> out) {
            child.collectNegative(out);
        }

        @Override
        public void collectNegative(Set<String> out) {
            child.collectPositive(out);
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('!');
            boolean paren = !(child instanceof NameNode);
            if (paren) {
                sb.append('(');
            }
            child.write(sb);
            if (paren) {
                sb.append(')');
            }
        }
    }

    private record AndNode(List<Node> children) implements Node {
        @Override
        public boolean eval(Set<String> tags) {
            for (Node c : children) {
                if (!c.eval(tags)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void collectPositive(Set<String> out) {
            for (Node c : children) {
                c.collectPositive(out);
            }
        }

        @Override
        public void collectNegative(Set<String> out) {
            for (Node c : children) {
                c.collectNegative(out);
            }
        }

        @Override
        public void write(StringBuilder sb) {
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(" & ");
                }
                writeChild(sb, children.get(i));
            }
        }
    }

    private record OrNode(List<Node> children) implements Node {
        @Override
        public boolean eval(Set<String> tags) {
            for (Node c : children) {
                if (c.eval(tags)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void collectPositive(Set<String> out) {
            // an 'or' cannot promise any single positive name, so do not over-claim:
            // only names referenced negatively are safe to report
            for (Node c : children) {
                c.collectNegative(out);
            }
        }

        @Override
        public void collectNegative(Set<String> out) {
            for (Node c : children) {
                c.collectPositive(out);
            }
        }

        @Override
        public void write(StringBuilder sb) {
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                writeChild(sb, children.get(i));
            }
        }
    }

    private static void writeChild(StringBuilder sb, Node node) {
        if (node instanceof NameNode) {
            node.write(sb);
        } else {
            sb.append('(');
            node.write(sb);
            sb.append(')');
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Node parse() {
            return parseOr();
        }

        private Node parseOr() {
            Node left = parseAnd();
            List<Node> ors = null;
            while (peek('|')) {
                pos++;
                if (ors == null) {
                    ors = new ArrayList<>();
                    ors.add(left);
                }
                ors.add(parseAnd());
            }
            return ors == null ? left : new OrNode(ors);
        }

        private Node parseAnd() {
            Node left = parseUnary();
            List<Node> ands = null;
            while (peek('&')) {
                pos++;
                if (ands == null) {
                    ands = new ArrayList<>();
                    ands.add(left);
                }
                ands.add(parseUnary());
            }
            return ands == null ? left : new AndNode(ands);
        }

        private Node parseUnary() {
            skipSpaces();
            if (peek('!')) {
                pos++;
                return new NotNode(parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipSpaces();
            if (pos >= text.length()) {
                throw error("expected a tag name or '('");
            }
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                Node inner = parseOr();
                skipSpaces();
                if (!peek(')')) {
                    throw error("missing ')'");
                }
                pos++;
                return inner;
            }
            if (isNameChar(c)) {
                int start = pos;
                while (pos < text.length() && isNameChar(text.charAt(pos))) {
                    pos++;
                }
                String raw = text.substring(start, pos);
                String name = NNameFormat.LOWER_KEBAB_CASE.format(raw.trim());
                if (name.isEmpty()) {
                    throw error("invalid tag name '" + raw + "'");
                }
                return new NameNode(name);
            }
            throw error("unexpected character '" + c + "'");
        }

        void expectEnd() {
            skipSpaces();
            if (pos < text.length()) {
                throw error("unexpected trailing '" + text.charAt(pos) + "'");
            }
        }

        private boolean peek(char c) {
            skipSpaces();
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private static boolean isNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(
                    "invalid tag expression '" + text + "' at position " + pos + ": " + msg);
        }
    }
}