package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.List;

public class HelpDirective extends AbstractDirective {
    private final NaruRegistryImpl naruToolRegistry;

    public HelpDirective(NaruRegistryImpl naruToolRegistry) {
        super("help", "show help","?");
        this.naruToolRegistry = naruToolRegistry;
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Available directives: "));
        for (NaruDirective value : naruToolRegistry.directives().values()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s : %s",
                    NMsg.ofStyledPrimary1(value.getName()),
                    value.getDescription()
            ));
        }
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
