package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M6 filesystem guard: a {@code file}/{@code dir} rule bites every concrete
 * entry — the {@code file_*} helpers that back both the tools and {@code /file}
 * — and an empty policy leaves the filesystem exactly as it was.
 */
public class NaruFsGuardTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSession session() {
        NPath dir = NPath.ofTempFolder("naru-fs-guard-" + System.nanoTime());
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        return agent.newSession()
                .directory(dir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    private static NaruPermissionRule rule(String id, NaruResourceType type, String pattern,
                                           NaruAction action) {
        return new NaruPermissionRule()
                .id(id)
                .resourceType(type)
                .patterns(pattern)
                .actions(action)
                .effect(NaruAccessEffect.DENY);
    }

    private static void assertDenied(String result, String ruleId) {
        assertTrue(result.startsWith("ERROR: permission denied"),
                "expected the stable denial shape, was: " + result);
        assertTrue(result.contains(ruleId), "the responsible rule is named: " + result);
    }

    @Test
    public void projectRelativeRuleBlocksReadingASecretFile() {
        NaruSession s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        task.resolve("secret/a.txt").mkParentDirs().writeString("top secret");
        task.resolve("public.txt").writeString("hello");

        s.accessPolicy().putRule(rule("deny-secret", NaruResourceType.FILE, "secret/**", NaruAction.READ));

        assertDenied(FileToolHelper.fileRead(task, "secret/a.txt", null, null), "deny-secret");
        assertFalse(FileToolHelper.fileRead(task, "public.txt", null, null).startsWith("ERROR: permission denied"),
                "a file outside the pattern stays readable");
    }

    @Test
    public void writeRuleBlocksEveryWritingHelper() {
        NaruSession s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(rule("deny-write", NaruResourceType.FILE, "secret/**", NaruAction.WRITE));

        assertDenied(FileToolHelper.fileWrite(task, "secret/x.txt", "data", null), "deny-write");
        assertDenied(FileToolHelper.fileAppend(task, "secret/x.txt", "data", null), "deny-write");
        assertDenied(FileToolHelper.fileCreate(task, "secret/x.txt", "data", true), "deny-write");
        assertDenied(FileToolHelper.fileEdit(task, "secret/x.txt", 0L, 0L, "data", null), "deny-write");
        assertFalse(FileToolHelper.fileCreate(task, "ok.txt", "data", true).startsWith("ERROR: permission denied"),
                "a file outside the pattern stays writable");
    }

    @Test
    public void listRuleBlocksDirectorySearching() {
        NaruSession s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        task.resolve("secret").mkdirs();
        s.accessPolicy().putRule(rule("deny-ls", NaruResourceType.DIR, "secret", NaruAction.LIST));

        String result = FileToolHelper.folderFind(task, "secret",
                null, null, null, null, null, null, null, null, null, null, null);
        assertDenied(result, "deny-ls");
    }

    @Test
    public void emptyPolicyLeavesTheDomainOpen() {
        NaruSession s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        task.resolve("hello.txt").writeString("hi");

        assertTrue(FileToolHelper.fileRead(task, "hello.txt", null, null).contains("hi"));
    }

    @Test
    public void agentOnlyRuleAppliesToTheAgentActor() {
        NaruSession s = session();
        NaruTask user = s.newTask(NaruTaskSpec.of());
        user.resolve("note.txt").writeString("hi");

        s.accessPolicy().putRule(new NaruPermissionRule()
                .id("no-agent-read").resourceType(NaruResourceType.FILE).patterns("**")
                .actions(NaruAction.READ).actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY));

        assertFalse(FileToolHelper.fileRead(user, "note.txt", null, null).startsWith("ERROR: permission denied"),
                "the user acting directly is not an agent");

        NaruTask agent = s.newTask(NaruTaskSpec.of().actor(NaruActor.agent("agent-x", s.user())));
        assertDenied(FileToolHelper.fileRead(agent, "note.txt", null, null), "no-agent-read");
    }
}
