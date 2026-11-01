package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Actor threading: who owns a session, who a task runs as, and who authored a
 * statement — carried from creation through persistence to the point where a
 * statement executes.
 */
public class NaruActorThreadingTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSession session() {
        NPath dir = NPath.ofTempFolder("naru-actor-" + System.nanoTime());
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

    @Test
    public void defaultUserIsTheOsUser() {
        NaruSession s = session();
        assertEquals(System.getProperty("user.name", "user"), s.user().id());
        assertEquals(s.user(), s.userActor().principal(),
                "the acting user is the owner principal, no more");
    }

    @Test
    public void taskInheritsTheSessionUserUnlessTheSpecNamesAnActor() {
        NaruSession s = session();
        NaruTask plain = s.newTask(NaruTaskSpec.of());
        assertEquals(s.userActor(), plain.currentActor());

        NaruActor agent = NaruActor.agent("reviewer", s.user());
        NaruTask owned = s.newTask(NaruTaskSpec.of().actor(agent));
        assertEquals(agent, owned.currentActor(), "a declared actor is what the task runs as");
    }

    @Test
    public void childTaskInheritsTheParentsActor() {
        NaruSession s = session();
        NaruActor agent = NaruActor.agent("planner", s.user());
        NaruTask parent = s.newTask(NaruTaskSpec.of().actor(agent));
        NaruTask child = s.newTask(NaruTaskSpec.of().parentId(parent.id()));
        assertEquals(agent, child.currentActor(), "a spawned task runs as the agent that spawned it");
    }

    @Test
    public void declaredStatementsAreStampedWithTheTaskActor() {
        NaruSession s = session();
        NaruActor agent = NaruActor.agent("reviewer", s.user());
        NaruTask task = s.newTask(NaruTaskSpec.of().actor(agent).statements(" "));
        NaruStatement first = task.peekStatement().get();
        assertEquals(agent, first.creatorActor(),
                "every statement created for the task carries the actor that authored it");
    }

    @Test
    public void addingAStatementWithAnActorStampsTheCreator() {
        NaruSession s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        NaruActor bob = NaruActor.user(NaruPrincipal.user("bob"));
        NaruStatement nop = NaruStatementHelper.of(NElement.ofObjectBuilder("NOP").build());
        assertNull(nop.creatorActor());
        task.addStatement(nop, bob);
        assertEquals(bob, task.peekStatement().get().creatorActor());
    }

    @Test
    public void effectiveActorPrefersTheStatementCreator() {
        NaruSession s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        NaruActor agent = NaruActor.agent("reviewer", s.user());
        NaruStatement withActor = NaruStatementHelper.of(NElement.ofObjectBuilder("NOP").build())
                .creatorActor(agent);
        NaruStatement without = NaruStatementHelper.of(NElement.ofObjectBuilder("NOP").build());

        assertEquals(agent, task.effectiveActor(withActor));
        assertEquals(task.currentActor(), task.effectiveActor(without),
                "a statement that claims no author is the task's own");
    }

    @Test
    public void taskActorSurvivesAPersistenceRoundTrip() {
        NaruSession s = session();
        NaruActor agent = NaruActor.agent("reviewer", s.user()).withTaskId(7);
        NaruTaskImpl task = (NaruTaskImpl) s.newTask(NaruTaskSpec.of().actor(agent));
        NElement element = task.toElement();
        NaruTaskImpl reloaded = new NaruTaskImpl(element, s);
        assertEquals(agent, reloaded.currentActor());
    }

    @Test
    public void statementActorSurvivesAnElementRoundTrip() {
        NaruActor agent = NaruActor.agent("reviewer", NaruPrincipal.user("alice")).withTaskId(3);
        NaruStatement s = NaruStatementHelper.of(NElement.ofObjectBuilder("NOP").build()).creatorActor(agent);
        NaruStatement back = NaruStatementHelper.of(s.toElement());
        assertEquals(agent, back.creatorActor());
    }

    @Test
    public void builderUserActorReachesTheSession() {
        NaruActor owner = NaruActor.user(NaruPrincipal.user("carol").withGroup("devs"));
        NPath dir = NPath.ofTempFolder("naru-actor-builder-" + System.nanoTime());
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession().directory(dir).userActor(owner).build();
        assertEquals(owner, s.userActor());
        assertEquals("carol", s.user().id());
    }
}
