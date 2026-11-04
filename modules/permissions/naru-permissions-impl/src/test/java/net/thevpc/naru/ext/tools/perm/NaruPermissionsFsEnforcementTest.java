package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.ext.tools.fs.FileToolHelper;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 end to end: the built-in agent secret rule, declared on the policy, now
 * physically bites when an agent reads a private key through the filesystem
 * domain — the check is no longer only theoretical. The human path stays open.
 */
public class NaruPermissionsFsEnforcementTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void builtinSecretRuleStopsAnAgentAtTheFileTool() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsBuiltins.install(s.accessPolicy());

        NaruTask user = s.newTask(NaruTaskSpec.of());
        user.resolve(".ssh/id_rsa").mkParentDirs().writeString("PRIVATE KEY");
        NaruTask agent = s.newTask(NaruTaskSpec.of().actor(NaruActor.agent("a1", s.user())));

        String userResult = FileToolHelper.fileRead(user, ".ssh/id_rsa", null, null);
        assertTrue(userResult.contains("PRIVATE KEY"), "the human reads their own key: " + userResult);

        String agentResult = FileToolHelper.fileRead(agent, ".ssh/id_rsa", null, null);
        assertFalse(agentResult.contains("PRIVATE KEY"), "the agent never sees the content: " + agentResult);
        assertTrue(agentResult.startsWith("ERROR: permission denied"),
                "and the refusal carries the built-in's id: " + agentResult);
        assertTrue(agentResult.contains(NaruPermissionsBuiltins.NO_AGENT_SECRET_FILES), agentResult);
    }
}
