package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event-driven rules: a rule can be injected by firing {@code perm.grant} rather
 * than by calling a directive or a tool, which is how a host or a hook adds a
 * permission without an API in the middle.
 */
public class NaruPermissionsEventsTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    /** A session with the extension installed, so its listener sees fired events. */
    private static NaruSessionImpl sessionWithExtension() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        new NaruPermissionsExtension().open(s);
        return s;
    }

    @Test
    public void grantEventAddsASessionRule() {
        NaruSessionImpl s = sessionWithExtension();

        s.fireEvent(NaruPermissionsEvents.GRANT, Map.of(
                "id", "evt-allow", "type", "tool", "pattern", "echo_tool",
                "actions", "call", "effect", "allow"));

        NaruPermissionRule r = s.accessPolicy().rule("evt-allow").orNull();
        assertNotNull(r, "the event installed a rule");
        assertEquals(NaruResourceType.TOOL, r.resourceType());
        assertEquals(NaruAccessEffect.ALLOW, r.effect());
        assertEquals(NaruPermissionsExtension.SOURCE_SESSION, r.source(),
                "an event-injected rule rides the session state");
    }

    @Test
    public void revokeEventRemovesARule() {
        NaruSessionImpl s = sessionWithExtension();
        s.fireEvent(NaruPermissionsEvents.GRANT,
                Map.of("id", "r1", "type", "tool", "pattern", "t", "effect", "deny"));
        assertTrue(s.accessPolicy().rule("r1").isPresent());

        s.fireEvent(NaruPermissionsEvents.REVOKE, Map.of("id", "r1"));

        assertTrue(s.accessPolicy().rule("r1").isEmpty());
    }

    @Test
    public void defaultEventChangesTheFallback() {
        NaruSessionImpl s = sessionWithExtension();
        assertEquals(NaruAccessEffect.ALLOW, s.accessPolicy().defaultEffect());

        s.fireEvent(NaruPermissionsEvents.DEFAULT, Map.of("effect", "deny"));

        assertEquals(NaruAccessEffect.DENY, s.accessPolicy().defaultEffect());
    }

    @Test
    public void grantHonoursAnExplicitIndexAndSource() {
        NaruSessionImpl s = sessionWithExtension();
        s.fireEvent(NaruPermissionsEvents.GRANT, Map.of("id", "a", "type", "tool", "pattern", "a"));
        s.fireEvent(NaruPermissionsEvents.GRANT,
                Map.of("id", "b", "type", "tool", "pattern", "b", "source", "hook:ci", "index", "0"));

        NaruPermissionRule b = s.accessPolicy().rule("b").orNull();
        assertNotNull(b);
        assertEquals("hook:ci", b.source());
        assertEquals("b", s.accessPolicy().rules().get(0).id());
    }
}
