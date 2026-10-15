package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubject;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two homes of rules: the committed project file (read, replaced wholesale on
 * reload) and the session state (dynamic rules only).
 */
public class NaruPermissionsRuleStoreTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void projectFileIsLoadedAndReplacesPreviousProjectRules() {
        NaruSessionImpl session = NaruPermissionsTestSupport.session();
        NPath file = session.projectDir().resolve(NaruPermissionsRuleStore.PROJECT_FILE);

        NaruPermissionRule fromFile = new NaruPermissionRule()
                .id("devs-rw-src")
                .resourceType(NaruResourceType.FILE)
                .patterns("src/**")
                .actions(NaruAction.READ, NaruAction.WRITE)
                .subjects(NaruSubject.group("devs"))
                .effect(NaruAccessEffect.ALLOW);
        NElement root = NObjectElementBuilder.of()
                .set("defaultEffect", "deny")
                .set("rules", NElement.ofArrayBuilder().add(fromFile.toElement()).build())
                .build();
        file.parent().mkdirs();
        NElementWriter.ofTson().write(root, file);

        NaruAccessPolicy policy = session.accessPolicy();
        // a stale project rule the file no longer contains must not survive the reload
        policy.putRule(new NaruPermissionRule()
                .id("stale").resourceType(NaruResourceType.FILE).patterns("gone/**")
                .effect(NaruAccessEffect.DENY).source(NaruPermissionsExtension.SOURCE_PROJECT));

        new NaruPermissionsRuleStore(session).loadProjectRules();

        NaruPermissionRule loaded = policy.rule("devs-rw-src").orNull();
        assertTrue(loaded != null, "the rule from the file is loaded");
        assertEquals(NaruResourceType.FILE, loaded.resourceType());
        assertEquals(java.util.List.of("src/**"), loaded.patterns());
        assertTrue(loaded.actions().contains(NaruAction.READ));
        assertTrue(loaded.actions().contains(NaruAction.WRITE));
        assertEquals(NaruPermissionsExtension.SOURCE_PROJECT, loaded.source(),
                "a project rule is tagged with its origin so reload can replace it");
        assertTrue(policy.rule("stale").isEmpty(), "a rule the file dropped is dropped here too");
        assertEquals(NaruAccessEffect.DENY, policy.defaultEffect(), "the file's default effect applies");
    }

    @Test
    public void projectFileAbsentLeavesThePolicyUntouched() {
        NaruSessionImpl session = NaruPermissionsTestSupport.session();
        NaruAccessPolicy policy = session.accessPolicy();
        policy.putRule(new NaruPermissionRule().id("keep").resourceType(NaruResourceType.TOOL).effect(NaruAccessEffect.ALLOW));

        new NaruPermissionsRuleStore(session).loadProjectRules();

        assertTrue(policy.rule("keep").isPresent());
        assertEquals(NaruAccessEffect.ALLOW, policy.defaultEffect());
    }

    @Test
    public void sessionStateRoundTripsOnlyDynamicRules() {
        NaruSessionImpl session = NaruPermissionsTestSupport.session();
        NaruAccessPolicy policy = session.accessPolicy();
        policy.putRule(new NaruPermissionRule().id("sess1")
                .resourceType(NaruResourceType.TOOL).patterns("echo_tool")
                .actions(NaruAction.CALL).effect(NaruAccessEffect.DENY)
                .source(NaruPermissionsExtension.SOURCE_SESSION));
        policy.putRule(new NaruPermissionRule().id("spawn1")
                .resourceType(NaruResourceType.FILE).patterns("tmp/**")
                .effect(NaruAccessEffect.ALLOW)
                .source(NaruPermissionsExtension.SOURCE_SPAWN_PREFIX + "task-7"));
        // neither of these belongs in the session state
        policy.putRule(new NaruPermissionRule().id("proj1")
                .resourceType(NaruResourceType.FILE).effect(NaruAccessEffect.ALLOW)
                .source(NaruPermissionsExtension.SOURCE_PROJECT));
        policy.putRule(new NaruPermissionRule().id("core1")
                .resourceType(NaruResourceType.URL).effect(NaruAccessEffect.ALLOW)
                .source(NaruPermissionsExtension.SOURCE_CORE));

        NElement state = new NaruPermissionsRuleStore(session).saveSessionRules();

        NaruSessionImpl resumed = NaruPermissionsTestSupport.session();
        NaruAccessPolicy resumedPolicy = resumed.accessPolicy();
        new NaruPermissionsRuleStore(resumed).loadSessionRules(state);

        assertTrue(resumedPolicy.rule("sess1").isPresent());
        assertTrue(resumedPolicy.rule("spawn1").isPresent());
        assertFalse(resumedPolicy.rule("proj1").isPresent(),
                "project rules are not persisted in the session state");
        assertFalse(resumedPolicy.rule("core1").isPresent(),
                "built-in rules are not persisted in the session state");
        assertEquals(NaruPermissionsExtension.SOURCE_SESSION, resumedPolicy.rule("sess1").get().source());
    }

    @Test
    public void reloadingSessionStateDoesNotAccumulateDuplicates() {
        NaruSessionImpl session = NaruPermissionsTestSupport.session();
        NaruAccessPolicy policy = session.accessPolicy();
        policy.putRule(new NaruPermissionRule().id("dyn")
                .resourceType(NaruResourceType.TOOL).patterns("t")
                .effect(NaruAccessEffect.DENY)
                .source(NaruPermissionsExtension.SOURCE_SESSION));
        NaruPermissionsRuleStore store = new NaruPermissionsRuleStore(session);
        NElement state = store.saveSessionRules();

        store.loadSessionRules(state);
        store.loadSessionRules(state);

        assertEquals(1, policy.rules(NaruPermissionsExtension.SOURCE_SESSION).size(),
                "loading the same state twice must not duplicate the rules");
    }
}
