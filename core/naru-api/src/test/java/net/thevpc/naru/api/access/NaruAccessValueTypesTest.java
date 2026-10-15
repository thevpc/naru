package net.thevpc.naru.api.access;

import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value types of the access model, exercised on their own: glob semantics,
 * subject matching (especially the user-owns-agent case), the rule filter, and
 * element round-trips. Nothing here consults a policy; the policy engine is a
 * separate concern.
 */
public class NaruAccessValueTypesTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    // ── glob ────────────────────────────────────────────────────────────────

    @Test
    public void singleStarDoesNotCrossSegmentBoundary() {
        assertTrue(NaruGlob.matches("/a/*", "/a/b"));
        assertFalse(NaruGlob.matches("/a/*", "/a/b/c"),
                "a rule that means 'directly under a' must not read as 'anywhere under a'");
    }

    @Test
    public void doubleStarCrossesSegmentBoundary() {
        assertTrue(NaruGlob.matches("/proj/src/**", "/proj/src/A.java"));
        assertTrue(NaruGlob.matches("/proj/src/**", "/proj/src/a/b.java"));
        assertFalse(NaruGlob.matches("/proj/src/**", "/proj/src"),
                "a trailing separator requires something after it");
    }

    @Test
    public void leadingDoubleStarAlsoMatchesNoSegment() {
        assertTrue(NaruGlob.matches("**/.env", "/proj/.env"));
        assertTrue(NaruGlob.matches("**/.env", "/a/b/.env"));
        assertTrue(NaruGlob.matches("**/.env", ".env"));
        assertFalse(NaruGlob.matches("**/.env", "/proj/env"));
    }

    @Test
    public void questionMarkMatchesOneNonSeparatorChar() {
        assertTrue(NaruGlob.matches("/a/b?c", "/a/bxc"));
        assertFalse(NaruGlob.matches("/a/b?c", "/a/b/c"));
    }

    @Test
    public void characterClassAndAlternation() {
        assertTrue(NaruGlob.matches("/a/[bc].txt", "/a/b.txt"));
        assertTrue(NaruGlob.matches("/a/[!bc].txt", "/a/d.txt"));
        assertFalse(NaruGlob.matches("/a/[!bc].txt", "/a/b.txt"));
        assertTrue(NaruGlob.matches("/a/{b,c}.txt", "/a/c.txt"));
        assertFalse(NaruGlob.matches("/a/{b,c}.txt", "/a/d.txt"));
    }

    @Test
    public void literalPatternsAreExactMatches() {
        assertTrue(NaruGlob.matches("/a/b", "/a/b"));
        assertFalse(NaruGlob.matches("/a/b", "/a/c"));
        assertFalse(NaruGlob.isGlob("/a/b"));
        assertTrue(NaruGlob.isGlob("/a/*"));
    }

    // ── actions ─────────────────────────────────────────────────────────────

    @Test
    public void aliasesResolveToTheCanonicalAction() {
        assertEquals(NaruAction.READ, NaruAction.parse("r"));
        assertEquals(NaruAction.WRITE, NaruAction.parse("w"));
        assertEquals(NaruAction.EXECUTE, NaruAction.parse("x"));
        assertEquals("read", NaruAction.parse("r").id(), "the id is canonical, the alias is only an input");
        assertEquals(NaruAction.parse("deploy"), NaruAction.parse("deploy"),
                "an unknown verb still round-trips to one value");
    }

    @Test
    public void anyActionCoversEveryOther() {
        assertTrue(NaruAction.ANY.matches(NaruAction.WRITE));
        assertFalse(NaruAction.WRITE.matches(NaruAction.ANY),
                "match is asymmetric: a specific grant does not cover a wildcard query");
        assertTrue(NaruAction.READ.matches(NaruAction.READ));
    }

    // ── subjects ────────────────────────────────────────────────────────────

    @Test
    public void userSubjectReachesTheUserAndTheAgentsItOwns() {
        NaruActor alice = NaruActor.user("alice");
        NaruActor reviewers = NaruActor.agent("reviewer", NaruPrincipal.user("alice"));
        NaruSubject user = NaruSubject.user("alice");
        assertTrue(user.matches(alice));
        assertTrue(user.matches(reviewers), "an agent runs on behalf of its owner");
        assertFalse(user.matches(NaruActor.user("bob")));
    }

    @Test
    public void actorSubjectStaysExact() {
        NaruActor reviewers = NaruActor.agent("reviewer", NaruPrincipal.user("alice"));
        assertTrue(NaruSubject.actor("reviewer").matches(reviewers));
        assertFalse(NaruSubject.actor("alice").matches(reviewers),
                "the owner does not become the agent's identity");
        assertTrue(NaruSubject.agents().matches(reviewers));
        assertFalse(NaruSubject.agents().matches(NaruActor.user("alice")));
    }

    @Test
    public void groupAndRoleSeeTheOwnersMemberships() {
        NaruPrincipal alice = NaruPrincipal.user("alice").withGroup("devs").withRole("reviewer");
        NaruActor agent = NaruActor.agent("helper", alice);
        assertTrue(NaruSubject.group("devs").matches(agent),
                "an agent inherits its owner's memberships as a property of the actor");
        assertTrue(NaruSubject.role("reviewer").matches(agent));
        assertFalse(NaruSubject.group("ops").matches(agent));
    }

    @Test
    public void anySubjectMatchesEveryone() {
        assertTrue(NaruSubject.any().matches(NaruActor.user("alice")));
        assertTrue(NaruSubject.any().matches(NaruActor.system()));
    }

    // ── rule filter ─────────────────────────────────────────────────────────

    @Test
    public void ruleMatchesOnTypeActionsPatternAndSubject() {
        NaruPermissionRule rule = new NaruPermissionRule()
                .id("devs-write-src")
                .resourceType(NaruResourceType.FILE)
                .patterns("/proj/src/**")
                .actions(NaruAction.READ, NaruAction.WRITE)
                .subjects(NaruSubject.user("alice"));

        NaruResource res = NaruResource.of(NaruResourceType.FILE, "/proj/src/A.java");
        assertTrue(rule.matches(res, NaruAction.WRITE, NaruActor.user("alice")));
        assertTrue(rule.matches(res, NaruAction.WRITE,
                        NaruActor.agent("reviewer", NaruPrincipal.user("alice"))),
                "the owner's grant reaches the agent");
        assertFalse(rule.matches(res, NaruAction.WRITE, NaruActor.user("bob")));
        assertFalse(rule.matches(res, NaruAction.EXECUTE, NaruActor.user("alice")));
        assertFalse(rule.matches(NaruResource.of(NaruResourceType.URL, "https://x/src/**"),
                NaruAction.WRITE, NaruActor.user("alice")), "a file rule does not match a url resource");
    }

    @Test
    public void actorKindIsTheRoleContextNotThePrincipalType() {
        NaruPermissionRule agentOnly = new NaruPermissionRule()
                .id("agents-only")
                .resourceType(NaruResourceType.FILE)
                .patterns("**")
                .actions(NaruAction.READ)
                .actorKind(NaruActorKind.AGENT);
        NaruResource res = NaruResource.of(NaruResourceType.FILE, "/proj/x");

        assertTrue(agentOnly.matches(res, NaruAction.READ,
                NaruActor.agent("a", NaruPrincipal.user("alice"))));
        assertFalse(agentOnly.matches(res, NaruAction.READ, NaruActor.user("alice")),
                "the owner acting directly is not an agent");
    }

    @Test
    public void disabledRuleNeverMatches() {
        NaruPermissionRule rule = new NaruPermissionRule()
                .resourceType(NaruResourceType.FILE)
                .patterns("**")
                .actions(NaruAction.READ)
                .enabled(false);
        assertFalse(rule.matches(NaruResource.of(NaruResourceType.FILE, "/x"),
                NaruAction.READ, NaruActor.user("alice")));
    }

    @Test
    public void emptyFilterMeansAny() {
        NaruPermissionRule catchAll = new NaruPermissionRule().id("all").effect(NaruAccessEffect.DENY);
        assertTrue(catchAll.matches(NaruResource.of(NaruResourceType.URL, "https://x"),
                NaruAction.FETCH, NaruActor.user("alice")));
    }

    // ── round-trips ─────────────────────────────────────────────────────────

    @Test
    public void ruleRoundTripsThroughAnElement() {
        NaruPermissionRule rule = new NaruPermissionRule()
                .id("deny-secrets")
                .resourceType(NaruResourceType.FILE)
                .patterns("**/.env", "**/id_rsa")
                .actions(NaruAction.READ, NaruAction.WRITE)
                .subjects(NaruSubject.any(), NaruSubject.group("devs"))
                .actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY)
                .order(10)
                .source("perm:project")
                .description("agents never read credentials");
        NaruPermissionRule back = NaruPermissionRule.of(rule.toElement());
        assertEquals(rule, back);
        assertEquals(rule.toElement(), back.toElement());
    }

    @Test
    public void actorRoundTripsThroughAnElement() {
        NaruActor agent = NaruActor.agent(
                NaruPrincipal.agent("reviewer", "alice"),
                NaruPrincipal.user("alice").withGroup("devs")).withTaskId(42);
        NaruActor back = NaruActor.of(agent.toElement());
        assertEquals(agent, back);
        assertEquals(42, back.taskId());
        assertTrue(back.isAgent());
        assertEquals("alice", back.owner().id());
    }

    @Test
    public void subjectParseHandlesShorthands() {
        assertEquals(NaruSubject.any(), NaruSubject.parse("*"));
        assertEquals(NaruSubject.user("alice"), NaruSubject.parse("user:alice"));
        assertEquals(NaruSubject.group("devs"), NaruSubject.parse("group:devs"));
        assertEquals(NaruSubject.role("admin"), NaruSubject.parse("role:admin"));
        assertEquals(NaruSubject.agents(), NaruSubject.parse("agent"));
        assertEquals(NaruSubject.system(), NaruSubject.parse("system"));
    }

    @Test
    public void principalAndActorIdentityIsByIdAndType() {
        assertEquals(NaruPrincipal.user("alice"), NaruPrincipal.user("alice", "Alice Smith"));
        assertNotEquals(NaruPrincipal.user("alice"), NaruPrincipal.agent("alice", "bob"));
        assertNotEquals(NaruActor.user("alice"),
                NaruActor.agent("alice", NaruPrincipal.user("bob")));
    }

    @Test
    public void badInputsAreRejected() {
        assertThrows(RuntimeException.class, () -> NaruPrincipal.user("  "));
        assertThrows(RuntimeException.class, () -> NaruResource.of(NaruResourceType.FILE, " "));
        assertThrows(RuntimeException.class, () -> NaruSubject.parse("nonsense:x:y"));
    }

    @Test
    public void matchesHelpersAggregate() {
        assertTrue(NaruGlob.matchesAny(List.of("/a/*", "/b/*"), "/b/c"));
        assertFalse(NaruGlob.matchesAny(List.of(), "/b/c"));
        assertTrue(NaruGlob.matchesAny(Arrays.asList("/a/*", "/b/*"), "/a/c"));
    }

    @Test
    public void attributeCarryingIsCopyOnWrite() {
        NaruResource r = NaruResource.of(NaruResourceType.TOOL, "web_fetch")
                .withAttribute("tags", new LinkedHashSet<>(List.of("network")));
        assertEquals(1, r.attributes().size());
        NaruResource r2 = r.withAttribute("tags", null);
        assertEquals(0, r2.attributes().size(), "removing an attribute does not disturb the original");
        assertEquals(1, r.attributes().size());
    }
}
