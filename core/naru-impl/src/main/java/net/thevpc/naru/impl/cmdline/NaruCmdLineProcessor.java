package net.thevpc.naru.impl.cmdline;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExecutionException;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses CLI arguments and wires everything together for a single agent run.
 *
 * <p>Kept deliberately simple — no Nuts NCmdLine dependency here so this
 * class can be unit-tested without the Nuts runtime.
 *
 * <pre>
 * Usage:
 *   naru [options]
 *
 * Options:
 *   --task &lt;text&gt;           (required) The task description for the agent
 *   --model &lt;name&gt;          Reasoning model (default: qwen2.5-coder:7b)
 *   --vision-model &lt;name&gt;   Vision model for inspect_image (default: qwen2.5vl:7b)
 *   --provider &lt;name&gt;       Provider: ollama (default: ollama)
 *   --provider-url &lt;url&gt;    Provider base URL (default: http://localhost:11434)
 *   --directory &lt;path&gt;      Workspace folder holding the .naru store (default: .)
 *   --project &lt;id|name&gt;     Create the new session under that project
 *   --user &lt;name&gt;           Opaque user label for the new session
 *   --max-steps &lt;n&gt;         Max agent loop iterations (default: 20)
 *   --no-vision             Disable the inspect_image tool
 *   --quiet                 Suppress step-by-step output
 *   --help                  Show this help
 * </pre>
 */
public class NaruCmdLineProcessor {

    private String workspaceDir = ".";
    private String projectRef;
    private String userRef;
    private String sessionId;
    private boolean help = false;
    private boolean helpCommands = false;
    private String[] helpCommandsArgs = new String[0];
    private final List<String> precommands = new ArrayList<>();
    private final List<String> extensions = new ArrayList<>();
    private Boolean forceInteractive;

    public NaruCmdLineProcessor(NCmdLine args) {
        parse(args);
    }

    // ── Argument parser ────────────────────────────────────────────────────────

    private void parse(NCmdLine args) {
        args
                .matcher()
                .when("--tasks", "-t").asEntry(a -> precommands.add(a.value()))
                .when("--file", "-f").asEntry(a -> precommands.add("/source " + a.value()))
                .when("--interactive", "-i").asFlag(a -> forceInteractive = a.booleanValue())
                .when("--directory", "-d").asEntry(a -> workspaceDir = a.value())
                .when("--project").asEntry(a -> projectRef = a.value())
                .when("--user").asEntry(a -> userRef = a.value())
                .when("--session", "-s").asEntry(a -> sessionId = a.value())
                .when("--extension", "-e").asEntry(a -> extensions.add(a.value()))
                .when("--help", "-h").asFlag(a -> {
                    help = a.booleanValue();
                    while (args.hasNext()) {
                        NArg n = args.next().get();
                        if (n.image().equals("commands")) {
                            helpCommands = true;
                            helpCommandsArgs = args.toStringArray();
                        }
                        args.skipAll();
                    }
                })
                .withDefaults()
                .requireAll();
    }


    // ── Run ───────────────────────────────────────────────────────────────────

    /** The workspace root for this run: the directory holding the {@code .naru} store. */
    private NPath workspaceDirectory() {
        return NPath.of(workspaceDir);
    }

    public void run() {
        if (help) {
            if (helpCommands) {
                printHelpCommands(helpCommandsArgs);
            } else {
                printHelp();
            }
            return;
        }
        NaruWorkspace runner = createWorkspace(workspaceDirectory());
        boolean interactive = (forceInteractive == null ? precommands.isEmpty() : forceInteractive);
        if (interactive) {
            NaruSession session = applySessionCreationOptions(runner.newSession()
                    .statements(precommands.toArray(new String[0]))
                    .interactive()
                    .banner(true)
                    .richTerm(true)
                    .id(sessionId))
                    .build();
            printRestoredConversation(session);
            session.start().waitFor();
        } else {
            if (precommands.isEmpty()) {
                throw new NIllegalArgumentException(NMsg.ofC("no task specified"));
            }
            try {
                NaruSession session = applySessionCreationOptions(runner.newSession()
                        .statements(precommands.toArray(new String[0]))
                        .id(sessionId))
                        .build();
                printRestoredConversation(session);
                session.start().waitFor();
            } catch (NCancelException e) {
                // just exit
            }
        }
    }

    /**
     * {@code --project} and {@code --user} apply at session creation: the project seeds
     * the session directory and user when not otherwise stated, and a missing project
     * fails the build. Reopening an existing session keeps its stored values.
     */
    private NaruSessionBuilder applySessionCreationOptions(NaruSessionBuilder builder) {
        if (projectRef != null && !projectRef.isBlank()) {
            builder = builder.project(projectRef);
        }
        if (userRef != null && !userRef.isBlank()) {
            builder = builder.user(userRef);
        }
        return builder;
    }

    /**
     * Reprint the conversation a {@code --session} reopen hydrated, so the terminal does not open on
     * a blank screen over a session that has a history.
     * <p>
     * The items come from the engine's one expander, not from a second reading of the messages here:
     * reasoning, tool cards and progress lines land on the channels the live turn used, which is what
     * makes the same session read the same way in this terminal and in the desktop. A session with no
     * {@code --session} id has nothing stored, so nothing prints.
     */
    private void printRestoredConversation(NaruSession session) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        for (NaruTask t : session.conversationTasks()) {
            NaruTranscriptReplay.publishTo(session, t.id(), t.history());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Assembles the workspace for this run: every {@code --profile} is expanded to the
     * extensions it bundles and every {@code --extension} is loaded on top. When neither
     * is declared the builder itself falls back to the dev profile (if installed) and
     * otherwise to a bare core, so the same invocation yields a full harness or a minimal
     * agent depending on what is present.
     */
    private NaruWorkspace createWorkspace(NPath projectDirectory) {
        NaruWorkspaceBuilder builder = NaruWorkspaceBuilder.of();
        for (String extension : extensions) {
            builder.importExtension(NId.of(extension));
        }
        return builder.projectDirectory(projectDirectory).build();
    }

    private void printHelp() {
        NOut.println(
                NMsg.ofC("%s — Nuts AI Reasoning Unit",
                        NMsg.ofStyledPrimary1("naru"))
        );
        NOut.println(
                "Multi-model, tool-based AI agent (Ollama-local-first)\n\n" +
                        "Usage:\n" +
                        "  naru --task \"<description>\" [options]\n\n" +
                        "Options:\n" +
                        "  -t|--task <text>          task to run\n" +
                        "  -f|--file <text>          task file to run\n" +
                        "  -i|--interactive          force interactive mode\n" +
                        "  -d|--directory <path>     workspace folder holding the .naru store (default: .)\n" +
                        "  --project <id|name>       create the new session under that project:\n" +
                        "                            its folder and user seed the session (see /project)\n" +
                        "  --user <name>             opaque user label stored with the new session\n" +
                        "  -s|--session <uuid>     reopen a session saved in that project's store and\n" +
                        "                            reprint its conversation before the prompt\n" +
                        "  -p|--profile <name|id>    load a profile (a bundle of extensions);\n" +
                        "                            repeatable. bare names resolve under\n" +
                        "                            net.thevpc.naru.profiles:naru-profile-<name>\n" +
                        "  -e|--extension <id>       load a single extension (group:artifact[#version]);\n" +
                        "                            repeatable\n" +
                        "  --help, -h             Show this help\n\n" +
                        "Examples:\n" +
                        "  naru --task \"What files are in the project?\"\n" +
                        "  naru -t \"/model use qwen2.5-coder:7b\" -t \"Fix the bug in MyApp.java\" -d ./my-app\n" +
                        "  naru -t \"/model use qwen2.5-coder:7b\" -f \"myscript.naru\" -d ./my-app\n" +
                        "  naru --task \"Verify the output.png matches input.tson\" -d ./my-app\n" +
                        "  naru --project backend --task \"Add pagination to the list\"\n" +
                        "  naru --profile dev --task \"Explore the repo with the full dev harness\"\n" +
                        "  naru --extension net.thevpc.naru:naru-budget --task \"Show my spend\"\n"
        );
    }

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
        Nuts.require();
    }

    public void printHelpCommands(String[] helpCommandsArgs) {
        openStandaloneWorkspace();
        NPath helpWorkspaceDir = NPath.ofTempFolder("naru-helpgen-" + System.nanoTime());
        // built through the same profile/extension path as a real run, so the generated
        // manual lists whatever a configured naru would actually offer
        NaruWorkspace agent = createWorkspace(helpWorkspaceDir);
        NaruSession session = agent.newSession()
                .sessionDir(helpWorkspaceDir)
                .interaction(new NaruStreamInteraction(o -> {
                    // help renders through the directive result; session output is discarded
                }))
                .sessionListener(NOOP_LISTENER)
                .build();
        try {
            NaruTask task = session.newTask(NaruTaskSpec.of());
            NaruDirective help = session.registry().findDirective("help")
                    .orElseThrow(() -> new IllegalStateException("no /help directive registered"));
            String argument = helpCommandsArgs.length == 0 ? "--full" : String.join(" ", helpCommandsArgs);
            NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("help", argument, task);
            NaruStmtResult result = help.execute(ctx);
            Object value = result == null ? null : result.successValue();
            String out = value == null ? "" : String.valueOf(value);
            NOut.println(out);
            if (result != null && result.errorValue() != null) {
                throw new NExecutionException(NMsg.ofC("/help reported an error: %s", result.errorValue()), 1);
            }
            if (out.isBlank()) {
                throw new NExecutionException(NMsg.ofC("/help produced no output (args=%s)"
                        ,Arrays.toString(helpCommandsArgs)), 2);
            }
        } catch (Exception e) {
            throw new NExecutionException(NMsg.ofC("failed: %s" , e), 3);
        } finally {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }
}
