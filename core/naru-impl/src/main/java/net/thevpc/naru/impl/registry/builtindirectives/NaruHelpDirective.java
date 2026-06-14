package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.*;
import java.util.stream.Collectors;

public class NaruHelpDirective extends AbstractDirective {

    public NaruHelpDirective() {
        super("help", "help", "show help", "?");
    }
    private NMsg formatDirective(String name) {
        return NMsg.ofC("%s%s",NMsg.ofStyledSeparator("/"),NMsg.ofStyledPrimary1(name));
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("You can interact with %s by issuing a command that can be on one the three forms :", NMsg.ofStyledPrimary1("Naru")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- Directives (start by %s) processed by naru", NMsg.ofStyledString("'/'")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    ex:"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("       %s %s",formatDirective("print"), NMsg.ofStyledString("'could you check the file readme.md'")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- Comments (start by %s), ignored totally", NMsg.ofStyledNumber("#")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    ex:"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("       %s", NMsg.ofStyledComments("# this is a comment...")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- Edit routines (start by %s or '%s') to patch or append", NMsg.ofStyledNumber("<number>"), NMsg.ofStyledNumber("+")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    ex:"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("       %s %s", NMsg.ofStyledNumber("10"), NMsg.ofStyledString("could you check the file readme.md")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("       %s %s %s", NMsg.ofStyledNumber("+"), NMsg.ofStyledPrimary1("print"), NMsg.ofStyledString("'hello'")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- AI Prompt (any other string) to send to AI model"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    ex:"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("       %s", NMsg.ofStyledString("could you check the file readme.md")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Available directives are : "));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s directives:",
                NMsg.ofStyledPrimary5("Control Flow")
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    conditional branching :"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s <expression>",formatDirective("if")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        ..."));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s <expression>",formatDirective("elseif")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        ..."));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s",formatDirective("else")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        ..."));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s",formatDirective("end")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    while loop :"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s <expression>",formatDirective("while")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        ..."));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s",formatDirective("end")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    for loop :"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s <var>:<from>..<to>[:<step>]",formatDirective("for")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        ..."));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s",formatDirective("end")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    branching :"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s:",formatDirective("<label-name>")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s <label-name>",formatDirective("goto")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("      %s [<expression>] , to return from current routine",formatDirective("return")));
        List<NaruDirective> values = new ArrayList<>(context.task().session().registry().directives().values());
        Map<String, List<NaruDirective>> collected = values.stream().collect(Collectors.groupingBy(x -> x.group()));
        for (String groupName : new TreeSet<>(collected.keySet())) {
            List<NaruDirective> naruDirectives = collected.get(groupName);
            naruDirectives.sort((o1, o2) -> o1.name().compareTo(o2.name()));
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s directives:",
                    NMsg.ofStyledPrimary5(groupName)
            ));
            for (NaruDirective value : naruDirectives) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    %s : %s",
                                formatDirective(value.name()),
                        value.getDescription()
                ));
            }
        }
        if (true) {
            for (NaruDirective value : values) {
                if(!value.name().equals("help")) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("-------------"));
                    value.execute(new NaruDirectiveCallContextImpl(value.name(), "help", task));
                }
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("-------------"));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Type '/<directivename> help' to get help for a directive."));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Type '/exit' to quit the interpreter"));
    }


    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new java.util.ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";

        if (wordIndex == 1) {
            for (String d : session.registry().directives().keySet()) {
                addCandidates(candidates, currentArg, d);
            }
        }
        return candidates;
    }
}
