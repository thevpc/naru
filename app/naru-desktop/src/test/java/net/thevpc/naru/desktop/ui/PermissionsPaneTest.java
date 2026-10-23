package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.PermissionsModel;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.naru.impl.access.NaruAccessPolicyImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Permissions tab as an object, built off-screen.
 * <p>
 * The gate harness exercises the whole window with a real session; this covers what that run cannot
 * distinguish — a tab that renders the policy in evaluation order, keeps the selection across a
 * repaint, and reports an unbound policy honestly. It needs no workspace: the pane talks to
 * {@link PermissionsModel}, and the model talks to the policy.
 */
class PermissionsPaneTest {

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    private static NaruPermissionRule deny() {
        return new NaruPermissionRule().id("deny-agent-write")
                .resourceType(NaruResourceType.FILE).patterns("src/**")
                .actions(NaruAction.WRITE).actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY).source(PermissionsModel.SOURCE_PROJECT);
    }

    private static NaruPermissionRule allow() {
        return new NaruPermissionRule().id("devs-write")
                .resourceType(NaruResourceType.FILE).patterns("**")
                .actions(NaruAction.WRITE).effect(NaruAccessEffect.ALLOW)
                .source(PermissionsModel.SOURCE_SESSION);
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** A session model that never boots: the pane only ever asks it for its models and its directory. */
    private static NaruSessionModel unbootedSession() {
        return new NaruSessionModel(() -> {
            throw new IllegalStateException("this test never starts a workspace");
        }, NPath.of(System.getProperty("java.io.tmpdir")), new InteractionHost());
    }

    @Test
    void rowsFollowPolicyOrderAndSurviveARepaint() throws Exception {
        // widgets need a graphics environment; headless CI skips rather than passes vacuously
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        EDT.setStrict(true);
        try {
            onEdt(() -> {
                NaruSessionModel session = unbootedSession();
                NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
                session.permissions().attach(policy);
                // added allow-first so the panel's order, not insertion order, is what is on trial
                session.permissions().putRule(allow());
                session.permissions().putRule(deny());
                session.permissions().move("deny-agent-write", -1);

                PermissionsPane pane = new PermissionsPane(session);
                assertTrue(pane.getComponentCount() >= 3,
                        "a toolbar, the rule list with its detail, and the note line");
                assertEquals("deny-agent-write", session.permissions().rows().get(0).id());

                assertTrue(pane.selectRule("devs-write"), "an existing id is selectable");
                assertFalse(pane.selectRule("no-such-rule"), "a stale denial card must not fake a selection");

                // a repaint with a new rule must keep the row the user is reading
                session.permissions().putRule(allow().id("third"));
                session.permissions().refresh();
                assertEquals("devs-write", selectedId(pane), "the selection survives the reload");

                pane.dispose();
                session.permissions().detach();
            });
        } finally {
            EDT.setStrict(false);
        }
    }

    @Test
    void anUnattachedPolicySaysSo() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        onEdt(() -> {
            NaruSessionModel session = unbootedSession();
            PermissionsPane pane = new PermissionsPane(session);
            assertEquals(0, session.permissions().rows().size());
            assertFalse(session.permissions().isAttached());
            assertTrue(noteText(pane).contains("not attached"),
                    "an empty table would otherwise read as a policy that allows everything: " + noteText(pane));
            pane.dispose();
        });
    }

    /** The note line's text, reached through the component tree rather than a getter. */
    private static String noteText(PermissionsPane pane) {
        javax.swing.JLabel[] found = new javax.swing.JLabel[1];
        walk(pane, c -> {
            if (found[0] == null && c instanceof javax.swing.JLabel l
                    && "Policy note".equals(l.getAccessibleContext().getAccessibleName())) {
                found[0] = l;
            }
        });
        return found[0] == null ? "" : found[0].getText();
    }

    /** The id the list currently holds, reached through the component tree rather than a getter. */
    private static String selectedId(PermissionsPane pane) {
        javax.swing.JList<?>[] found = new javax.swing.JList<?>[1];
        walk(pane, c -> {
            if (found[0] == null && c instanceof javax.swing.JList<?> l) {
                found[0] = l;
            }
        });
        javax.swing.JList<?> list = found[0];
        Object v = list == null ? null : list.getSelectedValue();
        return v instanceof PermissionsModel.Row r ? r.id() : null;
    }

    private static void walk(java.awt.Container c, java.util.function.Consumer<java.awt.Component> visit) {
        for (java.awt.Component x : c.getComponents()) {
            visit.accept(x);
            if (x instanceof java.awt.Container inner) {
                walk(inner, visit);
            }
        }
    }
}
