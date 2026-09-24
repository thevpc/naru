package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.expr.NExprContext;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruSetDirective extends NaruDirectiveBase {

    private static final Pattern INC_DEC =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)(\\+\\+|--)\\s*$");
    private static final Pattern COMPOUND =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*(\\+=|-=|\\*=|/=)\\s*(.+)$");

    public NaruSetDirective() {
        super("set", "routine", "set variable value");
        register(new AbstractSubCommand(
                new SubCommandHelp("<var> = <expr>", "set variable value. The default scope is the task env, so --task is optional.\nex:\n/set a=x*2\n/set attempts++\n/set n += 2"),
                new SubCommandHelp("<var>++ | <var>--", "increment/decrement the variable by 1 (a missing var counts as 0).\nex:\n/set attempts++"),
                new SubCommandHelp("<var> += <expr> | -= <expr> | *= <expr> | /= <expr>", "apply an arithmetic update to the variable (a missing var counts as 0).\nex:\n/set n += 2"),
                new SubCommandHelp("--task <var> = <expr>", "explicitly target the task env (this is the default).\nex:\n/set --task a=x*2"),
                new SubCommandHelp("--session <var> = <expr>", "target the session env.\nex:\n/set --session a=x*2"),
                new SubCommandHelp("--local <var> = <expr>", "target the current frame local variable.\nex:\n/set --local a=x*2"),
                new SubCommandHelp("", "list variables of the default scope (task env)"),
                new SubCommandHelp("--task", "list task env variables"),
                new SubCommandHelp("--session", "list session env variables"),
                new SubCommandHelp("--local", "list current frame variables")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String raw = context.argument() == null ? "" : context.argument().trim();
                VarType v = VarType.TASK;
                if (raw.startsWith("--task ") || raw.equals("--task")) {
                    raw = raw.substring("--task".length()).trim();
                    v = VarType.TASK;
                } else if (raw.startsWith("--session ") || raw.equals("--session")) {
                    raw = raw.substring("--session".length()).trim();
                    v = VarType.SESSION;
                } else if (raw.startsWith("--local ") || raw.equals("--local")) {
                    raw = raw.substring("--local".length()).trim();
                    v = VarType.VAR;
                }
                if (raw.isEmpty()) {
                    return listVars(task, v);
                }
                NExprContext b = task.expressionBuilder().build();
                // 1) increment / decrement: name++ | name--
                Matcher inc = INC_DEC.matcher(raw);
                if (inc.matches()) {
                    String name = inc.group(1);
                    long delta = inc.group(2).equals("++") ? 1L : -1L;
                    long current = NLiteral.of(readVar(task, v, name)).asLong().orElse(0L);
                    long value = current + delta;
                    writeVar(task, v, name, value);
                    return NaruStmtResult.of(value, setExitCode(value));
                }
                // 2) compound assignment: name op= <expr>
                Matcher cmp = COMPOUND.matcher(raw);
                if (cmp.matches()) {
                    String name = cmp.group(1);
                    String op = cmp.group(2);
                    String rhs = cmp.group(3).trim();
                    NOptional<NExprNode> rn = b.parse(rhs);
                    if (!rn.isPresent()) {
                        task.throwError(NMsg.ofC("expected <var> <op>= <expr> : %s", raw));
                        return NaruStmtResult.ofError("expected <var> <op>= <expr>");
                    }
                    Object rhsValue = rn.get().eval(b).orNull();
                    Object value = applyCompound(task, readVar(task, v, name), rhsValue, op);
                    if (value == null) {
                        return NaruStmtResult.ofError("invalid compound assignment"); // applyCompound already reported the error
                    }
                    writeVar(task, v, name, value);
                    return NaruStmtResult.of(value, setExitCode(value));
                }
                // 3) plain assignment: name = <expr>
                NOptional<NExprNode> n = b.parse(raw);
                if (n.isPresent()) {
                    NExprNode a = n.get();
                    if (a instanceof NExprOpNode && a.name().equals("=") && a.children().size() == 2) {
                        String varName = a.children().get(0).name();
                        Object exprValue = a.children().get(1).eval(b).orNull();
                        writeVar(task, v, varName, exprValue);
                        return NaruStmtResult.of(exprValue, setExitCode(exprValue));
                    } else {
                        task.throwError(NMsg.ofC("expected var = <expr>"));
                        return NaruStmtResult.ofError("expected var = <expr>");
                    }
                } else {
                    return listVars(task, v);
                }
            }
        });
    }

    private static Object readVar(NaruTask task, VarType v, String name) {
        switch (v) {
            case SESSION:
                return task.session().getSessionEnv(name).orNull();
            case VAR:
                return task.frame().getLocalVar(name).orNull();
            default:
                return task.getTaskEnv(name, false).orNull();
        }
    }

    private static void writeVar(NaruTask task, VarType v, String name, Object value) {
        switch (v) {
            case SESSION:
                task.session().setSessionEnv(name, value);
                break;
            case VAR:
                task.frame().setLocalVar(name, value);
                break;
            default:
                task.setTaskEnv(name, value);
                break;
        }
    }

    private static Object applyCompound(NaruTask task, Object current, Object rhs, String op) {
        boolean bothLong = NLiteral.of(current).asLong().isPresent()
                && NLiteral.of(rhs).asLong().isPresent()
                && !op.equals("/=");
        if (bothLong) {
            long x = NLiteral.of(current).asLong().get();
            long y = NLiteral.of(rhs).asLong().get();
            switch (op) {
                case "+=": return x + y;
                case "-=": return x - y;
                case "*=": return x * y;
                default:
                    task.throwError(NMsg.ofC("unsupported operator %s", op));
                    return null;
            }
        }
        double x = NLiteral.of(current).asDouble().orElse(0.0);
        double y = NLiteral.of(rhs).asDouble().orElse(0.0);
        switch (op) {
            case "+=": return x + y;
            case "-=": return x - y;
            case "*=": return x * y;
            case "/=": {
            if (y == 0.0) {
                return Double.NaN;
            }
            double d = x / y;
            // exact division of integral values keeps the integral type (9/3==3, not 3.0)
            if (d == Math.rint(d) && Math.abs(d) < (double) Long.MAX_VALUE) {
                return (long) d;
            }
            return d;
        }
            default:
                task.throwError(NMsg.ofC("unsupported operator %s", op));
                return null;
        }
    }

    /**
     * Exit code published by a successful /set (Rule C): 0 on success EXCEPT when
     * the assigned value is a Boolean, in which case the exit code mirrors it
     * (true -> 0, false -> 1). A check like
     *     /set green = (calc3 == "3")
     * therefore yields lastExitCode=0 when the condition holds and
     * lastExitCode=1 otherwise — without scripts ever writing lastExitCode
     * themselves. The (value, exitCode) pair is returned to the task's
     * invokeDirective factorization, which publishes lastResult / lastExitCode.
     */
    private static int setExitCode(Object value) {
        return value instanceof Boolean ? ((Boolean) value ? 0 : 1) : 0;
    }

    private static NaruStmtResult listVars(NaruTask task, VarType v) {
        Map<String, Object> varMap;
        switch (v) {
            case SESSION:
                varMap = task.session().getSessionEnv();
                break;
            case VAR:
                varMap = task.frame().getAllVars();
                break;
            default:
                varMap = task.getTaskEnv();
                break;
        }
        varMap.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC(
                        "%s : %s"
                        , NMsg.ofStyledPrimary1(e.getKey())
                        , e.getValue()
                )));
        return NaruStmtResult.ofSuccess(varMap);
    }

    enum VarType {
        VAR, TASK, SESSION
    }
}