package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;

import java.util.ArrayList;
import java.util.List;

public class NaruIfStmt extends NaruIncrementalStmt {
    public String condition;
    public List<NaruStatement> trueBranch = new ArrayList<>();
    private List<ElseIfBranch> elseIfBranch = new ArrayList<>();
    public List<NaruStatement> falseBranch = new ArrayList<>();

    enum IfStatus {
        IF, ELSE, ELSEIF, COMPLETE
    }
    static class ElseIfBranch {
        String condition;
        List<NaruStatement> branch = new ArrayList<>();

        public ElseIfBranch(String condition) {
            this.condition = condition;
        }
    }

    private IfStatus status=IfStatus.IF;

    public NaruIfStmt() {
        super(NaruStatement.Type.IF);
    }

    public NaruIfStmt(NElement element) {
        super(NaruStatement.Type.IF);
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

    public void exec(NaruSession session) {
        if(isPending()){
            session.throwError(NMsg.ofC("error statement : incomplete if statement"));
        }
        Object object=session.evalExpression(condition);
        if(NLiteral.of(object).asBoolean().orElse(false)){
            session.pushStatements(trueBranch.toArray(new NaruStatement[0]));
        }else{
            for (ElseIfBranch ifBranch : elseIfBranch) {
                object=session.evalExpression(ifBranch.condition);
                if(NLiteral.of(object).asBoolean().orElse(false)){
                    session.pushStatements(trueBranch.toArray(new NaruStatement[0]));
                    return;
                }
            }
            session.pushStatements(falseBranch.toArray(new NaruStatement[0]));
        }
    }

    public boolean acceptStatement(NaruStatement any, NaruSession session) {
        switch (status) {
            case IF:{
                NaruStatement last = trueBranch.isEmpty()?null:trueBranch.get(trueBranch.size()-1);
                if(last instanceof NaruIncrementalStmt){
                    ((NaruIncrementalStmt) last).acceptStatement(any,session);
                }else{
                    if(any instanceof NaruElseStmt){
                        status=IfStatus.ELSE;
                    }else if(any instanceof NaruElseIfStmt){
                        status=IfStatus.ELSEIF;
                        elseIfBranch.add(new ElseIfBranch(((NaruElseIfStmt) any).condition));
                    }else if(any instanceof NaruEndStmt){
                        status=IfStatus.COMPLETE;
                        return true;
                    }else{
                        trueBranch.add(any);
                    }
                }
                break;
            }
            case ELSEIF:{
                ElseIfBranch lastEF = elseIfBranch.get(elseIfBranch.size()-1);
                NaruStatement last = lastEF.branch.isEmpty()?null:lastEF.branch.get(lastEF.branch.size()-1);
                if(last instanceof NaruIncrementalStmt){
                    ((NaruIncrementalStmt) last).acceptStatement(any,session);
                }else{
                    if(any instanceof NaruElseStmt){
                        status=IfStatus.ELSE;
                    }else if(any instanceof NaruElseIfStmt){
                        status=IfStatus.ELSEIF;
                        elseIfBranch.add(new ElseIfBranch(((NaruElseIfStmt) any).condition));
                    }else if(any instanceof NaruEndStmt){
                        status=IfStatus.COMPLETE;
                        return true;
                    }else{
                        lastEF.branch.add(any);
                    }
                }
                break;
            }
            case ELSE:{
                NaruStatement last = trueBranch.isEmpty()?null:trueBranch.get(trueBranch.size()-1);
                if(last instanceof NaruIncrementalStmt){
                    ((NaruIncrementalStmt) last).acceptStatement(any,session);
                }else{
                    if(any instanceof NaruElseStmt){
                        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("error statement : unexpected 'else'"));
                        session.throwError(NMsg.ofC("error statement : unexpected 'else'"));
                    }else if(any instanceof NaruElseIfStmt){
                        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("error statement : unexpected 'else'"));
                        session.throwError(NMsg.ofC("error statement : unexpected 'elseif'"));
                    }else if(any instanceof NaruEndStmt){
                        status=IfStatus.COMPLETE;
                        return true;
                    }else{
                        falseBranch.add(any);
                    }
                }
                break;
            }
        }
        return false;
    }

    @Override
    public boolean isPending() {
        return status!=IfStatus.COMPLETE;
    }
}
