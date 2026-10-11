package net.thevpc.naru.ext.tools.tags;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code tag_list} — the listing companion to {@code tag_add}/{@code tag_remove}. It
 * shows the known tags with their status on the task ({@code enabled} = granted,
 * {@code disabled} = not granted, {@code all} = both, the default) and keeps the two
 * mutators from inlining the catalog in their schemas.
 */
public class ToolTagListToolTest {

    @BeforeAll
    public static void setUp() {
        // NText.ofPlain (used by tool descriptions) needs a Nuts workspace.
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final class Fixture {
        final Map<String, NaruToolTag> available = new LinkedHashMap<>();
        final Set<String> enabledNames = new LinkedHashSet<>();
    }

    private static Fixture fixture() {
        Fixture f = new Fixture();
        f.available.put("fs", new DefaultNaruToolTag("fs", "file system operations"));
        f.available.put("routine", new DefaultNaruToolTag("routine", "routine operations"));
        f.available.put("write", new DefaultNaruToolTag("write", "persistent modifications"));
        f.available.put("git", new DefaultNaruToolTag("git", "git operations"));
        f.enabledNames.add("routine");
        f.enabledNames.add("write");
        return f;
    }

    @SuppressWarnings("unchecked")
    private static NaruTask task(Fixture f) {
        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(
                ToolTagListToolTest.class.getClassLoader(),
                new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> "availableTags".equals(method.getName())
                        ? new LinkedHashMap<>(f.available) : null);
        NaruSession session = (NaruSession) Proxy.newProxyInstance(
                ToolTagListToolTest.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> "registry".equals(method.getName()) ? registry : null);
        return (NaruTask) Proxy.newProxyInstance(
                ToolTagListToolTest.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "session":
                            return session;
                        case "findToolTagNames":
                            return new LinkedHashSet<>(f.enabledNames);
                        case "findToolTags": {
                            List<NaruToolTag> tags = new ArrayList<>();
                            for (String n : f.enabledNames) {
                                NaruToolTag t = f.available.get(n);
                                if (t != null) {
                                    tags.add(t);
                                }
                            }
                            return tags;
                        }
                        default:
                            return null;
                    }
                });
    }

    private static String list(Fixture f, Boolean enabled, Boolean disabled, Boolean all, String query) {
        return ToolTagListTool.listTags(task(f), enabled, disabled, all, query);
    }

    @Test
    public void defaultListsEveryTagWithStatus() {
        String out = list(fixture(), null, null, null, null);
        Assertions.assertTrue(out.contains("4 tags (2 enabled, 2 disabled)"), out);
        Assertions.assertTrue(out.contains("[enabled] routine"), out);
        Assertions.assertTrue(out.contains("[enabled] write"), out);
        Assertions.assertTrue(out.contains("[disabled] fs"), out);
        Assertions.assertTrue(out.contains("[disabled] git"), out);
    }

    @Test
    public void enabledFilterKeepsGrantedTagsOnly() {
        String out = list(fixture(), true, null, null, null);
        Assertions.assertTrue(out.contains("[enabled] routine"), out);
        Assertions.assertTrue(out.contains("[enabled] write"), out);
        Assertions.assertFalse(out.contains("fs"), out);
        Assertions.assertFalse(out.contains("git"), out);
    }

    @Test
    public void disabledFilterKeepsUngrantedTagsOnly() {
        String out = list(fixture(), null, true, null, null);
        Assertions.assertTrue(out.contains("[disabled] fs"), out);
        Assertions.assertTrue(out.contains("[disabled] git"), out);
        Assertions.assertFalse(out.contains("routine"), out);
        Assertions.assertFalse(out.contains("write"), out);
    }

    @Test
    public void allFlagKeepsBothStatuses() {
        String out = list(fixture(), null, null, true, null);
        Assertions.assertEquals(4, countRows(out), out);
    }

    @Test
    public void queryMatchesNameOrDescription() {
        Fixture f = fixture();
        String byName = list(f, null, null, null, "git");
        Assertions.assertTrue(byName.contains("git"), byName);
        Assertions.assertFalse(byName.contains("routine"), byName);

        String byDescription = list(f, null, null, null, "persistent");
        Assertions.assertTrue(byDescription.contains("write"), byDescription);
        Assertions.assertFalse(byDescription.contains("git"), byDescription);
    }

    @Test
    public void grantedButUnregisteredTagStaysVisible() {
        Fixture f = fixture();
        f.enabledNames.add("mcp"); // granted name whose provider is not installed
        String out = list(f, true, null, null, null);
        Assertions.assertTrue(out.contains("[enabled] mcp"), out);
    }

    @Test
    public void emptyRegistryIsReported() {
        String out = list(new Fixture(), null, null, null, null);
        Assertions.assertTrue(out.contains("No tool tags are registered"), out);
    }

    @Test
    public void tagAddNoLongerInlinesTheCatalog() {
        NaruToolDefinition def = new ToolTagAddTool().getDefinition(task(fixture()));
        String description = def.getDescription();
        Assertions.assertFalse(description.contains("file system operations"),
                () -> "tag_add must not inline the catalog: " + description);
        Assertions.assertTrue(description.contains("tag_list"),
                () -> "tag_add should point at tag_list: " + description);
        List<String> params = ((NaruToolDefinitionFunction) def).getParams().stream()
                .map(NaruToolParameter::getName).collect(Collectors.toList());
        Assertions.assertEquals(List.of("tags"), params);
    }

    @Test
    public void tagRemoveNoLongerInlinesTheCatalog() {
        NaruToolDefinition def = new ToolTagRemoveTool().getDefinition(task(fixture()));
        String description = def.getDescription();
        Assertions.assertFalse(description.contains("routine operations"),
                () -> "tag_remove must not inline the catalog: " + description);
        Assertions.assertTrue(description.contains("tag_list"),
                () -> "tag_remove should point at tag_list: " + description);
    }

    private static int countRows(String out) {
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("  ")) {
                n++;
            }
        }
        return n;
    }
}
