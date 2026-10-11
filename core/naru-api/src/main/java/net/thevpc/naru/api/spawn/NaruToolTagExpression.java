package net.thevpc.naru.api.spawn;

import net.thevpc.naru.api.util.NaruExprs;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprNodeType;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NOptional;

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
 * The syntax is not parsed here by hand: NARU routes it through the Nuts <b>NExpr</b>
 * engine ({@link NaruExprs}), which supplies the parser and the operator/spacing rules.
 * This class keeps only the tag-specific semantics on top of the resulting syntax tree
 * (membership evaluation and the positive/negative name collection below).
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
     * input on syntax errors, or on a blank/empty input.
     */
    public static NaruToolTagExpression parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("empty tag expression");
        }
        NOptional<NExprNode> parsed = NaruExprs.tryParse(delimitNegation(expression));
        if (parsed.isNotPresent()) {
            var m = parsed.message();
            String detail = m == null ? String.valueOf(parsed) : String.valueOf(m.get());
            throw new IllegalArgumentException("invalid tag expression '" + expression + "': " + detail);
        }
        return new NaruToolTagExpression(toNode(parsed.get(), expression));
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

    // ── NExpr AST → tag AST ─────────────────────────────────────────────────

    private static Node toNode(NExprNode n, String expression) {
        switch (n.nodeType()) {
            case WORD: {
                String name = NNameFormat.LOWER_KEBAB_CASE.format(n.name() == null ? "" : n.name().trim());
                if (name.isEmpty()) {
                    throw unsupported(expression, n);
                }
                return new NameNode(name);
            }
            case OPERATOR: {
                String op = n.name() == null ? "" : n.name().toLowerCase();
                switch (op) {
                    case "(": {
                        // NExpr models a parenthesised group as an operator with one child
                        List<NExprNode> ch = n.children();
                        if (ch.size() != 1) {
                            throw unsupported(expression, n);
                        }
                        return toNode(ch.get(0), expression);
                    }
                    case "!": {
                        List<NExprNode> ch = n.children();
                        if (ch.size() != 1) {
                            throw unsupported(expression, n);
                        }
                        return new NotNode(toNode(ch.get(0), expression));
                    }
                    case "&":
                    case "&&":
                    case "and": {
                        return new AndNode(toNodes(n, expression, true));
                    }
                    case "|":
                    case "||":
                    case "or": {
                        return new OrNode(toNodes(n, expression, false));
                    }
                    default: {
                        throw unsupported(expression, n);
                    }
                }
            }
            default: {
                throw unsupported(expression, n);
            }
        }
    }

    private static List<Node> toNodes(NExprNode n, String expression, boolean and) {
        List<NExprNode> children = n.children();
        if (children == null || children.isEmpty()) {
            throw unsupported(expression, n);
        }
        List<Node> out = new ArrayList<>(children.size());
        for (NExprNode c : children) {
            Node node = toNode(c, expression);
            // NExpr builds same-operator chains right-nested (a & (b & c)); the tag grammar
            // is a flat n-ary fold, so flatten direct same-operator children back out. A
            // group the user spelled out is kept as written.
            boolean directSameOp = c.nodeType() == NExprNodeType.OPERATOR
                    && !"(".equals(c.name())
                    && (and ? isAndOp(c.name()) : isOrOp(c.name()));
            if (directSameOp && and && node instanceof AndNode a) {
                out.addAll(a.children());
            } else if (directSameOp && !and && node instanceof OrNode o) {
                out.addAll(o.children());
            } else {
                out.add(node);
            }
        }
        return out;
    }

    private static boolean isAndOp(String op) {
        return "&".equals(op) || "&&".equals(op) || "and".equalsIgnoreCase(op);
    }

    private static boolean isOrOp(String op) {
        return "|".equals(op) || "||".equals(op) || "or".equalsIgnoreCase(op);
    }

    /**
     * The Nuts NExpr parser binds a prefix {@code !} loosely -- it consumes the whole rest
     * of the expression (so {@code !a & b} would parse as {@code !(a & b)}). The tag
     * grammar instead treats {@code !} as the tightest-binding unary. This rewrites each
     * unary negation whose operand is a bare name or a parenthesised group into an explicit
     * {@code (!operand)} term; NExpr then parses the text normally and the pre-existing
     * group term delimits the negation.
     */
    private static String delimitNegation(String expression) {
        StringBuilder out = new StringBuilder(expression.length() + 8);
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c != '!') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < expression.length() && Character.isWhitespace(expression.charAt(j))) {
                j++;
            }
            if (j < expression.length() && isNameStart(expression.charAt(j))) {
                int k = j;
                while (k < expression.length() && isNameChar(expression.charAt(k))) {
                    k++;
                }
                out.append("(!").append(expression, j, k).append(')');
                i = k;
            } else if (j < expression.length() && expression.charAt(j) == '(') {
                int close = matchingParen(expression, j);
                if (close > 0) {
                    out.append("(!").append(expression, j, close + 1).append(')');
                    i = close + 1;
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int p = open; p < text.length(); p++) {
            char ch = text.charAt(p);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return p;
                }
            }
        }
        return -1;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    private static IllegalArgumentException unsupported(String expression, NExprNode n) {
        String token = n.name() == null ? String.valueOf(n.nodeType()) : n.name();
        return new IllegalArgumentException(
                "invalid tag expression '" + expression + "': unsupported token '" + token + "'");
    }
}