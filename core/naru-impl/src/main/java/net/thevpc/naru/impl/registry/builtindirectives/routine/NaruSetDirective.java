package net.thevpc.naru.impl.registry.builtindirectives.routine;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.expr.NExprContext;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.expr.NExprWordNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

public class NaruSetDirective extends AbstractDirective {
    public NaruSetDirective() {
        super("set", "routine", "set variable value");
        register(new AbstractSubCommand(
                new SubCommandHelp("<var> = <expr>", "set variable value.\nex:\n/set a=x*2"),
                new SubCommandHelp("--task <var> = <expr>", "set task env variable value.\nex:\n/set --task a=x*2"),
                new SubCommandHelp("--session <var> = <expr>", "set session env variable value.\nex:\n/set --session a=x*2")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                String raw = context.argument();
                VarType v = VarType.VAR;
                if (raw.startsWith("--task ")) {
                    raw = raw.substring("--task ".length());
                    v = VarType.TASK;
                } else if (raw.startsWith("--session ")) {
                    raw = raw.substring("--session ".length());
                    v = VarType.SESSION;
                }
                NExprContext b = context.task().expressionBuilder().build();
                NOptional<NExprNode> n = b.parse(raw);
                if (n.isPresent()) {
                    NExprNode a = n.get();
                    if (a instanceof NExprOpNode && a.name().equals("=") && a.children().size() == 2) {
                        //this will assign var using varResolver (and store it into runcontext
                        String varName = a.children().get(0).name();
                        Object exprValue = a.children().get(1).eval(b).orNull();
                        switch (v) {
                            case VAR: {
                                context.task().frame().setLocalVar(
                                        varName,
                                        exprValue
                                );
                                a.eval(b).get();
                                break;
                            }
                            case TASK: {
                                context.task().setTaskEnv(
                                        varName,
                                        exprValue
                                );
                                break;
                            }
                            case SESSION: {
                                context.task().session().setSessionEnv(
                                        varName,
                                        exprValue
                                );
                                break;
                            }
                        }
                        context.task().frame().setLastResult(NaruStmtResult.ofSuccess(exprValue));
                        return;
                    }
                }
                context.task().throwError(NMsg.ofC("expected var = <expr>"));
            }
        });
    }

    enum VarType {
        VAR, TASK, SESSION
    }
}
