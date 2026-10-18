package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessGuard;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things the desktop has to get right about a denial: recognising one, and naming the rule
 * that caused it — which is the jump §6 promises.
 * <p>
 * Both are tested against the string {@code NaruAccessGuard.denyMessage()} actually produces rather
 * than a hand-written imitation, because that text is what arrives in a tool result and there is no
 * typed denial event to read instead (plan §3.3).
 */
class PermissionsTextTest {

    private static NaruPermissionRule rule(String id) {
        return new NaruPermissionRule().id(id).resourceType(NaruResourceType.FILE)
                .patterns("**/.env").actions(NaruAction.READ)
                .effect(NaruAccessEffect.DENY).source("perm:project");
    }

    @Test
    void aRealDenialIsRecognisedAndItsRuleRecovered() {
        NaruResource r = NaruResource.of(NaruResourceType.FILE, "/proj/.env");
        NaruAccessDecision d = NaruAccessDecision.deny(rule("deny-env"), "matched rule deny-env");
        String text = NaruAccessGuard.denyMessage(null, r, NaruAction.READ, d);
        assertTrue(PermissionsText.isDenial(text), text);
        assertEquals("deny-env", PermissionsText.matchedRuleId(text),
                "the id in the message is what the Permissions tab selects");
    }

    @Test
    void theFallbackDenialHasNoRuleToJumpTo() {
        NaruResource r = NaruResource.of(NaruResourceType.URL, "http://example.com");
        NaruAccessDecision d = NaruAccessDecision.deny(null, "default deny");
        String text = NaruAccessGuard.denyMessage(null, r, NaruAction.READ, d);
        assertTrue(PermissionsText.isDenial(text), text);
        assertNull(PermissionsText.matchedRuleId(text),
                "<default> is not a rule id, so the pane opens unselected instead of lying");
    }

    @Test
    void anOrdinaryResultIsNotADenial() {
        assertFalse(PermissionsText.isDenial("OK: wrote 3 files"));
        assertFalse(PermissionsText.isDenial("ERROR: tool not found: foo"));
        assertFalse(PermissionsText.isDenial(null));
        assertNull(PermissionsText.matchedRuleId(null));
        // a tool that fails for another reason must not offer a jump to permissions
        assertNull(PermissionsText.matchedRuleId("ERROR: permission denied"));
    }

    @Test
    void describeIsOneLineAndCarriesThePartsThatDecidePrecedence() {
        String s = PermissionsText.describe(rule("deny-env"));
        assertEquals(1, s.lines().count(), s);
        assertTrue(s.startsWith("DENY anyone read file **/.env"), s);
        assertTrue(s.contains("[perm:project]"), s);
        String off = PermissionsText.describe(rule("deny-env").enabled(false));
        assertTrue(off.contains("(off)"), off);
    }
}
