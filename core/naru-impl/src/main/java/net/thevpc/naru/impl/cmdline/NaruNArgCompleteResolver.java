package net.thevpc.naru.impl.cmdline;

import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.cmdline.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.thevpc.naru.api.agent.NaruSession;

public class NaruNArgCompleteResolver implements NArgCompleteResolver {
    private final NaruSession session;

    public NaruNArgCompleteResolver(NaruSession session) {
        this.session = session;
    }

    @Override
    public NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos) {
        List<NArgCompleteCandidate> candidates = new ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        ArrayList<NArgCompleteFlag> flags = new ArrayList<>();

        if (stringArray.length == 0 || (stringArray.length == 1 && stringArray[0].isEmpty())) {
            // First word - show all directive commands
            for (Map.Entry<String, NaruDirective> e : session.registry().directives().entrySet().stream()
                    .sorted(Comparator.comparing(a -> a.getKey()))
                    .collect(Collectors.toList())) {
                candidates.add(NArgCompleteCandidate.of(
                        "/" + e.getKey(),
                        "/" + e.getKey() + " - " + e.getValue().getDescription()
                ));
            }
            addKeywords(candidates, "");
        } else if (wordIndex == 0 && stringArray[0].startsWith("/")) {
            // Command completion - partial match for first command word
            String currentCommand = stringArray[0];
            for (Map.Entry<String, NaruDirective> e : session.registry().directives().entrySet().stream()
                    .sorted(Comparator.comparing(a -> a.getKey()))
                    .collect(Collectors.toList())) {
                String value = "/" + e.getKey();
                if (value.startsWith(currentCommand)) {
                    candidates.add(NArgCompleteCandidate.of(
                            value,
                            value + " - " + e.getValue().getDescription()
                    ));
                }
            }
            addKeywords(candidates, currentCommand);
        } else if (wordIndex > 0 && stringArray[0].startsWith("/")) {
            // Argument completion for specific commands
            String commandName = stringArray[0].substring(1); // Remove the leading "/"
            NaruDirective directive = session.registry().findDirective(commandName).orElse(null);
            if (directive != null) {
                NArgCompleteResult a = directive.resolveCandidates(cmdLine, pos, session);
                candidates.addAll(a.candidates());
                flags.addAll(a.flags());
            }
        }

        return NArgCompleteResult.of(candidates, flags);
    }

    /**
     * Adds the statement keywords that can still become what has been typed so far.
     *
     * <p>Filtered like the directives are: offering every keyword while the user
     * is midway through typing a command name lists things that cannot possibly
     * match, which is the difference between completion helping and completion
     * getting in the way. Sorted because {@code STATEMENT_KEYWORDS} is an
     * unordered set, and a list that reorders between runs cannot be scanned.
     *
     * @param currentCommand the command word as typed so far, or empty when nothing
     *                        has been typed and every keyword still applies
     */
    private void addKeywords(List<NArgCompleteCandidate> candidates, String currentCommand) {
        List<String> keywords = new ArrayList<>(NaruStatementHelper.STATEMENT_KEYWORDS);
        Collections.sort(keywords);
        for (String kw : keywords) {
            String value = "/" + kw;
            if (value.startsWith(currentCommand)) {
                candidates.add(NArgCompleteCandidate.of(value, value + " - keyword"));
            }
        }
    }


}
