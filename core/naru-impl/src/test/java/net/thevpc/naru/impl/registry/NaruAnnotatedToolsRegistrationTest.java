package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.annotations.NaruArg;
import net.thevpc.naru.api.annotations.NaruToolSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code registerToolsetProviderType} turns a class's annotated methods into tools of the session.
 */
public class NaruAnnotatedToolsRegistrationTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    public static class Greeter {
        @NaruToolSpec(description = "Greet someone.", tags = "fs")
        public static String greet(@NaruArg(name = "who") String who) {
            return "hello " + who;
        }
    }

    @Test
    public void aRegisteredTypeContributesItsTools() {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-annotated-tools-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession().sessionDir(dir).configureDefaults(false).build();

        s.registry().registerToolsetProviderType(Greeter.class);

        Assertions.assertTrue(s.registry().findTool("greet").isPresent(),
                "tools: " + s.registry().tools().keySet());
        Assertions.assertTrue(s.registry().toolsets().stream()
                .anyMatch(t -> t.id().equals(Greeter.class.getName())));
    }
}
