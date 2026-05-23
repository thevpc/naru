package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.*;
import java.util.stream.Collectors;

public class HelpDirective extends AbstractDirective {

    public HelpDirective() {
        super("help","help", "show help", "?");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("You can interact with %s by issuing a command that can be on one the three forms :", NMsg.ofStyledPrimary1("Naru")));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- Directives (start by %s like '%s%s')", NMsg.ofStyledString("'/'"), NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary8("help")));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- Routine lines (start by numbers like '%s %s')", NMsg.ofStyledNumber("10"), NMsg.ofStyledNumber("could you check the file readme.md")));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("- Prompt (any other string, like in %s)", NMsg.ofStyledItalic("'could you check the file readme.md'")));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Available directives are : "));
        List<NaruDirective> values = new ArrayList<>(context.session().registry().directives().values());
        Map<String, List<NaruDirective>> collected = values.stream().collect(Collectors.groupingBy(x -> x.group()));
        for (String groupName : new TreeSet<>(collected.keySet())) {
            List<NaruDirective> naruDirectives = collected.get(groupName);
            naruDirectives.sort((o1, o2) -> o1.name().compareTo(o2.name()));
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s directives:",
                    NMsg.ofStyledPrimary5(groupName)
            ));
            for (NaruDirective value : naruDirectives) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    %s%s : %s",
                        NMsg.ofStyledSeparator("/"),
                        NMsg.ofStyledPrimary1(value.name()),
                        value.getDescription()
                ));
            }
        }
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("-------------"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Type '/<directivename> help' to get help for a directive."));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Type '/exit' to quit the interpreter"));
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
