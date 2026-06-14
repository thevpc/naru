package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTexts;
import net.thevpc.nuts.util.NLiteral;

import java.util.*;
import java.util.stream.Collectors;

public class NaruHelpDirective extends AbstractDirective {
    private static final Map<String, HelpExample> EXAMPLES_REGISTRY_BY_NAME = new HashMap<>();
    private static final Map<Integer, HelpExample> EXAMPLES_REGISTRY_BY_NUM = new HashMap<>();

    static {
        addExample("01", "hello-world", "First contact: comments, directives, prompts (Beginner)");
        addExample("02", "variables", "`/set`, expressions, `_`, scoping (Beginner)");
        addExample("03", "control-flow", "`if/elseif/else`, `while`, `for` (Beginner)");
        addExample("04", "routines", "Defining, editing, renumbering routines (Beginner)");
        addExample("04", "routines", "Defining, editing, renumbering routines (Beginner)");
        addExample("05", "subroutines", "`call` vs `source` vs `start`, `/return` (Intermediate)");
        addExample("05", "subroutines", "`call` vs `source` vs `start`, `/return` (Intermediate)");
        addExample("06", "labels-goto", "Labels and `/goto` (Intermediate)");
        addExample("07", "ai-prompting", "Models, modes, skills, context, `/go` (Intermediate)");
        addExample("08", "file-operations", "`/file`, `/cat`, `/ls`, `/cd` (Intermediate)");
        addExample("09", "shell-integration", "`/sh`, `/sys` (Intermediate)");
        addExample("09", "shell-integration", "`/sh`, `/sys` (Intermediate)");
        addExample("10", "sessions", "Save, restore, session lifecycle (Intermediate)");
        addExample("11", "tasks", "Background tasks, hold/unhold, kill (Advanced)");
        addExample("12", "events", "`/fire`, `/on`, `/wait`, event bus (Advanced)");
        addExample("13", "multi-agent", "Multi-task agent pipeline (Advanced)");
        addExample("14", "code-review-pipeline", "Real-world code review workflow (Advanced)");
    }

    private static void addExample(String index, String name, String description) {
        HelpExample e = new HelpExample(index, name, description, "net/thevpc/naru/examples/" + index + "-" + name + ".naru");
        EXAMPLES_REGISTRY_BY_NAME.put(name.toLowerCase(), e);
        EXAMPLES_REGISTRY_BY_NUM.put(Integer.valueOf(index), e);

    }

    public NaruHelpDirective() {
        super("help", "help", "Show help framework documentation", "?");
    }

    private NMsg formatDirective(String name) {
        return NMsg.ofC("%s%s", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name));
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        String argument = context.argument();
        NCmdLine cmdLine = NCmdLine.parse(argument).get();

        class Options {
            boolean full;
            boolean syntax;
            boolean examplesList;
            String targetedExample;
            final List<String> args = new ArrayList<>();
        }

        Options o = new Options();
        if (!cmdLine.isEmpty()) {
            cmdLine.matcher()
                    .with("--full", "-f").matchFlag(a -> o.full = a.booleanValue())
                    .with("--syntax", "-s").matchFlag(a -> o.syntax = a.booleanValue())
                    .with("--examples", "-E").matchFlag(a -> o.examplesList = a.booleanValue())
                    .with("--example", "-e").matchEntry(a -> o.targetedExample = a.stringValue())
                    .withNonOption().matchAny(a -> o.args.add(a.image()))
                    .requireAll();
        }

        class HelpWriter {
            void log(NMsg s) {
                task.log(NaruLogMode.AGENT_RESPONSE, s);
            }

            void printGlobalSyntax() {
                log(NMsg.ofC("You can interact with %s using four primary syntactic forms:", NMsg.ofStyledPrimary1("Naru")));
                log(NMsg.ofC("  %s Directives (processed by Naru runtime engine)", NMsg.ofStyledString("-")));
                log(NMsg.ofC("      ex: %s %s", formatDirective("print"), NMsg.ofStyledString("'verifying workspace configuration...'")));
                log(NMsg.ofC("  %s Comments (completely ignored by parser)", NMsg.ofStyledString("-")));
                log(NMsg.ofC("      ex: %s", NMsg.ofStyledComments("# track baseline latency evaluations")));
                log(NMsg.ofC("  %s Edit Routines (append or patch persistent task memory sequences)", NMsg.ofStyledString("-")));
                log(NMsg.ofC("      ex: %s %s", NMsg.ofStyledNumber("10"), NMsg.ofStyledString("evaluate native memory allocations")));
                log(NMsg.ofC("          %s %s %s", NMsg.ofStyledNumber("+"), formatDirective("print"), NMsg.ofStyledString("'routine appended'")));
                log(NMsg.ofC("  %s AI Prompts (unhandled strings forwarded directly to target execution LLM)", NMsg.ofStyledString("-")));
                log(NMsg.ofC("      ex: %s", NMsg.ofStyledString("optimize this database engine profile configuration")));
            }

            void printControlFlowHelp(String type) {
                log(NMsg.ofC("--- %s Syntax Block ---", NMsg.ofStyledPrimary5("Built-in Control Flow")));
                switch (type) {
                    case "conditional":
                        log(NMsg.ofC("Structural syntax layout for execution branching:"));
                        log(NMsg.ofC("  %s <expression>", formatDirective("if")));
                        log(NMsg.ofC("    ... internal routine blocks ..."));
                        log(NMsg.ofC("  %s <expression> (Optional)", formatDirective("elseif")));
                        log(NMsg.ofC("    ... alternate path execution ..."));
                        log(NMsg.ofC("  %s (Optional)", formatDirective("else")));
                        log(NMsg.ofC("    ... fallback routine statements ..."));
                        log(NMsg.ofC("  %s", formatDirective("end")));
                        break;
                    case "while":
                        log(NMsg.ofC("Durable validation testing loop structure:"));
                        log(NMsg.ofC("  %s <expression>", formatDirective("while")));
                        log(NMsg.ofC("    ... parallel statement evaluation routines ..."));
                        log(NMsg.ofC("  %s", formatDirective("end")));
                        break;
                    case "for":
                        log(NMsg.ofC("Indexed sequence iterative block generation syntax:"));
                        log(NMsg.ofC("  %s <var>:<from>..<to>[:<step>]", formatDirective("for")));
                        log(NMsg.ofC("    ... processing scoped variable indices ..."));
                        log(NMsg.ofC("  %s", formatDirective("end")));
                        break;
                }
            }
        }

        HelpWriter w = new HelpWriter();

        // Target Explicit Example Request Evaluation
        if (o.examplesList) {
            w.log(NMsg.ofC("--- Available Script Examples ---"));
            for (String s : new TreeSet<>(EXAMPLES_REGISTRY_BY_NAME.keySet())) {
                HelpExample e = EXAMPLES_REGISTRY_BY_NAME.get(s);
                w.log(NMsg.ofC(" %s %s : %s", NMsg.ofStyledNumber(e.number), NMsg.ofStyledPrimary5(s), e.description));
            }
            return;
        }
        if (o.targetedExample != null) {
            HelpExample ex = EXAMPLES_REGISTRY_BY_NAME.get(o.targetedExample.toLowerCase().trim());
            if (ex == null) {
                StringBuilder sb = new StringBuilder(o.targetedExample);
                while (sb.length() > 0 && sb.charAt(0) == '0') {
                    sb.deleteCharAt(0);
                }
                if (sb.length() > 0) {
                    Integer asInt = NLiteral.of(o.targetedExample).asInt().orNull();
                    if (asInt != null) {
                        ex = EXAMPLES_REGISTRY_BY_NUM.get(asInt);
                    }
                }
            }
            if (ex != null) {
                w.log(NMsg.ofC("--- Example Script Matrix: %s %s ---", NMsg.ofStyledNumber(ex.number), NMsg.ofStyledPrimary5(ex.name)));
                w.log(NMsg.ofC("Description: %s", NMsg.ofStyledPale(ex.description)));
                w.log(NMsg.ofC(""));
                // Log the syntax-highlighted block directly
                w.log(NMsg.ofC("%s",ex.getHighlightedCode()));
                return;
            } else {
                w.log(NMsg.ofC("Unknown help example name target identifier: %s", NMsg.ofStyledError(o.targetedExample)));
                w.log(NMsg.ofC("Type %s to browse available documentation examples.", NMsg.ofStyledString("/help --examples")));
            }
            return;
        }

        // Scenario 1: Specific subcommands target evaluation (e.g. /help model set)
        if (!o.args.isEmpty()) {
            String primaryQuery = o.args.get(0).toLowerCase();

            // Intercept Internal Engine Control Flow Syntax Bundles
            if (Arrays.asList("if", "elseif", "else", "end").contains(primaryQuery)) {
                w.printControlFlowHelp("conditional");
                return;
            }
            if (Objects.equals("while", primaryQuery)) {
                w.printControlFlowHelp("while");
                return;
            }
            if (Objects.equals("for", primaryQuery)) {
                w.printControlFlowHelp("for");
                return;
            }

            // Route execution query to target plugin infrastructure
            NaruDirective delegatedDirective = context.task().session().registry().directives().get(primaryQuery);
            if (delegatedDirective != null) {
                // Forward along any deep parameters down stream (e.g. "help set" or "help")
                String nestedArguments = o.args.size() > 1
                        ? "help " + String.join(" ", o.args.subList(1, o.args.size()))
                        : "help";

                delegatedDirective.execute(new NaruDirectiveCallContextImpl(
                        delegatedDirective.name(),
                        nestedArguments,
                        task
                ));
                return;
            }

            w.log(NMsg.ofC("Unknown engine component or registered directive: %s", NMsg.ofStyledError(primaryQuery)));
            return;
        }

        // Scenario 2: Isolated Syntax Request Flag
        if (o.syntax) {
            w.printGlobalSyntax();
            return;
        }

        // Scenario 3: Standard Dashboard Index Overview or Exhaustive Manual (--full)
        w.printGlobalSyntax();
        w.log(NMsg.ofC(""));
        w.log(NMsg.ofC("Available Native Core Directives:"));
        w.log(NMsg.ofC("  %s syntax blocks (use %s, %s, or %s for structural scopes)",
                NMsg.ofStyledPrimary5("Control Flow"), formatDirective("if"), formatDirective("while"), formatDirective("for")));
        w.log(NMsg.ofC("  %s: %s <label>", formatDirective("goto"), NMsg.ofStyledPale("Jumps processing to target label identifier")));
        w.log(NMsg.ofC("  %s: %s [<expr>]", formatDirective("return"), NMsg.ofStyledPale("Gracefully breaks execution frame runtime path")));

        Map<String, List<NaruDirective>> groups = context.task().session().registry().directives().values().stream()
                .collect(Collectors.groupingBy(NaruDirective::group));

        for (String groupName : new TreeSet<>(groups.keySet())) {
            if (groupName.equals("help")) continue;
            w.log(NMsg.ofC(""));
            w.log(NMsg.ofC("  %s Directives:", NMsg.ofStyledPrimary5(groupName)));

            List<NaruDirective> sortedDirectives = groups.get(groupName).stream()
                    .sorted(Comparator.comparing(NaruDirective::name))
                    .collect(Collectors.toList());

            for (NaruDirective d : sortedDirectives) {
                if (d.name().equals("help")) continue;

                w.log(NMsg.ofC("    %s : %s", formatDirective(d.name()), d.getDescription()));

                // If --full is requested, deep dump live documentation inline
                if (o.full) {
                    w.log(NMsg.ofC("    | Detailed Specification :"));
                    d.execute(new NaruDirectiveCallContextImpl(d.name(), "help", task));
                    w.log(NMsg.ofC("    -----------------------------------------"));
                }
            }
        }

        w.log(NMsg.ofC(""));
        w.log(NMsg.ofC("For targeted sub-command parameter layouts, type: %s %s [%s]", NaruUtils.formatDirective("help"), NMsg.ofStyledPlaceholder("<directive_name>"), NMsg.ofStyledPlaceholder("<subcommand>")));
        w.log(NMsg.ofC("For detailed help, type: %s %s",NaruUtils.formatDirective("help"), NMsg.ofStyledOption("--full") ));
        w.log(NMsg.ofC("For source examples, type: %s %s",NaruUtils.formatDirective("help") , NMsg.ofStyledOption("--examples") ));
        w.log(NMsg.ofC("For source indexed example, type: %s %s %s",NaruUtils.formatDirective("help"), NMsg.ofStyledOption("-e") , NMsg.ofStyledNumber("<number>") ));
        w.log(NMsg.ofC("To safely close out active environment processing stream session: %s", formatDirective("exit")));
    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new ArrayList<>();
        String[] args = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < args.length ? args[wordIndex] : "";

        // Flags completion
        if (currentArg.startsWith("-")) {
            addCandidates(candidates, currentArg, "--full");
            addCandidates(candidates, currentArg, "--syntax");
            return candidates;
        }

        // Complete directive names on first non-option field location
        if (wordIndex == 1) {
            // Include core flow bundle keywords
            for (String coreKeyword : Arrays.asList("if", "while", "for", "else", "elseif", "end", "goto", "return")) {
                addCandidates(candidates, currentArg, coreKeyword);
            }
            // Include dynamically configured application registry components
            for (String dName : session.registry().directives().keySet()) {
                addCandidates(candidates, currentArg, dName);
            }
        }
        return candidates;
    }

    private record HelpExample(String number, String name, String description, String resourcePath) {

        public NText getHighlightedCode() {
            try {
                // Fetch the whole script content natively from the classpath
                String content = NPath.of("classpath:" + resourcePath).readString();
                if (content == null || content.trim().isEmpty()) {
                    return NTexts.of().ofPlain("# (Empty example file)");
                }
                // Use the framework's token highlighting stream for Naru syntax
                return NTexts.of().ofCode("naru", content);
            } catch (Exception e) {
                return NTexts.of().of(NMsg.ofC("# Error: Could not read example resource from %s", resourcePath));
            }
        }
    }
}