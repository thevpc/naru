package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;

/**
 * Asserts a script condition in ONE line (it replaces the common
 * "/if cond /print ... /set var = 0 /else /print ... /end" pattern):
 *
 * <pre>
 * /assert lastExitCode == 0
 * /assert --set green calc42 == "42"
 * /assert --fail fileCount == 3      (abort the script when false)
 * </pre>
 *
 * The condition is evaluated exactly like an /if condition; the {{var}}
 * interpolation of directive arguments already resolved values, so the log
 * shows the actual numbers/strings being compared. A success publishes
 * {@code lastExitCode=0} and logs "✓ assert &lt;condition&gt;"; a failure
 * publishes {@code lastExitCode=1} and logs "✗ assert &lt;condition&gt;".
 * <p>
 * {@code --set <var>} AND-accumulates the outcome into the task env var
 * {@code <var>}: an unset var behaves as "true", a single failure flips it to
 * 0 and later successes cannot raise it again. A chain of asserts can thus
 * drive ONE "everything green" flag, e.g. the /while exit condition of a
 * build loop.
 * <p>
 * {@code --fail} additionally aborts the script (task error) on the first
 * failure, for anyone who expects the classic "assert breaks out" semantics.
 * Without it /assert is non-fatal by design: a script may want to keep going
 * and only report the outcome.
 */
public class NaruAssertDirective extends NaruDirectiveBase {

    public NaruAssertDirective() {
        super("assert", "control", "assert a condition", "check");
        register(new AbstractSubCommand(
                new SubCommandHelp("<condition>", "evaluate the condition; log ✓/✗, publish lastExitCode (0=ok,1=failed,2=error).\nex:\n/assert lastExitCode == 0"),
                new SubCommandHelp("--set <var> <condition>", "also AND-accumulate the outcome into the task var <var> (starts as true, a failure pins it to 0).\nex:\n/assert --set green calc42 == \"42\""),
                new SubCommandHelp("--fail <condition>", "abort the script (task error) when the condition is false.\nex:\n/assert --fail count == 3")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String raw = context.argument() == null ? "" : context.argument().trim();
                boolean fail = false;
                String setVar = null;
                while (true) {
                    if (raw.equals("--fail")) {
                        fail = true;
                        raw = "";
                    } else if (raw.startsWith("--fail ")) {
                        fail = true;
                        raw = raw.substring("--fail ".length()).trim();
                    } else if (raw.startsWith("--set ")) {
                        String rest = raw.substring("--set ".length());
                        int sp = rest.indexOf(' ');
                        setVar = sp < 0 ? rest : rest.substring(0, sp);
                        raw = (sp < 0 ? "" : rest.substring(sp + 1)).trim();
                    } else {
                        break;
                    }
                }
                String condition = raw;
                if (condition.isEmpty()) {
                    NMsg msg = NMsg.ofC("assert: missing condition").asError();
                    task.log(NaruLogMode.PROGRESS, msg);
                    if (fail) {
                        task.throwError(NMsg.ofC("assert failed: missing condition"));
                    }
                    return NaruStmtResult.of(msg.toString(), 2);
                }
                Object value = task.evalExpression(condition);
                boolean ok = NLiteral.of(value).asBoolean().orElse(false);
                if (ok) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("✓ assert %s", NMsg.ofStyledPrimary1(condition)));
                } else {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("✗ assert %s", NMsg.ofStyledPrimary2(condition)));
                }
                if (setVar != null && !setVar.isEmpty()) {
                    Object current = task.getTaskEnv(setVar, false).orNull();
                    boolean prev = NLiteral.of(current).asBoolean().orElse(true);
                    task.setTaskEnv(setVar, (prev && ok) ? 1 : 0);
                }
                if (!ok && fail) {
                    task.throwError(NMsg.ofC("assert failed: %s", condition));
                }
                // exit code mirrors the condition: 0 = pass, 1 = fail.
                // _ / lastResult carries the condition value; lastError the condition
                // value too when the assertion failed (via NaruStmtResult.of).
                return NaruStmtResult.of(value, ok ? 0 : 1);
            }
        });
    }
}