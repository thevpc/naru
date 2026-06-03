package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.stmt.shared.NaruSimpleParseStatus;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.expr.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class NaruForStmt extends NaruIncrementalStmt implements Cloneable {
    private String condition;
    private List<NaruStatement> body = new java.util.ArrayList<>();
    private NaruSimpleParseStatus parseStatus=NaruSimpleParseStatus.PENDING;
    private List<BigDecimal> runtimeIterator;
    private int runtimeChildIndexKey;
    private String runtimeVarName; // captured once, survives in copy

    public NaruForStmt(String condition) {
        super(NaruStatement.Type.FOR);
        this.condition = condition;
        this.parseStatus = NaruSimpleParseStatus.PENDING;
        this.runtimeIterator = null;
        this.runtimeChildIndexKey = 0;
        this.runtimeVarName = null;
    }


    public NaruForStmt(NElement element) {
        super(NaruStatement.Type.FOR, element);
        NListContainerElement lc = element.asListContainer().get();
        this.condition = lc.get("condition").flatMap(NElement::asStringValue).orNull();
        this.body = lc.get("body").flatMap(NElement::asArray).get()
                .children()
                .stream().map(NaruStatementHelper::of).collect(Collectors.toList());
        this.parseStatus = NaruSimpleParseStatus.parse(lc.getStringValue("parseStatus").orElse("")).orElse(NaruSimpleParseStatus.COMPLETE);
        this.runtimeChildIndexKey = lc.getIntValue("runtimeChildIndexKey").orElse(0);
        this.runtimeVarName = lc.getStringValue("runtimeVarName").orNull();
        NArrayElement runtimeIterator1 = lc.getArray("runtimeIterator").orElse(null);
        if (runtimeIterator1 != null) {
            this.runtimeIterator = runtimeIterator1.children().stream().map(x -> NLiteral.of(x).asBigDecimal().get()).collect(Collectors.toList());
        } else {
            this.runtimeIterator = null;
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        a.set("condition", NElement.ofString(condition));
        a.set("body", NElement.ofArray(body == null ? new NElement[0] : body.stream().map(x -> x.toElement()).toArray(NElement[]::new)));
        if (parseStatus!=null && parseStatus !=NaruSimpleParseStatus.COMPLETE) {
            a.set("parseStatus", parseStatus.name());
        }
        if (runtimeIterator != null) {
            a.set("runtimeIterator", NElement.ofArray(runtimeIterator.stream().map(NElement::ofNumber).toArray(NElement[]::new)));
            a.set("runtimeChildIndexKey", runtimeChildIndexKey);
            a.set("runtimeVarName", runtimeVarName);
        }
        return a.build();
    }

    @Override
    protected NaruStatement clone() {
        NaruForStmt o = (NaruForStmt) super.clone();
        if (o.body != null) {
            o.body = o.body.stream().map(x -> x.copy()).collect(Collectors.toList());
        }
        if (o.runtimeIterator != null) {
            o.runtimeIterator = new ArrayList<>(runtimeIterator);
        }
        return o;
    }

    @Override
    public boolean isPending() {
        return parseStatus==NaruSimpleParseStatus.PENDING;
    }

    @Override
    public boolean acceptStatement(NaruStatement any, NaruTask task) {
        if (!isPending()) {
            return false;
        }
        NaruStatement last = body.isEmpty() ? null : body.get(body.size() - 1);
        if (last instanceof NaruIncrementalStmt && ((NaruIncrementalStmt) last).isPending()) {
            return ((NaruIncrementalStmt) last).acceptStatement(any, task);
        }
        if (any instanceof NaruEndStmt) {
            this.parseStatus = NaruSimpleParseStatus.COMPLETE;
            return true;
        }
        body.add(any);
        return true;
    }

    @Override
    public void exec(NaruTask task) {
        if (isPending()) {
            task.throwError(NMsg.ofC("Error statement: incomplete 'for' statement"));
        }

        NaruTaskFrame ctx = task.peekFrame();

        final NaruForStmt selfCopy=(NaruForStmt) copy();
        // Phase A: First Pass Initialization
        if (runtimeIterator == null) {
            NExprContext ectx = task.expressionBuilder()
                    .declareOperator(":", NExprOpType.INFIX, NExprOpPrecedence.ASSIGN - 1, NOperatorAssociativity.LEFT, new NExprCallHandler() {
                        @Override
                        public Object eval(NExprCallContext callContext) {
                            // just needed for parsing
                            throw new UnsupportedOperationException("not supported.");
                        }
                    })
                    .declareOperator("..", NExprOpType.INFIX, NExprOpPrecedence.ASSIGN, NOperatorAssociativity.LEFT, new NExprCallHandler() {
                        @Override
                        public Object eval(NExprCallContext callContext) {
                            // just needed for parsing
                            throw new UnsupportedOperationException("not supported.");
                        }
                    })
                    .build();
            NOptional<NExprNode> n = ectx.parse(condition);
            if (!n.isPresent()) {
                task.throwError(NMsg.ofC("Error parsing expression '%s'", condition));
            }
            NExprNode nn = n.get();
            String varName = null;
            NExprNode from = null;
            NExprNode to = null;
            NExprNode step = null;
            if (nn instanceof NExprOpNode && nn.name().equals(":")) {
                if (nn.children().get(0) instanceof NExprWordNode) {
                    varName = nn.children().get(0).name();
                    if (nn.children().get(1) instanceof NExprOpNode && nn.children().get(1).name().equals("..")) {
                        from = nn.children().get(1).children().get(0);
                        to = nn.children().get(1).children().get(1);
                        if (to instanceof NExprOpNode && to.name().equals(":")) {
                            NExprNode i0 = to.children().get(0);
                            step = to.children().get(1);
                            to = i0;
                        }
                    } else {
                        task.throwError(NMsg.ofC("Error parsing expression '%s'", condition));
                        return;
                    }
                } else {
                    task.throwError(NMsg.ofC("Error parsing expression '%s'", condition));
                    return;
                }
            } else {
                task.throwError(NMsg.ofC("Error parsing expression '%s'", condition));
                return;
            }

            BigDecimal vFrom = NLiteral.of(from.eval(ectx).orNull()).asBigDecimal().orNull();
            BigDecimal vTo = NLiteral.of(to.eval(ectx).orNull()).asBigDecimal().orNull();
            BigDecimal vStep = step == null ? null : NLiteral.of(step.eval(ectx)).asBigDecimal().orNull();
            if (vFrom == null || vTo == null) {
                task.throwError(NMsg.ofC("Loop boundaries must evaluate to valid numbers in expression '%s'", condition));
                return;
            }
            // Calculate direction trend signum: 1 if ascending, -1 if descending, 0 if equal
            int direction = vTo.compareTo(vFrom);

            // Resolve default step values if omitted by the user
            if (vStep == null) {
                vStep = (direction >= 0) ? BigDecimal.ONE : BigDecimal.valueOf(-1);
            }

            // 🔒 HARDENING BOUNDARY CHECKS
            if (vStep.signum() == 0) {
                task.throwError(NMsg.ofC("Loop step increment cannot be zero in expression '%s'", condition));
                return;
            }
            if (direction > 0 && vStep.signum() < 0) {
                task.throwError(NMsg.ofC("Infinite loop detected: step is negative while sequence is ascending in '%s'", condition));
                return;
            }
            if (direction < 0 && vStep.signum() > 0) {
                task.throwError(NMsg.ofC("Infinite loop detected: step is positive while sequence is descending in '%s'", condition));
                return;
            }

            final BigDecimal finalStart = vFrom;
            final BigDecimal finalEnd = vTo;
            final BigDecimal finalStep = vStep;
            final int finalDirection = finalStep.signum(); // 1 = tracking up, -1 = tracking down

            Iterable<BigDecimal> rangeIterable = () -> new java.util.Iterator<BigDecimal>() {
                private BigDecimal current = finalStart;

                @Override
                public boolean hasNext() {
                    // Exact exact checks using compareTo without precision cushions
                    if (finalDirection > 0) {
                        return current.compareTo(finalEnd) <= 0;
                    } else {
                        return current.compareTo(finalEnd) >= 0;
                    }
                }

                @Override
                public BigDecimal next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    BigDecimal val = current;
                    current = current.add(finalStep);
                    return val;
                }
            };
            Iterator<BigDecimal> it0 = rangeIterable.iterator();
            List<BigDecimal> it = new ArrayList<>();
            while (it0.hasNext()){
                it.add(it0.next());
            }

            // Immediate terminal shortcut check for empty matching bounds (e.g. 5 .. 5 step 1 evaluates fine, but 5 .. 4 step 1 exits)
            if (it.isEmpty()) {
                task.defaultAdvance(this);
                return;
            }
            selfCopy.runtimeIterator=it;
            selfCopy.runtimeVarName=varName;
            // Bind the initial precise BigDecimal value directly to the parameter context environment
            ctx.setParam(varName, it.remove(0));
            selfCopy.runtimeChildIndexKey=0;
        }

        int nextChildIndex = selfCopy.runtimeChildIndexKey;

        if (nextChildIndex < body.size()) {
            NaruStatement currentChild = body.get(nextChildIndex);
            task.prependStatement(selfCopy.injected(true));
            if (nextChildIndex + 1 < body.size()) {
                // Step internal instruction pointer inside the branch
                selfCopy.runtimeChildIndexKey++;
            } else {
                if (!selfCopy.runtimeIterator.isEmpty()) {
                    task.prependStatement(new NaruSetStmt(selfCopy.runtimeVarName,""+selfCopy.runtimeIterator.remove(0)));
                    selfCopy.runtimeChildIndexKey=0;
                } else {
                    selfCopy.runtimeChildIndexKey=body.size();
                }
            }

            task.prependStatement(currentChild.copy().injected(true));
        } else {
            task.defaultAdvance(this);
        }
    }
}
