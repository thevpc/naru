package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.impl.budget.NaruMeteringServiceImpl;
import net.thevpc.naru.impl.cmd.NaruTerminalFormatter;
import net.thevpc.naru.impl.cmd.NaruNCmdLineAutoCompleteResolver;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.naru.impl.stmt.NaruDefRoutineLineStmt;
import net.thevpc.naru.impl.stmt.NaruDirectiveCallStmt;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The core agent loop.
 *
 * <ol>
 *   <li>Adds a system prompt + user task to the conversation history.</li>
 *   <li>Calls the model.</li>
 *   <li>If the model returns {@code tool_calls}: dispatches each call via
 *       {@link NaruRegistry}, appends the results, and loops.</li>
 *   <li>If the model returns plain text: that is the final answer.</li>
 *   <li>Stops after {@code maxSteps} iterations regardless.</li>
 * </ol>
 *
 * <p>This class is pure Java — no Nuts dependency — so it can be extracted
 * into a standalone library later.
 */
public class NaruAgentImpl implements NaruAgent {

    private final NaruRegistry registry;
    private final NaruMeteringServiceImpl meteringService = new NaruMeteringServiceImpl();
    /**
     * Optional step listener for CLI progress printing.
     */
    private NLogger logger;
    private NPath projectDirectory;
    private StoredStringMap<NaruModelConfig> modelAliases;
    private NaruProjectEnv projectEnv;

    public NaruAgentImpl() {
        this(new NaruRegistryImpl()
                .registerDefaults());
    }

    public NaruAgentImpl(NaruRegistry registry) {
        this.registry = registry;
        this.logger = NLogger.STDOUT;
    }

    @Override
    public NPath getProjectDirectory() {
        return projectDirectory;
    }

    @Override
    public NaruAgent setProjectDirectory(NPath projectDirectory) {
        this.projectDirectory = projectDirectory;
        modelAliases = new StoredStringMap<>(projectDirectory.resolve(".naru/model/aliases.tson"), NaruModelConfig.class)
                .setSerializer(x -> x.toElement())
                .setDeserializer(x -> NaruModelConfig.of(x).get())
        ;
        projectEnv = new NaruProjectEnv(
                projectDirectory.resolve(".naru/config/env.tson"),
                projectDirectory.resolve(".naru/local/config/env.tson")
        );
        return this;
    }

    public NaruProjectEnv env() {
        return projectEnv;
    }

    public StoredStringMap<NaruModelConfig> getModelAliases() {
        return modelAliases;
    }

    public NaruAgent logger(NLogger logger) {
        this.logger = logger;
        return this;
    }

    public NaruRegistry registry() {
        return registry;
    }
    // ── Public entry point ─────────────────────────────────────────────────────


    public void runInteractive(PreCommand... preCommands) {
        NPath pwd = projectDirectory;
        if (pwd == null) {
            pwd = NPath.ofUserDirectory();
        }
        NaruSession session = new NaruSessionImpl(this, pwd.toAbsolute(), meteringService);
        enableRichTerm(session);
        // 1. foundation — static, immutable
        session.addHistory(NaruMessage.system(buildSystemPrompt(session)));
        NOut.resetLine();
        log(NaruLogMode.RAW, NMsg.ofC(
                "╭╮╷╭─╮╭─╮╷ ╷\n" +
                        "│╰┤├─┤├┬╯│ │ Nuts AI Reasoning Unit\n" +
                        "╵ ╵╵ ╵╵╰╴╰─╯ v%s", NVersion.of("0.8.9")));

        // 2. imperative init — mutates session state on top of foundation
        session.prepareProject();

        for (PreCommand preCommand : preCommands) {
            if (preCommand.isFile()) {
                session.addStatements(parseFile(NPath.of(preCommand.command())).get().toArray(new NaruStatement[0]));
            } else {
                NaruStatement p = parseStatement(preCommand.command()).orNull();
                if(p!=null) {
                    session.addStatements(p);
                }
            }
        }

        // 3. runtime
        session.runOrReadline();
//        run is equivalent to, if you need to run step by step
//        while (session.hasMoreStatements()) {
//            invokeStep(session);
//        }
    }

    private void enableRichTerm(NaruSession session) {
        NSystemTerminal.enableRichTerm();
        NIO.of().systemTerminal()
                .commandAutoCompleteResolver(new NaruNCmdLineAutoCompleteResolver(session))
                .commandHighlighter(new NaruTerminalFormatter(this))
        ;
    }

    @Override
    public void runTasks(PreCommand... preCommands) {
        if(preCommands.length==0) {
            return;
        }
        NPath pwd = projectDirectory;
        if (pwd == null) {
            pwd = NPath.ofUserDirectory();
        }
        NaruSession session = new NaruSessionImpl(this, pwd.toAbsolute(), meteringService);


        session.addHistory(NaruMessage.system(buildSystemPrompt(session)));
        session.prepareProject();

        for (PreCommand preCommand : preCommands) {
            if (preCommand.isFile()) {
                NPath p = NPath.of(preCommand.command());
                if(p.exists() && p.isFile()) {
                    session.addStatements(parseFile(p).get().toArray(new NaruStatement[0]));
                }else{
                    session.log(NaruLogMode.TRACE, NMsg.ofC("Skipping file %s: does not exist", p));
                }
            } else {
                NaruStatement p = parseStatement(preCommand.command()).orNull();
                if(p!=null) {
                    session.addStatements(p);
                }
            }
        }
        session.run();
    }


    public void invokeStep(NaruSession session) {
        NaruStatement op = session.popStatement();
        op.execAndAdvance(session);
    }

    public NOptional<List<NaruStatement>> parseFile(NPath path) {
        List<NaruStatement> list = new ArrayList<>();
        if (path.isFile()) {
            for (String line : path.lines().toList()) {
                NOptional<NaruStatement> li = parseStatement(line);
                if (li.isPresent()) {
                    list.add(li.get());
                }
            }
        }
        return NOptional.of(list);
    }

    public NOptional<NaruStatement> parseStatement(String line) {
        if (NBlankable.isBlank(line)) {
            return NOptional.ofNamedEmpty("statement");
        }
        line = line.trim();
        if (line.startsWith("#")) {
            return NOptional.ofNamedEmpty("statement");
        }
        if (line.startsWith("/")) {
            return NOptional.of(new NaruDirectiveCallStmt(line));
        }
        Pattern LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");
        Matcher m = LINE_PATTERN.matcher(line);
        if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            String content = m.group(2) != null ? m.group(2).trim() : "";
            return NOptional.of(new NaruDefRoutineLineStmt(num, content));
        }
        return NOptional.of(NaruStatementHelper.ofModelCall(line));
    }

//    private void handleCallDirective(String raw, NaruSession ctx, NaruRoutine routine) {
//        // Parse: /call subName arg1 arg2 (simple split; upgrade to tokenizer later)
//        String[] parts = raw.substring(6).trim().split("\\s+");
//        String subName = parts[0];
//        List<String> args = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));
//
//        SubroutineDef sub = routine.getSubroutines().get(subName);
//        if (sub == null) {
//            log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error : Unknown subroutine: %s", subName).asError());
//            advancePcOrEnd(ctx, routine);
//            return;
//        }
//        if (sub.params().size() != args.size()) {
//            log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error : Arg mismatch for /call %s: expected %d, got %d",
//                    subName, sub.params().size(), args.size()).asError());
//            advancePcOrEnd(ctx, routine);
//            return;
//        }
//
//        // Save return point: next line after /call
//        Integer nextLine = routine.getLinesSet().higherKey(ctx.pc());
//
//        // Push new context frame with returnPc + params
//        ctx.pushContext(sub.startLine(), nextLine); // Adds new RunContext at index 0
//
//        // Bind params to new frame
//        for (int i = 0; i < sub.params().size(); i++) {
//            ctx.getTopContext().bindParam(sub.params().get(i), args.get(i));
//        }
//
//        // Continue execution at subroutine start
//        ctx.pushStatement(NaruStatementHelper.ofExecRoutineLine());
//    }

//    private void advancePcOrEnd(NaruSession ctx, NaruRoutine routine) {
//        Integer next = routine.getLinesSet().higherKey(ctx.pc());
//        if (next != null) {
//            ctx.pc(next);
//            ctx.pushStatement(NaruStatementHelper.ofExecRoutineLine());
//        } else {
//            log(NaruLogMode.PROGRESS, NMsg.ofC("Routine execution finished."));
//            ctx.pc(-1);
////            if (ctx.isForever()) {
////                ctx.pushStatement(NaruStatementHelper.ofReadLine());
////            }
//        }
//    }

//    private void handleReturnDirective(NaruSession ctx) {
//        RunContext current = ctx.getTopContext();
//        Integer resumePc = current.returnPc();
//
//        // Pop the subroutine frame
//        ctx.popStatement(); // Removes index 0 RunContext
//
//        if (resumePc != null) {
//            ctx.pc(resumePc);
//            ctx.pushStatement(NaruStatementHelper.ofExecRoutineLine());
//        } else {
//            // No return point → end of routine
//            log(NaruLogMode.PROGRESS, NMsg.ofC("Subroutine returned to end of routine."));
////            if (ctx.isForever()) {
////                ctx.pushStatement(NaruStatementHelper.ofReadLine());
////            }
//        }
//    }

    public void invokeDirective(String line, NaruSession session) {
        if (line.startsWith("/")) {
            line = line.substring(1).trim();
        } else {
            log(NaruLogMode.TRACE, NMsg.ofC("Unknown directive : %s", line));
            return;
        }
        int idx = line.indexOf(' ');
        String cmd = line;
        if (idx != -1) {
            cmd = line.substring(0, idx);
            line = line.substring(idx + 1).trim();
        } else {
            line = "";
        }
        registry.dispatchSlash(cmd, line, session);
    }

    @Override
    public void invokeRoutine(NaruSession session, String routineName) {
        NaruRoutineManager sm = session.routineManager();
//        String previousContext = sm.getCurrentRoutineName();
//        sm.switchRoutine(routineName);
        NaruRoutine script = sm.getRoutine(routineName);

        if (script.isEmpty()) {
            log(NaruLogMode.TRACE, NMsg.ofC("Script '%s' is empty. Nothing to execute.", NMsg.ofStyledPrimary1(routineName)));
//            sm.switchRoutine(previousContext);
//            if (session.isForever()) {
//                session.pushStatement(NaruStatementHelper.ofReadLine());
//            }
            return;
        }

        String sysPrompt = "You are executing a script named '" + routineName + "'.\n" +
                "Here is the full script for context:\n" +
                script.getFormattedText() + "\n" +
                "I will instruct you to execute one line at a time.";

        session.addHistory(NaruMessage.system(sysPrompt));

        Integer firstLine = script.getLinesSet().firstKey();
        session.pushContext(firstLine, null,routineName);
        session.addStatement(parseStatement(script.getLine(firstLine)).get());
        session.runStep();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildSystemPrompt(NaruSession context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are NARU (Nuts AI Reasoning Unit), an expert software engineering agent.\n");
        sb.append("You have access to tools that let you read/write files, run shell commands, ");
        sb.append("compile Maven projects, and inspect images using a vision model.\n\n");
        sb.append("Guidelines:\n");
        sb.append("- Always read the relevant files before modifying them.\n");
        sb.append("- After modifying Java files, always compile to check for errors.\n");
        sb.append("- Use inspect_image to verify that generated images match expectations.\n");
        sb.append("- Be concise in your final answer. Summarise what you changed and why.\n");

        if (context.projectDir() != null) {
            sb.append("\nProject directory: ").append(context.projectDir()).append('\n');
        }
        if (context.getExtraContext() != null) {
            sb.append("\nAdditional context:\n").append(context.getExtraContext()).append('\n');
        }
        if (!registry.isEmpty()) {
            sb.append("\nAvailable tools: ").append(registry.names()).append('\n');
        }
        return sb.toString();
    }


    @Override
    public void log(NaruLogMode mode, NMsg message) {
        //if (config.isVerbose() && logger != null) {
        switch (mode) {
            case RAW: {
                logger.log(message);
                break;
            }
            case MODEL_RESPONSE: {
                for (NText line : NaruTerminalFormatter.formatOutputLines(message.toString(), NText.ofStyled("  \u258C", NTextStyle.primary3()))) {
                    logger.log(NMsg.ofC("%s", line));
                }
                break;
            }
            case MODEL_THINKING: {
                for (NText line : NaruTerminalFormatter.formatOutputLines(message.toString(), NText.ofStyled("  \u258C", NTextStyle.primary9()))) {
                    logger.log(NMsg.ofC("%s", line));
                }
                break;
            }
            case AGENT_RESPONSE: {
                logLines(message, 1, "\u258C", 4);
                break;
            }
            case SCRIPT: {
                logLines(message, 2, "▶️", 5);
                break;
            }
            case TRACE: {
                logLines(message, 2, "\u258C", 6);
                break;
            }
            case PROGRESS: {
                logLines(message, 2, "\u258C", 7);
                break;
            }
            case DEBUG: {
                logLines(message, 2, "\u258C", 8);
                break;
            }
            default: {
                logger.log(message);
            }
        }
        //}
    }

    private void logLines(NMsg message, int indent, String prefix, int style) {
        List<NText> all = NText.of(message).split("\n", false);
        String spaces = NStringUtils.repeat(" ", indent * 2);
        for (NText o : all) {
            logger.log((NMsg.ofC("%s%s %s", spaces, NMsg.ofStyled(prefix, NTextStyle.primary(style)), o)));
        }
    }

}
