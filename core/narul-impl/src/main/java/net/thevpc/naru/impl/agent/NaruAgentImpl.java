package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.naru.impl.registry.NaruToolRegistryImpl;
import net.thevpc.naru.impl.registry.tools.DelegateModelTool;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.cmdline.*;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NAssert;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NCancelException;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The core agent loop.
 *
 * <ol>
 *   <li>Adds a system prompt + user task to the conversation history.</li>
 *   <li>Calls the model.</li>
 *   <li>If the model returns {@code tool_calls}: dispatches each call via
 *       {@link NaruToolRegistry}, appends the results, and loops.</li>
 *   <li>If the model returns plain text: that is the final answer.</li>
 *   <li>Stops after {@code maxSteps} iterations regardless.</li>
 * </ol>
 *
 * <p>This class is pure Java — no Nuts dependency — so it can be extracted
 * into a standalone library later.
 */
public class NaruAgentImpl implements NaruAgent {

    private final NaruModelProvider provider;
    private final NaruToolRegistry registry;
    private final NaruAgentConfig config;
    /**
     * Optional step listener for CLI progress printing.
     */
    private NLogger logger;
    private final String currentModel;

    public NaruAgentImpl(NaruAgentConfig config) {
        this(new NaruToolRegistryImpl(config)
                .registerDefaults(), config);
    }

    public NaruAgentImpl(NaruToolRegistry registry, NaruAgentConfig config) {
        this.registry = registry;
        this.config = config;
        this.logger = NLogger.STDOUT;
        this.currentModel = config.getModel();
        provider = registry.modelProviders().get(config.getProvider().toLowerCase());
        NAssert.requireNamedNonNull(provider, "provide " + config.getProvider().toLowerCase());
        List<String> availableModels = provider.listModels();
        registry.registerTool(new DelegateModelTool(provider, availableModels));
    }

    public String model() {
        return currentModel;
    }

    public NaruModelProvider provider() {
        return provider;
    }

    public NaruAgent logger(NLogger logger) {
        this.logger = logger;
        return this;
    }

    public NaruToolRegistry registry() {
        return registry;
    }
    // ── Public entry point ─────────────────────────────────────────────────────


    @Override
    public void run(String task, NPath pwd) {
        if (task == null) {
            NSystemTerminal.enableRichTerm();
            NIO.of().getSystemTerminal()
                    .setCommandAutoCompleteResolver(new NCmdLineAutoCompleteResolver() {
                        @Override
                        public List<NArgCandidate> resolveCandidates(NCmdLine cmdLine, Pos pos) {
                            List<NArgCandidate> candidates = new ArrayList<>();
                            String[] stringArray = cmdLine.toStringArray();
                            if (stringArray.length == 0 || stringArray.length == 1 && stringArray[0].isEmpty()) {
                                for (Map.Entry<String, NaruDirective> e : registry().directives().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList())) {
                                    candidates.add(new DefaultNArgCandidate(
                                            "/" + e.getKey(),
                                            "/" + e.getKey()
                                    ));
                                }
                            } else if (stringArray[0].startsWith("/")) {
                                if (pos.wordIndex() == 0) {
                                    for (Map.Entry<String, NaruDirective> e : registry().directives().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList())) {
                                        String value = "/" + e.getKey();
                                        if (value.startsWith(stringArray[0])) {
                                            candidates.add(new DefaultNArgCandidate(
                                                    value,
                                                    "/" + e.getKey()
                                            ));
                                        }
                                    }
                                }
                            }
                            return candidates;
                        }
                    })
//                    .setCommandHistory(
//                            NCmdLineHistory.of()
//                                    .setPath(appVarFolder.resolve("nsh-history.hist"))
//                    )
            ;

        }
        NaruAgentContext context = new NaruAgentContextImpl(this, pwd.toAbsolute());
        run(task, context);
    }

    /**
     * Run the agent with the given user task.
     *
     * @return the model's final text answer
     */
    public void run(String task, NaruAgentContext context) {
        NaruSessionContext sessionContext = new NaruSessionContextImpl(context, this);
        sessionContext.addHistory(NaruMessage.system(buildSystemPrompt(context)));
        NOut.resetLine();
        log(NaruLogMode.RAW, NMsg.ofC(
                "╭╮╷╭─╮╭─╮╷ ╷\n" +
                        "│╰┤├─┤├┬╯│ │ Nuts AI Reasoning Unit\n" +
                        "╵ ╵╵ ╵╵╰╴╰─╯ v%s", NVersion.of("0.8.9")));
        log(NaruLogMode.RAW, NMsg.ofC("%s Starting agent — model: %s",
                NMsg.ofStyledSeparator("🤖"),
                NMsg.ofStyledPrimary1(config.getModel())
        ));
        if (NBlankable.isBlank(task)) {
            sessionContext.pushOperation(NaruAgentOp.ofReadLine());
            sessionContext.setForever(true);
            while (sessionContext.hasMoreOps()) {
                runStep(sessionContext);
            }
        } else {
            log(NaruLogMode.TRACE, NMsg.ofC("📋 Task: %s", NMsg.ofStyledPrimary1(task)));
            sessionContext.addHistory(NaruMessage.user(task));
            sessionContext.setForever(false);
            sessionContext.pushOperation(NaruAgentOp.ofCallModel());
            while (sessionContext.hasMoreOps()) {
                runStep(sessionContext);
            }
        }
    }


    private void runStep(NaruSessionContext sessionContext) {
        NaruAgentOp op = sessionContext.popOperation();
        switch (op.type) {
            case READLINE: {
                String line = null;
                try {
                    line = NTerminal.of().readLine(NMsg.ofC("%s%s ", NMsg.ofStyledPrimary1("naru"), NMsg.ofStyledSeparator(">")));
                } catch (NCancelException e) {
                    // CTRL-C ?
                    sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                }
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) {
                    sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    break;
                }

                if (line.startsWith("/")) {
                    if (sessionContext.isForever()) {
                        sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    }
                    runDirective(line, sessionContext);
                    break;
                }

                NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
                if (sm.tryParseLine(line)) {
                    // Line successfully added to script
                    sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    break;
                }

                if (sessionContext.addHistory(line)) {
                    sessionContext.pushOperation(new NaruAgentOp(NaruAgentOp.Type.CALL_MODEL));
                } else {
                    sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                }
                break;
            }
            case CALL_MODEL: {
                log(NaruLogMode.PROGRESS, NMsg.ofC("%s Model: %s…",
                        NMsg.ofStyledPrimary8("\uD83E\uDDE0"),
                        NMsg.ofStyledPrimary1(this.currentModel)
                ));
                NaruResponse response;
                try {
                    response = provider.chat(this.currentModel, sessionContext.history(), registry.getDefinitions());
                } catch (Exception e) {
                    String err = "ERROR calling model: " + e.getMessage();
                    log(NaruLogMode.PROGRESS, NMsg.ofC("%s", err).asError());
                    if (sessionContext.isForever()) {
                        sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    }
                    return;
                }

                NaruMessage assistantMsg = response.getMessage();
                if (assistantMsg == null) {
                    log(NaruLogMode.DEBUG, NMsg.ofC("Model returned empty response."));
                    if (sessionContext.isForever()) {
                        sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    }
                    return;
                }
                sessionContext.addHistory(assistantMsg);
                // ── Case 1: model wants to call tools ─────────────────────────────
                if (assistantMsg.hasToolCalls()) {
                    List<NaruToolCall> toolCalls = assistantMsg.getToolCalls();
                    if (sessionContext.isForever()) {
                        sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    }
                    sessionContext.pushOperation(NaruAgentOp.ofCallModel());
                    for (int i = toolCalls.size() - 1; i >= 0; i--) {
                        sessionContext.pushOperation(NaruAgentOp.ofToolCall(toolCalls.get(i)));
                    }
                    if (!NBlankable.isBlank(assistantMsg.getContent())) {
                        log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
                    }
                    return;
                }
                log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
                sessionContext.setLastResult(assistantMsg);

                if (sessionContext.pc() != -1) {
                    NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
                    NaruScript currentScript = sm.getScript(sm.getCurrentScriptName());
                    Integer nextPc = currentScript.getLines().higherKey(sessionContext.pc());
                    if (nextPc != null) {
                        sessionContext.pc(nextPc);
                        sessionContext.pushOperation(new NaruAgentOp(NaruAgentOp.Type.EXECUTE_SCRIPT_LINE));
                    } else {
                        log(NaruLogMode.PROGRESS, NMsg.ofC("%s Script execution finished.",
                                NMsg.ofStyledSuccess("✅")
                        ));
                        sessionContext.pc(-1);
                        if (sessionContext.isForever()) {
                            sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                        }
                    }
                } else if (sessionContext.isForever()) {
                    sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                }
                break;
            }
            case TOOL_CALL: {
                NaruToolCall call = op.call;
                log(NaruLogMode.PROGRESS, NMsg.ofC("%s Tool: %s(%s)",
                                NMsg.ofStyledPrimary9("🔧"),
                                NMsg.ofStyledPrimary1(call.getName()), call.getArguments()
                        )
                );
                String result = registry.dispatch(call, sessionContext);
                log(NaruLogMode.PROGRESS, NMsg.ofC("  %s Result: %s",
                        NMsg.ofStyledPrimary6("📤"),
                        abbreviate(result, 300)));
                sessionContext.addHistory(NaruMessage.tool(call.getName(), call.getId(), result));
                break;
            }
            case EXECUTE_SCRIPT_LINE: {
                NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
                NaruScript currentScript = sm.getScript(sm.getCurrentScriptName());
                String lineText = currentScript.getLines().get(sessionContext.pc());

                if (lineText == null) {
                    log(NaruLogMode.PROGRESS, NMsg.ofC("Script execution finished."));
                    sessionContext.pc(-1);
                    if (sessionContext.isForever()) {
                        sessionContext.pushOperation(NaruAgentOp.ofReadLine());
                    }
                    break;
                }

                String prompt = "Execute line " + sessionContext.pc() + ": " + lineText;
                log(NaruLogMode.SCRIPT, NMsg.ofC(prompt));
                sessionContext.addHistory(NaruMessage.user(prompt));
                sessionContext.pushOperation(NaruAgentOp.ofCallModel());
                break;
            }
        }
    }

    private void runDirective(String line, NaruSessionContext sessionContext) {
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
        registry.dispatchSlash(cmd, line, sessionContext);
    }

    @Override
    public void invokeScript(NaruSessionContext sessionContext, String scriptName) {
        NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
        String previousContext = sm.getCurrentScriptName();
        sm.switchScript(scriptName);
        NaruScript script = sm.getScript(scriptName);

        if (script.isEmpty()) {
            log(NaruLogMode.TRACE, NMsg.ofC("Script '%s' is empty. Nothing to execute.", NMsg.ofStyledPrimary1(scriptName)));
            sm.switchScript(previousContext);
            if (sessionContext.isForever()) {
                sessionContext.pushOperation(NaruAgentOp.ofReadLine());
            }
            return;
        }

        String sysPrompt = "You are executing a script named '" + scriptName + "'.\n" +
                "Here is the full script for context:\n" +
                script.getFormattedText() + "\n" +
                "I will instruct you to execute one line at a time.";

        sessionContext.addHistory(NaruMessage.system(sysPrompt));

        Integer firstLine = script.getLines().firstKey();
        sessionContext.pc(firstLine);
        sessionContext.pushContext();
        sessionContext.pushOperation(new NaruAgentOp(NaruAgentOp.Type.EXECUTE_SCRIPT_LINE));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildSystemPrompt(NaruAgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are NARU (Nuts AI Reasoning Unit), an expert software engineering agent.\n");
        sb.append("You have access to tools that let you read/write files, run shell commands, ");
        sb.append("compile Maven projects, and inspect images using a vision model.\n\n");
        sb.append("Guidelines:\n");
        sb.append("- Always read the relevant files before modifying them.\n");
        sb.append("- After modifying Java files, always compile to check for errors.\n");
        sb.append("- Use inspect_image to verify that generated images match expectations.\n");
        sb.append("- Be concise in your final answer. Summarise what you changed and why.\n");

        if (context.getProjectDir() != null) {
            sb.append("\nProject directory: ").append(context.getProjectDir()).append('\n');
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
                logLines(message, 1, "\u258C", 3);
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
            case THINKING: {
                logLines(message, 3, "\u258C", 9);
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

    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
