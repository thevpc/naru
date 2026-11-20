package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code /tags enable} completion against a proxy session: the registry answers
 * the tag vocabulary, and the completion must offer the {@code all} keyword the
 * directive accepts (it expands to every available tag, like {@code *}) together
 * with the tag names themselves. No files, no network.
 */
public class NaruTagsAutocompleteTest {

    private final NaruTagsDirective directive = new NaruTagsDirective();
    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void newSession() {
        Map<String, NaruToolTag> tags = new TreeMap<>();
        for (String[] t : new String[][]{
                {"fs", "file system access"},
                {"network", "network access"},
                {"write", "write access"}}) {
            NaruToolTag tag = new NaruToolTag() {
                @Override
                public String name() {
                    return t[0];
                }

                @Override
                public String description() {
                    return t[1];
                }
            };
            tags.put(t[0], tag);
        }
        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> "availableTags".equals(method.getName()) ? tags : null);
        session = (NaruSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> "registry".equals(method.getName()) ? registry : null);
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
    public void allIsOfferedAfterEnable() {
        List<String> c = values("/tags", "enable", "a");
        Assertions.assertTrue(c.contains("all"), () -> "'all' must complete after /tags enable, got: " + c);
    }

    @Test
    public void allIsOfferedWithAnEmptyPrefix() {
        List<String> c = values("/tags", "enable", "");
        Assertions.assertTrue(c.contains("all"), () -> "'all' must be offered, got: " + c);
        Assertions.assertTrue(c.contains("fs"), () -> "the tag names must be offered too, got: " + c);
    }

    @Test
    public void allIsOfferedAfterDisableToo() {
        List<String> c = values("/tags", "disable", "a");
        Assertions.assertTrue(c.contains("all"), () -> "'all' must complete after /tags disable, got: " + c);
    }

    @Test
    public void allIsOfferedForTheTagAlias() {
        List<String> c = values("/tag", "enable", "a");
        Assertions.assertTrue(c.contains("all"), () -> "'all' must complete after /tag enable, got: " + c);
    }

    @Test
    public void tagNamesCompleteWithTheirDescriptions() {
        List<NArgCompleteCandidate> c = complete("/tags", "enable", "net");
        List<String> values = c.stream().map(NArgCompleteCandidate::value).collect(Collectors.toList());
        Assertions.assertEquals(List.of("network"), values, () -> "got: " + values);
        Assertions.assertTrue(display(c, "network").contains("network access"),
                () -> "a tag must show its description, got: " + display(c, "network"));
    }

    @Test
    public void subcommandNamesStillCompleteAtWord1() {
        List<String> c = values("/tags", "en");
        Assertions.assertEquals(List.of("enable"), c, () -> "got: " + c);
    }
}
