package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class NaruIfStmt extends NaruIncrementalStmt implements Cloneable {
    public String condition;
    public List<NaruStatement> trueBranch = new ArrayList<>();
    private List<ElseIfBranch> elseIfBranch = new ArrayList<>();
    public List<NaruStatement> falseBranch = new ArrayList<>();
    private IfStatus parseStatus = IfStatus.IF;

    private int runtimeNextChildIndex = -2;        // -2 = not evaluated yet
    private BranchType runtimeSelectedBranchType = null;  // which kind of branch
    private int runtimeSelectedBranchSubIndex = -1;       // only for ELSEIF — which one

    public NaruIfStmt(String condition) {
        super(NaruStatement.Type.IF);
        this.condition = condition;
    }

    public NaruIfStmt(NElement element) {
        super(NaruStatement.Type.IF, element);
        NListContainerElement lc = element.asListContainer().get();
        this.condition = lc.get("condition").flatMap(NElement::asStringValue).orNull();
        this.trueBranch = lc.get("trueBranch").flatMap(NElement::asArray).orElse(NElement.ofArray())
                .children()
                .stream().map(NaruStatementHelper::of).collect(Collectors.toList());
        this.elseIfBranch = lc.get("elseIfBranch").flatMap(NElement::asArray).orElse(NElement.ofArray())
                .children()
                .stream().map(x -> new ElseIfBranch(x)).collect(Collectors.toList());
        this.falseBranch = lc.get("falseBranch").flatMap(NElement::asArray).orElse(NElement.ofArray())
                .children()
                .stream().map(NaruStatementHelper::of).collect(Collectors.toList());
        this.parseStatus = IfStatus.parse(lc.getStringValue("parseStatus").orElse("")).orElse(IfStatus.COMPLETE);
        this.runtimeNextChildIndex = lc.getIntValue("runtimeNextChildIndex").orElse(-2);
        if(this.runtimeNextChildIndex!=-1) {
            this.runtimeSelectedBranchType = BranchType.parse(lc.getStringValue("runtimeSelectedBranchType").orElse("")).orElse(null);
            this.runtimeSelectedBranchSubIndex = lc.getIntValue("runtimeSelectedBranchSubIndex").orElse(-1);
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("condition", NElement.ofString(condition));
        a.set("trueBranch", NElement.ofArray(trueBranch == null ? new NElement[0] : trueBranch.stream().map(x -> x.toElement()).toArray(NElement[]::new)));
        a.set("elseIfBranch", NElement.ofArray(elseIfBranch == null ? new NElement[0] : elseIfBranch.stream().map(x -> x.toElement()).toArray(NElement[]::new)));
        a.set("falseBranch", NElement.ofArray(falseBranch == null ? new NElement[0] : falseBranch.stream().map(x -> x.toElement()).toArray(NElement[]::new)));
        if(parseStatus !=null && parseStatus !=IfStatus.COMPLETE) {
            a.set("parseStatus", parseStatus.name());
        }
        if(runtimeNextChildIndex !=-2) {
            a.set("runtimeNextChildIndex", runtimeNextChildIndex);
            a.set("runtimeSelectedBranchType", runtimeSelectedBranchType.name());
            a.set("runtimeSelectedBranchSubIndex", runtimeSelectedBranchSubIndex);
        }
        return a.build();
    }

    @Override
    protected NaruStatement clone() {
        NaruIfStmt o = (NaruIfStmt) super.clone();
        if (o.trueBranch != null) {
            o.trueBranch = o.trueBranch.stream().map(x -> x.copy()).collect(Collectors.toList());
        }
        if (o.elseIfBranch != null) {
            o.elseIfBranch = o.elseIfBranch.stream().map(x -> x.copy()).collect(Collectors.toList());
        }
        if (o.falseBranch != null) {
            o.falseBranch = o.falseBranch.stream().map(x -> x.copy()).collect(Collectors.toList());
        }
        return o;
    }

    public String getCondition() {
        return condition;
    }

    public NaruIfStmt setCondition(String condition) {
        this.condition = condition;
        return this;
    }

    public List<NaruStatement> getTrueBranch() {
        return trueBranch;
    }

    public NaruIfStmt setTrueBranch(List<NaruStatement> trueBranch) {
        this.trueBranch = trueBranch;
        return this;
    }

    public List<NaruStatement> getFalseBranch() {
        return falseBranch;
    }

    public NaruIfStmt setFalseBranch(List<NaruStatement> falseBranch) {
        this.falseBranch = falseBranch;
        return this;
    }

    public void exec(NaruTask task) {
        if (isPending()) {
            task.throwError(NMsg.ofC("Error statement: incomplete 'if' statement"));
        }

        // Phase A — evaluate the condition ONCE and decide which branch runs.
        // (Using the same copy+prepend pattern as /while: the branch children run
        // one at a time through injected copies, and the final copy advances.)
        int nextChildIndex = this.runtimeNextChildIndex;
        if (nextChildIndex == -2) {
            resolveBranch(task);
            this.runtimeNextChildIndex = 0;
        }

        List<NaruStatement> branch = getSelectedBranch();

        // Phase B — one child at a time
        if (branch == null || runtimeNextChildIndex >= branch.size()) {
            task.defaultAdvance(this);
            return;
        }

        NaruStatement currentChild = branch.get(runtimeNextChildIndex);
        NaruIfStmt selfCopy = (NaruIfStmt) copy();
        selfCopy.runtimeSelectedBranchType = runtimeSelectedBranchType;
        selfCopy.runtimeSelectedBranchSubIndex = runtimeSelectedBranchSubIndex;
        selfCopy.runtimeNextChildIndex = runtimeNextChildIndex + 1;

        task.prependStatement(selfCopy.injected(true));
        task.prependStatement(currentChild.copy().injected(true));
    }

    private void resolveBranch(NaruTask task) {
        Object obj = task.evalExpression(condition);
        if (NLiteral.of(obj).asBoolean().orElse(false)) {
            runtimeSelectedBranchType = BranchType.TRUE;
            runtimeSelectedBranchSubIndex = -1;
            return;
        }
        for (int i = 0; i < elseIfBranch.size(); i++) {
            obj = task.evalExpression(elseIfBranch.get(i).condition);
            if (NLiteral.of(obj).asBoolean().orElse(false)) {
                runtimeSelectedBranchType = BranchType.ELSEIF;
                runtimeSelectedBranchSubIndex = i;
                return;
            }
        }
        runtimeSelectedBranchType = BranchType.FALSE;
        runtimeSelectedBranchSubIndex = -1;
    }

    private List<NaruStatement> getSelectedBranch() {
        if (runtimeSelectedBranchType == null) return null;
        switch (runtimeSelectedBranchType) {
            case TRUE:   return trueBranch;
            case FALSE:  return falseBranch;
            case ELSEIF: {
                // elseIfBranch list might have changed — guard
                if (runtimeSelectedBranchSubIndex < elseIfBranch.size()) {
                    return elseIfBranch.get(runtimeSelectedBranchSubIndex).body;
                }
                return null;
            }
        }
        return null;
    }

    public boolean acceptStatement(NaruStatement any, NaruTask task) {
        // Like /while, return TRUE whenever this statement was consumed (folded or
        // completed); the scheduler only advances its input when this returns true.
        switch (parseStatus) {
            case IF: {
                NaruStatement last = trueBranch.isEmpty() ? null : trueBranch.get(trueBranch.size() - 1);
                if (last instanceof NaruIncrementalStmt && ((NaruIncrementalStmt) last).isPending()) {
                    return ((NaruIncrementalStmt) last).acceptStatement(any, task);
                }
                if (any instanceof NaruElseStmt) {
                    parseStatus = IfStatus.ELSE;
                    return true;
                } else if (any instanceof NaruElseIfStmt) {
                    parseStatus = IfStatus.ELSEIF;
                    elseIfBranch.add(new ElseIfBranch(((NaruElseIfStmt) any).condition));
                    return true;
                } else if (any instanceof NaruEndStmt) {
                    parseStatus = IfStatus.COMPLETE;
                    return true;
                } else {
                    trueBranch.add(any);
                    return true;
                }
            }
            case ELSEIF: {
                ElseIfBranch lastEF = elseIfBranch.get(elseIfBranch.size() - 1);
                NaruStatement last = lastEF.body.isEmpty() ? null : lastEF.body.get(lastEF.body.size() - 1);
                if (last instanceof NaruIncrementalStmt && ((NaruIncrementalStmt) last).isPending()) {
                    return ((NaruIncrementalStmt) last).acceptStatement(any, task);
                }
                if (any instanceof NaruElseStmt) {
                    parseStatus = IfStatus.ELSE;
                    return true;
                } else if (any instanceof NaruElseIfStmt) {
                    parseStatus = IfStatus.ELSEIF;
                    elseIfBranch.add(new ElseIfBranch(((NaruElseIfStmt) any).condition));
                    return true;
                } else if (any instanceof NaruEndStmt) {
                    parseStatus = IfStatus.COMPLETE;
                    return true;
                } else {
                    lastEF.body.add(any);
                    return true;
                }
            }
            case ELSE: {
                NaruStatement last = falseBranch.isEmpty() ? null : falseBranch.get(falseBranch.size() - 1);
                if (last instanceof NaruIncrementalStmt && ((NaruIncrementalStmt) last).isPending()) {
                    return ((NaruIncrementalStmt) last).acceptStatement(any, task);
                }
                if (any instanceof NaruElseStmt) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("error statement : unexpected 'else'"));
                    task.throwError(NMsg.ofC("error statement : unexpected 'else'"));
                    return false;
                } else if (any instanceof NaruElseIfStmt) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("error statement : unexpected 'else'"));
                    task.throwError(NMsg.ofC("error statement : unexpected 'elseif'"));
                    return false;
                } else if (any instanceof NaruEndStmt) {
                    parseStatus = IfStatus.COMPLETE;
                    return true;
                } else {
                    falseBranch.add(any);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isPending() {
        return parseStatus != IfStatus.COMPLETE;
    }

    ///////////////

    enum BranchType {
        TRUE,    // trueBranch
        ELSEIF,  // elseIfBranch[selectedBranchSubIndex]
        FALSE  ;  // falseBranch

        public static NOptional<BranchType> parse(String s) {
            if (NBlankable.isBlank(s)) {
                return NOptional.ofNamedEmpty(s);
            }
            switch (NNameFormat.LOWER_KEBAB_CASE.format(s)) {
                case "if":
                case "true":
                    return NOptional.of(TRUE);
                case "elseif":
                case "else-if":
                    return NOptional.of(ELSEIF);
                case "else":
                case "false":
                    return NOptional.of(FALSE);
            }
            return NOptional.ofNamedError(NMsg.ofC("branch type %s", s));
        }
    }

    enum IfStatus {
        IF, ELSE, ELSEIF, COMPLETE;

        public static NOptional<IfStatus> parse(String s) {
            if (NBlankable.isBlank(s)) {
                return NOptional.ofNamedEmpty(s);
            }
            switch (NNameFormat.LOWER_KEBAB_CASE.format(s)) {
                case "if":
                    return NOptional.of(IF);
                case "else":
                    return NOptional.of(ELSE);
                case "elseif":
                case "else-if":
                    return NOptional.of(ELSEIF);
                case "complete":
                case "end":
                    return NOptional.of(COMPLETE);
            }
            return NOptional.ofNamedError(NMsg.ofC("status %s", s));
        }
    }

    static class ElseIfBranch implements NCopiable, Cloneable, NToElement {
        String condition;
        List<NaruStatement> body = new ArrayList<>();

        public ElseIfBranch(String condition) {
            this.condition = condition;
        }

        public ElseIfBranch(NElement element) {
            String name;
            if (element.isName()) {
                name = element.asName().get().stringValue();
            } else if (element.isListContainer()) {
                NListContainerElement o = element.asListContainer().get();
                if (o.isNamed()) {
                    name = o.asNamed().get().name().get();
                } else {
                    throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
                }
            } else {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
            switch (NNameFormat.CONST_NAME.format(name)) {
                case "ELSEIF":
                case "ELSE_IF": {
                    NListContainerElement lc = element.asListContainer().get();
                    this.condition = lc.get("condition").flatMap(NElement::asStringValue).orNull();
                    this.body = lc.get("body").flatMap(NElement::asArray).orElse(NElement.ofArray())
                            .children()
                            .stream().map(NaruStatementHelper::of).collect(Collectors.toList());
                    break;
                }
                default: {
                    throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
                }
            }
        }

        @Override
        public NElement toElement() {
            NObjectElementBuilder a = NElement.ofObjectBuilder("ELSEIF");
            a.set("condition", NElement.ofString(condition));
            a.set("body", NElement.ofArray(body == null ? new NElement[0] : body.stream().map(x -> x.toElement()).toArray(NElement[]::new)));
            return a.build();
        }

        @Override
        public ElseIfBranch copy() {
            return clone();
        }

        @Override
        protected ElseIfBranch clone() {
            try {
                ElseIfBranch o = (ElseIfBranch) super.clone();
                if (o.body != null) {
                    o.body = o.body.stream().map(x -> x.copy()).collect(Collectors.toList());
                }
                return o;
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
