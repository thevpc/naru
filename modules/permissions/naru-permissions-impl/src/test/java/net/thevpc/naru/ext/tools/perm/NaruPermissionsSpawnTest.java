package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spawn-scoped rules: an agent created by a spawn is confined to read-only until
 * an operator grants more, and the confinement disappears with the task.
 */
public class NaruPermissionsSpawnTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void spawnedAgentIsConfinedToReadOnly() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsExtension ext = new NaruPermissionsExtension();
        ext.open(s);
        NaruTask task = NaruPermissionsTestSupport.task(s);
        NaruActor agent = NaruActor.agent("worker", NaruPrincipal.user("alice"));
        String source = NaruPermissionsExtension.SOURCE_SPAWN_PREFIX + task.id();

        ext.installSpawnConfinement(s, task, agent);
        assertNotNull(s.accessPolicy().rule(source).orNull(), "the confinement is scoped to the spawn");

        NaruResource file = NaruResource.of(NaruResourceType.FILE, "/tmp/report.txt");
        assertTrue(!s.accessPolicy().check(
                NaruAccessRequest.of(file, NaruAction.WRITE, agent).task(task)).allowed(),
                "write must be granted explicitly");
        assertTrue(!s.accessPolicy().check(
                NaruAccessRequest.of(file, NaruAction.EXECUTE, agent).task(task)).allowed(),
                "execute must be granted explicitly");
        assertTrue(s.accessPolicy().check(
                NaruAccessRequest.of(file, NaruAction.READ, agent).task(task)).allowed(),
                "reading is the inherited baseline");

        assertTrue(s.accessPolicy().check(NaruAccessRequest.of(file, NaruAction.WRITE,
                NaruActor.agent("other", null)).task(task)).allowed(), "another agent is untouched");
        assertTrue(s.accessPolicy().check(NaruAccessRequest.of(file, NaruAction.WRITE,
                NaruActor.user("alice")).task(task)).allowed(), "the owning user is untouched");

        ext.onTaskDeregistered(s, task.id());
        assertTrue(s.accessPolicy().rule(source).isEmpty(), "the rule dies with the task");
        assertTrue(s.accessPolicy().check(
                NaruAccessRequest.of(file, NaruAction.WRITE, agent).task(task)).allowed());
    }

    @Test
    public void aUserSpawnGetsNoConfinement() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsExtension ext = new NaruPermissionsExtension();
        ext.open(s);
        NaruTask userTask = s.newTask(NaruTaskSpec.of().actor(NaruActor.user("alice")));

        ext.onSpawned(s, userTask, null);

        assertTrue(s.accessPolicy().rules(NaruPermissionsExtension.SOURCE_SPAWN_PREFIX + userTask.id()).isEmpty(),
                "only agents are confined");
    }

    @Test
    public void anAgentSpawnInstallsConfinementThroughTheSpawnHook() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsExtension ext = new NaruPermissionsExtension();
        ext.open(s);
        NaruActor agent = NaruActor.agent("worker", NaruPrincipal.user("alice"));
        NaruTask agentTask = s.newTask(NaruTaskSpec.of().actor(agent));

        ext.onSpawned(s, agentTask, null);

        assertNotNull(s.accessPolicy()
                .rule(NaruPermissionsExtension.SOURCE_SPAWN_PREFIX + agentTask.id()).orNull());
    }
}
