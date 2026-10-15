package net.thevpc.naru.ext.tools.web;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M6 web guard: a {@code url} rule is consulted before any socket is opened,
 * for both {@code web_fetch} and the fixed search backend.
 */
public class NaruWebGuardTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionImpl session() {
        NPath dir = NPath.ofTempFolder("naru-web-guard-" + System.nanoTime());
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(dir);
        return new NaruSessionImpl(agent, dir,
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
    }

    private static NaruPermissionRule denyFetch(String id, String pattern) {
        return new NaruPermissionRule()
                .id(id)
                .resourceType(NaruResourceType.URL)
                .patterns(pattern)
                .actions(NaruAction.FETCH)
                .effect(NaruAccessEffect.DENY);
    }

    @Test
    public void fetchRuleBlocksBeforeAnySocketIsOpened() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyFetch("deny-evil", "**evil.example**"));

        String denied = WebToolHelper.webFetch(task, "https://evil.example/x", "text", 1);
        assertTrue(denied.startsWith("ERROR: permission denied"), denied);
        assertTrue(denied.contains("evil.example"), denied);
        assertTrue(denied.contains("deny-evil"), denied);
    }

    @Test
    public void unrelatedUrlIsNotDenied() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyFetch("deny-evil", "**evil.example**"));

        // an unparsable URL fails fast in the client and never reaches a denial
        String result = WebToolHelper.webFetch(task, "not-a-url", "text", 1);
        assertFalse(result.startsWith("ERROR: permission denied"), result);
    }

    @Test
    public void searchBackendIsPoliced() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyFetch("deny-search", WebToolHelper.SEARCH_URL));

        String denied = WebToolHelper.searchWeb(task, "naru");
        assertTrue(denied.startsWith("ERROR: permission denied"), denied);
        assertTrue(denied.contains("deny-search"), denied);
    }
}
