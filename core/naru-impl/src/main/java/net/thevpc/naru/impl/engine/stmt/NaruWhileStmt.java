package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.engine.stmt.shared.NaruSimpleParseStatus;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;

import java.util.List;
import java.util.stream.Collectors;

public class NaruWhileStmt extends NaruIncrementalStmt implements Cloneable {
    private final String condition;
    private List<NaruStatement> body = new java.util.ArrayList<>();
    private NaruSimpleParseStatus parseStatus = NaruSimpleParseStatus.PENDING;
    private int runtimeNextChildIndex = -1;

    public NaruWhileStmt(String condition) {
        super(Type.WHILE);
        this.condition = condition;
        this.parseStatus = NaruSimpleParseStatus.PENDING;
    }

    public NaruWhileStmt(NElement element) {
        super(Type.WHILE);
        NListContainerElement lc = element.asListContainer().get();
        this.condition = lc.get("condition").flatMap(NElement::asStringValue).orNull();
        this.body = lc.get("body").flatMap(NElement::asArray).get()
                .children()
                .stream().map(NaruStatementHelper::of).collect(Collectors.toList());
        this.parseStatus = NaruSimpleParseStatus.parse(lc.getStringValue("parseStatus").orElse("")).orElse(NaruSimpleParseStatus.COMPLETE);
        this.runtimeNextChildIndex = lc.getIntValue("runtimeNextChildIndex").orElse(-1);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("condition", NElement.ofString(condition));
        a.set("body", NElement.ofArray(body == null ? new NElement[0] : body.stream().map(x -> x.toElement()).toArray(NElement[]::new)));
        if (parseStatus != null && parseStatus != NaruSimpleParseStatus.COMPLETE) {
            a.set("parseStatus", parseStatus.name());
        }
        if (runtimeNextChildIndex >= 0) {
            a.set("runtimeNextChildIndex", runtimeNextChildIndex);
        }
        return a.build();
    }

    @Override
    protected NaruStatement clone() {
        NaruWhileStmt o = (NaruWhileStmt) super.clone();
        if (o.body != null) {
            o.body = o.body.stream().map(x -> x.copy()).collect(Collectors.toList());
        }
        return o;
    }

    @Override
    public boolean isPending() {
        return parseStatus == NaruSimpleParseStatus.PENDING;
    }

    @Override
    public boolean acceptStatement(NaruStatement any, NaruTask task) {
        if (!isPending()) return false;

        // Support nested compound blocks cleanly by delegating downwards if the last child is still open
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
            task.throwError(NMsg.ofC("Error statement: incomplete 'while' statement"));
        }

        // Read the tracking state from internal storage frame
        int nextChildIndex = this.runtimeNextChildIndex;

        // Phase A: Evaluate loop condition
        if (nextChildIndex == -1) {
            Object obj = task.evalExpression(condition);
            boolean conditionTrue = NLiteral.of(obj).asBoolean().orElse(false);

            if (!conditionTrue) {
                task.defaultAdvance(this);
                return;
            }
            nextChildIndex = 0;
        }

        // Phase B & C Combined: Optimized routing execution
        if (nextChildIndex < body.size()) {
            NaruStatement currentChild = body.get(nextChildIndex);
            NaruWhileStmt selfCopy = (NaruWhileStmt) copy();

            if (nextChildIndex + 1 < body.size()) {
                // There are still more statements remaining in this iteration loop block
                selfCopy.runtimeNextChildIndex = nextChildIndex + 1;
            } else {
                selfCopy.runtimeNextChildIndex = -1;
            }

            // Enqueue loop controller back onto execution frame first, then child
            task.prependStatement(selfCopy.injected(true));
            task.prependStatement(currentChild.copy().injected(true));
        } else {
            task.defaultAdvance(this);
        }
    }

}
