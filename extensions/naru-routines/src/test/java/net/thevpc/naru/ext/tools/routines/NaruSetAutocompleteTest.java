package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code /set} completion against a real session: the variables it can target
 * (session and agent env, since those are all the completion context can see)
 * merged with the config keys the build declares through the registry. The
 * declared keys carry their descriptions; a same-named env variable is shadowed
 * by the declaration.
 */
public class NaruSetAutocompleteTest {

    private final NaruSetDirective directive = new NaruSetDirective();
    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void newSession() {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-set-complete-" + System.nanoTime());
        agent.projectDirectory(dir);
        session = agent.newSession()
                .directory(dir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    private List<NArgCompleteCandidate> complete(String... words) {
        NCmdLine cmdLine = NCmdLine.of(words);
        int last = words.length - 1;
        NArgCompletePosition pos = NArgCompletePosition.of(last, words[last].length(), 0);
        NArgCompleteResult result = directive.resolveCandidates(cmdLine.completePosition(pos), pos, session);
        return result.candidates();
    }

    private List<String> values(String... words) {
        return complete(words).stream().map(NArgCompleteCandidate::value).collect(Collectors.toList());
    }

    private static String display(List<NArgCompleteCandidate> candidates, String value) {
        for (NArgCompleteCandidate c : candidates) {
            if (c.value().equals(value)) {
                return c.display();
            }
        }
        return null;
    }

    @Test
    public void declaredConfigKeysCompleteWithDescriptions() {
        List<NArgCompleteCandidate> c = complete("/set", "max");
        List<String> values = values("/set", "max");
        Assertions.assertTrue(values.contains("maxSteps"), () -> "got: " + values);
        Assertions.assertTrue(values.contains("maxSteps="), () -> "the '=' hole must be offered, got: " + values);
        Assertions.assertTrue(display(c, "maxSteps").contains("maximum steps"),
                () -> "a declared key must show its description, got: " + display(c, "maxSteps"));
    }

    @Test
    public void fixedVocabularyCompletesValuesAfterTheEquals() {
        List<String> c = values("/set", "trace=");
        Assertions.assertTrue(c.contains("trace=true"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("trace=false"), () -> "got: " + c);
    }

    @Test
    public void envVariablesAreMergedWithTheDeclaredKeys() {
        session.agent().agentEnv().put("custom.x", NElement.ofString("1"), NaruVisibility.PRIVATE);

        List<String> c = values("/set", "custom.");
        Assertions.assertTrue(c.contains("custom.x"), () -> "an existing env variable must complete, got: " + c);
        Assertions.assertTrue(c.contains("custom.x="), () -> "got: " + c);
        Assertions.assertTrue(display(complete("/set", "custom.x"), "custom.x").contains("session or agent env"),
                () -> "an undeclared variable must be labeled as env-present");
    }

    @Test
    public void aDeclaredKeyShadowsTheSameNamedEnvVariable() {
        // trace is declared by the core provider; a task can still carry its own value
        session.setSessionEnv("trace", true);
        List<String> c = values("/set", "trace");
        Assertions.assertTrue(c.contains("trace"), () -> "got: " + c);
        Assertions.assertTrue(display(complete("/set", "trace"), "trace").contains("trace logging"),
                "the declared description must win over the plain env label");
    }

    @Test
    public void scopeFlagsStillComplete() {
        List<String> c = values("/set", "--");
        Assertions.assertTrue(c.contains("--task"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--session"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--agent"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--local"), () -> "got: " + c);
    }
}
