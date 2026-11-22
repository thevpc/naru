package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruScriptedModelProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extensions do not have to be classes on the classpath: an object registered with
 * {@code NExtensions.registerInstance} is a component too, and sessions find it as they find
 * the classpath ones. That is what lets a host hand over a configured provider.
 */
public class NaruRegisteredInstanceDiscoveryTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruWorkspace workspace(String name) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-instance-" + name + "-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        return agent;
    }

    @Test
    public void aRegisteredProviderInstanceReachesEverySession() {
        // the instance is workspace-wide in nuts, so a name of its own keeps other tests apart
        String name = "registered-instance-" + System.nanoTime();
        NaruScriptedModelProvider provider = new NaruScriptedModelProvider(name).when("ping", "pong");
        NExtensions.of().registerInstance(NaruModelProvider.class, provider);

        NaruWorkspace agent = workspace("provider");
        NaruSession first = agent.newSession().build();
        NaruSession second = agent.newSession().build();

        Assertions.assertSame(provider, first.registry().provider(name).orNull(),
                "providers: " + first.registry().modelProviders().keySet());
        Assertions.assertSame(provider, second.registry().provider(name).orNull());
    }

    /**
     * The workspace class loader is built on first use, and that can be on any thread. Under
     * {@code exec:java} an HTTP worker only sees the JDK's application loader, and a session
     * handle serving a request found no store at all.
     */
    @Test
    public void theClassLoaderIsTheCreatorsWhicheverThreadBuildsIt() throws Exception {
        Thread me = Thread.currentThread();
        ClassLoader previous = me.getContextClassLoader();
        ClassLoader host = new URLClassLoader(new URL[0], previous);
        NaruWorkspace agent;
        me.setContextClassLoader(host);
        try {
            agent = workspace("loader");
        } finally {
            me.setContextClassLoader(previous);
        }

        AtomicReference<ClassLoader> built = new AtomicReference<>();
        Thread worker = new Thread(() -> built.set(agent.classLoader().asClassLoader()));
        worker.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        worker.start();
        worker.join();

        boolean seesHost = false;
        for (ClassLoader c = built.get(); c != null; c = c.getParent()) {
            seesHost |= c == host;
        }
        Assertions.assertTrue(seesHost, "the workspace loader must descend from its creator's, not from "
                + "the thread that first asked for it");
    }
}
