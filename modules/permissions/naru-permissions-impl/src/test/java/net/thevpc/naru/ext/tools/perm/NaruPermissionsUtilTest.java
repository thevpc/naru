package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPrincipalType;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubjectType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vocabulary both the directive and the tools accept: tokens in, value types out.
 */
public class NaruPermissionsUtilTest {

    @Test
    public void parsesActionsWithAliases() {
        Set<NaruAction> actions = NaruPermissionsUtil.parseActions("r,w,x");
        assertTrue(actions.contains(NaruAction.READ));
        assertTrue(actions.contains(NaruAction.WRITE));
        assertTrue(actions.contains(NaruAction.EXECUTE));
    }

    @Test
    public void parsesEffectsAndKindsLeniently() {
        assertEquals(NaruAccessEffect.ALLOW, NaruPermissionsUtil.parseEffect("allow"));
        assertEquals(NaruAccessEffect.DENY, NaruPermissionsUtil.parseEffect("deny"));
        assertEquals(NaruAccessEffect.ALLOW, NaruPermissionsUtil.parseEffect(null));
        assertEquals(NaruActorKind.AGENT, NaruPermissionsUtil.parseActorKind("agent"));
        org.junit.jupiter.api.Assertions.assertNull(NaruPermissionsUtil.parseActorKind("*"));
    }

    @Test
    public void parsesSubjectShorthands() {
        assertTrue(NaruPermissionsUtil.splitList("user:a, group:devs").size() == 2);
        assertTrue(NaruPermissionsUtil.parseSubjects("user:alice").get(0).type() == NaruSubjectType.USER);
        assertTrue(NaruPermissionsUtil.parseSubjects("agent").get(0).type() == NaruSubjectType.AGENT);
        assertTrue(NaruPermissionsUtil.parseSubjects("*").get(0).type() == NaruSubjectType.ANY);
    }

    @Test
    public void parsesActorShorthands() {
        NaruActor user = NaruPermissionsUtil.parseActor("user:alice");
        assertTrue(user.principal().type() == NaruPrincipalType.USER);
        assertTrue(NaruPermissionsUtil.parseActor("alice").principal().type() == NaruPrincipalType.USER);
        assertTrue(NaruPermissionsUtil.parseActor("system").isSystem());

        NaruActor agent = NaruPermissionsUtil.parseActor("agent:reviewer");
        assertTrue(agent.isAgent());
        assertTrue(agent.principal().type() == NaruPrincipalType.AGENT);
    }

    @Test
    public void parsesResourceTypes() {
        assertEquals(NaruResourceType.of("file"), NaruPermissionsUtil.parseResourceType("file"));
        assertEquals(NaruResourceType.of("tool"), NaruPermissionsUtil.parseResourceType("tool"));
        assertNull(NaruPermissionsUtil.parseResourceType(""));
    }
}
