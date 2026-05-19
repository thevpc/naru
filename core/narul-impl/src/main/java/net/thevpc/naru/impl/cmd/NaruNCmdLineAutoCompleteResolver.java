package net.thevpc.naru.impl.cmd;

import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.nuts.cmdline.DefaultNArgCandidate;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.thevpc.naru.api.agent.NaruSession;

public class NaruNCmdLineAutoCompleteResolver implements NCmdLineAutoCompleteResolver {
    private final NaruSession session;

    public NaruNCmdLineAutoCompleteResolver(NaruSession session) {
        this.session = session;
    }

    @Override
    public List<NArgCandidate> resolveCandidates(NCmdLine cmdLine, Pos pos) {
        List<NArgCandidate> candidates = new ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        
        if (stringArray.length == 0 || (stringArray.length == 1 && stringArray[0].isEmpty())) {
            // First word - show all directive commands
            for (Map.Entry<String, NaruDirective> e : session.registry().directives().entrySet().stream()
                    .sorted(Comparator.comparing(a -> a.getKey()))
                    .collect(Collectors.toList())) {
                candidates.add(new DefaultNArgCandidate(
                        "/" + e.getKey(),
                        "/" + e.getKey() + " - " + e.getValue().getDescription()
                ));
            }
        } else if (wordIndex == 0 && stringArray[0].startsWith("/")) {
            // Command completion - partial match for first command word
            String currentCommand = stringArray[0];
            for (Map.Entry<String, NaruDirective> e : session.registry().directives().entrySet().stream()
                    .sorted(Comparator.comparing(a -> a.getKey()))
                    .collect(Collectors.toList())) {
                String value = "/" + e.getKey();
                if (value.startsWith(currentCommand)) {
                    candidates.add(new DefaultNArgCandidate(
                            value,
                            value + " - " + e.getValue().getDescription()
                    ));
                }
            }
        } else if (wordIndex > 0 && stringArray[0].startsWith("/")) {
            // Argument completion for specific commands
            String commandName = stringArray[0].substring(1); // Remove the leading "/"
            NaruDirective directive = session.registry().directives().get(commandName);
            if (directive != null) {
                candidates.addAll(directive.resolveCandidates(cmdLine, pos, session));
            }
        }
        
        return candidates;
    }


}
