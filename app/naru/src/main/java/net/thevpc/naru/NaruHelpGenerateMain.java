package net.thevpc.naru;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;

import java.util.Arrays;

/**
 * Offline help generator: runs {@code /help --full} (or any {@code /help} arguments) and
 * prints the generated manual text to stdout.
 *
 * <p>This is the bootstrap behind {@code scripts/generate-help.sh}, which fences the output
 * into {@code HELP.md}. It is deliberately model-free: the session it builds never runs an
 * AI prompt — {@code /help --full} only walks the directive registry and renders each
 * directive's built-in documentation, so there is no need to resolve a reasoning model. The
 * task is created held and nothing ever calls {@code unhold()}.
 *
 * <p>Usage:
 * <pre>
 *   java -cp &lt;classpath&gt; net.thevpc.naru.NaruHelpGenerateMain [--full|--syntax|directive ...]
 * </pre>
 */
public class NaruHelpGenerateMain {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private static void openStandaloneWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
                // if opening fails we still try to build the session; it may use a
                // pre-existing workspace
            }
        }
    }

    public static void main(String[] args) {
        openStandaloneWorkspace();
        NPath projectDir = NPath.ofTempFolder("naru-helpgen-" + System.nanoTime());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        NaruSessionImpl session = new NaruSessionImpl(
                agent, projectDir,
                new NaruStreamInteraction(o -> {
                    // help renders through the directive result; session output is discarded
                }),
                true, NOOP_LISTENER, null, null, null);
        try {
            NaruTask task = session.newTask(NaruTaskSpec.of());
            NaruDirective help = session.registry().findDirective("help")
                    .orElseThrow(() -> new IllegalStateException("no /help directive registered"));
            String argument = args.length == 0 ? "--full" : String.join(" ", args);
            NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("help", argument, task);
            NaruStmtResult result = help.execute(ctx);
            Object value = result == null ? null : result.successValue();
            String out = value == null ? "" : String.valueOf(value);
            System.out.println(out);
            if (result != null && result.errorValue() != null) {
                System.err.println("NaruHelpGenerateMain: /help reported an error: " + result.errorValue());
                System.exit(1);
            }
            if (out.isBlank()) {
                System.err.println("NaruHelpGenerateMain: /help produced no output (args="
                        + Arrays.toString(args) + ")");
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("NaruHelpGenerateMain failed: " + e);
            e.printStackTrace(System.err);
            System.exit(3);
        } finally {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }
}