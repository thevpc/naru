package net.thevpc.naru.api.util;

import net.thevpc.nuts.expr.NExprContext;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprVarResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NException;
import net.thevpc.nuts.util.NOptional;

/**
 * The single entry point for every expression NARU parses, evaluates or templates.
 *
 * <p>NARU does not hand-roll expression parsers: the Nuts <b>NExpr</b> engine
 * ({@code net.thevpc.nuts.expr}) already provides a fully customizable, context-aware
 * parser/evaluator (operators, functions, constructs, variables, literals and templates).
 * This class fixes the small shared configuration NARU wants and gives every module one
 * place to reach it, so tag expressions, scheduler expressions, statement conditions and
 * directive templates all speak the same dialect.
 *
 * <p>Customization is done by starting from {@link #builder()} and declaring the extra
 * operators, functions or variables a feature needs:
 *
 * <pre>{@code
 * NExprContext ctx = NaruExprs.builder()
 *         .declareOperator("..", NFixity.INFIX, NExprOpPrecedence.ASSIGN,
 *                 NOperatorAssociativity.LEFT, handler)
 *         .declareVars(myVars)
 *         .build();
 * }</pre>
 *
 * @see NExprContextBuilder
 */
public final class NaruExprs {

    private NaruExprs() {
    }

    /**
     * A fresh, customizable builder pre-loaded with the Nuts builtins (C/Java operators
     * such as {@code &&}, {@code ||}, {@code !}, arithmetic and comparison). Prefer this
     * over calling {@code NExprContextBuilder.of()} directly so that NARU has a single
     * expression dialect.
     */
    public static NExprContextBuilder builder() {
        return NExprContextBuilder.of().declareBuiltins();
    }

    /**
     * The builder used for expressions that run against a task: builtins plus math
     * constants/functions, physics constants and the task variable resolver.
     */
    public static NExprContextBuilder runtimeBuilder(NExprVarResolver vars) {
        NExprContextBuilder b = builder()
                .declareMathConstants()
                .declareMathFunctions()
                .declarePhysicsConstants();
        if (vars != null) {
            b = b.declareVars(vars);
        }
        return b;
    }

    /** A ready-to-use context with the NARU default dialect (builtins only). */
    public static NExprContext context() {
        return builder().build();
    }

    /**
     * Parses {@code expression} with the NARU default dialect, throwing when it is not a
     * valid expression.
     */
    public static NExprNode parse(String expression) {
        return builder().build().parse(expression).get();
    }

    /**
     * Parses {@code expression} with the NARU default dialect, returning the optional
     * parser result (present on success, an error optional carrying the diagnostic
     * otherwise) instead of throwing.
     *
     * <p>The Nuts parser usually reports a syntax error through the returned optional, but
     * some malformed inputs surface as an {@link NException} instead; both are folded into
     * the error optional here so callers get one non-throwing contract.
     */
    public static NOptional<NExprNode> tryParse(String expression) {
        try {
            return builder().build().parse(expression);
        } catch (NException e) {
            String message = e.getMessage();
            return NOptional.ofError(NMsg.ofC("%s", message == null ? String.valueOf(e) : message));
        }
    }
}
